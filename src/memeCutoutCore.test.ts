import {
  CUTOUT_DECODE_CEILING_BYTES,
  CUTOUT_FAILURE_CODES,
  CUTOUT_MAX_WORKING_EDGE,
  CUTOUT_MEMORY_CEILING_BYTES,
  CUTOUT_RECOMMENDED_MIN_EDGE,
  MAX_CUTOUT_LAYERS,
  type CutoutEvent,
  type CutoutRef,
  type CutoutRequest,
  type CutoutResult,
  type CutoutState,
  abandonedCutoutRequestIds,
  activeCutoutRequestId,
  type CutoutApplicationInput,
  type CutoutApplicationOutcome,
  buildCutoutApplication,
  classifyCutoutFailure,
  cutoutBackgroundSpec,
  cutoutCancellable,
  cutoutDecodeBytes,
  cutoutFailureFor,
  cutoutResultFromNative,
  cutoutSelectionOptions,
  cutoutStatusLabel,
  drainCutoutOrphans,
  initialCutoutState,
  isCutoutBusy,
  memeCutoutReducer,
  normalizeCutoutSticker,
  orphanedMaskTrackIds,
  planSubjectSegmentation,
  selectedCutoutRef,
} from './memeCutoutCore';
import {
  PROJECT_LIMITS,
  createDefaultImageProject,
  createDefaultVideoProject,
  validateMemeEditProject,
  type MemeEditProject,
  type SubjectLayer,
} from './memeEditProjectCore';
import type { NativeSubjectCutout, NativeSubjectCutoutResult } from '../modules/memeget-bg';

function imageProject(): MemeEditProject {
  return createDefaultImageProject({
    uri: 'file:///photos/dog.jpg',
    name: 'dog.jpg',
    width: 4032,
    height: 3024,
  });
}

function nativeCutout(overrides: Partial<NativeSubjectCutout> = {}): NativeSubjectCutout {
  return {
    id: 'req-1-subject-0',
    subjectIndex: 0,
    cutoutUri: 'file:///cache/meme_work_cutout/req-1/req-1-subject-0.png',
    bounds: { x: 0.2, y: 0.1, width: 0.4, height: 0.6 },
    widthPx: 800,
    heightPx: 1200,
    coverage: 0.24,
    bytes: 240_000,
    ...overrides,
  };
}

function nativeResult(
  overrides: Partial<NativeSubjectCutoutResult> = {}
): NativeSubjectCutoutResult {
  return {
    requestId: 'req-1',
    sourceWidth: 4032,
    sourceHeight: 3024,
    workingWidth: 2016,
    workingHeight: 1512,
    sampleSize: 2,
    estimatedPeakBytes: 2016 * 1512 * 12,
    ceilingBytes: CUTOUT_MEMORY_CEILING_BYTES,
    directory: 'file:///cache/meme_work_cutout/req-1',
    combined: nativeCutout({
      id: 'req-1-combined',
      subjectIndex: null,
      cutoutUri: 'file:///cache/meme_work_cutout/req-1/req-1-combined.png',
      bounds: { x: 0.1, y: 0.05, width: 0.7, height: 0.8 },
      coverage: 0.4,
    }),
    subjects: [nativeCutout()],
    droppedSubjects: 0,
    ...overrides,
  };
}

function result(overrides: Partial<NativeSubjectCutoutResult> = {}): CutoutResult {
  const adopted = cutoutResultFromNative(nativeResult(overrides), 'file:///photos/dog.jpg');
  if (!adopted) throw new Error('fixture produced an empty result');
  return adopted;
}

function request(requestId = 'req-1'): CutoutRequest {
  return {
    sourceUri: 'file:///photos/dog.jpg',
    requestId,
    plan: planSubjectSegmentation({ width: 4032, height: 3024 }),
  };
}

function run(state: CutoutState, ...events: CutoutEvent[]): CutoutState {
  return events.reduce(memeCutoutReducer, state);
}

/** Start a run and hand back its runId, which every later event must carry. */
function started(requestId = 'req-1') {
  const state = run(initialCutoutState(), { type: 'start', request: request(requestId) });
  if (state.phase.kind !== 'preparing') throw new Error('expected preparing');
  return { state, runId: state.phase.runId };
}

