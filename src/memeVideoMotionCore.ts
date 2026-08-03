// Timed-layer motion: the start/end handles of a layer's active range and the
// keyframe commands behind the motion tool.
//
// This module owns NO interpolation of its own. The transform a layer shows at
// a timestamp is `interpolateTransformKeyframes` in the project core, and the
// visibility answer is `isLayerActiveAt` — the same two functions the preview
// canvas already draws with. `evaluateLayerMotionAt` is only their composition,
// and it exists so preview, the motion tool, and the native overlay evaluator
// all name the same contract instead of each re-deriving it.
//
// Every command returns ACTIONS rather than a project. The caller commits them
// through `commitGestureTransaction`, which is what makes one gesture one undo
// entry; a refused command returns an empty list, so a rejected press cannot
// leave a do-nothing entry in history.
//
// Nothing here touches an active range directly either: a handle drag becomes a
// `set-layer-active-range` action, whose reducer case already re-normalizes the
// layer's keyframes into the new bounds. That is the existing path for clamping
// what a range change invalidated, and duplicating it here would be a second
// answer to the same question.

import {
  PROJECT_LIMITS,
  interpolateTransformKeyframes,
  isLayerActiveAt,
  type InterpolatedTransform,
  type KeyframedLayer,
  type MemeEditProject,
  type MemeEditProjectAction,
  type TimeRangeUs,
  type TransformKeyframe,
} from './memeEditProjectCore';
import { TIMELINE_MIN_RANGE_US } from './memeTimelineCore';

/** The minimum a layer's own transform track needs to be evaluable. */
export type MotionTrack = Pick<KeyframedLayer, 'active' | 'keyframes'>;

export type MotionHandle = 'start' | 'end';

export type MotionRefusal =
  | 'accepted'
  | 'no-layer'
  | 'outside-active-range'
  | 'already-keyframed'
  | 'no-keyframe-here'
  | 'last-keyframe'
  | 'not-later'
  | 'keyframe-limit'
  | 'unchanged';

export interface MotionCommand {
  /** Empty whenever `refusal` is anything but `accepted`. */
  actions: MemeEditProjectAction[];
  refusal: MotionRefusal;
}

const REFUSED: Record<Exclude<MotionRefusal, 'accepted'>, string> = {
  'no-layer': 'Select a layer that can move first.',
  'outside-active-range': 'Move the playhead inside the layer’s active range first.',
  'already-keyframed': 'This time already has a keyframe.',
  'no-keyframe-here': 'There is no keyframe at the playhead.',
  'last-keyframe': 'A moving layer keeps at least one keyframe.',
  'not-later': 'Copy forward needs a later time than the playhead.',
  'keyframe-limit': `This layer already has the ${PROJECT_LIMITS.maxKeyframesPerLayer}-keyframe maximum.`,
  unchanged: 'That is already the current value.',
};

export function motionRefusalMessage(refusal: MotionRefusal): string {
  return refusal === 'accepted' ? '' : REFUSED[refusal];
}

function refuse(refusal: Exclude<MotionRefusal, 'accepted'>): MotionCommand {
  return { actions: [], refusal };
}

/**
 * The keyframed layer this id names, or null when the id is unknown or names a
 * cover layer — covers carry rect corrections rather than a transform track and
 * belong to the replace-text tool, not to this one.
 */
export function motionLayerFor(project: MemeEditProject, layerId: string | null): KeyframedLayer | null {
  if (layerId === null) return null;
  const layer = project.layers.find((candidate) => candidate.id === layerId);
  if (layer === undefined || layer.kind === 'cover') return null;
  return layer;
}

/**
 * A null active range means "the whole clip"; the handles need concrete numbers
 * to drag, and the reducer stores concrete numbers for every video layer.
 */
export function motionActiveRange(track: MotionTrack, durationUs: number): TimeRangeUs {
  if (track.active !== null) return { startUs: track.active.startUs, endUs: track.active.endUs };
  return { startUs: 0, endUs: Math.max(0, Math.round(Number.isFinite(durationUs) ? durationUs : 0)) };
}

