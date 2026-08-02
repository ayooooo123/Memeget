import {
  containedMediaRect,
  commitGestureTransaction,
  canDuplicateLayer,
  beforeAfterAccessibilityNextState,
  beforeAfterPointerNextState,
  describeCanvasLayers,
  canvasLayerVisualDescriptor,
  captureTransformGesture,
  dragKeyframeByViewDelta,
  gestureMoveShouldClaim,
  gesturePointInsideMedia,
  layerHandlePoints,
  layerBodyTouchInsideMedia,
  layerHandleTouchInsideMedia,
  memeRemixExportControlState,
  memeEditToolsForSource,
  projectHistoryCommandAvailability,
  runProjectHistoryCommand,
  nextDuplicateLayerId,
  selectedLayerIdAfterDelete,
  memeRemixHeaderLayout,
  normalizedPointToViewPoint,
  resizeKeyframeFromHandle,
  rotateKeyframeFromHandle,
  transformAccessibilityAction,
  transformHandleAccessibilityAction,
  viewPointToNormalizedPoint,
  upsertLayerKeyframeAtCapturedTime,
  viewRectToAbsoluteStyle,
} from './memeEditCanvasCore';
import {
  applyProjectAction,
  beginProjectTransaction,
  commitProjectTransaction,
  createDefaultImageProject,
  createProjectHistory,
  type TransformKeyframe,
} from './memeEditProjectCore';

function kf(overrides: Partial<TransformKeyframe> = {}): TransformKeyframe {
  return {
    timeUs: 0,
    center: { x: 0.5, y: 0.5 },
    scale: 1,
    rotationDegrees: 0,
    opacity: 1,
    easing: 'linear',
    ...overrides,
  };
}

describe('containedMediaRect', () => {
  test('contain-fits landscape media inside a portrait view with vertical letterbox', () => {
    expect(containedMediaRect({ width: 300, height: 600 }, { width: 1200, height: 600, rotation: 0 })).toEqual({
      x: 0,
      y: 225,
      width: 300,
      height: 150,
    });
  });

  test('contain-fits portrait media inside a landscape view with horizontal letterbox', () => {
    expect(containedMediaRect({ width: 600, height: 300 }, { width: 600, height: 1200, rotation: 0 })).toEqual({
      x: 225,
      y: 0,
      width: 150,
      height: 300,
    });
  });

  test('uses rotated display dimensions for quarter-turned media', () => {
    expect(containedMediaRect({ width: 400, height: 300 }, { width: 1200, height: 600, rotation: 90 })).toEqual({
      x: 125,
      y: 0,
      width: 150,
      height: 300,
    });
  });

  test('returns null for non-finite or empty input', () => {
    expect(containedMediaRect({ width: Infinity, height: 300 }, { width: 100, height: 100, rotation: 0 })).toBeNull();
    expect(containedMediaRect({ width: 300, height: 0 }, { width: 100, height: 100, rotation: 0 })).toBeNull();
  });
});

describe('source-specific editor tools', () => {
  test('exposes image transform and text replacement only for image projects', () => {
    expect(memeEditToolsForSource('image')).toEqual(['layers', 'text', 'transform', 'replace-text']);
    expect(memeEditToolsForSource('video')).toEqual(['layers', 'text']);
  });
});

describe('view and normalized coordinates', () => {
  const rect = { x: 20, y: 10, width: 200, height: 100 };

  test('maps view points only while inside actual media bounds', () => {
    expect(viewPointToNormalizedPoint({ x: 120, y: 60 }, rect)).toEqual({ x: 0.5, y: 0.5 });
    expect(viewPointToNormalizedPoint({ x: 19.99, y: 60 }, rect)).toBeNull();
    expect(viewPointToNormalizedPoint({ x: 221, y: 60 }, rect)).toBeNull();
    expect(viewPointToNormalizedPoint({ x: 120, y: 111 }, rect)).toBeNull();
  });

  test('converts normalized coordinates back into view space and clamps finite values', () => {
    expect(normalizedPointToViewPoint({ x: 0.25, y: 0.75 }, rect)).toEqual({ x: 70, y: 85 });
    expect(normalizedPointToViewPoint({ x: -1, y: 2 }, rect)).toEqual({ x: 20, y: 110 });
    expect(normalizedPointToViewPoint({ x: Number.NaN, y: Infinity }, rect)).toEqual({ x: 20, y: 10 });
  });
});

  test('converts view rect origins to React Native absolute layout keys', () => {
    expect(viewRectToAbsoluteStyle({ x: 125, y: 40, width: 150, height: 300 })).toEqual({
      left: 125,
      top: 40,
      width: 150,
      height: 300,
    });
  });

  test('gates child gestures against actual media bounds with nonzero letterbox origin', () => {
    const media = { x: 125, y: 40, width: 150, height: 300 };
    expect(gesturePointInsideMedia({ x: 126, y: 41 }, media)).toBe(true);
    expect(gesturePointInsideMedia({ x: 124.5, y: 120 }, media)).toBe(false);
    expect(gesturePointInsideMedia({ x: 276, y: 120 }, media)).toBe(false);
    expect(gesturePointInsideMedia({ x: 150, y: 39.5 }, media)).toBe(false);
  });

  test('does not claim a move after the gesture started in letterbox', () => {
    expect(gestureMoveShouldClaim(false, { dx: 80, dy: 0 })).toBe(false);
    expect(gestureMoveShouldClaim(true, { dx: 1, dy: 1 })).toBe(false);
    expect(gestureMoveShouldClaim(true, { dx: 3, dy: 0 })).toBe(true);
  });