describe('segmentation plan', () => {
  it('keeps the working size inside the stated memory ceiling', () => {
    // 12 000 x 9 000 = 108 MP: 1.3 GB of bitmap+mask+cutout if segmented whole.
    const plan = planSubjectSegmentation({ width: 12_000, height: 9_000 });
    expect(plan.estimatedPeakBytes).toBeLessThanOrEqual(CUTOUT_MEMORY_CEILING_BYTES);
    expect(Math.max(plan.workingWidth, plan.workingHeight)).toBeLessThanOrEqual(
      CUTOUT_MAX_WORKING_EDGE
    );
    expect(plan.downscaled).toBe(true);
  });

  it('keeps the edge cap under the memory ceiling on its own', () => {
    // At default settings the EDGE cap is what binds — a square working frame at
    // the cap costs well under the ceiling. Raising the cap without raising the
    // ceiling is exactly the change that reopens the OOM, so pin the relation.
    const squareAtCap = CUTOUT_MAX_WORKING_EDGE * CUTOUT_MAX_WORKING_EDGE * 12;
    expect(squareAtCap).toBeLessThanOrEqual(CUTOUT_MEMORY_CEILING_BYTES);
  });

  it('only ever picks a power-of-two sample size', () => {
    for (const width of [640, 1000, 1920, 4032, 8000, 12_000]) {
      const plan = planSubjectSegmentation({ width, height: Math.round(width * 0.75) });
      expect(Math.log2(plan.sampleSize) % 1).toBe(0);
      expect(plan.workingWidth).toBe(Math.max(1, Math.floor(width / plan.sampleSize)));
    }
  });

  it('leaves a source that already fits alone', () => {
    const plan = planSubjectSegmentation({ width: 1600, height: 1200 });
    expect(plan.sampleSize).toBe(1);
    expect(plan.downscaled).toBe(false);
    expect(plan.workingWidth).toBe(1600);
  });

  it('flags a source below the documented accuracy floor without upscaling it', () => {
    const plan = planSubjectSegmentation({ width: 400, height: 300 });
    expect(plan.belowRecommendedResolution).toBe(true);
    expect(plan.workingWidth).toBe(400);
    expect(
      planSubjectSegmentation({
        width: CUTOUT_RECOMMENDED_MIN_EDGE,
        height: CUTOUT_RECOMMENDED_MIN_EDGE,
      }).belowRecommendedResolution
    ).toBe(false);
  });

  it('honours a tighter ceiling by halving further', () => {
    const tight = planSubjectSegmentation({ width: 2048, height: 2048 }, { ceilingBytes: 8_000_000 });
    expect(tight.estimatedPeakBytes).toBeLessThanOrEqual(8_000_000);
    expect(tight.sampleSize).toBeGreaterThan(1);
  });

  it('refuses dimensions it cannot plan for', () => {
    expect(() => planSubjectSegmentation({ width: 0, height: 100 })).toThrow(TypeError);
    expect(() => planSubjectSegmentation({ width: Number.NaN, height: 100 })).toThrow(TypeError);
  });
});

describe('failure classification', () => {
  it('separates offline, unavailable and a real failure', () => {
    expect(classifyCutoutFailure(CUTOUT_FAILURE_CODES.offline)).toBe('offline');
    expect(classifyCutoutFailure(CUTOUT_FAILURE_CODES.moduleUnavailable)).toBe(
      'module-unavailable'
    );
    expect(classifyCutoutFailure(CUTOUT_FAILURE_CODES.failed)).toBe('failed');
    expect(classifyCutoutFailure(CUTOUT_FAILURE_CODES.cancelled)).toBe('cancelled');
  });

  it('treats an unknown code as a real failure rather than guessing', () => {
    expect(classifyCutoutFailure('E_SOMETHING_NEW')).toBe('failed');
    expect(classifyCutoutFailure(undefined)).toBe('failed');
  });

  it('gives each failure its own remedy, because the fixes differ', () => {
    const offline = cutoutFailureFor(CUTOUT_FAILURE_CODES.offline);
    const unavailable = cutoutFailureFor(CUTOUT_FAILURE_CODES.moduleUnavailable);
    const failed = cutoutFailureFor(CUTOUT_FAILURE_CODES.failed, 'decode blew up');
    if (!offline || !unavailable || !failed) throw new Error('expected three failures');
    const remedies = [offline.remedy, unavailable.remedy, failed.remedy];
    expect(new Set(remedies).size).toBe(3);
    expect(new Set([offline.message, unavailable.message, failed.message]).size).toBe(3);
    // Retrying an unsupported device is pointless; the other two can work.
    expect(offline.retryable).toBe(true);
    expect(failed.retryable).toBe(true);
    expect(unavailable.retryable).toBe(false);
    expect(failed.detail).toBe('decode blew up');
  });

  it('does not turn a cancellation into a failure', () => {
    expect(cutoutFailureFor(CUTOUT_FAILURE_CODES.cancelled)).toBeNull();
  });
});

