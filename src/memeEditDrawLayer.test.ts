import {
  MAX_DRAW_STROKE_SCALE,
  PROJECT_LIMITS,
  createDefaultImageProject,
  createDefaultVideoProject,
  reduceMemeEditProject,
  validateMemeEditProject,
  type DrawElement,
  type DrawLayer,
  type MemeEditLayer,
  type MemeEditProject,
  type ProjectValidationResult,
} from './memeEditProjectCore';

const SECOND_US = 1_000_000;

const imageSource = { uri: 'file:///s.jpg', name: 's.jpg', width: 1200, height: 800 };
const videoSource = {
  uri: 'file:///s.mp4',
  name: 's.mp4',
  width: 1920,
  height: 1080,
  durationUs: 10 * SECOND_US,
};

function element(overrides: Partial<DrawElement> = {}): DrawElement {
  return {
    shape: 'free',
    color: '#ff0000',
    strokeScale: 0.01,
    filled: false,
    points: [
      { x: 0.1, y: 0.1 },
      { x: 0.4, y: 0.4 },
    ],
    ...overrides,
  };
}

function drawLayer(id: string, overrides: Partial<DrawLayer> = {}): DrawLayer {
  return {
    id,
    kind: 'draw',
    opacity: 1,
    active: null,
    elements: [element()],
    ...overrides,
  };
}

function requireDraw(layer: MemeEditLayer | undefined): DrawLayer {
  if (!layer || layer.kind !== 'draw') throw new Error(`Expected draw layer, got ${layer?.kind}`);
  return layer;
}

function validateWith(layer: unknown, kind: 'image' | 'video' = 'image'): ProjectValidationResult {
  const base =
    kind === 'image' ? createDefaultImageProject(imageSource) : createDefaultVideoProject(videoSource);
  const raw = JSON.parse(JSON.stringify(base)) as { layers: unknown[] };
  raw.layers = [layer];
  return validateMemeEditProject(raw);
}

function expectInvalid(layer: unknown, path: string, code?: string): void {
  const result = validateWith(layer);
  expect(result.ok).toBe(false);
  if (result.ok) return;
  expect(result.errors).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ path, ...(code ? { code } : {}), message: expect.any(String) }),
    ])
  );
}

describe('draw layer validation', () => {
  test('accepts a well-formed freehand + shape layer', () => {
    const layer = drawLayer('d1', {
      elements: [
        element({ shape: 'free', points: [{ x: 0, y: 0 }, { x: 0.5, y: 0.5 }, { x: 1, y: 1 }] }),
        element({ shape: 'line', points: [{ x: 0.1, y: 0.1 }, { x: 0.9, y: 0.9 }] }),
        element({ shape: 'arrow', points: [{ x: 0.2, y: 0.2 }, { x: 0.8, y: 0.2 }] }),
        element({ shape: 'rectangle', filled: true, points: [{ x: 0.1, y: 0.1 }, { x: 0.6, y: 0.4 }] }),
        element({ shape: 'ellipse', points: [{ x: 0.3, y: 0.3 }, { x: 0.7, y: 0.7 }] }),
      ],
    });
    expect(validateWith(layer).ok).toBe(true);
  });

  test('rejects an unknown shape', () => {
    expectInvalid(drawLayer('d', { elements: [element({ shape: 'triangle' as never })] }), 'layers[0].elements[0].shape', 'invalid_value');
  });

  test('requires exactly two points for a fixed shape', () => {
    expectInvalid(
      drawLayer('d', { elements: [element({ shape: 'rectangle', points: [{ x: 0.1, y: 0.1 }] })] }),
      'layers[0].elements[0].points',
      'out_of_bounds'
    );
    expectInvalid(
      drawLayer('d', {
        elements: [element({ shape: 'line', points: [{ x: 0, y: 0 }, { x: 0.5, y: 0.5 }, { x: 1, y: 1 }] })],
      }),
      'layers[0].elements[0].points',
      'out_of_bounds'
    );
  });

  test('requires at least one point for a free stroke', () => {
    expectInvalid(
      drawLayer('d', { elements: [element({ shape: 'free', points: [] })] }),
      'layers[0].elements[0].points',
      'out_of_bounds'
    );
  });

  test('rejects fill on a shape that cannot be filled', () => {
    expectInvalid(
      drawLayer('d', { elements: [element({ shape: 'line', filled: true, points: [{ x: 0.1, y: 0.1 }, { x: 0.8, y: 0.8 }] })] }),
      'layers[0].elements[0].filled',
      'invalid_value'
    );
  });

  test('rejects a stroke scale outside (0, ceiling]', () => {
    expectInvalid(drawLayer('d', { elements: [element({ strokeScale: 0 })] }), 'layers[0].elements[0].strokeScale', 'out_of_bounds');
    expectInvalid(
      drawLayer('d', { elements: [element({ strokeScale: MAX_DRAW_STROKE_SCALE + 0.1 })] }),
      'layers[0].elements[0].strokeScale',
      'out_of_bounds'
    );
  });

  test('rejects a non-unit layer opacity and out-of-range points', () => {
    expectInvalid(drawLayer('d', { opacity: 2 }), 'layers[0].opacity');
    expectInvalid(
      drawLayer('d', { elements: [element({ points: [{ x: 1.5, y: 0.1 }, { x: 0.4, y: 0.4 }] })] }),
      'layers[0].elements[0].points[0].x'
    );
  });

  test('rejects unknown fields on the layer and element', () => {
    expectInvalid({ ...drawLayer('d'), rogue: 1 }, 'layers[0].rogue', 'unknown_field');
    expectInvalid(drawLayer('d', { elements: [{ ...element(), rogue: 1 } as never] }), 'layers[0].elements[0].rogue', 'unknown_field');
  });

  test('enforces the per-element and per-layer point ceilings', () => {
    const tooManyPoints = Array.from({ length: PROJECT_LIMITS.maxPointsPerDrawElement + 1 }, () => ({ x: 0.5, y: 0.5 }));
    expectInvalid(drawLayer('d', { elements: [element({ shape: 'free', points: tooManyPoints })] }), 'layers[0].elements[0].points', 'limit_exceeded');

    const manyElements = Array.from({ length: PROJECT_LIMITS.maxDrawElements + 1 }, () => element());
    expectInvalid(drawLayer('d', { elements: manyElements }), 'layers[0].elements', 'limit_exceeded');
  });
});