describe('transform gesture math', () => {
  const rect = { x: 20, y: 10, width: 200, height: 100 };

  test('converts drag pixels into clamped normalized center deltas', () => {
    expect(dragKeyframeByViewDelta(kf(), { dx: 40, dy: -25 }, rect).center).toEqual({ x: 0.7, y: 0.25 });
    expect(dragKeyframeByViewDelta(kf({ center: { x: 0.95, y: 0.1 } }), { dx: 200, dy: -100 }, rect).center).toEqual({
      x: 1,
      y: 0,
    });
  });

  test('ignores non-finite drag values instead of poisoning the project', () => {
    const start = kf({ center: { x: 0.4, y: 0.6 } });
    expect(dragKeyframeByViewDelta(start, { dx: Number.NaN, dy: 10 }, rect)).toBe(start);
    expect(dragKeyframeByViewDelta(start, { dx: 10, dy: 10 }, { ...rect, width: 0 })).toBe(start);
  });

  test('resize handle scales from center distance with finite clamping', () => {
    const start = kf({ scale: 2 });
    const next = resizeKeyframeFromHandle(start, { x: 100, y: 100 }, { x: 130, y: 100 }, { x: 160, y: 100 });
    expect(next.scale).toBe(4);
    expect(resizeKeyframeFromHandle(start, { x: 100, y: 100 }, { x: 100, y: 100 }, { x: 160, y: 100 })).toBe(start);
    expect(resizeKeyframeFromHandle(start, { x: 100, y: 100 }, { x: 130, y: 100 }, { x: Infinity, y: 100 }).scale).toBe(2);
  });

  test('rotation handle adds signed angle delta around the layer center', () => {
    const start = kf({ rotationDegrees: 10 });
    expect(rotateKeyframeFromHandle(start, { x: 100, y: 100 }, { x: 130, y: 100 }, { x: 100, y: 130 }).rotationDegrees).toBe(100);
    expect(rotateKeyframeFromHandle(start, { x: 100, y: 100 }, { x: 100, y: 100 }, { x: 100, y: 130 })).toBe(start);
  });

  test('computes screen-space handles from a rotated layer without jumping', () => {
    const handles = layerHandlePoints(kf({ rotationDegrees: 90, scale: 1, center: { x: 0.5, y: 0.5 } }), 0.2, rect);
    expect(handles.center).toEqual({ x: 120, y: 60 });
    expect(handles.resize.x).toBeCloseTo(98);
    expect(handles.resize.y).toBeCloseTo(82);
    expect(handles.rotate.x).toBeCloseTo(164);
    expect(handles.rotate.y).toBeCloseTo(60);
    const resized = resizeKeyframeFromHandle(kf({ rotationDegrees: 90 }), handles.center, handles.resize, {
      x: handles.resize.x - 10,
      y: handles.resize.y + 20,
    });
    const flipped = layerHandlePoints(kf({ rotationDegrees: 180, scale: 1, center: { x: 0.01, y: 0.5 } }), 0.2, rect);
    expect(gesturePointInsideMedia(flipped.resize, rect)).toBe(false);
    expect(resized.scale).toBeGreaterThan(1);
  });

  test('gates fixed-size handle touch points against their scaled visual corners', () => {
    const halfScale = kf({ scale: 0.5, center: { x: 0.885, y: 0.5 } });
    expect(layerHandlePoints(halfScale, 0.2, rect).resize.x).toBeCloseTo(208);
    expect(layerHandleTouchInsideMedia(halfScale, 0.2, rect, 'resize', { x: 6, y: 22 })).toBe(true);
    expect(layerHandleTouchInsideMedia(halfScale, 0.2, rect, 'resize', { x: 37, y: 22 })).toBe(false);

    const doubleScale = kf({ scale: 2, center: { x: 0.775, y: 0.5 } });
    expect(layerHandlePoints(doubleScale, 0.2, rect).resize.x).toBeCloseTo(219);
    expect(layerHandleTouchInsideMedia(doubleScale, 0.2, rect, 'resize', { x: 6, y: 22 })).toBe(true);
    expect(layerHandleTouchInsideMedia(doubleScale, 0.2, rect, 'resize', { x: 37, y: 22 })).toBe(false);

    const halfScaleTop = kf({ scale: 0.5, center: { x: 0.5, y: 0.45 } });
    expect(layerHandlePoints(halfScaleTop, 0.2, rect).rotate.y).toBeCloseTo(22);
    expect(layerHandleTouchInsideMedia(halfScaleTop, 0.2, rect, 'rotate', { x: 22, y: 7 })).toBe(false);
    expect(layerHandleTouchInsideMedia(halfScaleTop, 0.2, rect, 'rotate', { x: 22, y: 37 })).toBe(true);
  });

  test('gates rotated layer body touch points instead of unrotated box coordinates', () => {
    const flippedAtLeftEdge = kf({ rotationDegrees: 180, center: { x: 0.005, y: 0.5 } });
    expect(layerBodyTouchInsideMedia(flippedAtLeftEdge, 0.2, rect, { x: 1, y: 22 })).toBe(true);
    expect(layerBodyTouchInsideMedia(flippedAtLeftEdge, 0.2, rect, { x: 43, y: 22 })).toBe(false);
  });

  test.each([0.01, 0.5, 2, 16])('keeps fixed 44 DIP handles around the %.2fx visual bounds', (scale) => {
    const descriptor = canvasLayerVisualDescriptor(kf({ scale, rotationDegrees: 30 }), 0.2, rect);

    expect(descriptor.content.baseWidthDip).toBe(44);
    expect(descriptor.content.scale).toBe(scale);
    expect(descriptor.controls.widthDip).toBeCloseTo(44 * scale);
    expect(descriptor.controls.heightDip).toBeCloseTo(44 * scale);
    expect(descriptor.controls.handleSizeDip).toBe(44);
    expect(descriptor.content.rotationDegrees).toBe(30);
    expect(descriptor.controls.rotationDegrees).toBe(30);
  });

  test('accessibility transform actions commit bounded keyframe changes', () => {
    expect(transformAccessibilityAction('increment', kf(), rect).center).toEqual({ x: 0.51, y: 0.5 });
    expect(transformAccessibilityAction('decrement', kf(), rect).center).toEqual({ x: 0.49, y: 0.5 });
    expect(transformAccessibilityAction('escape', kf({ scale: 16 }), rect).scale).toBe(16);
    expect(transformHandleAccessibilityAction('resize', 'increment', kf({ scale: 1 }), rect).scale).toBe(1.05);
    expect(transformHandleAccessibilityAction('resize', 'decrement', kf({ scale: 0.01 }), rect).scale).toBe(0.01);
    expect(transformHandleAccessibilityAction('rotate', 'increment', kf({ rotationDegrees: 0 }), rect).rotationDegrees).toBe(5);
    expect(transformHandleAccessibilityAction('rotate', 'decrement', kf({ rotationDegrees: 0 }), rect).rotationDegrees).toBe(-5);
  });
});

  test('commits a transform at grant time when playback advances before release', () => {
    const grantTimeUs = 1_000_000;
    const releaseTimeUs = 2_050_000;
    const mediaRect = { x: 20, y: 10, width: 200, height: 100 };
    const original = [kf({ timeUs: 0 }), kf({ timeUs: 3_000_000, center: { x: 0.8, y: 0.5 } })];
    const gesture = captureTransformGesture(kf({ center: { x: 0.4, y: 0.5 } }), grantTimeUs);
    const movedAtRelease = dragKeyframeByViewDelta(gesture.keyframe, { dx: 40, dy: 0 }, mediaRect);

    const committed = upsertLayerKeyframeAtCapturedTime(original, movedAtRelease, gesture.timeUs);

    expect(releaseTimeUs - grantTimeUs).toBeGreaterThan(1_000_000);
    expect(committed.map((frame) => frame.timeUs)).toEqual([0, grantTimeUs, 3_000_000]);
    expect(committed.find((frame) => frame.timeUs === grantTimeUs)?.center).toEqual({ x: 0.6, y: 0.5 });
    expect(committed.find((frame) => frame.timeUs === releaseTimeUs)).toBeUndefined();
  });