describe('adopting a native result', () => {
  it('reads an empty segmentation as no-subject, not as a failure', () => {
    expect(cutoutResultFromNative(nativeResult({ combined: null }), 'file:///a.jpg')).toBeNull();
  });

  it('drops a cutout whose geometry cannot place it', () => {
    const adopted = cutoutResultFromNative(
      nativeResult({
        subjects: [
          nativeCutout({ subjectIndex: 0, bounds: { x: 0.5, y: 0.5, width: 0, height: 0.2 } }),
          nativeCutout({ id: 'req-1-subject-1', subjectIndex: 1 }),
        ],
      }),
      'file:///a.jpg'
    );
    expect(adopted?.subjects.map((subject) => subject.subjectIndex)).toEqual([1]);
  });

  it('drops a cutout with no materialized file', () => {
    const adopted = cutoutResultFromNative(
      nativeResult({ subjects: [nativeCutout({ cutoutUri: '' })] }),
      'file:///a.jpg'
    );
    expect(adopted?.subjects).toEqual([]);
    expect(adopted?.combined.cutoutUri).toContain('req-1-combined.png');
  });

  it('clamps bounds and coverage that arrive out of range', () => {
    const adopted = result({
      combined: nativeCutout({
        id: 'req-1-combined',
        subjectIndex: null,
        bounds: { x: -0.5, y: 0.9, width: 3, height: 3 },
        coverage: 4,
      }),
    });
    expect(adopted.combined.bounds).toEqual({ x: 0, y: 0.9, width: 1, height: 0.1 });
    expect(adopted.combined.coverage).toBe(1);
  });

  it('reports the working size against the accuracy floor', () => {
    expect(result({ workingWidth: 480, workingHeight: 320 }).belowRecommendedResolution).toBe(true);
    expect(result().belowRecommendedResolution).toBe(false);
  });
});

describe('subject selection', () => {
  const twoSubjects = () =>
    result({
      subjects: [
        nativeCutout({ subjectIndex: 0, coverage: 0.12 }),
        nativeCutout({ id: 'req-1-subject-1', subjectIndex: 1, coverage: 0.31 }),
      ],
    });

  it('offers all-subjects plus one entry per subject, in source order', () => {
    const options = cutoutSelectionOptions(twoSubjects());
    expect(options.map((option) => option.label)).toEqual([
      'All subjects',
      'Subject 1',
      'Subject 2',
    ]);
    // Labels are positional, so the order must not follow coverage.
    expect(options.map((option) => option.coverage)).toEqual([0.4, 0.12, 0.31]);
  });

  it('does not offer all-subjects when there is exactly one subject', () => {
    expect(cutoutSelectionOptions(result()).map((option) => option.label)).toEqual(['Subject 1']);
  });

  it('resolves a selection to the cutout it names', () => {
    const two = twoSubjects();
    expect(selectedCutoutRef(two, { kind: 'all' })?.subjectIndex).toBeNull();
    expect(selectedCutoutRef(two, { kind: 'subject', index: 1 })?.id).toBe('req-1-subject-1');
    expect(selectedCutoutRef(two, { kind: 'subject', index: 7 })).toBeNull();
  });
});

describe('backgrounds', () => {
  it('maps each choice onto a project background the validator accepts', () => {
    expect(cutoutBackgroundSpec({ mode: 'transparent' })).toEqual({
      mode: 'transparent',
      color: '#000000',
      assetUri: null,
      blurScale: 0,
    });
    expect(cutoutBackgroundSpec({ mode: 'solid', color: '#B8FF2C' }).color).toBe('#B8FF2C');
    expect(cutoutBackgroundSpec({ mode: 'blurred-source', blurScale: 0.8 }).blurScale).toBe(0.8);
    expect(cutoutBackgroundSpec({ mode: 'image', assetUri: 'file:///bg.png' }).assetUri).toBe(
      'file:///bg.png'
    );
  });

  it('never carries a value onto a mode that must not have it', () => {
    // The project validator rejects an asset on a non-asset mode, so a stale
    // uri here would only surface as a validation failure on the next save.
    const blurred = cutoutBackgroundSpec({
      mode: 'blurred-source',
      assetUri: 'file:///stale.png',
      blurScale: 0.5,
    });
    expect(blurred.assetUri).toBeNull();
    const solid = cutoutBackgroundSpec({ mode: 'solid', blurScale: 0.9 });
    expect(solid.blurScale).toBe(0);
    expect(cutoutBackgroundSpec({ mode: 'image', assetUri: 'file:///bg.png', blurScale: 0.9 }).blurScale).toBe(0);
  });

  it('clamps a blur value outside the unit range', () => {
    expect(cutoutBackgroundSpec({ mode: 'blurred-source', blurScale: 4 }).blurScale).toBe(1);
    expect(cutoutBackgroundSpec({ mode: 'blurred-source', blurScale: -1 }).blurScale).toBe(0);
  });
});

