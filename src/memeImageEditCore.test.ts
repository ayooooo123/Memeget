import {
  MIN_NORMALIZED_CROP_AREA,
  MAX_TEXT_REGION_CANDIDATES,
  applyCropPreset,
  BorderSampleRequestGate,
  createTextRegionLayers,
  contrastRatio,
  canApplyTextRegionAction,
  flattenDetectedTextRegions,
  defaultManualTextRegion,
  moveCropHandle,
  moveNormalizedRegion,
  nextQuarterRotation,
  normalizeFreeCrop,
  remapImageProject,
  remapNormalizedPoint,
  remapNormalizedRect,
  replacementTextColorsForCover,
  resizeNormalizedRegion,
  sourceFrameForVisibleCrop,
  visibleImageDimensions,
  textRegionFingerprint,
  type DetectedTextResult,
  splitGlyphAndBackground,
  inferOriginalTextStyle,
  looksAllCaps,
} from './memeImageEditCore';
import { commitGestureTransaction } from './memeEditCanvasCore';
import {
  PROJECT_LIMITS,
  applyProjectAction,
  createDefaultImageProject,
  createProjectHistory,
  undoProjectHistory,
  type BaseTransform,
  type CoverLayer,
  type MaskTrackSpec,
  type MediaOverlayLayer,
  type SubjectLayer,
  type TextLayer,
  type TransformKeyframe,
} from './memeEditProjectCore';

const fullBase: BaseTransform = {
  rotation: 0,
  flipX: false,
  flipY: false,
  crop: { x: 0, y: 0, width: 1, height: 1 },
  outputAspect: 'source',
};

function frame(x: number, y: number, rotationDegrees = 0): TransformKeyframe {
  return {
    timeUs: 0,
    center: { x, y },
    scale: 1,
    rotationDegrees,
    opacity: 1,
    easing: 'linear',
  };
}

const textStyle = {
  preset: 'plain' as const,
  color: '#ffffff',
  outlineColor: '#000000',
  outlineScale: 0,
  backgroundColor: null,
  opacity: 1,
  align: 'center' as const,
  uppercase: false,
};

describe('image crop presets', () => {
  test.each([
    ['source', { x: 0, y: 0, width: 1, height: 1 }],
    ['1:1', { x: 1 / 6, y: 0, width: 2 / 3, height: 1 }],
    ['4:5', { x: 7 / 30, y: 0, width: 8 / 15, height: 1 }],
    ['9:16', { x: 5 / 16, y: 0, width: 3 / 8, height: 1 }],
    ['16:9', { x: 0, y: 5 / 64, width: 1, height: 27 / 32 }],
  ] as const)('fits the %s aspect inside the oriented source', (preset, expected) => {
    const base = applyCropPreset(fullBase, preset, { width: 1200, height: 800 });

    expect(base.outputAspect).toBe(preset);
    expect(base.crop.x).toBeCloseTo(expected.x, 10);
    expect(base.crop.y).toBeCloseTo(expected.y, 10);
    expect(base.crop.width).toBeCloseTo(expected.width, 10);
    expect(base.crop.height).toBeCloseTo(expected.height, 10);
  });

  test('uses post-rotation dimensions and preserves a normalized free crop', () => {
    const rotated = applyCropPreset({ ...fullBase, rotation: 90 }, '16:9', {
      width: 1200,
      height: 800,
    });
    const free = applyCropPreset(
      { ...rotated, crop: { x: 0.12, y: 0.2, width: 0.6, height: 0.5 } },
      'free',
      { width: 1200, height: 800 }
    );

    expect(rotated.crop).toEqual({ x: 0, y: 0.3125, width: 1, height: 0.375 });
    expect(free.outputAspect).toBe('free');
    expect(free.crop).toEqual({ x: 0.12, y: 0.2, width: 0.6, height: 0.5 });
  });

  test('normalizes non-finite free crops to a finite in-bounds minimum area', () => {
    const crop = normalizeFreeCrop(
      { x: Number.NaN, y: Number.POSITIVE_INFINITY, width: -4, height: 0 },
      { x: 0.2, y: 0.3, width: 0.4, height: 0.35 }
    );

    expect(Object.values(crop).every(Number.isFinite)).toBe(true);
    expect(crop.x).toBeGreaterThanOrEqual(0);
    expect(crop.y).toBeGreaterThanOrEqual(0);
    expect(crop.x + crop.width).toBeLessThanOrEqual(1);
    expect(crop.y + crop.height).toBeLessThanOrEqual(1);
    expect(crop.width * crop.height).toBeGreaterThanOrEqual(MIN_NORMALIZED_CROP_AREA);
  });

  test('moves free-crop handles without crossing the finite minimum edge', () => {
    const crop = { x: 0.2, y: 0.2, width: 0.5, height: 0.5 };

    expect(moveCropHandle(crop, 'top-left', { x: 0.1, y: 0.15 })).toEqual({
      x: 0.3,
      y: 0.35,
      width: 0.4,
      height: 0.35,
    });
    const minimum = moveCropHandle(crop, 'bottom-right', { x: -1, y: -1 });
    expect(minimum.width).toBeGreaterThanOrEqual(0.05);
    expect(minimum.height).toBeGreaterThanOrEqual(0.05);
    expect(minimum.width * minimum.height).toBeGreaterThanOrEqual(MIN_NORMALIZED_CROP_AREA);
  });

  test('computes post-rotation/post-crop visible dimensions and source frame', () => {
    const base: BaseTransform = {
      rotation: 90,
      flipX: true,
      flipY: false,
      crop: { x: 0.25, y: 0.1, width: 0.5, height: 0.8 },
      outputAspect: 'free',
    };

    expect(visibleImageDimensions({ width: 1200, height: 800 }, base)).toEqual({
      width: 400,
      height: 960,
    });
    expect(sourceFrameForVisibleCrop({ x: 10, y: 20, width: 200, height: 480 }, base)).toEqual({
      x: -90,
      y: -40,
      width: 400,
      height: 600,
    });
  });

  test('cycles only through quarter rotations', () => {
    expect(([0, 90, 180, 270] as const).map(nextQuarterRotation)).toEqual([90, 180, 270, 0]);
  });
});

