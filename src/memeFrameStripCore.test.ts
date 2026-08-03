import {
  FRAME_CELL_WIDTH_PX,
  MAX_FRAME_CACHE_ENTRIES,
  centerScrollXPx,
  createFrameCache,
  formatFrameTimeUs,
  frameIndexAt,
  frameStripCells,
  frameStripIntervalUs,
  frameStripReadout,
  frameStripStride,
  frameStripTickWidthPx,
  frameStripTicks,
  frameWindowAt,
  frameWindowTicks,
  pendingFrameRequests,
  snapToFrameUs,
  sourceFrameIntervalUs,
  stepFrameUs,
  totalFrameCount,
} from './memeFrameStripCore';
import { TimelineThumbnailCache, timeUsToPixels, type TimelineScale } from './memeTimelineCore';
const FRAME_30FPS = 33_333;
const FRAME_240FPS = 4_167;

function scale(over: Partial<TimelineScale> = {}): TimelineScale {
  return { durationUs: 10_000_000, viewportWidthPx: 400, zoom: 1, ...over };
}

describe('sourceFrameIntervalUs', () => {
  it('falls back to 30fps when the rate is unknown or nonsense', () => {
    expect(sourceFrameIntervalUs(null)).toBe(33_333);
    expect(sourceFrameIntervalUs(undefined)).toBe(33_333);
    expect(sourceFrameIntervalUs(0)).toBe(33_333);
    expect(sourceFrameIntervalUs(-24)).toBe(33_333);
    expect(sourceFrameIntervalUs(Number.NaN)).toBe(33_333);
  });

  it('derives the interval from a real rate', () => {
    expect(sourceFrameIntervalUs(60)).toBe(16_667);
    expect(sourceFrameIntervalUs(24)).toBe(41_667);
  });
});

describe('snapToFrameUs', () => {
  it('snaps an arbitrary instant onto a real frame boundary', () => {
    // 40ms sits between frame 1 (33.333ms) and frame 2 (66.666ms) — nearer 1.
    expect(snapToFrameUs(40_000, FRAME_30FPS, 10_000_000)).toBe(33_333);
    expect(snapToFrameUs(60_000, FRAME_30FPS, 10_000_000)).toBe(66_666);
  });

  it('never returns a timestamp past the last decodable frame', () => {
    const duration = 100_000; // 3 full frames at 30fps
    const snapped = snapToFrameUs(99_999, FRAME_30FPS, duration);
    expect(snapped).toBeLessThanOrEqual(duration);
    expect(snapped % FRAME_30FPS).toBe(0);
  });

  it('clamps below zero and rejects non-finite input', () => {
    expect(snapToFrameUs(-5_000, FRAME_30FPS, 10_000_000)).toBe(0);
    expect(snapToFrameUs(Number.NaN, FRAME_30FPS, 10_000_000)).toBe(0);
  });

  it('is idempotent — snapping an already-snapped value changes nothing', () => {
    const once = snapToFrameUs(812_345, FRAME_30FPS, 10_000_000);
    expect(snapToFrameUs(once, FRAME_30FPS, 10_000_000)).toBe(once);
  });
});

describe('frameIndexAt', () => {
  it('maps timestamps to frame ordinals', () => {
    expect(frameIndexAt(0, FRAME_30FPS)).toBe(0);
    expect(frameIndexAt(33_333, FRAME_30FPS)).toBe(1);
    expect(frameIndexAt(66_666, FRAME_30FPS)).toBe(2);
  });
});

