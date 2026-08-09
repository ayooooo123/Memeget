// The `.memeget` sidecar: everything this app *derives* about a meme folder,
// written back INTO that folder so it outlives the app.
//
// Why this exists: all knowledge — tags, taught exemplars, embeddings, VLM
// captions, transcripts — lived only in the app-private SQLite DB at
// /data/data/<applicationId>/files/SQLite/memeget.db. That path is owned by the
// Android package, so it is lost to an uninstall, a "clear data", a device move,
// AND — the case that actually bit — installing a build with a different
// applicationId, which Android treats as an unrelated app with an empty sandbox.
// Hours of teaching disappear with no way back. The memes themselves were never
// at risk (they are the user's own files in their own folder), so the fix is to
// keep the knowledge next to them: a `.memeget/` directory inside the linked
// folder. Re-link the folder from any install and the knowledge comes back.
//
// This module is the pure format: build/serialize/parse and nothing else, so it
// unit-tests without SAF, SQLite, or a device (same split as collectionExport).
// src/sidecarSync.ts is the I/O + DB shell around it.
import { PRIMARY_EMBEDDING_MODEL } from './embeddingModels';
import { base64Decode, base64Encode } from './zipWriter';
import type { AudioState, MediaKind, Tag, VisionState } from './types';

export const SIDECAR_DIR = '.memeget';
export const SIDECAR_FORMAT = 'memeget-sidecar';
// v2 exists only to force one full rewrite of every chunk. v1 files were written
// through a SAF overwrite that does not truncate (see saf.writeSidecarFile), so
// any chunk that shrank between two syncs was left with the tail of its previous
// contents and no longer parsed — silently restoring nothing. Reading a v1 file
// is still fine (the record shape is unchanged); a sync that finds a v1 manifest
// just rewrites everything once, padded, and the corruption is gone.
export const SIDECAR_VERSION = 2;

export const MANIFEST_FILE = 'manifest.json';
// Taught knowledge is written in the EXISTING teaching-pack format (see
// teachingPack.ts) rather than a sidecar-specific one, so the file a user finds
// in their folder is the same artifact the Settings export produces and the
// Settings import accepts. One format, two delivery mechanisms.
export const TEACHINGS_FILE = 'teachings.json';

// Per-meme knowledge is split across a fixed set of chunk files. SAF has no
// partial-file write: persisting one changed tag means rewriting whatever file
// holds it. A single library file would mean re-encoding and re-writing tens of
// megabytes for a one-word edit; one file per meme would mean thousands of SAF
// document creations (tens of ms each) per sync. 64 chunks puts a typical
// library at a few hundred KB per file, so a sync rewrites only the handful of
// chunks that actually changed.
export const SIDECAR_CHUNKS = 64;

export function chunkFileName(chunk: string): string {
  return `library-${chunk}.json`;
}

// Which chunk a meme belongs to, keyed on its FILE NAME. Deliberately not the
// row id or the content:// uri: both are install-local, and the whole point is
// to survive a reinstall. The name is what identifies a file inside the folder
// the sidecar lives in (folder scans are non-recursive, so names are unique),
// which also means a chunk assignment is stable across devices.
export function chunkFor(name: string): string {
  let h = 0x811c9dc5; // FNV-1a offset basis
  for (let i = 0; i < name.length; i++) {
    h ^= name.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return ((h >>> 0) % SIDECAR_CHUNKS).toString(16).padStart(2, '0');
}

// Change stamp for a serialized chunk, so a sync can skip rewriting files whose
// content is byte-identical to what is already in the folder. Full pass, never
// sampled: the share path's contentHash.hashFileSample only samples windows of
// long media (a full scan of a multi-MB video would stall the share), but a
// sampled digest can miss an edit that lands between samples — which here would
// mean silently declining to back up changed knowledge.
export function digest(payload: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < payload.length; i++) {
    h ^= payload.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return `${payload.length.toString(36)}.${(h >>> 0).toString(36)}`;
}

// ---- vectors ------------------------------------------------------------------

// Vectors go out as base64 of the raw float32 bytes, not as a JSON `number[]`.
// A library of a few thousand memes carries ~7KB of vectors each (primary +
// visual + caption); as decimal JSON that is ~90MB of text to build, hold, and
// write on every sync, versus ~20MB base64 — and base64 of the float bytes is
// bit-exact, where decimal round-trips are not guaranteed to be.
export function encodeVec(vec: Float32Array | number[] | null | undefined): string {
  if (!vec || vec.length === 0) return '';
  const f32 = vec instanceof Float32Array ? vec : Float32Array.from(vec);
  return base64Encode(new Uint8Array(f32.buffer, f32.byteOffset, f32.byteLength));
}

export function decodeVec(b64: string): number[] {
  if (!b64) return [];
  const bytes = base64Decode(b64);
  // A float32 view needs 4-byte alignment and a whole number of floats. Trailing
  // junk means a corrupt entry, not a partial vector — drop it rather than
  // restore a truncated embedding that would quietly poison similarity.
  if (bytes.byteLength === 0 || bytes.byteLength % 4 !== 0) return [];
  return Array.from(new Float32Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 4));
}

