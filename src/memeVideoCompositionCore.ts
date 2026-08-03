// Pure, serializable composition plan for the multi-range video exporter.
//
// This is the video twin of `memeImageRenderCore`: it turns a validated
// `MemeEditProject` into the exact, ordered segment list the media3 builder
// (`RetainedRangeComposition.kt`) will concatenate, and it refuses — loudly and
// with a readable reason — anything the builder cannot honour. It re-derives no
// timing: retained-range normalization and the source<->output mapping come
// from `memeEditProjectCore`, and the output pixel size comes from
// `visibleImageDimensions`, the same rotation+crop math the canvas and the
// still exporter already use.
//
// The plan is JSON only: no functions, no clock reads, no randomness.
import {
  PROJECT_LIMITS,
  normalizeRetainedRanges,
  outputDurationUs,
  outputTimeToSourceTimeUs,
  type InsertedCard,
  type MemeEditProject,
  type NormalizedRect,
  type QuarterRotation,
  type TimeRangeUs,
} from './memeEditProjectCore';
import { normalizeFreeCrop, visibleImageDimensions } from './memeImageEditCore';
// Type-only: the plan stays runnable under plain Node, but there is exactly one
// definition of the shape the native asset guard consumes.
import type { CompositionAssetRequirement } from '../modules/memeget-bg';

export const VIDEO_COMPOSITION_PLAN_VERSION = 1;

// media3 renders a still title card as a fixed-rate bitmap stream; the rate has
// to be stated because `ImageAssetLoader` refuses an item without one. 30 fps
// matches the synthetic fixtures and keeps a one-second card at 30 frames.
export const TITLE_CARD_FRAME_RATE = 30;

// A title card is a beat, not a second clip. Below 0.2 s it is a flicker; above
// 10 s it is its own video and belongs in the timeline as a source instead.
export const MIN_TITLE_CARD_DURATION_US = 200_000;
export const MAX_TITLE_CARD_DURATION_US = 10_000_000;

// `MediaItem.Builder#setImageDurationMs` — the only way to make media3 treat a
// URI as a still image — takes MILLIseconds, so a card duration that is not a
// whole number of milliseconds cannot survive the trip. Round here, once, and
// publish the rounded value rather than letting the native side silently floor
// a number the UI showed the user.
export const TITLE_CARD_DURATION_GRANULARITY_US = 1_000;

// Same window the project validator enforces on `video.speed`; restated so a
// plan built from a hand-written project cannot outrun the exporter.
export const MIN_COMPOSITION_SPEED = 0.5;
export const MAX_COMPOSITION_SPEED = 2;

// Every retained range can be split at most once per inserted card, so the
// worst case is `ranges + cards` source pieces plus one item per card.
export const MAX_COMPOSITION_SEGMENTS =
  PROJECT_LIMITS.maxRetainedRanges + 2 * PROJECT_LIMITS.maxInsertedCards;

// The still-image types media3's `DefaultAssetLoaderFactory` routes to
// `ImageAssetLoader` AND Android's `BitmapFactory` decodes without a plugin.
// Deliberately excludes the types media3 *names* but would quietly reduce:
// `gif` (a single frame of an animation), `svg` (no platform decoder), and the
// raw/tiff family (device-dependent). A card the exporter cannot draw is a
// rejection, never a substitution.
const TITLE_CARD_MIME_TYPES: Readonly<Record<string, string>> = Object.freeze({
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  jpe: 'image/jpeg',
  jfif: 'image/jpeg',
  webp: 'image/webp',
  bmp: 'image/bmp',
  dib: 'image/bmp',
  heif: 'image/heif',
  heic: 'image/heic',
});

export interface VideoCompositionPlanSource {
  uri: string;
  widthPx: number;
  heightPx: number;
  durationUs: number;
  rotation: QuarterRotation;
  flipX: boolean;
  flipY: boolean;
  crop: NormalizedRect;
}

export interface VideoCompositionPlanOutput {
  // Frame size every segment — source piece and title card alike — is presented
  // into, so a card cannot resize the composition and a seam cannot un-rotate.
  widthPx: number;
  heightPx: number;
  speed: number;
  // Total output length after the speed change.
  durationUs: number;
  // Pre-speed timeline lengths, split by where they came from.
  retainedDurationUs: number;
  cardDurationUs: number;
}

