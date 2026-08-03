import { readFileSync } from 'fs';
import { join } from 'path';

import {
  CUTOUT_PARITY_FIXTURE_VERSION,
  CUTOUT_PARITY_TOLERANCE_PX,
  IMAGE_RENDER_PLAN_VERSION,
  buildCutoutParityFixtures,
  MAX_IMAGE_RENDER_PIXELS,
  MAX_MOSAIC_CELLS,
  CUTOUT_OUTLINE_MAX_FRACTION,
  CUTOUT_SHADOW_BLUR_MAX_FRACTION,
  CUTOUT_SHADOW_COLOR,
  CUTOUT_SHADOW_OFFSET_MAX_FRACTION,
  MEME_MEDIA_LAYER_BASE_WIDTH,
  buildImageRenderPlan,
  imageRenderPlanUnavailableLayers,
  mosaicCellFloorPx,
  type ImageRenderPlan,
  type ImageRenderTextLayerPlan,
} from './memeImageRenderCore';
import { visibleImageDimensions } from './memeImageEditCore';
import { buildMemeTextLayoutSpec } from './memeTextLayoutCore';
import {
  createDefaultImageProject,
  createDefaultVideoProject,
  reduceMemeEditProject,
  validateMemeEditProject,
  type CoverLayer,
  type MediaOverlayLayer,
  type MemeEditProject,
  type SubjectLayer,
  type TextLayer,
  type TransformKeyframe,
} from './memeEditProjectCore';

const imageSource = { uri: 'file:///meme.png', name: 'meme.png', width: 1200, height: 800 };

function frame(overrides: Partial<TransformKeyframe> = {}): TransformKeyframe {
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

const textLayer: TextLayer = {
  id: 'text-1',
  kind: 'text',
  text: 'top text',
  width: 0.8,
  fontSize: 0.12,
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
  active: null,
  keyframes: [frame({ center: { x: 0.5, y: 0.2 } })],
};

const coverLayer: CoverLayer = {
  id: 'cover-1',
  kind: 'cover',
  rect: { x: 0.1, y: 0.2, width: 0.5, height: 0.25 },
  mode: 'pixelate',
  color: '#112233',
  pixelSize: 12,
  active: null,
  corrections: [],
};

const mediaLayer: MediaOverlayLayer = {
  id: 'media-1',
  kind: 'media',
  assetUri: 'file:///sticker.png',
  assetKind: 'image',
  fit: 'contain',
  targetMaskTrackId: null,
  active: null,
  keyframes: [frame({ center: { x: 0.25, y: 0.75 }, scale: 2, rotationDegrees: 30, opacity: 0.5 })],
};

const subjectLayer: SubjectLayer = {
  id: 'subject-1',
  kind: 'subject',
  subjectIndex: 0,
  maskTrackId: 'mask-1',
  active: null,
  keyframes: [frame()],
  outlineColor: null,
  outlineScale: 0,
  shadowScale: 0,
};

function projectWithLayers(layers: MemeEditProject['layers']): MemeEditProject {
  let project = createDefaultImageProject(imageSource);
  for (const layer of layers) {
    if (layer.kind !== 'subject') continue;
    project = reduceMemeEditProject(project, {
      type: 'add-mask-track',
      track: {
        id: layer.maskTrackId,
        active: null,
        corrections: [{ timeUs: 0, rect: { x: 0.3, y: 0.3, width: 0.4, height: 0.4 }, easing: 'linear' }],
      },
    });
  }
  return reduceMemeEditProject(project, { type: 'add-layers', layers });
}

describe('buildImageRenderPlan geometry', () => {
  test('takes output pixel size from visibleImageDimensions so rotation and crop apply once', () => {
    const project = reduceMemeEditProject(createDefaultImageProject(imageSource), {
      type: 'set-base-transform',
      base: {
        rotation: 90,
        flipX: true,
        flipY: false,
        crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.4 },
        outputAspect: 'free',
      },
    });

    const plan = buildImageRenderPlan(project, { planId: 'plan-1' });
    const visible = visibleImageDimensions(
      { width: project.source.width, height: project.source.height },
      project.base
    );

    expect(plan.version).toBe(IMAGE_RENDER_PLAN_VERSION);
    expect(plan.id).toBe('plan-1');
    expect(plan.timeUs).toBe(0);
    expect(plan.output.widthPx).toBe(visible.width);
    expect(plan.output.heightPx).toBe(visible.height);
    // 1200x800 rotated 90 -> 800x1200, cropped 0.5 x 0.4.
    expect(plan.output.widthPx).toBe(400);
    expect(plan.output.heightPx).toBe(480);
    expect(plan.output.downscaled).toBe(false);
    expect(plan.output.scale).toBe(1);
    expect(plan.source).toEqual({
      uri: 'file:///meme.png',
      widthPx: 1200,
      heightPx: 800,
      rotation: 90,
      flipX: true,
      flipY: false,
      crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.4 },
    });
  });

  test('prefers the materialized source uri when the session staged one', () => {
    const project = reduceMemeEditProject(createDefaultImageProject(imageSource), {
      type: 'set-materialized-source-uri',
      uri: 'file:///cache/staged.png',
    });

    expect(buildImageRenderPlan(project, { planId: 'plan-1' }).source.uri).toBe('file:///cache/staged.png');
  });

  test('rejects a video project instead of pretending it renders as a still', () => {
    const project = createDefaultVideoProject({ ...imageSource, uri: 'file:///clip.mp4', name: 'clip.mp4', durationUs: 5_000_000 });

    expect(() => buildImageRenderPlan(project, { planId: 'plan-1' })).toThrow(/image source/i);
  });
});

