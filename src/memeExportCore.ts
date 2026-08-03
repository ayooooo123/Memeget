// The export lifecycle, as a pure state machine.
//
// Rendering a variation is the one operation in this app that is slow,
// cancellable, produces a file the user can lose, and has three possible
// destinations. Every one of those is a place to get the states wrong, and the
// wrong states are expensive: a double-resolve creates two memes from one
// render, a lost cancel leaves an orphan file in the cache, and a stale
// snapshot silently saves the PREVIOUS edit after the user changed something.
//
// So the transitions live here, with no React and no native calls, and the
// component only feeds events in and reads the result out.

import { makeVariationName } from './memeActionsCore';
import { extOf } from './mediaFormats';

/** Ordered: a run only ever moves forward through these. */
export const EXPORT_STAGES = ['preparing', 'segmenting', 'encoding', 'saving', 'indexing'] as const;
export type ExportStage = (typeof EXPORT_STAGES)[number];

export interface MemeExportProgress {
  stage: ExportStage;
  /** 0..1, or null when the stage genuinely cannot report a fraction. */
  progress: number | null;
  detail: string;
}

export type ExportMimeType = 'image/png' | 'video/mp4';

export interface MemeExportResult {
  /** Cache path of the rendered file. The caller owns deleting it. */
  path: string;
  name: string;
  mimeType: ExportMimeType;
  /** Non-fatal truths the user must still be told: skipped layers, codec fallbacks. */
  warnings: string[];
}

/** Where a finished render is sent. The render itself is destination-agnostic. */
export type ExportDestination = 'library' | 'clipboard' | 'downloads';

export type ExportPhase =
  | { kind: 'idle' }
  | {
      kind: 'running';
      destination: ExportDestination;
      progress: MemeExportProgress;
      /** Cancel is a REQUEST; the run is not over until the worker acknowledges. */
      cancelRequested: boolean;
      revision: number;
      runId: number;
    }
  | { kind: 'ready'; result: MemeExportResult; revision: number }
  | { kind: 'failed'; message: string; destination: ExportDestination; revision: number }
  | { kind: 'cancelled' };

export interface ExportState {
  phase: ExportPhase;
  /**
   * Files this machine knows exist on disk and nobody owns any more. The host
   * drains this and deletes them. Cancellation and superseded snapshots both
   * produce orphans, and neither is visible to the user, so leaking here is
   * silent until the cache is full.
   */
  orphans: string[];
  nextRunId: number;
}

export type ExportEvent =
  | { type: 'start'; destination: ExportDestination; revision: number }
  | { type: 'progress'; runId: number; progress: MemeExportProgress }
  | { type: 'succeeded'; runId: number; result: MemeExportResult }
  | { type: 'failed'; runId: number; message: string }
  | { type: 'cancel' }
  | { type: 'cancelAcknowledged'; runId: number; partialPath?: string | null }
  | { type: 'projectChanged'; revision: number }
  | { type: 'consumed' }
  | { type: 'dismiss' }
  | { type: 'orphansDrained' };

export function initialExportState(): ExportState {
  return { phase: { kind: 'idle' }, orphans: [], nextRunId: 1 };
}

const STAGE_INDEX = new Map<ExportStage, number>(EXPORT_STAGES.map((s, i) => [s, i]));

/** True when `next` is at or after `current` in the pipeline. */
export function stageAdvances(current: ExportStage, next: ExportStage): boolean {
  return (STAGE_INDEX.get(next) ?? 0) >= (STAGE_INDEX.get(current) ?? 0);
}

function clampProgress(value: number | null): number | null {
  if (value == null || !Number.isFinite(value)) return null;
  return Math.min(1, Math.max(0, value));
}

/**
 * Fold a reported progress into the current one.
 *
 * Media3 and the segmenter both report percentages that can jitter backwards,
 * and a backwards progress bar reads as "it's going wrong" to a user watching a
 * slow render. Within a stage the fraction only ever climbs; across stages it
 * resets, because that is a real change of activity and the detail text changes
 * with it.
 */
export function mergeExportProgress(
  current: MemeExportProgress,
  next: MemeExportProgress
): MemeExportProgress {
  if (!stageAdvances(current.stage, next.stage)) return current;
  const clamped = clampProgress(next.progress);
  if (next.stage !== current.stage) return { ...next, progress: clamped };
  const held =
    clamped != null && current.progress != null ? Math.max(clamped, current.progress) : clamped ?? current.progress;
  return { stage: current.stage, progress: held, detail: next.detail || current.detail };
}

/** The snapshot is only reusable while it still describes the current project. */
export function reusableResult(state: ExportState, revision: number): MemeExportResult | null {
  return state.phase.kind === 'ready' && state.phase.revision === revision ? state.phase.result : null;
}

export function isExportBusy(state: ExportState): boolean {
  return state.phase.kind === 'running';
}