// ---- records ------------------------------------------------------------------

// One meme's derived knowledge. Everything here is expensive to recompute (model
// passes) or impossible to recompute (the user's own manual tags). Notably
// absent: `uri` (install-local), `id` (install-local), `thumb_uri` (a path into
// the app sandbox pointing at a poster jpeg that is itself cheap to re-extract),
// and `pending` (a transient indexing state, never a fact worth preserving).
export interface SidecarMeme {
  name: string;
  kind: MediaKind;
  tags: Tag[];
  extraTerms: string;
  ocr: string;
  caption: string;
  transcript: string;
  visionState: VisionState;
  audioState: AudioState;
  modifiedAt: number;
  embedding: string;
  visualEmbedding: string;
  visualModel: string;
  captionEmbedding: string;
}

export interface SidecarChunk {
  format: string;
  version: number;
  chunk: string;
  count: number;
  memes: SidecarMeme[];
}

export interface ChunkStamp {
  count: number;
  digest: string;
  // Byte length of the file as written. The next overwrite pads up to this so a
  // shrinking payload can't leave a stale tail behind (saf.writeSidecarFile),
  // and knowing it here saves reading the old file back just to measure it.
  bytes: number;
}

export interface SidecarManifest {
  format: string;
  version: number;
  // The embedding space every stored vector lives in. A sidecar written under a
  // different primary model still restores its tags, captions and transcripts —
  // those are model-independent — but its vectors are meaningless here, so
  // restore drops them and lets the indexer re-embed. Same guard the teaching
  // pack applies, just non-fatal: text knowledge is worth more than a clean
  // rejection.
  model: string;
  dim: number;
  writtenAt: number;
  memeCount: number;
  chunks: Record<string, ChunkStamp>;
  // Digest of teachings.json, so a sync rewrites the exemplar file only when
  // the taught set actually changed.
  teachings: ChunkStamp;
}

// ---- build --------------------------------------------------------------------

// Group memes into their chunks. `previous` names the chunks the folder already
// holds: a chunk that has just become empty (its last meme was deleted) must
// still be emitted, as an empty file, or the folder would keep serving stale
// records for memes that no longer exist.
export function groupChunks(
  memes: SidecarMeme[],
  previous: Iterable<string> = []
): Map<string, SidecarMeme[]> {
  const out = new Map<string, SidecarMeme[]>();
  for (const key of previous) out.set(key, []);
  for (const m of memes) {
    const key = chunkFor(m.name);
    const bucket = out.get(key);
    if (bucket) bucket.push(m);
    else out.set(key, [m]);
  }
  // Stable order inside a chunk keeps the serialized bytes — and therefore the
  // digest — identical when nothing actually changed, which is what lets a sync
  // skip the write.
  for (const bucket of out.values()) bucket.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  return out;
}

export function serializeChunk(chunk: string, memes: SidecarMeme[]): string {
  const payload: SidecarChunk = {
    format: SIDECAR_FORMAT,
    version: SIDECAR_VERSION,
    chunk,
    count: memes.length,
    memes,
  };
  return JSON.stringify(payload);
}

export function buildManifest(
  stamps: Map<string, ChunkStamp>,
  teachings: ChunkStamp,
  writtenAt: number,
  model = PRIMARY_EMBEDDING_MODEL.id,
  dim = PRIMARY_EMBEDDING_MODEL.dim
): SidecarManifest {
  const chunks: Record<string, ChunkStamp> = {};
  let memeCount = 0;
  for (const key of [...stamps.keys()].sort()) {
    const stamp = stamps.get(key)!;
    chunks[key] = stamp;
    memeCount += stamp.count;
  }
  return {
    format: SIDECAR_FORMAT,
    version: SIDECAR_VERSION,
    model,
    dim,
    writtenAt,
    memeCount,
    chunks,
    teachings,
  };
}

// ---- parse --------------------------------------------------------------------

// Everything below treats the folder as untrusted input: it is a user-visible
// directory that a file manager, a sync client, or another tool can have
// mangled. A malformed chunk must degrade to "that chunk restored nothing",
// never to a throw that aborts the whole restore.

