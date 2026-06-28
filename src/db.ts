import * as SQLite from 'expo-sqlite';

import type { MemeRecord, MediaKind, SearchHit, Tag, LinkedFolder, Exemplar } from './types';

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
      indexed_at INTEGER NOT NULL,
      pending INTEGER NOT NULL DEFAULT 0
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
      is_positive INTEGER NOT NULL DEFAULT 1,
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS index_errors (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      kind TEXT NOT NULL,
      stage TEXT NOT NULL,
      reason TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );
  `);
  // Migrate v1 databases that predate the extra_terms column.
  const cols = await db.getAllAsync<{ name: string }>('PRAGMA table_info(memes)');
  if (!cols.some((c) => c.name === 'extra_terms')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN extra_terms TEXT NOT NULL DEFAULT '';`);
  }
  // Migrate v2 databases that predate the pending flag (rows saved-but-not-yet-
  // indexed, so a shared meme can show in the list before it's embedded).
  if (!cols.some((c) => c.name === 'pending')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN pending INTEGER NOT NULL DEFAULT 0;`);
  }
  // Migrate exemplar tables that predate negative ("not this") teaching.
  const exCols = await db.getAllAsync<{ name: string }>('PRAGMA table_info(exemplars)');
  if (!exCols.some((c) => c.name === 'is_positive')) {
    await db.execAsync(`ALTER TABLE exemplars ADD COLUMN is_positive INTEGER NOT NULL DEFAULT 1;`);
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

// True only for memes that are fully indexed. A pending placeholder (saved but
// not yet embedded) does NOT count, so the indexer still picks the file up and
// replaces the placeholder with the real, searchable record.
export async function memeExists(uri: string): Promise<boolean> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ id: number }>(
    'SELECT id FROM memes WHERE uri = ? AND pending = 0',
    uri
  );
  return !!row;
}

export async function deleteMeme(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync('DELETE FROM memes WHERE id = ?', id);
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

// Insert a lightweight placeholder for a freshly-saved meme that hasn't been
// embedded yet, so it appears in the library list immediately. Stamped with the
// current time (sorts to the top of recents) and pending=1 so it's excluded from
// search/training until the indexer fills in its embedding, OCR, and tags. Uses
// INSERT OR IGNORE so it never clobbers an already-indexed row for the same uri.
export async function insertPendingMeme(args: {
  uri: string;
  name: string;
  kind: string;
}): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `INSERT OR IGNORE INTO memes (uri, name, kind, embedding, ocr_text, tags, extra_terms, indexed_at, pending)
     VALUES (?, ?, ?, ?, '', '[]', '', ?, 1)`,
    args.uri,
    args.name,
    args.kind,
    vecToBlob([]),
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
  pending: number;
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
    pending: row.pending === 1,
    embedding: blobToVec(row.embedding),
  };
}

// Re-tagging reuses already-stored embeddings, so applying new knowledge
// (exemplars, association edits) costs no re-embedding.
export async function getAllMemeEmbeddings(): Promise<
  { id: number; embedding: Float32Array; ocrText: string }[]
> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ id: number; embedding: Uint8Array; ocr_text: string }>(
    'SELECT id, embedding, ocr_text FROM memes WHERE pending = 0'
  );
  return rows.map((r) => ({ id: r.id, embedding: blobToVec(r.embedding), ocrText: r.ocr_text ?? '' }));
}

// A random sample of library embeddings, used as the negative/background set
// when training a taught label's classifier head.
export async function getEmbeddingSample(limit = 500): Promise<Float32Array[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ embedding: Uint8Array }>(
    'SELECT embedding FROM memes WHERE pending = 0 ORDER BY RANDOM() LIMIT ?',
    limit
  );
  return rows.map((r) => blobToVec(r.embedding));
}

export async function getMemeEmbedding(id: number): Promise<Float32Array | null> {
  const db = await getDb();
  // Pending placeholders carry an empty embedding; treat them as "no embedding
  // yet" so teaching/confidence don't operate on a zero-length vector.
  const row = await db.getFirstAsync<{ embedding: Uint8Array }>(
    'SELECT embedding FROM memes WHERE id = ? AND pending = 0',
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

// Write tags for many memes in one transaction with a single prepared statement.
// Re-tagging the whole library (retagAll) used to fire one auto-committed UPDATE
// per meme — a separate disk fsync each — which made teaching crawl on large
// libraries. Batching into one commit turns hundreds of fsyncs into one.
export async function bulkUpdateMemeTags(
  updates: { id: number; tags: Tag[]; extraTerms: string }[]
): Promise<void> {
  if (updates.length === 0) return;
  const db = await getDb();
  const stmt = await db.prepareAsync('UPDATE memes SET tags = ?, extra_terms = ? WHERE id = ?');
  try {
    await db.withTransactionAsync(async () => {
      for (const u of updates) {
        await stmt.executeAsync(JSON.stringify(u.tags), u.extraTerms, u.id);
      }
    });
  } finally {
    await stmt.finalizeAsync();
  }
}

function safeParseTags(s: string): Tag[] {
  try {
    return JSON.parse(s) as Tag[];
  } catch {
    return [];
  }
}

export async function countMemes(kind?: MediaKind): Promise<number> {
  const db = await getDb();
  const row = kind
    ? await db.getFirstAsync<{ c: number }>('SELECT COUNT(*) as c FROM memes WHERE kind = ?', kind)
    : await db.getFirstAsync<{ c: number }>('SELECT COUNT(*) as c FROM memes');
  return row?.c ?? 0;
}

// Distinct meme-tag labels actually present across the indexed library, ordered
// by how many memes carry each (most common first). Powers the quick-filter
// chips so a user can narrow to a known format/character without typing it.
export async function getLibraryTagLabels(limit = 40): Promise<string[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ tags: string }>(
    "SELECT tags FROM memes WHERE pending = 0 AND tags != '[]'"
  );
  const counts = new Map<string, number>();
  for (const r of rows) {
    for (const t of safeParseTags(r.tags)) {
      counts.set(t.label, (counts.get(t.label) ?? 0) + 1);
    }
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([label]) => label);
}

// How many memes currently carry a given tag label (used for teach feedback).
export async function countMemesWithLabel(label: string): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    'SELECT COUNT(*) as c FROM memes WHERE tags LIKE ?',
    `%"label":"${label}"%`
  );
  return row?.c ?? 0;
}

export async function getRecentMemes(
  limit = 90,
  offset = 0,
  kind?: MediaKind
): Promise<MemeRecord[]> {
  const db = await getDb();
  // Deliberately does NOT select the embedding blob: the grid only renders
  // thumbnails + metadata, so pulling a 512-float vector per row into JS just to
  // scroll past it wasted megabytes of RAM on a big library — which stuttered
  // the list and competed with the CLIP model loading on first launch. Search
  // and teaching read embeddings on demand (searchByVector / getMemeEmbedding).
  //
  // Tiebreak on id: bulk indexing stamps many rows with the same indexed_at
  // (same millisecond), and without a stable secondary sort LIMIT/OFFSET paging
  // repeats and skips rows — which is what broke infinite scroll.
  const rows = kind
    ? await db.getAllAsync<Omit<MemeRow, 'embedding'>>(
        `SELECT id, uri, name, kind, ocr_text, tags, extra_terms, indexed_at, pending
         FROM memes WHERE kind = ? ORDER BY indexed_at DESC, id DESC LIMIT ? OFFSET ?`,
        kind,
        limit,
        offset
      )
    : await db.getAllAsync<Omit<MemeRow, 'embedding'>>(
        `SELECT id, uri, name, kind, ocr_text, tags, extra_terms, indexed_at, pending
         FROM memes ORDER BY indexed_at DESC, id DESC LIMIT ? OFFSET ?`,
        limit,
        offset
      );
  return rows.map((r) => ({
    id: r.id,
    uri: r.uri,
    name: r.name,
    kind: r.kind as MemeRecord['kind'],
    ocrText: r.ocr_text,
    tags: safeParseTags(r.tags),
    extraTerms: r.extra_terms ?? '',
    indexedAt: r.indexed_at,
    pending: r.pending === 1,
  }));
}

// Brute-force vector search. Fine for thousands of items; swap for sqlite-vec
// if a collection ever gets huge.
export async function searchByVector(
  queryVec: number[],
  queryText: string,
  limit = 40,
  kind?: MediaKind
): Promise<SearchHit[]> {
  const db = await getDb();
  // Pending placeholders have no embedding/OCR/tags yet, so they'd only add
  // noise — leave them out until the indexer fills them in.
  const rows = kind
    ? await db.getAllAsync<MemeRow>('SELECT * FROM memes WHERE pending = 0 AND kind = ?', kind)
    : await db.getAllAsync<MemeRow>('SELECT * FROM memes WHERE pending = 0');
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
      const matched = terms.reduce((acc, t) => acc + (hay.includes(t) ? 1 : 0), 0);
      const lexical = matched / terms.length;
      score += 0.35 * lexical; // lexical boost (incl. world-knowledge association terms)
      // A literal keyword hit (the word actually appears in the meme's text,
      // name, or tags) should outrank pure-semantic near-misses — text/image
      // cosines top out around ~0.35, so this guarantees keyword results
      // surface to the top instead of being buried past the result cap.
      if (matched === terms.length) score += 0.6;
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
  positive?: boolean; // false = "this is NOT a <label>" (negative example)
}): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `INSERT INTO exemplars (label, category, vector, associations, source_uri, is_positive, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    args.label,
    args.category,
    vecToBlob(args.vector),
    JSON.stringify(args.associations),
    args.sourceUri,
    args.positive === false ? 0 : 1,
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
    is_positive: number;
    created_at: number;
  }>('SELECT * FROM exemplars ORDER BY created_at DESC');
  return rows.map((r) => ({
    id: r.id,
    label: r.label,
    category: r.category,
    vector: Array.from(blobToVec(r.vector)),
    associations: safeParseStrings(r.associations),
    sourceUri: r.source_uri,
    positive: r.is_positive !== 0,
    createdAt: r.created_at,
  }));
}