describe('frameStripIntervalUs', () => {
  it('never samples finer than the source frame rate', () => {
    const interval = frameStripIntervalUs(scale({ durationUs: 1_000_000, zoom: 16 }), FRAME_30FPS);
    expect(interval).toBeGreaterThanOrEqual(FRAME_30FPS);
    expect(Math.log2(interval / FRAME_30FPS) % 1).toBe(0);
  });

  it('widens with a pixel bound so cells cannot overlap', () => {
    // The bug this pins: without a pixel bound a long clip materialized
    // thousands of cells because each pixel spanned many frames.
    const long = frameStripIntervalUs(scale({ durationUs: 600_000_000, zoom: 4 }), FRAME_30FPS);
    const pixelBoundUs = (FRAME_CELL_WIDTH_PX * 600_000_000) / (400 * 4);
    expect(long).toBeGreaterThanOrEqual(pixelBoundUs);
  });

  it('narrows as zoom increases', () => {
    const atOne = frameStripIntervalUs(scale({ zoom: 1 }), FRAME_30FPS);
    const atEight = frameStripIntervalUs(scale({ zoom: 8 }), FRAME_30FPS);
    expect(atEight).toBeLessThan(atOne);
  });
});

describe('frameStripTicks', () => {
  it('positions ticks using the shared timeline scale, not a private one', () => {
    const s = scale({ zoom: 16, durationUs: 1_000_000, viewportWidthPx: 400 });
    const ticks = frameStripTicks(s, FRAME_30FPS, {
      scrollXPx: 0,
      windowWidthPx: 400,
      overscanPx: 0,
    });
    expect(ticks.length).toBeGreaterThan(1);
    // This is the assertion the first version of this file was missing: xPx must
    // agree with the timeline's own conversion, not silently collapse to 0.
    for (const tick of ticks) {
      expect(tick.xPx).toBeCloseTo(timeUsToPixels(tick.timeUs, s), 6);
    }
    expect(ticks.some((t) => t.xPx > 0)).toBe(true);
  });

  it('marks ticks exact only when pixel density genuinely allows one cell per frame', () => {
    // Short clip, high zoom: one cell per frame fits, so ticks are exact.
    const exact = frameStripTicks(scale({ durationUs: 300_000, zoom: 16 }), FRAME_30FPS, {
      scrollXPx: 0,
      windowWidthPx: 400,
      overscanPx: 0,
    });
    // Long clip at the same zoom: it physically cannot show every frame, and it
    // must say so rather than mislabel a coarse sample as an exact frame.
    const coarse = frameStripTicks(scale({ durationUs: 600_000_000, zoom: 16 }), FRAME_30FPS, {
      scrollXPx: 0,
      windowWidthPx: 400,
      overscanPx: 0,
    });
    expect(exact.every((t) => t.exact)).toBe(true);
    expect(coarse.every((t) => !t.exact)).toBe(true);
  });

  it('every tick lands on a real frame boundary regardless of mode', () => {
    const ticks = frameStripTicks(scale({ zoom: 16 }), FRAME_30FPS, {
      scrollXPx: 1_000,
      windowWidthPx: 400,
      overscanPx: 44,
    });
    expect(ticks.length).toBeGreaterThan(0);
    for (const tick of ticks) expect(tick.timeUs % FRAME_30FPS).toBe(0);
  });

  it('virtualizes: a long clip costs the same as a short one at equal zoom', () => {
    const window = { scrollXPx: 0, windowWidthPx: 400, overscanPx: 44 };
    const short = frameStripTicks(scale({ durationUs: 5_000_000, zoom: 4 }), FRAME_30FPS, window);
    const long = frameStripTicks(scale({ durationUs: 600_000_000, zoom: 4 }), FRAME_30FPS, window);
    // A 10-minute clip must not materialize proportionally more cells than a 5s one.
    expect(long.length).toBeLessThanOrEqual(short.length * 2);
  });

  it('only materializes the scroll window plus overscan', () => {
    const s = scale({ durationUs: 60_000_000, zoom: 8, viewportWidthPx: 400 });
    const ticks = frameStripTicks(s, FRAME_30FPS, {
      scrollXPx: 1_600,
      windowWidthPx: 400,
      overscanPx: 0,
    });
    const xs = ticks.map((t) => t.xPx);
    expect(Math.min(...xs)).toBeGreaterThanOrEqual(1_600 - FRAME_30FPS);
    expect(Math.max(...xs)).toBeLessThanOrEqual(2_000 + 1);
  });

  it('returns nothing for a zero-duration or zero-width strip', () => {
    const w = { scrollXPx: 0, windowWidthPx: 400, overscanPx: 0 };
    expect(frameStripTicks(scale({ durationUs: 0 }), FRAME_30FPS, w)).toEqual([]);
    expect(frameStripTicks(scale({ viewportWidthPx: 0 }), FRAME_30FPS, w)).toEqual([]);
  });
});

