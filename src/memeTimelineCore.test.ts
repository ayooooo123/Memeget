import {
  TIMELINE_MAX_ZOOM,
  TIMELINE_MIN_RANGE_US,
  TIMELINE_MIN_ZOOM,
  TIMELINE_THUMBNAIL_BASE_INTERVAL_US,
  TimelineThumbnailCache,
  applyTrimHandle,
  clampTimelineZoom,
  clampTrimHandleUs,
  createSeekThrottle,
  flushSeekThrottle,
  formatTimelineTimeUs,
  parseTimelineTimeUs,
  insertSplitPointUs,
  nearestRetainedSourceTimeUs,
  nextSeekThrottleState,
  pendingThumbnailRequests,
  pixelsToTimeUs,
  reconcileLayersForRetainedRanges,
  removeTimelineSegment,
  resolvePlayheadUs,
  seekTargetForLayerSelection,
  splitPointsForRetainedRanges,
  timeUsToPixels,
  timelineContentWidthPx,
  timelineGapBars,
  timelineLayerBar,
  timelineRangeBars,
  timelineReadout,
  timelineSegments,
  timelineThumbnailIntervalUs,
  timelineThumbnailTicks,
  type TimelineScale,
} from './memeTimelineCore';
import {
  commitGestureTransaction,
} from './memeEditCanvasCore';
import {
  createDefaultVideoProject,
  createProjectHistory,
  sourceTimeToOutputTimeUs,
  undoProjectHistory,
  type MemeEditLayer,
  type MemeEditProject,
  type TextLayer,
  type TimeRangeUs,
} from './memeEditProjectCore';

const SECOND = 1_000_000;

function scale(overrides: Partial<TimelineScale> = {}): TimelineScale {
  return { durationUs: 10 * SECOND, viewportWidthPx: 300, zoom: 1, ...overrides };
}

function videoProject(durationUs = 10 * SECOND): MemeEditProject {
  return createDefaultVideoProject({
    uri: 'file:///clip.mp4',
    name: 'clip.mp4',
    width: 1920,
    height: 1080,
    durationUs,
  });
}

function textLayer(id: string, active: TimeRangeUs | null): TextLayer {
  return {
    id,
    kind: 'text',
    text: id,
    width: 0.8,
    fontSize: 0.1,
    style: {
      preset: 'impact',
      color: '#ffffff',
      outlineColor: '#000000',
      outlineScale: 0.06,
      backgroundColor: null,
      opacity: 1,
      align: 'center',
      uppercase: true,
    },
    active,
    keyframes: [
      {
        timeUs: active ? active.startUs : 0,
        center: { x: 0.5, y: 0.5 },
        scale: 1,
        rotationDegrees: 0,
        opacity: 1,
        easing: 'linear',
      },
    ],
  };
}

function withLayers(project: MemeEditProject, layers: MemeEditLayer[]): MemeEditProject {
  return { ...project, layers };
}

function withRanges(project: MemeEditProject, ranges: TimeRangeUs[]): MemeEditProject {
  if (project.video === null) throw new Error('expected a video project');
  return { ...project, video: { ...project.video, retainedRanges: ranges } };
}

describe('pixel <-> time conversion', () => {
  test('content width scales the viewport by zoom', () => {
    expect(timelineContentWidthPx(scale({ zoom: 1 }))).toBe(300);
    expect(timelineContentWidthPx(scale({ zoom: 3 }))).toBe(900);
  });

  test('maps the source endpoints onto the content endpoints', () => {
    const view = scale({ zoom: 2 });
    expect(timeUsToPixels(0, view)).toBe(0);
    expect(timeUsToPixels(10 * SECOND, view)).toBe(600);
    expect(timeUsToPixels(2.5 * SECOND, view)).toBe(150);
  });

  test('round-trips pixels through time at every zoom level', () => {
    for (const zoom of [1, 2, 5, 16]) {
      const view = scale({ zoom });
      for (const px of [0, 17, 123, timelineContentWidthPx(view)]) {
        expect(timeUsToPixels(pixelsToTimeUs(px, view), view)).toBeCloseTo(px, 3);
      }
    }
  });

  test('clamps out-of-range pixels into the source duration and returns integer microseconds', () => {
    const view = scale({ zoom: 2 });
    expect(pixelsToTimeUs(-500, view)).toBe(0);
    expect(pixelsToTimeUs(99_999, view)).toBe(10 * SECOND);
    expect(Number.isSafeInteger(pixelsToTimeUs(123.456, view))).toBe(true);
  });

  test('degenerate scales collapse to zero rather than producing NaN or Infinity', () => {
    expect(pixelsToTimeUs(50, scale({ durationUs: 0 }))).toBe(0);
    expect(timeUsToPixels(5 * SECOND, scale({ durationUs: 0 }))).toBe(0);
    expect(pixelsToTimeUs(Number.NaN, scale())).toBe(0);
    expect(timeUsToPixels(Number.NaN, scale())).toBe(0);
    expect(timelineContentWidthPx(scale({ viewportWidthPx: 0 }))).toBe(0);
  });

  test('zoom is clamped to the supported band', () => {
    expect(clampTimelineZoom(0.1)).toBe(TIMELINE_MIN_ZOOM);
    expect(clampTimelineZoom(1_000)).toBe(TIMELINE_MAX_ZOOM);
    expect(clampTimelineZoom(Number.NaN)).toBe(TIMELINE_MIN_ZOOM);
    expect(clampTimelineZoom(4)).toBe(4);
  });
});