describe('buildImageRenderPlan layers', () => {
  test('resolves cover layers to output-pixel rects carrying mode, color and pixel size', () => {
    const plan = buildImageRenderPlan(projectWithLayers([coverLayer]), { planId: 'plan-1' });

    expect(plan.layers).toEqual([
      {
        kind: 'cover',
        id: 'cover-1',
        rect: { x: 120, y: 160, width: 600, height: 200 },
        mode: 'pixelate',
        color: '#112233',
        pixelSizePx: 12,
      },
    ]);
  });

  test('clamps the mosaic cell so a full-canvas pixelate cover cannot ask for millions of cells', () => {
    const square = createDefaultImageProject({
      uri: 'file:///square.png',
      name: 'square.png',
      width: 4000,
      height: 4000,
    });
    const fullCover: CoverLayer = {
      ...coverLayer,
      rect: { x: 0, y: 0, width: 1, height: 1 },
      pixelSize: 1,
    };
    const plan = buildImageRenderPlan(
      reduceMemeEditProject(square, { type: 'add-layers', layers: [fullCover] }),
      { planId: 'plan-1' }
    );

    const cover = plan.layers[0];
    if (cover.kind !== 'cover') throw new Error('expected cover layer');
    // 16 MP of one-pixel cells is 16M averaged cells for a visually identical
    // image; the plan has to state the coarser cell the renderer will use.
    expect(cover.pixelSizePx).toBe(mosaicCellFloorPx(cover.rect));
    expect(cover.pixelSizePx).toBe(16);
    expect(
      (cover.rect.width * cover.rect.height) / (cover.pixelSizePx * cover.pixelSizePx)
    ).toBeLessThanOrEqual(MAX_MOSAIC_CELLS);
  });

  test('leaves a requested cell alone when the region is small enough to afford it', () => {
    const plan = buildImageRenderPlan(projectWithLayers([{ ...coverLayer, pixelSize: 3 }]), {
      planId: 'plan-1',
    });

    const cover = plan.layers[0];
    if (cover.kind !== 'cover') throw new Error('expected cover layer');
    expect(mosaicCellFloorPx(cover.rect)).toBe(2);
    expect(cover.pixelSizePx).toBe(3);
  });

  test('resolves text layers through buildMemeTextLayoutSpec at output pixel size', () => {
    const project = projectWithLayers([textLayer]);
    const plan = buildImageRenderPlan(project, { planId: 'plan-1' });
    const stored = project.layers[0] as TextLayer;

    const expected = buildMemeTextLayoutSpec(stored, stored.keyframes[0], {
      canvasWidthDip: 1200,
      canvasHeightDip: 800,
    });

    expect(plan.layers).toHaveLength(1);
    const resolved = plan.layers[0] as ImageRenderTextLayerPlan;
    expect(resolved.kind).toBe('text');
    expect(resolved.spec).toEqual(expected);
    // The full-res spec must keep the Android StaticLayout contract intact.
    expect(resolved.spec.font.family).toBe('Anton');
    expect(resolved.spec.font.includeFontPadding).toBe(false);
    expect(resolved.spec.layout.lineHeightDip).toBeGreaterThan(0);
    expect(resolved.spec.outline.widthDip).toBeGreaterThan(0);
    expect(resolved.spec.canvas.widthDip).toBe(1200);
    expect(resolved.spec.canvas.heightDip).toBe(800);
  });

  test('resolves media overlays to the same square base box the canvas previews', () => {
    const plan = buildImageRenderPlan(projectWithLayers([mediaLayer]), { planId: 'plan-1' });
    // 1200px canvas * 0.28 base box * keyframe scale 2.
    const side = 672;
    expect(1200 * MEME_MEDIA_LAYER_BASE_WIDTH * 2).toBeCloseTo(side, 6);

    expect(plan.layers).toEqual([
      {
        kind: 'media',
        id: 'media-1',
        assetUri: 'file:///sticker.png',
        assetKind: 'image',
        fit: 'contain',
        rect: { x: 300 - side / 2, y: 600 - side / 2, width: side, height: side },
        rotationDegrees: 30,
        opacity: 0.5,
      },
    ]);
  });

  test('marks a subject layer unavailable instead of faking a cutout', () => {
    const missing = buildImageRenderPlan(projectWithLayers([subjectLayer]), { planId: 'plan-1' });

    expect(missing.layers).toEqual([
      { kind: 'unavailable', id: 'subject-1', layerKind: 'subject', reason: 'subject-mask-missing' },
    ]);
    expect(imageRenderPlanUnavailableLayers(missing)).toEqual(missing.layers);
  });

  test('composites a subject layer once its mask track has a materialized cutout', () => {
    const withUri = reduceMemeEditProject(projectWithLayers([subjectLayer]), {
      type: 'set-mask-track-uri',
      trackId: 'mask-1',
      uri: 'file:///cache/mask-1.png',
    });
    const plan = buildImageRenderPlan(withUri, { planId: 'plan-1' });

    // Track rect 0.4x0.4 of a 1200x800 canvas, centred by the keyframe at 0.5.
    expect(plan.layers).toEqual([
      {
        kind: 'subject',
        id: 'subject-1',
        cutoutUri: 'file:///cache/mask-1.png',
        rect: { x: 360, y: 240, width: 480, height: 320 },
        rotationDegrees: 0,
        opacity: 1,
        outlineColor: null,
        outlinePx: 0,
        shadowColor: CUTOUT_SHADOW_COLOR,
        shadowBlurPx: 0,
        shadowOffsetPx: 0,
      },
    ]);
    // A composited cutout is not a gap, so it must not reach the export warnings.
    expect(imageRenderPlanUnavailableLayers(plan)).toEqual([]);
  });

  test('moves, scales and rotates a cutout with its keyframe', () => {
    const moved: SubjectLayer = {
      ...subjectLayer,
      keyframes: [frame({ center: { x: 0.25, y: 0.6 }, scale: 0.5, rotationDegrees: 12, opacity: 0.75 })],
    };
    const withUri = reduceMemeEditProject(projectWithLayers([moved]), {
      type: 'set-mask-track-uri',
      trackId: 'mask-1',
      uri: 'file:///cache/mask-1.png',
    });
    const [layer] = buildImageRenderPlan(withUri, { planId: 'plan-1' }).layers;
    if (layer.kind !== 'subject') throw new Error('expected a subject plan');

    expect(layer.rect).toEqual({ x: 300 - 120, y: 480 - 80, width: 240, height: 160 });
    expect(layer.rotationDegrees).toBe(12);
    expect(layer.opacity).toBe(0.75);
  });

  test('resolves sticker sizes from the shared fractions against the cutout, not the canvas', () => {
    const sticker: SubjectLayer = {
      ...subjectLayer,
      outlineColor: '#B8FF2C',
      outlineScale: 0.5,
      shadowScale: 0.25,
    };
    const withUri = reduceMemeEditProject(projectWithLayers([sticker]), {
      type: 'set-mask-track-uri',
      trackId: 'mask-1',
      uri: 'file:///cache/mask-1.png',
    });
    const [layer] = buildImageRenderPlan(withUri, { planId: 'plan-1' }).layers;
    if (layer.kind !== 'subject') throw new Error('expected a subject plan');

    // Short edge of the drawn cutout is 320 px (0.4 * 800).
    expect(layer.outlinePx).toBeCloseTo(0.5 * CUTOUT_OUTLINE_MAX_FRACTION * 320, 6);
    expect(layer.shadowBlurPx).toBeCloseTo(0.25 * CUTOUT_SHADOW_BLUR_MAX_FRACTION * 320, 6);
    expect(layer.shadowOffsetPx).toBeCloseTo(0.25 * CUTOUT_SHADOW_OFFSET_MAX_FRACTION * 320, 6);
    expect(layer.outlineColor).toBe('#B8FF2C');
  });

  test('drops the outline when the layer has no outline colour', () => {
    const noColour: SubjectLayer = { ...subjectLayer, outlineColor: null, outlineScale: 1 };
    const withUri = reduceMemeEditProject(projectWithLayers([noColour]), {
      type: 'set-mask-track-uri',
      trackId: 'mask-1',
      uri: 'file:///cache/mask-1.png',
    });
    const [layer] = buildImageRenderPlan(withUri, { planId: 'plan-1' }).layers;
    if (layer.kind !== 'subject') throw new Error('expected a subject plan');
    expect(layer.outlinePx).toBe(0);
  });

  test('places a cutout identically at preview scale and at full resolution', () => {
    const sticker: SubjectLayer = {
      ...subjectLayer,
      outlineColor: '#FFFFFF',
      outlineScale: 0.4,
      shadowScale: 0.6,
      keyframes: [frame({ center: { x: 0.3, y: 0.7 }, scale: 1.5 })],
    };
    const project = reduceMemeEditProject(projectWithLayers([sticker]), {
      type: 'set-mask-track-uri',
      trackId: 'mask-1',
      uri: 'file:///cache/mask-1.png',
    });
    const full = buildImageRenderPlan(project, { planId: 'full' });
    // A quarter of the pixels on each axis: the preview surface, in effect.
    const preview = buildImageRenderPlan(project, {
      planId: 'preview',
      maxOutputPixels: Math.floor((1200 * 800) / 16),
    });
    const [fullLayer] = full.layers;
    const [previewLayer] = preview.layers;
    if (fullLayer.kind !== 'subject' || previewLayer.kind !== 'subject') {
      throw new Error('expected subject plans');
    }
    expect(preview.output.widthPx).toBeLessThan(full.output.widthPx);

    // Everything that positions or sizes the sticker has to be the same
    // FRACTION of the canvas at both scales — this is the bug where a mask
    // composites in the preview and lands somewhere else in the export.
    const asFractions = (
      layer: typeof fullLayer,
      output: ImageRenderPlan['output']
    ): number[] => [
      layer.rect.x / output.widthPx,
      layer.rect.y / output.heightPx,
      layer.rect.width / output.widthPx,
      layer.rect.height / output.heightPx,
      layer.outlinePx / output.heightPx,
      layer.shadowBlurPx / output.heightPx,
      layer.shadowOffsetPx / output.heightPx,
    ];
    const fullFractions = asFractions(fullLayer, full.output);
    const previewFractions = asFractions(previewLayer, preview.output);
    fullFractions.forEach((value, index) => {
      expect(previewFractions[index]).toBeCloseTo(value, 4);
    });
    expect(fullLayer.rotationDegrees).toBe(previewLayer.rotationDegrees);
    expect(fullLayer.opacity).toBe(previewLayer.opacity);
  });

  test('marks a video overlay unavailable rather than sampling an arbitrary frame', () => {
    const videoOverlay: MediaOverlayLayer = {
      ...mediaLayer,
      id: 'media-video',
      assetUri: 'file:///overlay.mp4',
      assetKind: 'video',
    };

    expect(buildImageRenderPlan(projectWithLayers([videoOverlay]), { planId: 'plan-1' }).layers).toEqual([
      { kind: 'unavailable', id: 'media-video', layerKind: 'media', reason: 'video-overlay-unsupported' },
    ]);
  });

  test('keeps project layer order and drops layers inactive at timeUs 0', () => {
    // Image projects normalize `active` to null, so an active range only
    // reaches the planner from a hand-built project; it must still be honored.
    const base = projectWithLayers([coverLayer, textLayer, mediaLayer]);
    const inactive: CoverLayer = { ...coverLayer, id: 'cover-late', active: { startUs: 1_000, endUs: 2_000 } };
    const project: MemeEditProject = {
      ...base,
      layers: [base.layers[0], base.layers[1], inactive, base.layers[2]],
    };

    const plan = buildImageRenderPlan(project, { planId: 'plan-1' });

    expect(plan.layers.map((layer) => layer.id)).toEqual(['cover-1', 'text-1', 'media-1']);
  });

  test('carries the background spec verbatim', () => {
    const project = reduceMemeEditProject(createDefaultImageProject(imageSource), {
      type: 'set-background',
      background: { mode: 'transparent', color: '#00000000', assetUri: null, blurScale: 0 },
    });

    expect(buildImageRenderPlan(project, { planId: 'plan-1' }).background).toEqual({
      mode: 'transparent',
      color: '#00000000',
      assetUri: null,
      blurScale: 0,
    });
  });
});

