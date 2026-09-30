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
let fullDirty = true;
// Rows whose searchable content changed while the rest of the index stayed
// valid. Reloading three rows beats re-marshalling and re-decoding the whole
// library, which is what an import (share a meme → insert → describe → caption
// vector → transcript, every step a write) used to cost on the next keystroke.
const dirtyIds = new Set<number>();
let building: Promise<SearchCacheEntry[]> | null = null;
// Bumped only when the resident array is rebuilt from a FULL load, never by a
// row splice. Downstream structures derived from the corpus (the fuzzy search
// vocabulary) use it to tell "a few rows changed, grow what you have" from
// "the library was replaced, start over" — an array-identity check alone
// cannot, because a splice also returns a new array.
let generation = 0;

function isDirty(): boolean {
  return fullDirty || dirtyIds.size > 0;
}

// Mark the whole cache stale. Cheap and idempotent — the next `ensureSearchIndex`
// rebuilds. Call from every mutator that changes searchable content
// (embedding, caption_embedding, ocr_text, name, caption, transcript, tags,
// extra_terms) or membership (a row entering/leaving pending=0) for an unknown
// or library-wide set of rows. Do NOT call it for poster/DINO writes: those
// don't touch any field text search reads, and busting the cache mid-drain
// would re-pay the rebuild for nothing.
export function invalidateSearchIndex(): void {
  fullDirty = true;
  dirtyIds.clear();
}

// Same, for a known set of rows: the next `ensureSearchIndex` reloads only
// these (via its `loadRows` thunk) and splices them into the resident array.
// Covers insert, delete and pending→indexed transitions as well as content
// edits — a row the reload doesn't return has left the searchable set and is
// dropped. Row dirt is welcome mid-build — `ensureSearchIndex`'s loop picks it
// up on the next iteration — but degrades to a full invalidation before the
// first successful load, when there is no resident array to splice into.
export function invalidateSearchIndexRows(ids: readonly number[]): void {
  if (ids.length === 0) return;
  if (!entries) {
    invalidateSearchIndex();
    return;
  }
  for (const id of ids) dirtyIds.add(id);
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
  if (isDirty() || building || !entries) return false;
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
  load: () => Promise<SearchCacheEntry[]>,
  loadRows?: (ids: readonly number[]) => Promise<SearchCacheEntry[]>
): Promise<SearchCacheEntry[]> {
  // A build already in flight is authoritative: return it rather than the
  // fast-path cache, so a caller can never receive an intermediate snapshot the
  // in-flight build is about to supersede.
  if (building) return building;
  if (!isDirty() && entries) return entries;
  building = (async () => {
    try {
      // Rebuild until we complete a load that no invalidation superseded. A
      // write (e.g. a transcript) landing mid-load flips the cache dirty again;
      // without this loop the in-flight build — shared by every concurrent
      // caller — would resolve to data predating that write, and the stale
      // result would sit on screen until the query changed, making a
      // just-written transcript look unsearchable. Row-level dirt takes the
      // incremental path, so the loop costs one small SELECT per iteration
      // instead of re-reading the library once per write.
      let built: SearchCacheEntry[];
      do {
        const resident = entries;
        if (!fullDirty && resident && loadRows && dirtyIds.size > 0) {
          const ids = [...dirtyIds];
          dirtyIds.clear();
          built = spliceRows(resident, ids, await loadRows(ids));
        } else {
          fullDirty = false;
          dirtyIds.clear();
          built = await load();
          generation++;
        }
        entries = built;
      } while (isDirty());
      return built;
    } catch (e) {
      // A failed build must not leave a fresh flag — and the ids it consumed are
      // gone, so fall back to the safe superset: reload everything next time.
      invalidateSearchIndex();
      throw e;
    } finally {
      building = null;
    }
  })();
  return building;
}

// Fold reloaded rows into the resident array, preserving order. A requested id
// the reload didn't return has left the searchable set (deleted, or back to
// pending) and is dropped; an id that wasn't resident is appended. Returns a NEW
// array so identity-keyed memos downstream (the fuzzy vocab) still see a change.
function spliceRows(
  resident: readonly SearchCacheEntry[],
  requestedIds: readonly number[],
  rows: readonly SearchCacheEntry[]
): SearchCacheEntry[] {
  const byId = new Map(rows.map((row) => [row.id, row]));
  const requested = new Set(requestedIds);
  const out: SearchCacheEntry[] = [];
  for (const entry of resident) {
    const reloaded = byId.get(entry.id);
    if (reloaded) {
      out.push(reloaded);
      byId.delete(entry.id);
      continue;
    }
    if (!requested.has(entry.id)) out.push(entry);
  }
  for (const row of byId.values()) out.push(row);
  return out;
}

// Test/diagnostic hook: current resident entries without triggering a build.
export function peekSearchIndex(): SearchCacheEntry[] | null {
  return entries;
}

// Which full rebuild the resident entries came from. See `generation`.
export function searchIndexGeneration(): number {
  return generation;
}

// Test hook: drop all state so each test starts cold.
export function resetSearchIndexForTest(): void {
  entries = null;
  fullDirty = true;
  dirtyIds.clear();
  building = null;
  generation = 0;
}