describe('thumbnail ticks', () => {
  test('interval is a rung of the doubling ladder so zooming out reuses cached frames', () => {
    const coarse = timelineThumbnailIntervalUs(scale({ zoom: 1 }), 48, 64);
    const fine = timelineThumbnailIntervalUs(scale({ zoom: 8 }), 48, 64);
    expect(fine).toBeLessThanOrEqual(coarse);
    for (const interval of [coarse, fine]) {
      const rungs = Math.log2(interval / TIMELINE_THUMBNAIL_BASE_INTERVAL_US);
      expect(Number.isInteger(rungs)).toBe(true);
      expect(rungs).toBeGreaterThanOrEqual(0);
    }
    expect(coarse % fine).toBe(0);
  });

  test('interval keeps tiles from overlapping at the requested tile width', () => {
    const view = scale({ zoom: 1 });
    const interval = timelineThumbnailIntervalUs(view, 48, 64);
    expect(timeUsToPixels(interval, view)).toBeGreaterThanOrEqual(48);
  });

  test('tick count is bounded even for a very long clip at maximum zoom', () => {
    const view = scale({ durationUs: 3 * 60 * 60 * SECOND, zoom: TIMELINE_MAX_ZOOM });
    const ticks = timelineThumbnailTicks(view, { tileWidthPx: 48, maxTiles: 40 });
    expect(ticks.length).toBeGreaterThan(0);
    expect(ticks.length).toBeLessThanOrEqual(40);
  });

  test('ticks are sorted, in-bounds, and positioned at their own time', () => {
    const view = scale({ zoom: 2 });
    const ticks = timelineThumbnailTicks(view, { tileWidthPx: 48, maxTiles: 64 });
    expect(ticks.length).toBeGreaterThan(1);
    let previousUs = -1;
    for (const tick of ticks) {
      expect(tick.timeUs).toBeGreaterThan(previousUs);
      previousUs = tick.timeUs;
      expect(tick.timeUs).toBeGreaterThanOrEqual(0);
      expect(tick.timeUs).toBeLessThanOrEqual(view.durationUs);
      expect(tick.xPx).toBeCloseTo(timeUsToPixels(tick.timeUs, view), 3);
      expect(tick.widthPx).toBeGreaterThan(0);
    }
  });

  test('a scroll window keeps the rendered tile count bounded and covers the window', () => {
    const view = scale({ durationUs: 600 * SECOND, zoom: 8 });
    const all = timelineThumbnailTicks(view, { tileWidthPx: 48, maxTiles: 512 });
    const windowed = timelineThumbnailTicks(view, {
      tileWidthPx: 48,
      maxTiles: 512,
      scrollXPx: 900,
      windowWidthPx: 300,
      overscanPx: 48,
    });
    expect(windowed.length).toBeLessThan(all.length);
    expect(windowed.length).toBeGreaterThan(0);
    for (const tick of windowed) {
      expect(tick.xPx).toBeGreaterThanOrEqual(900 - 48 - tick.widthPx);
      expect(tick.xPx).toBeLessThanOrEqual(900 + 300 + 48);
    }
  });

  test('a zero-duration source still yields exactly one poster tick', () => {
    const ticks = timelineThumbnailTicks(scale({ durationUs: 0 }), { tileWidthPx: 48, maxTiles: 64 });
    expect(ticks).toEqual([{ timeUs: 0, xPx: 0, widthPx: 48 }]);
  });

  // Regression: tiles used to be a fixed `tileWidthPx` wide while the interval
  // that placed them was chosen to be at LEAST that wide, so on a real device
  // the strip rendered as 44dp tiles at 73dp spacing — a dotted line, not a
  // filmstrip. A tile now fills its interval.
  test('tiles abut so the strip is continuous, and are never narrower than the tile width', () => {
    for (const zoom of [1, 2, 8]) {
      const view = scale({ durationUs: 5_050_000, viewportWidthPx: 370, zoom });
      const ticks = timelineThumbnailTicks(view, { tileWidthPx: 44, maxTiles: 48 });
      expect(ticks.length).toBeGreaterThan(1);
      for (let index = 1; index < ticks.length; index += 1) {
        expect(ticks[index - 1].xPx + ticks[index - 1].widthPx).toBeCloseTo(ticks[index].xPx, 6);
      }
      for (const tick of ticks.slice(0, -1)) expect(tick.widthPx).toBeGreaterThanOrEqual(44);
    }
  });

  test('the final tile is clipped to the content edge instead of overhanging it', () => {
    const view = scale({ durationUs: 5_050_000, viewportWidthPx: 370, zoom: 1 });
    const ticks = timelineThumbnailTicks(view, { tileWidthPx: 44, maxTiles: 48 });
    const last = ticks[ticks.length - 1];
    expect(last.xPx + last.widthPx).toBeLessThanOrEqual(timelineContentWidthPx(view) + 1e-6);
  });
});

