import * as SQLite from 'expo-sqlite';

import { AUDIO_MODEL_INFO } from './audioCore';

import { modelStamp, PRIMARY_EMBEDDING_MODEL, VISUAL_EMBEDDING_MODEL } from './embeddingModels';
import { scoreEntry } from './searchCore';
import {
  assembleSearchText,
  classificationContextTerms,
  containsPhrase,
  phraseKey,
  phraseTokenCount,
} from './searchText';
import { guessFacet } from './facetCoverage';
import { INSERT_MEME_SQL, MEMES_TABLE_SQL, RESTORE_SIDECAR_MEME_SQL } from './memeSql';
import { MEME_SEARCH_FTS_DDL, MEME_SEARCH_FTS_INSERT, MEME_SEARCH_FTS_QUERY } from './memeFtsSql';
import { hashText } from './contentHash';
// Knowledge mutations announce themselves here so the `.memeget` sidecar backup
// picks them up. Emitting from the write helpers rather than the screens means
// no UI path — present or future — can silently skip the backup.
import { emitKnowledgeChanged } from './events';
import {
  ensureSearchIndex,
  invalidateSearchIndex as invalidateResidentSearchIndex,
  patchSearchIndexEntries,
  peekSearchIndex,
  type SearchCacheEntry,
} from './searchIndexCache';
import {
  ftsMatchQuery,
  fuseDenseAndLexicalRanks,
  lexicalRankQuery,
  searchTermsForText,
  searchScopeEntries,
  tagTermScore,
  type LexicalQuery,
} from './searchExpansion';
import { rankPropagationHits, scorePropagationCandidate, type PropagationHit } from './tagPropagation';
import { upsertDurableTag } from './tagMerge';
import type { MemeRecord, MediaKind, SearchHit, Tag, LinkedFolder, Exemplar } from './types';
import { selectPairVectors, type VisualSimilarityRecord } from './visualSearch';

let dbPromise: Promise<SQLite.SQLiteDatabase> | null = null;

// Whether the bundled sqlite-vec extension loaded AND its scalar cosine is
// callable. Everything that uses it degrades gracefully to the JS brute-force
// path when this is false — the extension is a speedup, never a requirement, so
// a build without it (plugin not enabled, a platform without the .so) still
// works identically, just slower on very large libraries.
let vecReady = false;
export function sqliteVecReady(): boolean {
  return vecReady;
}

let ftsAvailable: boolean | null = null;
// Content version: bumped on every searchable-content change. The FTS index
// records the version it was last built at (`ftsBuiltVersion`); it is usable
// ONLY when the two match. This is what keeps a rebuild that raced a write from
// ever being trusted — see ensureFtsSearchIndex / scheduleFtsRebuild.
let contentVersion = 0;
let ftsBuiltVersion = -1;
let ftsRebuilding = false;

function invalidateSearchIndex(): void {
  invalidateResidentSearchIndex();
  contentVersion++;
}

function getDb(): Promise<SQLite.SQLiteDatabase> {
  if (!dbPromise) {
    dbPromise = (async () => {
      const db = await SQLite.openDatabaseAsync('memeget.db');
      await tryLoadVecExtension(db);
      return db;
    })();
  }
  return dbPromise;
}

// Best-effort one-time load of the bundled sqlite-vec extension. Enabled at
// build time via the expo-sqlite config plugin (`withSQLiteVecExtension`, see
// app.json); absent otherwise. We probe the scalar function after loading so a
// half-configured build is treated as "no vec" rather than crashing the first
// similarity query.
async function tryLoadVecExtension(db: SQLite.SQLiteDatabase): Promise<void> {
  try {
    const bundled = (SQLite as unknown as {
      bundledExtensions?: Record<string, { libPath: string; entryPoint?: string }>;
    }).bundledExtensions;
    const ext = bundled?.['sqlite-vec'];
    if (!ext) return;
    const loadable = db as unknown as {
      loadExtensionAsync?: (libPath: string, entryPoint?: string) => Promise<void>;
    };
    if (typeof loadable.loadExtensionAsync !== 'function') return;
    await loadable.loadExtensionAsync(ext.libPath, ext.entryPoint);
    // Probe: the extension is only "ready" if the scalar cosine actually runs.
    await db.getFirstAsync(
      'SELECT vec_distance_cosine(vec_f32(?), vec_f32(?)) AS d',
      vecToBlob([1, 0]),
      vecToBlob([1, 0])
    );
    vecReady = true;
  } catch {
    // Extension unavailable or unusable — stay on the JS brute-force path.
    vecReady = false;
  }
}

