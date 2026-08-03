// Pure, React-free math for the video timeline strip: pixel <-> time at a given
// zoom, the bounded thumbnail ladder, trim-handle clamping, split points and
// segment removal, the layer reconciliation a trim forces, and the seek throttle
// that keeps a drag from flooding ExoPlayer.
//
// Everything here is deliberately free of react-native / expo imports so the
// timeline's arithmetic is provable under plain Node. The component is then a
// thin renderer over these functions.
//
// Source <-> output time mapping is NOT reimplemented here: `timelineReadout`
// calls the project core's `sourceTimeToOutputTimeUs` / `outputDurationUs`,
// which already pin the half-open seam and rounding behaviour.
import {
  isLayerActiveAt,
  normalizeRetainedRanges,
  outputDurationUs,
  sourceTimeToOutputTimeUs,
  type MemeEditLayer,
  type MemeEditProject,
  type MemeEditProjectAction,
  type TimeRangeUs,
} from './memeEditProjectCore';

// ---- tuning knobs -----------------------------------------------------------

export const TIMELINE_MIN_ZOOM = 1;
export const TIMELINE_MAX_ZOOM = 16;

// The shortest clip a trim handle or a split may leave behind. Below ~100ms a
// range is a decode artefact rather than something anyone meant to keep.
export const TIMELINE_MIN_RANGE_US = 100_000;

// Thumbnail timestamps come off a DOUBLING ladder rooted here. Every coarser
// rung's timestamps are a strict subset of every finer rung's, so zooming out is
// a pure cache hit and zooming in only ever adds new extractions.
export const TIMELINE_THUMBNAIL_BASE_INTERVAL_US = 250_000;
const TIMELINE_THUMBNAIL_MAX_RUNGS = 24;

// Bars thinner than this vanish on a phone screen; a one-frame layer still has
// to be grabbable.
const MIN_BAR_WIDTH_PX = 2;

// ---- geometry ---------------------------------------------------------------

export interface TimelineScale {
  /** Source duration in microseconds. */
  durationUs: number;
  /** Width of the visible strip in device-independent pixels. */
  viewportWidthPx: number;
  /** Content width is the viewport times this factor. */
  zoom: number;
}

export interface TimelineTick {
  timeUs: number;
  xPx: number;
  widthPx: number;
}

export interface TimelineBar {
  startUs: number;
  endUs: number;
  xPx: number;
  widthPx: number;
}

export interface TimelineThumbnailTickOptions {
  tileWidthPx: number;
  maxTiles: number;
  /** Left edge of the visible window in content pixels. */
  scrollXPx?: number;
  /** Width of the visible window in content pixels. */
  windowWidthPx?: number;
  /** Extra pixels rendered either side of the window. */
  overscanPx?: number;
}

function finiteOr(value: number, fallback: number): number {
  return Number.isFinite(value) ? value : fallback;
}

function clamp(value: number, minimum: number, maximum: number): number {
  if (!Number.isFinite(value)) return minimum;
  if (maximum < minimum) return minimum;
  return Math.min(maximum, Math.max(minimum, value));
}

export function clampTimelineZoom(zoom: number): number {
  return clamp(finiteOr(zoom, TIMELINE_MIN_ZOOM), TIMELINE_MIN_ZOOM, TIMELINE_MAX_ZOOM);
}

function scaleDurationUs(scale: TimelineScale): number {
  return Math.max(0, Math.round(finiteOr(scale.durationUs, 0)));
}

export function timelineContentWidthPx(scale: TimelineScale): number {
  return Math.max(0, finiteOr(scale.viewportWidthPx, 0)) * clampTimelineZoom(scale.zoom);
}

export function timeUsToPixels(timeUs: number, scale: TimelineScale): number {
  const durationUs = scaleDurationUs(scale);
  if (durationUs <= 0) return 0;
  const contentWidthPx = timelineContentWidthPx(scale);
  return (contentWidthPx * clamp(timeUs, 0, durationUs)) / durationUs;
}

