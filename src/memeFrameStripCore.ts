// Frame-accurate strip math for the video studio.
//
// The timeline strip (memeTimelineCore) answers "show me a coarse filmstrip of
// this clip". This module answers a different question: "let me reach an EXACT
// source frame". The two differ in one decisive way — timeline ticks land on a
// doubling ladder of round intervals, while frame ticks must land on real
// decodable frame boundaries, because a frame edit that references a timestamp
// between two frames cannot be rendered back.
//
// Everything here is pure. Scale/pixel conversion is delegated to
// memeTimelineCore so the strip and the timeline can never disagree about where
// a timestamp sits on screen.
import {
  TimelineThumbnailCache,
  pixelsToTimeUs,
  timeUsToPixels,
  timelineContentWidthPx,
  type TimelineScale,
} from './memeTimelineCore';
/** Default width of one frame cell, in dip. */
export const FRAME_CELL_WIDTH_PX = 44;
export const FRAME_EXACT_ZOOM = 8;

/** Assumed frame rate when the container does not report one. */
export const FALLBACK_FRAME_RATE = 30;

/** Frames decoded per pump. Small so a fling never queues dozens of decodes. */
export const FRAME_DECODE_BATCH = 3;

/** Hard ceiling on cached frames. Bounds memory regardless of clip length. */
export const MAX_FRAME_CACHE_ENTRIES = 120;

export interface FrameTick {
  /** Exact source timestamp this cell decodes. */
  timeUs: number;
  /** Left edge in content pixels. */
  xPx: number;
  /** True when the tick is a real frame boundary rather than a coarse sample. */
  exact: boolean;
}

function finiteOr(value: number, fallback: number): number {
  return Number.isFinite(value) ? value : fallback;
}

/** Microseconds between frames. Falls back to 30fps when the rate is unknown. */
export function sourceFrameIntervalUs(frameRate: number | null | undefined): number {
  const rate = finiteOr(frameRate ?? 0, 0);
  if (rate <= 0) return Math.round(1_000_000 / FALLBACK_FRAME_RATE);
  return Math.round(1_000_000 / rate);
}

/**
 * Snap an arbitrary timestamp onto the nearest real frame boundary. This is the
 * contract that makes a frame edit renderable: the exporter decodes frames, not
 * interpolated instants, so a stored edit timestamp must be one a decoder can
 * actually land on.
 */
export function snapToFrameUs(
  timeUs: number,
  frameIntervalUs: number,
  durationUs: number
): number {
  const interval = Math.max(1, Math.round(finiteOr(frameIntervalUs, 1)));
  const duration = Math.max(0, finiteOr(durationUs, 0));
  const clamped = Math.min(Math.max(0, finiteOr(timeUs, 0)), duration);
  const snapped = Math.round(clamped / interval) * interval;
  return Math.min(snapped, Math.floor(duration / interval) * interval);
}

/** Index of the frame a timestamp belongs to. */
export function frameIndexAt(timeUs: number, frameIntervalUs: number): number {
  const interval = Math.max(1, Math.round(finiteOr(frameIntervalUs, 1)));
  return Math.round(Math.max(0, finiteOr(timeUs, 0)) / interval);
}
/**
 * Tick interval for the current zoom.
 *
 * Two bounds apply and the LARGER wins:
 *   - the real frame interval (never sample finer than the source has frames)
 *   - a pixel bound, so cells never overlap at `tileWidthPx`
 *
 * The pixel bound is what keeps a ten-minute clip from materializing thousands
 * of cells: at low zoom each pixel spans a lot of time, so the ladder widens.
 * Without it the strip is O(duration) and defeats the whole point.
 */
export function frameStripIntervalUs(
  scale: TimelineScale,
  frameIntervalUs: number,
  tileWidthPx = FRAME_CELL_WIDTH_PX
): number {
  const frame = Math.max(1, Math.round(finiteOr(frameIntervalUs, 1)));
  const durationUs = Math.max(0, finiteOr(scale.durationUs, 0));
  const contentWidthPx = timelineContentWidthPx(scale);
  if (durationUs <= 0 || contentWidthPx <= 0) return frame;
  const tile = Math.max(1, finiteOr(tileWidthPx, 1));
  const pixelBoundUs = (tile * durationUs) / contentWidthPx;
  let interval = frame;
  while (interval < pixelBoundUs) interval *= 2;
  return interval;
}

export interface FrameStripWindow {
  /** Horizontal scroll offset in content pixels. */
  scrollXPx: number;
  /** Visible width in pixels. */
  windowWidthPx: number;
  /** Extra pixels rendered beyond each edge. */
  overscanPx: number;
}