describe('trim handle clamping', () => {
  const ranges: TimeRangeUs[] = [
    { startUs: 1 * SECOND, endUs: 3 * SECOND },
    { startUs: 5 * SECOND, endUs: 8 * SECOND },
  ];

  test('start handle cannot cross its own end minus the minimum range', () => {
    expect(clampTrimHandleUs(ranges, 1, 'start', 9 * SECOND, 10 * SECOND)).toBe(8 * SECOND - TIMELINE_MIN_RANGE_US);
  });

  test('end handle cannot cross its own start plus the minimum range', () => {
    expect(clampTrimHandleUs(ranges, 1, 'end', 0, 10 * SECOND)).toBe(5 * SECOND + TIMELINE_MIN_RANGE_US);
  });

  test('start handle stops at the previous range end', () => {
    expect(clampTrimHandleUs(ranges, 1, 'start', 2 * SECOND, 10 * SECOND)).toBe(3 * SECOND);
  });

  test('end handle stops at the next range start', () => {
    expect(clampTrimHandleUs(ranges, 0, 'end', 7 * SECOND, 10 * SECOND)).toBe(5 * SECOND);
  });

  test('outer handles clamp to the source bounds', () => {
    expect(clampTrimHandleUs(ranges, 0, 'start', -4 * SECOND, 10 * SECOND)).toBe(0);
    expect(clampTrimHandleUs(ranges, 1, 'end', 40 * SECOND, 10 * SECOND)).toBe(10 * SECOND);
  });

  test('non-finite proposals fall back to the untouched edge', () => {
    expect(clampTrimHandleUs(ranges, 0, 'start', Number.NaN, 10 * SECOND)).toBe(1 * SECOND);
    expect(clampTrimHandleUs(ranges, 0, 'end', Number.POSITIVE_INFINITY, 10 * SECOND)).toBe(3 * SECOND);
  });

  test('an unknown range index is inert', () => {
    expect(clampTrimHandleUs(ranges, 7, 'start', 2 * SECOND, 10 * SECOND)).toBe(0);
    expect(applyTrimHandle(ranges, 7, 'start', 2 * SECOND, 10 * SECOND)).toEqual(ranges);
  });

  test('applying a handle returns sorted, non-overlapping, in-bounds ranges', () => {
    const next = applyTrimHandle(ranges, 0, 'end', 4 * SECOND, 10 * SECOND);
    expect(next).toEqual([
      { startUs: 1 * SECOND, endUs: 4 * SECOND },
      { startUs: 5 * SECOND, endUs: 8 * SECOND },
    ]);
  });

  // A handle dragged onto its neighbour closes the gap, and a closed gap IS one
  // clip — the same collapse the project reducer would perform anyway.
  test('closing a gap collapses the two ranges into one', () => {
    expect(applyTrimHandle(ranges, 0, 'end', 7 * SECOND, 10 * SECOND)).toEqual([
      { startUs: 1 * SECOND, endUs: 8 * SECOND },
    ]);
  });
});