export interface VideoCompositionSourceSegment {
  kind: 'source';
  index: number;
  sourceStartUs: number;
  sourceEndUs: number;
  timelineDurationUs: number;
  outputStartUs: number;
  outputEndUs: number;
}

export interface VideoCompositionCardSegment {
  kind: 'card';
  index: number;
  uri: string;
  mimeType: string;
  timelineDurationUs: number;
  frameRate: number;
  outputStartUs: number;
  outputEndUs: number;
}

export type VideoCompositionSegment =
  | VideoCompositionSourceSegment
  | VideoCompositionCardSegment;

export type VideoCompositionRejectionCode =
  // `video.speed` is outside the window the composition can express.
  | 'unsupported-speed'
  // Every retained range normalized away; there is nothing left to export.
  | 'no-retained-ranges'
  // The card asset is not a still image media3 decodes.
  | 'card-unsupported-asset'
  // The card would flicker or become a clip of its own.
  | 'card-duration-out-of-range'
  // The card sits off the retained (card-free) output timeline.
  | 'card-position-out-of-range'
  // Splitting for cards pushed the item count past the bounded ceiling.
  | 'segment-limit-exceeded';

export interface VideoCompositionRejection {
  code: VideoCompositionRejectionCode;
  path: string;
  message: string;
}

export interface VideoCompositionPlan {
  version: typeof VIDEO_COMPOSITION_PLAN_VERSION;
  id: string;
  source: VideoCompositionPlanSource;
  output: VideoCompositionPlanOutput;
  audio: { muted: boolean; volume: number };
  // Composition order. Empty whenever `rejections` is non-empty: a partial
  // segment list would invite a partial export.
  segments: VideoCompositionSegment[];
  rejections: VideoCompositionRejection[];
}

export interface VideoCompositionPlanOptions {
  // Caller-supplied so the plan stays deterministic (no clock, no randomness).
  planId: string;
  maxSegments?: number;
}

// The MIME type media3 must be told explicitly — it only sniffs `content://`
// URIs through the ContentResolver, and falls back to the file extension
// otherwise. Returns null for anything the exporter would have to fake.
export function titleCardMimeType(uri: string): string | null {
  const withoutFragment = uri.split('#', 1)[0];
  const withoutQuery = withoutFragment.split('?', 1)[0];
  const lastSegment = withoutQuery.slice(withoutQuery.lastIndexOf('/') + 1);
  const dot = lastSegment.lastIndexOf('.');
  if (dot < 0 || dot === lastSegment.length - 1) return null;
  return TITLE_CARD_MIME_TYPES[lastSegment.slice(dot + 1).toLowerCase()] ?? null;
}

// Whether the plan can be handed to the native composition builder as-is.
export function videoCompositionPlanIsBuildable(plan: VideoCompositionPlan): boolean {
  return plan.rejections.length === 0 && plan.segments.length > 0;
}

// Every asset the native guard has to header-decode before this plan is worth
// exporting. `videoCompositionPlanIsBuildable` says the plan is internally
// consistent; this says which files still have to be real.
export function videoCompositionPlanAssetRequirements(
  plan: VideoCompositionPlan
): CompositionAssetRequirement[] {
  const requirements: CompositionAssetRequirement[] = [];
  for (const segment of plan.segments) {
    if (segment.kind !== 'card') continue;
    requirements.push({ uri: segment.uri, role: 'TITLE_CARD', mimeType: segment.mimeType });
  }
  return requirements;
}

function seconds(valueUs: number): string {
  return `${valueUs / 1_000_000}s`;
}

// A card's insertion point resolved onto the retained range list: either the
// gap before range `boundaryIndex`, or a cut inside range `rangeIndex`.
interface CardPlacement {
  card: InsertedCard;
  order: number;
  mimeType: string;
  timelineDurationUs: number;
  boundaryIndex: number | null;
  rangeIndex: number;
  cutSourceUs: number;
}