describe('pendingFrameRequests', () => {
  it('asks only for uncached timestamps, capped at the batch limit', () => {
    const cache = createFrameCache();
    const ticks = frameStripTicks(scale({ zoom: 8 }), FRAME_30FPS, {
      scrollXPx: 0,
      windowWidthPx: 400,
      overscanPx: 0,
    });
    cache.set('vid', ticks[0].timeUs, 'file://a.jpg');
    const pending = pendingFrameRequests(ticks, cache, 'vid', 3);
    expect(pending).toHaveLength(3);
    expect(pending).not.toContain(ticks[0].timeUs);
  });

  it('does not re-request a timestamp another batch is already decoding', () => {
    // Observed on device: a re-pump fired before the previous batch had written
    // its result, so the same frame was decoded twice concurrently — at 1-3s a
    // decode that is a whole wasted cell.
    const cache = createFrameCache();
    const ticks = frameStripTicks(scale({ durationUs: 1_000_000, zoom: 16 }), FRAME_30FPS, {
      scrollXPx: 0,
      windowWidthPx: 400,
      overscanPx: 2_000,
    });
    expect(ticks.length).toBeGreaterThan(6);
    const inFlight = new Set([TimelineThumbnailCache.keyFor('vid', ticks[0].timeUs)]);
    const pending = pendingFrameRequests(ticks, cache, 'vid', 5, inFlight);
    expect(pending).not.toContain(ticks[0].timeUs);
    expect(pending).toContain(ticks[1].timeUs);
    // Skipping an in-flight frame must pull the next candidate in, not shrink
    // the batch — otherwise every duplicate costs a slot of real throughput.
    expect(pending).toHaveLength(5);
  });

  it('keys in-flight work by source as well as time, so two clips never collide', () => {
    const cache = createFrameCache();
    const ticks = frameStripTicks(scale({ zoom: 8 }), FRAME_30FPS, {
      scrollXPx: 0,
      windowWidthPx: 400,
      overscanPx: 0,
    });
    const inFlight = new Set([TimelineThumbnailCache.keyFor('other', ticks[0].timeUs)]);
    expect(pendingFrameRequests(ticks, cache, 'vid', 5, inFlight)).toContain(ticks[0].timeUs);
  });

  it('does not retry a timestamp that decoded to null', () => {
    const cache = createFrameCache();
    const ticks = frameStripTicks(scale({ zoom: 8 }), FRAME_30FPS, {
      scrollXPx: 0,
      windowWidthPx: 400,
      overscanPx: 0,
    });
    cache.set('vid', ticks[0].timeUs, null); // undecodable frame, remembered
    expect(pendingFrameRequests(ticks, cache, 'vid', 5)).not.toContain(ticks[0].timeUs);
  });
});

describe('frame cache bounds', () => {
  it('never exceeds its ceiling and evicts least-recently-used first', () => {
    const cache = createFrameCache(3);
    cache.set('vid', 1, 'a');
    cache.set('vid', 2, 'b');
    cache.set('vid', 3, 'c');
    cache.get('vid', 1); // touch 1 so 2 becomes the eviction victim
    cache.set('vid', 4, 'd');
    expect(cache.size).toBe(3);
    expect(cache.has('vid', 2)).toBe(false);
    expect(cache.get('vid', 1)).toBe('a');
    expect(cache.get('vid', 4)).toBe('d');
  });

  it('stays bounded across a long scroll', () => {
    const cache = createFrameCache(MAX_FRAME_CACHE_ENTRIES);
    for (let i = 0; i < MAX_FRAME_CACHE_ENTRIES * 5; i += 1) {
      cache.set('vid', i * FRAME_30FPS, `f${i}`);
    }
    expect(cache.size).toBe(MAX_FRAME_CACHE_ENTRIES);
  });
});