describe('sticker parameters', () => {
  it('clamps the scales the project validator requires to be unit numbers', () => {
    const sticker = normalizeCutoutSticker({ outlineScale: 5, shadowScale: -2 });
    expect(sticker.outlineScale).toBe(1);
    expect(sticker.shadowScale).toBe(0);
  });

  it('keeps a non-finite slider value from reaching the project', () => {
    const sticker = normalizeCutoutSticker({
      outlineScale: Number.NaN,
      duplicateOffset: { x: Number.POSITIVE_INFINITY, y: 5 },
    });
    expect(sticker.outlineScale).toBe(0.35);
    expect(sticker.duplicateOffset.x).toBe(0.04);
    expect(sticker.duplicateOffset.y).toBe(1);
  });
});

describe('applying a cutout to a project', () => {
  function apply(
    overrides: Partial<CutoutApplicationInput> = {}
  ): CutoutApplicationOutcome {
    return buildCutoutApplication({
      project: imageProject(),
      result: result(),
      selection: { kind: 'all' },
      background: { mode: 'transparent' },
      idPrefix: 'c1',
      ...overrides,
    });
  }

  it('produces project parts that pass real project validation', () => {
    const outcome = apply({ background: { mode: 'solid', color: '#101010' } });
    if (!outcome.ok) throw new Error(`refused: ${outcome.reason}`);
    const base = imageProject();
    const project: MemeEditProject = {
      ...base,
      layers: [...base.layers, ...outcome.application.layers],
      maskTracks: [...base.maskTracks, ...outcome.application.maskTracks],
      background: outcome.application.background,
      transient: {
        ...base.transient,
        maskTracks: {
          ...base.transient.maskTracks,
          ...outcome.application.transientMaskTracks,
        },
      },
    };
    expect(validateMemeEditProject(project)).toEqual({ ok: true, value: project });
  });

  it('centres the cutout on the bounds it was found in', () => {
    const outcome = apply();
    if (!outcome.ok) throw new Error('refused');
    // combined fixture bounds: x 0.1 w 0.7 -> 0.45; y 0.05 h 0.8 -> 0.45
    expect(outcome.application.layers[0].keyframes[0].center).toEqual({ x: 0.45, y: 0.45 });
    expect(outcome.application.layers[0].keyframes[0].scale).toBe(1);
    expect(outcome.application.maskTracks[0].corrections[0].rect).toEqual(
      result().combined.bounds
    );
  });

  it('remaps the cutout into the cropped frame the user is looking at', () => {
    // Native segmented the WHOLE source; this project shows its right half. A
    // cutout at source x 0.1..0.8 has to land at 0..0.6 of the visible frame,
    // not stay where the full-frame coordinates put it.
    const base = imageProject();
    const cropped: MemeEditProject = {
      ...base,
      base: { ...base.base, crop: { x: 0.5, y: 0, width: 0.5, height: 1 } },
    };
    const outcome = apply({ project: cropped });
    if (!outcome.ok) throw new Error(`refused: ${outcome.reason}`);
    const rect = outcome.application.maskTracks[0].corrections[0].rect;
    expect(rect.x).toBe(0);
    expect(rect.width).toBeCloseTo(0.6, 6);
    expect(outcome.application.layers[0].keyframes[0].center.x).toBeCloseTo(0.3, 6);
    // The un-remapped source rect would have been x 0.1 — pin that it moved.
    expect(rect).not.toEqual(result().combined.bounds);
  });

  it('remaps through a quarter rotation as well as a crop', () => {
    const base = imageProject();
    const rotated: MemeEditProject = { ...base, base: { ...base.base, rotation: 90 } };
    const outcome = apply({ project: rotated });
    if (!outcome.ok) throw new Error(`refused: ${outcome.reason}`);
    const rect = outcome.application.maskTracks[0].corrections[0].rect;
    // Rotating the frame swaps the axes: the source's 0.7-wide box is now tall.
    expect(rect.height).toBeCloseTo(0.7, 6);
    expect(rect.width).toBeCloseTo(0.8, 6);
  });

  it('refuses a subject the crop has cut away instead of hiding a dead layer', () => {
    const base = imageProject();
    const cropped: MemeEditProject = {
      ...base,
      base: { ...base.base, crop: { x: 0.9, y: 0.9, width: 0.1, height: 0.1 } },
    };
    const outcome = apply({
      project: cropped,
      result: result({
        combined: nativeCutout({
          id: 'req-1-combined',
          subjectIndex: null,
          bounds: { x: 0, y: 0, width: 0.2, height: 0.2 },
        }),
      }),
    });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.reason).toBe('outside-crop');
  });

  it('points the mask track at the materialized cutout file', () => {
    const outcome = apply();
    if (!outcome.ok) throw new Error('refused');
    const trackId = outcome.application.maskTracks[0].id;
    expect(outcome.application.transientMaskTracks[trackId]).toBe(result().combined.cutoutUri);
    expect(outcome.application.layers[0].maskTrackId).toBe(trackId);
  });

  it('records the chosen subject index so a re-run can match it', () => {
    const outcome = apply({ selection: { kind: 'subject', index: 0 } });
    if (!outcome.ok) throw new Error('refused');
    expect(outcome.application.layers[0].subjectIndex).toBe(0);
    expect(outcome.application.layers[0].keyframes[0].center).toEqual({ x: 0.4, y: 0.4 });
  });

  it('draws the duplicate behind the subject and offsets it', () => {
    const outcome = apply({ sticker: { duplicate: true, duplicateOffset: { x: 0.05, y: 0.03 } } });
    if (!outcome.ok) throw new Error('refused');
    const [behind, front] = outcome.application.layers;
    expect(outcome.application.layers).toHaveLength(2);
    // Order IS the effect: an offset copy in front would hide the subject.
    expect(behind.keyframes[0].center).toEqual({ x: 0.5, y: 0.48 });
    expect(front.keyframes[0].center).toEqual({ x: 0.45, y: 0.45 });
    // One mask, two layers: the duplicate costs no extra bitmap.
    expect(behind.maskTrackId).toBe(front.maskTrackId);
    expect(outcome.application.maskTracks).toHaveLength(1);
  });

  it('keeps one shadow per stack while both copies keep the outline', () => {
    const outcome = apply({
      sticker: { duplicate: true, outline: true, outlineColor: '#FF4E42', shadow: true },
    });
    if (!outcome.ok) throw new Error('refused');
    const [behind, front] = outcome.application.layers;
    expect(front.shadowScale).toBeGreaterThan(0);
    expect(behind.shadowScale).toBe(0);
    expect(behind.outlineColor).toBe('#FF4E42');
    expect(front.outlineColor).toBe('#FF4E42');
  });

  it('leaves outline and shadow off when they were not asked for', () => {
    const outcome = apply();
    if (!outcome.ok) throw new Error('refused');
    expect(outcome.application.layers[0].outlineColor).toBeNull();
    expect(outcome.application.layers[0].outlineScale).toBe(0);
    expect(outcome.application.layers[0].shadowScale).toBe(0);
  });

  it('does not collide with ids the project already holds', () => {
    const base = imageProject();
    const existing: SubjectLayer = {
      id: 'c1-cutout',
      kind: 'subject',
      subjectIndex: null,
      maskTrackId: 'c1-mask',
      active: null,
      keyframes: [
        { timeUs: 0, center: { x: 0.5, y: 0.5 }, scale: 1, rotationDegrees: 0, opacity: 1, easing: 'hold' },
      ],
      outlineColor: null,
      outlineScale: 0,
      shadowScale: 0,
    };
    const project: MemeEditProject = {
      ...base,
      layers: [existing],
      maskTracks: [{ id: 'c1-mask', active: null, corrections: [{ timeUs: 0, rect: { x: 0, y: 0, width: 1, height: 1 }, easing: 'hold' }] }],
    };
    const outcome = apply({ project });
    if (!outcome.ok) throw new Error('refused');
    expect(outcome.application.layers[0].id).not.toBe('c1-cutout');
    expect(outcome.application.maskTracks[0].id).not.toBe('c1-mask');
  });

  it('refuses a replacement background with no image chosen', () => {
    const outcome = apply({ background: { mode: 'image', assetUri: null } });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.reason).toBe('background-asset-missing');
    expect(outcome.message.length).toBeGreaterThan(0);
  });

  it('refuses a selection whose cutout is gone', () => {
    const outcome = apply({ selection: { kind: 'subject', index: 9 } });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.reason).toBe('no-cutout');
  });

  it('refuses a cutout too large to decode at export', () => {
    const huge = result({
      combined: nativeCutout({
        id: 'req-1-combined',
        subjectIndex: null,
        widthPx: 6000,
        heightPx: 6000,
      }),
    });
    expect(cutoutDecodeBytes(huge.combined)).toBeGreaterThan(CUTOUT_DECODE_CEILING_BYTES);
    const outcome = apply({ result: huge });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.reason).toBe('memory-ceiling');
    // The message has to name the size, or the user cannot act on it.
    expect(outcome.message).toMatch(/MB/);
  });

  it('accepts a cutout that fits the decode ceiling exactly', () => {
    const edge = Math.sqrt(CUTOUT_DECODE_CEILING_BYTES / 4);
    const fitting = result({
      combined: nativeCutout({
        id: 'req-1-combined',
        subjectIndex: null,
        widthPx: Math.floor(edge),
        heightPx: Math.floor(edge),
      }),
    });
    expect(apply({ result: fitting }).ok).toBe(true);
  });

  it('refuses once the project holds the maximum number of cutouts', () => {
    const base = imageProject();
    const project: MemeEditProject = {
      ...base,
      layers: Array.from({ length: MAX_CUTOUT_LAYERS }, (_unused, index) => ({
        id: `existing-cutout-${index}`,
        kind: 'subject' as const,
        subjectIndex: null,
        maskTrackId: `existing-mask-${index}`,
        active: null,
        keyframes: [
          { timeUs: 0, center: { x: 0.5, y: 0.5 }, scale: 1, rotationDegrees: 0, opacity: 1, easing: 'hold' as const },
        ],
        outlineColor: null,
        outlineScale: 0,
        shadowScale: 0,
      })),
      maskTracks: Array.from({ length: MAX_CUTOUT_LAYERS }, (_unused, index) => ({
        id: `existing-mask-${index}`,
        active: null,
        corrections: [{ timeUs: 0, rect: { x: 0, y: 0, width: 1, height: 1 }, easing: 'hold' as const }],
      })),
    };
    const outcome = apply({ project });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.reason).toBe('cutout-layer-limit');
  });

  it('counts the duplicate against the cutout limit', () => {
    const base = imageProject();
    const project: MemeEditProject = {
      ...base,
      layers: Array.from({ length: MAX_CUTOUT_LAYERS - 1 }, (_unused, index) => ({
        id: `existing-cutout-${index}`,
        kind: 'subject' as const,
        subjectIndex: null,
        maskTrackId: `existing-mask-${index}`,
        active: null,
        keyframes: [
          { timeUs: 0, center: { x: 0.5, y: 0.5 }, scale: 1, rotationDegrees: 0, opacity: 1, easing: 'hold' as const },
        ],
        outlineColor: null,
        outlineScale: 0,
        shadowScale: 0,
      })),
      maskTracks: Array.from({ length: MAX_CUTOUT_LAYERS - 1 }, (_unused, index) => ({
        id: `existing-mask-${index}`,
        active: null,
        corrections: [{ timeUs: 0, rect: { x: 0, y: 0, width: 1, height: 1 }, easing: 'hold' as const }],
      })),
    };
    expect(apply({ project }).ok).toBe(true);
    const withDuplicate = apply({ project, sticker: { duplicate: true } });
    expect(withDuplicate.ok).toBe(false);
    if (withDuplicate.ok) return;
    expect(withDuplicate.reason).toBe('cutout-layer-limit');
  });

  it('refuses when the mask track limit is already reached', () => {
    const base = imageProject();
    const project: MemeEditProject = {
      ...base,
      maskTracks: Array.from({ length: PROJECT_LIMITS.maxMaskTracks }, (_unused, index) => ({
        id: `track-${index}`,
        active: null,
        corrections: [{ timeUs: 0, rect: { x: 0, y: 0, width: 1, height: 1 }, easing: 'hold' as const }],
      })),
    };
    const outcome = apply({ project });
    expect(outcome.ok).toBe(false);
    if (outcome.ok) return;
    expect(outcome.reason).toBe('mask-track-limit');
  });
});

