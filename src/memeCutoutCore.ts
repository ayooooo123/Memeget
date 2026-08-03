// Still-image subject cutouts: the parts that are geometry, state and policy.
//
// Native code owns the pixels — ML Kit produces the masks, writes one cutout PNG
// per subject, and hands back references. That split is deliberate: a
// full-resolution alpha channel in the JS heap is how this app OOM'd before, so
// nothing here ever holds a bitmap. What lives here is everything that can be
// wrong without crashing:
//
//   * the memory ceiling, derived rather than hoped for;
//   * the request lifecycle, including a model download that has to be
//     cancellable and three failures whose remedies differ;
//   * "no subject found", which is an ANSWER and not an error;
//   * which subject the user picked, and the layers/mask tracks that produces.
//
// No React, no native calls, no clock, no randomness: callers supply ids.

import {
  PROJECT_LIMITS,
  clampNormalizedPoint,
  clampNormalizedRect,
  type BackgroundSpec,
  type MaskTrackSpec,
  type MemeEditProject,
  type NormalizedPoint,
  type NormalizedRect,
  type SubjectLayer,
} from './memeEditProjectCore';
import type { NativeSubjectCutout, NativeSubjectCutoutResult } from '../modules/memeget-bg';

/**
 * Transient bytes one segmentation request may allocate natively.
 *
 * Mirrors MemeStillSubjectSegmenter.MEMORY_CEILING_BYTES. Two copies of a number
 * is a real risk, so the plan this module produces reports the working size it
 * derived and the native side reports what it actually used — a drift shows up
 * as a mismatch the studio can display rather than as an OOM.
 */
export const CUTOUT_MEMORY_CEILING_BYTES = 96 * 1024 * 1024;

/** Mirrors MemeStillSubjectSegmenter.MAX_WORKING_EDGE. */
export const CUTOUT_MAX_WORKING_EDGE = 2048;

/** ML Kit documents 512x512 as its accuracy floor. We report, never upscale. */
export const CUTOUT_RECOMMENDED_MIN_EDGE = 512;

/** Decoded ARGB source + confidence float + cutout ARGB, per working pixel. */
export const CUTOUT_BYTES_PER_WORKING_PIXEL = 12;

/**
 * Ceiling on ONE cutout's decoded size at export.
 *
 * The renderer draws cutout layers one at a time, so the peak is the largest
 * single cutout — not their sum — and this is the number that has to stay under
 * the renderer's own budget. Bounding the count as well ([MAX_CUTOUT_LAYERS])
 * is what keeps the cache and the layer list finite.
 */
export const CUTOUT_DECODE_CEILING_BYTES = 64 * 1024 * 1024;

/** Cutout layers one project may hold. Well under the 64-layer project limit. */
export const MAX_CUTOUT_LAYERS = 8;

/** Mirrors MemeStillSubjectSegmenter.MAX_SUBJECTS. */
export const MAX_CUTOUT_SUBJECTS = 8;

/**
 * Native failure codes. Exported so the bridge, this module and the tests
 * cannot disagree about the strings that cross the boundary.
 */
export const CUTOUT_FAILURE_CODES = Object.freeze({
  offline: 'E_CUTOUT_OFFLINE',
  moduleUnavailable: 'E_CUTOUT_MODULE_UNAVAILABLE',
  cancelled: 'E_CUTOUT_CANCELLED',
  failed: 'E_CUTOUT_FAILED',
});

// --- segmentation plan ------------------------------------------------------

export interface CutoutSegmentationPlan {
  /** Power of two, the same meaning as BitmapFactory.inSampleSize. */
  sampleSize: number;
  workingWidth: number;
  workingHeight: number;
  estimatedPeakBytes: number;
  ceilingBytes: number;
  downscaled: boolean;
  /** Source smaller than ML Kit's documented accuracy floor. */
  belowRecommendedResolution: boolean;
}

export interface CutoutPlanOptions {
  ceilingBytes?: number;
  maxWorkingEdge?: number;
}

/**
 * The size segmentation will really run at, derived from the ceiling.
 *
 * Mirrors the native derivation step for step so the studio can state the cost
 * (and the quality caveat) BEFORE the user waits for a download. Halving until
 * it fits is not an approximation of the native behaviour — inSampleSize is
 * powers of two, so it is the same set of choices.
 */
