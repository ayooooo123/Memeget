import * as SQLite from 'expo-sqlite';

import { modelStamp, PRIMARY_EMBEDDING_MODEL, VISUAL_EMBEDDING_MODEL } from './embeddingModels';
import { hybridSearchScore } from './searchCore';
import type { MemeRecord, MediaKind, SearchHit, Tag, LinkedFolder, Exemplar } from './types';
import { selectPairVectors, type VisualSimilarityRecord } from './visualSearch';

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
      visual_embedding BLOB,
      visual_model TEXT NOT NULL DEFAULT '',
      ocr_text TEXT NOT NULL DEFAULT '',
      caption TEXT NOT NULL DEFAULT '',
      caption_embedding BLOB,
      transcript TEXT NOT NULL DEFAULT '',
      tags TEXT NOT NULL DEFAULT '[]',
      extra_terms TEXT NOT NULL DEFAULT '',
      vision_state TEXT NOT NULL DEFAULT 'pending',
      audio_state TEXT NOT NULL DEFAULT 'none',
      indexed_at INTEGER NOT NULL,
      modified_at INTEGER NOT NULL DEFAULT 0,
      pending INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS folders (
      uri TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      added_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS label_vectors (
      label TEXT PRIMARY KEY,
      model TEXT NOT NULL DEFAULT 'clip-vit-base-patch32',
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
      origin TEXT NOT NULL DEFAULT 'self',
      pack TEXT NOT NULL DEFAULT '',
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
  if (!cols.some((c) => c.name === 'visual_embedding')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN visual_embedding BLOB;`);
  }
  if (!cols.some((c) => c.name === 'visual_model')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN visual_model TEXT NOT NULL DEFAULT '';`);
  }
  // Migrate v2 databases that predate the pending flag (rows saved-but-not-yet-
  // indexed, so a shared meme can show in the list before it's embedded).
  if (!cols.some((c) => c.name === 'pending')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN pending INTEGER NOT NULL DEFAULT 0;`);
  }
  // Migrate databases that predate VLM enrichment (caption + vision_state).
  // Existing rows default to vision_state='pending' so they get picked up by the
  // first "Describe library" run.
  if (!cols.some((c) => c.name === 'caption')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN caption TEXT NOT NULL DEFAULT '';`);
  }
  if (!cols.some((c) => c.name === 'caption_embedding')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN caption_embedding BLOB;`);
  }
  if (!cols.some((c) => c.name === 'vision_state')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN vision_state TEXT NOT NULL DEFAULT 'pending';`);
  }
  // Migrate databases that predate sorting by the file's last-modified time.
  // Seed existing rows from indexed_at so they keep their current relative order
  // until a re-index stamps them with the real file mtime; newly indexed memes
  // get the true file time straight away.
  if (!cols.some((c) => c.name === 'modified_at')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0;`);
    await db.execAsync(`UPDATE memes SET modified_at = indexed_at WHERE modified_at = 0;`);
  }
  // Migrate databases that predate audio transcription (transcript + audio_state).
  // Existing videos start 'pending' so the first transcription pass picks them
  // up; images are 'none' — there is nothing to listen to.
  if (!cols.some((c) => c.name === 'transcript')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN transcript TEXT NOT NULL DEFAULT '';`);
  }
  if (!cols.some((c) => c.name === 'audio_state')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN audio_state TEXT NOT NULL DEFAULT 'none';`);
    await db.execAsync(`UPDATE memes SET audio_state = 'pending' WHERE kind = 'video';`);
  }
  // Migrate databases that predate persisted video posters. Existing videos
  // start '' so the thumbnail backfill picks them up.
  if (!cols.some((c) => c.name === 'thumb_uri')) {
    await db.execAsync(`ALTER TABLE memes ADD COLUMN thumb_uri TEXT NOT NULL DEFAULT '';`);
  }
  // One-time un-stamp (v3: earlier passes stamped 'failed' silently, with no
  // error capture — re-serve those files so the reasons land in diagnostics).
  const thumbRetry = await db.getFirstAsync<{ value: string }>(
    `SELECT value FROM settings WHERE key = 'thumb_retry_v3'`
  );
  if (!thumbRetry) {
    await db.execAsync(`UPDATE memes SET thumb_uri = '' WHERE thumb_uri = 'failed';`);
    await db.runAsync(
      `INSERT OR REPLACE INTO settings (key, value) VALUES ('thumb_retry_v3', '1')`
    );
  }
  // Migrate exemplar tables that predate negative ("not this") teaching.
  const exCols = await db.getAllAsync<{ name: string }>('PRAGMA table_info(exemplars)');
  if (!exCols.some((c) => c.name === 'is_positive')) {
    await db.execAsync(`ALTER TABLE exemplars ADD COLUMN is_positive INTEGER NOT NULL DEFAULT 1;`);
  }
  // Migrate exemplar tables that predate provenance tracking. `origin` is 'self'
  // (you taught it) or 'pack' (imported); `pack` names the source pack so a whole
  // import can be listed and removed as a unit. Existing rows are your own work,
  // so they default to 'self'.
  if (!exCols.some((c) => c.name === 'origin')) {
    await db.execAsync(`ALTER TABLE exemplars ADD COLUMN origin TEXT NOT NULL DEFAULT 'self';`);
  }
  if (!exCols.some((c) => c.name === 'pack')) {
    await db.execAsync(`ALTER TABLE exemplars ADD COLUMN pack TEXT NOT NULL DEFAULT '';`);
  }
  const labelCols = await db.getAllAsync<{ name: string }>('PRAGMA table_info(label_vectors)');
  if (!labelCols.some((c) => c.name === 'model')) {
    await db.execAsync(
      `ALTER TABLE label_vectors ADD COLUMN model TEXT NOT NULL DEFAULT 'clip-vit-base-patch32';`
    );
  }
  // Exemplar vectors live in the primary image space too — stamp them so a
  // primary-model swap can't silently train heads on mixed-space vectors.
  // Existing rows are CLIP-taught.
  if (!exCols.some((c) => c.name === 'model')) {
    await db.execAsync(
      `ALTER TABLE exemplars ADD COLUMN model TEXT NOT NULL DEFAULT 'clip-vit-base-patch32';`
    );
  }
}

// ---- primary-space guard -------------------------------------------------------

// The stamp of the primary model the index was (last) built with. Written on
// every index run; compared against the running app's model so a swapped build
// can't silently search a foreign-space index.
export const INDEX_MODEL_KEY = 'index.primaryModel';

export async function getIndexModelMismatch(): Promise<{ stored: string; current: string } | null> {
  const stored = await getSetting(INDEX_MODEL_KEY);
  const current = modelStamp(PRIMARY_EMBEDDING_MODEL);
  if (!stored || stored === current) return null;
  return { stored, current };
}

export async function stampIndexModel(): Promise<void> {
  await setSetting(INDEX_MODEL_KEY, modelStamp(PRIMARY_EMBEDDING_MODEL));
}

// ---- float32 <-> blob helpers -------------------------------------------------

export function vecToBlob(vec: number[]): Uint8Array {
  return new Uint8Array(Float32Array.from(vec).buffer);
}

export function blobToVec(blob: Uint8Array): Float32Array {
  // View in place when the bytes are already 4-aligned (the driver hands each
  // row its own buffer, so this is safe and free). Otherwise one memcpy via
  // slice(). The old Uint8Array.from() copied byte-by-byte through the iterator
  // protocol — and this runs for every meme on every search scan.
  if (blob.byteOffset % 4 === 0 && blob.byteLength % 4 === 0) {
    return new Float32Array(blob.buffer, blob.byteOffset, blob.byteLength / 4);
  }
  const bytes = blob.slice();
  return new Float32Array(bytes.buffer, 0, Math.floor(bytes.byteLength / 4));
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

// Every fully-indexed URI in one query. The folder scan used to call
// memeExists() once per file — thousands of round-trips on a large library just
// to conclude "nothing new"; with this Set the skip check is a hash lookup.
export async function getIndexedUris(): Promise<Set<string>> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ uri: string }>('SELECT uri FROM memes WHERE pending = 0');
  return new Set(rows.map((r) => r.uri));
}

export async function deleteMeme(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync('DELETE FROM memes WHERE id = ?', id);
}

export async function insertMeme(args: {
  uri: string;
  name: string;
  kind: string;
  embedding: number[]; // already normalized; [] for a degraded row
  visualEmbedding?: number[] | null; // already normalized, optional DINO/S2-side visual space
  visualModel?: string | null;
  ocrText: string;
  tags: Tag[];
  extraTerms: string;
  modifiedAt?: number | null; // file's last-modified time (ms); falls back to now
  // Persisted poster jpeg for a video (the grid can't decode every codec).
  thumbUri?: string | null;
  // A file the index pipeline could not process: stored so it's visible in the
  // grid (not an eternal pending spinner) but excluded from every model pass.
  degraded?: boolean;
}): Promise<void> {
  const db = await getDb();
  const now = Date.now();
  // caption + vision_state use their column defaults ('' / 'pending'); the
  // VLM pass fills them in later via setMemeVision. audio_state queues
  // videos for the transcription pass the same way; images have no audio to
  // analyze. modified_at drives the library's "most recent first" order — it's
  // the file's own last-modified time when we could read it, otherwise the
  // index time so a row is never 0.
  await db.runAsync(
    `INSERT OR REPLACE INTO memes (uri, name, kind, embedding, visual_embedding, visual_model, ocr_text, tags, extra_terms, indexed_at, modified_at, vision_state, audio_state, thumb_uri)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    args.uri,
    args.name,
    args.kind,
    vecToBlob(args.embedding),
    args.visualEmbedding ? vecToBlob(args.visualEmbedding) : null,
    // Degraded rows are pre-stamped visual-failed so the DINO backfill never
    // retries a file the pipeline already couldn't read.
    args.degraded ? visualFailureStamp(VISUAL_EMBEDDING_MODEL.id) : args.visualEmbedding ? (args.visualModel ?? '') : '',
    args.ocrText,
    JSON.stringify(args.tags),
    args.extraTerms,
    now,
    args.modifiedAt ?? now,
    args.degraded ? 'failed' : 'pending',
    args.degraded ? 'none' : args.kind === 'video' ? 'pending' : 'none',
    args.thumbUri ?? ''
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
  // A freshly shared/saved meme is by definition the newest thing in the
  // library, so stamp modified_at = now too; it sorts to the very top and the
  // indexer's later insertMeme replaces it with the file's real mtime.
  const now = Date.now();
  await db.runAsync(
    `INSERT OR IGNORE INTO memes (uri, name, kind, embedding, ocr_text, tags, extra_terms, indexed_at, modified_at, pending, audio_state)
     VALUES (?, ?, ?, ?, '', '[]', '', ?, ?, 1, ?)`,
    args.uri,
    args.name,
    args.kind,
    vecToBlob([]),
    now,
    now,
    args.kind === 'video' ? 'pending' : 'none'
  );
}