export function pixelsToTimeUs(xPx: number, scale: TimelineScale): number {
  const contentWidthPx = timelineContentWidthPx(scale);
  if (contentWidthPx <= 0) return 0;
  const durationUs = scaleDurationUs(scale);
  return Math.round((durationUs * clamp(xPx, 0, contentWidthPx)) / contentWidthPx);
}

// ---- thumbnail ladder -------------------------------------------------------

// Smallest doubling rung that both keeps tiles from overlapping at `tileWidthPx`
// and keeps the whole strip under `maxTiles` extractions.
export function timelineThumbnailIntervalUs(
  scale: TimelineScale,
  tileWidthPx: number,
  maxTiles: number
): number {
  const durationUs = scaleDurationUs(scale);
  if (durationUs <= 0) return TIMELINE_THUMBNAIL_BASE_INTERVAL_US;
  const contentWidthPx = timelineContentWidthPx(scale);
  const tileWidth = Math.max(1, finiteOr(tileWidthPx, 1));
  const tiles = Math.max(1, Math.floor(finiteOr(maxTiles, 1)));
  const pixelBoundUs = contentWidthPx > 0 ? (tileWidth * durationUs) / contentWidthPx : durationUs;
  const countBoundUs = durationUs / tiles;
  const requiredUs = Math.max(pixelBoundUs, countBoundUs);
  let intervalUs = TIMELINE_THUMBNAIL_BASE_INTERVAL_US;
  for (let rung = 0; rung < TIMELINE_THUMBNAIL_MAX_RUNGS && intervalUs < requiredUs; rung += 1) {
    intervalUs *= 2;
  }
  return intervalUs;
}

export function timelineThumbnailTicks(
  scale: TimelineScale,
  options: TimelineThumbnailTickOptions
): TimelineTick[] {
  const tileWidthPx = Math.max(1, finiteOr(options.tileWidthPx, 1));
  const maxTiles = Math.max(1, Math.floor(finiteOr(options.maxTiles, 1)));
  const durationUs = scaleDurationUs(scale);
  if (durationUs <= 0) return [{ timeUs: 0, xPx: 0, widthPx: tileWidthPx }];

  const intervalUs = timelineThumbnailIntervalUs(scale, tileWidthPx, maxTiles);
  const windowWidthPx = finiteOr(options.windowWidthPx ?? 0, 0);
  const windowed = windowWidthPx > 0;
  const overscanPx = Math.max(0, finiteOr(options.overscanPx ?? 0, 0));
  const leftPx = finiteOr(options.scrollXPx ?? 0, 0) - overscanPx;
  const rightPx = leftPx + windowWidthPx + overscanPx * 2;

  const ticks: TimelineTick[] = [];
  for (let timeUs = 0; timeUs < durationUs && ticks.length < maxTiles; timeUs += intervalUs) {
    const xPx = timeUsToPixels(timeUs, scale);
    if (windowed && (xPx + tileWidthPx < leftPx || xPx > rightPx)) continue;
    ticks.push({ timeUs, xPx, widthPx: tileWidthPx });
  }
  return ticks;
}

// ---- trim handles -----------------------------------------------------------

export type TimelineTrimEdge = 'start' | 'end';