export async function initDb(): Promise<void> {
  const db = await getDb();
  await db.execAsync(`
    PRAGMA journal_mode = WAL;
    ${MEMES_TABLE_SQL};
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
    -- Content fingerprints of saved memes, so a re-shared (or OS-redelivered)
    -- meme with identical bytes is skipped instead of landing as a duplicate.
    -- Kept in its own table (not a memes column) so the indexer's row-replacing
    -- insertMeme can never blank it. A new CREATE IF NOT EXISTS doubles as the
    -- migration for existing databases.
    CREATE TABLE IF NOT EXISTS content_hashes (
      hash TEXT PRIMARY KEY,
      uri TEXT NOT NULL
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
  // One-time re-extract (v4): every earlier poster was a fixed t=1s grab —
  // black for any clip with a fade-in — and gif-named video files never got
  // one at all. Clear ALL poster state (good, missing, and failed) so the
  // luma-checked native extractor redoes the lot; the orphaned jpegs are
  // reclaimed by the next index run's sweep.
  const thumbRetry = await db.getFirstAsync<{ value: string }>(
    `SELECT value FROM settings WHERE key = 'thumb_retry_v4'`
  );
  if (!thumbRetry) {
    await db.execAsync(
      `UPDATE memes SET thumb_uri = '' WHERE kind = 'video' OR (kind = 'image' AND lower(name) LIKE '%.gif');`
    );
    await db.runAsync(
      `INSERT OR REPLACE INTO settings (key, value) VALUES ('thumb_retry_v4', '1')`
    );
  }
  // One-time re-queue (audio v1): the first Moonshine decoder read its output
  // tensor as a raw ArrayBuffer instead of a typed array, so every clip
  // "transcribed" to nothing (argmax always landed on token 0) yet was still
  // marked done. Re-queue every already-analyzed video so the fixed decoder
  // re-transcribes the library once; genuinely silent clips just come back empty.
  const audioRequeue = await db.getFirstAsync<{ value: string }>(
    `SELECT value FROM settings WHERE key = 'audio_requeue_v1'`
  );
  if (!audioRequeue) {
    await db.execAsync(
      `UPDATE memes SET audio_state = 'pending', transcript = '' WHERE kind = 'video' AND audio_state IN ('done', 'failed');`
    );
    await db.runAsync(
      `INSERT OR REPLACE INTO settings (key, value) VALUES ('audio_requeue_v1', '1')`
    );
  }
  // One-time re-queue (audio v2): ~1/3 of the library came back with an empty
  // transcript ('done' + transcript = '') and some clips that plainly contain
  // speech ("the female cop clip") were among them. We can't yet tell from here
  // whether those empties are genuine silence, over-aggressive transcript
  // cleanup, or the decoder emitting EOS immediately — so re-queue exactly the
  // suspect set (empty 'done' videos + 'failed' ones) for a pass that now logs
  // audio level + raw-vs-cleaned text per clip (see transcribeOne). Videos that
  // really are silent just come back empty again cheaply; the logs classify the
  // rest. Untouched: 'done' videos that already carry a transcript.
  const audioRequeue2 = await db.getFirstAsync<{ value: string }>(
    `SELECT value FROM settings WHERE key = 'audio_requeue_v2'`
  );
  if (!audioRequeue2) {
    await db.execAsync(
      `UPDATE memes SET audio_state = 'pending', transcript = '' WHERE kind = 'video' AND (audio_state = 'failed' OR (audio_state = 'done' AND transcript = ''));`
    );
    await db.runAsync(
      `INSERT OR REPLACE INTO settings (key, value) VALUES ('audio_requeue_v2', '1')`
    );
  }
  // Model cutover: Moonshine's unsupported generic-module decoder returned empty
  // text for nearly the whole real library. Requeue every indexed video once for
  // the supported native Whisper runner, including rows Moonshine marked done.
  const whisperRequeue = await db.getFirstAsync<{ value: string }>(
    'SELECT value FROM settings WHERE key = ?',
    AUDIO_MODEL_INFO.requeueKey
  );
  if (!whisperRequeue) {
    await db.execAsync(
      `UPDATE memes SET audio_state = 'pending', transcript = '' WHERE kind = 'video' AND pending = 0;`
    );
    invalidateSearchIndex();
    await db.runAsync(
      `INSERT OR REPLACE INTO settings (key, value) VALUES (?, '1')`,
      AUDIO_MODEL_INFO.requeueKey
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
  // Tag labels are stored lower-case everywhere (normalizeTags), but taught
  // exemplar labels were persisted verbatim. A "Milady" example therefore
  // produced a "milady" tag it could never be matched back to, so every taught
  // tag in Settings reported "0 memes tagged". Fold existing rows into the same
  // normal form once; writes are normalized at the boundary from here on.
  const exLower = await db.getFirstAsync<{ value: string }>(
    `SELECT value FROM settings WHERE key = 'exemplars_lowercase_v1'`
  );
  if (!exLower) {
    await db.execAsync(
      `UPDATE exemplars SET label = lower(trim(label)) WHERE label != lower(trim(label));`
    );
    await db.runAsync(
      `INSERT OR REPLACE INTO settings (key, value) VALUES ('exemplars_lowercase_v1', '1')`
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

// Placeholder rows from share-imports whose immediate index failed (model was
// loading, app was killed…). The next full index finishes them — and should do
// so FIRST: these are the newest memes, sorted to the top of the library,
// showing eternal spinners until their real row lands.
export async function getPendingUris(): Promise<Set<string>> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ uri: string }>('SELECT uri FROM memes WHERE pending = 1');
  return new Set(rows.map((r) => r.uri));
}

// Full pending rows, for the recovery sweep that re-indexes them without
// waiting for the user to run a manual Index.
export async function getPendingMemes(): Promise<
  { id: number; uri: string; name: string; kind: string }[]
> {
  const db = await getDb();
  return db.getAllAsync<{ id: number; uri: string; name: string; kind: string }>(
    'SELECT id, uri, name, kind FROM memes WHERE pending = 1 ORDER BY id DESC'
  );
}

export async function countPendingMemes(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    'SELECT COUNT(*) as c FROM memes WHERE pending = 1'
  );
  return row?.c ?? 0;
}

export async function deleteMeme(id: number): Promise<void> {
  const db = await getDb();
  // Drop the content fingerprint first so re-sharing this exact meme later
  // isn't wrongly rejected as a duplicate of a row that no longer exists.
  await db.runAsync('DELETE FROM content_hashes WHERE uri IN (SELECT uri FROM memes WHERE id = ?)', id);
  await db.runAsync('DELETE FROM memes WHERE id = ?', id);
  invalidateSearchIndex(); // membership changed
}

// Record a saved meme's content fingerprint → its URI. Called right after a
// shared file is written into a linked folder.
export async function recordContentHash(hash: string, uri: string): Promise<void> {
  const db = await getDb();
  await db.runAsync('INSERT OR REPLACE INTO content_hashes (hash, uri) VALUES (?, ?)', hash, uri);
}

// Look up the URI a content fingerprint belongs to, but only if that meme still
// exists (the JOIN self-heals a stale mapping a delete somehow missed — so a
// user can always re-add a meme they previously removed). Returns null when the
// bytes aren't already in the library.
export async function findContentHashUri(hash: string): Promise<string | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ uri: string }>(
    `SELECT ch.uri AS uri FROM content_hashes ch
     JOIN memes m ON m.uri = ch.uri
     WHERE ch.hash = ? LIMIT 1`,
    hash
  );
  return row?.uri ?? null;
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
  //
  // On conflict this refreshes the index-derived columns but deliberately does
  // NOT touch caption, caption_embedding, or transcript — they're absent from
  // the SET list, so they survive. This used to be INSERT OR REPLACE, which
  // rebuilt the row from scratch and silently discarded every VLM description
  // and transcript the moment a file was re-indexed (a cleared index, a
  // re-scan, or a sidecar restore handing the indexer a row it already knows).
  // Those are the most expensive things the app ever computes; an index pass
  // has nothing newer to say about them.
  await db.runAsync(
    INSERT_MEME_SQL,
    args.uri,
    args.name,
    args.kind,
    vecToBlob(args.embedding),
    args.visualEmbedding ? vecToBlob(args.visualEmbedding) : null,
    // Degraded rows are pre-stamped visual-failed so the DINO backfill never
    // retries a file the pipeline already couldn't read.
    args.degraded ? visualFailureStamp(VISUAL_EMBEDDING_MODEL.id) : args.visualEmbedding ? (args.visualModel ?? '') : '',
    args.ocrText,
    JSON.stringify(normalizeTags(args.tags)),
    args.extraTerms,
    now,
    args.modifiedAt ?? now,
    args.degraded ? 'failed' : 'pending',
    args.degraded ? 'none' : args.kind === 'video' ? 'pending' : 'none',
    args.thumbUri ?? ''
  );
  invalidateSearchIndex(); // a searchable row was added or replaced
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
export async function countMemesNeedingEmbeddings(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(`SELECT COUNT(*) AS c FROM memes WHERE pending = 0`);
  return row?.c ?? 0;
}

export async function* eachMemeEmbedding(): AsyncGenerator<
  {
    id: number;
    embedding: Float32Array;
    ocrText: string;
    tags: Tag[];
    rawTags: string; // the stored JSON, kept so callers can diff without re-stringifying
    extraTerms: string;
  }
> {
  const db = await getDb();
  const stmt = await db.prepareAsync('SELECT id, embedding, ocr_text, tags, extra_terms FROM memes WHERE pending = 0');
  try {
    const result = await stmt.executeAsync<{
      id: number;
      embedding: Uint8Array;
      ocr_text: string;
      tags: string;
      extra_terms: string;
    }>();
    for await (const r of result) {
      yield {
        id: r.id,
        embedding: blobToVec(r.embedding),
        ocrText: r.ocr_text ?? '',
        tags: safeParseTags(r.tags),
        rawTags: r.tags ?? '[]',
        extraTerms: r.extra_terms ?? '',
      };
    }
  } finally {
    await stmt.finalizeAsync();
  }
}

export async function getAllMemeEmbeddings(): Promise<
  {
    id: number;
    embedding: Float32Array;
    ocrText: string;
    tags: Tag[];
    rawTags: string;
    extraTerms: string;
  }[]
> {
  const rows = [];
  for await (const row of eachMemeEmbedding()) {
    rows.push(row);
  }
  return rows;
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
  // Hand-applied tags train heads too (see getManualTagVectors), so a bulk tag
  // added or renamed has to invalidate the cached heads just like a teach does.
  return `${PRIMARY_EMBEDDING_MODEL.id}:${counts}:${await getManualTagStamp()}`;
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
  const normalized = normalizeTags(tags);
  await db.runAsync(
    'UPDATE memes SET tags = ?, extra_terms = ? WHERE id = ?',
    JSON.stringify(normalized),
    extraTerms,
    id
  );
  if (!patchTagSearchCache([{ id, tags: normalized, extraTerms }])) invalidateResidentSearchIndex();
  contentVersion++; // resident cache patched in place; FTS must still rebuild
  emitKnowledgeChanged();
}

// Write tags for many memes in one transaction with a single prepared statement.
// Re-tagging the whole library (retagAll) used to fire one auto-committed UPDATE
// per meme — a separate disk fsync each — which made teaching crawl on large
// libraries. Batching into one commit turns hundreds of fsyncs into one.
export async function bulkUpdateMemeTags(
  updates: { id: number; tags: Tag[]; extraTerms: string }[]
): Promise<void> {
  if (updates.length === 0) return;
  const normalized = updates.map((u) => ({ ...u, tags: normalizeTags(u.tags) }));
  const db = await getDb();
  const stmt = await db.prepareAsync('UPDATE memes SET tags = ?, extra_terms = ? WHERE id = ?');
  try {
    await db.withTransactionAsync(async () => {
      for (const u of normalized) {
        await stmt.executeAsync(JSON.stringify(u.tags), u.extraTerms, u.id);
      }
    });
  } finally {
    await stmt.finalizeAsync();
  }
  if (!patchTagSearchCache(normalized)) invalidateResidentSearchIndex();
  contentVersion++; // resident cache patched in place; FTS must still rebuild
  emitKnowledgeChanged();
}

// ---- VLM enrichment ----------------------------------------------------------

// Memes still awaiting a VLM description. Placeholder rows (pending = 1) are
// excluded: they have no embedding yet, so the duplicate-skip twin match would
// run against a zero vector and their file may not even be indexable yet.
// Returns just the fields the enricher needs to re-materialize the image and
// write back.
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
    `SELECT id, uri, name, kind, tags, ocr_text, embedding FROM memes
     WHERE vision_state = 'pending' AND pending = 0 AND length(embedding) > 0
     ORDER BY indexed_at DESC, id DESC LIMIT ?`,
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
    JSON.stringify(normalizeTags(args.tags)),
    args.extraTerms,
    id
  );
  invalidateSearchIndex(); // caption + caption vector + tags all feed search
  emitKnowledgeChanged();
}

// Requeue one meme for description: flip vision_state back to 'pending' and drop
// its vision-derived output (caption + caption vector) so the background enrich
// loop — or a manual burst — runs the model on it again. The per-meme "re-caption"
// button in the viewer. Non-vision tags (CLIP/OCR/exemplar) are kept; enrichOne
// re-merges vision tags on top of them. Dropping vision_state to 'pending' also
// shrinks the described-count the twin cache is keyed on, so it self-heals.
export async function requeueMemeVision(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE memes SET vision_state = 'pending', caption = '', caption_embedding = NULL WHERE id = ?",
    id
  );
  invalidateSearchIndex(); // the stale caption + its vector leave the haystack now
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
  invalidateSearchIndex(); // caption vector powers the hybrid text↔text channel
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

// Rows whose grid thumbnail hasn't been generated yet: every video (which needs
// a decoded poster frame), AND every image. Images used to render straight off
// the full-resolution original in the grid — a multi-megapixel JPEG/PNG decoded
// down to a ~130px cell, re-decoded off disk every time a memory-cached bitmap
// was evicted on scroll. That full-res decode is the "thumbnails are slow" jank.
// Now each image also gets a small persisted thumb (indexer.ts) the grid loads
// instead of the original. mp4-as-gif files land here as kind 'image' too; the
// backfill tries the image path first and falls back to the video decoder for
// them. Excludes THUMB_FAILED stamps (same never-re-serve reasoning as the
// visual backfill) and pending placeholders (indexing finishes those first).
const NEEDS_THUMB_WHERE = `pending = 0 AND thumb_uri = '' AND kind IN ('video', 'image')`;

export async function getVideosNeedingThumb(limit = 10): Promise<
  { id: number; uri: string; name: string; kind: 'image' | 'video' }[]
> {
  const db = await getDb();
  return db.getAllAsync<{ id: number; uri: string; name: string; kind: 'image' | 'video' }>(
    `SELECT id, uri, name, kind FROM memes WHERE ${NEEDS_THUMB_WHERE} ORDER BY modified_at DESC, id DESC LIMIT ?`,
    limit
  );
}

export async function setMemeThumb(id: number, thumbUri: string): Promise<void> {
  const db = await getDb();
  await db.runAsync('UPDATE memes SET thumb_uri = ? WHERE id = ?', thumbUri, id);
}

// Requeue one video for poster extraction: clear its thumb_uri (also lifting any
// THUMB_FAILED stamp) so it matches NEEDS_THUMB_WHERE again and the poster loop
// regenerates it. The per-meme "refresh poster" button. The old poster file, if
// any, is reclaimed by the orphan sweep (getAllThumbUris no longer lists it).
export async function requeueMemeThumb(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync("UPDATE memes SET thumb_uri = '' WHERE id = ?", id);
}

// Poster coverage for the Settings diagnostics card: how many poster-needing
// rows (videos + gif-named video files) have one, how many were stamped
// undecodable, how many still await one.
export async function getPosterStats(): Promise<{ total: number; done: number; failed: number; missing: number }> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ total: number; done: number; failed: number }>(
    `SELECT COUNT(*) AS total,
            SUM(CASE WHEN thumb_uri != '' AND thumb_uri != ? THEN 1 ELSE 0 END) AS done,
            SUM(CASE WHEN thumb_uri = ? THEN 1 ELSE 0 END) AS failed
     FROM memes WHERE pending = 0 AND (
       kind = 'video'
       OR (kind = 'image' AND lower(name) LIKE '%.gif' AND (embedding IS NULL OR length(embedding) = 0))
     )`,
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

// Describable memes still queued: 'pending' state, already indexed. Mirrors
// getMemesNeedingVision exactly, so the count can never promise work the queue
// won't hand out.
export async function countMemesNeedingVision(): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    "SELECT COUNT(*) as c FROM memes WHERE vision_state = 'pending' AND pending = 0 AND length(embedding) > 0"
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
// e.g. after switching to the higher-quality model. Degraded rows (no
// embedding: the indexer could never read the file) stay 'failed'; the model
// has nothing to read either, so re-queueing them only pads the queue.
export async function resetVisionState(): Promise<void> {
  const db = await getDb();
  await db.execAsync("UPDATE memes SET vision_state = 'pending' WHERE length(embedding) > 0;");
}

// Every meme in the library, split by what the describe pass can do with it.
// The buckets partition the table, so `total` is the honest denominator for
// "Described x / y" — done + queued alone silently drops failures, degraded
// files, and rows still being indexed, which is what made the number wrong.
export interface VisionStats {
  total: number; // every row in the library
  described: number; // has a caption
  queued: number; // ready for the model right now
  indexing: number; // placeholder rows — describable once indexed
  failed: number; // the model errored; retryable
  unsupported: number; // the indexer could not read the file at all
}

export async function getVisionStats(): Promise<VisionStats> {
  const db = await getDb();
  const row = await db.getFirstAsync<VisionStats>(
    `SELECT COUNT(*) AS total,
            COALESCE(SUM(vision_state = 'done'), 0) AS described,
            COALESCE(SUM(pending = 1), 0) AS indexing,
            COALESCE(SUM(pending = 0 AND vision_state = 'pending' AND length(embedding) > 0), 0) AS queued,
            COALESCE(SUM(pending = 0 AND vision_state = 'failed' AND length(embedding) > 0), 0) AS failed,
            COALESCE(SUM(pending = 0 AND vision_state != 'done' AND length(embedding) = 0), 0) AS unsupported
     FROM memes`
  );
  return (
    row ?? { total: 0, described: 0, queued: 0, indexing: 0, failed: 0, unsupported: 0 }
  );
}

// Re-queue memes the model errored on. Degraded rows (empty embedding — the
// indexer could never read the file) share the 'failed' state but are NOT
// retryable: re-queueing them would fail again on every pass forever.
export async function resetVisionFailures(): Promise<number> {
  const db = await getDb();
  const res = await db.runAsync(
    "UPDATE memes SET vision_state = 'pending' WHERE vision_state = 'failed' AND pending = 0 AND length(embedding) > 0"
  );
  return res.changes ?? 0;
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

// One video's transcriber inputs by id — for the per-meme "regenerate
// transcription" action, which retranscribes a single clip rather than draining
// the whole pending queue. Null when the id isn't a (non-pending) video.
export async function getMemeForAudio(id: number): Promise<MemeNeedingAudioRow | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<MemeNeedingAudioRow>(
    "SELECT id, uri, name FROM memes WHERE id = ? AND kind = 'video' AND pending = 0",
    id
  );
  return row ?? null;
}

// The current stored transcript for one meme — lets the viewer refresh its open
// snapshot in place after a per-meme retranscription (the row in `selected` is a
// copy, not re-fetched automatically). '' for a silent clip or a missing row.
export async function getMemeTranscript(id: number): Promise<string> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ transcript: string }>(
    'SELECT transcript FROM memes WHERE id = ?',
    id
  );
  return row?.transcript ?? '';
}

// Re-queue ONE video for transcription: drop its stale transcript (so the old
// terms leave search immediately) and flip it back to 'pending'. The caller
// (AudioApi.regenerateMeme) runs the single-clip pass right after.
export async function requeueMemeAudio(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE memes SET audio_state = 'pending', transcript = '' WHERE id = ? AND kind = 'video'",
    id
  );
  invalidateSearchIndex(); // the cleared transcript is part of the lexical haystack
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
  invalidateSearchIndex(); // transcript is part of the lexical haystack
  emitKnowledgeChanged();
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

// Re-queue EVERY indexed video for a fresh transcription pass and drop their
// stale transcripts — for the settings "Regenerate all transcriptions" action
// (e.g. after a model change). Covers already-pending rows too so no video keeps
// stale transcript text. Returns the number re-queued.
export async function resetAllAudio(): Promise<number> {
  const db = await getDb();
  const res = await db.runAsync(
    "UPDATE memes SET audio_state = 'pending', transcript = '' WHERE kind = 'video' AND pending = 0"
  );
  invalidateSearchIndex(); // cleared transcripts are part of the lexical haystack
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

// Every tag label is stored lower-case so search, dedupe, and the tag UI treat
// "Pepe" and "pepe" as one tag. Taught exemplar labels go through the same
// normal form (they become meme tags verbatim), so the two sides always match.
export function normalizeLabel(label: string): string {
  return label.trim().toLowerCase();
}

// Normalizing at the write/read boundary means no pipeline (curated labels,
// vision, OCR, or manual entry) can persist mixed case, and legacy rows written
// before this render lower-case too. Dedupes by the normalized label, keeping
// the first (already highest-ranked upstream).
export function normalizeTags(tags: Tag[]): Tag[] {
  const seen = new Set<string>();
  const out: Tag[] = [];
  for (const t of tags) {
    const label = normalizeLabel(t.label);
    if (!label || seen.has(label)) continue;
    seen.add(label);
    out.push(t.label === label ? t : { ...t, label });
  }
  return out;
}

function safeParseTags(s: string): Tag[] {
  try {
    return normalizeTags(JSON.parse(s) as Tag[]);
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
      label = label.toLowerCase();
      counts.set(label, (counts.get(label) ?? 0) + 1);
    }
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([label]) => label);
}

// Full per-meme records for a shareable collection export: metadata + tags +
// embeddings, plus the uris the caller needs to attach the images. Whole
// indexed library, so it's one artifact instead of juggling tag + image dumps.
export interface CollectionRecord {
  id: number;
  uri: string;
  thumbUri: string;
  name: string;
  kind: MediaKind;
  caption: string;
  ocrText: string;
  transcript: string;
  tags: Tag[];
  extraTerms: string;
  embedding: number[] | null;
  captionEmbedding: number[] | null;
}

export async function getCollectionRecords(): Promise<CollectionRecord[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{
    id: number;
    uri: string;
    thumb_uri: string;
    name: string;
    kind: MediaKind;
    caption: string;
    ocr_text: string;
    transcript: string;
    tags: string;
    extra_terms: string;
    embedding: Uint8Array;
    caption_embedding: Uint8Array | null;
  }>(
    `SELECT id, uri, thumb_uri, name, kind, caption, ocr_text, transcript, tags,
            extra_terms, embedding, caption_embedding
     FROM memes WHERE pending = 0 ORDER BY id`
  );
  return rows.map((r) => ({
    id: r.id,
    uri: r.uri,
    thumbUri: r.thumb_uri ?? '',
    name: r.name,
    kind: r.kind,
    caption: r.caption ?? '',
    ocrText: r.ocr_text ?? '',
    transcript: r.transcript ?? '',
    tags: safeParseTags(r.tags),
    extraTerms: r.extra_terms ?? '',
    embedding: r.embedding && r.embedding.byteLength ? Array.from(blobToVec(r.embedding)) : null,
    captionEmbedding:
      r.caption_embedding && r.caption_embedding.byteLength
        ? Array.from(blobToVec(r.caption_embedding))
        : null,
  }));
}

// Export the model-produced tags per described meme, for the facet-coverage
// prompt-tuning loop (drop into tools/eval/described.json, run `npm run
// coverage`). Only the VLM's OWN tags (source 'vision') — that's what the
// caption prompt controls — so coverage measures the model, not the CLIP/OCR/
// exemplar tags mixed into the same list. Memes the VLM tagged nothing on are
// kept (empty tags) so coverage honestly reflects misses.
export async function exportDescribedTags(): Promise<{ id: string; tags: string[] }[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ id: number; tags: string }>(
    "SELECT id, tags FROM memes WHERE vision_state = 'done' AND tags != '[]'"
  );
  const out: { id: string; tags: string[] }[] = [];
  for (const r of rows) {
    let parsed: Tag[] = [];
    try {
      parsed = JSON.parse(r.tags) as Tag[];
    } catch {
      continue;
    }
    const vision = parsed.filter((t) => t.source === 'vision').map((t) => t.label);
    out.push({ id: String(r.id), tags: vision });
  }
  return out;
}

// How many memes currently carry a given tag label (used for teach feedback).
export async function countMemesWithLabel(label: string): Promise<number> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ c: number }>(
    "SELECT COUNT(*) as c FROM memes WHERE pending = 0 AND tags LIKE ?",
    `%"label":"${normalizeLabel(label)}"%`
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
  return rows.map(liteRowToRecord);
}