describe('buildImageRenderPlan memory guard', () => {
  test('emits a reduced, aspect-preserving size with an explicit scale above the pixel cap', () => {
    const huge = createDefaultImageProject({
      uri: 'file:///huge.png',
      name: 'huge.png',
      width: 12_000,
      height: 8_000,
    });

    const plan = buildImageRenderPlan(huge, { planId: 'plan-1' });

    expect(plan.output.fullWidthPx).toBe(12_000);
    expect(plan.output.fullHeightPx).toBe(8_000);
    expect(plan.output.downscaled).toBe(true);
    expect(plan.output.scale).toBeGreaterThan(0);
    expect(plan.output.scale).toBeLessThan(1);
    expect(plan.output.widthPx * plan.output.heightPx).toBeLessThanOrEqual(MAX_IMAGE_RENDER_PIXELS);
    expect(plan.output.widthPx / plan.output.heightPx).toBeCloseTo(1.5, 2);
    expect(plan.output.maxPixels).toBe(MAX_IMAGE_RENDER_PIXELS);
  });

  test('honours a caller-supplied cap and scales layer geometry with the output', () => {
    const project = projectWithLayers([coverLayer]);
    const plan = buildImageRenderPlan(project, { planId: 'plan-1', maxOutputPixels: 240_000 });

    expect(plan.output.downscaled).toBe(true);
    expect(plan.output.widthPx * plan.output.heightPx).toBeLessThanOrEqual(240_000);
    const cover = plan.layers[0];
    expect(cover.kind).toBe('cover');
    if (cover.kind !== 'cover') throw new Error('expected cover layer');
    expect(cover.rect.width).toBeCloseTo(plan.output.widthPx * 0.5, 6);
    expect(cover.rect.x).toBeCloseTo(plan.output.widthPx * 0.1, 6);
    expect(cover.pixelSizePx).toBe(Math.max(1, Math.round(12 * plan.output.scale)));
  });

  test('never emits a zero-pixel output', () => {
    const tiny = createDefaultImageProject({ uri: 'file:///tiny.png', name: 'tiny.png', width: 3, height: 1 });
    const plan = buildImageRenderPlan(tiny, { planId: 'plan-1', maxOutputPixels: 1 });

    expect(plan.output.widthPx).toBeGreaterThanOrEqual(1);
    expect(plan.output.heightPx).toBeGreaterThanOrEqual(1);
  });
});