export function planSubjectSegmentation(
  source: { width: number; height: number },
  options: CutoutPlanOptions = {}
): CutoutSegmentationPlan {
  const width = Math.floor(source.width);
  const height = Math.floor(source.height);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    throw new TypeError('planSubjectSegmentation requires positive source dimensions.');
  }
  const ceilingBytes = Math.max(1, Math.floor(options.ceilingBytes ?? CUTOUT_MEMORY_CEILING_BYTES));
  const maxEdge = Math.max(1, Math.floor(options.maxWorkingEdge ?? CUTOUT_MAX_WORKING_EDGE));
  let sampleSize = 1;
  for (;;) {
    const workingWidth = Math.max(1, Math.floor(width / sampleSize));
    const workingHeight = Math.max(1, Math.floor(height / sampleSize));
    const estimatedPeakBytes = workingWidth * workingHeight * CUTOUT_BYTES_PER_WORKING_PIXEL;
    const fitsEdge = Math.max(workingWidth, workingHeight) <= maxEdge;
    const fitsCeiling = estimatedPeakBytes <= ceilingBytes;
    if ((fitsEdge && fitsCeiling) || (workingWidth <= 1 && workingHeight <= 1)) {
      return {
        sampleSize,
        workingWidth,
        workingHeight,
        estimatedPeakBytes,
        ceilingBytes,
        downscaled: sampleSize > 1,
        belowRecommendedResolution:
          Math.min(workingWidth, workingHeight) < CUTOUT_RECOMMENDED_MIN_EDGE,
      };
    }
    sampleSize *= 2;
  }
}

// --- failures ---------------------------------------------------------------

export type CutoutOutcome = 'offline' | 'module-unavailable' | 'cancelled' | 'failed';

export type CutoutFailureKind = Exclude<CutoutOutcome, 'cancelled'>;

export interface CutoutFailure {
  kind: CutoutFailureKind;
  /** What happened, in one sentence. */
  message: string;
  /** What the user can DO about it. Different for each kind — that is the point. */
  remedy: string;
  /** Whether trying the same thing again could plausibly work. */
  retryable: boolean;
  /** Native detail, kept for logs. Never the user-facing text. */
  detail: string | null;
}

/**
 * Map a native code onto an outcome.
 *
 * Codes, never message matching: ML Kit's strings are not API, and mistaking an
 * offline device for an unsupported one sends the user to fix the wrong thing.
 * Anything unrecognized is a genuine failure rather than a guess.
 */
export function classifyCutoutFailure(code: string | null | undefined): CutoutOutcome {
  switch (code) {
    case CUTOUT_FAILURE_CODES.offline:
      return 'offline';
    case CUTOUT_FAILURE_CODES.moduleUnavailable:
      return 'module-unavailable';
    case CUTOUT_FAILURE_CODES.cancelled:
      return 'cancelled';
    default:
      return 'failed';
  }
}

/**
 * The three failures, with three remedies.
 *
 * Offline is temporary and the user fixes it by connecting. Unavailable is a
 * property of the device and no amount of retrying helps — say so, and point at
 * the tool that does work. A real segmentation failure is about THIS image.
 */
export function cutoutFailureFor(
  code: string | null | undefined,
  detail: string | null = null
): CutoutFailure | null {
  const outcome = classifyCutoutFailure(code);
  if (outcome === 'cancelled') return null;
  if (outcome === 'offline') {
    return {
      kind: 'offline',
      message: 'Memeget could not download the cutout model.',
      remedy: 'Connect to Wi-Fi or mobile data and try again. It downloads once, then cutouts work offline.',
      retryable: true,
      detail,
    };
  }
  if (outcome === 'module-unavailable') {
    return {
      kind: 'module-unavailable',
      message: 'Subject cutouts are not available on this device.',
      remedy: 'Google Play services could not install the cutout model. Update Play services, or use Cover to hide a region instead.',
      retryable: false,
      detail,
    };
  }
  return {
    kind: 'failed',
    message: 'The cutout did not work on this image.',
    remedy: 'Try an image where the subject stands out from the background, or use Cover instead.',
    retryable: true,
    detail,
  };
}

// --- results ----------------------------------------------------------------

export interface CutoutRef {
  id: string;
  /** null for the combined "all subjects" cutout. */
  subjectIndex: number | null;
  cutoutUri: string;
  /** Where the cutout sits in the EXIF-oriented, uncropped source frame. */
  bounds: NormalizedRect;
  widthPx: number;
  heightPx: number;
  coverage: number;
  bytes: number;
}

export interface CutoutResult {
  requestId: string;
  sourceUri: string;
  sourceWidth: number;
  sourceHeight: number;
  workingWidth: number;
  workingHeight: number;
  /** Always present in a result: an empty segmentation is not a result. */
  combined: CutoutRef;
  subjects: CutoutRef[];
  droppedSubjects: number;
  belowRecommendedResolution: boolean;
}

