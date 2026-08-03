import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import {
  MOTION_PARITY_FIXTURE_VERSION,
  MOTION_PARITY_TOLERANCE,
  buildMotionParityFixtures,
  clampMotionHandleUs,
  copyKeyframeForward,
  deleteKeyframeAt,
  evaluateLayerMotionAt,
  keyframeIndexAt,
  motionActiveRange,
  motionHandleActions,
  motionLayerFor,
  motionRefusalMessage,
  nextKeyframeUs,
  previousKeyframeUs,
  setKeyframeEasing,
  setKeyframeHere,
} from './memeVideoMotionCore';
import {
  PROJECT_LIMITS,
  applyProjectAction,
  createDefaultVideoProject,
  createProjectHistory,
  reduceMemeEditProject,
  undoProjectHistory,
  type MemeEditProject,
  type MemeEditProjectAction,
  type TextLayer,
  type TransformKeyframe,
} from './memeEditProjectCore';
import { commitGestureTransaction } from './memeEditCanvasCore';
import { TIMELINE_MIN_RANGE_US } from './memeTimelineCore';

const SECOND_US = 1_000_000;

const videoSource = {
  uri: 'file:///clip.mp4',
  name: 'clip.mp4',
  width: 1920,
  height: 1080,
  durationUs: 10 * SECOND_US,
};

function kf(timeUs: number, overrides: Partial<TransformKeyframe> = {}): TransformKeyframe {
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

function textLayer(overrides: Partial<TextLayer> = {}): TextLayer {
  return {
    id: 'caption',
    kind: 'text',
    text: 'MOVE',
    width: 0.6,
    fontSize: 0.09,
    style: {
      preset: 'impact',
      color: '#ffffff',
      outlineColor: '#000000',
      outlineScale: 0.05,
      backgroundColor: null,
      opacity: 1,
      align: 'center',
      uppercase: true,
    },
    active: { startUs: 1 * SECOND_US, endUs: 5 * SECOND_US },
    keyframes: [kf(1 * SECOND_US), kf(5 * SECOND_US, { center: { x: 0.9, y: 0.5 } })],
    ...overrides,
  };
}

function projectWith(layer: TextLayer): MemeEditProject {
  return reduceMemeEditProject(createDefaultVideoProject(videoSource), { type: 'add-layer', layer });
}

function apply(project: MemeEditProject, actions: readonly MemeEditProjectAction[]): MemeEditProject {
  return actions.reduce((current, action) => reduceMemeEditProject(current, action), project);
}

function layerIn(project: MemeEditProject, id = 'caption'): TextLayer {
  const layer = project.layers.find((candidate) => candidate.id === id);
  if (!layer || layer.kind !== 'text') throw new Error(`no text layer ${id}`);
  return layer;
}

describe('timed layer motion evaluation', () => {
  test('evaluates through the shared keyframe contract and reports nothing outside the active range', () => {
    const layer = textLayer();

    expect(evaluateLayerMotionAt(layer, 1 * SECOND_US)).toEqual({
      center: { x: 0.5, y: 0.5 },
      scale: 1,
      rotationDegrees: 0,
      opacity: 1,
    });
    expect(evaluateLayerMotionAt(layer, 3 * SECOND_US)).toEqual({
      center: { x: 0.7, y: 0.5 },
      scale: 1,
      rotationDegrees: 0,
      opacity: 1,
    });
    expect(evaluateLayerMotionAt(layer, 999_999)).toBeNull();
    expect(evaluateLayerMotionAt(layer, 5 * SECOND_US + 1)).toBeNull();
  });

  test('interpolates center, scale, rotation, and opacity under linear easing and freezes them under hold', () => {
    const linear = textLayer({
      keyframes: [
        kf(0, { center: { x: 0.2, y: 0.25 }, scale: 1, rotationDegrees: 0, opacity: 0.25 }),
        kf(4 * SECOND_US, { center: { x: 0.6, y: 0.75 }, scale: 3, rotationDegrees: 90, opacity: 1 }),
      ],
      active: { startUs: 0, endUs: 4 * SECOND_US },
    });
    const held = textLayer({
      keyframes: [
        kf(0, { center: { x: 0.2, y: 0.25 }, scale: 1, rotationDegrees: 0, opacity: 0.25, easing: 'hold' }),
        kf(4 * SECOND_US, { center: { x: 0.6, y: 0.75 }, scale: 3, rotationDegrees: 90, opacity: 1 }),
      ],
      active: { startUs: 0, endUs: 4 * SECOND_US },
    });

    expect(evaluateLayerMotionAt(linear, 1 * SECOND_US)).toEqual({
      // Interpolation rounds to 1e-12, so 0.30000000000000004 lands on 0.3.
      center: { x: 0.3, y: 0.375 },
      scale: 1.5,
      rotationDegrees: 22.5,
      opacity: 0.4375,
    });
    expect(evaluateLayerMotionAt(held, 1 * SECOND_US)).toEqual({
      center: { x: 0.2, y: 0.25 },
      scale: 1,
      rotationDegrees: 0,
      opacity: 0.25,
    });
    // A hold releases exactly at the next keyframe, never before it.
    expect(evaluateLayerMotionAt(held, 4 * SECOND_US - 1)?.scale).toBe(1);
    expect(evaluateLayerMotionAt(held, 4 * SECOND_US)?.scale).toBe(3);
  });

  test('reads the effective active range and refuses layers that carry no keyframes', () => {
    const project = projectWith(textLayer());

    expect(motionActiveRange(layerIn(project), videoSource.durationUs)).toEqual({
      startUs: 1 * SECOND_US,
      endUs: 5 * SECOND_US,
    });
    expect(motionLayerFor(project, 'caption')?.id).toBe('caption');
    expect(motionLayerFor(project, 'missing')).toBeNull();
    expect(
      motionLayerFor(
        reduceMemeEditProject(project, {
          type: 'add-layer',
          layer: {
            id: 'blur',
            kind: 'cover',
            rect: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 },
            mode: 'pixelate',
            color: '#000000',
            pixelSize: 12,
            active: null,
            corrections: [],
          },
        }),
        'blur'
      )
    ).toBeNull();
  });
});