describe('native cutout parity fixtures', () => {
  const fixtures = buildCutoutParityFixtures();

  test('the committed fixture JSON is exactly what TypeScript produces, on both sides of the bridge', () => {
    const shared = JSON.parse(
      readFileSync(join(__dirname, 'memeCutoutParityFixtures.json'), 'utf8')
    );
    const asset = JSON.parse(
      readFileSync(
        join(
          __dirname,
          '..',
          'modules',
          'memeget-bg',
          'android',
          'src',
          'main',
          'assets',
          'cutout_parity_fixtures.json'
        ),
        'utf8'
      )
    );

    expect(shared).toEqual(fixtures);
    expect(asset).toEqual(fixtures);
  });

  test('every placement is the same fraction of its canvas, whatever the canvas is', () => {
    expect(fixtures.version).toBe(CUTOUT_PARITY_FIXTURE_VERSION);
    expect(fixtures.tolerancePx).toBe(CUTOUT_PARITY_TOLERANCE_PX);
    expect(fixtures.canvases.length).toBeGreaterThanOrEqual(2);
    expect(fixtures.cases.length).toBeGreaterThanOrEqual(5);

    for (const parityCase of fixtures.cases) {
      expect(parityCase.placements).toHaveLength(fixtures.canvases.length);
      for (const placement of parityCase.placements) {
        const canvas = fixtures.canvases[placement.canvas];
        expect(placement.rect.x / canvas.widthPx).toBeCloseTo(parityCase.normalizedRect.x, 6);
        expect(placement.rect.y / canvas.heightPx).toBeCloseTo(parityCase.normalizedRect.y, 6);
        expect(placement.rect.width / canvas.widthPx).toBeCloseTo(parityCase.normalizedRect.width, 6);
        expect(placement.rect.height / canvas.heightPx).toBeCloseTo(
          parityCase.normalizedRect.height,
          6
        );
        expect(placement.rotationDegrees).toBe(parityCase.rotationDegrees);
        expect(placement.opacity).toBe(parityCase.opacity);
      }
    }
  });

  test('effect sizes scale with the canvas rather than staying put', () => {
    const sticker = fixtures.cases.find((entry) => entry.id === 'outline-and-shadow');
    if (!sticker) throw new Error('expected the outline-and-shadow case');
    const [big, small] = sticker.placements;
    const ratio = fixtures.canvases[0].heightPx / fixtures.canvases[1].heightPx;

    expect(big.outlinePx).toBeGreaterThan(0);
    expect(big.shadowBlurPx).toBeGreaterThan(0);
    expect(big.shadowOffsetPx).toBeGreaterThan(0);
    // A fixed pixel outline is the bug: at a sixteenth of the pixels it would
    // swallow the sticker in the preview, or vanish in the export.
    expect(big.outlinePx / small.outlinePx).toBeCloseTo(ratio, 6);
    expect(big.shadowBlurPx / small.shadowBlurPx).toBeCloseTo(ratio, 6);
    expect(big.shadowOffsetPx / small.shadowOffsetPx).toBeCloseTo(ratio, 6);
  });

  test('the cases the device compares against pixels really are comparable', () => {
    const verifiable = fixtures.cases.filter((entry) => entry.pixelVerifiable);
    expect(verifiable.length).toBeGreaterThanOrEqual(3);

    for (const parityCase of verifiable) {
      // Rotation or an effect would spill the drawn pixels past the rect, and
      // the device measures the opaque bounding box against exactly that rect.
      expect(parityCase.rotationDegrees).toBe(0);
      expect(parityCase.outlineScale).toBe(0);
      expect(parityCase.shadowScale).toBe(0);
      for (const placement of parityCase.placements) {
        const canvas = fixtures.canvases[placement.canvas];
        expect(placement.rect.x).toBeGreaterThanOrEqual(0);
        expect(placement.rect.y).toBeGreaterThanOrEqual(0);
        expect(placement.rect.x + placement.rect.width).toBeLessThanOrEqual(canvas.widthPx);
        expect(placement.rect.y + placement.rect.height).toBeLessThanOrEqual(canvas.heightPx);
      }
      // Both canvases share an aspect ratio, so one generated cutout fits both.
      const aspects = parityCase.placements.map(
        (placement) => placement.rect.width / placement.rect.height
      );
      for (const aspect of aspects) expect(aspect).toBeCloseTo(aspects[0], 6);
    }
  });
});

