// In-memory search index: decoded vectors + a precomputed lexical haystack for
// every fully-indexed meme, held once and reused across keystrokes.
//
// Why this exists: text search used to run `SELECT * FROM memes WHERE pending=0`
// on EVERY debounced keystroke, then per row re-decode two float32 BLOBs and
// rebuild the lowercased search haystack before scoring. The dot products were
// never the bottleneck — the per-keystroke re-marshal + re-decode + haystack
// rebuild was. This module does that work ONCE, keeps the decoded
// `Float32Array`s and haystacks resident, and rebuilds only when the searchable
// content or membership of the library actually changes (see
// `invalidateSearchIndex`). Scoring then reads straight off the cached entries.
//
// Deliberately DB-free and React-free: the caller injects a `load` thunk (which
// does the one SELECT), so this whole module is unit-testable with synthetic
// rows and shares nothing with the native-backed db module.
//
// Memory: the resident cost is image+caption vectors ≈ N × dim × 4 × 2 bytes
// (~40 MB at 10k memes / 512-dim, ~400 MB at 100k). Comfortable to the tens of
// thousands; a much larger library is the point where an on-disk native vector
// index (sqlite-vec) earns its keep. `visual_embedding` (DINOv2) is deliberately
// NOT cached here — text search never uses it.
import type { MediaKind, MemeRecord } from './types';

export interface SearchCacheEntry {
  id: number;
  kind: MediaKind;
  imageVec: Float32Array;
  captionVec: Float32Array | null;
  // Raw (not lowercased) haystack, matched with `.includes` against
  // already-lowercased query terms — identical to the previous inline behavior.
  searchText: string;
  // Everything the UI needs to render a hit (a plain MemeRecord — the heavy
  // decoded vector is kept separately in imageVec, not on the record).
  record: MemeRecord;
}

let entries: SearchCacheEntry[] | null = null;
let dirty = true;
let building: Promise<SearchCacheEntry[]> | null = null;

// Mark the cache stale. Cheap and idempotent — the next `ensureSearchIndex`
// rebuilds. Call from every mutator that changes searchable content
// (embedding, caption_embedding, ocr_text, name, caption, transcript, tags,
// extra_terms) or membership (a row entering/leaving pending=0). Do NOT call it
// for poster/DINO writes: those don't touch any field text search reads, and
// busting the cache mid-drain would re-pay the rebuild for nothing.
export function invalidateSearchIndex(): void {
  dirty = true;
}

// Return the resident entries, rebuilding via `load` only when stale. Concurrent
// callers during a build share the one in-flight build instead of each issuing
// their own SELECT. An invalidation that lands mid-build re-flags `dirty`, so the
// very next call rebuilds against the newer data (the in-flight result is at
// worst one keystroke stale and is immediately superseded).
export async function ensureSearchIndex(
  load: () => Promise<SearchCacheEntry[]>
): Promise<SearchCacheEntry[]> {
  if (!dirty && entries) return entries;
  if (building) return building;
  // Snapshot point: clearing dirty BEFORE the load means any invalidate() during
  // the load flips it back to true and forces a follow-up rebuild.
  dirty = false;
  building = (async () => {
    try {
      const built = await load();
      entries = built;
      return built;
    } catch (e) {
      // A failed build must not leave a fresh flag — retry next time.
      dirty = true;
      throw e;
    } finally {
      building = null;
    }
  })();
  return building;
}

// Test/diagnostic hook: current resident entries without triggering a build.
export function peekSearchIndex(): SearchCacheEntry[] | null {
  return entries;
}

// Test hook: drop all state so each test starts cold.
export function resetSearchIndexForTest(): void {
  entries = null;
  dirty = true;
  building = null;
}
