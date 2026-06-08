import * as SQLite from 'expo-sqlite';

import type { MemeRecord, SearchHit, Tag, LinkedFolder, Exemplar } from './types';

let dbPromise: Promise<SQLite.SQLiteDatabase> | null = null;

function getDb(): Promise<SQLite.SQLiteDatabase> {
  if (!dbPromise) {
    dbPromise = SQLite.openDatabaseAsync('memeget.db');
  }
  return dbPromise;
}

export async function initDb(): Promise<void> {
  const db = await getDb();
  await db.execAsync(`
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS memes (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      uri TEXT UNIQUE NOT NULL,
      name TEXT NOT NULL,
      kind TEXT NOT NULL,
      embedding BLOB NOT NULL,
      ocr_text TEXT NOT NULL DEFAULT '',
      tags TEXT NOT NULL DEFAULT '[]',
      extra_terms TEXT NOT NULL DEFAULT '',
      indexed_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS folders (
      uri TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      added_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS label_vectors (
      label TEXT PRIMARY KEY,
      vector BLOB NOT NULL
    );
    CREATE TABLE IF NOT EXISTS exemplars (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      label TEXT NOT NULL,
      category TEXT NOT NULL,
      vector BLOB NOT NULL,
      associations TEXT NOT NULL DEFAULT '[]',
      source_uri TEXT NOT NULL DEFAULT '',
      created_at INTEGER NOT NULL
    );
  `);
  // Migrate v1 databases that predate the extra_terms column.
  const cols = await db.getAllAsync<{ name: string }>('PRAGMA table_info(memes)');
  if (!cols.some((c) => c.name === 'extra_terms')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN extra_terms TEXT NOT NULL DEFAULT '';`);
  }
}

// ---- float32 <-> blob helpers -------------------------------------------------

export function vecToBlob(vec: number[]): Uint8Array {
  return new Uint8Array(Float32Array.from(vec).buffer);
}

export function blobToVec(blob: Uint8Array): Float32Array {
  // Copy into a fresh, correctly-aligned buffer before viewing as Float32.
  const bytes = Uint8Array.from(blob);
  return new Float32Array(bytes.buffer);
}

export function normalize(vec: number[]): number[] {
  let norm = 0;
  for (const v of vec) norm += v * v;
  norm = Math.sqrt(norm) || 1;
  return vec.map((v) => v / norm);
}

export function dot(a: Float32Array | number[], b: Float32Array | number[]): number {
  let s = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) s += a[i] * b[i];
  return s;
}

// ---- memes -------------------------------------------------------------------

export async function memeExists(uri: string): Promise<boolean> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ id: number }>('SELECT id FROM memes WHERE uri = ?', uri);
  return !!row;
}

export async function insertMeme(args: {
  uri: string;
  name: string;
  kind: string;
  embedding: number[]; // already normalized
  ocrText: string;
  tags: Tag[];
  extraTerms: string;
}): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `INSERT OR REPLACE INTO memes (uri, name, kind, embedding, ocr_text, tags, extra_terms, indexed_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    args.uri,
    args.name,
    args.kind,
    vecToBlob(args.embedding),
    args.ocrText,
    JSON.stringify(args.tags),
    args.extraTerms,
    Date.now()
  );
}

interface MemeRow {
  id: number;
  uri: string;
  name: string;
  kind: string;
  embedding: Uint8Array;
  ocr_text: string;
  tags: string;
  extra_terms: string;
  indexed_at: number;
}

function rowToRecord(row: MemeRow): MemeRecord & { embedding: Float32Array } {
  return {
    id: row.id,
    uri: row.uri,
    name: row.name,
    kind: row.kind as MemeRecord['kind'],
    ocrText: row.ocr_text,
    tags: safeParseTags(row.tags),
    extraTerms: row.extra_terms ?? '',
    indexedAt: row.indexed_at,
    embedding: blobToVec(row.embedding),
  };
}

// Re-tagging reuses already-stored embeddings, so applying new knowledge
// (exemplars, association edits) costs no re-embedding.
export async function getAllMemeEmbeddings(): Promise<{ id: number; embedding: Float32Array }[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ id: number; embedding: Uint8Array }>(
    'SELECT id, embedding FROM memes'
  );
  return rows.map((r) => ({ id: r.id, embedding: blobToVec(r.embedding) }));
}

export async function getMemeEmbedding(id: number): Promise<Float32Array | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ embedding: Uint8Array }>(
    'SELECT embedding FROM memes WHERE id = ?',
    id
  );
  return row ? blobToVec(row.embedding) : null;
}