export function evaluateLayerMotionAt(track: MotionTrack, timeUs: number): InterpolatedTransform | null {
  if (!isLayerActiveAt(track, timeUs)) return null;
  return interpolateTransformKeyframes(track.keyframes, timeUs);
}

/**
 * Where a dragged handle is allowed to land. The floor on the remaining span is
 * the timeline's own `TIMELINE_MIN_RANGE_US`: layer bars are dragged on the same
 * strip as the trim handles, and a bar thinner than a trim can leave behind is
 * not grabbable again afterwards.
 */
export function clampMotionHandleUs(
  track: MotionTrack,
  handle: MotionHandle,
  proposedUs: number,
  durationUs: number
): number {
  const range = motionActiveRange(track, durationUs);
  const boundedDurationUs = Math.max(0, Math.round(Number.isFinite(durationUs) ? durationUs : 0));
  const rounded = Math.round(Number.isFinite(proposedUs) ? proposedUs : 0);
  if (handle === 'start') {
    return Math.max(0, Math.min(rounded, range.endUs - TIMELINE_MIN_RANGE_US));
  }
  return Math.min(boundedDurationUs, Math.max(rounded, range.startUs + TIMELINE_MIN_RANGE_US));
}

/**
 * One handle drag as actions. `set-layer-active-range` is the whole change: its
 * reducer case re-normalizes the layer's keyframes into the new bounds, so a
 * keyframe the drag stranded outside the range is clamped there rather than
 * lingering as unreachable state.
 */
export function motionHandleActions(
  project: MemeEditProject,
  layerId: string | null,
  handle: MotionHandle,
  proposedUs: number
): MemeEditProjectAction[] {
  const layer = motionLayerFor(project, layerId);
  if (layer === null) return [];
  const durationUs = project.source.durationUs ?? 0;
  const range = motionActiveRange(layer, durationUs);
  const landedUs = clampMotionHandleUs(layer, handle, proposedUs, durationUs);
  const active = handle === 'start'
    ? { startUs: landedUs, endUs: range.endUs }
    : { startUs: range.startUs, endUs: landedUs };
  if (active.startUs >= active.endUs) return [];
  if (active.startUs === range.startUs && active.endUs === range.endUs) return [];
  return [{ type: 'set-layer-active-range', id: layer.id, active }];
}

/** Index of the keyframe sitting exactly on `timeUs`, or -1. */
export function keyframeIndexAt(keyframes: readonly TransformKeyframe[], timeUs: number): number {
  return keyframes.findIndex((frame) => frame.timeUs === timeUs);
}

// Strictly later / strictly earlier, so holding the button down keeps walking
// the track instead of sticking on the keyframe under the playhead.
export function nextKeyframeUs(keyframes: readonly TransformKeyframe[], fromUs: number): number | null {
  for (const frame of keyframes) {
    if (frame.timeUs > fromUs) return frame.timeUs;
  }
  return null;
}

export function previousKeyframeUs(keyframes: readonly TransformKeyframe[], fromUs: number): number | null {
  for (let index = keyframes.length - 1; index >= 0; index -= 1) {
    if (keyframes[index].timeUs < fromUs) return keyframes[index].timeUs;
  }
  return null;
}

/**
 * The easing governing the segment `timeUs` falls in. An inserted keyframe takes
 * it so the insertion is invisible: dropping a `linear` keyframe into a held
 * stretch would start the stretch animating, which is not what "set a keyframe
 * here" promises.
 */
function segmentEasingAt(
  keyframes: readonly TransformKeyframe[],
  timeUs: number
): TransformKeyframe['easing'] {
  let easing: TransformKeyframe['easing'] = 'linear';
  for (const frame of keyframes) {
    if (frame.timeUs > timeUs) break;
    easing = frame.easing;
  }
  return easing;
}