/**
 * Visible frame ticks for the current scroll window. Virtualized on purpose:
 * only the window (plus overscan) is materialized, so a ten-minute clip costs
 * the same as a ten-second one.
 */
export function frameStripTicks(
  scale: TimelineScale,
  frameIntervalUs: number,
  window: FrameStripWindow
): FrameTick[] {
  const durationUs = Math.max(0, finiteOr(scale.durationUs, 0));
  const contentWidthPx = timelineContentWidthPx(scale);
  if (durationUs <= 0 || contentWidthPx <= 0) return [];

  const interval = frameStripIntervalUs(scale, frameIntervalUs);
  const exact = interval === Math.max(1, Math.round(finiteOr(frameIntervalUs, 1)));
  const overscan = Math.max(0, finiteOr(window.overscanPx, 0));
  const leftPx = Math.max(0, finiteOr(window.scrollXPx, 0) - overscan);
  const rightPx = Math.min(
    contentWidthPx,
    finiteOr(window.scrollXPx, 0) + Math.max(0, finiteOr(window.windowWidthPx, 0)) + overscan
  );
  if (rightPx <= leftPx) return [];

  const startUs = Math.floor(pixelsToTimeUs(leftPx, scale) / interval) * interval;
  const endUs = Math.min(durationUs, pixelsToTimeUs(rightPx, scale));

  const ticks: FrameTick[] = [];
  for (let timeUs = Math.max(0, startUs); timeUs <= endUs; timeUs += interval) {
    ticks.push({ timeUs, xPx: timeUsToPixels(timeUs, scale), exact });
  }
  return ticks;
}

/**
 * Timestamps in `ticks` that are not cached yet, capped at `limit`. Cached
 * misses (a timestamp that decoded to null) are NOT retried — an undecodable
 * frame must not be re-attempted on every scroll.
 *
 * `inFlight` holds `TimelineThumbnailCache.keyFor` keys for decodes that have
 * started but not yet written a result. Without it a re-pump that lands inside
 * that window requests the same frame again, and on a real device a duplicate
 * decode costs a whole extra cell's worth of time.
 */
export function pendingFrameRequests(
  ticks: readonly FrameTick[],
  cache: TimelineThumbnailCache,
  sourceUri: string,
  limit: number,
  inFlight?: ReadonlySet<string>
): number[] {
  const max = Math.max(0, Math.floor(finiteOr(limit, 0)));
  const out: number[] = [];
  for (const tick of ticks) {
    if (out.length >= max) break;
    if (cache.has(sourceUri, tick.timeUs)) continue;
    if (inFlight?.has(TimelineThumbnailCache.keyFor(sourceUri, tick.timeUs))) continue;
    out.push(tick.timeUs);
  }
  return out;
}

/** A bounded LRU keyed by source+timestamp, reusing the timeline's cache. */
export function createFrameCache(maxEntries = MAX_FRAME_CACHE_ENTRIES): TimelineThumbnailCache {
  return new TimelineThumbnailCache(maxEntries);
}


// ---- frame mode -------------------------------------------------------------
//
// Time-based zoom cannot reach individual frames on a long clip: at the
// timeline's 16x ceiling a ten-minute clip still spans ~2.3s per 44px cell, so
// "zoom in far enough" is not a real answer. Frame mode drops the time->pixel
// mapping entirely and lays cells out PER FRAME at a fixed width, so any frame
// is reachable by scrolling regardless of clip length, and the number of
// materialized cells depends only on the viewport.

export interface FrameWindow {
  /** Frame ordinal at the left edge of the visible window. */
  startIndex: number;
  /** Cells to materialize, including overscan. */
  count: number;
  /** Total frames in the clip. */
  totalFrames: number;
  /** Content width of the whole frame track, in pixels. */
  contentWidthPx: number;
}

/** Frames in the clip. The last partial frame is not decodable, so it is excluded. */
export function totalFrameCount(durationUs: number, frameIntervalUs: number): number {
  const interval = Math.max(1, Math.round(finiteOr(frameIntervalUs, 1)));
  return Math.max(0, Math.floor(Math.max(0, finiteOr(durationUs, 0)) / interval));
}

/**
 * Which frame cells to render for a scroll offset. Purely a function of the
 * viewport, so cost is constant in clip length — the property that makes
 * "reach every frame" affordable.
 */