describe('buildImageRenderPlan determinism', () => {
  test('produces an identical, JSON-serializable plan for the same project and id', () => {
    const project = projectWithLayers([coverLayer, textLayer, mediaLayer, subjectLayer]);

    const first = buildImageRenderPlan(project, { planId: 'plan-1' });
    const second = buildImageRenderPlan(project, { planId: 'plan-1' });

    expect(JSON.stringify(second)).toBe(JSON.stringify(first));
    expect(JSON.parse(JSON.stringify(first)) as ImageRenderPlan).toEqual(first);
  });

  test('reads no ambient clock or randomness', () => {
    const project = projectWithLayers([textLayer]);
    const now = jest.spyOn(Date, 'now');
    const random = jest.spyOn(Math, 'random');
    try {
      buildImageRenderPlan(project, { planId: 'plan-1' });
      expect(now).not.toHaveBeenCalled();
      expect(random).not.toHaveBeenCalled();
    } finally {
      now.mockRestore();
      random.mockRestore();
    }
  });
});

describe('transparent background widening', () => {
  test('validates as a version 2 project with no asset', () => {
    const project = reduceMemeEditProject(createDefaultImageProject(imageSource), {
      type: 'set-background',
      background: { mode: 'transparent', color: '#00000000', assetUri: 'file:///ignored.png', blurScale: 0.5 },
    });

    expect(project.version).toBe(2);
    expect(project.background.mode).toBe('transparent');
    expect(project.background.assetUri).toBeNull();
    expect(validateMemeEditProject(project).ok).toBe(true);
  });
});