describe('timed layer start and end handles', () => {
  test('clamps each handle inside the media and keeps a grabbable minimum span', () => {
    const layer = textLayer();

    expect(clampMotionHandleUs(layer, 'start', -5_000, videoSource.durationUs)).toBe(0);
    expect(clampMotionHandleUs(layer, 'start', 2 * SECOND_US, videoSource.durationUs)).toBe(2 * SECOND_US);
    expect(clampMotionHandleUs(layer, 'start', 9 * SECOND_US, videoSource.durationUs)).toBe(
      5 * SECOND_US - TIMELINE_MIN_RANGE_US
    );
    expect(clampMotionHandleUs(layer, 'end', 99 * SECOND_US, videoSource.durationUs)).toBe(videoSource.durationUs);
    expect(clampMotionHandleUs(layer, 'end', 0, videoSource.durationUs)).toBe(
      1 * SECOND_US + TIMELINE_MIN_RANGE_US
    );
    expect(clampMotionHandleUs(layer, 'end', 3_500_123.7, videoSource.durationUs)).toBe(3_500_124);
  });

  test('a handle drag goes through set-layer-active-range so out-of-range keyframes are clamped, not orphaned', () => {
    const project = projectWith(textLayer());

    const actions = motionHandleActions(project, 'caption', 'start', 3 * SECOND_US);
    expect(actions).toEqual([
      { type: 'set-layer-active-range', id: 'caption', active: { startUs: 3 * SECOND_US, endUs: 5 * SECOND_US } },
    ]);

    const moved = layerIn(apply(project, actions));
    expect(moved.active).toEqual({ startUs: 3 * SECOND_US, endUs: 5 * SECOND_US });
    expect(moved.keyframes.map((frame) => frame.timeUs)).toEqual([3 * SECOND_US, 5 * SECOND_US]);
  });

  test('a handle drag that changes nothing produces no action at all', () => {
    const project = projectWith(textLayer());

    expect(motionHandleActions(project, 'caption', 'start', 1 * SECOND_US)).toEqual([]);
    expect(motionHandleActions(project, 'missing', 'start', 2 * SECOND_US)).toEqual([]);
  });

  test('one handle gesture is one undo entry even when it collapses keyframes together', () => {
    const project = projectWith(
      textLayer({
        keyframes: [
          kf(1 * SECOND_US),
          kf(3 * SECOND_US, { center: { x: 0.7, y: 0.5 } }),
          kf(5 * SECOND_US, { center: { x: 0.9, y: 0.5 } }),
        ],
      })
    );
    const history = createProjectHistory(project);

    const committed = commitGestureTransaction(
      history,
      motionHandleActions(project, 'caption', 'end', 1 * SECOND_US + TIMELINE_MIN_RANGE_US)
    );
    expect(layerIn(committed.present).keyframes.map((frame) => frame.timeUs)).toEqual([
      1 * SECOND_US,
      1 * SECOND_US + TIMELINE_MIN_RANGE_US,
    ]);
    expect(committed.past).toHaveLength(1);
    expect(layerIn(undoProjectHistory(committed).present).keyframes).toHaveLength(3);
  });
});