interface MemeRow {
  id: number;
  uri: string;
  name: string;
  kind: string;
  embedding: Uint8Array;
  visual_embedding: Uint8Array | null;
  visual_model: string;
  ocr_text: string;
  caption: string;
  caption_embedding: Uint8Array | null;
  transcript: string;
  tags: string;
  extra_terms: string;
  vision_state: string;
  audio_state: string;
  indexed_at: number;
  modified_at: number;
  pending: number;
  thumb_uri: string;
}

// Sentinel stored in thumb_uri when poster extraction failed permanently for a
// video, so the backfill stops re-serving the row (same pattern as the visual-
// embedding failure stamp). rowToRecord hides it from the UI.
export const THUMB_FAILED = 'failed';

function rowToRecord(row: MemeRow): MemeRecord & { embedding: Float32Array } {
  return {
    id: row.id,
    uri: row.uri,
    name: row.name,
    kind: row.kind as MemeRecord['kind'],
    ocrText: row.ocr_text,
    caption: row.caption ?? '',
    transcript: row.transcript ?? '',
    tags: safeParseTags(row.tags),
    extraTerms: row.extra_terms ?? '',
    visionState: (row.vision_state as MemeRecord['visionState']) ?? 'pending',
    audioState: (row.audio_state as MemeRecord['audioState']) ?? 'none',
    indexedAt: row.indexed_at,
    modifiedAt: row.modified_at ?? row.indexed_at,
    pending: row.pending === 1,
    thumbUri: row.thumb_uri && row.thumb_uri !== THUMB_FAILED ? row.thumb_uri : undefined,
    embedding: blobToVec(row.embedding),
  };
}