function liteRowToRecord(
  r: Omit<MemeRow, 'embedding' | 'visual_embedding' | 'caption_embedding'>
): MemeRecord {
  return {
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
  };
}

// Keyset page for infinite scroll: the memes strictly AFTER `cursor` in the
// browse order (modified_at DESC, id DESC). The cursor is the last loaded row's
// (modifiedAt, id) pair — modified_at is NOT NULL (backfilled from indexed_at),
// so the compound comparison is total. LIMIT/OFFSET paging was retired here
// because the offset shifts whenever rows enter or leave above the fold (a
// share/index landing mid-scroll, a deletion), which re-served or skipped rows
// and handed the grid duplicate keys — the "glitchy" infinite scroll. A keyset
// cursor names an absolute position in the sort, so the next page is exact no
// matter what happened above it.
export async function getMemesBefore(
  cursor: { modifiedAt: number; id: number },
  limit = 90,
  kind?: MediaKind
): Promise<MemeRecord[]> {
  const db = await getDb();
  const cols = `id, uri, name, kind, ocr_text, caption, transcript, tags, extra_terms, vision_state, audio_state, indexed_at, modified_at, pending, thumb_uri`;
  const after = `(modified_at < ? OR (modified_at = ? AND id < ?))`;
  const rows = kind
    ? await db.getAllAsync<Omit<MemeRow, 'embedding' | 'visual_embedding' | 'caption_embedding'>>(
        `SELECT ${cols} FROM memes WHERE ${after} AND kind = ?
         ORDER BY modified_at DESC, id DESC LIMIT ?`,
        cursor.modifiedAt,
        cursor.modifiedAt,
        cursor.id,
        kind,
        limit
      )
    : await db.getAllAsync<Omit<MemeRow, 'embedding' | 'visual_embedding' | 'caption_embedding'>>(
        `SELECT ${cols} FROM memes WHERE ${after}
         ORDER BY modified_at DESC, id DESC LIMIT ?`,
        cursor.modifiedAt,
        cursor.modifiedAt,
        cursor.id,
        limit
      );
  return rows.map(liteRowToRecord);
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
  const tagLabels: string[] = [];
  TAG_LABEL_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = TAG_LABEL_RE.exec(row.tags ?? ''))) tagLabels.push(m[1]);
  return assembleSearchText({
    ocr: row.ocr_text,
    name: row.name,
    caption: row.caption ?? '',
    transcript: row.transcript ?? '',
    tagLabels,
    extraTerms: row.extra_terms ?? '',
  });
}