describe('old transform to new transform remapping', () => {
  const oldBase: BaseTransform = {
    rotation: 90,
    flipX: true,
    flipY: false,
    crop: { x: 0.1, y: 0.15, width: 0.8, height: 0.7 },
    outputAspect: 'free',
  };
  const newBase: BaseTransform = {
    rotation: 270,
    flipX: false,
    flipY: true,
    crop: { x: 0.05, y: 0.1, width: 0.9, height: 0.8 },
    outputAspect: 'free',
  };

  test('round-trips a point within deterministic floating tolerance', () => {
    const mapped = remapNormalizedPoint({ x: 0.42, y: 0.58 }, oldBase, newBase);
    expect(mapped).not.toBeNull();
    const roundTrip = remapNormalizedPoint(mapped!, newBase, oldBase);

    expect(roundTrip).not.toBeNull();
    expect(roundTrip!.x).toBeCloseTo(0.42, 9);
    expect(roundTrip!.y).toBeCloseTo(0.58, 9);
  });

  test('clips a partially visible rectangle and drops a fully outside rectangle', () => {
    const cropped: BaseTransform = {
      ...fullBase,
      crop: { x: 0.25, y: 0.25, width: 0.5, height: 0.5 },
      outputAspect: 'free',
    };

    expect(remapNormalizedRect({ x: 0.1, y: 0.3, width: 0.3, height: 0.2 }, fullBase, cropped)).toEqual({
      x: 0,
      y: 0.1,
      width: 0.3,
      height: 0.4,
    });
    expect(remapNormalizedRect({ x: 0, y: 0, width: 0.2, height: 0.2 }, fullBase, cropped)).toBeNull();
  });
});