export function clampTrimHandleUs(
  ranges: readonly TimeRangeUs[],
  rangeIndex: number,
  edge: TimelineTrimEdge,
  proposedUs: number,
  durationUs: number,
  minimumRangeUs: number = TIMELINE_MIN_RANGE_US
): number {
  const range = ranges[rangeIndex];
  if (range === undefined) return 0;
  const boundedDurationUs = Math.max(0, Math.round(finiteOr(durationUs, 0)));
  const minimumUs = Math.max(0, Math.round(finiteOr(minimumRangeUs, 0)));
  const currentUs = edge === 'start' ? range.startUs : range.endUs;
  const lowerUs =
    edge === 'start'
      ? (ranges[rangeIndex - 1]?.endUs ?? 0)
      : range.startUs + minimumUs;
  const upperUs =
    edge === 'start'
      ? range.endUs - minimumUs
      : (ranges[rangeIndex + 1]?.startUs ?? boundedDurationUs);
  const lowerBoundUs = clamp(lowerUs, 0, boundedDurationUs);
  const upperBoundUs = clamp(upperUs, lowerBoundUs, boundedDurationUs);
  if (!Number.isFinite(proposedUs)) return clamp(currentUs, lowerBoundUs, upperBoundUs);
  return Math.round(clamp(proposedUs, lowerBoundUs, upperBoundUs));
}

export function applyTrimHandle(
  ranges: readonly TimeRangeUs[],
  rangeIndex: number,
  edge: TimelineTrimEdge,
  proposedUs: number,
  durationUs: number,
  minimumRangeUs: number = TIMELINE_MIN_RANGE_US
): TimeRangeUs[] {
  const range = ranges[rangeIndex];
  if (range === undefined) return ranges.map((entry) => ({ ...entry }));
  const nextUs = clampTrimHandleUs(ranges, rangeIndex, edge, proposedUs, durationUs, minimumRangeUs);
  const next = ranges.map((entry) => ({ ...entry }));
  if (edge === 'start') next[rangeIndex].startUs = nextUs;
  else next[rangeIndex].endUs = nextUs;
  return normalizeRetainedRanges(next, Math.max(0, Math.round(finiteOr(durationUs, 0))));
}

// ---- split points and segments ----------------------------------------------

export interface TimelineSegment {
  startUs: number;
  endUs: number;
  rangeIndex: number;
}

function rangeIndexContaining(ranges: readonly TimeRangeUs[], timeUs: number): number {
  for (let index = 0; index < ranges.length; index += 1) {
    const range = ranges[index];
    if (timeUs > range.startUs && timeUs < range.endUs) return index;
  }
  return -1;
}

// Drops split points that no longer sit strictly inside a retained range, then
// sorts and dedupes. Every trim runs its points back through this so a removed
// gap cannot leave a phantom cut behind.
export function splitPointsForRetainedRanges(
  splitPoints: readonly number[],
  ranges: readonly TimeRangeUs[]
): number[] {
  const kept = new Set<number>();
  for (const point of splitPoints) {
    if (!Number.isFinite(point)) continue;
    const rounded = Math.round(point);
    if (rangeIndexContaining(ranges, rounded) >= 0) kept.add(rounded);
  }
  return [...kept].sort((left, right) => left - right);
}

export function insertSplitPointUs(
  splitPoints: readonly number[],
  atUs: number,
  ranges: readonly TimeRangeUs[],
  minimumSegmentUs: number = TIMELINE_MIN_RANGE_US
): number[] | null {
  if (!Number.isFinite(atUs)) return null;
  const rounded = Math.round(atUs);
  const rangeIndex = rangeIndexContaining(ranges, rounded);
  if (rangeIndex < 0) return null;
  const existing = splitPointsForRetainedRanges(splitPoints, ranges);
  if (existing.includes(rounded)) return null;
  const range = ranges[rangeIndex];
  const minimumUs = Math.max(0, Math.round(finiteOr(minimumSegmentUs, 0)));
  let lowerUs = range.startUs;
  let upperUs = range.endUs;
  for (const point of existing) {
    if (point <= range.startUs || point >= range.endUs) continue;
    if (point < rounded && point > lowerUs) lowerUs = point;
    if (point > rounded && point < upperUs) upperUs = point;
  }
  if (rounded - lowerUs < minimumUs || upperUs - rounded < minimumUs) return null;
  return [...existing, rounded].sort((left, right) => left - right);
}

