import {
  MAX_HISTORY_STATES,
  PROJECT_LIMITS,
  applyProjectAction,
  beginProjectTransaction,
  cancelProjectTransaction,
  clampNormalizedPoint,
  clampNormalizedRect,
  commitProjectTransaction,
  createDefaultImageProject,
  createDefaultVideoProject,
  createProjectHistory,
  evaluateMaskTrackRect,
  interpolateTransformKeyframes,
  interpolateCoverCorrections,
  isLayerActiveAt,
  mapPointToCroppedCanvas,
  migrateMemeEditProject,
  mapRectToCroppedCanvas,
  normalizeRetainedRanges,
  outputDurationUs,
  outputTimeToSourceTimeUs,
  reduceMemeEditProject,
  redoProjectHistory,
  rotateNormalizedPoint,
  rotateNormalizedRect,
  sourceTimeToOutputTimeUs,
  transformPointToWorkingCanvas,
  undoProjectHistory,
  validateMemeEditProject,
  type BackgroundSpec,
  type CoverLayer,
  type CoverCorrectionKeyframe,
  type MediaOverlayLayer,
  type MemeEditProject,
  type MemeEditLayer,
  type SubjectLayer,
  type MaskTrackSpec,
  type TextLayer,
  type TransformKeyframe,
} from './memeEditProjectCore';

const SECOND_US = 1_000_000;

const imageSource = {
  uri: 'file:///source.jpg',
  name: 'source.jpg',
  width: 1200,
  height: 800,
};

const videoSource = {
  uri: 'file:///source.mp4',
  name: 'source.mp4',
  width: 1920,
  height: 1080,
  durationUs: 10 * SECOND_US,
};

const defaultStyle = {
  preset: 'impact' as const,
  color: '#ffffff',
  outlineColor: '#000000',
  outlineScale: 0.05,
  backgroundColor: null,
  opacity: 1,
  align: 'center' as const,
  uppercase: false,
};

function keyframe(
  timeUs = 0,
  overrides: Partial<TransformKeyframe> = {}
): TransformKeyframe {
  return {
    timeUs,
    center: { x: 0.5, y: 0.5 },
    scale: 1,
    rotationDegrees: 0,
    opacity: 1,
    easing: 'linear',
    ...overrides,
  };
}
function maskTrack(
  id: string,
  active: MaskTrackSpec['active'] = null
): MaskTrackSpec {
  return {
    id,
    active,
    corrections: [
      {
        timeUs: active?.startUs ?? 0,
        rect: { x: 0.1, y: 0.1, width: 0.3, height: 0.3 },
        easing: 'linear',
      },
    ],
  };
}


function textLayer(id: string, active: TextLayer['active'] = null): TextLayer {
  return {
    id,
    kind: 'text',
    text: id,
    width: 0.5,
    fontSize: 0.1,
    style: defaultStyle,
    active,
    keyframes: [keyframe(active?.startUs ?? 0)],
  };
}

function cloneProject(project: MemeEditProject): MemeEditProject {
  return JSON.parse(JSON.stringify(project)) as MemeEditProject;
}
function requireTextLayer(layer: MemeEditLayer): TextLayer {
  if (layer.kind !== 'text') throw new Error(`Expected text layer, received ${layer.kind}.`);
  return layer;
}


function expectInvalid(project: unknown, path: string, code?: string): void {
  const result = validateMemeEditProject(project);
  expect(result.ok).toBe(false);
  if (result.ok) return;
  expect(result.errors).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        path,
        ...(code ? { code } : {}),
        message: expect.any(String),
      }),
    ])
  );
}

describe('default projects', () => {
  test('creates a minimal image project from caller-supplied metadata', () => {
    const project = createDefaultImageProject(imageSource);

    expect(project).toEqual({
      version: 2,
      source: { ...imageSource, kind: 'image', durationUs: null },
      base: {
        rotation: 0,
        flipX: false,
        flipY: false,
        crop: { x: 0, y: 0, width: 1, height: 1 },
        outputAspect: 'source',
      },
      video: null,
      layers: [],
      maskTracks: [],
      background: {
        mode: 'source',
        color: '#000000',
        assetUri: null,
        blurScale: 0,
      },
      transient: { maskTracks: {}, materializedSourceUri: null },
    });
    expect(validateMemeEditProject(project)).toEqual({ ok: true, value: project });
  });

  test('creates a full-retained-range video with source audio preserved', () => {
    const project = createDefaultVideoProject(videoSource);

    expect(project.source).toEqual({ ...videoSource, kind: 'video' });
    expect(project.video).toEqual({
      retainedRanges: [{ startUs: 0, endUs: videoSource.durationUs }],
      speed: 1,
      audio: { muted: false, volume: 1 },
      insertedCards: [],
    });
    expect(validateMemeEditProject(project)).toEqual({ ok: true, value: project });
  });
});

describe('normalized geometry', () => {
  test('clamps point and rect values to finite unit-canvas bounds', () => {
    expect(clampNormalizedPoint({ x: -0.25, y: 1.25 })).toEqual({ x: 0, y: 1 });
    expect(clampNormalizedPoint({ x: Number.NaN, y: Number.POSITIVE_INFINITY })).toEqual({
      x: 0,
      y: 1,
    });
    expect(clampNormalizedRect({ x: 0.8, y: -1, width: 0.8, height: 1.5 })).toEqual({
      x: 0.8,
      y: 0,
      width: 0.2,
      height: 1,
    });
  });

  test('rotates normalized points and axis-aligned rects clockwise', () => {
    expect(rotateNormalizedPoint({ x: 0.2, y: 0.3 }, 90)).toEqual({ x: 0.7, y: 0.2 });
    expect(rotateNormalizedPoint({ x: 0.2, y: 0.3 }, 180)).toEqual({ x: 0.8, y: 0.7 });
    expect(rotateNormalizedPoint({ x: 0.2, y: 0.3 }, 270)).toEqual({ x: 0.3, y: 0.8 });
    expect(rotateNormalizedRect({ x: 0.1, y: 0.2, width: 0.3, height: 0.4 }, 90)).toEqual({
      x: 0.4,
      y: 0.1,
      width: 0.4,
      height: 0.3,
    });
  });

  test('maps points and rects into the cropped working canvas', () => {
    const crop = { x: 0.25, y: 0.25, width: 0.5, height: 0.5 };
    expect(mapPointToCroppedCanvas({ x: 0.5, y: 0.5 }, crop)).toEqual({ x: 0.5, y: 0.5 });
    expect(mapPointToCroppedCanvas({ x: 0, y: 1 }, crop)).toEqual({ x: 0, y: 1 });
    expect(mapRectToCroppedCanvas({ x: 0.25, y: 0.25, width: 0.25, height: 0.5 }, crop)).toEqual({
      x: 0,
      y: 0,
      width: 0.5,
      height: 1,
    });
  });

  test('applies rotation, flips, then crop in a stable order', () => {
    expect(
      transformPointToWorkingCanvas(
        { x: 0.2, y: 0.3 },
        {
          rotation: 90,
          flipX: true,
          flipY: false,
          crop: { x: 0.2, y: 0, width: 0.5, height: 1 },
          outputAspect: 'source',
        }
      )
    ).toEqual({ x: 0.2, y: 0.2 });
  });
});