export async function countExemplars(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>('SELECT COUNT(*) as c FROM exemplars');
  return row?.c ?? 0;
}

// Distinct labels the user has taught — used to suggest/reuse labels when
// teaching so they aren't retyped (and fragmented by typos).
export async function getLabels(): Promise<string[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ label: string }>(
    'SELECT DISTINCT label FROM exemplars ORDER BY label COLLATE NOCASE'
  );
  return rows.map((r) => r.label);
}

export async function deleteExemplar(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync('DELETE FROM exemplars WHERE id = ?', id);
}

// ---- indexing errors (diagnostics) ------------------------------------------

export interface IndexError {
  name: string;
  kind: string;
  stage: string;
  reason: string;
}

export async function clearIndexErrors(): Promise<void> {
  const db = await getDb();
  await db.execAsync('DELETE FROM index_errors;');
}

export async function addIndexError(e: IndexError): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    'INSERT INTO index_errors (name, kind, stage, reason, created_at) VALUES (?, ?, ?, ?, ?)',
    e.name,
    e.kind,
    e.stage,
    e.reason,
    Date.now()
  );
}

export async function getIndexErrors(limit = 300): Promise<IndexError[]> {
  const db = await getDb();
  return db.getAllAsync<IndexError>(
    'SELECT name, kind, stage, reason FROM index_errors ORDER BY created_at DESC LIMIT ?',
    limit
  );
}

export async function countIndexErrors(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>('SELECT COUNT(*) as c FROM index_errors');
  return row?.c ?? 0;
}

function safeParseStrings(s: string): string[] {
  try {
    const v = JSON.parse(s);
    return Array.isArray(v) ? v.map(String) : [];
  } catch {
    return [];
  }
}