export async function updateMemeTags(id: number, tags: Tag[], extraTerms: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    'UPDATE memes SET tags = ?, extra_terms = ? WHERE id = ?',
    JSON.stringify(tags),
    extraTerms,
    id
  );
}

function safeParseTags(s: string): Tag[] {
  try {
    return JSON.parse(s) as Tag[];
  } catch {
    return [];
  }
}

export async function countMemes(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>('SELECT COUNT(*) as c FROM memes');
  return row?.c ?? 0;
}

export async function getRecentMemes(limit = 60): Promise<MemeRecord[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<MemeRow>(
    'SELECT * FROM memes ORDER BY indexed_at DESC LIMIT ?',
    limit
  );
  return rows.map(rowToRecord);
}

// Brute-force vector search. Fine for thousands of items; swap for sqlite-vec
// if a collection ever gets huge.
export async function searchByVector(
  queryVec: number[],
  queryText: string,
  limit = 40
): Promise<SearchHit[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<MemeRow>('SELECT * FROM memes');
  const terms = queryText
    .toLowerCase()
    .split(/\s+/)
    .filter((t) => t.length > 2);

  const hits = rows.map((row) => {
    const rec = rowToRecord(row);
    let score = dot(queryVec, rec.embedding); // cosine (both normalized)
    if (terms.length) {
      const hay = (
        rec.ocrText +
        ' ' +
        rec.name +
        ' ' +
        rec.tags.map((t) => t.label).join(' ') +
        ' ' +
        rec.extraTerms
      ).toLowerCase();
      const lexical = terms.reduce((acc, t) => acc + (hay.includes(t) ? 1 : 0), 0) / terms.length;
      score += 0.2 * lexical; // lexical boost (incl. world-knowledge association terms)
    }
    const { embedding, ...record } = rec;
    return { ...record, score } as SearchHit;
  });

  hits.sort((a, b) => b.score - a.score);
  return hits.slice(0, limit);
}

export async function clearIndex(): Promise<void> {
  const db = await getDb();
  await db.execAsync('DELETE FROM memes;');
}

// ---- folders -----------------------------------------------------------------

export async function addFolder(uri: string, name: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    'INSERT OR REPLACE INTO folders (uri, name, added_at) VALUES (?, ?, ?)',
    uri,
    name,
    Date.now()
  );
}

export async function getFolders(): Promise<LinkedFolder[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ uri: string; name: string; added_at: number }>(
    'SELECT * FROM folders ORDER BY added_at DESC'
  );
  return rows.map((r) => ({ uri: r.uri, name: r.name, addedAt: r.added_at }));
}

export async function removeFolder(uri: string): Promise<void> {
  const db = await getDb();
  await db.runAsync('DELETE FROM folders WHERE uri = ?', uri);
}

// ---- cached label vectors ----------------------------------------------------

export async function getLabelVectors(): Promise<Map<string, Float32Array>> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ label: string; vector: Uint8Array }>(
    'SELECT * FROM label_vectors'
  );
  const map = new Map<string, Float32Array>();
  for (const r of rows) map.set(r.label, blobToVec(r.vector));
  return map;
}

export async function putLabelVector(label: string, vec: number[]): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    'INSERT OR REPLACE INTO label_vectors (label, vector) VALUES (?, ?)',
    label,
    vecToBlob(vec)
  );
}

// ---- exemplars (teach-by-example) --------------------------------------------

export async function addExemplar(args: {
  label: string;
  category: string;
  vector: number[]; // normalized image embedding
  associations: string[];
  sourceUri: string;
}): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `INSERT INTO exemplars (label, category, vector, associations, source_uri, created_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
    args.label,
    args.category,
    vecToBlob(args.vector),
    JSON.stringify(args.associations),
    args.sourceUri,
    Date.now()
  );
}

export async function getExemplars(): Promise<Exemplar[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{
    id: number;
    label: string;
    category: string;
    vector: Uint8Array;
    associations: string;
    source_uri: string;
    created_at: number;
  }>('SELECT * FROM exemplars ORDER BY created_at DESC');
  return rows.map((r) => ({
    id: r.id,
    label: r.label,
    category: r.category,
    vector: Array.from(blobToVec(r.vector)),
    associations: safeParseStrings(r.associations),
    sourceUri: r.source_uri,
    createdAt: r.created_at,
  }));
}

export async function countExemplars(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>('SELECT COUNT(*) as c FROM exemplars');
  return row?.c ?? 0;
}

export async function deleteExemplar(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync('DELETE FROM exemplars WHERE id = ?', id);
}

function safeParseStrings(s: string): string[] {
  try {
    const v = JSON.parse(s);
    return Array.isArray(v) ? v.map(String) : [];
  } catch {
    return [];
  }
}
