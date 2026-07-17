// Tiny in-process event bus so background flows (e.g. accepting a meme shared
// into the app) can tell the Library to refresh without prop-drilling through
// the tab shell.
type Listener = () => void;

const listeners = new Set<Listener>();

export function onLibraryChanged(cb: Listener): () => void {
  listeners.add(cb);
  return () => {
    listeners.delete(cb);
  };
}

export function emitLibraryChanged(): void {
  for (const cb of listeners) cb();
}

// A video poster (thumb_uri) landed for one or more already-visible memes. This
// is deliberately a SEPARATE channel from onLibraryChanged: a poster changes
// only a row's thumbnail, not its membership, order, or search text. The Library
// patches those rows in place by id instead of re-fetching and re-merging the
// whole loaded span — which was re-rendering memoized grid cells mid-scroll and
// causing the flick-scroll jitter (see docs/handoff-issues.md Issue 1).
export interface ThumbPatch {
  id: number;
  thumbUri: string;
}
type ThumbListener = (patches: ThumbPatch[]) => void;

const thumbListeners = new Set<ThumbListener>();

export function onThumbsUpdated(cb: ThumbListener): () => void {
  thumbListeners.add(cb);
  return () => {
    thumbListeners.delete(cb);
  };
}

export function emitThumbsUpdated(patches: ThumbPatch[]): void {
  if (patches.length === 0) return;
  for (const cb of thumbListeners) cb(patches);
}