describe('persistent image geometry remapping', () => {
  test('remaps text/media/subject keyframes, covers, and mask corrections without changing IDs', () => {
    const project = createDefaultImageProject({
      uri: 'file:///image.jpg',
      name: 'image.jpg',
      width: 1000,
      height: 1000,
    });
    const mask: MaskTrackSpec = {
      id: 'mask-1',
      active: null,
      corrections: [
        { timeUs: 0, rect: { x: 0.3, y: 0.3, width: 0.2, height: 0.2 }, easing: 'linear' },
        { timeUs: 1, rect: { x: 0, y: 0, width: 0.1, height: 0.1 }, easing: 'hold' },
      ],
    };
    const text: TextLayer = {
      id: 'text-1', kind: 'text', text: 'HELLO', width: 0.4, fontSize: 0.1,
      style: textStyle, active: null, keyframes: [frame(0.4, 0.4, 10)],
    };
    const cover: CoverLayer = {
      id: 'cover-1', kind: 'cover', rect: { x: 0.2, y: 0.2, width: 0.3, height: 0.3 },
      mode: 'solid', color: '#123456', pixelSize: 8, active: null,
      corrections: [{ timeUs: 0, rect: { x: 0.25, y: 0.25, width: 0.2, height: 0.2 }, mode: 'solid', easing: 'linear' }],
    };
    const subject: SubjectLayer = {
      id: 'subject-1', kind: 'subject', subjectIndex: 0, maskTrackId: 'mask-1', active: null,
      keyframes: [frame(0.5, 0.5)], outlineColor: null, outlineScale: 0, shadowScale: 0,
    };
    const media: MediaOverlayLayer = {
      id: 'media-1', kind: 'media', assetUri: 'file:///overlay.png', assetKind: 'image', fit: 'contain',
      targetMaskTrackId: 'mask-1', active: null, keyframes: [frame(0.6, 0.6)],
    };
    project.maskTracks = [mask];
    project.layers = [text, cover, subject, media];

    const remapped = remapImageProject(project, {
      ...fullBase,
      crop: { x: 0.2, y: 0.2, width: 0.6, height: 0.6 },
      outputAspect: 'free',
    });

    expect(remapped.base.crop).toEqual({ x: 0.2, y: 0.2, width: 0.6, height: 0.6 });
    expect(remapped.layers.map((layer) => layer.id)).toEqual(['text-1', 'cover-1', 'subject-1', 'media-1']);
    expect(remapped.maskTracks).toHaveLength(1);
    expect(remapped.maskTracks[0].corrections).toHaveLength(1);
    const correctionRect = remapped.maskTracks[0].corrections[0].rect;
    expect(correctionRect.x).toBeCloseTo(1 / 6, 9);
    expect(correctionRect.y).toBeCloseTo(1 / 6, 9);
    expect(correctionRect.width).toBeCloseTo(1 / 3, 9);
    expect(correctionRect.height).toBeCloseTo(1 / 3, 9);
    const remappedText = remapped.layers[0] as TextLayer;
    expect(remappedText.keyframes[0].center.x).toBeCloseTo(1 / 3, 9);
    expect(remappedText.keyframes[0].center.y).toBeCloseTo(1 / 3, 9);
    expect(remappedText.width).toBeCloseTo(2 / 3, 5);
    expect(remappedText.fontSize).toBeCloseTo(1 / 6, 5);
    expect(remapped.layers[1]).toMatchObject({
      kind: 'cover',
      rect: { x: 0, y: 0, width: 0.5, height: 0.5 },
    });
  });

  test('drops fully cropped covers and orphaned masks/dependents deterministically', () => {
    const project = createDefaultImageProject({ uri: 'file:///i.jpg', name: 'i.jpg', width: 10, height: 10 });
    project.maskTracks = [{
      id: 'outside-mask', active: null,

      corrections: [{ timeUs: 0, rect: { x: 0, y: 0, width: 0.1, height: 0.1 }, easing: 'linear' }],
    }];
    project.layers = [
      { id: 'outside-cover', kind: 'cover', rect: { x: 0, y: 0, width: 0.1, height: 0.1 }, mode: 'solid', color: '#000000', pixelSize: 8, active: null, corrections: [] },
      { id: 'orphan-subject', kind: 'subject', subjectIndex: 0, maskTrackId: 'outside-mask', active: null, keyframes: [frame(0.5, 0.5)], outlineColor: null, outlineScale: 0, shadowScale: 0 },
    ];

    const remapped = remapImageProject(project, {
      ...fullBase,
      crop: { x: 0.25, y: 0.25, width: 0.5, height: 0.5 },
      outputAspect: 'free',
    });

    expect(remapped.maskTracks).toEqual([]);
    expect(remapped.layers).toEqual([]);
  });

  test('preserves text pixel dimensions and orientation across a rectangular quarter rotation', () => {
    const project = createDefaultImageProject({
      uri: 'file:///wide.jpg',
      name: 'wide.jpg',
      width: 1200,
      height: 800,
    });
    project.layers = [{
      id: 'rotated-text',
      kind: 'text',
      text: 'WIDE',
      width: 0.4,
      fontSize: 0.1,
      style: textStyle,
      active: null,
      keyframes: [frame(0.4, 0.4, 0)],
    }];

    const remapped = remapImageProject(project, { ...project.base, rotation: 90 });
    const text = remapped.layers[0] as TextLayer;

    expect(text.width).toBeCloseTo(0.6, 6);
    expect(text.fontSize).toBeCloseTo(0.1 * 800 / 1200, 6);
    expect(text.keyframes[0].rotationDegrees).toBeCloseTo(90, 9);
  });
  test.each([
    ['horizontal', { flipX: true, flipY: false }],
    ['vertical', { flipX: false, flipY: true }],
  ] as const)('keeps transformable orientation readable through a %s base reflection', (_label, flips) => {
    const project = createDefaultImageProject({ uri: 'file:///i.jpg', name: 'i.jpg', width: 100, height: 100 });
    project.layers = [{
      id: 'readable',
      kind: 'text',
      text: 'READ',
      width: 0.4,
      fontSize: 0.1,
      style: textStyle,
      active: null,
      keyframes: [frame(0.4, 0.4, 37)],
    }];

    const remapped = remapImageProject(project, { ...project.base, ...flips });

    expect((remapped.layers[0] as TextLayer).keyframes[0].rotationDegrees).toBe(37);
  });

  test('drops a transformable layer whose keyframes are fully outside the new crop', () => {
    const project = createDefaultImageProject({ uri: 'file:///i.jpg', name: 'i.jpg', width: 100, height: 100 });
    project.layers = [{
      id: 'outside-text',
      kind: 'text',
      text: 'OUT',
      width: 0.2,
      fontSize: 0.08,
      style: textStyle,
      active: null,
      keyframes: [frame(0.1, 0.5)],
    }];

    const remapped = remapImageProject(project, {
      ...project.base,
      crop: { x: 0.5, y: 0, width: 0.5, height: 1 },
      outputAspect: 'free',
    });

    expect(remapped.layers).toEqual([]);
  });

  test('retains a cover when a correction survives even if its fallback rect is cropped out', () => {
    const project = createDefaultImageProject({ uri: 'file:///i.jpg', name: 'i.jpg', width: 100, height: 100 });
    project.layers = [{
      id: 'corrected-cover',
      kind: 'cover',
      rect: { x: 0.05, y: 0.2, width: 0.1, height: 0.2 },
      mode: 'solid',
      color: '#000000',
      pixelSize: 8,
      active: null,
      corrections: [{
        timeUs: 0,
        rect: { x: 0.6, y: 0.2, width: 0.2, height: 0.2 },
        mode: 'solid',
        easing: 'linear',
      }],
    }];

    const remapped = remapImageProject(project, {
      ...project.base,
      crop: { x: 0.5, y: 0, width: 0.5, height: 1 },
      outputAspect: 'free',
    });

    expect(remapped.layers).toHaveLength(1);
    expect(remapped.layers[0]).toMatchObject({
      id: 'corrected-cover',
      rect: { x: 0.2, y: 0.2, width: 0.4, height: 0.2 },
    });
  });

  test('net-zero image geometry commits add no history', () => {
    const project = createDefaultImageProject({ uri: 'file:///i.jpg', name: 'i.jpg', width: 100, height: 100 });
    const remapped = remapImageProject(project, project.base);
    const history = commitGestureTransaction(createProjectHistory(project), [{
      type: 'set-image-geometry',
      base: remapped.base,
      layers: remapped.layers,
      maskTracks: remapped.maskTracks,
    }]);

    expect(history.past).toEqual([]);
    expect(history.present).toBe(project);
  });


  test('committing a crop gesture creates one history entry and undo restores the exact project', () => {
    const project = createDefaultImageProject({ uri: 'file:///i.jpg', name: 'i.jpg', width: 100, height: 80 });
    const history = createProjectHistory(project);
    const remapped = remapImageProject(project, {
      ...project.base,
      crop: { x: 0.1, y: 0.1, width: 0.8, height: 0.8 },
      outputAspect: 'free',
    });
    const committed = applyProjectAction(history, {
      type: 'set-image-geometry',
      base: remapped.base,
      layers: remapped.layers,
      maskTracks: remapped.maskTracks,
    });

    expect(committed.past).toHaveLength(1);
    expect(undoProjectHistory(committed).present).toEqual(project);
  });
});

