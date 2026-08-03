import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import {
  applyProjectAction,
  createDefaultVideoProject,
  createProjectHistory,
  outputDurationUs,
  type TimeRangeUs,
} from './memeEditProjectCore';
import { commitGestureTransaction } from './memeEditCanvasCore';
import {
  VIDEO_SPEEDS,
  VIDEO_VOLUME,
  formatOutputDuration,
  formatVolumePercent,
  nextVideoSpeed,
  normalizeVideoVolume,
  parseVideoSpeed,
  sliderValueForVolume,
  speedPreviewSupport,
  videoAudioActions,
  videoAudioPreview,
  volumeForSliderValue,
} from './memeVideoAudioCore';

const SECOND_US = 1_000_000;

const videoSource = {
  uri: 'file:///clip.mp4',
  name: 'clip.mp4',
  width: 1920,
  height: 1080,
  durationUs: 12 * SECOND_US,
};

const ranges = (...pairs: [number, number][]): TimeRangeUs[] =>
  pairs.map(([startUs, endUs]) => ({ startUs, endUs }));

describe('video speed set', () => {
  it('offers exactly the six editor speeds in ascending order', () => {
    expect(VIDEO_SPEEDS).toEqual([0.5, 0.75, 1, 1.25, 1.5, 2]);
  });

  it('accepts every member of the set', () => {
    for (const speed of VIDEO_SPEEDS) {
      expect(parseVideoSpeed(speed)).toBe(speed);
    }
  });

  it('rejects in-range speeds that are not offered', () => {
    // The project reducer clamps to a continuous 0.5..2 band, so an unoffered
    // value like 0.6 would silently survive. The editor must never emit one.
    for (const speed of [0.6, 0.9, 1.1, 1.75, 1.99]) {
      expect(parseVideoSpeed(speed)).toBeNull();
    }
  });

  it('rejects out-of-range, non-finite, and non-numeric speeds', () => {
    for (const speed of [0, -1, 0.25, 2.5, 16, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
      expect(parseVideoSpeed(speed)).toBeNull();
    }
    expect(parseVideoSpeed('1.5' as unknown as number)).toBeNull();
    expect(parseVideoSpeed(null as unknown as number)).toBeNull();
  });

  it('tolerates float noise around a set member', () => {
    expect(parseVideoSpeed(0.5 + Number.EPSILON)).toBe(0.5);
    expect(parseVideoSpeed(1.2500000001)).toBe(1.25);
    expect(parseVideoSpeed(1.26)).toBeNull();
  });

  it('steps through the set for accessibility increment and decrement', () => {
    expect(nextVideoSpeed(1, 1)).toBe(1.25);
    expect(nextVideoSpeed(1, -1)).toBe(0.75);
    expect(nextVideoSpeed(2, 1)).toBe(2);
    expect(nextVideoSpeed(0.5, -1)).toBe(0.5);
    // An unoffered current value snaps to the nearest offered neighbour.
    expect(nextVideoSpeed(1.1, 1)).toBe(1.25);
    expect(nextVideoSpeed(Number.NaN, 1)).toBe(1.25);
  });
});

describe('volume', () => {
  it('preserves audio at unity by default', () => {
    const project = createDefaultVideoProject(videoSource);
    expect(project.video?.audio).toEqual({ muted: false, volume: 1 });
    expect(VIDEO_VOLUME).toEqual({ min: 0, max: 2, default: 1 });
  });

  it('clamps to the 0..200% band', () => {
    expect(normalizeVideoVolume(-0.5, 1)).toBe(0);
    expect(normalizeVideoVolume(0, 1)).toBe(0);
    expect(normalizeVideoVolume(2.4, 1)).toBe(2);
    expect(normalizeVideoVolume(1e9, 1)).toBe(2);
  });

  it('rejects non-finite volume and keeps the previous value', () => {
    expect(normalizeVideoVolume(Number.NaN, 1.5)).toBe(1.5);
    expect(normalizeVideoVolume(Number.POSITIVE_INFINITY, 1.5)).toBe(1.5);
    expect(normalizeVideoVolume(Number.NEGATIVE_INFINITY, 1.5)).toBe(1.5);
    expect(normalizeVideoVolume('1.2' as unknown as number, 1.5)).toBe(1.5);
  });

  it('quantizes to whole percent so the readout matches the stored value', () => {
    expect(normalizeVideoVolume(1.23456, 1)).toBe(1.23);
    expect(normalizeVideoVolume(0.005, 1)).toBe(0.01);
    expect(formatVolumePercent(1.23)).toBe('123%');
    expect(formatVolumePercent(0)).toBe('0%');
    expect(formatVolumePercent(2)).toBe('200%');
  });

  it('round-trips through the 0..1 slider track', () => {
    expect(sliderValueForVolume(0)).toBe(0);
    expect(sliderValueForVolume(1)).toBe(0.5);
    expect(sliderValueForVolume(2)).toBe(1);
    for (const volume of [0, 0.37, 1, 1.5, 2]) {
      expect(volumeForSliderValue(sliderValueForVolume(volume))).toBe(volume);
    }
    expect(volumeForSliderValue(-1)).toBe(0);
    expect(volumeForSliderValue(2)).toBe(2);
    expect(volumeForSliderValue(Number.NaN)).toBe(VIDEO_VOLUME.default);
  });
});

describe('preview honesty', () => {
  it('passes volume and mute straight to the player when they fit its range', () => {
    expect(videoAudioPreview({ muted: false, volume: 1 })).toEqual({
      muted: false,
      playerVolume: 1,
      gainBeyondPreview: false,
    });
    expect(videoAudioPreview({ muted: false, volume: 0.4 })).toEqual({
      muted: false,
      playerVolume: 0.4,
      gainBeyondPreview: false,
    });
    expect(videoAudioPreview({ muted: true, volume: 0.4 })).toEqual({
      muted: true,
      playerVolume: 0.4,
      gainBeyondPreview: false,
    });
  });

  it('flags gain above unity because the player cannot amplify', () => {
    // ExoPlayer (what expo-video wraps) treats 1.0 as unity gain and clamps
    // above it, so >100% is an export-only promise and must say so.
    expect(videoAudioPreview({ muted: false, volume: 1.5 })).toEqual({
      muted: false,
      playerVolume: 1,
      gainBeyondPreview: true,
    });
    expect(videoAudioPreview({ muted: true, volume: 2 })).toEqual({
      muted: true,
      playerVolume: 1,
      gainBeyondPreview: true,
    });
  });

  it('never reports a silent drop: muting is explicit and audio is otherwise kept', () => {
    const project = createDefaultVideoProject(videoSource);
    expect(videoAudioPreview(project.video!.audio).muted).toBe(false);
    expect(videoAudioPreview({ muted: false, volume: 0 })).toEqual({
      muted: false,
      playerVolume: 0,
      gainBeyondPreview: false,
    });
  });

  it('classifies every offered speed against measured device behaviour', () => {
    for (const speed of VIDEO_SPEEDS) {
      const support = speedPreviewSupport(speed);
      expect(['preview', 'export-only']).toContain(support.mode);
      expect(support.speed).toBe(speed);
      if (support.mode === 'export-only') expect(support.reason.length).toBeGreaterThan(0);
    }
  });

  it('matches the measured device evidence speed for speed', () => {
    // The table in the core is a claim about hardware. This reads the recorded
    // measurement back and fails if the two ever drift apart, so nobody can
    // promise a clean preview the device never demonstrated.
    const evidence = JSON.parse(
      readFileSync(join(__dirname, '..', 'docs', 'editing', 'video-preview-speed-emulator.json'), 'utf8')
    ) as {
      speeds: { requestedSpeed: number; previewClean: boolean; rebufferCount: number; relativeError: number }[];
      volume: { requested: number; appliedByPlayer: number; honored: boolean }[];
    };
    expect(evidence.speeds.map((entry) => entry.requestedSpeed)).toEqual([...VIDEO_SPEEDS]);
    for (const entry of evidence.speeds) {
      expect(speedPreviewSupport(entry.requestedSpeed).mode).toBe(
        entry.previewClean ? 'preview' : 'export-only'
      );
    }
    // And the gain ceiling the preview model assumes is the one the player showed.
    const aboveUnity = evidence.volume.filter((entry) => entry.requested > 1);
    expect(aboveUnity.length).toBeGreaterThan(0);
    for (const entry of aboveUnity) {
      expect(entry.honored).toBe(false);
      expect(entry.appliedByPlayer).toBe(1);
      expect(videoAudioPreview({ muted: false, volume: entry.requested })).toEqual({
        muted: false,
        playerVolume: entry.appliedByPlayer,
        gainBeyondPreview: true,
      });
    }
  });

  it('treats an unoffered speed as export-only rather than guessing', () => {
    const support = speedPreviewSupport(1.1);
    expect(support.mode).toBe('export-only');
    expect(support.reason.length).toBeGreaterThan(0);
  });
});

describe('output duration readout', () => {
  it('shows the retained total divided by the speed', () => {
    const retained = ranges([0, 2 * SECOND_US], [8 * SECOND_US, 12 * SECOND_US]);
    expect(formatOutputDuration(outputDurationUs(retained, 1))).toBe('0:06.0');
    expect(formatOutputDuration(outputDurationUs(retained, 2))).toBe('0:03.0');
    expect(formatOutputDuration(outputDurationUs(retained, 0.5))).toBe('0:12.0');
    expect(formatOutputDuration(outputDurationUs(retained, 1.25))).toBe('0:04.8');
  });

  it('recomputes across a multi-range seam at every offered speed', () => {
    const retained = ranges([0, 1_000_001], [2_000_000, 3_000_001]);
    const expected = ['0:04.0', '0:02.6', '0:02.0', '0:01.6', '0:01.3', '0:01.0'];
    expect(VIDEO_SPEEDS.map((speed) => formatOutputDuration(outputDurationUs(retained, speed)))).toEqual(
      expected
    );
  });

  it('is zero for no retained footage and for an unusable speed', () => {
    expect(outputDurationUs([], 1)).toBe(0);
    expect(outputDurationUs(ranges([0, SECOND_US]), 0)).toBe(0);
    expect(outputDurationUs(ranges([0, SECOND_US]), Number.NaN)).toBe(0);
  });

  it('formats as a fixed-width clock so tabular numerals do not jump', () => {
    expect(formatOutputDuration(0)).toBe('0:00.0');
    expect(formatOutputDuration(4_500_000)).toBe('0:04.5');
    expect(formatOutputDuration(65_400_000)).toBe('1:05.4');
    expect(formatOutputDuration(3_600_000_000)).toBe('60:00.0');
    expect(formatOutputDuration(49_999)).toBe('0:00.0');
    expect(formatOutputDuration(-1)).toBe('0:00.0');
  });
});

describe('history transactions', () => {
  it('emits one undo entry for a completed volume drag', () => {
    const project = createDefaultVideoProject(videoSource);
    let history = createProjectHistory(project);
    history = commitGestureTransaction(history, videoAudioActions(project, { volume: 1.4 }));
    expect(history.present.video?.audio).toEqual({ muted: false, volume: 1.4 });
    expect(history.past).toHaveLength(1);
  });

  it('emits one undo entry for a speed change', () => {
    const project = createDefaultVideoProject(videoSource);
    let history = createProjectHistory(project);
    history = commitGestureTransaction(history, videoAudioActions(project, { speed: 1.5 }));
    expect(history.present.video?.speed).toBe(1.5);
    expect(history.past).toHaveLength(1);
  });

  it('emits one undo entry for a mute toggle and restores the volume on undo', () => {
    const project = createDefaultVideoProject(videoSource);
    let history = createProjectHistory(project);
    history = commitGestureTransaction(history, videoAudioActions(project, { volume: 0.6 }));
    history = commitGestureTransaction(history, videoAudioActions(history.present, { muted: true }));
    expect(history.present.video?.audio).toEqual({ muted: true, volume: 0.6 });
    expect(history.past).toHaveLength(2);
  });

  it('produces no action when nothing changes, so a no-op drag adds no undo entry', () => {
    const project = createDefaultVideoProject(videoSource);
    expect(videoAudioActions(project, { volume: 1 })).toEqual([]);
    expect(videoAudioActions(project, { speed: 1 })).toEqual([]);
    expect(videoAudioActions(project, { muted: false })).toEqual([]);
    let history = createProjectHistory(project);
    history = commitGestureTransaction(history, videoAudioActions(project, { volume: 1 }));
    expect(history.past).toHaveLength(0);
  });

  it('drops a rejected speed and a non-finite volume instead of writing them', () => {
    const project = createDefaultVideoProject(videoSource);
    expect(videoAudioActions(project, { speed: 1.1 })).toEqual([]);
    expect(videoAudioActions(project, { volume: Number.POSITIVE_INFINITY })).toEqual([]);
    let history = createProjectHistory(project);
    history = applyProjectAction(history, { type: 'set-video-speed', speed: 1.1 });
    expect(history.present.video?.speed).toBe(1.1);
    // The reducer would have taken it; the core is what keeps it out.
  });

  it('carries a combined volume and mute change in one transaction', () => {
    const project = createDefaultVideoProject(videoSource);
    const actions = videoAudioActions(project, { muted: true, volume: 0.25 });
    expect(actions).toHaveLength(1);
    let history = createProjectHistory(project);
    history = commitGestureTransaction(history, actions);
    expect(history.present.video?.audio).toEqual({ muted: true, volume: 0.25 });
    expect(history.past).toHaveLength(1);
  });

  it('produces nothing for an image project', () => {
    expect(
      videoAudioActions(
        { ...createDefaultVideoProject(videoSource), video: null },
        { volume: 0.5, speed: 2, muted: true }
      )
    ).toEqual([]);
  });
});
