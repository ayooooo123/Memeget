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

export interface SearchCachePatch {
  id: number;
  record: Partial<MemeRecord>;
  searchText: string;
}

// Patch fields that do not alter vector identity (currently tags/extra terms)
// without throwing away every decoded embedding. Returns false when no stable
// resident cache exists so the caller can fall back to full invalidation.
export function patchSearchIndexEntries(patches: readonly SearchCachePatch[]): boolean {
  if (dirty || building || !entries) return false;
  const byId = new Map(patches.map((patch) => [patch.id, patch]));
  let matched = 0;
  entries = entries.map((entry) => {
    const patch = byId.get(entry.id);
    if (!patch) return entry;
    matched++;
    return {
      ...entry,
      searchText: patch.searchText,
      record: { ...entry.record, ...patch.record },
    };
  });
  return matched === patches.length;
}

// Return the resident entries, rebuilding via `load` only when stale. Concurrent
// callers during a build share the one in-flight build instead of each issuing
// their own SELECT. An invalidation that lands mid-build re-flags `dirty`, and
// the in-flight build itself loops until it completes a load with no pending
// invalidation — so the shared promise NEVER resolves to data that predates a
// write already committed, and a just-written transcript is searchable on the
// first query after it lands.
export async function ensureSearchIndex(
  load: () => Promise<SearchCacheEntry[]>
): Promise<SearchCacheEntry[]> {
  // A build already in flight is authoritative: return it rather than the
  // fast-path cache, so a caller can never receive an intermediate snapshot the
  // in-flight build is about to supersede.
  if (building) return building;
  if (!dirty && entries) return entries;
  building = (async () => {
    try {
      // Rebuild until we complete a load that no invalidation superseded. A
      // write (e.g. a transcript) landing mid-load flips `dirty` back to true;
      // without this loop the in-flight build — shared by every concurrent
      // caller — would resolve to data predating that write, and the stale
      // result would sit on screen until the query changed, making a
      // just-written transcript look unsearchable.
      let built: SearchCacheEntry[];
      do {
        dirty = false;
        built = await load();
        entries = built;
      } while (dirty);
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