describe('real OCR region normalization and replacement layers', () => {
  const detection: DetectedTextResult = {
    sourceWidth: 100,
    sourceHeight: 50,
    rotation: 0,
    languages: ['en'],
    blocks: [{
      text: 'HELLO WORLD',
      box: { x: 0.1, y: 0.1, width: 0.8, height: 0.4 },
      cornerPoints: [{ x: 0.1, y: 0.1 }, { x: 0.9, y: 0.1 }, { x: 0.9, y: 0.5 }, { x: 0.1, y: 0.5 }],
      languages: ['en'],
      lines: [{
        text: 'HELLO WORLD',
        box: { x: 0.1, y: 0.1, width: 0.8, height: 0.4 },
        cornerPoints: [{ x: 0.1, y: 0.1 }, { x: 0.9, y: 0.1 }, { x: 0.9, y: 0.5 }, { x: 0.1, y: 0.5 }],
        languages: ['en'],
        elements: [
          { text: 'HELLO', box: { x: 0.1, y: 0.1, width: 0.3, height: 0.4 }, cornerPoints: [], languages: ['en'] },
          { text: 'OUTSIDE', box: { x: 1.1, y: 0.1, width: 0.2, height: 0.2 }, cornerPoints: [], languages: [] },
        ],
      }],
    }],
  };

  test('flattens genuine nested OCR boxes while clipping/dropping invalid boxes', () => {
    expect(flattenDetectedTextRegions(detection)).toEqual([
      { id: 'ocr-0-0-0', text: 'HELLO', rect: { x: 0.1, y: 0.1, width: 0.3, height: 0.4 }, source: 'element' },
    ]);
  });

  test('bounds selectable OCR overlays even when native recognition is unusually dense', () => {
    const dense: DetectedTextResult = {
      sourceWidth: 100,
      sourceHeight: 100,
      rotation: 0,
      languages: [],
      blocks: Array.from({ length: MAX_TEXT_REGION_CANDIDATES + 20 }, (_, index) => ({
        text: `box-${index}`,
        box: { x: 0.1, y: 0.1, width: 0.1, height: 0.1 },
        cornerPoints: [],
        languages: [],
        lines: [],
      })),
    };

    expect(flattenDetectedTextRegions(dense)).toHaveLength(MAX_TEXT_REGION_CANDIDATES);
  });

  test('moves and resizes a selected OCR/manual region within visible bounds', () => {
    const region = { x: 0.2, y: 0.25, width: 0.3, height: 0.2 };

    expect(moveNormalizedRegion(region, { x: 0.8, y: -0.5 })).toEqual({
      x: 0.7,
      y: 0,
      width: 0.3,
      height: 0.2,
    });
    expect(resizeNormalizedRegion(region, { x: -1, y: 0.9 })).toEqual({
      x: 0.2,
      y: 0.25,
      width: 0.05,
      height: 0.75,
    });
  });

  test.each(['#FFFFFF', '#101820', '#FF4E42', '#B8FF2C'])(
    'chooses readable replacement text against %s',
    (coverColor) => {
      const textColors = replacementTextColorsForCover(coverColor);

      expect(contrastRatio(textColors.color, coverColor)).toBeGreaterThanOrEqual(4.5);
      expect(textColors.outlineColor).not.toBe(textColors.color);
    }
  );


  test('creates a centered bounded manual rectangle for accessibility activate', () => {
    expect(defaultManualTextRegion()).toEqual({
      id: 'manual-current',
      text: '',
      source: 'manual',
      rect: { x: 0.25, y: 0.35, width: 0.5, height: 0.3 },
    });
  });
  test('rejects stale A sampling after region B starts and gates fill actions by current region', () => {
    const gate = new BorderSampleRequestGate();
    const regionA = textRegionFingerprint({ id: 'A', text: 'A', source: 'manual', rect: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 } });
    const regionB = textRegionFingerprint({ id: 'B', text: 'B', source: 'manual', rect: { x: 0.4, y: 0.4, width: 0.2, height: 0.2 } });
    const requestA = gate.begin(regionA);
    const requestB = gate.begin(regionB);

    expect(gate.accepts(requestA, regionA)).toBe(false);
    expect(gate.accepts(requestB, regionB)).toBe(true);
    expect(canApplyTextRegionAction('cover', regionB, null, null)).toBe(false);
    expect(canApplyTextRegionAction('pixelate', regionB, null, null)).toBe(true);
    expect(canApplyTextRegionAction('replace', regionB, regionB, null)).toBe(true);
    expect(canApplyTextRegionAction('cover', regionB, null, regionA)).toBe(false);
    expect(canApplyTextRegionAction('cover', regionB, null, regionB)).toBe(true);
  });

  test.each(['cover', 'pixelate', 'replace'] as const)('creates bounded persistent %s layers', (action) => {
    const layers = createTextRegionLayers({
      action,
      rect: { x: -0.1, y: 0.2, width: 0.7, height: 0.3 },
      text: 'HELLO',
      coverId: 'caller-cover',
      textId: 'caller-text',
      color: '#345678',
      pixelSize: 999,
    });

    expect(layers[0]).toMatchObject({
      id: 'caller-cover',
      kind: 'cover',
      rect: { x: 0, y: 0.2, width: 0.6, height: 0.3 },
      mode: action === 'pixelate' ? 'pixelate' : 'solid',
      color: '#345678',
      active: null,
    });
    expect((layers[0] as CoverLayer).pixelSize).toBeLessThanOrEqual(256);
    if (action === 'replace') {
      expect(layers).toHaveLength(2);
      expect(layers[1]).toMatchObject({ id: 'caller-text', kind: 'text', text: 'HELLO', active: null });
      expect((layers[1] as TextLayer).keyframes[0].center).toEqual({ x: 0.3, y: 0.35 });
      expect((layers[1] as TextLayer).style).toMatchObject(
        replacementTextColorsForCover('#345678')
      );
    } else {
      expect(layers).toHaveLength(1);
    }
  });

  test('rejects Replace before mutation when two layer slots are not available', () => {
    const project = createDefaultImageProject({ uri: 'file:///i.jpg', name: 'i.jpg', width: 10, height: 10 });
    project.layers = Array.from({ length: PROJECT_LIMITS.maxLayers - 1 }, (_, index) => ({
      id: `cover-${index}`,
      kind: 'cover' as const,
      rect: { x: 0, y: 0, width: 0.1, height: 0.1 },
      mode: 'solid' as const,
      color: '#000000',
      pixelSize: 8,
      active: null,
      corrections: [],
    }));
    const history = createProjectHistory(project);
    const layers = createTextRegionLayers({ action: 'replace', rect: { x: 0.2, y: 0.2, width: 0.3, height: 0.2 }, text: 'x', coverId: 'c', textId: 't', color: '#000000', pixelSize: 8 });

    expect(() => applyProjectAction(history, { type: 'add-layers', layers })).toThrow(/64 layer/);
    expect(history.present).toBe(project);
  });
});