function asString(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

function asTags(v: unknown): Tag[] {
  if (!Array.isArray(v)) return [];
  const out: Tag[] = [];
  for (const t of v) {
    if (!t || typeof t !== 'object') continue;
    const label = asString((t as Tag).label);
    if (!label) continue;
    const score = (t as Tag).score;
    out.push({
      label,
      category: asString((t as Tag).category) || 'unknown',
      score: typeof score === 'number' && Number.isFinite(score) ? score : 0,
      source: (t as Tag).source,
    });
  }
  return out;
}

function asMeme(v: unknown): SidecarMeme | null {
  if (!v || typeof v !== 'object') return null;
  const r = v as Record<string, unknown>;
  const name = asString(r.name);
  if (!name) return null;
  const modifiedAt = typeof r.modifiedAt === 'number' && Number.isFinite(r.modifiedAt) ? r.modifiedAt : 0;
  const visionState = asString(r.visionState);
  const audioState = asString(r.audioState);
  return {
    name,
    kind: r.kind === 'video' ? 'video' : 'image',
    tags: asTags(r.tags),
    extraTerms: asString(r.extraTerms),
    ocr: asString(r.ocr),
    caption: asString(r.caption),
    transcript: asString(r.transcript),
    visionState:
      visionState === 'done' || visionState === 'failed' ? (visionState as VisionState) : 'pending',
    audioState:
      audioState === 'done' || audioState === 'failed' || audioState === 'pending'
        ? (audioState as AudioState)
        : 'none',
    modifiedAt,
    embedding: asString(r.embedding),
    visualEmbedding: asString(r.visualEmbedding),
    visualModel: asString(r.visualModel),
    captionEmbedding: asString(r.captionEmbedding),
  };
}

// Returns [] for anything unreadable — a truncated write, a foreign file that
// happens to sit at the chunk path, JSON from a future format version whose
// shape we can't assume.
export function parseChunk(text: string): SidecarMeme[] {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return [];
  }
  if (!raw || typeof raw !== 'object') return [];
  const obj = raw as Record<string, unknown>;
  if (obj.format !== SIDECAR_FORMAT) return [];
  if (typeof obj.version !== 'number' || obj.version > SIDECAR_VERSION) return [];
  if (!Array.isArray(obj.memes)) return [];
  const out: SidecarMeme[] = [];
  for (const entry of obj.memes) {
    const meme = asMeme(entry);
    if (meme) out.push(meme);
  }
  return out;
}

export function parseManifest(text: string): SidecarManifest | null {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return null;
  }
  if (!raw || typeof raw !== 'object') return null;
  const obj = raw as Record<string, unknown>;
  if (obj.format !== SIDECAR_FORMAT) return null;
  if (typeof obj.version !== 'number' || obj.version > SIDECAR_VERSION) return null;
  const chunks: Record<string, ChunkStamp> = {};
  const rawChunks = obj.chunks;
  if (rawChunks && typeof rawChunks === 'object') {
    for (const [key, value] of Object.entries(rawChunks as Record<string, unknown>)) {
      if (!value || typeof value !== 'object') continue;
      const stamp = value as Record<string, unknown>;
      chunks[key] = {
        count: typeof stamp.count === 'number' ? stamp.count : 0,
        digest: asString(stamp.digest),
        // Absent in v1 manifests. 0 means "unknown", which makes the writer
        // measure the file instead of trusting a wrong length.
        bytes: typeof stamp.bytes === 'number' ? stamp.bytes : 0,
      };
    }
  }
  return {
    format: SIDECAR_FORMAT,
    version: obj.version,
    model: asString(obj.model),
    dim: typeof obj.dim === 'number' ? obj.dim : 0,
    writtenAt: typeof obj.writtenAt === 'number' ? obj.writtenAt : 0,
    memeCount: typeof obj.memeCount === 'number' ? obj.memeCount : 0,
    chunks,
    teachings: {
      count:
        obj.teachings && typeof (obj.teachings as ChunkStamp).count === 'number'
          ? (obj.teachings as ChunkStamp).count
          : 0,
      digest: obj.teachings ? asString((obj.teachings as ChunkStamp).digest) : '',
      bytes:
        obj.teachings && typeof (obj.teachings as ChunkStamp).bytes === 'number'
          ? (obj.teachings as ChunkStamp).bytes
          : 0,
    },
  };
}

// Whether a sidecar's vectors are usable here. Text knowledge always restores;
// this only gates the embeddings.
export function vectorsUsable(
  manifest: SidecarManifest,
  model = PRIMARY_EMBEDDING_MODEL.id,
  dim = PRIMARY_EMBEDDING_MODEL.dim
): boolean {
  return manifest.model === model && manifest.dim === dim;
}