// Re-tagging reuses already-stored embeddings, so applying new knowledge
// (exemplars, association edits) costs no re-embedding.
export async function getAllMemeEmbeddings(): Promise<
  {
    id: number;
    embedding: Float32Array;
    ocrText: string;
    tags: Tag[];
    rawTags: string; // the stored JSON, kept so callers can diff without re-stringifying
    extraTerms: string;
  }[]
> {
  const db = await getDb();
  const rows = await db.getAllAsync<{
    id: number;
    embedding: Uint8Array;
    ocr_text: string;
    tags: string;
    extra_terms: string;
  }>('SELECT id, embedding, ocr_text, tags, extra_terms FROM memes WHERE pending = 0');
  return rows.map((r) => ({
    id: r.id,
    embedding: blobToVec(r.embedding),
    ocrText: r.ocr_text ?? '',
    tags: safeParseTags(r.tags),
    rawTags: r.tags ?? '[]',
    extraTerms: r.extra_terms ?? '',
  }));
}

// Cheap change-stamp over (taught exemplars, indexed library size), used to
// cache the trained label heads: retraining is the slow part of knowledge
// building, and most builds (indexing a share, opening the teach sheet, a
// background tick) happen when nothing was taught in between. COUNT+SUM(id)
// changes on any add/remove mix; the meme count folds in library growth, which
// shifts the mean/background the heads are trained against.
export async function getKnowledgeVersion(): Promise<string> {
  const db = await getDb();
  // Scoped to the active primary space (stale-space exemplars can't train
  // heads) and prefixed with the model id so a swap always invalidates.
  const row = await db.getFirstAsync<{ ec: number; es: number; mc: number }>(
    `SELECT (SELECT COUNT(*) FROM exemplars WHERE model = ?) AS ec,
            (SELECT COALESCE(SUM(id), 0) FROM exemplars WHERE model = ?) AS es,
            (SELECT COUNT(*) FROM memes WHERE pending = 0) AS mc`,
    PRIMARY_EMBEDDING_MODEL.id,
    PRIMARY_EMBEDDING_MODEL.id
  );
  const counts = row ? `${row.ec}:${row.es}:${row.mc}` : '0:0:0';
  return `${PRIMARY_EMBEDDING_MODEL.id}:${counts}`;
}

// A random sample of library embeddings, used as the negative/background set
// when training a taught label's classifier head.
export async function getEmbeddingSample(limit = 500): Promise<Float32Array[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ embedding: Uint8Array }>(
    'SELECT embedding FROM memes WHERE pending = 0 AND length(embedding) > 0 ORDER BY RANDOM() LIMIT ?',
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

// ---- VLM enrichment ----------------------------------------------------------

// Memes still awaiting (or due to retry) a VLM description. Returns just
// the fields the enricher needs to re-materialize the image and write back.
export interface MemeNeedingVisionRow {
  id: number;
  uri: string;
  name: string;
  kind: 'image' | 'video';
  tags: Tag[];
  ocrText: string;
  embedding: Float32Array; // normalized CLIP image vector (for duplicate-skip)
}

export async function getMemesNeedingVision(limit = 10000): Promise<MemeNeedingVisionRow[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{
    id: number;
    uri: string;
    name: string;
    kind: string;
    tags: string;
    ocr_text: string;
    embedding: Uint8Array;
  }>(
    "SELECT id, uri, name, kind, tags, ocr_text, embedding FROM memes WHERE vision_state = 'pending' ORDER BY indexed_at DESC, id DESC LIMIT ?",
    limit
  );
  return rows.map((r) => ({
    id: r.id,
    uri: r.uri,
    name: r.name,
    kind: r.kind as 'image' | 'video',
    tags: safeParseTags(r.tags),
    ocrText: r.ocr_text ?? '',
    embedding: blobToVec(r.embedding),
  }));
}

// Already-described memes, used as the "twin" set for duplicate-skip: a pending
// meme whose CLIP vector AND OCR text match one of these can copy its result
// instead of running the model again.
export interface DescribedVisionRow {
  embedding: Float32Array;
  ocrText: string;
  caption: string;
  captionEmbedding: Float32Array | null;
  tags: Tag[];
  extraTerms: string;
}

export async function getDescribedVisionRecords(): Promise<DescribedVisionRow[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{
    embedding: Uint8Array;
    ocr_text: string;
    caption: string;
    caption_embedding: Uint8Array | null;
    tags: string;
    extra_terms: string;
  }>(
    "SELECT embedding, ocr_text, caption, caption_embedding, tags, extra_terms FROM memes WHERE vision_state = 'done'"
  );
  return rows.map((r) => ({
    embedding: blobToVec(r.embedding),
    ocrText: r.ocr_text ?? '',
    caption: r.caption ?? '',
    captionEmbedding: r.caption_embedding ? blobToVec(r.caption_embedding) : null,
    tags: safeParseTags(r.tags),
    extraTerms: r.extra_terms ?? '',
  }));
}

// Write the result of a successful description pass: caption + merged tags +
// refreshed search terms, and flip vision_state so it isn't re-described.
export async function setMemeVision(
  id: number,
  args: { caption: string; tags: Tag[]; extraTerms: string; captionEmbedding?: number[] | null }
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE memes SET caption = ?, caption_embedding = ?, tags = ?, extra_terms = ?, vision_state = 'done' WHERE id = ?",
    args.caption,
    args.captionEmbedding ? vecToBlob(args.captionEmbedding) : null,
    JSON.stringify(args.tags),
    args.extraTerms,
    id
  );
}

export interface MemeNeedingCaptionEmbeddingRow {
  id: number;
  caption: string;
  tags: Tag[];
  ocrText: string;
  extraTerms: string;
}

