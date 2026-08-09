import {
  DEFAULT_DRAW_SETTINGS,
  DRAW_SHAPE_MIN_EXTENT,
  activeDrawLayer,
  canAppendElement,
  appendFreehandPoint,
  buildDrawElement,
  createDrawLayer,
  drawLayerSummary,
  isDrawLayerFull,
  withAppendedElement,
  withClearedElements,
  type DrawSettings,
} from './memeDrawToolCore';
import { MAX_DRAW_STROKE_SCALE, PROJECT_LIMITS } from './memeEditProjectCore';

function settings(overrides: Partial<DrawSettings> = {}): DrawSettings {
  return { ...DEFAULT_DRAW_SETTINGS, ...overrides };
}

describe('appendFreehandPoint', () => {
  test('keeps a point far enough from the last', () => {
    const next = appendFreehandPoint([{ x: 0, y: 0 }], { x: 0.5, y: 0.5 });
    expect(next).toEqual([{ x: 0, y: 0 }, { x: 0.5, y: 0.5 }]);
  });

  test('drops a point within the thinning distance and returns the same array', () => {
    const points = [{ x: 0.5, y: 0.5 }];
    const next = appendFreehandPoint(points, { x: 0.5005, y: 0.5 });
    expect(next).toBe(points);
  });

  test('always keeps the first point', () => {
    expect(appendFreehandPoint([], { x: 0.2, y: 0.3 })).toEqual([{ x: 0.2, y: 0.3 }]);
  });
});

describe('buildDrawElement', () => {
  test('builds a freehand element from a polyline', () => {
    const element = buildDrawElement(settings({ shape: 'free', color: '#fff' }), [
      { x: 0.1, y: 0.1 },
      { x: 0.4, y: 0.5 },
    ]);
    expect(element).toEqual({ shape: 'free', color: '#fff', strokeScale: DEFAULT_DRAW_SETTINGS.strokeScale, filled: false, points: [{ x: 0.1, y: 0.1 }, { x: 0.4, y: 0.5 }] });
  });

  test('returns null for an empty freehand gesture', () => {
    expect(buildDrawElement(settings({ shape: 'free' }), [])).toBeNull();
  });

  test('reduces a fixed shape to its start and end points', () => {
    const element = buildDrawElement(settings({ shape: 'rectangle', filled: true }), [
      { x: 0.1, y: 0.1 },
      { x: 0.5, y: 0.3 },
      { x: 0.8, y: 0.6 },
    ]);
    expect(element?.points).toEqual([{ x: 0.1, y: 0.1 }, { x: 0.8, y: 0.6 }]);
    expect(element?.filled).toBe(true);
  });

  test('rejects a fixed shape dragged less than the minimum extent (a stray tap)', () => {
    const nudge = DRAW_SHAPE_MIN_EXTENT / 2;
    expect(buildDrawElement(settings({ shape: 'line' }), [{ x: 0.5, y: 0.5 }, { x: 0.5 + nudge, y: 0.5 }])).toBeNull();
  });

  test('never fills a non-fillable shape', () => {
    const element = buildDrawElement(settings({ shape: 'arrow', filled: true }), [{ x: 0.1, y: 0.1 }, { x: 0.8, y: 0.8 }]);
    expect(element?.filled).toBe(false);
  });

  test('clamps an out-of-range stroke scale', () => {
    const element = buildDrawElement(settings({ shape: 'free', strokeScale: 99 }), [{ x: 0.5, y: 0.5 }]);
    expect(element?.strokeScale).toBe(MAX_DRAW_STROKE_SCALE);
  });
});

describe('draw layer accumulation', () => {
  test('appends elements immutably and reports fullness', () => {
    const layer = createDrawLayer('d');
    const one = withAppendedElement(layer, buildDrawElement(settings(), [{ x: 0.5, y: 0.5 }])!);
    expect(one.elements).toHaveLength(1);
    expect(layer.elements).toHaveLength(0);
    expect(isDrawLayerFull(one, 1)).toBe(true);
  });

  test('refuses to append past the element cap', () => {
    const full = { ...createDrawLayer('d'), elements: Array.from({ length: PROJECT_LIMITS.maxDrawElements }, () => buildDrawElement(settings(), [{ x: 0.5, y: 0.5 }])!) };
    expect(withAppendedElement(full, buildDrawElement(settings(), [{ x: 0.1, y: 0.1 }])!)).toBe(full);
  });

  test('clears every element', () => {
    const layer = withAppendedElement(createDrawLayer('d'), buildDrawElement(settings(), [{ x: 0.5, y: 0.5 }])!);
    expect(withClearedElements(layer).elements).toEqual([]);
  });

  test('summarizes mark count', () => {
    expect(drawLayerSummary(createDrawLayer('d'))).toBe('Empty drawing');
    const one = withAppendedElement(createDrawLayer('d'), buildDrawElement(settings(), [{ x: 0.5, y: 0.5 }])!);
    expect(drawLayerSummary(one)).toBe('1 mark');
  });
});

describe('activeDrawLayer', () => {
  const draw = createDrawLayer('draw-1');
  const text = { id: 'text-1', kind: 'text' as const } as never;

  test('prefers the selected layer when it is a drawing', () => {
    const later = createDrawLayer('draw-2');
    expect(activeDrawLayer([draw, later], 'draw-1')).toBe(draw);
  });

  test('falls back to the top-most drawing when the selection is not one', () => {
    const later = createDrawLayer('draw-2');
    expect(activeDrawLayer([draw, text, later], 'text-1')).toBe(later);
  });

  test('returns null when there is no draw layer', () => {
    expect(activeDrawLayer([text], null)).toBeNull();
  });
});

describe('draw point budgets', () => {
  test('caps a single freehand gesture at the per-element point limit', () => {
    let points: { x: number; y: number }[] = [];
    // Feed far-apart points (past the thinning distance) beyond the cap.
    for (let i = 0; i < PROJECT_LIMITS.maxPointsPerDrawElement + 50; i += 1) {
      points = appendFreehandPoint(points, { x: (i % 100) / 100, y: (i % 97) / 97 });
    }
    expect(points.length).toBe(PROJECT_LIMITS.maxPointsPerDrawElement);
  });

  test('refuses an element that would push the layer past the point ceiling', () => {
    const bigStroke = buildDrawElement(settings({ shape: 'free' }), Array.from({ length: PROJECT_LIMITS.maxPointsPerDrawElement }, (_, i) => ({ x: i / 2000, y: 0.5 })))!;
    // Eight full strokes = 8192 points = exactly the ceiling; a ninth is refused.
    let layer = createDrawLayer('d');
    for (let i = 0; i < 8; i += 1) layer = withAppendedElement(layer, bigStroke);
    expect(layer.elements).toHaveLength(8);
    expect(canAppendElement(layer, bigStroke)).toBe(false);
    expect(withAppendedElement(layer, bigStroke)).toBe(layer);
  });
});