describe('retained ranges and timeline mapping', () => {
  test('normalizes user ranges by rounding, clipping, sorting, and merging overlap', () => {
    expect(
      normalizeRetainedRanges(
        [
          { startUs: 8 * SECOND_US, endUs: 12 * SECOND_US },
          { startUs: -2, endUs: 3 * SECOND_US },
          { startUs: 2 * SECOND_US, endUs: 5 * SECOND_US },
          { startUs: 6.2, endUs: 6.4 },
        ],
        10 * SECOND_US
      )
    ).toEqual([
      { startUs: 0, endUs: 5 * SECOND_US },
      { startUs: 6, endUs: 6 },
      { startUs: 8 * SECOND_US, endUs: 10 * SECOND_US },
    ].filter((range) => range.startUs < range.endUs));
  });

  test('maps source and output times across removed spans at 2x speed', () => {
    const ranges = [
      { startUs: 0, endUs: 2 * SECOND_US },
      { startUs: 4 * SECOND_US, endUs: 6 * SECOND_US },
    ];

    expect(sourceTimeToOutputTimeUs(SECOND_US, ranges, 2)).toBe(500_000);
    expect(sourceTimeToOutputTimeUs(3 * SECOND_US, ranges, 2)).toBeNull();
    expect(sourceTimeToOutputTimeUs(5 * SECOND_US, ranges, 2)).toBe(1_500_000);
    expect(outputTimeToSourceTimeUs(SECOND_US, ranges, 2)).toBe(4 * SECOND_US);
    expect(outputTimeToSourceTimeUs(1_500_000, ranges, 2)).toBe(5 * SECOND_US);
    expect(outputTimeToSourceTimeUs(2 * SECOND_US + 1, ranges, 2)).toBeNull();
    expect(outputDurationUs(ranges, 2)).toBe(2 * SECOND_US);
  });
  test('treats interior retained-range ends as half-open and only includes the final endpoint', () => {
    const ranges = [
      { startUs: 0, endUs: 2 * SECOND_US },
      { startUs: 4 * SECOND_US, endUs: 6 * SECOND_US },
    ];

    expect(sourceTimeToOutputTimeUs(2 * SECOND_US, ranges, 2)).toBeNull();
    expect(sourceTimeToOutputTimeUs(4 * SECOND_US, ranges, 2)).toBe(SECOND_US);
    expect(outputTimeToSourceTimeUs(SECOND_US, ranges, 2)).toBe(4 * SECOND_US);
    expect(sourceTimeToOutputTimeUs(6 * SECOND_US, ranges, 2)).toBe(2 * SECOND_US);
    expect(outputTimeToSourceTimeUs(2 * SECOND_US, ranges, 2)).toBe(6 * SECOND_US);
  });
  test('maps a rounded output seam exactly to the next retained range start', () => {
    const ranges = [
      { startUs: 0, endUs: 1 },
      { startUs: 10, endUs: 12 },
    ];

    expect(outputTimeToSourceTimeUs(1, ranges, 2)).toBe(10);
  });



  test('uses deterministic integer microseconds at 0.5x speed', () => {
    const ranges = [
      { startUs: 0, endUs: 2 * SECOND_US },
      { startUs: 4 * SECOND_US, endUs: 6 * SECOND_US },
    ];
    expect(sourceTimeToOutputTimeUs(5 * SECOND_US, ranges, 0.5)).toBe(6 * SECOND_US);
    expect(outputTimeToSourceTimeUs(6 * SECOND_US, ranges, 0.5)).toBe(5 * SECOND_US);
    expect(outputDurationUs(ranges, 0.5)).toBe(8 * SECOND_US);
  });
});

