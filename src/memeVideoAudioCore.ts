// Audio and speed logic for the video editor.
//
// Two things this module exists to guarantee:
//
//  1. Audio is never dropped by accident. The project reducer clamps rather
//     than rejects, so a NaN volume or an unoffered speed would survive as a
//     plausible-looking number. Everything the audio tool emits is filtered
//     through here first, and a mute is only ever an explicit user choice.
//
//  2. The preview does not lie. What expo-video can actually reproduce is a
//     narrower thing than what the exporter will render, and the difference is
//     encoded as measured data (SPEED_PREVIEW, PLAYER_MAX_VOLUME) rather than
//     hidden behind a control that quietly means something else.

import type { MemeEditProject, MemeEditProjectAction } from './memeEditProjectCore';

/** The speeds the editor offers. Sorted ascending; the UI renders them in order. */
export const VIDEO_SPEEDS: readonly number[] = Object.freeze([0.5, 0.75, 1, 1.25, 1.5, 2]);

export const VIDEO_VOLUME = Object.freeze({ min: 0, max: 2, default: 1 });

/**
 * expo-video writes `volume` straight to ExoPlayer, which documents 1.0 as
 * unity gain and constrains anything above it. Measured on emulator-5554:
 * writing 1.5 or 2.0 reads back as 1.0. Gain above unity is therefore an
 * export-only promise, and the tool says so instead of moving a slider that
 * silently does nothing.
 */
const PLAYER_MAX_VOLUME = 1;

/** Volume is stored to whole percent so the readout always equals the state. */
const VOLUME_STEP = 0.01;

/** Half a millionth of drift in a set member is float noise, not a new speed. */
const SPEED_EPSILON = 1e-6;

export type SpeedPreviewMode = 'preview' | 'export-only';

export interface SpeedPreviewSupport {
  speed: number;
  mode: SpeedPreviewMode;
  /** Empty when previewable; user-facing explanation when it is not. */
  reason: string;
}

/**
 * Measured, not assumed — see docs/editing/video-preview-speed-emulator.json,
 * which the test pins this table against. Each offered speed was driven
 * through the exact ExoPlayer properties expo-video sets (`playbackParameters`
 * with pitch correction, rendering into a real surface) and the media clock
 * was compared against wall clock over a 4 s window on emulator-5554. A speed
 * only earns 'preview' when the observed rate held within 2% of the request
 * with no rebuffering; worst observed error was +0.81% at 0.75x. A speed that
 * ever fails that bar gets 'export-only' plus the sentence the tool shows.
 */
const SPEED_PREVIEW: Record<string, { mode: SpeedPreviewMode; reason: string }> = {
  '0.5': { mode: 'preview', reason: '' },
  '0.75': { mode: 'preview', reason: '' },
  '1': { mode: 'preview', reason: '' },
  '1.25': { mode: 'preview', reason: '' },
  '1.5': { mode: 'preview', reason: '' },
  '2': { mode: 'preview', reason: '' },
};

const UNOFFERED_SPEED_REASON = 'This speed is not offered in the editor, so the preview cannot show it.';

/**
 * Returns the offered speed this value denotes, or null. Float noise around a
 * set member snaps to the member; anything else is rejected outright so an
 * unoffered speed can never reach the reducer's continuous 0.5..2 clamp.
 */
export function parseVideoSpeed(value: number): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  for (const speed of VIDEO_SPEEDS) {
    if (Math.abs(value - speed) <= SPEED_EPSILON) return speed;
  }
  return null;
}

/** Accessibility increment/decrement over the discrete set. */
export function nextVideoSpeed(current: number, direction: number): number {
  const step = direction >= 0 ? 1 : -1;
  const exact = parseVideoSpeed(current);
  if (exact !== null) {
    const index = VIDEO_SPEEDS.indexOf(exact) + step;
    return VIDEO_SPEEDS[Math.max(0, Math.min(VIDEO_SPEEDS.length - 1, index))];
  }
  // Unoffered or unusable current value: land on the nearest offered speed in
  // the requested direction, treating an unusable one as if it were unity.
  const from = Number.isFinite(current) ? current : 1;
  if (step === 1) {
    for (const speed of VIDEO_SPEEDS) if (speed > from) return speed;
    return VIDEO_SPEEDS[VIDEO_SPEEDS.length - 1];
  }
  for (let index = VIDEO_SPEEDS.length - 1; index >= 0; index -= 1) {
    if (VIDEO_SPEEDS[index] < from) return VIDEO_SPEEDS[index];
  }
  return VIDEO_SPEEDS[0];
}