describe('keyframe commands', () => {
  test('Set keyframe here records the value already on screen, so the motion does not change', () => {
    const project = projectWith(textLayer());
    const before = [2 * SECOND_US, 3 * SECOND_US, 4 * SECOND_US].map(
      (timeUs) => evaluateLayerMotionAt(layerIn(project), timeUs)
    );

    const command = setKeyframeHere(project, 'caption', 3 * SECOND_US);
    expect(command.refusal).toBe('accepted');
    const next = apply(project, command.actions);

    expect(layerIn(next).keyframes.map((frame) => frame.timeUs)).toEqual([
      1 * SECOND_US,
      3 * SECOND_US,
      5 * SECOND_US,
    ]);
    expect(
      [2 * SECOND_US, 3 * SECOND_US, 4 * SECOND_US].map((timeUs) =>
        evaluateLayerMotionAt(layerIn(next), timeUs)
      )
    ).toEqual(before);
  });

  test('a keyframe set inside a held segment inherits the hold, so inserting one never starts an animation', () => {
    const project = projectWith(
      textLayer({
        keyframes: [
          kf(1 * SECOND_US, { center: { x: 0.2, y: 0.5 }, easing: 'hold' }),
          kf(5 * SECOND_US, { center: { x: 0.8, y: 0.5 } }),
        ],
      })
    );

    const next = apply(project, setKeyframeHere(project, 'caption', 3 * SECOND_US).actions);
    const inserted = layerIn(next).keyframes[1];
    expect(inserted).toMatchObject({ timeUs: 3 * SECOND_US, easing: 'hold', center: { x: 0.2, y: 0.5 } });
    expect(evaluateLayerMotionAt(layerIn(next), 4 * SECOND_US)).toEqual(
      evaluateLayerMotionAt(layerIn(project), 4 * SECOND_US)
    );
  });

  test('Set keyframe here refuses a playhead outside the active range instead of overwriting a boundary keyframe', () => {
    const project = projectWith(textLayer());

    expect(setKeyframeHere(project, 'caption', 7 * SECOND_US)).toEqual({
      actions: [],
      refusal: 'outside-active-range',
    });
    expect(setKeyframeHere(project, 'caption', 1 * SECOND_US)).toEqual({
      actions: [],
      refusal: 'already-keyframed',
    });
    expect(setKeyframeHere(project, 'missing', 2 * SECOND_US).refusal).toBe('no-layer');
    expect(motionRefusalMessage('outside-active-range')).toMatch(/active range/i);
  });

  test('Set keyframe here refuses at the keyframe ceiling rather than throwing out of the reducer', () => {
    const dense = Array.from({ length: PROJECT_LIMITS.maxKeyframesPerLayer }, (_unused, index) =>
      kf(1 * SECOND_US + index * 2)
    );
    const project = projectWith(textLayer({ keyframes: dense }));

    expect(setKeyframeHere(project, 'caption', 1 * SECOND_US + 1)).toEqual({
      actions: [],
      refusal: 'keyframe-limit',
    });
    expect(motionRefusalMessage('keyframe-limit')).toContain(String(PROJECT_LIMITS.maxKeyframesPerLayer));
  });

  test('next and previous keyframe navigation is strict, so repeated presses always move', () => {
    const keyframes = [kf(1 * SECOND_US), kf(3 * SECOND_US), kf(5 * SECOND_US)];

    expect(nextKeyframeUs(keyframes, 0)).toBe(1 * SECOND_US);
    expect(nextKeyframeUs(keyframes, 1 * SECOND_US)).toBe(3 * SECOND_US);
    expect(nextKeyframeUs(keyframes, 5 * SECOND_US)).toBeNull();
    expect(previousKeyframeUs(keyframes, 5 * SECOND_US)).toBe(3 * SECOND_US);
    expect(previousKeyframeUs(keyframes, 1 * SECOND_US)).toBeNull();
    expect(previousKeyframeUs(keyframes, 4 * SECOND_US)).toBe(3 * SECOND_US);
    expect(keyframeIndexAt(keyframes, 3 * SECOND_US)).toBe(1);
    expect(keyframeIndexAt(keyframes, 3 * SECOND_US + 1)).toBe(-1);
  });

  test('deleting a keyframe leaves the rest sorted and refuses to empty the track', () => {
    const project = projectWith(
      textLayer({ keyframes: [kf(1 * SECOND_US), kf(3 * SECOND_US), kf(5 * SECOND_US)] })
    );

    const next = apply(project, deleteKeyframeAt(project, 'caption', 3 * SECOND_US).actions);
    expect(layerIn(next).keyframes.map((frame) => frame.timeUs)).toEqual([1 * SECOND_US, 5 * SECOND_US]);
    expect(deleteKeyframeAt(project, 'caption', 2 * SECOND_US)).toEqual({
      actions: [],
      refusal: 'no-keyframe-here',
    });

    const single = projectWith(textLayer({ keyframes: [kf(1 * SECOND_US)] }));
    expect(deleteKeyframeAt(single, 'caption', 1 * SECOND_US)).toEqual({
      actions: [],
      refusal: 'last-keyframe',
    });
  });

  test('copy forward parks the current value at a later time so the layer holds still between them', () => {
    const project = projectWith(textLayer());

    const command = copyKeyframeForward(project, 'caption', 2 * SECOND_US, 4 * SECOND_US);
    expect(command.refusal).toBe('accepted');
    const next = apply(project, command.actions);

    expect(layerIn(next).keyframes.map((frame) => frame.timeUs)).toEqual([
      1 * SECOND_US,
      2 * SECOND_US,
      4 * SECOND_US,
      5 * SECOND_US,
    ]);
    const parked = evaluateLayerMotionAt(layerIn(next), 2 * SECOND_US);
    expect(evaluateLayerMotionAt(layerIn(next), 3 * SECOND_US)).toEqual(parked);
    expect(evaluateLayerMotionAt(layerIn(next), 4 * SECOND_US)).toEqual(parked);
  });

  test('copy forward refuses a target that is not strictly later or is outside the range', () => {
    const project = projectWith(textLayer());

    expect(copyKeyframeForward(project, 'caption', 3 * SECOND_US, 3 * SECOND_US).refusal).toBe('not-later');
    expect(copyKeyframeForward(project, 'caption', 3 * SECOND_US, 2 * SECOND_US).refusal).toBe('not-later');
    expect(copyKeyframeForward(project, 'caption', 3 * SECOND_US, 9 * SECOND_US).refusal).toBe(
      'outside-active-range'
    );
    expect(copyKeyframeForward(project, 'caption', 9 * SECOND_US, 9_500_000).refusal).toBe(
      'outside-active-range'
    );
  });

  test('easing is switchable per keyframe and a redundant switch commits nothing', () => {
    const project = projectWith(textLayer());

    const held = apply(project, setKeyframeEasing(project, 'caption', 1 * SECOND_US, 'hold').actions);
    expect(layerIn(held).keyframes.map((frame) => frame.easing)).toEqual(['hold', 'linear']);
    expect(evaluateLayerMotionAt(layerIn(held), 3 * SECOND_US)?.center.x).toBe(0.5);
    expect(setKeyframeEasing(held, 'caption', 1 * SECOND_US, 'hold')).toEqual({
      actions: [],
      refusal: 'unchanged',
    });
    expect(setKeyframeEasing(project, 'caption', 2 * SECOND_US, 'hold').refusal).toBe('no-keyframe-here');
  });

  test('every keyframe command is exactly one undo entry', () => {
    const project = projectWith(textLayer());
    const history = createProjectHistory(project);

    const withKeyframe = commitGestureTransaction(history, setKeyframeHere(project, 'caption', 3 * SECOND_US).actions);
    const held = commitGestureTransaction(
      withKeyframe,
      setKeyframeEasing(withKeyframe.present, 'caption', 3 * SECOND_US, 'hold').actions
    );
    const copied = commitGestureTransaction(
      held,
      copyKeyframeForward(held.present, 'caption', 3 * SECOND_US, 4 * SECOND_US).actions
    );
    const deleted = commitGestureTransaction(
      copied,
      deleteKeyframeAt(copied.present, 'caption', 4 * SECOND_US).actions
    );

    expect(deleted.past).toHaveLength(4);
    expect(layerIn(undoProjectHistory(deleted).present).keyframes.map((frame) => frame.timeUs)).toEqual([
      1 * SECOND_US,
      3 * SECOND_US,
      4 * SECOND_US,
      5 * SECOND_US,
    ]);
  });

  test('keyframes stay sorted, unique, and bounded no matter what order the commands arrive in', () => {
    let project = projectWith(textLayer());
    for (const atUs of [4 * SECOND_US, 2 * SECOND_US, 3 * SECOND_US, 2 * SECOND_US]) {
      project = apply(project, setKeyframeHere(project, 'caption', atUs).actions);
    }
    project = apply(project, copyKeyframeForward(project, 'caption', 2 * SECOND_US, 3 * SECOND_US).actions);

    const times = layerIn(project).keyframes.map((frame) => frame.timeUs);
    expect(times).toEqual([...times].sort((left, right) => left - right));
    expect(new Set(times).size).toBe(times.length);
    expect(times.length).toBeLessThanOrEqual(PROJECT_LIMITS.maxKeyframesPerLayer);
    expect(times).toEqual([1, 2, 3, 4, 5].map((second) => second * SECOND_US));
  });

  test('a refused command applied through history leaves no undo entry behind', () => {
    const project = projectWith(textLayer());
    const history = createProjectHistory(project);
    const refused = setKeyframeHere(project, 'caption', 7 * SECOND_US);

    expect(commitGestureTransaction(history, refused.actions)).toBe(history);
  });
});