describe('layer behavior', () => {
  test('uses array order as render order and immutably moves, duplicates, updates, and deletes', () => {
    const initial = createDefaultImageProject(imageSource);
    const withLayers = ['a', 'b', 'c'].reduce(
      (project, id) => reduceMemeEditProject(project, { type: 'add-layer', layer: textLayer(id) }),
      initial
    );
    const moved = reduceMemeEditProject(withLayers, { type: 'move-layer', id: 'a', toIndex: 2 });
    const duplicated = reduceMemeEditProject(moved, {
      type: 'duplicate-layer',
      id: 'b',
      newId: 'b-copy',
    });
    const replacement = { ...textLayer('b-copy'), text: 'updated' };
    const updated = reduceMemeEditProject(duplicated, {
      type: 'update-layer',
      layer: replacement,
    });
    const removed = reduceMemeEditProject(updated, { type: 'remove-layer', id: 'c' });

    expect(withLayers.layers.map(({ id }) => id)).toEqual(['a', 'b', 'c']);
    expect(moved.layers.map(({ id }) => id)).toEqual(['b', 'c', 'a']);
    expect(duplicated.layers.map(({ id }) => id)).toEqual(['b', 'b-copy', 'c', 'a']);
    expect(requireTextLayer(updated.layers[1]).text).toBe('updated');
    expect(removed.layers.map(({ id }) => id)).toEqual(['b', 'b-copy', 'a']);
    expect(initial.layers).toEqual([]);
    const duplicatedOriginal = duplicated.layers[0];
    const duplicatedCopy = duplicated.layers[1];
    expect(duplicatedOriginal.kind).toBe('text');
    expect(duplicatedCopy.kind).toBe('text');
    if (duplicatedOriginal.kind === 'text' && duplicatedCopy.kind === 'text') {
      expect(duplicatedOriginal.keyframes).not.toBe(duplicatedCopy.keyframes);
    }
  });
  test('ignores non-finite add and move indices instead of relying on splice coercion', () => {
    const initial = createDefaultImageProject(imageSource);
    expect(
      reduceMemeEditProject(initial, {
        type: 'add-layer',
        layer: textLayer('invalid-index'),
        index: Number.NaN,
      })
    ).toBe(initial);
    expect(
      reduceMemeEditProject(initial, {
        type: 'add-layer',
        layer: textLayer('invalid-index'),
        index: Number.POSITIVE_INFINITY,
      })
    ).toBe(initial);

    const withLayer = reduceMemeEditProject(initial, {
      type: 'add-layer',
      layer: textLayer('move-me'),
    });
    expect(
      reduceMemeEditProject(withLayer, {
        type: 'move-layer',
        id: 'move-me',
        toIndex: Number.NaN,
      })
    ).toBe(withLayer);
    expect(
      reduceMemeEditProject(withLayer, {
        type: 'move-layer',
        id: 'move-me',
        toIndex: Number.NEGATIVE_INFINITY,
      })
    ).toBe(withLayer);
  });


  test('checks held active ranges inclusively and treats untimed layers as active', () => {
    const timed = textLayer('timed', { startUs: SECOND_US, endUs: 2 * SECOND_US });
    const cover: CoverLayer = {
      id: 'cover',
      kind: 'cover',
      rect: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 },
      mode: 'solid',
      color: '#000000',
      pixelSize: 8,
      active: { startUs: SECOND_US, endUs: 2 * SECOND_US },
      corrections: [],
    };

    expect(isLayerActiveAt(timed, SECOND_US - 1)).toBe(false);
    expect(isLayerActiveAt(timed, SECOND_US)).toBe(true);
    expect(isLayerActiveAt(timed, 2 * SECOND_US)).toBe(true);
    expect(isLayerActiveAt(timed, 2 * SECOND_US + 1)).toBe(false);
    expect(isLayerActiveAt(textLayer('image'), 99 * SECOND_US)).toBe(true);
    expect(isLayerActiveAt(cover, 99 * SECOND_US)).toBe(false);
  });

  test('linearly interpolates sparse transforms and clamps outside their timestamps', () => {
    const frames = [
      keyframe(0, {
        center: { x: 0, y: 0.25 },
        scale: 1,
        rotationDegrees: -30,
        opacity: 0.2,
      }),
      keyframe(SECOND_US, {
        center: { x: 1, y: 0.75 },
        scale: 3,
        rotationDegrees: 30,
        opacity: 1,
      }),
    ];

    expect(interpolateTransformKeyframes(frames, 500_000)).toEqual({
      center: { x: 0.5, y: 0.5 },
      scale: 2,
      rotationDegrees: 0,
      opacity: 0.6,
    });
    expect(interpolateTransformKeyframes(frames, -1)).toEqual({
      center: { x: 0, y: 0.25 },
      scale: 1,
      rotationDegrees: -30,
      opacity: 0.2,
    });
    expect(interpolateTransformKeyframes(frames, 2 * SECOND_US)).toEqual({
      center: { x: 1, y: 0.75 },
      scale: 3,
      rotationDegrees: 30,
      opacity: 1,
    });
  });
  test('rejects non-finite and fractional transform interpolation timestamps', () => {
    const frames = [keyframe(0), keyframe(SECOND_US)];
    expect(interpolateTransformKeyframes(frames, Number.NaN)).toBeNull();
    expect(interpolateTransformKeyframes(frames, Number.POSITIVE_INFINITY)).toBeNull();
    expect(interpolateTransformKeyframes(frames, 0.5)).toBeNull();
  });


  test('holds the left keyframe until the exact next sparse correction', () => {
    const frames = [
      keyframe(0, { center: { x: 0.2, y: 0.2 }, easing: 'hold' }),
      keyframe(SECOND_US, { center: { x: 0.8, y: 0.8 } }),
    ];

    expect(interpolateTransformKeyframes(frames, SECOND_US - 1)?.center).toEqual({ x: 0.2, y: 0.2 });
    expect(interpolateTransformKeyframes(frames, SECOND_US)?.center).toEqual({ x: 0.8, y: 0.8 });
    expect(frames).toHaveLength(2);
  });
  test('interpolates sparse cover rect corrections while holding discrete mode changes', () => {
    const corrections: CoverCorrectionKeyframe[] = [
      {
        timeUs: 0,
        rect: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 },
        mode: 'solid',
        easing: 'linear',
      },
      {
        timeUs: SECOND_US,
        rect: { x: 0.3, y: 0.3, width: 0.4, height: 0.4 },
        mode: 'pixelate',
        easing: 'hold',
      },
    ];

    expect(interpolateCoverCorrections(corrections, 500_000)).toEqual({
      rect: { x: 0.2, y: 0.2, width: 0.3, height: 0.3 },
      mode: 'solid',
    });
    expect(interpolateCoverCorrections(corrections, SECOND_US)).toEqual({
      rect: { x: 0.3, y: 0.3, width: 0.4, height: 0.4 },
      mode: 'pixelate',
    });
  });
  test('evaluates linear and hold mask corrections at sparse timestamps and boundaries', () => {
    const track: MaskTrackSpec = {
      id: 'tracked-object',
      active: { startUs: 0, endUs: 3 * SECOND_US },
      corrections: [
        {
          timeUs: 500_000,
          rect: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 },
          easing: 'linear',
        },
        {
          timeUs: SECOND_US,
          rect: { x: 0.3, y: 0.3, width: 0.4, height: 0.4 },
          easing: 'hold',
        },
        {
          timeUs: 2 * SECOND_US,
          rect: { x: 0.7, y: 0.7, width: 0.2, height: 0.2 },
          easing: 'linear',
        },
      ],
    };

    expect(evaluateMaskTrackRect(track, 0)).toEqual(track.corrections[0].rect);
    expect(evaluateMaskTrackRect(track, 750_000)).toEqual({
      x: 0.2,
      y: 0.2,
      width: 0.3,
      height: 0.3,
    });
    expect(evaluateMaskTrackRect(track, 1_500_000)).toEqual(track.corrections[1].rect);
    expect(evaluateMaskTrackRect(track, 2_500_000)).toEqual(track.corrections[2].rect);
    expect(evaluateMaskTrackRect(track, -1)).toBeNull();
    expect(evaluateMaskTrackRect(track, 3 * SECOND_US + 1)).toBeNull();
  });

  test('returns null for mask tracks without sparse correction metadata', () => {
    expect(
      evaluateMaskTrackRect(
        { id: 'empty', active: null, corrections: [] },
        SECOND_US
      )
    ).toBeNull();
  });


});