export function frameWindowAt(
  scrollXPx: number,
  windowWidthPx: number,
  durationUs: number,
  frameIntervalUs: number,
  cellWidthPx = FRAME_CELL_WIDTH_PX,
  overscanCells = 2
): FrameWindow {
  const cell = Math.max(1, finiteOr(cellWidthPx, 1));
  const totalFrames = totalFrameCount(durationUs, frameIntervalUs);
  const overscan = Math.max(0, Math.floor(finiteOr(overscanCells, 0)));
  const contentWidthPx = totalFrames * cell;
  if (totalFrames === 0) return { startIndex: 0, count: 0, totalFrames: 0, contentWidthPx: 0 };

  const left = Math.max(0, finiteOr(scrollXPx, 0));
  const width = Math.max(0, finiteOr(windowWidthPx, 0));
  const firstVisible = Math.floor(left / cell);
  const visibleCells = Math.ceil(width / cell) + 1;
  const startIndex = Math.max(0, firstVisible - overscan);
  const endIndex = Math.min(totalFrames - 1, firstVisible + visibleCells + overscan);
  return {
    startIndex,
    count: Math.max(0, endIndex - startIndex + 1),
    totalFrames,
    contentWidthPx,
  };
}

/** Exact ticks for a frame-mode window. Every tick is a real frame boundary. */
export function frameWindowTicks(
  window: FrameWindow,
  frameIntervalUs: number,
  cellWidthPx = FRAME_CELL_WIDTH_PX
): FrameTick[] {
  const interval = Math.max(1, Math.round(finiteOr(frameIntervalUs, 1)));
  const cell = Math.max(1, finiteOr(cellWidthPx, 1));
  const ticks: FrameTick[] = [];
  for (let i = 0; i < window.count; i += 1) {
    const index = window.startIndex + i;
    ticks.push({ timeUs: index * interval, xPx: index * cell, exact: true });
  }
  return ticks;
}


// ---- cell derivation --------------------------------------------------------

/**
 * How many source frames one strip cell spans. 1 means the strip really is
 * frame-exact; anything larger is a coarse sample and the UI must say so
 * instead of implying every frame is reachable at this zoom.
 */
export function frameStripStride(
  scale: TimelineScale,
  frameIntervalUs: number,
  tileWidthPx = FRAME_CELL_WIDTH_PX
): number {
  const frame = Math.max(1, Math.round(finiteOr(frameIntervalUs, 1)));
  return Math.round(frameStripIntervalUs(scale, frameIntervalUs, tileWidthPx) / frame);
}

/**
 * Pixel span of one strip interval — the width a cell needs so the strip reads
 * as a continuous filmstrip rather than a dotted line. The ladder already
 * guarantees an interval at least `tileWidthPx` wide, so this only ever widens
 * a cell beyond the minimum.
 */
export function frameStripTickWidthPx(scale: TimelineScale, intervalUs: number): number {
  const durationUs = Math.max(0, finiteOr(scale.durationUs, 0));
  const contentWidthPx = timelineContentWidthPx(scale);
  if (durationUs <= 0 || contentWidthPx <= 0) return 1;
  return Math.max(1, (contentWidthPx * Math.max(1, finiteOr(intervalUs, 1))) / durationUs);
}

/**
 * What a cell can honestly show.
 *   ready        — decoded, has a poster
 *   pending      — not decoded yet; the pump will get to it
 *   undecodable  — decode was attempted and failed; it will NOT be retried
 * The third state exists because a permanently blank cell that looks identical
 * to a pending one is a lie about work still being in flight.
 */
export type FrameCellState = 'ready' | 'pending' | 'undecodable';

export interface FrameCell {
  /** Source frame ordinal. Meaningful in both layouts, unlike a cell position. */
  index: number;
  /** Exact source timestamp this cell decodes and seeks to. */
  timeUs: number;
  xPx: number;
  widthPx: number;
  uri: string | null;
  state: FrameCellState;
  current: boolean;
}

export interface FrameCellOptions {
  cache: TimelineThumbnailCache;
  sourceUri: string;
  /** Source frame interval, used for the frame ordinal. */
  frameIntervalUs: number;
  /** Time one cell covers. Equal to `frameIntervalUs` in frame mode. */
  cellSpanUs: number;
  cellWidthPx: number;
  /** Playhead in source time; the cell containing it is marked current. */
  currentTimeUs: number;
  /** When given, the trailing cell is clipped so it cannot overhang the track. */
  contentWidthPx?: number;
}

/**
 * Everything a cell needs to render, derived in one pass. Reading the cache
 * here also promotes the visible window in the LRU, so the frames on screen are
 * the last ones evicted.
 */