// Geometry goes through the project's own clamp/round helpers, not a second
// implementation: the render plan is serialized as the export revision, so a
// centre of 0.44999999999999996 instead of 0.45 would invalidate a cached
// render for no visible reason.


function cutoutRef(native: NativeSubjectCutout): CutoutRef | null {
  if (!native.cutoutUri || native.widthPx <= 0 || native.heightPx <= 0) return null;
  const bounds = clampNormalizedRect(native.bounds);
  if (bounds.width <= 0 || bounds.height <= 0) return null;
  return {
    id: native.id,
    subjectIndex: native.subjectIndex,
    cutoutUri: native.cutoutUri,
    bounds,
    widthPx: native.widthPx,
    heightPx: native.heightPx,
    coverage: Math.min(1, Math.max(0, native.coverage)),
    bytes: Math.max(0, native.bytes),
  };
}

/**
 * Adopt a native result, or `null` when the image has no subject.
 *
 * The null is the honest half of this function: ML Kit succeeding on a photo of
 * a wall is a successful call with nothing in it, and the studio has to say "no
 * subject found" rather than "cutout failed" — the user did nothing wrong and
 * there is nothing to retry.
 */
export function cutoutResultFromNative(
  native: NativeSubjectCutoutResult,
  sourceUri: string
): CutoutResult | null {
  if (!native.combined) return null;
  const combined = cutoutRef(native.combined);
  if (!combined) return null;
  const subjects: CutoutRef[] = [];
  for (const candidate of native.subjects.slice(0, MAX_CUTOUT_SUBJECTS)) {
    const ref = cutoutRef(candidate);
    if (ref && ref.subjectIndex !== null) subjects.push(ref);
  }
  return {
    requestId: native.requestId,
    sourceUri,
    sourceWidth: native.sourceWidth,
    sourceHeight: native.sourceHeight,
    workingWidth: native.workingWidth,
    workingHeight: native.workingHeight,
    combined,
    subjects,
    droppedSubjects: Math.max(0, native.droppedSubjects),
    belowRecommendedResolution:
      Math.min(native.workingWidth, native.workingHeight) < CUTOUT_RECOMMENDED_MIN_EDGE,
  };
}

// --- selection --------------------------------------------------------------

export type CutoutSelection = { kind: 'all' } | { kind: 'subject'; index: number };

export function selectedCutoutRef(
  result: CutoutResult,
  selection: CutoutSelection
): CutoutRef | null {
  if (selection.kind === 'all') return result.combined;
  return result.subjects.find((subject) => subject.subjectIndex === selection.index) ?? null;
}

export interface CutoutSelectionOption {
  selection: CutoutSelection;
  label: string;
  /** Fraction of the frame this choice covers; shown so the picker is not a guess. */
  coverage: number;
}

/**
 * The choices to offer, in source order.
 *
 * NOT sorted by coverage: the labels are positional ("Subject 2"), and reordering
 * them between two segmentations of the same photo would move the button under
 * the user's finger. "All subjects" is only worth offering when there is more
 * than one — otherwise it is the same pixels as Subject 1 under two names.
 */
export function cutoutSelectionOptions(result: CutoutResult): CutoutSelectionOption[] {
  const options: CutoutSelectionOption[] = [];
  if (result.subjects.length !== 1) {
    options.push({
      selection: { kind: 'all' },
      label: 'All subjects',
      coverage: result.combined.coverage,
    });
  }
  result.subjects.forEach((subject, position) => {
    options.push({
      selection: { kind: 'subject', index: subject.subjectIndex as number },
      label: `Subject ${position + 1}`,
      coverage: subject.coverage,
    });
  });
  return options;
}

// --- backgrounds ------------------------------------------------------------

export type CutoutBackgroundMode = 'transparent' | 'solid' | 'blurred-source' | 'image';

export interface CutoutBackgroundChoice {
  mode: CutoutBackgroundMode;
  color?: string;
  assetUri?: string | null;
  blurScale?: number;
}

export const CUTOUT_BACKGROUND_MODES: readonly {
  mode: CutoutBackgroundMode;
  label: string;
  hint: string;
}[] = Object.freeze([
  { mode: 'transparent', label: 'Transparent', hint: 'Export a PNG with a real alpha channel' },
  { mode: 'solid', label: 'Solid colour', hint: 'Fill everything behind the subject' },
  { mode: 'blurred-source', label: 'Blur behind', hint: 'Keep the photo, blurred behind the subject' },
  { mode: 'image', label: 'Replace image', hint: 'Put the subject on another picture' },
]);