export function timelineSegments(
  ranges: readonly TimeRangeUs[],
  splitPoints: readonly number[]
): TimelineSegment[] {
  const points = splitPointsForRetainedRanges(splitPoints, ranges);
  const segments: TimelineSegment[] = [];
  for (let rangeIndex = 0; rangeIndex < ranges.length; rangeIndex += 1) {
    const range = ranges[rangeIndex];
    let cursorUs = range.startUs;
    for (const point of points) {
      if (point <= cursorUs || point >= range.endUs) continue;
      segments.push({ startUs: cursorUs, endUs: point, rangeIndex });
      cursorUs = point;
    }
    if (cursorUs < range.endUs) segments.push({ startUs: cursorUs, endUs: range.endUs, rangeIndex });
  }
  return segments;
}

export interface TimelineSegmentRemoval {
  retainedRanges: TimeRangeUs[];
  splitPoints: number[];
}

// Cuts one segment out of the retained ranges. Refused when it would leave the
// project with no video at all — the reducer would silently ignore that anyway,
// and an editor that can delete its own source is a trap.
export function removeTimelineSegment(
  ranges: readonly TimeRangeUs[],
  splitPoints: readonly number[],
  segmentIndex: number,
  durationUs: number
): TimelineSegmentRemoval | null {
  const segments = timelineSegments(ranges, splitPoints);
  if (segments.length <= 1) return null;
  const target = segments[segmentIndex];
  if (target === undefined) return null;
  const remaining: TimeRangeUs[] = [];
  for (const range of ranges) {
    if (target.endUs <= range.startUs || target.startUs >= range.endUs) {
      remaining.push({ ...range });
      continue;
    }
    if (range.startUs < target.startUs) remaining.push({ startUs: range.startUs, endUs: target.startUs });
    if (target.endUs < range.endUs) remaining.push({ startUs: target.endUs, endUs: range.endUs });
  }
  const retainedRanges = normalizeRetainedRanges(remaining, Math.max(0, Math.round(finiteOr(durationUs, 0))));
  if (retainedRanges.length === 0) return null;
  return { retainedRanges, splitPoints: splitPointsForRetainedRanges(splitPoints, retainedRanges) };
}

// ---- layer reconciliation ---------------------------------------------------

export interface TimelineTrimReconciliation {
  retainedRanges: TimeRangeUs[];
  clampedLayers: { id: string; active: TimeRangeUs }[];
  removedLayerIds: string[];
  /** Apply through `commitGestureTransaction` for exactly one undo entry. */
  actions: MemeEditProjectAction[];
}

function rangesEqual(left: readonly TimeRangeUs[], right: readonly TimeRangeUs[]): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index].startUs !== right[index].startUs || left[index].endUs !== right[index].endUs) return false;
  }
  return true;
}

// The hull of a layer's surviving overlaps. A timed layer can only hold ONE
// contiguous active range, so a layer straddling a removed gap keeps the span
// from its first surviving overlap to its last — never wider than it already
// was, and never resurrected once every overlap is gone.
function survivingActiveRange(active: TimeRangeUs, ranges: readonly TimeRangeUs[]): TimeRangeUs | null {
  let startUs: number | null = null;
  let endUs = 0;
  for (const range of ranges) {
    const overlapStartUs = Math.max(active.startUs, range.startUs);
    const overlapEndUs = Math.min(active.endUs, range.endUs);
    if (overlapStartUs >= overlapEndUs) continue;
    if (startUs === null) startUs = overlapStartUs;
    endUs = overlapEndUs;
  }
  return startUs === null ? null : { startUs, endUs };
}