export function buildVideoCompositionPlan(
  project: MemeEditProject,
  options: VideoCompositionPlanOptions
): VideoCompositionPlan {
  if (project.source.kind !== 'video' || project.video === null) {
    throw new TypeError('buildVideoCompositionPlan requires a video source project.');
  }
  const visible = visibleImageDimensions(
    { width: project.source.width, height: project.source.height },
    project.base
  );
  const speed = project.video.speed;
  const sourceDurationUs = Math.max(0, Math.round(project.source.durationUs ?? 0));
  const base: Omit<VideoCompositionPlan, 'output' | 'segments' | 'rejections'> = {
    version: VIDEO_COMPOSITION_PLAN_VERSION,
    id: options.planId,
    source: {
      uri: project.transient.materializedSourceUri ?? project.source.uri,
      widthPx: project.source.width,
      heightPx: project.source.height,
      durationUs: sourceDurationUs,
      rotation: project.base.rotation,
      flipX: project.base.flipX,
      flipY: project.base.flipY,
      crop: normalizeFreeCrop(project.base.crop),
    },
    audio: { muted: project.video.audio.muted, volume: project.video.audio.volume },
  };
  const output: VideoCompositionPlanOutput = {
    widthPx: Math.max(1, Math.round(visible.width)),
    heightPx: Math.max(1, Math.round(visible.height)),
    speed,
    durationUs: 0,
    retainedDurationUs: 0,
    cardDurationUs: 0,
  };
  const refuse = (rejections: VideoCompositionRejection[]): VideoCompositionPlan => ({
    ...base,
    output,
    segments: [],
    rejections,
  });

  if (
    !Number.isFinite(speed) ||
    speed < MIN_COMPOSITION_SPEED ||
    speed > MAX_COMPOSITION_SPEED
  ) {
    return refuse([
      {
        code: 'unsupported-speed',
        path: 'video.speed',
        message: `video.speed must be between ${MIN_COMPOSITION_SPEED} and ${MAX_COMPOSITION_SPEED}, was ${speed}.`,
      },
    ]);
  }

  const ranges = normalizeRetainedRanges(project.video.retainedRanges, sourceDurationUs);
  if (ranges.length === 0) {
    return refuse([
      {
        code: 'no-retained-ranges',
        path: 'video.retainedRanges',
        message: 'video.retainedRanges keeps nothing; there is no composition to build.',
      },
    ]);
  }

  const retainedTimelineUs = ranges.reduce(
    (total, range) => total + range.endUs - range.startUs,
    0
  );
  // Card positions are stated on the retained, card-free output timeline —
  // exactly what the project validator bounds `atUs` against — so inserting one
  // card never moves where the next one lands.
  const cardFreeOutputUs = outputDurationUs(ranges, speed);

  const rejections: VideoCompositionRejection[] = [];
  const placements: CardPlacement[] = [];
  project.video.insertedCards.forEach((card, order) => {
    const path = `video.insertedCards[${order}]`;
    const mimeType = titleCardMimeType(card.uri);
    if (mimeType === null) {
      rejections.push({
        code: 'card-unsupported-asset',
        path,
        message: `${path} is not a still image media3 can decode: ${card.uri}. Re-encode it as PNG, JPEG, WebP, BMP or HEIF.`,
      });
      return;
    }
    if (
      !Number.isFinite(card.durationUs) ||
      card.durationUs < MIN_TITLE_CARD_DURATION_US ||
      card.durationUs > MAX_TITLE_CARD_DURATION_US
    ) {
      rejections.push({
        code: 'card-duration-out-of-range',
        path: `${path}.durationUs`,
        message: `${path}.durationUs must be between ${seconds(MIN_TITLE_CARD_DURATION_US)} and ${seconds(MAX_TITLE_CARD_DURATION_US)}, was ${card.durationUs}us.`,
      });
      return;
    }
    if (!Number.isFinite(card.atUs) || card.atUs < 0 || card.atUs > cardFreeOutputUs) {
      rejections.push({
        code: 'card-position-out-of-range',
        path: `${path}.atUs`,
        message: `${path}.atUs must land on the retained output timeline (0..${cardFreeOutputUs}us), was ${card.atUs}us.`,
      });
      return;
    }
    placements.push({
      card,
      order,
      mimeType,
      timelineDurationUs:
        Math.round(card.durationUs / TITLE_CARD_DURATION_GRANULARITY_US) *
        TITLE_CARD_DURATION_GRANULARITY_US,
      ...resolveCardPosition(card.atUs, ranges, speed, cardFreeOutputUs),
    });
  });
  if (rejections.length > 0) return refuse(rejections);

  placements.sort((left, right) => left.card.atUs - right.card.atUs || left.order - right.order);
  const boundaryCards: CardPlacement[][] = Array.from(
    { length: ranges.length + 1 },
    () => []
  );
  const rangeCuts: CardPlacement[][] = ranges.map(() => []);
  for (const placement of placements) {
    if (placement.boundaryIndex === null) {
      rangeCuts[placement.rangeIndex].push(placement);
    } else {
      boundaryCards[placement.boundaryIndex].push(placement);
    }
  }

  const segments: VideoCompositionSegment[] = [];
  let timelineUs = 0;
  const pushSource = (startUs: number, endUs: number): void => {
    const outputStartUs = Math.round(timelineUs / speed);
    timelineUs += endUs - startUs;
    segments.push({
      kind: 'source',
      index: segments.length,
      sourceStartUs: startUs,
      sourceEndUs: endUs,
      timelineDurationUs: endUs - startUs,
      outputStartUs,
      outputEndUs: Math.round(timelineUs / speed),
    });
  };
  const pushCard = (placement: CardPlacement): void => {
    const outputStartUs = Math.round(timelineUs / speed);
    timelineUs += placement.timelineDurationUs;
    segments.push({
      kind: 'card',
      index: segments.length,
      uri: placement.card.uri,
      mimeType: placement.mimeType,
      timelineDurationUs: placement.timelineDurationUs,
      frameRate: TITLE_CARD_FRAME_RATE,
      outputStartUs,
      outputEndUs: Math.round(timelineUs / speed),
    });
  };

  boundaryCards[0].forEach(pushCard);
  ranges.forEach((range, index) => {
    let cursorUs = range.startUs;
    for (const cut of rangeCuts[index]) {
      if (cut.cutSourceUs > cursorUs) {
        pushSource(cursorUs, cut.cutSourceUs);
        cursorUs = cut.cutSourceUs;
      }
      pushCard(cut);
    }
    if (cursorUs < range.endUs) pushSource(cursorUs, range.endUs);
    boundaryCards[index + 1].forEach(pushCard);
  });

  const maxSegments = Math.max(1, Math.floor(options.maxSegments ?? MAX_COMPOSITION_SEGMENTS));
  if (segments.length > maxSegments) {
    return refuse([
      {
        code: 'segment-limit-exceeded',
        path: 'video',
        message: `The split and card operations need ${segments.length} composition items, past the ${maxSegments}-item ceiling. Remove a card or a cut.`,
      },
    ]);
  }

  output.retainedDurationUs = retainedTimelineUs;
  output.cardDurationUs = timelineUs - retainedTimelineUs;
  output.durationUs = Math.round(timelineUs / speed);
  return { ...base, output, segments, rejections: [] };
}