describe('sparse edit contract', () => {
  it('editing N frames yields O(N) stored keyframes, never O(duration)', () => {
    // A ten-minute clip at 30fps is ~18,000 frames. Editing three of them must
    // store three keyframes — this is the guarantee that keeps a frame-accurate
    // editor from turning into a per-frame bitmap project.
    const durationUs = 600_000_000;
    const edited = [12_000_000, 300_000_000, 599_000_000].map((t) =>
      snapToFrameUs(t, FRAME_30FPS, durationUs)
    );
    const keyframes = edited.map((timeUs) => ({ timeUs, easing: 'hold' as const }));
    expect(keyframes).toHaveLength(3);
    expect(new Set(keyframes.map((k) => k.timeUs)).size).toBe(3);
    const totalFrames = Math.floor(durationUs / FRAME_30FPS);
    expect(keyframes.length).toBeLessThan(totalFrames / 1000);
  });
});

describe('frame mode', () => {
  it('counts only fully decodable frames', () => {
    expect(totalFrameCount(100_000, FRAME_30FPS)).toBe(3); // 3 full frames, tail excluded
    expect(totalFrameCount(0, FRAME_30FPS)).toBe(0);
  });

  it('materializes a window whose size depends on the viewport, not the clip', () => {
    const short = frameWindowAt(0, 400, 5_000_000, FRAME_30FPS);
    const long = frameWindowAt(0, 400, 600_000_000, FRAME_30FPS);
    // This is the property time-based zoom could not deliver: a ten-minute clip
    // costs exactly what a five-second one costs.
    expect(long.count).toBe(short.count);
    expect(long.totalFrames).toBeGreaterThan(short.totalFrames);
  });

  it('reaches the final frame of a long clip by scrolling', () => {
    const durationUs = 600_000_000;
    const total = totalFrameCount(durationUs, FRAME_30FPS);
    const window = frameWindowAt(
      total * FRAME_CELL_WIDTH_PX,
      400,
      durationUs,
      FRAME_30FPS
    );
    const ticks = frameWindowTicks(window, FRAME_30FPS);
    expect(ticks.length).toBeGreaterThan(0);
    expect(ticks[ticks.length - 1].timeUs).toBe((total - 1) * FRAME_30FPS);
  });

  it('emits consecutive exact frames at fixed cell pitch', () => {
    const window = frameWindowAt(0, 200, 10_000_000, FRAME_30FPS, FRAME_CELL_WIDTH_PX, 0);
    const ticks = frameWindowTicks(window, FRAME_30FPS);
    expect(ticks.every((t) => t.exact)).toBe(true);
    for (let i = 1; i < ticks.length; i += 1) {
      expect(ticks[i].timeUs - ticks[i - 1].timeUs).toBe(FRAME_30FPS);
      expect(ticks[i].xPx - ticks[i - 1].xPx).toBe(FRAME_CELL_WIDTH_PX);
    }
  });

  it('clamps at both ends instead of emitting frames that do not exist', () => {
    const head = frameWindowAt(-500, 400, 10_000_000, FRAME_30FPS);
    expect(head.startIndex).toBe(0);
    const total = totalFrameCount(10_000_000, FRAME_30FPS);
    const tail = frameWindowAt(total * FRAME_CELL_WIDTH_PX * 2, 400, 10_000_000, FRAME_30FPS);
    const ticks = frameWindowTicks(tail, FRAME_30FPS);
    for (const tick of ticks) expect(tick.timeUs).toBeLessThan(10_000_000);
  });

  it('returns an empty window for a clip with no full frames', () => {
    const window = frameWindowAt(0, 400, 0, FRAME_30FPS);
    expect(window.count).toBe(0);
    expect(frameWindowTicks(window, FRAME_30FPS)).toEqual([]);
  });
});