export function memeExportReducer(state: ExportState, event: ExportEvent): ExportState {
  const { phase } = state;

  switch (event.type) {
    case 'start': {
      // A cached render of the SAME project is the whole point of caching:
      // picking a second destination must not re-encode a video.
      const reusable = reusableResult(state, event.revision);
      if (reusable) return state;
      if (phase.kind === 'running') return state;
      return {
        ...state,
        nextRunId: state.nextRunId + 1,
        // A previous ready result for a DIFFERENT revision is now dead weight.
        orphans: phase.kind === 'ready' ? [...state.orphans, phase.result.path] : state.orphans,
        phase: {
          kind: 'running',
          destination: event.destination,
          revision: event.revision,
          runId: state.nextRunId,
          cancelRequested: false,
          progress: { stage: 'preparing', progress: null, detail: 'Preparing' },
        },
      };
    }

    case 'progress': {
      if (phase.kind !== 'running' || phase.runId !== event.runId) return state;
      // Once cancel is requested the bar is frozen: still climbing while the
      // user waits for a cancel to land is a lie about what is happening.
      if (phase.cancelRequested) return state;
      const merged = mergeExportProgress(phase.progress, event.progress);
      if (merged === phase.progress) return state;
      return { ...state, phase: { ...phase, progress: merged } };
    }

    case 'succeeded': {
      if (phase.kind !== 'running' || phase.runId !== event.runId) {
        // A late success from a superseded run still made a file.
        return { ...state, orphans: [...state.orphans, event.result.path] };
      }
      if (phase.cancelRequested) {
        // Cancel won the race. The user asked for nothing, so they get nothing —
        // but the bytes exist and must be swept.
        return { ...state, phase: { kind: 'cancelled' }, orphans: [...state.orphans, event.result.path] };
      }
      return { ...state, phase: { kind: 'ready', result: event.result, revision: phase.revision } };
    }

    case 'failed': {
      if (phase.kind !== 'running' || phase.runId !== event.runId) return state;
      if (phase.cancelRequested) return { ...state, phase: { kind: 'cancelled' } };
      // Failure keeps the destination so retry means what the user first asked.
      return {
        ...state,
        phase: { kind: 'failed', message: event.message, destination: phase.destination, revision: phase.revision },
      };
    }

    case 'cancel': {
      if (phase.kind !== 'running' || phase.cancelRequested) return state;
      return { ...state, phase: { ...phase, cancelRequested: true } };
    }

    case 'cancelAcknowledged': {
      if (phase.kind !== 'running' || phase.runId !== event.runId) return state;
      return {
        ...state,
        phase: { kind: 'cancelled' },
        orphans: event.partialPath ? [...state.orphans, event.partialPath] : state.orphans,
      };
    }

    case 'projectChanged': {
      // An in-flight run is rendering the old project. Let it finish and be
      // discarded by runId rather than pretending we can retarget it.
      if (phase.kind === 'ready' && phase.revision !== event.revision) {
        return { ...state, phase: { kind: 'idle' }, orphans: [...state.orphans, phase.result.path] };
      }
      if (phase.kind === 'failed' && phase.revision !== event.revision) {
        return { ...state, phase: { kind: 'idle' } };
      }
      return state;
    }

    case 'consumed': {
      // The result was written somewhere durable; the cache copy is finished.
      if (phase.kind !== 'ready') return state;
      return { ...state, phase: { kind: 'idle' }, orphans: [...state.orphans, phase.result.path] };
    }

    case 'dismiss': {
      if (phase.kind === 'failed' || phase.kind === 'cancelled') return { ...state, phase: { kind: 'idle' } };
      return state;
    }

    case 'orphansDrained':
      return state.orphans.length === 0 ? state : { ...state, orphans: [] };

    default:
      return state;
  }
}

export interface ExportOutputSpec {
  name: string;
  mimeType: ExportMimeType;
}

/**
 * What the rendered file is called and what it actually is.
 *
 * Video always lands as MP4 — including WebM sources, which is the entire
 * reason the copy path transcodes. The name carries a UTC timestamp because a
 * user can export the same meme twice with different edits, and two different
 * files sharing a name is how you lose one.
 */
export function exportOutputSpec(
  sourceName: string,
  kind: 'image' | 'video',
  now = Date.now()
): ExportOutputSpec {
  return kind === 'video'
    ? { name: makeVariationName(sourceName, 'mp4', now), mimeType: 'video/mp4' }
    : { name: makeVariationName(sourceName, 'png', now), mimeType: 'image/png' };
}

/**
 * Make a name that does not collide with `taken`.
 *
 * makeVariationName resolves to the second, so two exports inside the same
 * second collide — rare, but a tap-happy user hits it, and MediaStore's own
 * de-duplication renames the file to "foo (1).png" behind our back, which then
 * does not match the name we reported in the toast.
 */
export function uniqueExportName(name: string, taken: Iterable<string>): string {
  const used = new Set(taken);
  if (!used.has(name)) return name;
  const dot = name.lastIndexOf('.');
  const base = dot > 0 ? name.slice(0, dot) : name;
  const ext = dot > 0 ? name.slice(dot) : '';
  for (let i = 2; ; i += 1) {
    const candidate = `${base}-${i}${ext}`;
    if (!used.has(candidate)) return candidate;
  }
}

/** Human summary of a finished export, including truths the render had to bend. */
export function exportSummary(result: MemeExportResult, destinationLabel: string): string {
  if (result.warnings.length === 0) return `Saved to ${destinationLabel}`;
  return `Saved to ${destinationLabel} — ${result.warnings.join('; ')}`;
}

/** A skipped layer is a warning, never a silent omission. */
export function skippedLayerWarning(count: number): string[] {
  if (count <= 0) return [];
  return [`${count} layer${count === 1 ? '' : 's'} could not be rendered`];
}