// Where a card lands on the retained range list. A card whose output time falls
// on a range edge sits in the gap next to it; anything else cuts the range it
// lands in. `normalizeRetainedRanges` has already merged touching ranges, so a
// source time belongs to exactly one range and the edge case is unambiguous.
function resolveCardPosition(
  atUs: number,
  ranges: readonly TimeRangeUs[],
  speed: number,
  cardFreeOutputUs: number
): Pick<CardPlacement, 'boundaryIndex' | 'rangeIndex' | 'cutSourceUs'> {
  if (atUs <= 0) return { boundaryIndex: 0, rangeIndex: 0, cutSourceUs: ranges[0].startUs };
  if (atUs >= cardFreeOutputUs) {
    const last = ranges[ranges.length - 1];
    return { boundaryIndex: ranges.length, rangeIndex: ranges.length - 1, cutSourceUs: last.endUs };
  }
  const sourceUs = outputTimeToSourceTimeUs(atUs, ranges, speed);
  if (sourceUs === null) {
    const last = ranges[ranges.length - 1];
    return { boundaryIndex: ranges.length, rangeIndex: ranges.length - 1, cutSourceUs: last.endUs };
  }
  for (let index = 0; index < ranges.length; index += 1) {
    const range = ranges[index];
    if (sourceUs < range.startUs || sourceUs > range.endUs) continue;
    if (sourceUs === range.startUs) return { boundaryIndex: index, rangeIndex: index, cutSourceUs: sourceUs };
    if (sourceUs === range.endUs) {
      return { boundaryIndex: index + 1, rangeIndex: index, cutSourceUs: sourceUs };
    }
    return { boundaryIndex: null, rangeIndex: index, cutSourceUs: sourceUs };
  }
  const last = ranges[ranges.length - 1];
  return { boundaryIndex: ranges.length, rangeIndex: ranges.length - 1, cutSourceUs: last.endUs };
}