export const DEFAULT_CUTOUT_BACKGROUND_COLOR = '#000000';
export const DEFAULT_CUTOUT_BLUR_SCALE = 0.6;

export function cutoutBackgroundRequiresAsset(mode: CutoutBackgroundMode): boolean {
  return mode === 'image';
}

/**
 * The project background a cutout choice means.
 *
 * `blurScale` only survives on the mode that uses it, and `assetUri` only on the
 * mode that needs it — the project validator rejects an asset on a mode that
 * cannot have one, so silently carrying a stale value would produce a project
 * that fails validation on the next save.
 */
export function cutoutBackgroundSpec(choice: CutoutBackgroundChoice): BackgroundSpec {
  const color = choice.color ?? DEFAULT_CUTOUT_BACKGROUND_COLOR;
  const blurScale = Math.min(1, Math.max(0, choice.blurScale ?? DEFAULT_CUTOUT_BLUR_SCALE));
  if (choice.mode === 'image') {
    return { mode: 'image', color, assetUri: choice.assetUri ?? null, blurScale: 0 };
  }
  if (choice.mode === 'blurred-source') {
    return { mode: 'blurred-source', color, assetUri: null, blurScale };
  }
  if (choice.mode === 'solid') {
    return { mode: 'solid', color, assetUri: null, blurScale: 0 };
  }
  return { mode: 'transparent', color, assetUri: null, blurScale: 0 };
}

// --- sticker effects --------------------------------------------------------

export interface CutoutStickerChoice {
  outline: boolean;
  outlineColor: string;
  outlineScale: number;
  shadow: boolean;
  shadowScale: number;
  duplicate: boolean;
  duplicateOffset: NormalizedPoint;
}

export const DEFAULT_CUTOUT_STICKER: Readonly<CutoutStickerChoice> = Object.freeze({
  outline: false,
  outlineColor: '#FFFFFF',
  outlineScale: 0.35,
  shadow: false,
  shadowScale: 0.4,
  duplicate: false,
  duplicateOffset: Object.freeze({ x: 0.04, y: 0.04 }) as NormalizedPoint,
});

/**
 * Clamp a sticker choice into what the project validator accepts.
 *
 * outlineScale/shadowScale are unit numbers there, so an out-of-range slider
 * value has to be clamped here rather than rejected three screens later.
 */
export function normalizeCutoutSticker(
  partial: Partial<CutoutStickerChoice> = {}
): CutoutStickerChoice {
  const offset = partial.duplicateOffset ?? DEFAULT_CUTOUT_STICKER.duplicateOffset;
  const unit = (value: number | undefined, fallback: number): number => {
    if (value == null || !Number.isFinite(value)) return fallback;
    return Math.min(1, Math.max(0, value));
  };
  const signedUnit = (value: number, fallback: number): number => {
    if (!Number.isFinite(value)) return fallback;
    return Math.min(1, Math.max(-1, value));
  };
  return {
    outline: partial.outline ?? DEFAULT_CUTOUT_STICKER.outline,
    outlineColor: partial.outlineColor ?? DEFAULT_CUTOUT_STICKER.outlineColor,
    outlineScale: unit(partial.outlineScale, DEFAULT_CUTOUT_STICKER.outlineScale),
    shadow: partial.shadow ?? DEFAULT_CUTOUT_STICKER.shadow,
    shadowScale: unit(partial.shadowScale, DEFAULT_CUTOUT_STICKER.shadowScale),
    duplicate: partial.duplicate ?? DEFAULT_CUTOUT_STICKER.duplicate,
    duplicateOffset: {
      x: signedUnit(offset.x, DEFAULT_CUTOUT_STICKER.duplicateOffset.x),
      y: signedUnit(offset.y, DEFAULT_CUTOUT_STICKER.duplicateOffset.y),
    },
  };
}

// --- applying ---------------------------------------------------------------

export type CutoutRefusalReason =
  | 'no-cutout'
  | 'layer-limit'
  | 'cutout-layer-limit'
  | 'mask-track-limit'
  | 'memory-ceiling'
  | 'background-asset-missing';

export interface CutoutApplication {
  /** Draw order: index 0 is drawn first (furthest back). */
  layers: SubjectLayer[];
  maskTracks: MaskTrackSpec[];
  /** Mask track id -> materialized cutout uri, for project.transient.maskTracks. */
  transientMaskTracks: Record<string, string>;
  background: BackgroundSpec;
}

