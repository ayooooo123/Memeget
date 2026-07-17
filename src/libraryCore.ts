// React-free helpers for reconciling the Library's record list, extracted so the
// identity-preservation rules (which decide whether a memoized grid cell
// re-renders) are unit-testable without a component tree.
import type { MemeRecord } from './types';
import type { ThumbPatch } from './events';

// Two records render-identically when every field the grid/viewer reads matches.
export function sameTags(a: MemeRecord['tags'], b: MemeRecord['tags']): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i].label !== b[i].label || a[i].source !== b[i].source) return false;
  }
  return true;
}

export function sameRecord(a: MemeRecord, b: MemeRecord): boolean {
  return (
    a.id === b.id &&
    a.uri === b.uri &&
    a.name === b.name &&
    a.kind === b.kind &&
    a.pending === b.pending &&
    // The poster backfill updates ONLY this field — omitting it here kept the
    // stale object alive, so freshly extracted posters never appeared until an
    // app restart even though the DB said they were done.
    a.thumbUri === b.thumbUri &&
    a.visionState === b.visionState &&
    a.caption === b.caption &&
    a.transcript === b.transcript &&
    a.ocrText === b.ocrText &&
    a.extraTerms === b.extraTerms &&
    a.indexedAt === b.indexedAt &&
    sameTags(a.tags, b.tags)
  );
}

// Re-fetching the browse list (e.g. after every shared/indexed meme) used to
// hand React a brand-new array of brand-new objects, so every memoized grid cell
// re-rendered and the list visibly hitched. This reuses the previous object for
// any row whose rendered fields are unchanged, so only genuinely new/changed
// cells re-render — and if nothing changed at all, the SAME array is returned so
// React bails out of the update entirely.
export function mergeRecords(prev: MemeRecord[], next: MemeRecord[]): MemeRecord[] {
  if (prev.length === 0) return next;
  const byId = new Map(prev.map((m) => [m.id, m]));
  let changed = next.length !== prev.length;
  const merged = next.map((r, i) => {
    const old = byId.get(r.id);
    if (old && sameRecord(old, r)) {
      if (old !== prev[i]) changed = true; // same data, but its position moved
      return old;
    }
    changed = true;
    return r;
  });
  return changed ? merged : prev;
}

// Apply video-poster patches to an existing record list IN PLACE by id: only the
// rows named in `patches` get a new object identity (with the fresh thumbUri);
// every other row keeps its exact identity, so its memoized grid cell does NOT
// re-render. Crucially, when no patch matches a row currently in the list, the
// SAME array reference is returned, so React/FlatList bail out of the update
// entirely — no re-layout, no mid-scroll hitch. This is the targeted alternative
// to a full getRecentMemes() re-fetch + mergeRecords for the common case where a
// background poster drain touches a handful of already-visible tiles.
export function patchThumbs(records: MemeRecord[], patches: ThumbPatch[]): MemeRecord[] {
  if (patches.length === 0 || records.length === 0) return records;
  const byId = new Map(patches.map((p) => [p.id, p.thumbUri]));
  let hit = false;
  const next = records.map((r) => {
    const thumbUri = byId.get(r.id);
    if (thumbUri === undefined || thumbUri === r.thumbUri) return r;
    hit = true;
    return { ...r, thumbUri };
  });
  return hit ? next : records;
}