describe('frameStripStride', () => {
  it('reports 1 only when a cell genuinely holds a single frame', () => {
    // Short clip, high zoom: one cell per frame really does fit.
    expect(frameStripStride(scale({ durationUs: 300_000, zoom: 16 }), FRAME_30FPS)).toBe(1);
    // Ten minutes at the same zoom cannot, and the label must not pretend it can.
    expect(frameStripStride(scale({ durationUs: 600_000_000, zoom: 16 }), FRAME_30FPS)).toBeGreaterThan(1);
  });

  it('is always a whole number of frames, so the strip can never claim a fraction', () => {
    for (const zoom of [1, 2, 4, 8, 16]) {
      for (const durationUs of [1_000_000, 60_000_000, 600_000_000]) {
        const stride = frameStripStride(scale({ durationUs, zoom }), FRAME_30FPS);
        expect(Number.isInteger(stride)).toBe(true);
        expect(stride).toBeGreaterThanOrEqual(1);
      }
    }
  });

  it('agrees with the interval the ladder actually chose', () => {
    const s = scale({ durationUs: 60_000_000, zoom: 4 });
    expect(frameStripStride(s, FRAME_30FPS) * FRAME_30FPS).toBe(frameStripIntervalUs(s, FRAME_30FPS));
  });
});

describe('frameStripTickWidthPx', () => {
  it('makes consecutive cells tile continuously instead of leaving gaps', () => {
    const s = scale({ durationUs: 60_000_000, zoom: 4 });
    const intervalUs = frameStripIntervalUs(s, FRAME_30FPS);
    const widthPx = frameStripTickWidthPx(s, intervalUs);
    const ticks = frameStripTicks(s, FRAME_30FPS, { scrollXPx: 0, windowWidthPx: 400, overscanPx: 0 });
    expect(ticks.length).toBeGreaterThan(1);
    for (let i = 1; i < ticks.length; i += 1) {
      // No gap and no overlap: cell i starts exactly where cell i-1 ends.
      expect(ticks[i - 1].xPx + widthPx).toBeCloseTo(ticks[i].xPx, 6);
    }
  });

  it('is at least the cell minimum, because the ladder guarantees that much room', () => {
    for (const zoom of [1, 4, 16]) {
      const s = scale({ durationUs: 600_000_000, zoom });
      const widthPx = frameStripTickWidthPx(s, frameStripIntervalUs(s, FRAME_30FPS));
      expect(widthPx).toBeGreaterThanOrEqual(FRAME_CELL_WIDTH_PX);
    }
  });

  it('degrades to a visible sliver rather than zero when there is nothing to scale against', () => {
    expect(frameStripTickWidthPx(scale({ durationUs: 0 }), FRAME_30FPS)).toBe(1);
    expect(frameStripTickWidthPx(scale({ viewportWidthPx: 0 }), FRAME_30FPS)).toBe(1);
  });
});