export async function getMemesNeedingCaptionEmbedding(
  limit = 25
): Promise<MemeNeedingCaptionEmbeddingRow[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{
    id: number;
    caption: string;
    tags: string;
    ocr_text: string;
    extra_terms: string;
  }>(
    "SELECT id, caption, tags, ocr_text, extra_terms FROM memes WHERE pending = 0 AND vision_state = 'done' AND caption != '' AND caption_embedding IS NULL ORDER BY indexed_at DESC, id DESC LIMIT ?",
    limit
  );
  return rows.map((r) => ({
    id: r.id,
    caption: r.caption ?? '',
    tags: safeParseTags(r.tags),
    ocrText: r.ocr_text ?? '',
    extraTerms: r.extra_terms ?? '',
  }));
}

export async function setMemeCaptionEmbedding(id: number, embedding: number[]): Promise<void> {
  const db = await getDb();
  await db.runAsync('UPDATE memes SET caption_embedding = ? WHERE id = ?', vecToBlob(embedding), id);
}

export interface MemeNeedingVisualEmbeddingRow {
  id: number;
  uri: string;
  name: string;
  kind: 'image' | 'video';
}

// Failure stamp for the visual backfill: rows whose file can no longer be read
// (deleted, corrupt) get `visual_model = 'failed:<model>'` so the pending query
// stops returning them — otherwise the backfill loop would re-copy and
// re-transcode the same broken files forever. The stamp never matches the
// active model id, so similarity routing still falls back to the image vector,
// and it self-clears if the visual model ever changes.
export function visualFailureStamp(model: string): string {
  return `failed:${model}`;
}

export async function getMemesNeedingVisualEmbedding(
  model = VISUAL_EMBEDDING_MODEL.id,
  limit = 25
): Promise<MemeNeedingVisualEmbeddingRow[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{
    id: number;
    uri: string;
    name: string;
    kind: string;
  }>(
    'SELECT id, uri, name, kind FROM memes WHERE pending = 0 AND (visual_embedding IS NULL OR visual_model != ?) AND visual_model != ? ORDER BY indexed_at DESC, id DESC LIMIT ?',
    model,
    visualFailureStamp(model),
    limit
  );
  return rows.map((r) => ({
    id: r.id,
    uri: r.uri,
    name: r.name,
    kind: r.kind as 'image' | 'video',
  }));
}

// Cheap pending check so the backfill loop can decide whether the (demand-
// loaded) visual model is worth summoning at all.
export async function countMemesNeedingVisualEmbedding(
  model = VISUAL_EMBEDDING_MODEL.id
): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    'SELECT COUNT(*) as c FROM memes WHERE pending = 0 AND (visual_embedding IS NULL OR visual_model != ?) AND visual_model != ?',
    model,
    visualFailureStamp(model)
  );
  return row?.c ?? 0;
}

// Videos whose grid poster hasn't been extracted yet. Excludes THUMB_FAILED
// stamps (same never-re-serve reasoning as the visual backfill) and pending
// placeholders (the indexer will stamp those with a poster itself).
export async function getVideosNeedingThumb(limit = 10): Promise<
  { id: number; uri: string; name: string }[]
> {
  const db = await getDb();
  return db.getAllAsync<{ id: number; uri: string; name: string }>(
    "SELECT id, uri, name FROM memes WHERE kind = 'video' AND pending = 0 AND thumb_uri = '' ORDER BY modified_at DESC, id DESC LIMIT ?",
    limit
  );
}

export async function setMemeThumb(id: number, thumbUri: string): Promise<void> {
  const db = await getDb();
  await db.runAsync('UPDATE memes SET thumb_uri = ? WHERE id = ?', thumbUri, id);
}

// Poster coverage for the Settings diagnostics card: how many videos have a
// poster, how many were stamped undecodable, how many still await one.
export async function getPosterStats(): Promise<{ total: number; done: number; failed: number; missing: number }> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ total: number; done: number; failed: number }>(
    `SELECT COUNT(*) AS total,
            SUM(CASE WHEN thumb_uri != '' AND thumb_uri != ? THEN 1 ELSE 0 END) AS done,
            SUM(CASE WHEN thumb_uri = ? THEN 1 ELSE 0 END) AS failed
     FROM memes WHERE kind = 'video' AND pending = 0`,
    THUMB_FAILED,
    THUMB_FAILED
  );
  const total = row?.total ?? 0;
  const done = row?.done ?? 0;
  const failed = row?.failed ?? 0;
  return { total, done, failed, missing: total - done - failed };
}

// Clear THUMB_FAILED stamps so the backfill re-serves those videos — the
// Settings "Retry failed posters" button, for after an extraction fix lands.
export async function resetFailedThumbs(): Promise<number> {
  const db = await getDb();
  const res = await db.runAsync(`UPDATE memes SET thumb_uri = '' WHERE thumb_uri = ?`, THUMB_FAILED);
  return res.changes ?? 0;
}

// Every live poster path, for the orphan sweep (posters whose meme row was
// deleted or re-indexed away must not pile up in the documents dir forever).
export async function getAllThumbUris(): Promise<Set<string>> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ thumb_uri: string }>(
    `SELECT thumb_uri FROM memes WHERE thumb_uri != '' AND thumb_uri != ?`,
    THUMB_FAILED
  );
  return new Set(rows.map((r) => r.thumb_uri));
}

export async function markVisualEmbeddingFailed(id: number, model: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    'UPDATE memes SET visual_embedding = NULL, visual_model = ? WHERE id = ?',
    visualFailureStamp(model),
    id
  );
}

export async function setMemeVisualEmbedding(
  id: number,
  model: string,
  embedding: number[]
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    'UPDATE memes SET visual_embedding = ?, visual_model = ? WHERE id = ?',
    vecToBlob(embedding),
    model,
    id
  );
}

// Mark a meme as failed-to-describe WITHOUT touching its existing tags/terms,
// so a transient model error doesn't wipe its CLIP/OCR data. Won't auto-retry.
export async function markVisionFailed(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync("UPDATE memes SET vision_state = 'failed' WHERE id = ?", id);
}

export async function countMemesNeedingVision(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    "SELECT COUNT(*) as c FROM memes WHERE vision_state = 'pending'"
  );
  return row?.c ?? 0;
}

export async function countMemesDescribed(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    "SELECT COUNT(*) as c FROM memes WHERE vision_state = 'done'"
  );
  return row?.c ?? 0;
}