describe('persisted project validation', () => {
  test('accepts valid text, cover, subject, and media-overlay layers', () => {
    const project = createDefaultVideoProject(videoSource);
    const active = { startUs: SECOND_US, endUs: 3 * SECOND_US };
    const subject: SubjectLayer = {
      id: 'subject',
      kind: 'subject',
      subjectIndex: 0,
      maskTrackId: 'subject-track',
      active,
      keyframes: [keyframe(SECOND_US), keyframe(3 * SECOND_US)],
      outlineColor: null,
      outlineScale: 0.1,
      shadowScale: 0.2,
    };
    const media: MediaOverlayLayer = {
      id: 'replacement',
      kind: 'media',
      assetUri: 'file:///replacement.png',
      assetKind: 'image',
      fit: 'cover',
      targetMaskTrackId: 'subject-track',
      active,
      keyframes: [keyframe(SECOND_US), keyframe(3 * SECOND_US)],
    };
    project.layers = [
      textLayer('caption', active),
      {
        id: 'cover',
        kind: 'cover',
        rect: { x: 0.1, y: 0.1, width: 0.3, height: 0.3 },
        mode: 'pixelate',
        color: '#000000',
        pixelSize: 12,
        active,
        corrections: [
          {
            timeUs: SECOND_US,
            rect: { x: 0.1, y: 0.1, width: 0.3, height: 0.3 },
            mode: 'pixelate',
            easing: 'hold',
          },
        ],
      },
      subject,
      media,
    ];
    project.maskTracks = [maskTrack('subject-track', active)];
    project.transient.maskTracks['subject-track'] = 'file:///mask.track';

    expect(validateMemeEditProject(project)).toEqual({ ok: true, value: project });
  });

  const activeLayerRange = { startUs: 0, endUs: SECOND_US };
  const validCoverLayer: CoverLayer = {
    id: 'cover',
    kind: 'cover',
    rect: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 },
    mode: 'solid',
    color: '#000000',
    pixelSize: 8,
    active: activeLayerRange,
    corrections: [],
  };
  const validSubjectLayer: SubjectLayer = {
    id: 'subject',
    kind: 'subject',
    subjectIndex: 0,
    maskTrackId: 'track',
    active: activeLayerRange,
    keyframes: [keyframe(0)],
    outlineColor: '#ffffff',
    outlineScale: 0.1,
    shadowScale: 0.1,
  };
  const validMediaLayer: MediaOverlayLayer = {
    id: 'media',
    kind: 'media',
    assetUri: 'file:///overlay.png',
    assetKind: 'image',
    fit: 'contain',
    targetMaskTrackId: null,
    active: activeLayerRange,
    keyframes: [keyframe(0)],
  };
  const validVideo = createDefaultVideoProject(videoSource);
  const malformedLayerCases: Array<{ name: string; project: unknown; path: string }> = [
    {
      name: 'text',
      project: {
        ...validVideo,
        layers: [{ ...textLayer('text', activeLayerRange), width: 0 }],
      },
      path: 'layers[0].width',
    },
    {
      name: 'cover',
      project: {
        ...validVideo,
        layers: [{ ...validCoverLayer, rect: { ...validCoverLayer.rect, width: 0 } }],
      },
      path: 'layers[0].rect.width',
    },
    {
      name: 'subject',
      project: {
        ...validVideo,
        layers: [{ ...validSubjectLayer, subjectIndex: -1 }],
      },
      path: 'layers[0].subjectIndex',
    },
    {
      name: 'media',
      project: {
        ...validVideo,
        layers: [{ ...validMediaLayer, assetKind: 'audio' }],
      },
      path: 'layers[0].assetKind',
    },
  ];

  test.each(malformedLayerCases)(
    'rejects malformed $name layers with a field path',
    ({ project, path }) => {
      expectInvalid(project, path);
    }
  );
  test('validates v2 text font size bounds strictly and migrates unambiguous v1 text layers', () => {
    const project = createDefaultImageProject(imageSource);
    const valid = textLayer('caption');
    project.layers = [valid];
    expect(validateMemeEditProject(project)).toEqual({ ok: true, value: project });

    const tooSmall = cloneProject(project);
    requireTextLayer(tooSmall.layers[0]).fontSize = 0.001;
    expectInvalid(tooSmall, 'layers[0].fontSize', 'out_of_bounds');

    const tooLarge = cloneProject(project);
    requireTextLayer(tooLarge.layers[0]).fontSize = 0.5;
    expectInvalid(tooLarge, 'layers[0].fontSize', 'out_of_bounds');

    const missingCurrent = cloneProject(project) as Omit<MemeEditProject, 'layers'> & {
      layers: Array<Omit<TextLayer, 'fontSize'>>;
    };
    delete (missingCurrent.layers[0] as Partial<TextLayer>).fontSize;
    expectInvalid(missingCurrent, 'layers[0].fontSize', 'invalid_type');

    const legacy = { ...missingCurrent, version: 1 };
    const migrated = migrateMemeEditProject(legacy);
    expect(migrated.ok).toBe(true);
    expect(migrated.ok ? migrated.value.version : null).toBe(2);
    expect(migrated.ok ? requireTextLayer(migrated.value.layers[0]).fontSize : null).toBe(0.118);
    expect(validateMemeEditProject(legacy).ok).toBe(false);
  });

  test('requires persistent normalized correction metadata for referenced mask tracks', () => {
    const project = createDefaultVideoProject(videoSource);
    const active = { startUs: 0, endUs: SECOND_US };
    project.layers = [
      {
        id: 'media',
        kind: 'media',
        assetUri: 'file:///replacement.png',
        assetKind: 'image',
        fit: 'cover',
        targetMaskTrackId: 'missing-track',
        active,
        keyframes: [keyframe(0)],
      },
    ];
    expectInvalid(project, 'layers[0].targetMaskTrackId', 'invalid_value');

    const persistent = maskTrack('object-track', active);
    const malformedCorrection: unknown = {
      ...project,
      layers: [],
      maskTracks: [
        {
          ...persistent,
          corrections: [{ ...persistent.corrections[0], bitmap: 'forbidden-bytes' }],
        },
      ],
    };
    expectInvalid(
      malformedCorrection,
      'maskTracks[0].corrections[0].bitmap',
      'unknown_field'
    );
  });

  test('accepts a transparent background but still rejects an unknown mode or a stray asset', () => {
    const transparent = cloneProject(createDefaultImageProject(imageSource));
    transparent.background = { mode: 'transparent', color: '#00000000', assetUri: null, blurScale: 0 };
    expect(validateMemeEditProject(transparent).ok).toBe(true);

    const strayAsset = cloneProject(transparent);
    strayAsset.background.assetUri = 'file:///nope.png';
    expectInvalid(strayAsset, 'background.assetUri', 'invalid_value');

    const unknownMode = cloneProject(transparent) as Omit<MemeEditProject, 'background'> & {
      background: Omit<BackgroundSpec, 'mode'> & { mode: string };
    };
    unknownMode.background.mode = 'see-through';
    expectInvalid(unknownMode, 'background.mode', 'invalid_value');
  });


  test('returns actionable errors for malformed version and non-finite geometry', () => {
    const imageProject = cloneProject(createDefaultImageProject(imageSource));
    const malformedVersion: unknown = { ...imageProject, version: 3 };
    expectInvalid(malformedVersion, 'version', 'unsupported_version');

    const nanPoint = cloneProject(createDefaultImageProject(imageSource));
    const nanLayer = textLayer('nan');
    nanLayer.keyframes[0].center.x = Number.NaN;
    nanPoint.layers = [nanLayer];
    expectInvalid(nanPoint, 'layers[0].keyframes[0].center.x', 'not_finite');

    const infiniteCrop = cloneProject(createDefaultImageProject(imageSource));
    infiniteCrop.base.crop.width = Number.POSITIVE_INFINITY;
    expectInvalid(infiniteCrop, 'base.crop.width', 'not_finite');
  });

  test('rejects zero-area crop, unsorted/overlapping/out-of-bounds ranges, and non-integer times', () => {
    const zeroCrop = cloneProject(createDefaultImageProject(imageSource));
    zeroCrop.base.crop.width = 0;
    expectInvalid(zeroCrop, 'base.crop.width', 'out_of_bounds');

    const unsorted = cloneProject(createDefaultVideoProject(videoSource));
    unsorted.video!.retainedRanges = [
      { startUs: 4 * SECOND_US, endUs: 5 * SECOND_US },
      { startUs: SECOND_US, endUs: 2 * SECOND_US },
    ];
    expectInvalid(unsorted, 'video.retainedRanges[1].startUs', 'not_sorted');

    const overlap = cloneProject(createDefaultVideoProject(videoSource));
    overlap.video!.retainedRanges = [
      { startUs: 0, endUs: 3 * SECOND_US },
      { startUs: 2 * SECOND_US, endUs: 4 * SECOND_US },
    ];
    expectInvalid(overlap, 'video.retainedRanges[1].startUs', 'overlap');

    const outOfBounds = cloneProject(createDefaultVideoProject(videoSource));
    outOfBounds.video!.retainedRanges = [{ startUs: 0, endUs: videoSource.durationUs + 1 }];
    expectInvalid(outOfBounds, 'video.retainedRanges[0].endUs', 'out_of_bounds');

    const fractional = cloneProject(createDefaultVideoProject(videoSource));
    fractional.video!.retainedRanges = [{ startUs: 0.5, endUs: videoSource.durationUs }];
    expectInvalid(fractional, 'video.retainedRanges[0].startUs', 'not_integer');
  });

  test('rejects duplicate layer IDs and unsorted, duplicate, or out-of-active-range keyframes', () => {
    const duplicateId = cloneProject(createDefaultImageProject(imageSource));
    duplicateId.layers = [textLayer('same'), textLayer('same')];
    expectInvalid(duplicateId, 'layers[1].id', 'duplicate');

    const unsorted = cloneProject(createDefaultVideoProject(videoSource));
    const unsortedLayer = textLayer('timed', { startUs: 0, endUs: 2 * SECOND_US });
    unsortedLayer.keyframes = [keyframe(SECOND_US), keyframe(0)];
    unsorted.layers = [unsortedLayer];
    expectInvalid(unsorted, 'layers[0].keyframes[1].timeUs', 'not_sorted');

    const duplicateTime = cloneProject(createDefaultVideoProject(videoSource));
    const duplicateTimeLayer = textLayer('timed', { startUs: 0, endUs: 2 * SECOND_US });
    duplicateTimeLayer.keyframes = [keyframe(0), keyframe(0)];
    duplicateTime.layers = [duplicateTimeLayer];
    expectInvalid(duplicateTime, 'layers[0].keyframes[1].timeUs', 'duplicate');

    const outsideActive = cloneProject(createDefaultVideoProject(videoSource));
    const outsideActiveLayer = textLayer('timed', {
      startUs: SECOND_US,
      endUs: 2 * SECOND_US,
    });
    outsideActiveLayer.keyframes = [keyframe(0)];
    outsideActive.layers = [outsideActiveLayer];
    expectInvalid(outsideActive, 'layers[0].keyframes[0].timeUs', 'out_of_bounds');
  });

  test('enforces every project collection limit', () => {
    const tooManyLayers = cloneProject(createDefaultImageProject(imageSource));
    tooManyLayers.layers = Array.from({ length: PROJECT_LIMITS.maxLayers + 1 }, (_, index) =>
      textLayer(`layer-${index}`)
    );
    expectInvalid(tooManyLayers, 'layers', 'limit_exceeded');

    const tooManyRanges = cloneProject(createDefaultVideoProject(videoSource));
    tooManyRanges.source.durationUs = (PROJECT_LIMITS.maxRetainedRanges + 1) * 2;
    tooManyRanges.video!.retainedRanges = Array.from(
      { length: PROJECT_LIMITS.maxRetainedRanges + 1 },
      (_, index) => ({ startUs: index * 2, endUs: index * 2 + 1 })
    );
    expectInvalid(tooManyRanges, 'video.retainedRanges', 'limit_exceeded');

    const tooManyKeyframes = cloneProject(createDefaultVideoProject(videoSource));
    tooManyKeyframes.source.durationUs = PROJECT_LIMITS.maxKeyframesPerLayer + 1;
    tooManyKeyframes.video!.retainedRanges = [
      { startUs: 0, endUs: PROJECT_LIMITS.maxKeyframesPerLayer + 1 },
    ];
    const timed = textLayer('many', {
      startUs: 0,
      endUs: PROJECT_LIMITS.maxKeyframesPerLayer + 1,
    });
    timed.keyframes = Array.from({ length: PROJECT_LIMITS.maxKeyframesPerLayer + 1 }, (_, index) =>
      keyframe(index)
    );
    tooManyKeyframes.layers = [timed];
    expectInvalid(tooManyKeyframes, 'layers[0].keyframes', 'limit_exceeded');

    const tooManyCards = cloneProject(createDefaultVideoProject(videoSource));
    tooManyCards.video!.insertedCards = Array.from(
      { length: PROJECT_LIMITS.maxInsertedCards + 1 },
      (_, index) => ({ uri: 'file:///same-card.png', atUs: index, durationUs: 1 })
    );
    expectInvalid(tooManyCards, 'video.insertedCards', 'limit_exceeded');

    const tooManyTracks = cloneProject(createDefaultImageProject(imageSource));
    tooManyTracks.transient.maskTracks = Object.fromEntries(
      Array.from({ length: PROJECT_LIMITS.maxMaskTracks + 1 }, (_, index) => [
        `track-${index}`,
        `file:///track-${index}`,
      ])
    );
    expectInvalid(tooManyTracks, 'transient.maskTracks', 'limit_exceeded');
    const tooManyPersistentTracks = cloneProject(createDefaultImageProject(imageSource));
    tooManyPersistentTracks.maskTracks = Array.from(
      { length: PROJECT_LIMITS.maxMaskTracks + 1 },
      (_, index) => maskTrack(`persistent-track-${index}`)
    );
    expectInvalid(tooManyPersistentTracks, 'maskTracks', 'limit_exceeded');
    const tooManyCorrections = cloneProject(createDefaultImageProject(imageSource));
    const correctionTrack = maskTrack('bounded-track');
    correctionTrack.corrections = Array.from(
      { length: PROJECT_LIMITS.maxCorrectionsPerMaskTrack + 1 },
      (_, index) => ({
        timeUs: index,
        rect: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 },
        easing: 'linear' as const,
      })
    );
    tooManyCorrections.maskTracks = [correctionTrack];
    expectInvalid(
      tooManyCorrections,
      'maskTracks[0].corrections',
      'limit_exceeded'
    );
  });

  test('rejects projects with too many distinct external assets or an unbounded asset URI', () => {
    const tooManyAssets = cloneProject(createDefaultVideoProject(videoSource));
    const active = { startUs: 0, endUs: SECOND_US };
    tooManyAssets.layers = Array.from(
      { length: PROJECT_LIMITS.maxExternalAssets + 1 },
      (_, index): MediaOverlayLayer => ({
        id: `media-${index}`,
        kind: 'media',
        assetUri: `file:///asset-${index}.png`,
        assetKind: 'image',
        fit: 'contain',
        targetMaskTrackId: null,
        active,
        keyframes: [keyframe(0)],
      })
    );
    expectInvalid(tooManyAssets, 'externalAssets', 'limit_exceeded');

    const longAsset = cloneProject(createDefaultVideoProject(videoSource));
    longAsset.layers = [
      {
        id: 'media',
        kind: 'media',
        assetUri: `file:///${'x'.repeat(PROJECT_LIMITS.maxAssetUriLength)}`,
        assetKind: 'image',
        fit: 'contain',
        targetMaskTrackId: null,
        active,
        keyframes: [keyframe(0)],
      },
    ];
    expectInvalid(longAsset, 'layers[0].assetUri', 'limit_exceeded');
  });

  test('rejects decoded-frame state instead of accepting one object per source frame', () => {
    const project = createDefaultVideoProject(videoSource) as MemeEditProject & {
      decodedFrames?: unknown[];
    };
    project.decodedFrames = [{ bitmap: 'not-project-state' }];
    expectInvalid(project, 'decodedFrames', 'unknown_field');
  });
});