describe('orphaned mask tracks', () => {
  it('finds tracks no layer references any more', () => {
    const base = imageProject();
    const project: MemeEditProject = {
      ...base,
      maskTracks: [
        { id: 'used', active: null, corrections: [{ timeUs: 0, rect: { x: 0, y: 0, width: 1, height: 1 }, easing: 'hold' }] },
        { id: 'dropped', active: null, corrections: [{ timeUs: 0, rect: { x: 0, y: 0, width: 1, height: 1 }, easing: 'hold' }] },
      ],
      layers: [
        {
          id: 'cut-1',
          kind: 'subject',
          subjectIndex: null,
          maskTrackId: 'used',
          active: null,
          keyframes: [
            { timeUs: 0, center: { x: 0.5, y: 0.5 }, scale: 1, rotationDegrees: 0, opacity: 1, easing: 'hold' },
          ],
          outlineColor: null,
          outlineScale: 0,
          shadowScale: 0,
        },
      ],
    };
    expect(orphanedMaskTrackIds(project)).toEqual(['dropped']);
  });

  it('counts a media layer aimed at a mask as a reference', () => {
    const base = createDefaultVideoProject({
      uri: 'file:///v.mp4',
      name: 'v.mp4',
      width: 1920,
      height: 1080,
      durationUs: 5_000_000,
    });
    const project: MemeEditProject = {
      ...base,
      maskTracks: [
        { id: 'target', active: null, corrections: [{ timeUs: 0, rect: { x: 0, y: 0, width: 1, height: 1 }, easing: 'hold' }] },
      ],
      layers: [
        {
          id: 'overlay',
          kind: 'media',
          assetUri: 'file:///bg.png',
          assetKind: 'image',
          fit: 'cover',
          targetMaskTrackId: 'target',
          active: null,
          keyframes: [
            { timeUs: 0, center: { x: 0.5, y: 0.5 }, scale: 1, rotationDegrees: 0, opacity: 1, easing: 'hold' },
          ],
        },
      ],
    };
    expect(orphanedMaskTrackIds(project)).toEqual([]);
  });
});