describe('split points and segments', () => {
  const ranges: TimeRangeUs[] = [{ startUs: 0, endUs: 10 * SECOND }];

  test('inserting a split point yields a sorted deduped set', () => {
    let points = splitPointsForRetainedRanges([], ranges);
    points = insertSplitPointUs(points, 6 * SECOND, ranges) ?? points;
    points = insertSplitPointUs(points, 2 * SECOND, ranges) ?? points;
    expect(points).toEqual([2 * SECOND, 6 * SECOND]);
    expect(insertSplitPointUs(points, 6 * SECOND, ranges)).toBeNull();
  });

  test('a split outside every retained range is rejected', () => {
    const gapped: TimeRangeUs[] = [
      { startUs: 0, endUs: 2 * SECOND },
      { startUs: 6 * SECOND, endUs: 10 * SECOND },
    ];
    expect(insertSplitPointUs([], 4 * SECOND, gapped)).toBeNull();
  });

  test('a split too close to a boundary to leave two usable segments is rejected', () => {
    expect(insertSplitPointUs([], Math.floor(TIMELINE_MIN_RANGE_US / 2), ranges)).toBeNull();
    expect(insertSplitPointUs([], 0, ranges)).toBeNull();
    expect(insertSplitPointUs([], 10 * SECOND, ranges)).toBeNull();
  });

  test('segments subdivide the retained ranges into sorted non-overlapping spans', () => {
    const segments = timelineSegments(ranges, [3 * SECOND, 7 * SECOND]);
    expect(segments).toEqual([
      { startUs: 0, endUs: 3 * SECOND, rangeIndex: 0 },
      { startUs: 3 * SECOND, endUs: 7 * SECOND, rangeIndex: 0 },
      { startUs: 7 * SECOND, endUs: 10 * SECOND, rangeIndex: 0 },
    ]);
    for (let index = 1; index < segments.length; index += 1) {
      expect(segments[index].startUs).toBe(segments[index - 1].endUs);
      expect(segments[index].startUs).toBeLessThan(segments[index].endUs);
    }
  });

  test('segments ignore split points that fall in a removed gap', () => {
    const gapped: TimeRangeUs[] = [
      { startUs: 0, endUs: 2 * SECOND },
      { startUs: 6 * SECOND, endUs: 10 * SECOND },
    ];
    expect(timelineSegments(gapped, [4 * SECOND, 8 * SECOND])).toEqual([
      { startUs: 0, endUs: 2 * SECOND, rangeIndex: 0 },
      { startUs: 6 * SECOND, endUs: 8 * SECOND, rangeIndex: 1 },
      { startUs: 8 * SECOND, endUs: 10 * SECOND, rangeIndex: 1 },
    ]);
  });

  test('splitPointsForRetainedRanges drops points that no longer sit inside a range', () => {
    const gapped: TimeRangeUs[] = [{ startUs: 0, endUs: 4 * SECOND }];
    expect(splitPointsForRetainedRanges([2 * SECOND, 8 * SECOND], gapped)).toEqual([2 * SECOND]);
  });

  test('removing a middle segment leaves two sorted non-overlapping ranges and drops its split points', () => {
    const result = removeTimelineSegment(ranges, [3 * SECOND, 7 * SECOND], 1, 10 * SECOND);
    expect(result).not.toBeNull();
    expect(result?.retainedRanges).toEqual([
      { startUs: 0, endUs: 3 * SECOND },
      { startUs: 7 * SECOND, endUs: 10 * SECOND },
    ]);
    expect(result?.splitPoints).toEqual([]);
  });

  test('removing the only segment is refused so the project never loses all video', () => {
    expect(removeTimelineSegment(ranges, [], 0, 10 * SECOND)).toBeNull();
  });

  test('removing an out-of-bounds segment index is refused', () => {
    expect(removeTimelineSegment(ranges, [3 * SECOND], 9, 10 * SECOND)).toBeNull();
  });
});