describe('immutable reducer', () => {
  test('normalizes base, video, background, and transient user actions without mutating input', () => {
    const initial = createDefaultVideoProject(videoSource);
    const based = reduceMemeEditProject(initial, {
      type: 'set-base-transform',
      base: {
        ...initial.base,
        rotation: 90,
        crop: { x: 0.8, y: -1, width: 0.8, height: 5 },
      },
    });
    const ranged = reduceMemeEditProject(based, {
      type: 'set-video-retained-ranges',
      retainedRanges: [
        { startUs: 8 * SECOND_US, endUs: 12 * SECOND_US },
        { startUs: 0, endUs: 2 * SECOND_US },
      ],
    });
    const sped = reduceMemeEditProject(ranged, { type: 'set-video-speed', speed: 9 });
    const audio = reduceMemeEditProject(sped, {
      type: 'set-video-audio',
      audio: { muted: true, volume: 5 },
    });
    const background = reduceMemeEditProject(audio, {
      type: 'set-background',
      background: {
        mode: 'image',
        color: '#123456',
        assetUri: 'file:///background.jpg',
        blurScale: 4,
      },
    });
    const materialized = reduceMemeEditProject(background, {
      type: 'set-materialized-source-uri',
      uri: 'file:///cache/source.mp4',
    });
    const persistent = reduceMemeEditProject(materialized, {
      type: 'add-mask-track',
      track: maskTrack('track', { startUs: 0, endUs: videoSource.durationUs }),
    });
    const tracked = reduceMemeEditProject(persistent, {
      type: 'set-mask-track-uri',
      trackId: 'track',
      uri: 'file:///cache/mask.track',
    });
    const untracked = reduceMemeEditProject(tracked, {
      type: 'remove-mask-track-uri',
      trackId: 'track',
    });

    expect(initial.base.rotation).toBe(0);
    expect(based.base).toEqual({
      ...initial.base,
      rotation: 90,
      crop: { x: 0.8, y: 0, width: 0.2, height: 1 },
    });
    expect(ranged.video!.retainedRanges).toEqual([
      { startUs: 0, endUs: 2 * SECOND_US },
      { startUs: 8 * SECOND_US, endUs: 10 * SECOND_US },
    ]);
    expect(sped.video!.speed).toBe(2);
    expect(audio.video!.audio).toEqual({ muted: true, volume: 2 });
    expect(background.background).toEqual({
      mode: 'image',
      color: '#123456',
      assetUri: 'file:///background.jpg',
      blurScale: 1,
    });
    expect(materialized.transient.materializedSourceUri).toBe('file:///cache/source.mp4');
    expect(tracked.transient.maskTracks).toEqual({ track: 'file:///cache/mask.track' });
    expect(untracked.transient.maskTracks).toEqual({});
    expect(initial.transient).toEqual({ maskTracks: {}, materializedSourceUri: null });
  });

  test('normalizes timed active ranges and sparse keyframes through explicit actions', () => {
    const initial = createDefaultVideoProject(videoSource);
    const added = reduceMemeEditProject(initial, {
      type: 'add-layer',
      layer: textLayer('timed', { startUs: 0, endUs: SECOND_US }),
    });
    const active = reduceMemeEditProject(added, {
      type: 'set-layer-active-range',
      id: 'timed',
      active: { startUs: -100, endUs: 20 * SECOND_US },
    });
    const keyed = reduceMemeEditProject(active, {
      type: 'set-layer-keyframes',
      id: 'timed',
      keyframes: [
        keyframe(9 * SECOND_US, { center: { x: 2, y: -1 }, opacity: 2 }),
        keyframe(SECOND_US),
        keyframe(SECOND_US, { scale: 2 }),
      ],
    });

    expect(requireTextLayer(active.layers[0]).active).toEqual({
      startUs: 0,
      endUs: 10 * SECOND_US,
    });
    expect(requireTextLayer(keyed.layers[0]).keyframes).toEqual([
      keyframe(SECOND_US, { scale: 2 }),
      keyframe(9 * SECOND_US, { center: { x: 1, y: 0 }, opacity: 1 }),
    ]);
    expect(requireTextLayer(added.layers[0]).keyframes).toEqual([keyframe(0)]);
  });
  test('normalizes bounded text style and layout updates without mutating input', () => {
    const initial = createDefaultImageProject(imageSource);
    const wild = textLayer('wild');
    wild.width = 2;
    wild.fontSize = 0.5;
    wild.style = {
      ...wild.style,
      outlineScale: 9,
      opacity: -1,
      backgroundColor: '#0a0b0e',
    };

    const added = reduceMemeEditProject(initial, { type: 'add-layer', layer: wild });
    const normalized = requireTextLayer(added.layers[0]);

    expect(normalized.width).toBe(1);
    expect(normalized.fontSize).toBe(0.2);
    expect(normalized.style.outlineScale).toBe(1);
    expect(normalized.style.opacity).toBe(0);
    expect(wild.width).toBe(2);
    expect(wild.fontSize).toBe(0.5);
  });
  test('keeps background reducer output validator-valid for every mode', () => {
    const project = createDefaultImageProject(imageSource);
    const sourceBackground = reduceMemeEditProject(project, {
      type: 'set-background',
      background: {
        mode: 'source',
        color: '#000000',
        assetUri: 'file:///must-be-cleared.jpg',
        blurScale: 0,
      },
    });
    expect(sourceBackground.background.assetUri).toBeNull();
    expect(validateMemeEditProject(sourceBackground).ok).toBe(true);
    const nonAssetModes = ['solid', 'blurred-source', 'transparent'] as const;
    for (const mode of nonAssetModes) {
      const reduced = reduceMemeEditProject(project, {
        type: 'set-background',
        background: {
          mode,
          color: '#000000',
          assetUri: 'file:///must-also-be-cleared.jpg',
          blurScale: 0.5,
        },
      });
      expect(reduced.background.assetUri).toBeNull();
      expect(validateMemeEditProject(reduced).ok).toBe(true);
    }

    const assetModes = ['image', 'video'] as const;
    for (const mode of assetModes) {
      const reduced = reduceMemeEditProject(project, {
        type: 'set-background',
        background: {
          mode,
          color: '#000000',
          assetUri: `file:///background.${mode === 'image' ? 'jpg' : 'mp4'}`,
          blurScale: 0,
        },
      });
      expect(validateMemeEditProject(reduced).ok).toBe(true);
    }

    const missingAsset = reduceMemeEditProject(project, {
      type: 'set-background',
      background: {
        mode: 'image',
        color: '#000000',
        assetUri: null,
        blurScale: 0,
      },
    });
    expect(missingAsset).toBe(project);
    expect(validateMemeEditProject(missingAsset).ok).toBe(true);
  });

  test('rejects reducer range and keyframe collections above their post-normalization limits', () => {
    const project = createDefaultVideoProject(videoSource);
    const retainedRanges = Array.from(
      { length: PROJECT_LIMITS.maxRetainedRanges + 1 },
      (_, index) => ({ startUs: index * 2, endUs: index * 2 + 1 })
    );
    expect(() =>
      reduceMemeEditProject(project, {
        type: 'set-video-retained-ranges',
        retainedRanges,
      })
    ).toThrow(RangeError);

    const added = reduceMemeEditProject(project, {
      type: 'add-layer',
      layer: textLayer('bounded', { startUs: 0, endUs: SECOND_US }),
    });
    const keyframes = Array.from(
      { length: PROJECT_LIMITS.maxKeyframesPerLayer + 1 },
      (_, index) => keyframe(index)
    );
    expect(() =>
      reduceMemeEditProject(added, {
        type: 'set-layer-keyframes',
        id: 'bounded',
        keyframes,
      })
    ).toThrow(RangeError);
  });

  test('adds, updates, and removes persistent sparse mask correction tracks immutably', () => {
    const project = createDefaultImageProject(imageSource);
    const originalTrack = maskTrack('object-track');
    const added = reduceMemeEditProject(project, {
      type: 'add-mask-track',
      track: originalTrack,
    });
    const correctedTrack: MaskTrackSpec = {
      ...originalTrack,
      corrections: [
        ...originalTrack.corrections,
        {
          timeUs: 0,
          rect: { x: 0.2, y: 0.2, width: 0.2, height: 0.2 },
          easing: 'hold',
        },
      ],
    };
    const updated = reduceMemeEditProject(added, {
      type: 'update-mask-track',
      track: correctedTrack,
    });
    const removed = reduceMemeEditProject(updated, {
      type: 'remove-mask-track',
      trackId: 'object-track',
    });

    expect(project.maskTracks).toEqual([]);
    expect(added.maskTracks).toEqual([originalTrack]);
    expect(updated.maskTracks[0].corrections).toHaveLength(1);
    expect(removed.maskTracks).toEqual([]);
    expect(validateMemeEditProject(updated).ok).toBe(true);
  });

  test('refuses action strings that would make the in-memory project unbounded', () => {
    const project = createDefaultImageProject(imageSource);
    const oversized = 'x'.repeat(PROJECT_LIMITS.maxAssetUriLength + 1);
    const media: MediaOverlayLayer = {
      id: 'media',
      kind: 'media',
      assetUri: oversized,
      assetKind: 'image',
      fit: 'contain',
      targetMaskTrackId: null,
      active: null,
      keyframes: [keyframe()],
    };

    expect(() =>
      reduceMemeEditProject(project, { type: 'add-layer', layer: media })
    ).toThrow(RangeError);
    expect(() =>
      reduceMemeEditProject(project, {
        type: 'set-background',
        background: {
          mode: 'image',
          color: '#000000',
          assetUri: oversized,
          blurScale: 0,
        },
      })
    ).toThrow(RangeError);
    expect(() =>
      reduceMemeEditProject(project, {
        type: 'set-materialized-source-uri',
        uri: oversized,
      })
    ).toThrow(RangeError);
  });

});