export type CutoutApplicationOutcome =
  | { ok: true; application: CutoutApplication }
  | { ok: false; reason: CutoutRefusalReason; message: string };

export interface CutoutApplicationInput {
  project: MemeEditProject;
  result: CutoutResult;
  selection: CutoutSelection;
  background: CutoutBackgroundChoice;
  sticker?: Partial<CutoutStickerChoice>;
  /** Caller-supplied so ids stay deterministic. */
  idPrefix: string;
  decodeCeilingBytes?: number;
}

/** Decoded bytes one cutout costs the renderer while it is being drawn. */
export function cutoutDecodeBytes(ref: CutoutRef): number {
  return Math.max(0, ref.widthPx) * Math.max(0, ref.heightPx) * 4;
}

export function countCutoutLayers(project: MemeEditProject): number {
  return project.layers.reduce(
    (total, layer) => (layer.kind === 'subject' ? total + 1 : total),
    0
  );
}

function uniqueId(base: string, taken: ReadonlySet<string>): string {
  if (!taken.has(base)) return base;
  let suffix = 2;
  while (taken.has(`${base}-${suffix}`)) suffix += 1;
  return `${base}-${suffix}`;
}

function subjectLayer(
  id: string,
  maskTrackId: string,
  subjectIndex: number | null,
  center: NormalizedPoint,
  sticker: CutoutStickerChoice,
  shadow: boolean
): SubjectLayer {
  return {
    id,
    kind: 'subject',
    subjectIndex,
    maskTrackId,
    // Still images carry no active range and exactly one keyframe at t=0; the
    // project validator enforces both.
    active: null,
    keyframes: [
      {
        timeUs: 0,
        center,
        scale: 1,
        rotationDegrees: 0,
        opacity: 1,
        easing: 'hold',
      },
    ],
    outlineColor: sticker.outline ? sticker.outlineColor : null,
    outlineScale: sticker.outline ? sticker.outlineScale : 0,
    shadowScale: shadow && sticker.shadow ? sticker.shadowScale : 0,
  };
}

/**
 * Turn a chosen subject plus a background and sticker choice into project parts.
 *
 * Returns a refusal instead of throwing, because every refusal here is something
 * the UI has to explain: a full layer list, a cutout too big to decode at export,
 * a replacement background with no image picked. The caller adds the returned
 * layers/mask tracks to the project as one undo step.
 */
export function buildCutoutApplication(
  input: CutoutApplicationInput
): CutoutApplicationOutcome {
  const ref = selectedCutoutRef(input.result, input.selection);
  if (!ref) {
    return {
      ok: false,
      reason: 'no-cutout',
      message: 'That subject is no longer available. Run the cutout again.',
    };
  }
  if (
    cutoutBackgroundRequiresAsset(input.background.mode) &&
    !input.background.assetUri
  ) {
    return {
      ok: false,
      reason: 'background-asset-missing',
      message: 'Choose a replacement image first.',
    };
  }
  const decodeCeiling = Math.max(
    1,
    Math.floor(input.decodeCeilingBytes ?? CUTOUT_DECODE_CEILING_BYTES)
  );
  if (cutoutDecodeBytes(ref) > decodeCeiling) {
    return {
      ok: false,
      reason: 'memory-ceiling',
      message: `That cutout needs ${Math.round(cutoutDecodeBytes(ref) / (1024 * 1024))} MB to draw, over the ${Math.round(decodeCeiling / (1024 * 1024))} MB limit. Crop the image smaller and try again.`,
    };
  }

  const sticker = normalizeCutoutSticker(input.sticker);
  const layerCount = sticker.duplicate ? 2 : 1;
  if (countCutoutLayers(input.project) + layerCount > MAX_CUTOUT_LAYERS) {
    return {
      ok: false,
      reason: 'cutout-layer-limit',
      message: `A meme can hold ${MAX_CUTOUT_LAYERS} cutouts. Delete one first.`,
    };
  }
  if (input.project.layers.length + layerCount > PROJECT_LIMITS.maxLayers) {
    return {
      ok: false,
      reason: 'layer-limit',
      message: `This meme already has ${PROJECT_LIMITS.maxLayers} layers. Delete one first.`,
    };
  }
  if (input.project.maskTracks.length + 1 > PROJECT_LIMITS.maxMaskTracks) {
    return {
      ok: false,
      reason: 'mask-track-limit',
      message: `A meme can hold ${PROJECT_LIMITS.maxMaskTracks} cutout masks. Delete one first.`,
    };
  }

  const takenTrackIds = new Set(input.project.maskTracks.map((track) => track.id));
  const maskTrackId = uniqueId(`${input.idPrefix}-mask`, takenTrackIds);
  const takenLayerIds = new Set(input.project.layers.map((layer) => layer.id));
  // A cutout starts where it was found: its bounds' centre, scale 1.
  const center: NormalizedPoint = clampNormalizedPoint({
    x: ref.bounds.x + ref.bounds.width / 2,
    y: ref.bounds.y + ref.bounds.height / 2,
  });
  const layers: SubjectLayer[] = [];

  // The duplicate is drawn FIRST so it sits behind the subject — that offset
  // copy peeking out from behind the edge is the whole sticker effect. Drawn
  // after, it would cover the subject it is meant to back.
  if (sticker.duplicate) {
    const duplicateId = uniqueId(`${input.idPrefix}-cutout-duplicate`, takenLayerIds);
    takenLayerIds.add(duplicateId);
    layers.push(
      subjectLayer(
        duplicateId,
        maskTrackId,
        ref.subjectIndex,
        clampNormalizedPoint({
          x: center.x + sticker.duplicateOffset.x,
          y: center.y + sticker.duplicateOffset.y,
        }),
        sticker,
        // One shadow per stack: two would read as a printing error.
        false
      )
    );
  }
  const layerId = uniqueId(`${input.idPrefix}-cutout`, takenLayerIds);
  layers.push(subjectLayer(layerId, maskTrackId, ref.subjectIndex, center, sticker, true));

  return {
    ok: true,
    application: {
      layers,
      maskTracks: [
        {
          id: maskTrackId,
          active: null,
          corrections: [{ timeUs: 0, rect: ref.bounds, easing: 'hold' }],
        },
      ],
      // Both layers share one mask track, so the duplicate costs no extra
      // bitmap — the renderer decodes the cutout once per draw either way.
      transientMaskTracks: { [maskTrackId]: ref.cutoutUri },
      background: cutoutBackgroundSpec(input.background),
    },
  };
}