export function speedPreviewSupport(speed: number): SpeedPreviewSupport {
  const exact = parseVideoSpeed(speed);
  if (exact === null) return { speed, mode: 'export-only', reason: UNOFFERED_SPEED_REASON };
  const measured = SPEED_PREVIEW[String(exact)];
  if (!measured) return { speed: exact, mode: 'export-only', reason: UNOFFERED_SPEED_REASON };
  return { speed: exact, mode: measured.mode, reason: measured.reason };
}

/**
 * Clamps into 0..200% and quantizes to whole percent. A non-finite input is
 * rejected — the caller keeps whatever it already had rather than dropping to
 * silence, because a silent drop is exactly the failure this task forbids.
 */
export function normalizeVideoVolume(value: number, fallback: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  const clamped = Math.max(VIDEO_VOLUME.min, Math.min(VIDEO_VOLUME.max, value));
  return Math.round(clamped / VOLUME_STEP) * VOLUME_STEP;
}

export function formatVolumePercent(volume: number): string {
  const bounded = Number.isFinite(volume)
    ? Math.max(VIDEO_VOLUME.min, Math.min(VIDEO_VOLUME.max, volume))
    : VIDEO_VOLUME.default;
  return `${Math.round(bounded * 100)}%`;
}

/** Volume 0..200% onto the shared Slider's 0..1 track, and back. */
export function sliderValueForVolume(volume: number): number {
  if (!Number.isFinite(volume)) return VIDEO_VOLUME.default / VIDEO_VOLUME.max;
  return Math.max(0, Math.min(1, volume / VIDEO_VOLUME.max));
}

export function volumeForSliderValue(value: number): number {
  if (!Number.isFinite(value)) return VIDEO_VOLUME.default;
  return normalizeVideoVolume(Math.max(0, Math.min(1, value)) * VIDEO_VOLUME.max, VIDEO_VOLUME.default);
}

export interface VideoAudioPreview {
  muted: boolean;
  /** What to write to the expo-video player right now. */
  playerVolume: number;
  /** True when the stored gain exceeds what the player can reproduce. */
  gainBeyondPreview: boolean;
}

export function videoAudioPreview(audio: { muted: boolean; volume: number }): VideoAudioPreview {
  const volume = normalizeVideoVolume(audio.volume, VIDEO_VOLUME.default);
  return {
    muted: !!audio.muted,
    playerVolume: Math.min(PLAYER_MAX_VOLUME, volume),
    gainBeyondPreview: volume > PLAYER_MAX_VOLUME,
  };
}

/**
 * `M:SS.t` from `outputDurationUs`. Fixed field widths so the tabular-numeral
 * readout never reflows while a slider or the timeline is moving.
 */
export function formatOutputDuration(durationUs: number): string {
  const bounded = Number.isFinite(durationUs) ? Math.max(0, durationUs) : 0;
  const tenths = Math.floor(bounded / 100_000);
  const minutes = Math.floor(tenths / 600);
  const seconds = Math.floor(tenths / 10) % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}.${tenths % 10}`;
}

export interface VideoAudioChange {
  muted?: boolean;
  volume?: number;
  speed?: number;
}

/**
 * The actions one gesture should commit. Returns an empty list when nothing
 * would change or the input is rejected, so a no-op drag never lands an undo
 * entry; volume and mute always travel together in a single action.
 */
export function videoAudioActions(
  project: MemeEditProject,
  change: VideoAudioChange
): MemeEditProjectAction[] {
  const video = project.video;
  if (!video) return [];
  const actions: MemeEditProjectAction[] = [];

  if (change.muted !== undefined || change.volume !== undefined) {
    const muted = change.muted === undefined ? video.audio.muted : !!change.muted;
    const volume =
      change.volume === undefined
        ? video.audio.volume
        : normalizeVideoVolume(change.volume, Number.NaN);
    if (Number.isFinite(volume) && (muted !== video.audio.muted || volume !== video.audio.volume)) {
      actions.push({ type: 'set-video-audio', audio: { muted, volume } });
    }
  }

  if (change.speed !== undefined) {
    const speed = parseVideoSpeed(change.speed);
    if (speed !== null && speed !== video.speed) actions.push({ type: 'set-video-speed', speed });
  }

  return actions;
}