export function reconcileLayersForRetainedRanges(
  project: MemeEditProject,
  retainedRanges: readonly TimeRangeUs[]
): TimelineTrimReconciliation {
  const durationUs = project.source.durationUs;
  if (project.video === null || durationUs === null) {
    return {
      retainedRanges: project.video?.retainedRanges.map((range) => ({ ...range })) ?? [],
      clampedLayers: [],
      removedLayerIds: [],
      actions: [],
    };
  }
  const normalized = normalizeRetainedRanges(retainedRanges, durationUs);
  if (normalized.length === 0) {
    return {
      retainedRanges: project.video.retainedRanges.map((range) => ({ ...range })),
      clampedLayers: [],
      removedLayerIds: [],
      actions: [],
    };
  }

  const clampedLayers: { id: string; active: TimeRangeUs }[] = [];
  const removedLayerIds: string[] = [];
  for (const layer of project.layers) {
    if (layer.active === null) continue;
    const surviving = survivingActiveRange(layer.active, normalized);
    if (surviving === null) {
      removedLayerIds.push(layer.id);
    } else if (surviving.startUs !== layer.active.startUs || surviving.endUs !== layer.active.endUs) {
      clampedLayers.push({ id: layer.id, active: surviving });
    }
  }

  const actions: MemeEditProjectAction[] = [];
  if (!rangesEqual(normalized, project.video.retainedRanges)) {
    actions.push({ type: 'set-video-retained-ranges', retainedRanges: normalized });
  }
  if (actions.length > 0) {
    for (const clamped of clampedLayers) {
      actions.push({ type: 'set-layer-active-range', id: clamped.id, active: clamped.active });
    }
    for (const id of removedLayerIds) actions.push({ type: 'remove-layer', id });
  }
  return { retainedRanges: normalized, clampedLayers, removedLayerIds, actions };
}

// ---- seek throttling --------------------------------------------------------

export interface SeekThrottleState {
  lastSeekAtMs: number;
  lastSeekUs: number | null;
  pendingUs: number | null;
}

export interface SeekThrottleStep {
  state: SeekThrottleState;
  /** Non-null when the caller must actually seek the player right now. */
  seekUs: number | null;
}

export function createSeekThrottle(): SeekThrottleState {
  return { lastSeekAtMs: Number.NEGATIVE_INFINITY, lastSeekUs: null, pendingUs: null };
}

export function nextSeekThrottleState(
  state: SeekThrottleState,
  requestedUs: number,
  nowMs: number,
  intervalMs: number
): SeekThrottleStep {
  if (!Number.isFinite(requestedUs) || !Number.isFinite(nowMs)) return { state, seekUs: null };
  const wantedUs = Math.max(0, Math.round(requestedUs));
  if (wantedUs === state.lastSeekUs && state.pendingUs === null) return { state, seekUs: null };
  if (nowMs - state.lastSeekAtMs < Math.max(0, finiteOr(intervalMs, 0))) {
    return { state: { ...state, pendingUs: wantedUs }, seekUs: null };
  }
  return {
    state: { lastSeekAtMs: nowMs, lastSeekUs: wantedUs, pendingUs: null },
    seekUs: wantedUs,
  };
}

// Emits whatever the throttle swallowed. Call on gesture end so the player lands
// on the frame the finger actually left, not the last one that made it through.
export function flushSeekThrottle(state: SeekThrottleState, nowMs: number): SeekThrottleStep {
  const pendingUs = state.pendingUs;
  if (pendingUs === null) return { state, seekUs: null };
  if (pendingUs === state.lastSeekUs) return { state: { ...state, pendingUs: null }, seekUs: null };
  return {
    state: { lastSeekAtMs: finiteOr(nowMs, state.lastSeekAtMs), lastSeekUs: pendingUs, pendingUs: null },
    seekUs: pendingUs,
  };
}

// The playhead follows the finger at full gesture rate; the throttle only gates
// what reaches the decoder.
export function resolvePlayheadUs(dragUs: number | null, playbackUs: number): number {
  if (dragUs !== null && Number.isFinite(dragUs)) return Math.max(0, Math.round(dragUs));
  return Number.isFinite(playbackUs) ? Math.max(0, Math.round(playbackUs)) : 0;
}