function patchTagSearchCache(
  updates: readonly { id: number; tags: Tag[]; extraTerms: string }[]
): boolean {
  const resident = peekSearchIndex();
  if (!resident) return false;
  const byId = new Map(updates.map((update) => [update.id, update]));
  const patches = resident.flatMap((entry) => {
    const update = byId.get(entry.id);
    if (!update) return [];
    const record = { tags: update.tags, extraTerms: update.extraTerms };
    return [{
      id: entry.id,
      record,
      searchText: assembleSearchText({
        ocr: entry.record.ocrText,
        name: entry.record.name,
        caption: entry.record.caption,
        transcript: entry.record.transcript,
        tagLabels: update.tags.map((tag) => tag.label),
        extraTerms: update.extraTerms,
      }),
    }];
  });
  return patches.length === updates.length && patchSearchIndexEntries(patches);
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

// Build the resident search index in one pass: decode each meme's image (and
// optional caption) vector once, precompute its lexical haystack, and stash a
// ready-to-render record. Deliberately skips visual_embedding (DINO) — text
// search never touches it. Runs only on a cold or invalidated cache, never per
// keystroke, so its cost is amortized across every search until the next
// content change.
async function loadSearchIndex(): Promise<SearchCacheEntry[]> {
  const db = await getDb();
  // Pending placeholders have no embedding/OCR/tags yet, so they'd only add
  // noise — leave them out until the indexer fills them in.
  const rows = await db.getAllAsync<MemeRow>(
    `SELECT id, uri, name, kind, embedding, caption_embedding, ocr_text, caption,
            transcript, tags, extra_terms, vision_state, audio_state, indexed_at,
            modified_at, pending, thumb_uri
     FROM memes WHERE pending = 0`
  );
  return rows.map((row) => {
    const { embedding, ...record } = rowToRecord(row);
    return {
      id: row.id,
      kind: row.kind as MediaKind,
      imageVec: embedding,
      captionVec: row.caption_embedding ? blobToVec(row.caption_embedding) : null,
      searchText: rowSearchText(row),
      record,
    };
  });
}

// Ensure the FTS5 virtual table exists. Separated from population so the
// keystroke path can cheaply check availability without touching rows. Sets
// `ftsAvailable=false` permanently if this SQLite build lacks FTS5, so we stop
// trying and fall back to the in-memory scan for good.
async function ensureFtsTable(db: SQLite.SQLiteDatabase): Promise<boolean> {
  if (ftsAvailable === false) return false;
  try {
    await db.execAsync(`${MEME_SEARCH_FTS_DDL};`);
    ftsAvailable = true;
    return true;
  } catch {
    ftsAvailable = false;
    return false;
  }
}

// Repopulate the FTS index from `entries` (a snapshot taken at content version
// `version`). Tags the index current ONLY if no write landed across the whole
// rebuild: if `contentVersion` advanced, `entries` is already stale and
// `ftsBuiltVersion` is deliberately left behind so the next query reschedules
// and keeps serving from the always-fresh in-memory scan. This is the invariant
// that makes `ftsBuiltVersion === contentVersion` a guarantee the index reflects
// exactly the current content — never stale-but-clean.
async function buildFtsIndex(
  db: SQLite.SQLiteDatabase,
  entries: readonly SearchCacheEntry[],
  version: number
): Promise<void> {
  await db.withTransactionAsync(async () => {
    await db.execAsync('DELETE FROM meme_search_fts;');
    const stmt = await db.prepareAsync(MEME_SEARCH_FTS_INSERT);
    try {
      for (const entry of entries) {
        const r = entry.record;
        await stmt.executeAsync(
          entry.id,
          r.name,
          r.ocrText,
          r.caption,
          r.transcript,
          r.tags.map((t) => t.label).join(' '),
          `${r.extraTerms} ${classificationContextTerms({ tags: r.tags.map((t) => t.label) })}`.trim()
        );
      }
    } finally {
      await stmt.finalizeAsync();
    }
  });
  if (contentVersion === version) ftsBuiltVersion = version;
}

// Rebuild the FTS index off the keystroke critical path. Rebuilding every row is
// far slower than the in-memory scan, so it must never run inline for a query;
// instead the current query serves from the scan and this repopulates the index
// so the NEXT query gets BM25 ranking. Guarded so overlapping searches schedule
// at most one rebuild at a time.
function scheduleFtsRebuild(db: SQLite.SQLiteDatabase): void {
  if (ftsRebuilding || ftsAvailable === false) return;
  ftsRebuilding = true;
  setTimeout(() => {
    void (async () => {
      try {
        if (!(await ensureFtsTable(db))) return;
        // Snapshot the version BEFORE reading entries: any write between here
        // and the build completing advances contentVersion and prevents us from
        // tagging a stale build as current.
        const version = contentVersion;
        const entries = await ensureSearchIndex(loadSearchIndex);
        if (contentVersion !== version) return; // superseded; a later query reschedules
        await buildFtsIndex(db, entries, version);
      } catch {
        // Leave the index un-current; the in-memory scan stays correct.
      } finally {
        ftsRebuilding = false;
      }
    })();
  }, 0);
}

async function ftsRankedIds(
  db: SQLite.SQLiteDatabase,
  allEntries: readonly SearchCacheEntry[],
  eligibleEntries: readonly SearchCacheEntry[],
  lexicalQuery: LexicalQuery,
  shouldAbort?: () => boolean
): Promise<number[]> {
  const match = ftsMatchQuery(lexicalQuery);
  if (!match || shouldAbort?.()) return [];
  // BM25 ranking is an upgrade over the in-memory scan, kept off the keystroke
  // path. When the index isn't current (initial state, or a write landed since
  // the last build) serve this query from the scan and rebuild in the
  // background — never inline — so the next query gets BM25.
  if (ftsBuiltVersion !== contentVersion) {
    scheduleFtsRebuild(db);
    return [];
  }
  const eligible = new Set(eligibleEntries.map((e) => e.id));
  const rows = await db.getAllAsync<{ id: number }>(
    MEME_SEARCH_FTS_QUERY,
    match,
    allEntries.length
  );
  return rows.map((r) => r.id).filter((id) => eligible.has(id));
}

// With vectors already decoded, scoring a few thousand memes is a few ms — score
// them in one synchronous pass. Only past this size does a single pass risk being
// felt mid-type, so only then do we hand the event loop a macrotask between
// chunks (and re-check `shouldAbort`).
const SEARCH_YIELD_THRESHOLD = 20_000;

// `queryVec` may be null: lexical-only mode, used to serve instant results
// while the text-embed model is busy behind heavy background work. Scores are
// then purely keyword/OCR/tag/caption-text matches; the caller re-runs with
// the real vector when it arrives.
//
// `limit` may be Infinity: the Library's search passes it to rank the ENTIRE
// indexed collection (a search re-sorts the library rather than filtering it),
// then pages the ranked list into the grid itself. Equal scores tiebreak on
// recency then id, so the unmatched tail reads like the browse grid instead of
// arbitrary DB order — and so the ranking is deterministic across re-runs of
// the same query (a lexical-only pass upgrading to the hybrid one must not
// reshuffle ties under the user).
export async function searchByVector(
  queryVec: number[] | null,
  queryText: string,
  limit = 40,
  kind?: MediaKind,
  shouldAbort?: () => boolean,
  expandedQuery?: LexicalQuery
): Promise<SearchHit[] | null> {
  // The resident index replaces the per-keystroke `SELECT *` + re-decode: it
  // rebuilds only when searchable content/membership changed (see
  // invalidateSearchIndex), so a keystroke pays for scoring alone.
  const all = await ensureSearchIndex(loadSearchIndex);
  if (shouldAbort?.()) return null;
  const entries = searchScopeEntries(all, kind, queryText);
  const scopeRelaxed =
    !!kind &&
    queryText.trim().length > 0 &&
    entries === all &&
    all.length > 0 &&
    !all.some((e) => e.kind === kind);
  if (scopeRelaxed) {
    console.log(
      `[memeget/search] relaxed empty ${kind} filter for "${queryText.trim()}" to ${all.length} all-media candidates`
    );
  }

  let terms = searchTermsForText(queryText);
  // Lexical-only mode has no dense channel to fall back on; keep short words
  // rather than handing back an unranked list.
  if (!queryVec && terms.length === 0) {
    terms = searchTermsForText(queryText, true);
  }
  const lexicalQuery = expandedQuery ?? { exactTerms: terms };

  const db = await getDb();
  // Semantic expansions are a relevance hint, not literal evidence. Keep BM25/RRF
  // anchored to the words the user typed so a nearby label cannot outrank an
  // exact query match; expanded terms still contribute through scoreEntry/tag scoring.
  const lexicalRankForFts = lexicalRankQuery(lexicalQuery);
  const lexicalIds = await ftsRankedIds(db, all, entries, lexicalRankForFts, shouldAbort);
  if (shouldAbort?.()) return null;
  // If FTS5 is available, lexical ranking is handled as its own BM25/RRF signal;
  // otherwise scoreEntry falls back to the historical JS lexical boost.
  const scoreTerms = lexicalIds.length
    ? { exactTerms: [], expandedTerms: lexicalQuery.expandedTerms }
    : lexicalQuery;

  const yielding = entries.length > SEARCH_YIELD_THRESHOLD;
  const scored: { entry: SearchCacheEntry; score: number }[] = [];
  for (let i = 0; i < entries.length; i++) {
    const entry = entries[i];
    scored.push({
      entry,
      score:
        scoreEntry(queryVec, scoreTerms, entry) +
        tagTermScore(
          entry.record.tags.map((tag) => tag.label),
          lexicalQuery
        ),
    });

    // Only huge libraries chunk-yield; below the threshold this branch never
    // runs and the whole scan stays synchronous.
    if (yielding && (i & (SEARCH_CHUNK - 1)) === SEARCH_CHUNK - 1) {
      if (shouldAbort?.()) return null;
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
  }

  scored.sort(
    (a, b) =>
      b.score - a.score ||
      (b.entry.record.modifiedAt ?? 0) - (a.entry.record.modifiedAt ?? 0) ||
      b.entry.id - a.entry.id
  );
  const fused = fuseDenseAndLexicalRanks(
    scored.map(({ entry, score }) => ({
      id: entry.id,
      score,
      modifiedAt: entry.record.modifiedAt ?? entry.record.indexedAt,
    })),
    lexicalIds
  );
  const byId = new Map(scored.map((s) => [s.entry.id, s.entry]));
  let ranked = fused
    .map(({ id, score }) => {
      const entry = byId.get(id);
      return entry ? ({ ...entry.record, score } as SearchHit) : null;
    })
    .filter((h): h is SearchHit => h !== null);
  // Exact-phrase boost: a clip whose stored text literally contains the typed
  // phrase (apostrophes and 1-2 char words included — see phraseKey) is a far
  // stronger signal than the term or dense channels, which drop short words and
  // never match across word boundaries. That's exactly the "find the clip that
  // SAYS this" case: typing "im so old" must surface a transcript of "I'm so
  // old". Float such hits to the top, keeping their fused order among
  // themselves. Gated to multi-token queries; a single word already ranks fine
  // through the term path.
  const queryKey = phraseKey(queryText);
  let phraseCount = 0;
  if (phraseTokenCount(queryKey) >= 2) {
    const phraseIds = new Set<number>();
    for (const e of entries) {
      if (containsPhrase(phraseKey(e.searchText), queryKey)) phraseIds.add(e.id);
    }
    if (phraseIds.size) {
      phraseCount = phraseIds.size;
      ranked = [
        ...ranked.filter((h) => phraseIds.has(h.id)),
        ...ranked.filter((h) => !phraseIds.has(h.id)),
      ];
    }
  }
  const hits = Number.isFinite(limit) ? ranked.slice(0, limit) : ranked;
  if (queryText.trim().length > 0) {
    console.log(
      `[memeget/search] query="${queryText.trim()}" candidates=${entries.length} lexical=${lexicalIds.length} phrase=${phraseCount} hits=${hits.length} top=${hits
        .slice(0, 5)
        .map((hit) => {
          const labels = hit.tags
            .slice(0, 3)
            .map((tag) => tag.label)
            .join('|');
          return `${hit.name}:${hit.score.toFixed(3)}:${labels}`;
        })
        .join('; ')}`
    );
  }
  return hits;
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

  // Native fast path: sqlite-vec computes the per-pair cosine in C (SIMD) over
  // the stored blobs, so the whole O(N) ranking never enters JS — a real win for
  // "More like this" on a large library. Falls through to the JS scan below if
  // the extension isn't loaded or the native query errors for any reason.
  if (vecReady) {
    const hits = await getSimilarMemesVec(db, id, target, limit).catch(() => null);
    if (hits) return hits;
  }

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

// sqlite-vec ranking for getSimilarMemes. The CASE reproduces selectPairVectors
// exactly: the DINO (visual) space is used for a pair only when BOTH the target
// (the bound flag) AND the candidate row carry an active-model visual vector;
// otherwise the primary image space. Vectors are normalized, so cosine distance
// ascending == cosine similarity descending == the JS `dot` ranking. Empty
// (degraded) embeddings are excluded — they'd score 0 and never reach the top-N.
async function getSimilarMemesVec(
  db: SQLite.SQLiteDatabase,
  id: number,
  target: VisualSimilarityRecord,
  limit: number
): Promise<SearchHit[]> {
  const targetHasDino =
    VISUAL_EMBEDDING_MODEL.available &&
    !!target.visualEmbedding &&
    target.visualEmbedding.length > 0 &&
    target.visualModel === VISUAL_EMBEDDING_MODEL.id;
  const clipQ = vecToBlob(Array.from(target.imageEmbedding));
  // When the target has no DINO vector the DINO branch is never taken, so this
  // bind is unused — reuse clipQ as a harmless placeholder rather than null.
  const dinoQ = targetHasDino ? vecToBlob(Array.from(target.visualEmbedding!)) : clipQ;

  const ranked = await db.getAllAsync<{ id: number; d: number }>(
    `SELECT id,
        CASE WHEN ? AND visual_embedding IS NOT NULL AND length(visual_embedding) > 0 AND visual_model = ?
             THEN vec_distance_cosine(vec_f32(visual_embedding), vec_f32(?))
             ELSE vec_distance_cosine(vec_f32(embedding), vec_f32(?))
        END AS d
     FROM memes
     WHERE pending = 0 AND id != ? AND length(embedding) > 0
     ORDER BY d ASC
     LIMIT ?`,
    targetHasDino ? 1 : 0,
    VISUAL_EMBEDDING_MODEL.id,
    dinoQ,
    clipQ,
    id,
    limit
  );
  if (ranked.length === 0) return [];

  // Fetch the winning rows in one query, then restore the ranked order (SQL
  // `IN (...)` doesn't preserve it) and carry the cosine through as the score.
  const ids = ranked.map((r) => r.id);
  const rows = await db.getAllAsync<MemeRow>(
    `SELECT * FROM memes WHERE id IN (${ids.map(() => '?').join(',')})`,
    ...ids
  );
  const byId = new Map(rows.map((r) => [r.id, r]));
  return ranked
    .map(({ id: rid, d }) => {
      const row = byId.get(rid);
      if (!row) return null;
      const { embedding, ...record } = rowToRecord(row);
      return { ...record, score: 1 - d } as SearchHit;
    })
    .filter((h): h is SearchHit => h !== null);
}

// Spread a just-applied manual tag to the library's visual look-alikes — the
// write side of the bulk-tag sheet's "spread to look-alikes" toggle. Loads the
// tagged memes' stored vectors, scans every other indexed meme in the
// visual-similarity space (DINO per pair when both sides carry a stamped
// vector, else the primary image space — selectPairVectors' routing), and tags
// everything above the strict per-space threshold (tagPropagation.ts). All
// stored-vector reads plus one bulk write; no model call.
export async function propagateTagToSimilarMemes(
  sourceIds: number[],
  label: string
): Promise<{ propagated: number }> {
  const trimmed = label.trim();
  if (sourceIds.length === 0 || !trimmed) return { propagated: 0 };
  const db = await getDb();
  const marks = sourceIds.map(() => '?').join(',');

  interface VectorCols {
    embedding: Uint8Array;
    visual_embedding: Uint8Array | null;
    visual_model: string | null;
  }
  const toRecord = (r: VectorCols): VisualSimilarityRecord => ({
    imageEmbedding: blobToVec(r.embedding),
    visualEmbedding: r.visual_embedding ? blobToVec(r.visual_embedding) : null,
    visualModel: r.visual_model ?? '',
  });

  const srcRows = await db.getAllAsync<VectorCols>(
    `SELECT embedding, visual_embedding, visual_model FROM memes WHERE pending = 0 AND id IN (${marks})`,
    ...sourceIds
  );
  const sources = srcRows.map(toRecord).filter((s) => s.imageEmbedding.length > 0);
  if (sources.length === 0) return { propagated: 0 };
  // Streamed on purpose: loading every candidate row's vectors at once OOM'd on
  // large libraries. Only the tiny PropagationHit (id/score/margin) is retained
  // per match; the ≤PROPAGATE_MAX_TARGETS winners are re-read for their text
  // afterwards, so peak memory is one row plus the hit list.
  const stmt = await db.prepareAsync(
    `SELECT id, tags, extra_terms, embedding, visual_embedding, visual_model FROM memes WHERE pending = 0 AND id NOT IN (${marks})`
  );
  const labelKey = normalizeLabel(trimmed);
  // A label already held from a user-owned source is left alone; automatic
  // prompt/vision/OCR matches are still scored so propagation can promote them.
  const hasDurableLabel = (rawTags: string): boolean =>
    safeParseTags(rawTags).some(
      (t) =>
        normalizeLabel(t.label) === labelKey &&
        (t.source === 'manual' || t.source === 'exemplar' || t.source === 'propagated')
    );

  const hits: PropagationHit[] = [];
  try {
    const result = await stmt.executeAsync<
      VectorCols & { id: number; tags: string; extra_terms: string }
    >(...sourceIds);
    let i = 0;
    for await (const row of result) {
      const hit = scorePropagationCandidate(
        sources,
        { id: row.id, hasDurableLabel: hasDurableLabel(row.tags), record: toRecord(row) },
        VISUAL_EMBEDDING_MODEL
      );
      if (hit) hits.push(hit);
      // Same chunked yield as the search scans, so spreading across a big
      // library never hitches the UI.
      if ((i++ & (SEARCH_CHUNK - 1)) === SEARCH_CHUNK - 1) {
        await new Promise<void>((resolve) => setTimeout(resolve, 0));
      }
    }
  } finally {
    await stmt.finalizeAsync();
  }

  // Margin-ranked and capped: bounds both the write and the blast radius of a
  // mis-tag. Dropping this cap would let one tag rewrite an entire library.
  const winners = rankPropagationHits(hits);
  if (winners.length === 0) return { propagated: 0 };

  const winnerMarks = winners.map(() => '?').join(',');
  const winnerRows = await db.getAllAsync<{ id: number; tags: string; extra_terms: string }>(
    `SELECT id, tags, extra_terms FROM memes WHERE id IN (${winnerMarks})`,
    ...winners.map((w) => w.id)
  );
  const byId = new Map(winnerRows.map((r) => [r.id, r]));
  const updates: { id: number; tags: Tag[]; extraTerms: string }[] = [];
  for (const w of winners) {
    const row = byId.get(w.id);
    if (!row) continue; // deleted between the scan and the write
    const next = upsertDurableTag(safeParseTags(row.tags), row.extra_terms ?? '', {
      label: trimmed,
      category: 'user',
      source: 'propagated',
      score: w.score,
    });
    updates.push({ id: w.id, tags: next.tags, extraTerms: next.extraTerms });
  }

  await bulkUpdateMemeTags(updates);
  return { propagated: updates.length };
}
export async function clearIndex(): Promise<void> {
  const db = await getDb();
  await db.execAsync('DELETE FROM memes; DELETE FROM content_hashes;');
  invalidateSearchIndex(); // whole library gone
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

// ---- sidecar (.memeget knowledge mirror) --------------------------------------

// A SAF document uri always starts with its tree uri followed by '/document/',
// so that prefix is what scopes a query to one linked folder. It has to go
// through LIKE ... ESCAPE: SAF uris are percent-encoded ('primary%3AMeme'), and
// a bare '%' inside a LIKE pattern is a wildcard that would happily match other
// folders' memes.
const LIKE_ESCAPE = /[\\%_]/g;

export interface SidecarRow {
  // Only used to confirm the file still exists in the folder before backing its
  // knowledge up; the sidecar itself never stores a uri (they're install-local).
  uri: string;
  name: string;
  kind: MediaKind;
  tags: Tag[];
  extraTerms: string;
  ocr: string;
  caption: string;
  transcript: string;
  visionState: string;
  audioState: string;
  modifiedAt: number;
  embedding: Float32Array | null;
  visualEmbedding: Float32Array | null;
  visualModel: string;
  captionEmbedding: Float32Array | null;
}

// Every fully-indexed meme living in one linked folder, with all the knowledge
// the sidecar mirrors. Placeholders (pending = 1) are skipped: they carry no
// knowledge yet, and writing them would put rows in the folder that claim to
// know things they don't.
export async function getSidecarRows(folderUri: string): Promise<SidecarRow[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<{
    uri: string;
    name: string;
    kind: MediaKind;
    tags: string;
    extra_terms: string;
    ocr_text: string;
    caption: string;
    transcript: string;
    vision_state: string;
    audio_state: string;
    modified_at: number;
    embedding: Uint8Array | null;
    visual_embedding: Uint8Array | null;
    visual_model: string;
    caption_embedding: Uint8Array | null;
  }>(
    `SELECT uri, name, kind, tags, extra_terms, ocr_text, caption, transcript, vision_state,
            audio_state, modified_at, embedding, visual_embedding, visual_model, caption_embedding
     FROM memes
     WHERE pending = 0 AND uri LIKE ? ESCAPE '\\'
     ORDER BY name`,
    `${folderUri.replace(LIKE_ESCAPE, '\\$&')}/document/%`
  );
  return rows.map((r) => ({
    uri: r.uri,
    name: r.name,
    kind: r.kind,
    tags: safeParseTags(r.tags),
    extraTerms: r.extra_terms ?? '',
    ocr: r.ocr_text ?? '',
    caption: r.caption ?? '',
    transcript: r.transcript ?? '',
    visionState: r.vision_state ?? 'pending',
    audioState: r.audio_state ?? 'none',
    modifiedAt: r.modified_at ?? 0,
    embedding: r.embedding?.byteLength ? blobToVec(r.embedding) : null,
    visualEmbedding: r.visual_embedding?.byteLength ? blobToVec(r.visual_embedding) : null,
    visualModel: r.visual_model ?? '',
    captionEmbedding: r.caption_embedding?.byteLength ? blobToVec(r.caption_embedding) : null,
  }));
}

export interface SidecarRestoreEntry {
  uri: string; // resolved against the files actually present in the folder
  name: string;
  kind: string;
  tags: Tag[];
  extraTerms: string;
  ocr: string;
  caption: string;
  transcript: string;
  visionState: string;
  audioState: string;
  modifiedAt: number;
  embedding: number[];
  visualEmbedding: number[];
  visualModel: string;
  captionEmbedding: number[];
}

// Fold sidecar knowledge back into the library. Strictly additive: a brand-new
// meme is inserted whole, and an existing row only receives the fields it is
// currently missing. Restoring must never be able to make the library worse
// than it was — the user may have re-taught or re-described something since the
// sidecar was written, and a folder is a file other tools can edit.
//
// A row restored WITHOUT an embedding (the sidecar was written under a
// different primary model, so its vectors mean nothing here) is left pending so
// the indexer re-embeds it; insertMeme's upsert preserves the caption and
// transcript restored here when it does.
export async function restoreSidecarMemes(
  entries: SidecarRestoreEntry[]
): Promise<{ added: number; enriched: number }> {
  if (entries.length === 0) return { added: 0, enriched: 0 };
  const db = await getDb();
  const existing = new Set(
    (await db.getAllAsync<{ uri: string }>('SELECT uri FROM memes')).map((r) => r.uri)
  );
  let added = 0;
  let enriched = 0;
  await db.withTransactionAsync(async () => {
    const stmt = await db.prepareAsync(RESTORE_SIDECAR_MEME_SQL);
    try {
      for (const e of entries) {
        await stmt.executeAsync({
          $uri: e.uri,
          $name: e.name,
          $kind: e.kind,
          $embedding: vecToBlob(e.embedding),
          $visualEmbedding: e.visualEmbedding.length ? vecToBlob(e.visualEmbedding) : null,
          $visualModel: e.visualEmbedding.length ? e.visualModel : '',
          $ocr: e.ocr,
          $caption: e.caption,
          $captionEmbedding: e.captionEmbedding.length ? vecToBlob(e.captionEmbedding) : null,
          $transcript: e.transcript,
          $tags: JSON.stringify(normalizeTags(e.tags)),
          $extraTerms: e.extraTerms,
          $visionState: e.visionState,
          $audioState: e.audioState,
          $now: Date.now(),
          $modifiedAt: e.modifiedAt,
          $pending: e.embedding.length ? 0 : 1,
        });
        if (existing.has(e.uri)) enriched++;
        else added++;
      }
    } finally {
      await stmt.finalizeAsync();
    }
  });
  invalidateSearchIndex(); // rows and their searchable text changed
  return { added, enriched };
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

// Drop cached text vectors nobody will ask for again: a prompt that was edited
// (its key carries the prompt hash), an anchor that left the list, or a row
// from before the keyspaces were namespaced. Each is 2KB of blob that would
// otherwise sit in the DB forever. The table holds hundreds of rows, so reading
// the keys to decide is cheaper than encoding the policy in SQL.
export async function pruneLabelVectors(
  isLive: (key: string) => boolean,
  model = PRIMARY_EMBEDDING_MODEL.id
): Promise<number> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ label: string }>(
    'SELECT label FROM label_vectors WHERE model = ?',
    model
  );
  const dead = rows.map((r) => r.label).filter((k) => !isLive(k));
  for (let i = 0; i < dead.length; i += 200) {
    const chunk = dead.slice(i, i + 200);
    await db.runAsync(
      `DELETE FROM label_vectors WHERE model = ? AND label IN (${chunk.map(() => '?').join(',')})`,
      model,
      ...chunk
    );
  }
  return dead.length;
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
    normalizeLabel(args.label),
    args.category,
    vecToBlob(args.vector),
    JSON.stringify(args.associations),
    args.sourceUri,
    args.positive === false ? 0 : 1,
    PRIMARY_EMBEDDING_MODEL.id,
    Date.now()
  );
  emitKnowledgeChanged();
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

// One-time re-facet: the old teach flow filed EVERY taught exemplar as
// 'character'. Re-infer the real facet from the label (Waving→action,
// Excited→emotion, Greentext→format) so existing teachings match how they're
// used. Idempotent — only touches rows still tagged 'character', and only when
// the label clearly points elsewhere (true characters + unknowns stay put).
export async function refacetExemplars(): Promise<{ updated: number }> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ id: number; label: string }>(
    "SELECT id, label FROM exemplars WHERE category = 'character'"
  );
  let updated = 0;
  for (const r of rows) {
    const facet = guessFacet(r.label);
    if (facet !== 'character') {
      await db.runAsync('UPDATE exemplars SET category = ? WHERE id = ?', facet, r.id);
      updated++;
    }
  }
  return { updated };
}

// One-time re-facet: every VLM tag was filed as a blanket 'topic', so a whole
// described library reads as having no character, action, emotion or situation
// — to facet coverage, to the grounding line, to anything facet-aware. The
// label text is all `guessFacet` needs, so this is a pure text pass over stored
// rows: no model, no re-describe. Idempotent (only rewrites 'topic' vision tags
// whose words clearly point elsewhere).
export async function refacetVisionTags(): Promise<{ memes: number; tags: number }> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ id: number; tags: string }>(
    `SELECT id, tags FROM memes WHERE tags LIKE '%"source":"vision"%'`
  );
  let memes = 0;
  let tags = 0;
  for (const r of rows) {
    const parsed = safeParseTags(r.tags);
    let changed = 0;
    for (const t of parsed) {
      if (t.source !== 'vision' || t.category !== 'topic') continue;
      const facet = guessFacet(t.label, 'topic');
      if (facet !== 'topic') {
        t.category = facet;
        changed++;
      }
    }
    if (changed === 0) continue;
    await db.runAsync('UPDATE memes SET tags = ? WHERE id = ?', JSON.stringify(parsed), r.id);
    memes++;
    tags += changed;
  }
  if (memes > 0) {
    invalidateSearchIndex();
    emitKnowledgeChanged(); // facets feed grounding, the sidecar and the UI
  }
  return { memes, tags };
}

