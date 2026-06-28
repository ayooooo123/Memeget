// React-free core shared by the in-app provider (vision.tsx) and the headless
// background task (headlessVision.ts / backgroundTask.ts). Nothing here imports
// React or any react-native-executorch HOOK, so it can run in a background JS
// context with no component tree.
import { LFM2_5_VL_450M_QUANTIZED, LFM2_5_VL_1_6B_QUANTIZED } from 'react-native-executorch';

import type { NativePower } from '../modules/memeget-bg';

export type VisionQuality = 'fast' | 'max';

// Persisted-setting keys, shared so the provider and the headless task read the
// exact same values out of the SQLite key/value store.
export const QUALITY_KEY = 'vision.quality';
export const ENABLED_KEY = 'vision.enabled';
export const BG_ENABLED_KEY = 'vision.bg.enabled';
export const BG_INTENSITY_KEY = 'vision.bg.intensity';
export const BG_ONLY_CHARGING_KEY = 'vision.bg.onlyCharging';
export const BG_PAUSE_HOT_KEY = 'vision.bg.pauseHot';
export const BG_PAUSE_LOW_KEY = 'vision.bg.pauseLowBattery';

// fast → 450M (smaller, snappier) · max → 1.6B (sharper, heavier). Each constant
// is a complete ExecuTorch model descriptor (source + tokenizer + capabilities).
export const MODEL = {
  fast: LFM2_5_VL_450M_QUANTIZED,
  max: LFM2_5_VL_1_6B_QUANTIZED,
} as const;

// Never hammer the accelerator faster than this between items, even at Extreme.
export const MIN_BG_INTERVAL_MS = 1200;
// Don't re-read battery/thermal more often than this.
export const POWER_CACHE_MS = 8000;

// ---- describe result + prompt -----------------------------------------------

export interface VisionResult {
  caption: string;
  subjects: string[];
  text: string; // text visibly written in the meme, verbatim
  tags: string[]; // open-vocabulary search keywords
}

export const SYSTEM_PROMPT =
  'You are a meme cataloging engine. You look at a single image and output ONLY ' +
  'a compact JSON object describing it for search. No prose, no markdown, no code fences.';

// Terse on purpose: decode is per-token, so a tight schema + short caption keeps
// each call fast. (react-native-executorch has no hard max-token knob, so brevity
// is enforced via the prompt.)
export const USER_PROMPT =
  'Describe this meme so it can be found later by search. Respond with ONLY this JSON ' +
  'object and nothing else, no prose:\n' +
  '{"caption": "<=14 words: what is happening and why it is funny>", ' +
  '"subjects": ["<main people, characters, or objects>"], ' +
  '"text": "<text visible in the image, verbatim; empty string if none>", ' +
  '"tags": ["<4-8 lowercase keywords: meme format/template name if known, topic, emotion, named characters>"]}\n' +
  'If it is not a meme, still describe the image the same way. Be concise.';

// Cap the injected OCR so it can't bloat the prompt (prefill cost) — a hint.
export const OCR_HINT_MAX = 280;

// Build the user turn, optionally grounding it with text ML Kit already read so
// the small model doesn't have to re-OCR small text from a downscaled frame.
export function userTurn(ocrHint?: string): string {
  const hint = (ocrHint ?? '').replace(/\s+/g, ' ').trim();
  if (!hint) return USER_PROMPT;
  return (
    USER_PROMPT +
    `\nText already extracted from this image by OCR — use it verbatim for the "text" field and ` +
    `as a hint for the caption: "${hint.slice(0, OCR_HINT_MAX)}"`
  );
}

function asStringArray(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  return v
    .map((x) => String(x).trim())
    .filter(Boolean)
    .slice(0, 16);
}

// Pull a JSON object out of the model's reply and coerce it into a VisionResult.
// Defensive: models occasionally wrap JSON in prose/fences, or emit a bare
// description — we recover a usable caption either way.
export function parseVision(raw: string): VisionResult {
  const reply = (raw ?? '').trim();
  const start = reply.indexOf('{');
  const end = reply.lastIndexOf('}');
  let obj: Record<string, unknown> = {};
  if (start >= 0 && end > start) {
    try {
      obj = JSON.parse(reply.slice(start, end + 1)) as Record<string, unknown>;
    } catch {
      // fall through to raw-text fallback below
    }
  }
  const caption =
    typeof obj.caption === 'string' && obj.caption.trim()
      ? obj.caption.trim()
      : reply.replace(/[{}]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 240);
  return {
    caption,
    subjects: asStringArray(obj.subjects),
    text: typeof obj.text === 'string' ? obj.text.trim() : '',
    tags: asStringArray(obj.tags),
  };
}

// ---- background pacing -------------------------------------------------------

// Map the intensity slider (0..1) to a target throughput in memes/hour. Bottom
// trickles (~6/hr); the very top means "as fast as the model allows".
export function memesPerHour(intensity: number): number {
  const v = Math.max(0, Math.min(1, intensity));
  if (v >= 0.97) return Infinity;
  const minRate = 6;
  const maxRate = 600;
  return Math.round(minRate * Math.pow(maxRate / minRate, v));
}

export function intensityLabel(intensity: number): string {
  const v = Math.max(0, Math.min(1, intensity));
  if (v < 0.3) return 'Conservative';
  if (v < 0.6) return 'Balanced';
  if (v < 0.97) return 'Aggressive';
  return 'Extreme';
}

export function bgIntervalMs(intensity: number): number {
  const rate = memesPerHour(intensity);
  if (rate === Infinity) return MIN_BG_INTERVAL_MS;
  return Math.max(MIN_BG_INTERVAL_MS, Math.round(3_600_000 / rate));
}

// ---- battery / thermal throttles --------------------------------------------

export interface BgThrottles {
  onlyWhileCharging: boolean;
  pauseWhenHot: boolean;
  pauseOnLowBattery: boolean;
}

// Returns a short human reason to pause, or null to proceed. Without the native
// module (no power signal) it never blocks.
export function powerBlockReason(p: NativePower | null, t: BgThrottles): string | null {
  if (!p) return null;
  if (t.onlyWhileCharging && !p.charging) return 'on battery';
  if (t.pauseOnLowBattery && !p.charging && p.level >= 0 && p.level < 0.2) return 'battery low';
  const hot = p.thermal >= 2 || (p.headroom >= 0 && p.headroom > 0.85);
  if (t.pauseWhenHot && hot) return 'device warm';
  return null;
}

// Read throttle prefs from the persisted settings (defaults match the provider:
// charging optional, pause-when-hot / pause-on-low-battery on).
export function throttlesFromSettings(
  onlyCharging: string | null,
  pauseHot: string | null,
  pauseLow: string | null
): BgThrottles {
  return {
    onlyWhileCharging: onlyCharging === '1',
    pauseWhenHot: pauseHot !== '0',
    pauseOnLowBattery: pauseLow !== '0',
  };
}