export function seekTargetForLayerSelection(
  layer: MemeEditLayer | null,
  playheadUs: number
): number | null {
  if (layer === null || layer.active === null) return null;
  if (isLayerActiveAt(layer, playheadUs)) return null;
  return layer.active.startUs;
}

// ---- bars -------------------------------------------------------------------

function barFor(startUs: number, endUs: number, scale: TimelineScale): TimelineBar {
  const xPx = timeUsToPixels(startUs, scale);
  const widthPx = Math.max(MIN_BAR_WIDTH_PX, timeUsToPixels(endUs, scale) - xPx);
  return { startUs, endUs, xPx, widthPx };
}

function sortedRanges(ranges: readonly TimeRangeUs[]): TimeRangeUs[] {
  return ranges.slice().sort((left, right) => left.startUs - right.startUs || left.endUs - right.endUs);
}

export function timelineRangeBars(ranges: readonly TimeRangeUs[], scale: TimelineScale): TimelineBar[] {
  return sortedRanges(ranges).map((range) => barFor(range.startUs, range.endUs, scale));
}

// The complement of the retained ranges — everything the export will drop,
// including head and tail. Rendered distinctly so a removed stretch reads as
// removed rather than as dead space.
export function timelineGapBars(ranges: readonly TimeRangeUs[], scale: TimelineScale): TimelineBar[] {
  const durationUs = scaleDurationUs(scale);
  const gaps: TimelineBar[] = [];
  let cursorUs = 0;
  for (const range of sortedRanges(ranges)) {
    if (range.startUs > cursorUs) gaps.push(barFor(cursorUs, range.startUs, scale));
    cursorUs = Math.max(cursorUs, range.endUs);
  }
  if (cursorUs < durationUs) gaps.push(barFor(cursorUs, durationUs, scale));
  return gaps;
}

export function timelineLayerBar(layer: MemeEditLayer, scale: TimelineScale): TimelineBar {
  const durationUs = scaleDurationUs(scale);
  if (layer.active === null) return barFor(0, durationUs, scale);
  return barFor(layer.active.startUs, layer.active.endUs, scale);
}

// ---- readout ----------------------------------------------------------------

export function formatTimelineTimeUs(timeUs: number): string {
  const safeUs = Number.isFinite(timeUs) ? Math.max(0, timeUs) : 0;
  const totalCentis = Math.floor(safeUs / 10_000);
  const minutes = Math.floor(totalCentis / 6_000);
  const withinMinute = totalCentis % 6_000;
  const seconds = `${Math.floor(withinMinute / 100)}`.padStart(2, '0');
  const centis = `${withinMinute % 100}`.padStart(2, '0');
  return `${minutes}:${seconds}.${centis}`;
}

// Nearest retained source time by distance — what a scrub that lands in a
// removed gap should snap to.
export function nearestRetainedSourceTimeUs(
  ranges: readonly TimeRangeUs[],
  sourceTimeUs: number
): number | null {
  const sorted = sortedRanges(ranges);
  if (sorted.length === 0) return null;
  const timeUs = Number.isFinite(sourceTimeUs) ? Math.round(sourceTimeUs) : 0;
  let bestUs = sorted[0].startUs;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const range of sorted) {
    if (timeUs >= range.startUs && timeUs <= range.endUs) return timeUs;
    for (const boundaryUs of [range.startUs, range.endUs]) {
      const distance = Math.abs(boundaryUs - timeUs);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestUs = boundaryUs;
      }
    }
  }
  return bestUs;
}

// A non-final range's `endUs` and the next range's `startUs` are the SAME output
// instant, and `sourceTimeToOutputTimeUs` only accepts the latter (its ranges are
// half-open except at the very end). Swap to the mappable representative rather
// than reimplementing the mapping.
function seamRepresentativeSourceTimeUs(
  ranges: readonly TimeRangeUs[],
  sourceTimeUs: number
): number {
  for (let index = 0; index < ranges.length - 1; index += 1) {
    if (ranges[index].endUs === sourceTimeUs) return ranges[index + 1].startUs;
  }
  return sourceTimeUs;
}