function keyframeWith(
  timeUs: number,
  transform: InterpolatedTransform,
  easing: TransformKeyframe['easing']
): TransformKeyframe {
  return {
    timeUs,
    center: { x: transform.center.x, y: transform.center.y },
    scale: transform.scale,
    rotationDegrees: transform.rotationDegrees,
    opacity: transform.opacity,
    easing,
  };
}

function withKeyframes(layerId: string, keyframes: TransformKeyframe[]): MotionCommand {
  return {
    actions: [{ type: 'set-layer-keyframes', id: layerId, keyframes }],
    refusal: 'accepted',
  };
}

function insertedInOrder(
  keyframes: readonly TransformKeyframe[],
  inserted: TransformKeyframe
): TransformKeyframe[] {
  const next = keyframes.filter((frame) => frame.timeUs !== inserted.timeUs);
  const index = next.findIndex((frame) => frame.timeUs > inserted.timeUs);
  next.splice(index < 0 ? next.length : index, 0, inserted);
  return next;
}

/**
 * Pins the value already on screen at `atUs`. Nothing about the motion changes
 * — the point is to give the next drag somewhere to write to.
 */
export function setKeyframeHere(
  project: MemeEditProject,
  layerId: string | null,
  atUs: number
): MotionCommand {
  const layer = motionLayerFor(project, layerId);
  if (layer === null) return refuse('no-layer');
  const timeUs = Math.round(Number.isFinite(atUs) ? atUs : -1);
  const transform = evaluateLayerMotionAt(layer, timeUs);
  // Outside the active range the reducer would clamp this onto a boundary and
  // silently overwrite the keyframe already there.
  if (transform === null) return refuse('outside-active-range');
  if (keyframeIndexAt(layer.keyframes, timeUs) >= 0) return refuse('already-keyframed');
  if (layer.keyframes.length >= PROJECT_LIMITS.maxKeyframesPerLayer) return refuse('keyframe-limit');
  return withKeyframes(
    layer.id,
    insertedInOrder(layer.keyframes, keyframeWith(timeUs, transform, segmentEasingAt(layer.keyframes, timeUs)))
  );
}

export function deleteKeyframeAt(
  project: MemeEditProject,
  layerId: string | null,
  atUs: number
): MotionCommand {
  const layer = motionLayerFor(project, layerId);
  if (layer === null) return refuse('no-layer');
  const index = keyframeIndexAt(layer.keyframes, Math.round(Number.isFinite(atUs) ? atUs : -1));
  if (index < 0) return refuse('no-keyframe-here');
  // The reducer ignores an empty keyframe list, which would turn this press into
  // an undo entry that changed nothing.
  if (layer.keyframes.length <= 1) return refuse('last-keyframe');
  return withKeyframes(layer.id, layer.keyframes.filter((_frame, at) => at !== index));
}

/**
 * Copies the value at `fromUs` onto `toUs`, keyframing both ends. Equal values
 * across the span is what makes the layer hold still there, so this works the
 * same whether the segment interpolates or is already held.
 */
export function copyKeyframeForward(
  project: MemeEditProject,
  layerId: string | null,
  fromUs: number,
  toUs: number
): MotionCommand {
  const layer = motionLayerFor(project, layerId);
  if (layer === null) return refuse('no-layer');
  const sourceUs = Math.round(Number.isFinite(fromUs) ? fromUs : -1);
  const targetUs = Math.round(Number.isFinite(toUs) ? toUs : -1);
  const transform = evaluateLayerMotionAt(layer, sourceUs);
  if (transform === null) return refuse('outside-active-range');
  if (targetUs <= sourceUs) return refuse('not-later');
  if (!isLayerActiveAt(layer, targetUs)) return refuse('outside-active-range');

  const additions = (keyframeIndexAt(layer.keyframes, sourceUs) < 0 ? 1 : 0)
    + (keyframeIndexAt(layer.keyframes, targetUs) < 0 ? 1 : 0);
  if (layer.keyframes.length + additions > PROJECT_LIMITS.maxKeyframesPerLayer) {
    return refuse('keyframe-limit');
  }
  const anchored = insertedInOrder(
    layer.keyframes,
    keyframeWith(sourceUs, transform, segmentEasingAt(layer.keyframes, sourceUs))
  );
  return withKeyframes(
    layer.id,
    insertedInOrder(anchored, keyframeWith(targetUs, transform, segmentEasingAt(anchored, targetUs)))
  );
}

