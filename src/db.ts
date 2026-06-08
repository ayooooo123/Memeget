import * as SQLite from 'expo-sqlite';

import type { MemeRecord, SearchHit, Tag, LinkedFolder } from './types';

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
  `);
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
}): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `INSERT OR REPLACE INTO memes (uri, name, kind, embedding, ocr_text, tags, indexed_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    args.uri,
    args.name,
    args.kind,
    vecToBlob(args.embedding),
    args.ocrText,
    JSON.stringify(args.tags),
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
    indexedAt: row.indexed_at,
    embedding: blobToVec(row.embedding),
  };
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
      const hay = (rec.ocrText + ' ' + rec.name + ' ' + rec.tags.map((t) => t.label).join(' ')).toLowerCase();
      const lexical = terms.reduce((acc, t) => acc + (hay.includes(t) ? 1 : 0), 0) / terms.length;
      score += 0.15 * lexical; // light lexical boost on top of semantic score
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