describe('request lifecycle', () => {
  it('moves from preparing through downloading to segmenting', () => {
    const { state, runId } = started();
    expect(isCutoutBusy(state)).toBe(true);
    const downloading = run(state, {
      type: 'downloadProgress',
      runId,
      bytesDownloaded: 512,
      totalBytes: 2048,
    });
    expect(downloading.phase.kind).toBe('downloading');
    if (downloading.phase.kind !== 'downloading') return;
    expect(downloading.phase.progress.fraction).toBe(0.25);
    const segmenting = run(downloading, { type: 'segmentStarted', runId });
    expect(segmenting.phase.kind).toBe('segmenting');
  });

  it('never walks the download bar backwards', () => {
    const { state, runId } = started();
    const progressed = run(
      state,
      { type: 'downloadProgress', runId, bytesDownloaded: 1500, totalBytes: 2048 },
      { type: 'downloadProgress', runId, bytesDownloaded: 900, totalBytes: 2048 }
    );
    if (progressed.phase.kind !== 'downloading') throw new Error('expected downloading');
    expect(progressed.phase.progress.bytesDownloaded).toBe(1500);
  });

  it('reports no fraction until Play services states a size', () => {
    const { state, runId } = started();
    const progressed = run(state, {
      type: 'downloadProgress',
      runId,
      bytesDownloaded: 0,
      totalBytes: 0,
    });
    if (progressed.phase.kind !== 'downloading') throw new Error('expected downloading');
    expect(progressed.phase.progress.fraction).toBeNull();
    expect(cutoutStatusLabel(progressed)).toBe('Downloading the cutout model (one time)…');
  });

  it('ignores progress from a run that was superseded', () => {
    const first = started('req-1');
    const second = run(first.state, { type: 'start', request: request('req-2') });
    const stale = run(second, {
      type: 'downloadProgress',
      runId: first.runId,
      bytesDownloaded: 999,
      totalBytes: 2048,
    });
    expect(stale.phase.kind).toBe('preparing');
    expect(activeCutoutRequestId(stale)).toBe('req-2');
  });

  it('keeps a superseded run from overwriting the current one, and orphans its files', () => {
    const first = started('req-1');
    const second = run(first.state, { type: 'start', request: request('req-2') });
    const late = run(second, {
      type: 'succeeded',
      runId: first.runId,
      result: result({ requestId: 'req-1' }),
    });
    expect(late.phase.kind).toBe('preparing');
    expect(late.orphans).toContain('req-1');
  });

  it('orphans the abandoned request when a new one starts', () => {
    const first = started('req-1');
    const second = run(first.state, { type: 'start', request: request('req-2') });
    expect(second.orphans).toEqual(['req-1']);
  });

  it('lands on ready with the adopted result', () => {
    const { state, runId } = started();
    const ready = run(state, { type: 'segmentStarted', runId }, { type: 'succeeded', runId, result: result() });
    expect(ready.phase.kind).toBe('ready');
    if (ready.phase.kind !== 'ready') return;
    expect(ready.phase.applied).toBe(false);
    expect(ready.orphans).toEqual([]);
    expect(cutoutStatusLabel(ready)).toBe('Found 1 subject');
  });

  it('calls an empty segmentation what it is, and cleans up after it', () => {
    const { state, runId } = started();
    const empty = run(state, { type: 'succeeded', runId, result: null });
    expect(empty.phase.kind).toBe('empty');
    expect(cutoutStatusLabel(empty)).toBe('No subject found in this image');
    // Nothing to keep, so the native directory must not be left behind.
    expect(empty.orphans).toEqual(['req-1']);
  });

  it('treats cancel as a request the run has to acknowledge', () => {
    const { state, runId } = started();
    const cancelling = run(state, { type: 'segmentStarted', runId }, { type: 'cancel' });
    expect(cancelling.phase.kind).toBe('segmenting');
    expect(cutoutCancellable(cancelling)).toBe(false);
    expect(cutoutStatusLabel(cancelling)).toBe('Cancelling…');
  });

  it('does not show a result the user cancelled', () => {
    const { state, runId } = started();
    const cancelled = run(
      state,
      { type: 'segmentStarted', runId },
      { type: 'cancel' },
      // Inference is not interruptible: the run can still succeed afterwards.
      { type: 'succeeded', runId, result: result() }
    );
    expect(cancelled.phase.kind).toBe('cancelled');
    expect(cancelled.orphans).toEqual(['req-1']);
  });

  it('maps a cancelled failure code to cancelled, not failed', () => {
    const { state, runId } = started();
    const cancelled = run(state, {
      type: 'failed',
      runId,
      code: CUTOUT_FAILURE_CODES.cancelled,
    });
    expect(cancelled.phase.kind).toBe('cancelled');
    expect(cancelled.orphans).toEqual(['req-1']);
  });

  it('surfaces a real failure with its remedy and cleans up', () => {
    const { state, runId } = started();
    const failed = run(state, {
      type: 'failed',
      runId,
      code: CUTOUT_FAILURE_CODES.offline,
      detail: 'network down',
    });
    if (failed.phase.kind !== 'failed') throw new Error('expected failed');
    expect(failed.phase.failure.kind).toBe('offline');
    expect(failed.phase.failure.retryable).toBe(true);
    expect(failed.orphans).toEqual(['req-1']);
  });

  it('ignores a failure from a superseded run', () => {
    const first = started('req-1');
    const second = run(first.state, { type: 'start', request: request('req-2') });
    const stale = run(second, {
      type: 'failed',
      runId: first.runId,
      code: CUTOUT_FAILURE_CODES.failed,
    });
    expect(stale.phase.kind).toBe('preparing');
  });

  it('stops treating an applied cutout as ours to delete', () => {
    const { state, runId } = started();
    const applied = run(
      state,
      { type: 'succeeded', runId, result: result() },
      { type: 'applied' }
    );
    const restarted = run(applied, { type: 'start', request: request('req-2') });
    // The project points at req-1's PNG now; deleting it would blank the layer.
    expect(restarted.orphans).toEqual([]);
    expect(abandonedCutoutRequestIds(restarted)).toEqual(['req-2']);
  });

  it('deletes a result nobody adopted', () => {
    const { state, runId } = started();
    const ready = run(state, { type: 'succeeded', runId, result: result() });
    expect(run(ready, { type: 'dismiss' }).orphans).toEqual(['req-1']);
    expect(run(ready, { type: 'start', request: request('req-2') }).orphans).toEqual(['req-1']);
    expect(abandonedCutoutRequestIds(ready)).toEqual(['req-1']);
  });

  it('keeps dismiss from interrupting a run', () => {
    const { state } = started();
    expect(run(state, { type: 'dismiss' }).phase.kind).toBe('preparing');
  });

  it('records a release request from a deleted layer exactly once', () => {
    const state = run(
      initialCutoutState(),
      { type: 'releaseRequested', requestId: 'req-9' },
      { type: 'releaseRequested', requestId: 'req-9' }
    );
    expect(state.orphans).toEqual(['req-9']);
  });

  it('reports the active request while it is still running', () => {
    const { state, runId } = started();
    expect(abandonedCutoutRequestIds(state)).toEqual(['req-1']);
    const ready = run(state, { type: 'succeeded', runId, result: result() });
    expect(activeCutoutRequestId(ready)).toBeNull();
  });
});