export function setKeyframeEasing(
  project: MemeEditProject,
  layerId: string | null,
  atUs: number,
  easing: TransformKeyframe['easing']
): MotionCommand {
  const layer = motionLayerFor(project, layerId);
  if (layer === null) return refuse('no-layer');
  const index = keyframeIndexAt(layer.keyframes, Math.round(Number.isFinite(atUs) ? atUs : -1));
  if (index < 0) return refuse('no-keyframe-here');
  if (layer.keyframes[index].easing === easing) return refuse('unchanged');
  return withKeyframes(
    layer.id,
    layer.keyframes.map((frame, at) => (at === index ? { ...frame, easing } : frame))
  );
}

// ---- native parity fixtures -------------------------------------------------

export const MOTION_PARITY_FIXTURE_VERSION = 1;

/**
 * Both sides evaluate the same doubles in the same order, so agreement should be
 * exact; the tolerance exists only to absorb JSON round-tripping. One normalized
 * unit is a whole canvas edge, so this is orders of magnitude below a pixel.
 */
export const MOTION_PARITY_TOLERANCE = 1e-9;

export interface MotionParityKeyframe {
  timeUs: number;
  centerX: number;
  centerY: number;
  scale: number;
  rotationDegrees: number;
  opacity: number;
  easing: TransformKeyframe['easing'];
}

export interface MotionParitySample {
  timeUs: number;
  /** False where the layer is outside its active range; the numbers are then 0. */
  visible: boolean;
  centerX: number;
  centerY: number;
  scale: number;
  rotationDegrees: number;
  opacity: number;
}

export interface MotionParityCase {
  id: string;
  activeStartUs: number;
  activeEndUs: number;
  keyframes: MotionParityKeyframe[];
  samples: MotionParitySample[];
}

export interface MotionParityFixtures {
  version: number;
  toleranceUnits: number;
  cases: MotionParityCase[];
}

interface MotionParitySpec {
  id: string;
  activeStartUs: number;
  activeEndUs: number;
  keyframes: MotionParityKeyframe[];
  sampleTimesUs: number[];
}

const SECOND_US = 1_000_000;

function parityKeyframe(
  timeUs: number,
  centerX: number,
  centerY: number,
  scale: number,
  rotationDegrees: number,
  opacity: number,
  easing: TransformKeyframe['easing'] = 'linear'
): MotionParityKeyframe {
  return { timeUs, centerX, centerY, scale, rotationDegrees, opacity, easing };
}