describe('bounded project history', () => {
  test('undoes and redoes immutable edits and clears redo after a new edit', () => {
    const initial = createDefaultImageProject(imageSource);
    let history = createProjectHistory(initial);
    history = applyProjectAction(history, {
      type: 'set-background',
      background: { ...initial.background, color: '#111111' },
    });
    history = applyProjectAction(history, {
      type: 'set-background',
      background: { ...initial.background, color: '#222222' },
    });

    history = undoProjectHistory(history);
    expect(history.present.background.color).toBe('#111111');
    history = redoProjectHistory(history);
    expect(history.present.background.color).toBe('#222222');
    history = undoProjectHistory(history);
    history = applyProjectAction(history, {
      type: 'set-background',
      background: { ...initial.background, color: '#333333' },
    });
    expect(history.future).toEqual([]);
    expect(redoProjectHistory(history)).toBe(history);
  });

  test('coalesces many gesture or trim updates into one committed history entry', () => {
    const initial = createDefaultVideoProject(videoSource);
    let history = beginProjectTransaction(createProjectHistory(initial));
    for (let index = 1; index <= 100; index += 1) {
      history = applyProjectAction(history, {
        type: 'set-video-retained-ranges',
        retainedRanges: [{ startUs: index, endUs: videoSource.durationUs }],
      });
    }
    expect(history.past).toHaveLength(0);

    history = commitProjectTransaction(history);
    expect(history.past).toHaveLength(1);
    expect(history.present.video!.retainedRanges).toEqual([
      { startUs: 100, endUs: videoSource.durationUs },
    ]);
    history = undoProjectHistory(history);
    expect(history.present).toBe(initial);
  });

  test('cancels a transaction without creating history', () => {
    const initial = createDefaultImageProject(imageSource);
    let history = beginProjectTransaction(createProjectHistory(initial));
    history = applyProjectAction(history, {
      type: 'set-background',
      background: { ...initial.background, color: '#ffffff' },
    });
    history = cancelProjectTransaction(history);

    expect(history.present).toBe(initial);
    expect(history.past).toEqual([]);
    expect(history.future).toEqual([]);
  });

  test('commits net-zero transactions without adding undo or clearing redo', () => {
    const initial = createDefaultImageProject(imageSource);
    let emptyHistory = beginProjectTransaction(createProjectHistory(initial));
    emptyHistory = applyProjectAction(emptyHistory, {
      type: 'set-background',
      background: { ...initial.background, color: '#ffffff' },
    });
    emptyHistory = applyProjectAction(emptyHistory, {
      type: 'set-background',
      background: { ...initial.background },
    });
    emptyHistory = commitProjectTransaction(emptyHistory);
    expect(emptyHistory.present).toBe(initial);
    expect(emptyHistory.past).toEqual([]);

    let history = createProjectHistory(initial);
    history = applyProjectAction(history, {
      type: 'set-background',
      background: { ...initial.background, color: '#111111' },
    });
    history = applyProjectAction(history, {
      type: 'set-background',
      background: { ...initial.background, color: '#222222' },
    });
    history = undoProjectHistory(history);
    const beforeTransaction = history;
    history = beginProjectTransaction(history);
    history = applyProjectAction(history, {
      type: 'set-background',
      background: { ...initial.background, color: '#temporary' },
    });
    history = applyProjectAction(history, {
      type: 'set-background',
      background: { ...initial.background, color: '#111111' },
    });
    history = commitProjectTransaction(history);

    expect(history.present).toBe(beforeTransaction.present);
    expect(history.past).toEqual(beforeTransaction.past);
    expect(history.future).toEqual(beforeTransaction.future);
  });

  test('caps undo history at 30 states', () => {
    const initial = createDefaultImageProject(imageSource);
    let history = createProjectHistory(initial);
    for (let index = 1; index <= 35; index += 1) {
      history = applyProjectAction(history, {
        type: 'set-background',
        background: { ...initial.background, color: `color-${index}` },
      });
    }

    expect(MAX_HISTORY_STATES).toBe(30);
    expect(history.past).toHaveLength(30);
    for (let index = 0; index < MAX_HISTORY_STATES; index += 1) {
      history = undoProjectHistory(history);
    }
    expect(history.present.background.color).toBe('color-5');
    expect(undoProjectHistory(history)).toBe(history);
  });
});