describe('draining orphans', () => {
  it('releases every orphan and clears the list', async () => {
    const released: string[] = [];
    const state: CutoutState = {
      phase: { kind: 'idle' },
      orphans: ['req-1', 'req-2'],
      nextRunId: 3,
    };
    const drained = await drainCutoutOrphans(state, async (requestId) => {
      released.push(requestId);
    });
    expect(released).toEqual(['req-1', 'req-2']);
    expect(drained.orphans).toEqual([]);
  });

  it('clears the list even when a delete fails, so it cannot grow forever', async () => {
    const state: CutoutState = { phase: { kind: 'idle' }, orphans: ['req-1'], nextRunId: 2 };
    const drained = await drainCutoutOrphans(state, async () => {
      throw new Error('file busy');
    });
    expect(drained.orphans).toEqual([]);
  });

  it('does not call the deleter when there is nothing to delete', async () => {
    const release = jest.fn();
    const state = initialCutoutState();
    expect(await drainCutoutOrphans(state, release)).toBe(state);
    expect(release).not.toHaveBeenCalled();
  });
});

describe('status labels', () => {
  it('counts the subjects it found', () => {
    const { state, runId } = started();
    const ready = run(state, {
      type: 'succeeded',
      runId,
      result: result({
        subjects: [nativeCutout({ subjectIndex: 0 }), nativeCutout({ id: 'b', subjectIndex: 1 })],
      }),
    });
    expect(cutoutStatusLabel(ready)).toBe('Found 2 subjects');
  });

  it('shows the download percentage once a size is known', () => {
    const { state, runId } = started();
    const downloading = run(state, {
      type: 'downloadProgress',
      runId,
      bytesDownloaded: 1024,
      totalBytes: 4096,
    });
    expect(cutoutStatusLabel(downloading)).toBe(
      'Downloading the cutout model (one time) — 25%'
    );
  });

  it('says nothing at all when idle', () => {
    expect(cutoutStatusLabel(initialCutoutState())).toBe('');
  });
});

describe('decode cost', () => {
  it('measures a cutout at four bytes per pixel', () => {
    const ref: CutoutRef = result().combined;
    expect(cutoutDecodeBytes(ref)).toBe(ref.widthPx * ref.heightPx * 4);
  });
});