describe('gesture transaction coalescing', () => {
  test('commits many reducer updates from one gesture as one undo state', () => {
    const project = createDefaultImageProject({ uri: 'file:///source.jpg', name: 'source.jpg', width: 100, height: 100 });
    const layer = { id: 'caption', kind: 'text' as const, text: 'caption', width: 0.4, fontSize: 0.1, style: { preset: 'impact' as const, color: '#fff', outlineColor: '#000', outlineScale: 0.05, backgroundColor: null, opacity: 1, align: 'center' as const, uppercase: true }, active: null, keyframes: [kf()] };
    const seeded = applyProjectAction(createProjectHistory(project), { type: 'add-layer', layer });


    const next = commitGestureTransaction(seeded, [
      { type: 'set-layer-keyframes', id: 'caption', keyframes: [kf({ center: { x: 0.6, y: 0.5 } })] },
      { type: 'set-layer-keyframes', id: 'caption', keyframes: [kf({ center: { x: 0.7, y: 0.5 } })] },
      { type: 'set-layer-keyframes', id: 'caption', keyframes: [kf({ center: { x: 0.8, y: 0.5 } })] },
    ]);

    expect(next.past).toHaveLength(seeded.past.length + 1);
    expect(next.present.layers[0]?.kind).toBe('text');
    expect(next.present.layers[0]?.kind === 'text' ? next.present.layers[0].keyframes[0]?.center : null).toEqual({ x: 0.8, y: 0.5 });
  });

  test('empty or no-op gesture transactions do not add undo history', () => {
    const history = createProjectHistory(createDefaultImageProject({ uri: 'file:///source.jpg', name: 'source.jpg', width: 100, height: 100 }));
    expect(commitGestureTransaction(history, [])).toBe(history);
  });
});

