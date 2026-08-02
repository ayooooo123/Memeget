import {
  containedMediaRect,
  commitGestureTransaction,
  describeCanvasLayers,
  dragKeyframeByViewDelta,
  gestureMoveShouldClaim,
  gesturePointInsideMedia,
  layerHandlePoints,
  layerBodyTouchInsideMedia,
  layerHandleTouchInsideMedia,
  nextDuplicateLayerId,
  normalizedPointToViewPoint,
  resizeKeyframeFromHandle,
  rotateKeyframeFromHandle,
  transformAccessibilityAction,
  transformHandleAccessibilityAction,
  viewPointToNormalizedPoint,
  viewRectToAbsoluteStyle,
} from './memeEditCanvasCore';
import {
  applyProjectAction,
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

  test('gates the transformed local handle touch point, not just the handle center', () => {
    const rightInside = kf({ center: { x: 0.885, y: 0.5 } });
    expect(layerHandlePoints(rightInside, 0.2, rect).resize.x).toBeCloseTo(219);
    expect(layerHandleTouchInsideMedia(rightInside, 0.2, rect, 'resize', { x: 37, y: 22 })).toBe(false);

    const centerOutside = kf({ center: { x: 0.895, y: 0.5 } });
    expect(layerHandlePoints(centerOutside, 0.2, rect).resize.x).toBeCloseTo(221);
    expect(layerHandleTouchInsideMedia(centerOutside, 0.2, rect, 'resize', { x: 6, y: 22 })).toBe(true);

    const topInside = kf({ center: { x: 0.5, y: 0.45 } });
    expect(layerHandlePoints(topInside, 0.2, rect).rotate.y).toBeCloseTo(11);
    expect(layerHandleTouchInsideMedia(topInside, 0.2, rect, 'rotate', { x: 22, y: 7 })).toBe(false);

    const centerOutsideTop = kf({ center: { x: 0.5, y: 0.42 } });
    expect(layerHandlePoints(centerOutsideTop, 0.2, rect).rotate.y).toBeCloseTo(8);
    expect(layerHandleTouchInsideMedia(centerOutsideTop, 0.2, rect, 'rotate', { x: 22, y: 37 })).toBe(true);

    const halfScaleRightInside = kf({ scale: 0.5, center: { x: 0.885, y: 0.5 } });
    expect(layerHandlePoints(halfScaleRightInside, 0.2, rect).resize.x).toBeCloseTo(219);
    expect(layerHandleTouchInsideMedia(halfScaleRightInside, 0.2, rect, 'resize', { x: 37, y: 22 })).toBe(false);

    const doubleScaleRightInside = kf({ scale: 2, center: { x: 0.795, y: 0.5 } });
    expect(layerHandlePoints(doubleScaleRightInside, 0.2, rect).resize.x).toBeCloseTo(219);
    expect(layerHandleTouchInsideMedia(doubleScaleRightInside, 0.2, rect, 'resize', { x: 37, y: 22 })).toBe(false);

    const doubleScaleCenterOutside = kf({ scale: 2, center: { x: 0.805, y: 0.5 } });
    expect(layerHandlePoints(doubleScaleCenterOutside, 0.2, rect).resize.x).toBeCloseTo(221);
    expect(layerHandleTouchInsideMedia(doubleScaleCenterOutside, 0.2, rect, 'resize', { x: 6, y: 22 })).toBe(true);

    const halfScaleTopInside = kf({ scale: 0.5, center: { x: 0.5, y: 0.45 } });
    expect(layerHandlePoints(halfScaleTopInside, 0.2, rect).rotate.y).toBeCloseTo(11);
    expect(layerHandleTouchInsideMedia(halfScaleTopInside, 0.2, rect, 'rotate', { x: 22, y: 7 })).toBe(false);

    const doubleScaleTopInside = kf({ scale: 2, center: { x: 0.5, y: 0.63 } });
    expect(layerHandlePoints(doubleScaleTopInside, 0.2, rect).rotate.y).toBeCloseTo(11);
    expect(layerHandleTouchInsideMedia(doubleScaleTopInside, 0.2, rect, 'rotate', { x: 22, y: 7 })).toBe(false);

    const doubleScaleCenterOutsideTop = kf({ scale: 2, center: { x: 0.5, y: 0.6 } });
    expect(layerHandlePoints(doubleScaleCenterOutsideTop, 0.2, rect).rotate.y).toBeCloseTo(8);
    expect(layerHandleTouchInsideMedia(doubleScaleCenterOutsideTop, 0.2, rect, 'rotate', { x: 22, y: 37 })).toBe(true);
  });

  test('gates rotated layer body touch points instead of unrotated box coordinates', () => {
    const flippedAtLeftEdge = kf({ rotationDegrees: 180, center: { x: 0.005, y: 0.5 } });
    expect(layerBodyTouchInsideMedia(flippedAtLeftEdge, 0.2, rect, { x: 1, y: 22 })).toBe(true);
    expect(layerBodyTouchInsideMedia(flippedAtLeftEdge, 0.2, rect, { x: 43, y: 22 })).toBe(false);
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

describe('gesture transaction coalescing', () => {
  test('commits many reducer updates from one gesture as one undo state', () => {
    const project = createDefaultImageProject({ uri: 'file:///source.jpg', name: 'source.jpg', width: 100, height: 100 });
    const layer = { id: 'caption', kind: 'text' as const, text: 'caption', width: 0.4, style: { preset: 'impact' as const, color: '#fff', outlineColor: '#000', outlineScale: 0.05, backgroundColor: null, opacity: 1, align: 'center' as const, uppercase: true }, active: null, keyframes: [kf()] };
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


describe('canvas layer descriptors', () => {
  test('keeps project layer order and marks missing subject masks unavailable', () => {
    const project = createDefaultImageProject({ uri: 'file:///source.jpg', name: 'source.jpg', width: 100, height: 100 });
    project.layers = [
      { id: 'text', kind: 'text', text: 'hello', width: 0.4, style: { preset: 'impact', color: '#fff', outlineColor: '#000', outlineScale: 0.05, backgroundColor: null, opacity: 1, align: 'center', uppercase: false }, active: null, keyframes: [kf()] },
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
});