describe('layer reconciliation on trim', () => {
  test('a layer that is now entirely inside a removed gap is removed', () => {
    const project = withLayers(videoProject(), [textLayer('gone', { startUs: 4 * SECOND, endUs: 5 * SECOND })]);
    const result = reconcileLayersForRetainedRanges(project, [
      { startUs: 0, endUs: 3 * SECOND },
      { startUs: 6 * SECOND, endUs: 10 * SECOND },
    ]);
    expect(result.removedLayerIds).toEqual(['gone']);
    expect(result.clampedLayers).toEqual([]);
  });

  test('a layer that overhangs a trim is clamped to the retained intersection', () => {
    const project = withLayers(videoProject(), [textLayer('over', { startUs: 2 * SECOND, endUs: 9 * SECOND })]);
    const result = reconcileLayersForRetainedRanges(project, [{ startUs: 3 * SECOND, endUs: 8 * SECOND }]);
    expect(result.removedLayerIds).toEqual([]);
    expect(result.clampedLayers).toEqual([{ id: 'over', active: { startUs: 3 * SECOND, endUs: 8 * SECOND } }]);
  });

  test('a layer spanning a removed gap keeps the hull of its surviving intersections', () => {
    const project = withLayers(videoProject(), [textLayer('span', { startUs: 1 * SECOND, endUs: 9 * SECOND })]);
    const result = reconcileLayersForRetainedRanges(project, [
      { startUs: 2 * SECOND, endUs: 3 * SECOND },
      { startUs: 6 * SECOND, endUs: 7 * SECOND },
    ]);
    expect(result.clampedLayers).toEqual([{ id: 'span', active: { startUs: 2 * SECOND, endUs: 7 * SECOND } }]);
  });

  test('untimed and already-valid layers are left alone', () => {
    const project = withLayers(videoProject(), [
      textLayer('always', null),
      textLayer('inside', { startUs: 1 * SECOND, endUs: 2 * SECOND }),
    ]);
    const result = reconcileLayersForRetainedRanges(project, [{ startUs: 0, endUs: 5 * SECOND }]);
    expect(result.clampedLayers).toEqual([]);
    expect(result.removedLayerIds).toEqual([]);
  });

  test('the whole trim is exactly one action list ending in one undo entry', () => {
    const project = withLayers(videoProject(), [
      textLayer('gone', { startUs: 4 * SECOND, endUs: 5 * SECOND }),
      textLayer('over', { startUs: 2 * SECOND, endUs: 9 * SECOND }),
    ]);
    const retained = [
      { startUs: 0, endUs: 3 * SECOND },
      { startUs: 6 * SECOND, endUs: 8 * SECOND },
    ];
    const result = reconcileLayersForRetainedRanges(project, retained);
    expect(result.actions[0]).toEqual({ type: 'set-video-retained-ranges', retainedRanges: retained });
    expect(result.actions).toHaveLength(3);

    const before = createProjectHistory(project);
    const after = commitGestureTransaction(before, result.actions);
    expect(after.past).toHaveLength(1);
    expect(after.present.video?.retainedRanges).toEqual(retained);
    expect(after.present.layers.map((layer) => layer.id)).toEqual(['over']);
    expect(after.present.layers[0].active).toEqual({ startUs: 2 * SECOND, endUs: 8 * SECOND });
  });

  test('a no-op trim produces no actions at all', () => {
    const project = videoProject();
    const result = reconcileLayersForRetainedRanges(project, [{ startUs: 0, endUs: 10 * SECOND }]);
    expect(result.actions).toEqual([]);
  });

  test('an image project is inert', () => {
    const project = videoProject();
    const imageish: MemeEditProject = { ...project, video: null };
    expect(reconcileLayersForRetainedRanges(imageish, [{ startUs: 0, endUs: 1 * SECOND }]).actions).toEqual([]);
  });
});

describe('seek throttling', () => {
  test('the first request seeks immediately', () => {
    const step = nextSeekThrottleState(createSeekThrottle(), 1 * SECOND, 1_000, 120);
    expect(step.seekUs).toBe(1 * SECOND);
  });

  test('requests inside the interval are coalesced to the newest pending value', () => {
    let state = createSeekThrottle();
    let step = nextSeekThrottleState(state, 1 * SECOND, 1_000, 120);
    state = step.state;
    step = nextSeekThrottleState(state, 2 * SECOND, 1_030, 120);
    expect(step.seekUs).toBeNull();
    state = step.state;
    step = nextSeekThrottleState(state, 3 * SECOND, 1_060, 120);
    expect(step.seekUs).toBeNull();
    state = step.state;
    step = nextSeekThrottleState(state, 4 * SECOND, 1_130, 120);
    expect(step.seekUs).toBe(4 * SECOND);
    expect(step.state.pendingUs).toBeNull();
  });

  test('a flush after the drag emits the last coalesced position exactly once', () => {
    let state = nextSeekThrottleState(createSeekThrottle(), 1 * SECOND, 1_000, 120).state;
    state = nextSeekThrottleState(state, 5 * SECOND, 1_010, 120).state;
    const flushed = flushSeekThrottle(state, 1_020);
    expect(flushed.seekUs).toBe(5 * SECOND);
    expect(flushSeekThrottle(flushed.state, 1_030).seekUs).toBeNull();
  });

  test('a repeat of the position already sent never re-seeks', () => {
    const state = nextSeekThrottleState(createSeekThrottle(), 1 * SECOND, 1_000, 120).state;
    expect(nextSeekThrottleState(state, 1 * SECOND, 9_000, 120).seekUs).toBeNull();
  });

  test('a drag of 60 moves over one second issues at most the throttled number of seeks', () => {
    let state = createSeekThrottle();
    let seeks = 0;
    for (let frame = 0; frame < 60; frame += 1) {
      const step = nextSeekThrottleState(state, frame * 100_000, 1_000 + frame * 16, 120);
      state = step.state;
      if (step.seekUs !== null) seeks += 1;
    }
    expect(seeks).toBeLessThanOrEqual(9);
    expect(seeks).toBeGreaterThan(1);
  });

  test('the visual playhead follows the drag while the seek lags behind', () => {
    expect(resolvePlayheadUs(7 * SECOND, 1 * SECOND)).toBe(7 * SECOND);
    expect(resolvePlayheadUs(null, 1 * SECOND)).toBe(1 * SECOND);
  });
});