export function frameStripCells(
  ticks: readonly FrameTick[],
  options: FrameCellOptions
): FrameCell[] {
  const frame = Math.max(1, Math.round(finiteOr(options.frameIntervalUs, 1)));
  const span = Math.max(1, Math.round(finiteOr(options.cellSpanUs, frame)));
  const width = Math.max(1, finiteOr(options.cellWidthPx, 1));
  const contentWidthPx = Math.max(0, finiteOr(options.contentWidthPx ?? 0, 0));
  // Compare against the cell the playhead SNAPS to rather than testing a range:
  // every tick is a multiple of the span in both layouts, so this marks exactly
  // one cell and never two or none.
  const currentCellUs = frameIndexAt(options.currentTimeUs, span) * span;
  return ticks.map((tick) => {
    const cached = options.cache.get(options.sourceUri, tick.timeUs);
    return {
      index: Math.round(tick.timeUs / frame),
      timeUs: tick.timeUs,
      xPx: tick.xPx,
      widthPx: contentWidthPx > 0 ? Math.max(0, Math.min(width, contentWidthPx - tick.xPx)) : width,
      uri: cached ?? null,
      state: cached === undefined ? 'pending' : cached === null ? 'undecodable' : 'ready',
      current: tick.timeUs === currentCellUs,
    };
  });
}

// ---- readout and stepping ---------------------------------------------------

/**
 * Millisecond-precise clock. The timeline's centisecond readout collapses
 * adjacent frames of a high-rate clip onto the same string, which is useless
 * precisely where frame accuracy is the point.
 */
export function formatFrameTimeUs(timeUs: number): string {
  const safeUs = Number.isFinite(timeUs) ? Math.max(0, timeUs) : 0;
  const totalMs = Math.floor(safeUs / 1_000);
  const minutes = Math.floor(totalMs / 60_000);
  const withinMinute = totalMs % 60_000;
  const seconds = `${Math.floor(withinMinute / 1_000)}`.padStart(2, '0');
  const millis = `${withinMinute % 1_000}`.padStart(3, '0');
  return `${minutes}:${seconds}.${millis}`;
}

export interface FrameStripReadout {
  /** Ordinal of the frame under the playhead, clamped to one the strip renders. */
  index: number;
  totalFrames: number;
  /** Exact source timestamp of that frame — what a seek or an edit stores. */
  exactUs: number;
  timeLabel: string;
  durationLabel: string;
}

/**
 * `snapToFrameUs` caps at `floor(duration / interval) * interval`, which is one
 * frame PAST the last cell `frameWindowAt` will lay out. Naming that frame in
 * the readout would point at a cell the user can never scroll to, so the
 * ordinal is clamped to the strip's own last frame instead.
 */
export function frameStripReadout(
  timeUs: number,
  frameIntervalUs: number,
  durationUs: number
): FrameStripReadout {
  const interval = Math.max(1, Math.round(finiteOr(frameIntervalUs, 1)));
  const totalFrames = totalFrameCount(durationUs, interval);
  const index = totalFrames === 0 ? 0 : Math.min(frameIndexAt(timeUs, interval), totalFrames - 1);
  const exactUs = totalFrames === 0 ? 0 : index * interval;
  return {
    index,
    totalFrames,
    exactUs,
    timeLabel: formatFrameTimeUs(exactUs),
    durationLabel: formatFrameTimeUs(Math.max(0, finiteOr(durationUs, 0))),
  };
}

/** Move `deltaFrames` frames from wherever the playhead is, staying on the strip. */
export function stepFrameUs(
  timeUs: number,
  frameIntervalUs: number,
  durationUs: number,
  deltaFrames: number
): number {
  const interval = Math.max(1, Math.round(finiteOr(frameIntervalUs, 1)));
  const totalFrames = totalFrameCount(durationUs, interval);
  if (totalFrames === 0) return 0;
  const index = frameIndexAt(timeUs, interval) + Math.trunc(finiteOr(deltaFrames, 0));
  return Math.min(Math.max(0, index), totalFrames - 1) * interval;
}

/**
 * Scroll offset that centres a content position, clamped to the scrollable
 * range. Both layouts use it: frame mode passes a cell centre, time mode passes
 * the playhead's pixel position.
 */
export function centerScrollXPx(
  contentXPx: number,
  viewportWidthPx: number,
  contentWidthPx: number
): number {
  const viewport = Math.max(0, finiteOr(viewportWidthPx, 0));
  const maxScrollPx = Math.max(0, Math.max(0, finiteOr(contentWidthPx, 0)) - viewport);
  return Math.min(Math.max(0, finiteOr(contentXPx, 0) - viewport / 2), maxScrollPx);
}