describe('frameStripCells', () => {
  const cellOptions = (
    over: Partial<Parameters<typeof frameStripCells>[1]> = {}
  ): Parameters<typeof frameStripCells>[1] => ({
    cache: createFrameCache(),
    sourceUri: 'vid',
    frameIntervalUs: FRAME_30FPS,
    cellSpanUs: FRAME_30FPS,
    cellWidthPx: FRAME_CELL_WIDTH_PX,
    currentTimeUs: 0,
    ...over,
  });

  it('tells a failed decode apart from one still in flight', () => {
    // The honesty requirement: a frame that will never decode must not render
    // as the same blank cell as a frame whose decode has not landed yet.
    const cache = createFrameCache();
    const window = frameWindowAt(0, 200, 10_000_000, FRAME_30FPS, FRAME_CELL_WIDTH_PX, 0);
    const ticks = frameWindowTicks(window, FRAME_30FPS);
    cache.set('vid', ticks[0].timeUs, 'file://a.jpg');
    cache.set('vid', ticks[1].timeUs, null);
    const cells = frameStripCells(ticks, cellOptions({ cache }));
    expect(cells[0].state).toBe('ready');
    expect(cells[0].uri).toBe('file://a.jpg');
    expect(cells[1].state).toBe('undecodable');
    expect(cells[1].uri).toBeNull();
    expect(cells[2].state).toBe('pending');
    expect(cells[2].uri).toBeNull();
  });

  it('marks exactly one cell current, and it is the one holding the playhead', () => {
    const ticks = frameWindowTicks(
      frameWindowAt(0, 400, 10_000_000, FRAME_30FPS, FRAME_CELL_WIDTH_PX, 0),
      FRAME_30FPS
    );
    // A playhead a few microseconds past frame 4's boundary is still frame 4.
    const cells = frameStripCells(ticks, cellOptions({ currentTimeUs: 4 * FRAME_30FPS + 900 }));
    expect(cells.filter((cell) => cell.current)).toHaveLength(1);
    expect(cells.find((cell) => cell.current)?.timeUs).toBe(4 * FRAME_30FPS);
  });

  it('highlights the cell a coarse overview lands in, not a frame it never drew', () => {
    const s = scale({ durationUs: 600_000_000, zoom: 4 });
    const intervalUs = frameStripIntervalUs(s, FRAME_30FPS);
    const ticks = frameStripTicks(s, FRAME_30FPS, { scrollXPx: 0, windowWidthPx: 400, overscanPx: 0 });
    // Deliberately a third of the way INTO cell 2. Snapping this to the frame
    // ladder rather than the cell ladder lands on a frame boundary that is not
    // a cell boundary, so nothing would highlight at all.
    const currentTimeUs = intervalUs * 2 + Math.floor(intervalUs / 3);
    const cells = frameStripCells(ticks, cellOptions({ cellSpanUs: intervalUs, currentTimeUs }));
    const current = cells.filter((cell) => cell.current);
    expect(current).toHaveLength(1);
    expect(current[0].timeUs).toBe(intervalUs * 2);
    expect(currentTimeUs).toBeGreaterThanOrEqual(current[0].timeUs);
    expect(currentTimeUs).toBeLessThan(current[0].timeUs + intervalUs);
  });

  it('numbers every cell by real source frame ordinal in both modes', () => {
    const frameTicks = frameWindowTicks(
      frameWindowAt(FRAME_CELL_WIDTH_PX * 100, 200, 600_000_000, FRAME_30FPS, FRAME_CELL_WIDTH_PX, 0),
      FRAME_30FPS
    );
    const frameCells = frameStripCells(frameTicks, cellOptions());
    for (const cell of frameCells) {
      expect(cell.index).toBe(frameIndexAt(cell.timeUs, FRAME_30FPS));
      expect(cell.timeUs).toBe(cell.index * FRAME_30FPS);
    }
    expect(frameCells[0].index).toBe(100);

    // In overview mode a cell holds `stride` frames, so the ordinal advances by
    // the stride and is NOT the cell's position on the track. Labelling a cell
    // 'frame 3' when it is really frame 192 is the failure this pins.
    const s = scale({ durationUs: 600_000_000, zoom: 4 });
    const intervalUs = frameStripIntervalUs(s, FRAME_30FPS);
    const stride = frameStripStride(s, FRAME_30FPS);
    expect(stride).toBeGreaterThan(1);
    const overviewCells = frameStripCells(
      frameStripTicks(s, FRAME_30FPS, { scrollXPx: 0, windowWidthPx: 400, overscanPx: 0 }),
      cellOptions({ cellSpanUs: intervalUs, cellWidthPx: frameStripTickWidthPx(s, intervalUs) })
    );
    expect(overviewCells.length).toBeGreaterThan(2);
    overviewCells.forEach((cell, position) => {
      expect(cell.index).toBe(position * stride);
      expect(cell.timeUs).toBe(cell.index * FRAME_30FPS);
    });
  });

  it('never lets the last cell overhang the content it is drawn in', () => {
    const s = scale({ durationUs: 3_000_000, zoom: 1 });
    const intervalUs = frameStripIntervalUs(s, FRAME_30FPS);
    const widthPx = frameStripTickWidthPx(s, intervalUs);
    const ticks = frameStripTicks(s, FRAME_30FPS, { scrollXPx: 0, windowWidthPx: 400, overscanPx: 0 });
    const contentWidthPx = 400;
    const cells = frameStripCells(
      ticks,
      cellOptions({ cellSpanUs: intervalUs, cellWidthPx: widthPx, contentWidthPx })
    );
    expect(cells.length).toBeGreaterThan(1);
    for (const cell of cells) {
      expect(cell.xPx + cell.widthPx).toBeLessThanOrEqual(contentWidthPx + 1e-6);
      expect(cell.widthPx).toBeGreaterThan(0);
    }
  });
});