/**
 * Mask track ids no subject layer references any more.
 *
 * Deleting a cutout layer leaves its mask track and its materialized PNG behind;
 * neither is visible, so the leak is silent until the cache fills.
 */
export function orphanedMaskTrackIds(project: MemeEditProject): string[] {
  const referenced = new Set<string>();
  for (const layer of project.layers) {
    if (layer.kind === 'subject') referenced.add(layer.maskTrackId);
    if (layer.kind === 'media' && layer.targetMaskTrackId) {
      referenced.add(layer.targetMaskTrackId);
    }
  }
  return project.maskTracks
    .map((track) => track.id)
    .filter((id) => !referenced.has(id));
}

// --- request lifecycle ------------------------------------------------------

export interface CutoutRequest {
  sourceUri: string;
  /** Filesystem-safe token; also the native cache directory name. */
  requestId: string;
  plan: CutoutSegmentationPlan;
}

export interface CutoutDownloadProgress {
  bytesDownloaded: number;
  totalBytes: number;
  /** 0..1, or null while Play services has not said how big the model is. */
  fraction: number | null;
}

export type CutoutPhase =
  | { kind: 'idle' }
  /** Checking whether the model is on the device; no download yet. */
  | { kind: 'preparing'; request: CutoutRequest; runId: number; cancelRequested: boolean }
  | {
      kind: 'downloading';
      request: CutoutRequest;
      runId: number;
      progress: CutoutDownloadProgress;
      cancelRequested: boolean;
    }
  | { kind: 'segmenting'; request: CutoutRequest; runId: number; cancelRequested: boolean }
  | {
      kind: 'ready';
      request: CutoutRequest;
      result: CutoutResult;
      /** True once the project owns these files; they stop being ours to delete. */
      applied: boolean;
    }
  /** Segmentation succeeded and found nothing. Not an error. */
  | { kind: 'empty'; request: CutoutRequest }
  | { kind: 'failed'; request: CutoutRequest; failure: CutoutFailure }
  | { kind: 'cancelled' };

export interface CutoutState {
  phase: CutoutPhase;
  /**
   * Request ids whose cutout files nobody owns any more. The host drains this
   * and calls releaseSubjectCutouts. Cancellation, superseded runs and dropped
   * results all produce them, and none of it is visible to the user.
   */
  orphans: string[];
  nextRunId: number;
}