// Re-queue everything (failed + already-done) for a fresh description pass —
// e.g. after switching to the higher-quality model.
export async function resetVisionState(): Promise<void> {
  const db = await getDb();
  await db.execAsync("UPDATE memes SET vision_state = 'pending';");
}

// ---- audio transcription -------------------------------------------------------

// Videos still awaiting a transcription pass. Just the fields the transcriber
// needs to materialize the file and write back.
export interface MemeNeedingAudioRow {
  id: number;
  uri: string;
  name: string;
}

export async function getMemesNeedingAudio(limit = 10000): Promise<MemeNeedingAudioRow[]> {
  const db = await getDb();
  return db.getAllAsync<MemeNeedingAudioRow>(
    "SELECT id, uri, name FROM memes WHERE kind = 'video' AND audio_state = 'pending' AND pending = 0 ORDER BY indexed_at DESC, id DESC LIMIT ?",
    limit
  );
}

// Persist a finished analysis. transcript = '' is a valid result — the video
// was listened to and had no audio track / no recognizable speech — and still
// flips audio_state to 'done' so it isn't re-analyzed.
export async function setMemeTranscript(id: number, transcript: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE memes SET transcript = ?, audio_state = 'done' WHERE id = ?",
    transcript,
    id
  );
}

// Mark a video as failed-to-transcribe without touching anything else, so a
// broken file or a transient decoder error doesn't wedge the queue. Won't
// auto-retry; resetAudioFailures re-queues them.
export async function markAudioFailed(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync("UPDATE memes SET audio_state = 'failed' WHERE id = ?", id);
}

export async function countMemesNeedingAudio(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    "SELECT COUNT(*) as c FROM memes WHERE kind = 'video' AND audio_state = 'pending' AND pending = 0"
  );
  return row?.c ?? 0;
}

// Videos analyzed (audio_state 'done'), and how many of those actually carried
// speech — the difference is silent/music-only clips.
export async function countMemesTranscribed(): Promise<{ analyzed: number; withSpeech: number }> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ analyzed: number; withSpeech: number }>(
    "SELECT COUNT(*) as analyzed, SUM(CASE WHEN transcript != '' THEN 1 ELSE 0 END) as withSpeech FROM memes WHERE kind = 'video' AND audio_state = 'done'"
  );
  return { analyzed: row?.analyzed ?? 0, withSpeech: row?.withSpeech ?? 0 };
}

export async function countAudioFailed(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    "SELECT COUNT(*) as c FROM memes WHERE audio_state = 'failed'"
  );
  return row?.c ?? 0;
}

// Re-queue failed videos for another pass (e.g. after fixing storage issues).
export async function resetAudioFailures(): Promise<number> {
  const db = await getDb();
  const res = await db.runAsync(
    "UPDATE memes SET audio_state = 'pending' WHERE audio_state = 'failed'"
  );
  return res.changes ?? 0;
}

// ---- settings (small key/value store) ----------------------------------------

export async function getSetting(key: string): Promise<string | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ value: string }>(
    'SELECT value FROM settings WHERE key = ?',
    key
  );
  return row?.value ?? null;
}

export async function setSetting(key: string, value: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    'INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)',
    key,
    value
  );
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
  // Regex label extraction instead of JSON.parse per row — this runs on every
  // (debounced) library refresh, including once per burst while indexing.
  const counts = new Map<string, number>();
  for (const r of rows) {
    TAG_LABEL_RE.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = TAG_LABEL_RE.exec(r.tags))) {
      // Rare labels containing JSON escapes still decode correctly.
      let label = m[1];
      if (label.includes('\\')) {
        try {
          label = JSON.parse(`"${label}"`);
        } catch {
          // keep the raw capture
        }
      }
      counts.set(label, (counts.get(label) ?? 0) + 1);
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
  // Order by the file's own last-modified time so the most recently added memes
  // surface first, regardless of when we happened to index them (a bulk index
  // stamps the whole library with the same indexed_at, which said nothing about
  // recency). Tiebreak on id: many files can share a modified_at (or fall back
  // to the same index time), and without a stable secondary sort LIMIT/OFFSET
  // paging repeats and skips rows — which is what broke infinite scroll.
  const rows = kind
    ? await db.getAllAsync<Omit<MemeRow, 'embedding' | 'visual_embedding' | 'caption_embedding'>>(
        `SELECT id, uri, name, kind, ocr_text, caption, transcript, tags, extra_terms, vision_state, audio_state, indexed_at, modified_at, pending, thumb_uri
         FROM memes WHERE kind = ? ORDER BY modified_at DESC, id DESC LIMIT ? OFFSET ?`,
        kind,
        limit,
        offset
      )
    : await db.getAllAsync<Omit<MemeRow, 'embedding' | 'visual_embedding' | 'caption_embedding'>>(
        `SELECT id, uri, name, kind, ocr_text, caption, transcript, tags, extra_terms, vision_state, audio_state, indexed_at, modified_at, pending, thumb_uri
         FROM memes ORDER BY modified_at DESC, id DESC LIMIT ? OFFSET ?`,
        limit,
        offset
      );
  return rows.map((r) => ({
    id: r.id,
    uri: r.uri,
    name: r.name,
    kind: r.kind as MemeRecord['kind'],
    ocrText: r.ocr_text,
    caption: r.caption ?? '',
    transcript: r.transcript ?? '',
    tags: safeParseTags(r.tags),
    extraTerms: r.extra_terms ?? '',
    visionState: (r.vision_state as MemeRecord['visionState']) ?? 'pending',
    audioState: (r.audio_state as MemeRecord['audioState']) ?? 'none',
    indexedAt: r.indexed_at,
    modifiedAt: r.modified_at ?? r.indexed_at,
    pending: r.pending === 1,
    thumbUri: r.thumb_uri && r.thumb_uri !== THUMB_FAILED ? r.thumb_uri : undefined,
  }));
}

// Brute-force vector search. Fine for thousands of items; swap for sqlite-vec
// if a collection ever gets huge.
//
// The scoring loop is the single heaviest synchronous JS in the app — a dot
// product over a 512-float vector for every meme, run on each (debounced)
// keystroke. Doing it in one pass froze the UI mid-type on a large library, so
// it now scores in chunks and hands the event loop a macrotask between them,
// keeping typing/scrolling responsive. `shouldAbort` lets the caller cancel an
// in-flight scan the instant a newer query supersedes it (returns null) instead
// of letting stale full scans stack up behind the latest one.
const SEARCH_CHUNK = 512;