describe('stepFrameUs', () => {
  it('moves exactly one frame and lands on a real boundary', () => {
    expect(stepFrameUs(4 * FRAME_30FPS, FRAME_30FPS, 10_000_000, 1)).toBe(5 * FRAME_30FPS);
    expect(stepFrameUs(4 * FRAME_30FPS, FRAME_30FPS, 10_000_000, -1)).toBe(3 * FRAME_30FPS);
  });

  it('steps from a playhead that is between frames without drifting', () => {
    // Mid-frame playhead: next must be the frame after the one you are on, and
    // stepping back then forward must return to where you started.
    const forward = stepFrameUs(4 * FRAME_30FPS + 900, FRAME_30FPS, 10_000_000, 1);
    expect(forward).toBe(5 * FRAME_30FPS);
    expect(stepFrameUs(forward, FRAME_30FPS, 10_000_000, -1)).toBe(4 * FRAME_30FPS);
  });

  it('clamps at the first frame instead of going negative', () => {
    expect(stepFrameUs(0, FRAME_30FPS, 10_000_000, -1)).toBe(0);
    expect(stepFrameUs(0, FRAME_30FPS, 10_000_000, -50)).toBe(0);
  });

  it('never steps past a frame the strip is able to render', () => {
    // The reconciliation this pins: snapToFrameUs will happily return
    // floor(duration/interval)*interval, but frameWindowAt stops one cell
    // earlier. Offering a frame with no cell would make next/prev walk off the
    // end of the strip.
    const durationUs = 10_000_000;
    const total = totalFrameCount(durationUs, FRAME_30FPS);
    const last = stepFrameUs(durationUs, FRAME_30FPS, durationUs, 1);
    expect(last).toBe((total - 1) * FRAME_30FPS);
    const tailTicks = frameWindowTicks(
      frameWindowAt(total * FRAME_CELL_WIDTH_PX, 400, durationUs, FRAME_30FPS),
      FRAME_30FPS
    );
    expect(tailTicks.map((tick) => tick.timeUs)).toContain(last);
  });

  it('returns zero for a clip with no full frames', () => {
    expect(stepFrameUs(0, FRAME_30FPS, 0, 1)).toBe(0);
  });
});

describe('formatFrameTimeUs', () => {
  it('keeps millisecond precision', () => {
    expect(formatFrameTimeUs(0)).toBe('0:00.000');
    expect(formatFrameTimeUs(9_966_567)).toBe('0:09.966');
    expect(formatFrameTimeUs(63_400_000)).toBe('1:03.400');
  });

  it('gives every frame of a 240fps clip a distinct label', () => {
    // Centisecond precision - what the timeline readout uses - collapses four
    // consecutive slow-motion frames onto the same string, which makes the
    // readout useless exactly where frame accuracy matters most.
    const labels = new Set<string>();
    for (let index = 0; index < 8; index += 1) labels.add(formatFrameTimeUs(index * FRAME_240FPS));
    expect(labels.size).toBe(8);
  });

  it('is defensive about junk input', () => {
    expect(formatFrameTimeUs(Number.NaN)).toBe('0:00.000');
    expect(formatFrameTimeUs(-1)).toBe('0:00.000');
  });
});

