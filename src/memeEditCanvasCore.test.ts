import {
  containedMediaRect,
  commitGestureTransaction,
  dragKeyframeByViewDelta,
  normalizedPointToViewPoint,
  resizeKeyframeFromHandle,
  rotateKeyframeFromHandle,
  viewPointToNormalizedPoint,
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