describe('native motion parity fixtures', () => {
  const fixtures = buildMotionParityFixtures();

  test('the committed fixture JSON is exactly what TypeScript produces, on both sides of the bridge', () => {
    const shared = JSON.parse(
      readFileSync(join(__dirname, 'memeVideoMotionParityFixtures.json'), 'utf8')
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
          'dynamic_overlay_parity_fixtures.json'
        ),
        'utf8'
      )
    );

    expect(shared).toEqual(fixtures);
    expect(asset).toEqual(fixtures);
  });

  test('every sampled transform is produced by the shared evaluator, not written by hand', () => {
    expect(fixtures.version).toBe(MOTION_PARITY_FIXTURE_VERSION);
    expect(fixtures.toleranceUnits).toBe(MOTION_PARITY_TOLERANCE);
    expect(fixtures.cases.length).toBeGreaterThanOrEqual(4);

    for (const parityCase of fixtures.cases) {
      const layer = textLayer({
        active: { startUs: parityCase.activeStartUs, endUs: parityCase.activeEndUs },
        keyframes: parityCase.keyframes.map((frame) =>
          kf(frame.timeUs, {
            center: { x: frame.centerX, y: frame.centerY },
            scale: frame.scale,
            rotationDegrees: frame.rotationDegrees,
            opacity: frame.opacity,
            easing: frame.easing,
          })
        ),
      });
      expect(parityCase.samples.length).toBeGreaterThanOrEqual(5);
      for (const sample of parityCase.samples) {
        const evaluated = evaluateLayerMotionAt(layer, sample.timeUs);
        if (!sample.visible) {
          expect(evaluated).toBeNull();
          continue;
        }
        expect(evaluated).toEqual({
          center: { x: sample.centerX, y: sample.centerY },
          scale: sample.scale,
          rotationDegrees: sample.rotationDegrees,
          opacity: sample.opacity,
        });
      }
    }
  });

  test('the fixtures exercise both easings, both visibility answers, and inexact interpolation', () => {
    const samples = fixtures.cases.flatMap((parityCase) => parityCase.samples);
    const easings = new Set(fixtures.cases.flatMap((parityCase) => parityCase.keyframes.map((frame) => frame.easing)));

    expect(easings).toEqual(new Set(['linear', 'hold']));
    expect(samples.some((sample) => sample.visible)).toBe(true);
    expect(samples.some((sample) => !sample.visible)).toBe(true);
    // A repeating-fraction progress is where a native float shortcut would show
    // up; a fixture of only halves and thirds of a second would hide it.
    expect(
      samples.some((sample) => sample.visible && !Number.isInteger(sample.centerX * 1_000))
    ).toBe(true);
    expect(new Set(samples.map((sample) => sample.scale)).size).toBeGreaterThan(1);
    expect(new Set(samples.map((sample) => sample.rotationDegrees)).size).toBeGreaterThan(1);
    expect(new Set(samples.map((sample) => sample.opacity)).size).toBeGreaterThan(1);
  });

  test('the tolerance is tight enough that a visible drift cannot pass as agreement', () => {
    expect(MOTION_PARITY_TOLERANCE).toBeLessThanOrEqual(1e-9);
    // One normalized unit is the whole canvas edge, so the tolerance is far
    // below a single output pixel on any plausible export size.
    expect(MOTION_PARITY_TOLERANCE * 8_192).toBeLessThan(1e-4);
  });

  test('some sampled values only exist because interpolation rounds, so exact parity means something', () => {
    // The instrumentation demands BIT-exact agreement rather than merely
    // within-tolerance agreement, and rounding to 1e-12 never moves a value by
    // more than ~5e-13 — far under the tolerance. That assertion is therefore
    // only meaningful while the fixture carries samples whose rounded value
    // differs from the raw interpolation, which is what this checks. The raw
    // lerp is recomputed here deliberately: it is the shortcut a native
    // re-implementation would take.
    const rounded: number[] = [];
    for (const parityCase of fixtures.cases) {
      const frames = parityCase.keyframes;
      for (const sample of parityCase.samples) {
        if (!sample.visible) continue;
        const high = frames.findIndex((frame) => frame.timeUs > sample.timeUs);
        if (high <= 0) continue;
        const left = frames[high - 1];
        const right = frames[high];
        if (left.easing === 'hold') continue;
        const progress = (sample.timeUs - left.timeUs) / (right.timeUs - left.timeUs);
        const raw = left.centerX + (right.centerX - left.centerX) * progress;
        if (raw !== sample.centerX) rounded.push(Math.abs(raw - sample.centerX));
      }
    }

    expect(rounded.length).toBeGreaterThan(0);
    expect(Math.max(...rounded)).toBeLessThan(MOTION_PARITY_TOLERANCE);
  });
});