describe('studio shell UI contracts', () => {
  test('disables duplicate at the project layer limit', () => {
    expect(canDuplicateLayer(63, 64)).toBe(true);
    expect(canDuplicateLayer(64, 64)).toBe(false);
  });

  test('one focused pending-text Undo flushes, commits, and navigates; Redo restores it', () => {
    const source = createDefaultImageProject({ uri: 'file:///source.jpg', name: 'source.jpg', width: 100, height: 100 });
    source.layers = [{
      id: 'caption',
      kind: 'text',
      text: 'before',
      width: 0.4,
      fontSize: 0.1,
      style: { preset: 'impact', color: '#fff', outlineColor: '#000', outlineScale: 0.05, backgroundColor: null, opacity: 1, align: 'center', uppercase: false },
      active: null,
      keyframes: [kf()],
    }];
    let history = beginProjectTransaction(createProjectHistory(source));
    let pendingText: string | null = 'typed while focused';
    let externalTextRevision = 0;
    const flushPending = () => {
      if (pendingText === null) return;
      const current = history.present.layers[0];
      if (!current || current.kind !== 'text') throw new Error('missing text layer');
      history = applyProjectAction(history, { type: 'update-layer', layer: { ...current, text: pendingText } });
      history = commitProjectTransaction(history);
      pendingText = null;
    };
    const updateHistory = (updater: (current: typeof history) => typeof history) => {
      history = updater(history);
    };
    const announceHistoryNavigation = () => {
      externalTextRevision += 1;
    };

    expect(projectHistoryCommandAvailability(history)).toEqual({ canUndo: true, canRedo: false });
    runProjectHistoryCommand('undo', flushPending, updateHistory, announceHistoryNavigation);
    expect(history.transaction).toBeNull();
    expect(history.present.layers[0]).toMatchObject({ text: 'before' });
    expect(projectHistoryCommandAvailability(history)).toEqual({ canUndo: false, canRedo: true });
    expect(externalTextRevision).toBe(1);

    runProjectHistoryCommand('redo', flushPending, updateHistory, announceHistoryNavigation);
    expect(history.present.layers[0]).toMatchObject({ text: 'typed while focused' });
    expect(externalTextRevision).toBe(2);
  });

  test('uses explicit compact header rows below 430dp and single row at 430dp', () => {
    for (const width of [320, 360, 375, 390]) {
      const layout = memeRemixHeaderLayout(width);
      expect(layout.mode).toBe('compact-two-row');
      expect(layout.showFullExportLabel).toBe(false);
      expect(layout.exportLabel).toBe('Out');
      expect(layout.rows).toEqual([
        { key: 'identity', controls: ['Cancel', 'TitleStatus'], maxWidth: width, minControlSize: 44 },
        { key: 'commands', controls: ['Before', 'Undo', 'Redo', 'Out'], maxWidth: width, minControlSize: 44 },
      ]);
      for (const row of layout.rows) {
        expect(row.maxWidth).toBeLessThanOrEqual(width);
        expect(row.minControlSize).toBeGreaterThanOrEqual(44);
      }
    }

    expect(memeRemixHeaderLayout(430)).toEqual({
      mode: 'single-row',
      showFullExportLabel: true,
      exportLabel: 'Export',
      rows: [
        { key: 'single', controls: ['Cancel', 'TitleStatus', 'Before', 'Undo', 'Redo', 'Export'], maxWidth: 430, minControlSize: 44 },
      ],
    });
  });

  test('export control is disabled whenever the editor or export callback is unavailable', () => {
    const compact = memeRemixHeaderLayout(390);
    expect(memeRemixExportControlState(compact, { ready: true, exportBusy: false, discarding: false, hasExport: true })).toEqual({
      label: 'Out',
      disabled: false,
      accessibilityState: { disabled: false },
    });
    expect(memeRemixExportControlState(compact, { ready: true, exportBusy: false, discarding: false, hasExport: false })).toEqual({
      label: 'No export',
      disabled: true,
      accessibilityState: { disabled: true },
    });
    expect(memeRemixExportControlState(compact, { ready: true, exportBusy: false, discarding: true, hasExport: true }).disabled).toBe(true);
    expect(memeRemixExportControlState(compact, { ready: true, exportBusy: true, discarding: false, hasExport: true }).disabled).toBe(true);

    const wide = memeRemixHeaderLayout(430);
    expect(memeRemixExportControlState(wide, { ready: false, exportBusy: false, discarding: false, hasExport: true })).toEqual({
      label: 'Export',
      disabled: true,
      accessibilityState: { disabled: true },
    });
    expect(memeRemixExportControlState(wide, { ready: true, exportBusy: false, discarding: false, hasExport: false })).toEqual({
      label: 'Export unavailable',
      disabled: true,
      accessibilityState: { disabled: true },
    });
  });

  test('before-after pointer hold ends visible while accessibility activate toggles', () => {
    expect(beforeAfterPointerNextState(false, 'press-in')).toBe(true);
    expect(beforeAfterPointerNextState(true, 'press-out')).toBe(false);
    expect(beforeAfterPointerNextState(false, 'press-out')).toBe(false);
    expect(beforeAfterAccessibilityNextState(false, 'activate')).toBe(true);
    expect(beforeAfterAccessibilityNextState(true, 'activate')).toBe(false);
  });
});