// The connected instrumentation (MemeImageRendererInstrumentedTest) feeds these
// exact JSON files to MemeImageRenderer.kt. Pinning them here means the native
// gate can never drift away from what this builder actually emits: change the
// plan shape and these fail until the assets are regenerated.
describe('device instrumentation fixtures', () => {
  const fixtureDir = join(__dirname, '..', 'modules', 'memeget-bg', 'android', 'src', 'androidTest', 'assets');
  const fixtureSource = { uri: 'SOURCE_URI', name: 'fixture.png', width: 640, height: 400 };

  const burnedText: TextLayer = {
    id: 'text-burn',
    kind: 'text',
    text: 'HELLO',
    width: 0.9,
    fontSize: 0.18,
    style: {
      preset: 'impact',
      color: '#FFFFFF',
      outlineColor: '#000000',
      outlineScale: 0.06,
      backgroundColor: null,
      opacity: 1,
      align: 'center',
      uppercase: true,
    },
    active: null,
    keyframes: [frame()],
  };
  const solidCover: CoverLayer = {
    id: 'cover-solid',
    kind: 'cover',
    rect: { x: 0, y: 0, width: 0.25, height: 0.25 },
    mode: 'solid',
    color: '#FF00FF',
    pixelSize: 8,
    active: null,
    corrections: [],
  };
  const pixelateCover: CoverLayer = {
    ...solidCover,
    id: 'cover-pixelate',
    rect: { x: 0.5, y: 0.6, width: 0.4, height: 0.3 },
    mode: 'pixelate',
    pixelSize: 16,
  };

  test('render_plan_rotated_cropped.json matches a freshly built rotated+cropped plan', () => {
    const rotated = reduceMemeEditProject(createDefaultImageProject(fixtureSource), {
      type: 'set-base-transform',
      base: {
        rotation: 90,
        flipX: false,
        flipY: false,
        crop: { x: 0.25, y: 0.1, width: 0.5, height: 0.8 },
        outputAspect: 'free',
      },
    });
    const plan = buildImageRenderPlan(
      reduceMemeEditProject(rotated, { type: 'add-layers', layers: [solidCover, pixelateCover, burnedText] }),
      { planId: 'gate-opaque' }
    );

    // 640x400 rotated 90 -> 400x640, cropped to 0.5 x 0.8.
    expect(plan.output.widthPx).toBe(200);
    expect(plan.output.heightPx).toBe(512);
    expect(JSON.parse(readFileSync(join(fixtureDir, 'render_plan_rotated_cropped.json'), 'utf8'))).toEqual(plan);
  });

  test('render_plan_transparent.json matches a freshly built transparent-background plan', () => {
    const transparent = reduceMemeEditProject(createDefaultImageProject(fixtureSource), {
      type: 'set-background',
      background: { mode: 'transparent', color: '#00000000', assetUri: null, blurScale: 0 },
    });
    const plan = buildImageRenderPlan(
      reduceMemeEditProject(transparent, { type: 'add-layers', layers: [solidCover, burnedText] }),
      { planId: 'gate-transparent' }
    );

    expect(JSON.parse(readFileSync(join(fixtureDir, 'render_plan_transparent.json'), 'utf8'))).toEqual(plan);
  });
});