// Search haystack straight off the raw row. Tag labels are pulled out of the
// stored JSON with a regex instead of JSON.parse — same label text, none of the
// per-row parse/allocation cost, and no false hits on JSON keys ("category",
// "prompt", …) the way matching against the raw JSON string would give.
const TAG_LABEL_RE = /"label":"((?:[^"\\]|\\.)*)"/g;
function rowSearchText(row: MemeRow): string {
  let labels = '';
  TAG_LABEL_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = TAG_LABEL_RE.exec(row.tags ?? ''))) labels += ' ' + m[1];
  return (
    row.ocr_text +
    ' ' +
    row.name +
    ' ' +
    (row.caption ?? '') +
    ' ' +
    (row.transcript ?? '') +
    labels +
    ' ' +
    (row.extra_terms ?? '')
  ).toLowerCase();
}

// Materialize only the winners: rowToRecord JSON.parses every meme's tags, and
// doing that for the whole library on each (debounced) keystroke was most of
// the search cost. Scoring uses raw columns; the top `limit` rows get parsed.
function materializeHits(scored: { row: MemeRow; score: number }[], limit: number): SearchHit[] {
  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, limit).map(({ row, score }) => {
    const { embedding, ...record } = rowToRecord(row);
    return { ...record, score } as SearchHit;
  });
}

// `queryVec` may be null: lexical-only mode, used to serve instant results
// while the text-embed model is busy behind heavy background work. Scores are
// then purely keyword/OCR/tag/caption-text matches; the caller re-runs with
// the real vector when it arrives.
export async function searchByVector(
  queryVec: number[] | null,
  queryText: string,
  limit = 40,
  kind?: MediaKind,
  shouldAbort?: () => boolean
): Promise<SearchHit[] | null> {
  const db = await getDb();
  // Pending placeholders have no embedding/OCR/tags yet, so they'd only add
  // noise — leave them out until the indexer fills them in.
  const rows = kind
    ? await db.getAllAsync<MemeRow>('SELECT * FROM memes WHERE pending = 0 AND kind = ?', kind)
    : await db.getAllAsync<MemeRow>('SELECT * FROM memes WHERE pending = 0');
  if (shouldAbort?.()) return null;
  let terms = queryText
    .toLowerCase()
    .split(/\s+/)
    .filter((t) => t.length > 2);
  // Lexical-only mode has no dense channel to fall back on; keep short words
  // rather than handing back an unranked list.
  if (!queryVec && terms.length === 0) {
    terms = queryText.toLowerCase().split(/\s+/).filter(Boolean);
  }

  const scored: { row: MemeRow; score: number }[] = [];
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    let score = queryVec
      ? hybridSearchScore(
          queryVec,
          blobToVec(row.embedding),
          row.caption_embedding ? blobToVec(row.caption_embedding) : null
        )
      : 0;
    if (terms.length) {
      const hay = rowSearchText(row);
      let matched = 0;
      for (const t of terms) if (hay.includes(t)) matched++;
      const lexical = matched / terms.length;
      score += 0.35 * lexical; // lexical boost (incl. world-knowledge association terms)
      // A literal keyword hit (the word actually appears in the meme's text,
      // name, or tags) should outrank pure-semantic near-misses — text/image
      // cosines top out around ~0.35, so this guarantees keyword results
      // surface to the top instead of being buried past the result cap.
      if (matched === terms.length) score += 0.6;
    }
    scored.push({ row, score });

    // Yield between chunks so the UI thread can render/handle touch, and bail
    // immediately if a newer query has superseded this one.
    if ((i & (SEARCH_CHUNK - 1)) === SEARCH_CHUNK - 1) {
      if (shouldAbort?.()) return null;
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
  }

  return materializeHits(scored, limit);
}

// "More like this" for the viewer: rank the library by cosine similarity to one
// meme's stored CLIP embedding. Same brute-force scan as text search (and the
// same chunked yielding so opening a meme never hitches the UI), but the query
// vector comes from the image itself — no model call needed, it's all reads.
export async function getSimilarMemes(id: number, limit = 12): Promise<SearchHit[]> {
  const db = await getDb();
  const source = await db.getFirstAsync<{
    embedding: Uint8Array;
    visual_embedding: Uint8Array | null;
    visual_model: string;
  }>('SELECT embedding, visual_embedding, visual_model FROM memes WHERE id = ? AND pending = 0', id);
  if (!source) return [];
  const target: VisualSimilarityRecord = {
    imageEmbedding: blobToVec(source.embedding),
    visualEmbedding: source.visual_embedding ? blobToVec(source.visual_embedding) : null,
    visualModel: source.visual_model ?? '',
  };
  if (target.imageEmbedding.length === 0) return [];
  const rows = await db.getAllAsync<MemeRow>(
    'SELECT * FROM memes WHERE pending = 0 AND id != ?',
    id
  );

  const scored: { row: MemeRow; score: number }[] = [];
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    const candidate: VisualSimilarityRecord = {
      imageEmbedding: blobToVec(row.embedding),
      visualEmbedding: row.visual_embedding ? blobToVec(row.visual_embedding) : null,
      visualModel: row.visual_model ?? '',
    };
    // Space is chosen PER PAIR: DINO only when both sides carry a matching
    // stamped vector, else primary-vs-primary — never a cross-space dot.
    const { a, b } = selectPairVectors(target, candidate, VISUAL_EMBEDDING_MODEL);
    scored.push({ row, score: dot(a, b) }); // cosine (both normalized)
    if ((i & (SEARCH_CHUNK - 1)) === SEARCH_CHUNK - 1) {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
  }

  return materializeHits(scored, limit);
}