describe('draw layer normalization via reduce', () => {
  const project = createDefaultImageProject(imageSource);

  function addDraw(base: MemeEditProject, layer: DrawLayer): DrawLayer {
    const next = reduceMemeEditProject(base, { type: 'add-layer', layer });
    return requireDraw(next.layers[next.layers.length - 1]);
  }

  test('clamps stroke scale and normalized points', () => {
    const normalized = addDraw(project, drawLayer('d', {
      elements: [element({ strokeScale: 99, points: [{ x: -1, y: 2 }, { x: 0.4, y: 0.4 }] })],
    }));
    expect(normalized.elements[0].strokeScale).toBe(MAX_DRAW_STROKE_SCALE);
    expect(normalized.elements[0].points[0]).toEqual({ x: 0, y: 1 });
  });

  test('coerces a fixed shape to first + last points and clears its fill for non-fillable shapes', () => {
    const normalized = addDraw(project, drawLayer('d', {
      elements: [
        element({ shape: 'line', filled: true, points: [{ x: 0, y: 0 }, { x: 0.3, y: 0.3 }, { x: 0.9, y: 0.9 }] }),
      ],
    }));
    expect(normalized.elements[0].points).toEqual([{ x: 0, y: 0 }, { x: 0.9, y: 0.9 }]);
    expect(normalized.elements[0].filled).toBe(false);
  });

  test('forces an image draw layer active range to null', () => {
    const normalized = addDraw(project, drawLayer('d', { active: { startUs: 1, endUs: 2 } }));
    expect(normalized.active).toBeNull();
  });

  test('preserves a video draw layer active range', () => {
    const video = createDefaultVideoProject(videoSource);
    const normalized = addDraw(video, drawLayer('d', { active: { startUs: SECOND_US, endUs: 3 * SECOND_US } }));
    expect(normalized.active).toEqual({ startUs: SECOND_US, endUs: 3 * SECOND_US });
  });
});

describe('draw layer reducer actions', () => {
  const project = reduceMemeEditProject(createDefaultVideoProject(videoSource), {
    type: 'add-layer',
    layer: drawLayer('d1', { active: { startUs: 0, endUs: 5 * SECOND_US } }),
  });

  test('set-layer-active-range updates a draw layer without touching keyframes', () => {
    const next = reduceMemeEditProject(project, {
      type: 'set-layer-active-range',
      id: 'd1',
      active: { startUs: SECOND_US, endUs: 4 * SECOND_US },
    });
    const layer = requireDraw(next.layers[0]);
    expect(layer.active).toEqual({ startUs: SECOND_US, endUs: 4 * SECOND_US });
    expect(layer).not.toHaveProperty('keyframes');
  });

  test('set-layer-keyframes is a no-op for a draw layer', () => {
    const next = reduceMemeEditProject(project, {
      type: 'set-layer-keyframes',
      id: 'd1',
      keyframes: [{ timeUs: 0, center: { x: 0.5, y: 0.5 }, scale: 1, rotationDegrees: 0, opacity: 1, easing: 'linear' }],
    });
    expect(next).toBe(project);
  });

  test('duplicate-layer deep-copies element points', () => {
    const next = reduceMemeEditProject(project, { type: 'duplicate-layer', id: 'd1', newId: 'd2' });
    const original = requireDraw(next.layers.find((l) => l.id === 'd1'));
    const copy = requireDraw(next.layers.find((l) => l.id === 'd2'));
    expect(copy.elements).toEqual(original.elements);
    expect(copy.elements[0].points).not.toBe(original.elements[0].points);
  });
});