export interface TimelineReadout {
  currentUs: number;
  durationUs: number;
  currentLabel: string;
  durationLabel: string;
}

// Both numbers are OUTPUT time — what the exported clip will actually read —
// so trimming and speed are visible in the labels without a render.
export function timelineReadout(project: MemeEditProject, playheadSourceUs: number): TimelineReadout {
  const video = project.video;
  if (video === null) {
    return { currentUs: 0, durationUs: 0, currentLabel: formatTimelineTimeUs(0), durationLabel: formatTimelineTimeUs(0) };
  }
  const ranges = sortedRanges(video.retainedRanges);
  const durationUs = outputDurationUs(ranges, video.speed);
  const snappedUs = nearestRetainedSourceTimeUs(ranges, playheadSourceUs);
  const currentUs =
    snappedUs === null
      ? 0
      : clamp(
          sourceTimeToOutputTimeUs(seamRepresentativeSourceTimeUs(ranges, snappedUs), ranges, video.speed) ?? 0,
          0,
          durationUs
        );
  return {
    currentUs,
    durationUs,
    currentLabel: formatTimelineTimeUs(currentUs),
    durationLabel: formatTimelineTimeUs(durationUs),
  };
}

// ---- thumbnail cache --------------------------------------------------------

// Bounded LRU over extracted frame URIs, keyed by source AND timestamp so two
// clips never share a poster. A `null` value is a REMEMBERED failure: the frame
// is undecodable at that timestamp and must not be re-requested every render.
export class TimelineThumbnailCache {
  private readonly entries = new Map<string, string | null>();
  private readonly maxEntries: number;

  constructor(maxEntries: number) {
    this.maxEntries = Number.isFinite(maxEntries) ? Math.max(0, Math.floor(maxEntries)) : 0;
  }

  static keyFor(sourceUri: string, timeUs: number): string {
    return `${sourceUri}@${Math.max(0, Math.round(Number.isFinite(timeUs) ? timeUs : 0))}`;
  }

  get size(): number {
    return this.entries.size;
  }

  has(sourceUri: string, timeUs: number): boolean {
    return this.entries.has(TimelineThumbnailCache.keyFor(sourceUri, timeUs));
  }

  get(sourceUri: string, timeUs: number): string | null | undefined {
    const key = TimelineThumbnailCache.keyFor(sourceUri, timeUs);
    if (!this.entries.has(key)) return undefined;
    const value = this.entries.get(key) ?? null;
    this.entries.delete(key);
    this.entries.set(key, value);
    return value;
  }

  set(sourceUri: string, timeUs: number, uri: string | null): void {
    if (this.maxEntries <= 0) return;
    const key = TimelineThumbnailCache.keyFor(sourceUri, timeUs);
    this.entries.delete(key);
    this.entries.set(key, uri);
    while (this.entries.size > this.maxEntries) {
      const oldest = this.entries.keys().next();
      if (oldest.done) break;
      this.entries.delete(oldest.value);
    }
  }

  clear(): void {
    this.entries.clear();
  }
}

// Timestamps still needing an extraction, capped so a scroll never launches a
// hundred decodes at once.
export function pendingThumbnailRequests(
  ticks: readonly TimelineTick[],
  cache: TimelineThumbnailCache,
  sourceUri: string,
  maxConcurrent: number
): number[] {
  const limit = Math.max(0, Math.floor(finiteOr(maxConcurrent, 0)));
  const pending: number[] = [];
  for (const tick of ticks) {
    if (pending.length >= limit) break;
    if (!cache.has(sourceUri, tick.timeUs)) pending.push(tick.timeUs);
  }
  return pending;
}