describe('reading the style of the text being replaced', () => {
  // A meme caption: white glyphs on a dark photo. Glyph pixels are the minority
  // because strokes cover far less area than the space around them.
  const whiteOnDark = [
    ...Array(24).fill('#101014'),
    ...Array(6).fill('#FFFFFF'),
  ];
  // An impact-style caption in the classic other direction.
  const blackOnWhite = [
    ...Array(24).fill('#FAFAFA'),
    ...Array(6).fill('#000000'),
  ];

  test('picks the glyph colour off the image instead of guessing black or white', () => {
    const split = splitGlyphAndBackground([
      ...Array(20).fill('#1B1B22'),
      ...Array(5).fill('#F2C14E'),
    ]);
    expect(split).not.toBeNull();
    // A yellow caption must come back yellow. Choosing by contrast alone would
    // have returned #FFFFFF and quietly restyled the meme.
    expect(split!.glyph).toBe('#F2C14E');
    expect(split!.background).toBe('#1B1B22');
    expect(split!.contrast).toBeGreaterThan(4);
  });

  test('identifies glyphs by area, not by being darker or lighter', () => {
    expect(splitGlyphAndBackground(whiteOnDark)!.glyph).toBe('#FFFFFF');
    expect(splitGlyphAndBackground(blackOnWhite)!.glyph).toBe('#000000');
  });

  test('refuses to read a style from a flat region', () => {
    // No text here. Inventing a colour would put invisible text on the meme.
    expect(splitGlyphAndBackground(Array(30).fill('#334455'))).toBeNull();
    // The case that actually matters: a solid background carrying compression
    // noise. It is not uniform, so the two clusters are both non-empty and the
    // algorithm will happily "find" glyphs in the dither unless the spread is
    // checked. Believing that noise means restyling the meme from nothing.
    const noisy = ['#334455', '#334456', '#344455', '#334454', '#333455', '#344456'];
    expect(splitGlyphAndBackground([...noisy, ...noisy, ...noisy, ...noisy, ...noisy])).toBeNull();
    expect(splitGlyphAndBackground([])).toBeNull();
    expect(splitGlyphAndBackground(['#fff', '#000'])).toBeNull();
  });

  test('sizes text from one line, not from the whole block', () => {
    // Height chosen to stay clear of the font-size clamps at both ends, so the
    // assertion is about the per-line division and not about clamping.
    const one = inferOriginalTextStyle({ sampledColors: whiteOnDark, originalText: 'TOP TEXT', regionHeight: 0.15, lineCount: 1 });
    const three = inferOriginalTextStyle({ sampledColors: whiteOnDark, originalText: 'A\nB\nC', regionHeight: 0.15, lineCount: 3 });
    // The bug this prevents: a three-line block rendering as one giant line.
    expect(three.fontSize).toBeLessThan(one.fontSize);
    expect(three.fontSize).toBeCloseTo(one.fontSize / 3, 5);
  });

  test('carries capitalisation over from the original', () => {
    expect(inferOriginalTextStyle({ sampledColors: whiteOnDark, originalText: 'ONE DOES NOT SIMPLY', regionHeight: 0.2, lineCount: 1 }).uppercase).toBe(true);
    expect(inferOriginalTextStyle({ sampledColors: whiteOnDark, originalText: 'a quiet subtitle', regionHeight: 0.2, lineCount: 1 }).uppercase).toBe(false);
    // Digits and punctuation alone are not evidence of shouting.
    expect(looksAllCaps('123 456')).toBe(false);
    expect(looksAllCaps('A')).toBe(false);
  });

  test('gives shouted high-contrast text an outline and quiet text none', () => {
    const caption = inferOriginalTextStyle({ sampledColors: whiteOnDark, originalText: 'GIGACHAD', regionHeight: 0.2, lineCount: 1 });
    expect(caption.preset).toBe('impact');
    expect(caption.outlineScale).toBeGreaterThan(0.04);

    const quiet = inferOriginalTextStyle({ sampledColors: whiteOnDark, originalText: 'photo credit', regionHeight: 0.05, lineCount: 1 });
    expect(quiet.preset).toBe('plain');
    expect(quiet.outlineScale).toBeLessThan(0.04);
  });

  test('falls back honestly when the original cannot be read', () => {
    const flat = inferOriginalTextStyle({ sampledColors: Array(30).fill('#808080'), originalText: 'WHATEVER', regionHeight: 0.2, lineCount: 1 });
    // No sampled colour is claimed; contrast reports that nothing was learned.
    expect(flat.contrast).toBeLessThanOrEqual(1.6);
    expect(['#000000', '#FFFFFF']).toContain(flat.color);
  });

  test('the outline always contrasts with the glyph it outlines', () => {
    for (const glyph of ['#FFFFFF', '#000000', '#F2C14E', '#1B1B22', '#7A7A7A']) {
      const style = inferOriginalTextStyle({
        sampledColors: [...Array(20).fill('#808080'), ...Array(5).fill(glyph)],
        originalText: 'TEST TEXT',
        regionHeight: 0.2,
        lineCount: 1,
      });
      expect(contrastRatio(style.color, style.outlineColor)).toBeGreaterThan(1.5);
    }
  });
});