describe('layer selection seeking', () => {
  test('selecting a layer whose range excludes the playhead seeks to its start', () => {
    const layer = textLayer('a', { startUs: 4 * SECOND, endUs: 6 * SECOND });
    expect(seekTargetForLayerSelection(layer, 1 * SECOND)).toBe(4 * SECOND);
    expect(seekTargetForLayerSelection(layer, 9 * SECOND)).toBe(4 * SECOND);
  });

  test('no seek when the playhead already sits inside the layer range or the layer is untimed', () => {
    expect(seekTargetForLayerSelection(textLayer('a', { startUs: 4 * SECOND, endUs: 6 * SECOND }), 5 * SECOND)).toBeNull();
    expect(seekTargetForLayerSelection(textLayer('a', { startUs: 4 * SECOND, endUs: 6 * SECOND }), 4 * SECOND)).toBeNull();
    expect(seekTargetForLayerSelection(textLayer('a', null), 9 * SECOND)).toBeNull();
    expect(seekTargetForLayerSelection(null, 9 * SECOND)).toBeNull();
  });
});

describe('bars', () => {
  const view = scale({ zoom: 1 });

  test('retained ranges become bars at their pixel positions', () => {
    expect(timelineRangeBars([{ startUs: 2 * SECOND, endUs: 5 * SECOND }], view)).toEqual([
      { startUs: 2 * SECOND, endUs: 5 * SECOND, xPx: 60, widthPx: 90 },
    ]);
  });

  test('removed gaps are their own bars, including head and tail gaps', () => {
    expect(
      timelineGapBars(
        [
          { startUs: 2 * SECOND, endUs: 4 * SECOND },
          { startUs: 6 * SECOND, endUs: 8 * SECOND },
        ],
        view
      )
    ).toEqual([
      { startUs: 0, endUs: 2 * SECOND, xPx: 0, widthPx: 60 },
      { startUs: 4 * SECOND, endUs: 6 * SECOND, xPx: 120, widthPx: 60 },
      { startUs: 8 * SECOND, endUs: 10 * SECOND, xPx: 240, widthPx: 60 },
    ]);
  });

  test('a fully retained clip has no gap bars', () => {
    expect(timelineGapBars([{ startUs: 0, endUs: 10 * SECOND }], view)).toEqual([]);
  });

  test('an untimed layer bar spans the whole strip', () => {
    expect(timelineLayerBar(textLayer('a', null), view)).toEqual({
      startUs: 0,
      endUs: 10 * SECOND,
      xPx: 0,
      widthPx: 300,
    });
  });

  test('a timed layer bar covers only its active range and stays visible when tiny', () => {
    expect(timelineLayerBar(textLayer('a', { startUs: 1 * SECOND, endUs: 3 * SECOND }), view)).toEqual({
      startUs: 1 * SECOND,
      endUs: 3 * SECOND,
      xPx: 30,
      widthPx: 60,
    });
    expect(timelineLayerBar(textLayer('a', { startUs: 1 * SECOND, endUs: 1 * SECOND + 100 }), view).widthPx)
      .toBeGreaterThanOrEqual(2);
  });
});

