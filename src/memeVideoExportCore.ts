// What a VIDEO export adds to the lifecycle in `memeExportCore`.
//
// The state machine there is destination- and media-agnostic on purpose, and it
// stays that way. Video brings exactly two rules of its own, both of which are
// only interesting because they are the ones that leak or lie when they are
// written inline in a component:
//
//  * progress arrives from a native encoder as loose strings, and a stage this
//    build does not know is not a reason to move the bar somewhere arbitrary;
//  * a cancel that lands while the encoder is finishing must still win, because
//    the alternative is saving the meme the user just cancelled.
import {
  EXPORT_STAGES,
  mergeExportProgress,
  type MemeExportProgress,
} from './memeExportCore';

/** One `onVideoExportProgress` payload, as it crosses the bridge: unvalidated. */
export interface NativeExportProgressReport {
  stage: string;
  progress: number | null;
  detail: string;
}

/**
 * Fold a native progress report into the one on screen.
 *
 * An unrecognized stage is dropped rather than guessed at: the stage drives
 * both the label and the ordering, so a future native build that reports a
 * stage this JS does not have must leave the bar where it is instead of
 * resetting it. Everything else — clamping, monotonicity, the stage ordering —
 * is `mergeExportProgress`'s job, not restated here.
 */
export function foldNativeExportProgress(
  current: MemeExportProgress,
  report: NativeExportProgressReport
): MemeExportProgress {
  const stage = EXPORT_STAGES.find((candidate) => candidate === report.stage);
  if (!stage) return current;
  const progress =
    typeof report.progress === 'number' && Number.isFinite(report.progress) ? report.progress : null;
  return mergeExportProgress(current, {
    stage,
    progress,
    detail: typeof report.detail === 'string' ? report.detail : '',
  });
}

/** Cancellation is an outcome, not a failure, and the UI has to tell them apart. */
export class ExportCancelledError extends Error {
  constructor(message = 'The export was cancelled') {
    super(message);
    this.name = 'ExportCancelledError';
  }
}

export interface CancellableRender<T> {
  /** The render itself. Null means this build cannot render, which is not a cancel. */
  render: () => Promise<T | null>;
  /** Whether the user has asked to stop. Read again AFTER the render settles. */
  cancelled: () => boolean;
  /** Throw away a render that arrived after the cancel. Must not throw. */
  discard: (value: T) => Promise<void>;
}

/**
 * Run a render that a user can cancel, and make sure the cancel wins.
 *
 * The native exporter rejects a cancelled run, so the interesting case is the
 * one it cannot cover: the encoder finished, the file is on disk, and the
 * cancel landed while the result was in flight back to JS. Delivering it would
 * save a meme the user explicitly stopped, so the file is discarded and the
 * caller sees a cancellation — which is also what keeps the export state
 * machine out of `ready`, so nothing downstream can reuse the snapshot.
 */
export async function renderUnlessCancelled<T>(effects: CancellableRender<T>): Promise<T | null> {
  if (effects.cancelled()) throw new ExportCancelledError();
  let value: T | null;
  try {
    value = await effects.render();
  } catch (error) {
    // A render that failed because it was cancelled is a cancellation; the
    // native message for it is an implementation detail the user never asked
    // about.
    if (effects.cancelled()) throw new ExportCancelledError();
    throw error;
  }
  if (value == null) return null;
  if (effects.cancelled()) {
    await effects.discard(value);
    throw new ExportCancelledError();
  }
  return value;
}
