// A react-native-executorch model that is loaded on first use, runs one call
// at a time, and unloads itself after sitting idle. The app already holds
// multi-GB models; small single-purpose ones (OCR, cutouts) must not add their
// weights for the whole session just because they ran once.
//
// A failed load (usually: first use while offline, so the weights never
// downloaded) makes `run` resolve null — the feature is unavailable — and is
// not retried until `retryMs` has passed, so a batch of calls doesn't re-pay a
// doomed download each time.

export interface OnDemandModel<M> {
  // Run `fn` against the loaded model; null when the model could not be loaded.
  // Errors thrown by `fn` itself propagate.
  run<T>(fn: (model: M) => Promise<T>, onDownloadProgress?: (fraction: number) => void): Promise<T | null>;
}

export function onDemandModel<M extends { delete(): void }>(opts: {
  name: string;
  load: (onDownloadProgress: (fraction: number) => void) => Promise<M>;
  idleMs: number;
  retryMs: number;
}): OnDemandModel<M> {
  let model: M | null = null;
  let loadFailedAt = -Infinity;
  let idleTimer: ReturnType<typeof setTimeout> | null = null;
  let tail: Promise<unknown> = Promise.resolve();

  const release = () => {
    idleTimer = null;
    const held = model;
    model = null;
    if (!held) return;
    try {
      held.delete();
    } catch {
      // already gone
    }
  };

  return {
    run<T>(fn: (model: M) => Promise<T>, onDownloadProgress?: (fraction: number) => void): Promise<T | null> {
      const job = tail.then(async (): Promise<T | null> => {
        clearTimeout(idleTimer ?? undefined);
        idleTimer = null;
        try {
          if (!model) {
            if (Date.now() - loadFailedAt < opts.retryMs) return null;
            try {
              model = await opts.load(onDownloadProgress ?? (() => {}));
            } catch (e) {
              loadFailedAt = Date.now();
              console.log(`[memeget/${opts.name}] model load failed: ${String(e).slice(0, 200)}`);
              return null;
            }
          }
          return await fn(model);
        } finally {
          idleTimer = setTimeout(release, opts.idleMs);
        }
      });
      tail = job.catch(() => {});
      return job;
    },
  };
}
