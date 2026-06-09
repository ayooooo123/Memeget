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