describe('canvas layer descriptors', () => {
  test('keeps project layer order and marks missing subject masks unavailable', () => {
    const project = createDefaultImageProject({ uri: 'file:///source.jpg', name: 'source.jpg', width: 100, height: 100 });
    project.layers = [
      { id: 'text', kind: 'text', text: 'hello', width: 0.4, fontSize: 0.1, style: { preset: 'impact', color: '#fff', outlineColor: '#000', outlineScale: 0.05, backgroundColor: null, opacity: 1, align: 'center', uppercase: false }, active: null, keyframes: [kf()] },
      { id: 'subject', kind: 'subject', subjectIndex: null, maskTrackId: 'missing-mask', active: null, keyframes: [kf()], outlineColor: null, outlineScale: 0, shadowScale: 0 },
      { id: 'media', kind: 'media', assetUri: 'file:///overlay.png', assetKind: 'image', fit: 'contain', targetMaskTrackId: null, active: null, keyframes: [kf()] },
    ];

    expect(describeCanvasLayers(project)).toEqual([
      { id: 'text', kind: 'text', unavailable: false, label: 'Text layer' },
      { id: 'subject', kind: 'subject', unavailable: true, label: 'Subject mask unavailable' },
      { id: 'media', kind: 'media', unavailable: false, label: 'Image overlay' },
    ]);
  });
});
describe('deterministic layer IDs', () => {
  test('continues duplicate suffixes after restoring a draft with existing duplicates', () => {
    expect(nextDuplicateLayerId('studio-42', ['caption', 'studio-42-dup-1', 'studio-42-dup-3'])).toBe('studio-42-dup-4');
  });

  test('selects a deterministic neighbor after deleting the active layer', () => {
    const ids = ['bottom', 'middle', 'top'];
    expect(selectedLayerIdAfterDelete(ids, 'middle', 'middle')).toBe('top');
    expect(selectedLayerIdAfterDelete(ids, 'top', 'top')).toBe('middle');
    expect(selectedLayerIdAfterDelete(ids, 'bottom', 'other')).toBe('other');
    expect(selectedLayerIdAfterDelete(['only'], 'only', 'only')).toBeNull();
  });
});