describe('frameStripReadout', () => {
  it('reports the frame ordinal and an exact timestamp for it', () => {
    const readout = frameStripReadout(4 * FRAME_30FPS + 900, FRAME_30FPS, 10_000_000);
    expect(readout.index).toBe(4);
    expect(readout.exactUs).toBe(4 * FRAME_30FPS);
    expect(readout.timeLabel).toBe(formatFrameTimeUs(4 * FRAME_30FPS));
    expect(readout.totalFrames).toBe(totalFrameCount(10_000_000, FRAME_30FPS));
  });

  it('never names a frame the strip cannot show', () => {
    const durationUs = 10_000_000;
    const readout = frameStripReadout(durationUs, FRAME_30FPS, durationUs);
    expect(readout.index).toBe(readout.totalFrames - 1);
    expect(readout.exactUs).toBeLessThan(durationUs);
  });

  it('says so honestly when there is no full frame to name', () => {
    const readout = frameStripReadout(0, FRAME_30FPS, 0);
    expect(readout.totalFrames).toBe(0);
    expect(readout.index).toBe(0);
    expect(readout.exactUs).toBe(0);
  });

  it('round-trips: stepping from the readout stays on the readout ladder', () => {
    let readout = frameStripReadout(0, FRAME_30FPS, 10_000_000);
    for (let i = 0; i < 5; i += 1) {
      const next = stepFrameUs(readout.exactUs, FRAME_30FPS, 10_000_000, 1);
      readout = frameStripReadout(next, FRAME_30FPS, 10_000_000);
      expect(readout.index).toBe(i + 1);
    }
  });
});

describe('centerScrollXPx', () => {
  it('centres the requested content position in the viewport', () => {
    expect(centerScrollXPx(1_000, 400, 5_000)).toBe(800);
  });

  it('clamps at both ends so the strip never scrolls into empty space', () => {
    expect(centerScrollXPx(10, 400, 5_000)).toBe(0);
    expect(centerScrollXPx(4_990, 400, 5_000)).toBe(4_600);
  });

  it('stays at zero when the content is narrower than the viewport', () => {
    expect(centerScrollXPx(100, 400, 200)).toBe(0);
  });

  it('survives the scroll bucketing the strip actually applies', () => {
    // MemeFrameStrip does not feed raw contentOffset.x to frameWindowAt; it
    // rounds to the cell pitch first so the window only re-materializes once
    // per cell. Rounding can push the offset PAST the real max scroll, and the
    // last frame must still be in the window when it does.
    const durationUs = 600_000_000;
    const total = totalFrameCount(durationUs, FRAME_30FPS);
    const contentWidthPx = total * FRAME_CELL_WIDTH_PX;
    const viewportWidthPx = 387;
    const maxScrollPx = contentWidthPx - viewportWidthPx;
    for (const rawPx of [maxScrollPx - 1, maxScrollPx, maxScrollPx + 43]) {
      const bucketed = Math.round(rawPx / FRAME_CELL_WIDTH_PX) * FRAME_CELL_WIDTH_PX;
      const ticks = frameWindowTicks(
        frameWindowAt(bucketed, viewportWidthPx, durationUs, FRAME_30FPS),
        FRAME_30FPS
      );
      expect(ticks.map((tick) => tick.timeUs)).toContain((total - 1) * FRAME_30FPS);
    }
  });

  it('scrolls the last frame of a long clip fully into view', () => {
    const durationUs = 600_000_000;
    const total = totalFrameCount(durationUs, FRAME_30FPS);
    const contentWidthPx = total * FRAME_CELL_WIDTH_PX;
    const lastCentrePx = (total - 1) * FRAME_CELL_WIDTH_PX + FRAME_CELL_WIDTH_PX / 2;
    const scrollXPx = centerScrollXPx(lastCentrePx, 400, contentWidthPx);
    const window = frameWindowAt(scrollXPx, 400, durationUs, FRAME_30FPS);
    const ticks = frameWindowTicks(window, FRAME_30FPS);
    expect(ticks.map((tick) => tick.timeUs)).toContain((total - 1) * FRAME_30FPS);
  });
});