// Every tag the user applied by hand is an assertion that this meme IS that
// label — the same statement teaching makes, with the same image vector behind
// it. The learner only ever saw the `exemplars` table, so a label the user bulk
// tagged onto 50 memes still trained NO head and never reached a meme indexed
// afterwards. This hands those vectors to the trainer as positives without
// writing anything into the user's explicit teachings.
export interface ManualPositives {
  category: string; // the facet the tag was filed under when it was applied
  vectors: Float32Array[];
}

export async function getManualTagVectors(): Promise<Map<string, ManualPositives>> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ tags: string; embedding: Uint8Array }>(
    `SELECT tags, embedding FROM memes
      WHERE pending = 0 AND length(embedding) > 0 AND tags LIKE '%"source":"manual"%'
      ORDER BY id`
  );
  const out = new Map<string, ManualPositives>();
  for (const r of rows) {
    let vec: Float32Array | null = null;
    for (const t of safeParseTags(r.tags)) {
      if (t.source !== 'manual') continue;
      vec = vec ?? blobToVec(r.embedding);
      const label = normalizeLabel(t.label);
      const cur = out.get(label);
      // A bulk tag is stored with category 'user'; that is not a facet, so fall
      // back to inferring one from the label the way teaching does.
      if (cur) cur.vectors.push(vec);
      else out.set(label, { category: guessFacet(label), vectors: [vec] });
    }
  }
  return out;
}