export async function clearIndex(): Promise<void> {
  const db = await getDb();
  await db.execAsync('DELETE FROM memes;');
  // An empty index has no space yet — the next index run re-stamps it.
  await db.runAsync('DELETE FROM settings WHERE key = ?', INDEX_MODEL_KEY);
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

export async function getLabelVectors(
  model = PRIMARY_EMBEDDING_MODEL.id
): Promise<Map<string, Float32Array>> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ label: string; vector: Uint8Array }>(
    'SELECT label, vector FROM label_vectors WHERE model = ?',
    model
  );
  const map = new Map<string, Float32Array>();
  for (const r of rows) map.set(r.label, blobToVec(r.vector));
  return map;
}

export async function putLabelVector(
  label: string,
  vec: number[],
  model = PRIMARY_EMBEDDING_MODEL.id
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    'INSERT OR REPLACE INTO label_vectors (label, model, vector) VALUES (?, ?, ?)',
    label,
    model,
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
  // Examples created here are the user's own teaching — origin 'self', no pack.
  // Stamped with the active primary model: the vector only means anything in
  // that space.
  await db.runAsync(
    `INSERT INTO exemplars (label, category, vector, associations, source_uri, is_positive, origin, pack, model, created_at)
     VALUES (?, ?, ?, ?, ?, ?, 'self', '', ?, ?)`,
    args.label,
    args.category,
    vecToBlob(args.vector),
    JSON.stringify(args.associations),
    args.sourceUri,
    args.positive === false ? 0 : 1,
    PRIMARY_EMBEDDING_MODEL.id,
    Date.now()
  );
}

// Only exemplars taught in the ACTIVE primary space — vectors from a previous
// primary model can't train today's heads. Stale-space rows stay stored (and
// visible via the mismatch warning) until re-taught.
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
    origin: string;
    pack: string;
    created_at: number;
  }>('SELECT * FROM exemplars WHERE model = ? ORDER BY created_at DESC', PRIMARY_EMBEDDING_MODEL.id);
  return rows.map((r) => ({
    id: r.id,
    label: r.label,
    category: r.category,
    vector: Array.from(blobToVec(r.vector)),
    associations: safeParseStrings(r.associations),
    sourceUri: r.source_uri,
    positive: r.is_positive !== 0,
    origin: r.origin === 'pack' ? 'pack' : 'self',
    pack: r.pack ?? '',
    createdAt: r.created_at,
  }));
}

// ---- stale-exemplar migration ---------------------------------------------------
//
// After a primary-model swap, exemplars taught under the old model are hidden
// (their vectors mean nothing in the new space) — but every self-taught example
// remembers WHICH meme it came from. Once the library has been re-indexed in
// the new space, that meme's fresh embedding IS what teaching it again would
// store, so the example can be migrated automatically instead of re-taught.
// Pack-imported examples carry no source image (vectors only) and cannot be
// migrated — those need a pack re-exported under the new model.

export async function countStaleExemplars(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    'SELECT COUNT(*) as c FROM exemplars WHERE model != ?',
    PRIMARY_EMBEDDING_MODEL.id
  );
  return row?.c ?? 0;
}