// Chosen to cover what a re-implementation gets wrong: the clamped ends, both
// easings, a hold releasing exactly on its next keyframe, a single-keyframe
// track, a range whose keyframes do not span it, and progress fractions that do
// not land on a binary boundary.
const PARITY_SPECS: readonly MotionParitySpec[] = [
  {
    id: 'linear-full-range',
    activeStartUs: 0,
    activeEndUs: 4 * SECOND_US,
    keyframes: [
      parityKeyframe(0, 0.2, 0.25, 1, 0, 0.25),
      parityKeyframe(4 * SECOND_US, 0.6, 0.75, 3, 90, 1),
    ],
    sampleTimesUs: [0, 1 * SECOND_US, 1_333_333, 2 * SECOND_US, 3_999_999, 4 * SECOND_US, 4 * SECOND_US + 1],
  },
  {
    id: 'hold-then-release',
    activeStartUs: 500_000,
    activeEndUs: 4_500_000,
    keyframes: [
      parityKeyframe(500_000, 0.15, 0.9, 0.75, -30, 1, 'hold'),
      parityKeyframe(2_500_000, 0.85, 0.1, 2.5, 240, 0.5),
      parityKeyframe(4_500_000, 0.5, 0.5, 1, 0, 0),
    ],
    sampleTimesUs: [
      499_999,
      500_000,
      1_500_000,
      2_499_999,
      2_500_000,
      3_100_000,
      4_500_000,
      4_500_001,
    ],
  },
  {
    id: 'keyframes-narrower-than-range',
    activeStartUs: 0,
    activeEndUs: 6 * SECOND_US,
    keyframes: [
      parityKeyframe(2 * SECOND_US, 0.1, 0.2, 0.5, 12.5, 0.75),
      parityKeyframe(3 * SECOND_US, 0.9, 0.8, 4, 355, 0.125),
    ],
    sampleTimesUs: [0, 1 * SECOND_US, 2 * SECOND_US, 2_500_000, 3 * SECOND_US, 5 * SECOND_US, 6 * SECOND_US, 6_000_001],
  },
  {
    id: 'single-keyframe-static',
    activeStartUs: 1 * SECOND_US,
    activeEndUs: 2 * SECOND_US,
    keyframes: [parityKeyframe(1_500_000, 0.42, 0.58, 1.375, 44.25, 0.6)],
    sampleTimesUs: [999_999, 1 * SECOND_US, 1_250_000, 1_500_000, 1_750_000, 2 * SECOND_US, 2_000_001],
  },
  {
    id: 'repeating-progress',
    activeStartUs: 1 * SECOND_US,
    activeEndUs: 4_000_001,
    keyframes: [
      parityKeyframe(1 * SECOND_US, 0.1, 0.7, 0.9, 17, 0.9),
      parityKeyframe(4_000_001, 0.8, 0.15, 2.2, -123.5, 0.05),
    ],
    sampleTimesUs: [1 * SECOND_US, 1_000_001, 2 * SECOND_US, 3_141_593, 4_000_000, 4_000_001, 4_000_002],
  },
];

/**
 * The fixture both sides consume. Every sampled number comes out of
 * `evaluateLayerMotionAt`, so this file cannot describe an expectation the
 * TypeScript evaluator does not actually produce.
 */
export function buildMotionParityFixtures(): MotionParityFixtures {
  return {
    version: MOTION_PARITY_FIXTURE_VERSION,
    toleranceUnits: MOTION_PARITY_TOLERANCE,
    cases: PARITY_SPECS.map((spec) => {
      const track: MotionTrack = {
        active: { startUs: spec.activeStartUs, endUs: spec.activeEndUs },
        keyframes: spec.keyframes.map((frame) => ({
          timeUs: frame.timeUs,
          center: { x: frame.centerX, y: frame.centerY },
          scale: frame.scale,
          rotationDegrees: frame.rotationDegrees,
          opacity: frame.opacity,
          easing: frame.easing,
        })),
      };
      return {
        id: spec.id,
        activeStartUs: spec.activeStartUs,
        activeEndUs: spec.activeEndUs,
        keyframes: spec.keyframes.map((frame) => ({ ...frame })),
        samples: spec.sampleTimesUs.map((timeUs) => {
          const transform = evaluateLayerMotionAt(track, timeUs);
          if (transform === null) {
            return {
              timeUs,
              visible: false,
              centerX: 0,
              centerY: 0,
              scale: 0,
              rotationDegrees: 0,
              opacity: 0,
            };
          }
          return {
            timeUs,
            visible: true,
            centerX: transform.center.x,
            centerY: transform.center.y,
            scale: transform.scale,
            rotationDegrees: transform.rotationDegrees,
            opacity: transform.opacity,
          };
        }),
      };
    }),
  };
}