export type CutoutEvent =
  | { type: 'start'; request: CutoutRequest }
  | { type: 'downloadProgress'; runId: number; bytesDownloaded: number; totalBytes: number }
  | { type: 'segmentStarted'; runId: number }
  | { type: 'succeeded'; runId: number; result: CutoutResult | null }
  | { type: 'failed'; runId: number; code: string; detail?: string | null }
  | { type: 'cancel' }
  /** The project now holds the cutout: stop treating the files as ours. */
  | { type: 'applied' }
  | { type: 'dismiss' }
  /** A cutout the project used has been deleted; its files can go. */
  | { type: 'releaseRequested'; requestId: string }
  | { type: 'orphansDrained' };

export function initialCutoutState(): CutoutState {
  return { phase: { kind: 'idle' }, orphans: [], nextRunId: 1 };
}

/** The three phases with a run in flight; the only ones an event can target. */
export type CutoutRunPhase = Extract<CutoutPhase, { runId: number }>;

function isCutoutRunning(phase: CutoutPhase): phase is CutoutRunPhase {
  return phase.kind === 'preparing' || phase.kind === 'downloading' || phase.kind === 'segmenting';
}

export function isCutoutBusy(state: CutoutState): boolean {
  return isCutoutRunning(state.phase);
}

export function cutoutCancellable(state: CutoutState): boolean {
  return isCutoutRunning(state.phase) && !state.phase.cancelRequested;
}

export function cutoutCancelRequested(state: CutoutState): boolean {
  return isCutoutRunning(state.phase) && state.phase.cancelRequested;
}

export function activeCutoutRequestId(state: CutoutState): string | null {
  return isCutoutRunning(state.phase) ? state.phase.request.requestId : null;
}

function withOrphan(orphans: readonly string[], requestId: string | null): string[] {
  if (!requestId || orphans.includes(requestId)) return orphans as string[];
  return [...orphans, requestId];
}

/**
 * Request ids the host has not deleted yet, plus the one belonging to a result
 * nobody adopted. Used on unmount, where there is no event left to dispatch.
 */
export function abandonedCutoutRequestIds(state: CutoutState): string[] {
  const phase = state.phase;
  const pending =
    phase.kind === 'ready' && !phase.applied
      ? withOrphan(state.orphans, phase.result.requestId)
      : state.orphans;
  const active = activeCutoutRequestId(state);
  return withOrphan(pending, active);
}

/**
 * The cutout lifecycle as one reducer.
 *
 * Three rules earn their keep here:
 *
 *  * Events carry the run they belong to. A slow first attempt that finishes
 *    after the user started a second one must not overwrite the second's state,
 *    and its files are garbage, not a result.
 *  * Cancel is a REQUEST. Native work is not interruptible mid-inference, so a
 *    run can succeed after the user cancelled — and that result is still
 *    cancelled. Showing it would mean the Cancel button lied.
 *  * A result the project adopted stops being ours. Dropping it must not delete
 *    the PNG a layer is now pointing at.
 */