describe('readout', () => {
  test('formats microseconds as tabular m:ss.cc', () => {
    expect(formatTimelineTimeUs(0)).toBe('0:00.00');
    expect(formatTimelineTimeUs(3_456_789)).toBe('0:03.45');
    expect(formatTimelineTimeUs(75 * SECOND)).toBe('1:15.00');
    expect(formatTimelineTimeUs(-5)).toBe('0:00.00');
    expect(formatTimelineTimeUs(Number.NaN)).toBe('0:00.00');
    expect(formatTimelineTimeUs(3_600 * SECOND)).toBe('60:00.00');
  });

  test('parses typed timecodes back to microseconds', () => {
    const duration = 7_200 * SECOND;
    expect(parseTimelineTimeUs('0:00.00', duration)).toBe(0);
    expect(parseTimelineTimeUs('1:15', duration)).toBe(75 * SECOND);
    expect(parseTimelineTimeUs('1:15.25', duration)).toBe(75 * SECOND + 250_000);
    expect(parseTimelineTimeUs('12.5', duration)).toBe(12 * SECOND + 500_000);
    expect(parseTimelineTimeUs('1:02:03', duration)).toBe(3_723 * SECOND);
    expect(parseTimelineTimeUs('  9  ', duration)).toBe(9 * SECOND);
    expect(parseTimelineTimeUs('0.123456', duration)).toBe(123_456);
  });

  test('rejects junk, negatives, out-of-range minutes and times past the source', () => {
    const duration = 10 * SECOND;
    for (const bad of ['', 'abc', '-1', '1:2:3:4', '1:75', '1:15', '.5', '1.']) {
      expect(parseTimelineTimeUs(bad, duration)).toBeNull();
    }
    expect(parseTimelineTimeUs('10', duration)).toBe(duration);
  });

  test('the readout reports OUTPUT time and OUTPUT duration, not source time', () => {
    const project = withRanges(videoProject(), [
      { startUs: 0, endUs: 2 * SECOND },
      { startUs: 6 * SECOND, endUs: 10 * SECOND },
    ]);
    const readout = timelineReadout(project, 7 * SECOND);
    expect(readout.currentUs).toBe(sourceTimeToOutputTimeUs(7 * SECOND, project.video!.retainedRanges, 1));
    expect(readout.currentUs).toBe(3 * SECOND);
    expect(readout.durationUs).toBe(6 * SECOND);
    expect(readout.currentLabel).toBe('0:03.00');
    expect(readout.durationLabel).toBe('0:06.00');
  });

  test('speed shortens the reported output duration', () => {
    const project = videoProject();
    const fast: MemeEditProject = { ...project, video: { ...project.video!, speed: 2 } };
    expect(timelineReadout(fast, 10 * SECOND).durationUs).toBe(5 * SECOND);
  });

  test('a playhead inside a removed gap reports the nearest retained output time', () => {
    const project = withRanges(videoProject(), [
      { startUs: 0, endUs: 2 * SECOND },
      { startUs: 6 * SECOND, endUs: 10 * SECOND },
    ]);
    expect(nearestRetainedSourceTimeUs(project.video!.retainedRanges, 3 * SECOND)).toBe(2 * SECOND);
    expect(nearestRetainedSourceTimeUs(project.video!.retainedRanges, 5 * SECOND)).toBe(6 * SECOND);
    expect(timelineReadout(project, 3 * SECOND).currentUs).toBe(2 * SECOND);
  });

  test('an image project reads out zeros', () => {
    const project = videoProject();
    const imageish: MemeEditProject = { ...project, video: null, source: { ...project.source, durationUs: null } };
    expect(timelineReadout(imageish, 0)).toEqual({
      currentUs: 0,
      durationUs: 0,
      currentLabel: '0:00.00',
      durationLabel: '0:00.00',
    });
  });
});

describe('thumbnail cache', () => {
  test('keys on source and timestamp together', () => {
    const cache = new TimelineThumbnailCache(4);
    cache.set('a', 0, 'thumb-a0');
    cache.set('b', 0, 'thumb-b0');
    expect(cache.get('a', 0)).toBe('thumb-a0');
    expect(cache.get('b', 0)).toBe('thumb-b0');
    expect(cache.get('a', 1)).toBeUndefined();
  });

  test('evicts the least recently used entry past its bound', () => {
    const cache = new TimelineThumbnailCache(3);
    cache.set('s', 1, 'one');
    cache.set('s', 2, 'two');
    cache.set('s', 3, 'three');
    expect(cache.get('s', 1)).toBe('one');
    cache.set('s', 4, 'four');
    expect(cache.size).toBe(3);
    expect(cache.get('s', 2)).toBeUndefined();
    expect(cache.get('s', 1)).toBe('one');
    expect(cache.get('s', 4)).toBe('four');
  });

  test('remembers a failed extraction as null so it is never retried in a loop', () => {
    const cache = new TimelineThumbnailCache(4);
    cache.set('s', 5, null);
    expect(cache.has('s', 5)).toBe(true);
    expect(cache.get('s', 5)).toBeNull();
  });

  test('a non-positive bound still holds nothing rather than growing without limit', () => {
    const cache = new TimelineThumbnailCache(0);
    cache.set('s', 1, 'one');
    expect(cache.size).toBe(0);
  });

  test('pending requests skip cached and failed ticks and stay bounded', () => {
    const cache = new TimelineThumbnailCache(16);
    cache.set('s', 0, 'have');
    cache.set('s', 1_000_000, null);
    const ticks = [0, 1_000_000, 2_000_000, 3_000_000, 4_000_000].map((timeUs) => ({
      timeUs,
      xPx: 0,
      widthPx: 48,
    }));
    expect(pendingThumbnailRequests(ticks, cache, 's', 2)).toEqual([2_000_000, 3_000_000]);
    expect(pendingThumbnailRequests(ticks, cache, 'other', 3)).toEqual([0, 1_000_000, 2_000_000]);
  });
});