// Fingerprint of the hand-applied tags, for the trained-heads cache. Content,
// not counts: renaming a label on the same rows has to invalidate too, and the
// row set is small (only memes carrying a manual tag are read).
export async function getManualTagStamp(): Promise<string> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ id: number; tags: string }>(
    `SELECT id, tags FROM memes WHERE tags LIKE '%"source":"manual"%' ORDER BY id`
  );
  const parts: string[] = [];
  for (const r of rows) {
    for (const t of safeParseTags(r.tags)) {
      if (t.source === 'manual') parts.push(`${r.id}:${normalizeLabel(t.label)}`);
    }
  }
  return `${parts.length}.${hashText(parts.join('|'))}`;
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
      normalizeLabel(e.label),
      e.source_uri,
      e.is_positive
    );
    if (!dup) {
      await db.runAsync(
        `INSERT INTO exemplars (label, category, vector, associations, source_uri, is_positive, origin, pack, model, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        normalizeLabel(e.label),
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
  emitKnowledgeChanged();
}

// Drop every example for a label — i.e. forget a taught tag entirely. The tags
// already written onto memes stay until the next re-tag, when the label simply
// stops matching (no head to train) and falls off.
export async function deleteExemplarsByLabel(label: string): Promise<number> {
  const db = await getDb();
  const res = await db.runAsync('DELETE FROM exemplars WHERE label = ?', normalizeLabel(label));
  emitKnowledgeChanged();
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
    // Match the tag side's normal form — an exemplar label IS the tag label.
    const label = normalizeLabel(r.label);
    const stat =
      byLabel.get(label) ??
      {
        label,
        category: r.category,
        positives: 0,
        negatives: 0,
        tagged: taggedCounts.get(label) ?? 0,
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
    byLabel.set(label, stat);
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
  emitKnowledgeChanged();
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
  // `origin` records whose work these examples are. A shared teaching pack is
  // someone else's ('pack', grouped under its name so it can be removed as a
  // unit); a sidecar restore is the user's OWN teaching coming home, so it goes
  // back in as 'self' rather than masquerading as a third-party import.
  opts: { pack: string; mode?: 'merge' | 'replace'; origin?: 'self' | 'pack' } = { pack: '' }
): Promise<{ added: number; skipped: number; removed: number }> {
  const db = await getDb();
  const mode = opts.mode ?? 'merge';
  const sig = (label: string, positive: boolean, vec: number[]) =>
    // First few components rounded are a cheap, collision-safe fingerprint for
    // a 512-dim normalized vector; exact equality across devices is unreliable.
    `${normalizeLabel(label)} ${positive ? 1 : 0} ${vec.slice(0, 8).map((v) => v.toFixed(5)).join(',')}`;

  // On replace we drop everything below, so there's nothing to dedupe against.
  const existing = mode === 'replace' ? [] : await getExemplars();
  const seen = new Set(existing.map((e) => sig(e.label, e.positive, e.vector)));

  let added = 0;
  let skipped = 0;
  let removed = 0;
  // Import is gated on pack↔app model compatibility upstream, so imported
  // vectors are by definition in the active primary space.
  const origin = opts.origin ?? 'pack';
  const stmt = await db.prepareAsync(
    `INSERT INTO exemplars (label, category, vector, associations, source_uri, is_positive, origin, pack, model, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
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
          normalizeLabel(e.label),
          e.category,
          vecToBlob(e.vector),
          JSON.stringify(e.associations),
          '', // imported examples have no local source image
          e.positive ? 1 : 0,
          origin,
          origin === 'pack' ? opts.pack || 'Imported pack' : '',
          PRIMARY_EMBEDDING_MODEL.id,
          Date.now()
        );
        added += 1;
      }
    });
  } finally {
    await stmt.finalizeAsync();
  }
  emitKnowledgeChanged();
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

// One row per (name, stage): re-logging the same failure REPLACES the prior
// row instead of appending. Without this, every "Retry failed posters" tap and
// every background backfill pass re-logs the same undecodable files, so the
// error count climbs without bound (10 failing videos read as 30+ "errors")
// even though nothing new is wrong. Delete-then-insert also collapses the
// duplicates left over from before this fix as each file is retried.
export async function addIndexError(e: IndexError): Promise<void> {
  const db = await getDb();
  await db.withTransactionAsync(async () => {
    await db.runAsync('DELETE FROM index_errors WHERE name = ? AND stage = ?', e.name, e.stage);
    await db.runAsync(
      'INSERT INTO index_errors (name, kind, stage, reason, created_at) VALUES (?, ?, ?, ?, ?)',
      e.name,
      e.kind,
      e.stage,
      e.reason,
      Date.now()
    );
  });
}

// Drop any logged errors for a file that has since succeeded, so a video whose
// poster lands on retry stops showing in the indexing-errors list.
export async function clearIndexErrorsFor(name: string): Promise<void> {
  const db = await getDb();
  await db.runAsync('DELETE FROM index_errors WHERE name = ?', name);
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