describe('atomic image geometry and multi-layer actions', () => {
  test('accepts free crop geometry and replaces image geometry in one immutable action', () => {
    const project = createDefaultImageProject(imageSource);
    const text = textLayer('mapped');
    const next = reduceMemeEditProject(project, {
      type: 'set-image-geometry',
      base: {
        rotation: 90,
        flipX: true,
        flipY: false,
        crop: { x: 0.1, y: 0.2, width: 0.7, height: 0.6 },
        outputAspect: 'free',
      },
      layers: [text],
      maskTracks: [],
    });

    expect(next).not.toBe(project);
    expect(next.base.outputAspect).toBe('free');
    expect(next.layers).toEqual([text]);
    expect(project.layers).toEqual([]);
    expect(validateMemeEditProject(next)).toEqual({ ok: true, value: next });
  });

  test('enforces a finite minimum crop area at the reducer boundary', () => {
    const project = createDefaultImageProject(imageSource);
    const next = reduceMemeEditProject(project, {
      type: 'set-image-geometry',
      base: {
        ...project.base,
        crop: { x: Number.NaN, y: 0.99, width: 0.0001, height: 0.0001 },
        outputAspect: 'free',
      },
      layers: [],
      maskTracks: [],
    });

    expect(Object.values(next.base.crop).every(Number.isFinite)).toBe(true);
    expect(next.base.crop.width * next.base.crop.height).toBeGreaterThanOrEqual(0.0025);
    expect(next.base.crop.x + next.base.crop.width).toBeLessThanOrEqual(1);
    expect(next.base.crop.y + next.base.crop.height).toBeLessThanOrEqual(1);
  });

  test('ignores image geometry actions for video projects', () => {
    const project = createDefaultVideoProject(videoSource);

    expect(reduceMemeEditProject(project, {
      type: 'set-image-geometry',
      base: { ...project.base, rotation: 90 },
      layers: [],
      maskTracks: [],
    })).toBe(project);
  });

  test('adds caller-ID layers atomically and enforces the project layer limit before mutation', () => {
    const project = createDefaultImageProject(imageSource);
    const first = textLayer('first');
    const second = textLayer('second');
    const added = reduceMemeEditProject(project, { type: 'add-layers', layers: [first, second] });

    expect(added.layers.map((layer) => layer.id)).toEqual(['first', 'second']);
    expect(project.layers).toEqual([]);

    const full = { ...project, layers: Array.from({ length: PROJECT_LIMITS.maxLayers - 1 }, (_, index) => textLayer(`existing-${index}`)) };
    expect(() => reduceMemeEditProject(full, {
      type: 'add-layers',
      layers: [textLayer('overflow-a'), textLayer('overflow-b')],
    })).toThrow(/64 layer/);
    expect(full.layers).toHaveLength(PROJECT_LIMITS.maxLayers - 1);
  });

  test('rejects duplicate IDs inside a multi-layer action without partial insertion', () => {
    const project = createDefaultImageProject(imageSource);

    expect(() => reduceMemeEditProject(project, {
      type: 'add-layers',
      layers: [textLayer('duplicate'), textLayer('duplicate')],
    })).toThrow(/duplicate/i);
    expect(project.layers).toEqual([]);
  });
});