// The whole point of the gesture layer: a drag emits many intermediate states
// but leaves exactly one thing behind in history. These drive the same call
// sequence the component does, minus React.
describe('gesture integration', () => {
  const view = scale({ zoom: 1 });

  test('a 40-move trim drag lands one undo entry and stays sorted throughout', () => {
    const project = withLayers(withRanges(videoProject(), [{ startUs: 0, endUs: 10 * SECOND }]), [
      textLayer('late', { startUs: 8 * SECOND, endUs: 9 * SECOND }),
      textLayer('early', { startUs: 0, endUs: 4 * SECOND }),
    ]);
    const baseRanges = project.video!.retainedRanges;
    const originPx = timeUsToPixels(10 * SECOND, view);

    let preview: TimeRangeUs[] = baseRanges;
    for (let move = 1; move <= 40; move += 1) {
      // Every move re-derives from the snapshot, exactly as the component does.
      preview = applyTrimHandle(baseRanges, 0, 'end', pixelsToTimeUs(originPx - move * 2, view), 10 * SECOND);
      expect(preview).toHaveLength(1);
      expect(preview[0].startUs).toBeLessThan(preview[0].endUs);
      expect(preview[0].endUs).toBeLessThanOrEqual(10 * SECOND);
    }
    // 80px back on a 300px strip over 10s == 2.667s trimmed off the tail.
    expect(preview[0].endUs).toBe(7_333_333);

    const plan = reconcileLayersForRetainedRanges(project, preview);
    const after = commitGestureTransaction(createProjectHistory(project), plan.actions);
    expect(after.past).toHaveLength(1);
    expect(after.future).toHaveLength(0);
    expect(after.transaction).toBeNull();
    // The tail layer died with the tail; the head layer was never in the way.
    expect(after.present.layers.map((layer) => layer.id)).toEqual(['early']);
    expect(after.present.video?.retainedRanges).toEqual([{ startUs: 0, endUs: 7_333_333 }]);

    const undone = undoProjectHistory(after);
    expect(undone.present.layers.map((layer) => layer.id)).toEqual(['late', 'early']);
    expect(undone.present.video?.retainedRanges).toEqual(baseRanges);
    expect(undone.past).toHaveLength(0);
  });

  test('split then remove is one further undo entry and restores cleanly', () => {
    const project = withRanges(videoProject(), [{ startUs: 0, endUs: 10 * SECOND }]);
    const ranges = project.video!.retainedRanges;
    const points = insertSplitPointUs([], 4 * SECOND, ranges);
    expect(points).toEqual([4 * SECOND]);

    const removal = removeTimelineSegment(ranges, points!, 0, 10 * SECOND);
    expect(removal?.retainedRanges).toEqual([{ startUs: 4 * SECOND, endUs: 10 * SECOND }]);

    const plan = reconcileLayersForRetainedRanges(project, removal!.retainedRanges);
    const after = commitGestureTransaction(createProjectHistory(project), plan.actions);
    expect(after.past).toHaveLength(1);
    expect(after.present.video?.retainedRanges).toEqual([{ startUs: 4 * SECOND, endUs: 10 * SECOND }]);
    expect(undoProjectHistory(after).present.video?.retainedRanges).toEqual([{ startUs: 0, endUs: 10 * SECOND }]);
  });

  test('a scrub drag flushes its swallowed tail so the player lands where the finger left', () => {
    let throttle = createSeekThrottle();
    const seeks: number[] = [];
    for (let frame = 0; frame < 30; frame += 1) {
      const step = nextSeekThrottleState(throttle, pixelsToTimeUs(frame * 4, view), 1_000 + frame * 16, 120);
      throttle = step.state;
      if (step.seekUs !== null) seeks.push(step.seekUs);
    }
    const flushed = flushSeekThrottle(throttle, 1_500);
    if (flushed.seekUs !== null) seeks.push(flushed.seekUs);
    expect(seeks.length).toBeLessThan(30);
    // Wherever the throttle cut the stream, the last emitted seek is the last
    // position the gesture actually reached.
    expect(seeks[seeks.length - 1]).toBe(pixelsToTimeUs(29 * 4, view));
  });
});