export function memeCutoutReducer(state: CutoutState, event: CutoutEvent): CutoutState {
  switch (event.type) {
    case 'start': {
      const phase = state.phase;
      // Anything in flight or held unapplied is abandoned by starting over.
      let orphans = withOrphan(state.orphans, activeCutoutRequestId(state));
      if (phase.kind === 'ready' && !phase.applied) {
        orphans = withOrphan(orphans, phase.result.requestId);
      }
      return {
        phase: {
          kind: 'preparing',
          request: event.request,
          runId: state.nextRunId,
          cancelRequested: false,
        },
        orphans,
        nextRunId: state.nextRunId + 1,
      };
    }
    case 'downloadProgress': {
      const phase = state.phase;
      if (!isCutoutRunning(phase) || phase.runId !== event.runId) return state;
      if (phase.kind === 'segmenting') return state;
      const totalBytes = Math.max(0, Math.floor(event.totalBytes));
      const reported = Math.max(0, Math.floor(event.bytesDownloaded));
      // Play services re-reports byte counts that can jitter backwards, and a
      // progress bar that walks back reads as "it is going wrong".
      const previous = phase.kind === 'downloading' ? phase.progress.bytesDownloaded : 0;
      const bytesDownloaded = Math.max(previous, reported);
      return {
        ...state,
        phase: {
          kind: 'downloading',
          request: phase.request,
          runId: phase.runId,
          cancelRequested: phase.cancelRequested,
          progress: {
            bytesDownloaded,
            totalBytes,
            fraction:
              totalBytes > 0 ? Math.min(1, Math.max(0, bytesDownloaded / totalBytes)) : null,
          },
        },
      };
    }
    case 'segmentStarted': {
      const phase = state.phase;
      if (!isCutoutRunning(phase) || phase.runId !== event.runId) return state;
      if (phase.kind === 'segmenting') return state;
      return {
        ...state,
        phase: {
          kind: 'segmenting',
          request: phase.request,
          runId: phase.runId,
          cancelRequested: phase.cancelRequested,
        },
      };
    }
    case 'succeeded': {
      const phase = state.phase;
      if (!isCutoutRunning(phase) || phase.runId !== event.runId) {
        // A superseded run finished anyway; its files belong to nobody.
        return {
          ...state,
          orphans: withOrphan(state.orphans, event.result?.requestId ?? null),
        };
      }
      if (phase.cancelRequested) {
        return {
          ...state,
          phase: { kind: 'cancelled' },
          orphans: withOrphan(state.orphans, phase.request.requestId),
        };
      }
      if (!event.result) {
        // Nothing found: the native directory holds no cutout worth keeping.
        return {
          ...state,
          phase: { kind: 'empty', request: phase.request },
          orphans: withOrphan(state.orphans, phase.request.requestId),
        };
      }
      return {
        ...state,
        phase: { kind: 'ready', request: phase.request, result: event.result, applied: false },
      };
    }
    case 'failed': {
      const phase = state.phase;
      if (!isCutoutRunning(phase) || phase.runId !== event.runId) return state;
      const failure = cutoutFailureFor(event.code, event.detail ?? null);
      const orphans = withOrphan(state.orphans, phase.request.requestId);
      if (!failure) return { ...state, phase: { kind: 'cancelled' }, orphans };
      return { ...state, phase: { kind: 'failed', request: phase.request, failure }, orphans };
    }
    case 'cancel': {
      const phase = state.phase;
      if (!isCutoutRunning(phase) || phase.cancelRequested) return state;
      return { ...state, phase: { ...phase, cancelRequested: true } };
    }
    case 'applied': {
      const phase = state.phase;
      if (phase.kind !== 'ready' || phase.applied) return state;
      return { ...state, phase: { ...phase, applied: true } };
    }
    case 'dismiss': {
      const phase = state.phase;
      if (phase.kind === 'ready' && !phase.applied) {
        return {
          ...state,
          phase: { kind: 'idle' },
          orphans: withOrphan(state.orphans, phase.result.requestId),
        };
      }
      if (isCutoutBusy(state)) return state;
      return { ...state, phase: { kind: 'idle' } };
    }
    case 'releaseRequested':
      return { ...state, orphans: withOrphan(state.orphans, event.requestId) };
    case 'orphansDrained':
      return state.orphans.length === 0 ? state : { ...state, orphans: [] };
    default:
      return state;
  }
}

/**
 * Hand the orphan list to a deleter and clear it.
 *
 * Failures are swallowed on purpose: a cache file that will not delete is not
 * worth interrupting the user for, and retrying forever would keep the list
 * growing. The native sweep catches whatever is left behind.
 */
export async function drainCutoutOrphans(
  state: CutoutState,
  release: (requestId: string) => Promise<unknown>
): Promise<CutoutState> {
  if (state.orphans.length === 0) return state;
  const pending = [...state.orphans];
  for (const requestId of pending) {
    try {
      await release(requestId);
    } catch {
      // Deliberately ignored; see above.
    }
  }
  return memeCutoutReducer(state, { type: 'orphansDrained' });
}

/**
 * One line describing where a request is, for the studio's status row.
 *
 * The download states say what is happening and that it happens once, because
 * "Downloading…" with no end and no reason is the state users kill the app in.
 */
export function cutoutStatusLabel(state: CutoutState): string {
  const phase = state.phase;
  switch (phase.kind) {
    case 'idle':
      return '';
    case 'preparing':
      return phase.cancelRequested ? 'Cancelling…' : 'Checking the cutout model…';
    case 'downloading': {
      if (phase.cancelRequested) return 'Cancelling…';
      const fraction = phase.progress.fraction;
      if (fraction === null) return 'Downloading the cutout model (one time)…';
      return `Downloading the cutout model (one time) — ${Math.round(fraction * 100)}%`;
    }
    case 'segmenting':
      return phase.cancelRequested ? 'Cancelling…' : 'Finding subjects…';
    case 'ready': {
      const count = phase.result.subjects.length;
      if (count <= 1) return 'Found 1 subject';
      return `Found ${count} subjects`;
    }
    case 'empty':
      return 'No subject found in this image';
    case 'failed':
      return phase.failure.message;
    case 'cancelled':
      return 'Cutout cancelled';
  }
}