export async function migrateStaleExemplars(): Promise<{ migrated: number; unmigratable: number }> {
  const db = await getDb();
  const stale = await db.getAllAsync<{
    id: number;
    label: string;
    category: string;
    associations: string;
    source_uri: string;
    is_positive: number;
    origin: string;
    pack: string;
  }>(
    'SELECT id, label, category, associations, source_uri, is_positive, origin, pack FROM exemplars WHERE model != ?',
    PRIMARY_EMBEDDING_MODEL.id
  );

  let migrated = 0;
  let unmigratable = 0;
  for (const e of stale) {
    if (!e.source_uri) {
      unmigratable++; // imported pack — no source image to re-embed from
      continue;
    }
    const meme = await db.getFirstAsync<{ embedding: Uint8Array }>(
      'SELECT embedding FROM memes WHERE uri = ? AND pending = 0',
      e.source_uri
    );
    if (!meme || meme.embedding.byteLength === 0) {
      unmigratable++; // source meme gone or not re-indexed yet
      continue;
    }
    // Skip if an equivalent current-space example already exists (e.g. the
    // user re-taught it manually before migrating).
    const dup = await db.getFirstAsync<{ id: number }>(
      'SELECT id FROM exemplars WHERE model = ? AND label = ? AND source_uri = ? AND is_positive = ?',
      PRIMARY_EMBEDDING_MODEL.id,
      e.label,
      e.source_uri,
      e.is_positive
    );
    if (!dup) {
      await db.runAsync(
        `INSERT INTO exemplars (label, category, vector, associations, source_uri, is_positive, origin, pack, model, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        e.label,
        e.category,
        meme.embedding,
        e.associations,
        e.source_uri,
        e.is_positive,
        e.origin,
        e.pack,
        PRIMARY_EMBEDDING_MODEL.id,
        Date.now()
      );
    }
    // The old-space original is superseded either way — drop it so the stale
    // count converges to just the genuinely unmigratable rows.
    await db.runAsync('DELETE FROM exemplars WHERE id = ?', e.id);
    migrated++;
  }
  return { migrated, unmigratable };
}

export async function countExemplars(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    'SELECT COUNT(*) as c FROM exemplars WHERE model = ?',
    PRIMARY_EMBEDDING_MODEL.id
  );
  return row?.c ?? 0;
}

// Distinct labels the user has taught — used to suggest/reuse labels when
// teaching so they aren't retyped (and fragmented by typos).
export async function getLabels(): Promise<string[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ label: string }>(
    'SELECT DISTINCT label FROM exemplars WHERE model = ? ORDER BY label COLLATE NOCASE',
    PRIMARY_EMBEDDING_MODEL.id
  );
  return rows.map((r) => r.label);
}

export async function deleteExemplar(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync('DELETE FROM exemplars WHERE id = ?', id);
}

// Drop every example for a label — i.e. forget a taught tag entirely. The tags
// already written onto memes stay until the next re-tag, when the label simply
// stops matching (no head to train) and falls off.
export async function deleteExemplarsByLabel(label: string): Promise<number> {
  const db = await getDb();
  const res = await db.runAsync('DELETE FROM exemplars WHERE label = ?', label);
  return res.changes ?? 0;
}

// Per-label rollup for the "Taught knowledge" list: how many positive/negative
// examples back each label, and how many memes currently carry it as a tag. One
// pass over exemplars + one pass over meme tags, so it's cheap even with a big
// library.
export interface TaughtLabelStat {
  label: string;
  category: string;
  positives: number;
  negatives: number;
  tagged: number; // memes in the library currently tagged with this label
  fromSelf: boolean; // at least one example you taught yourself
  fromPack: boolean; // at least one example came from an imported pack
  packs: string[]; // distinct source-pack names contributing to this label
}

export async function getTaughtLabelStats(): Promise<TaughtLabelStat[]> {
  const db = await getDb();
  const exRows = await db.getAllAsync<{
    label: string;
    category: string;
    is_positive: number;
    origin: string;
    pack: string;
  }>(
    'SELECT label, category, is_positive, origin, pack FROM exemplars WHERE model = ?',
    PRIMARY_EMBEDDING_MODEL.id
  );
  const tagRows = await db.getAllAsync<{ tags: string }>(
    "SELECT tags FROM memes WHERE pending = 0 AND tags != '[]'"
  );

  const taggedCounts = new Map<string, number>();
  for (const r of tagRows) {
    // A meme can only carry a label once, so count distinct labels per row.
    const seen = new Set<string>();
    for (const t of safeParseTags(r.tags)) seen.add(t.label);
    for (const label of seen) taggedCounts.set(label, (taggedCounts.get(label) ?? 0) + 1);
  }

  const byLabel = new Map<string, TaughtLabelStat & { packSet: Set<string> }>();
  for (const r of exRows) {
    const stat =
      byLabel.get(r.label) ??
      {
        label: r.label,
        category: r.category,
        positives: 0,
        negatives: 0,
        tagged: taggedCounts.get(r.label) ?? 0,
        fromSelf: false,
        fromPack: false,
        packs: [],
        packSet: new Set<string>(),
      };
    if (r.is_positive !== 0) stat.positives += 1;
    else stat.negatives += 1;
    if (r.origin === 'pack') {
      stat.fromPack = true;
      if (r.pack) stat.packSet.add(r.pack);
    } else {
      stat.fromSelf = true;
    }
    byLabel.set(r.label, stat);
  }

  return [...byLabel.values()]
    .map(({ packSet, ...s }) => ({ ...s, packs: [...packSet].sort() }))
    .sort((a, b) => b.tagged - a.tagged || a.label.localeCompare(b.label));
}

// One row per imported pack: which packs are installed and how much each adds.
// Powers the pack-management list (and per-pack removal).
export interface ImportedPack {
  pack: string;
  labels: number; // distinct labels the pack contributes
  examples: number; // total exemplars from the pack
}

export async function getImportedPacks(): Promise<ImportedPack[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ pack: string; label: string }>(
    "SELECT pack, label FROM exemplars WHERE origin = 'pack'"
  );
  const byPack = new Map<string, { labels: Set<string>; examples: number }>();
  for (const r of rows) {
    const name = r.pack || 'Imported pack';
    const g = byPack.get(name) ?? { labels: new Set<string>(), examples: 0 };
    g.labels.add(r.label);
    g.examples += 1;
    byPack.set(name, g);
  }
  return [...byPack.entries()]
    .map(([pack, g]) => ({ pack, labels: g.labels.size, examples: g.examples }))
    .sort((a, b) => a.pack.localeCompare(b.pack));
}

// Remove every example imported from a given pack (your own teaching is left
// untouched). Returns how many exemplars were dropped.
export async function deleteExemplarsByPack(pack: string): Promise<number> {
  const db = await getDb();
  const res = await db.runAsync(
    "DELETE FROM exemplars WHERE origin = 'pack' AND pack = ?",
    pack
  );
  return res.changes ?? 0;
}

// Bulk-insert exemplars from an imported teaching pack, tagging each with its
// source `pack` name and origin 'pack'. Two modes:
//  - 'merge'   (default): keep what you have, add the pack's examples, and skip
//    any that already exist verbatim so re-importing never piles up duplicates.
//  - 'replace': wipe ALL existing exemplars first (yours included) so the
//    library holds exactly this pack — for starting clean from a curated set.
export async function importExemplars(
  list: {
    label: string;
    category: string;
    vector: number[];
    associations: string[];
    positive: boolean;
  }[],
  opts: { pack: string; mode?: 'merge' | 'replace' } = { pack: '' }
): Promise<{ added: number; skipped: number; removed: number }> {
  const db = await getDb();
  const mode = opts.mode ?? 'merge';
  const sig = (label: string, positive: boolean, vec: number[]) =>
    // First few components rounded are a cheap, collision-safe fingerprint for
    // a 512-dim normalized vector; exact equality across devices is unreliable.
    `${label} ${positive ? 1 : 0} ${vec.slice(0, 8).map((v) => v.toFixed(5)).join(',')}`;

  // On replace we drop everything below, so there's nothing to dedupe against.
  const existing = mode === 'replace' ? [] : await getExemplars();
  const seen = new Set(existing.map((e) => sig(e.label, e.positive, e.vector)));

  let added = 0;
  let skipped = 0;
  let removed = 0;
  // Import is gated on pack↔app model compatibility upstream, so imported
  // vectors are by definition in the active primary space.
  const stmt = await db.prepareAsync(
    `INSERT INTO exemplars (label, category, vector, associations, source_uri, is_positive, origin, pack, model, created_at)
     VALUES (?, ?, ?, ?, ?, ?, 'pack', ?, ?, ?)`
  );
  try {
    await db.withTransactionAsync(async () => {
      if (mode === 'replace') {
        const res = await db.runAsync('DELETE FROM exemplars');
        removed = res.changes ?? 0;
      }
      for (const e of list) {
        const s = sig(e.label, e.positive, e.vector);
        if (seen.has(s)) {
          skipped += 1;
          continue;
        }
        seen.add(s);
        await stmt.executeAsync(
          e.label,
          e.category,
          vecToBlob(e.vector),
          JSON.stringify(e.associations),
          '', // imported examples have no local source image
          e.positive ? 1 : 0,
          opts.pack || 'Imported pack',
          PRIMARY_EMBEDDING_MODEL.id,
          Date.now()
        );
        added += 1;
      }
    });
  } finally {
    await stmt.finalizeAsync();
  }
  return { added, skipped, removed };
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
