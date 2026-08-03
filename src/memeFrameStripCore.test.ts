import {
  FRAME_CELL_WIDTH_PX,
  MAX_FRAME_CACHE_ENTRIES,
  createFrameCache,
  frameIndexAt,
  frameStripIntervalUs,
  frameStripTicks,
  frameWindowAt,
  frameWindowTicks,
  pendingFrameRequests,
  snapToFrameUs,
  sourceFrameIntervalUs,
  totalFrameCount,
} from './memeFrameStripCore';
import { timeUsToPixels, type TimelineScale } from './memeTimelineCore';
const FRAME_30FPS = 33_333;

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
