// React-free core shared by the in-app provider (vision.tsx) and the headless
// background task (headlessVision.ts / backgroundTask.ts). Nothing here imports
// React or any react-native-executorch HOOK, so it can run in a background JS
// context with no component tree.
import { GEMMA4_E2B_MM, LFM2_5_VL_450M_QUANTIZED } from 'react-native-executorch';

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

// Gemma ships no recommended sampling settings in the library descriptor, so we
// pin the same near-greedy config the LFM card recommended — this is a cataloging
// task, not creative writing, and low temperature keeps the four-line format tight.
const GEMMA_GENERATION_CONFIG = {
  temperature: 0.1,
  minP: 0.15,
  repetitionPenalty: 1.05,
} as const;

// fast → LFM2.5-VL 450M (small, snappy — for weaker devices or huge backlogs) ·
// max → Gemma 4 E2B multimodal (the default: much sharper captions/tags and far
// better meme-culture knowledge, at a bigger download + slower generation).
// Each entry is a complete ExecuTorch model descriptor (source + tokenizer +
// capabilities). On Android the Gemma binary runs on the Vulkan (GPU) backend;
// LFM stays on XNNPACK (CPU).
export const MODEL = {
  fast: LFM2_5_VL_450M_QUANTIZED,
  max: { ...GEMMA4_E2B_MM, generationConfig: GEMMA_GENERATION_CONFIG },
} as const;

// Fresh installs (no persisted choice) get Gemma.
export const DEFAULT_QUALITY: VisionQuality = 'max';

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
  'You are a meme cataloging engine. You look at a single image and describe it for ' +
  'search using four labeled lines. Output ONLY those lines — no prose, no JSON, no ' +
  'markdown, no code fences.';

// A flat "LABEL: value" format instead of JSON. A small on-device model frequently
// botches nested JSON (unbalanced braces/brackets, bad quote-escaping), and any
// truncation there loses the whole object. Line-delimited output has no nesting
// to corrupt and degrades gracefully: a reply cut off early still yields every
// line that finished. One filled-in example anchors the format and stops the
// model echoing the field hints back verbatim.
//
// react-native-executorch 0.9.2 has no max-token field, but it DOES expose
// llm.interrupt(), which resolves generate() with the text so far. Because TAGS
// is the last line and the parser tolerates truncation, we hard-stop generation
// the moment the TAGS line finishes (see captionLikelyComplete) instead of
// letting a small model ramble on past the four lines — pure decode-time savings.
export const USER_PROMPT =
  'Describe this meme so it can be found later by search. Reply with EXACTLY these ' +
  'four lines, each starting with the label in caps, and nothing else:\n' +
  'CAPTION: one sentence, <=14 words, the action taking place, the feeling or mood being conveyed, and why it is funny\n' +
  'TEXT: text visible in the image, verbatim; leave blank if none\n' +
  'SUBJECTS: comma-separated main people, characters, or objects\n' +
  'TAGS: 4-8 comma-separated lowercase keywords (meme format/template name if known, topic, actions happening, emotion/feeling conveyed, named characters)\n' +
  '\nExample of the exact format:\n' +
  'CAPTION: a man turns to admire another woman while his girlfriend glares in disgust\n' +
  'TEXT: me, new framework, the project i should be working on\n' +
  'SUBJECTS: man, girlfriend, other woman\n' +
  'TAGS: distracted boyfriend, turning to look, temptation, jealousy, disgust, relatable\n' +
  '\nNow describe the image. If it is not a meme, still describe it the same way. Be concise.';

// Cap the injected OCR so it can't bloat the prompt (prefill cost) — a hint.
export const OCR_HINT_MAX = 280;

// Hard backstop on decoded tokens per caption. The four lines are short (a
// <=14-word caption + three brief lists ≈ 60–100 tokens), so this only fires
// when the TAGS-line detector misses and the model runs away — it never clips a
// well-formed reply. Paired with captionLikelyComplete in the interrupt path.
export const CAPTION_TOKEN_BUDGET = 160;

// True once the model's streamed output contains everything worth keeping, so
// the caller can interrupt() the rest of the generation. The prompt fixes the
// order with TAGS last, so a finished, newline-terminated TAGS line means the
// four fields are all in — anything after it (explanations, a repeated example,
// a looping small model) is pure waste. We also require a CAPTION line so an
// out-of-order early "TAGS:" can't stop us before the headline field lands;
// parseVision still recovers whatever did stream if we stop a hair early.
export function captionLikelyComplete(response: string): boolean {
  if (!/(^|\n)\s*caption\s*[:\-]/i.test(response)) return false;
  const tags = /(^|\n)\s*tags\s*[:\-]/i.exec(response);
  if (!tags) return false;
  const afterTags = response.slice(tags.index + tags[0].length);
  // Some tag text, then a line break: the TAGS line is done.
  return /\S/.test(afterTags) && /\n/.test(afterTags);
}

// Build the user turn, optionally grounding it with text ML Kit already read so
// the small model doesn't have to re-OCR small text from a downscaled frame.
export function userTurn(ocrHint?: string): string {
  const hint = (ocrHint ?? '').replace(/\s+/g, ' ').trim();
  if (!hint) return USER_PROMPT;
  return (
    USER_PROMPT +
    `\nText already extracted from this image by OCR — use it verbatim for the TEXT line and ` +
    `as a hint for the caption: "${hint.slice(0, OCR_HINT_MAX)}"`
  );
}

// Fragments of the prompt's own field descriptions. The small model occasionally
// echoes a hint instead of filling it in; a value containing one of these is
// noise and must never reach the UI.
const HINT_FRAGMENTS = [
  'the action taking place, the feeling or mood being conveyed, and why it is funny',
  'the feeling or mood being conveyed',
  'text visible in the image',
  'leave blank if none',
  'main people, characters, or objects',
  'comma-separated',
  'lowercase keywords',
];

// True for an unfilled hint: a bracketed "<...>" placeholder (JSON-drift relic)
// or text that quotes one of the prompt's field descriptions.
function isJunk(s: string): boolean {
  const t = s.trim();
  if (!t) return true;
  if (/^<.*>$/.test(t)) return true;
  const lower = t.toLowerCase();
  return HINT_FRAGMENTS.some((f) => lower.includes(f));
}

// Back-compat alias used by the JSON fallback below.
const isPlaceholder = isJunk;

// Trim a single value and shed any quotes/brackets/markdown the model wrapped it
// in out of habit. Returns '' for an unfilled hint so it's dropped downstream.
function cleanValue(s: string): string {
  const v = s.replace(/^[\s"'`*[\]]+|[\s"'`*[\]]+$/g, '').trim();
  return isJunk(v) ? '' : v;
}

// Split a comma/semicolon-separated value into clean items. The whole string is
// checked for an echoed hint first, since a hint like "main people, characters,
// or objects" would otherwise survive being split on its own commas.
function splitList(s: string): string[] {
  if (isJunk(s.trim())) return [];
  return s
    .split(/[,;\n]+/)
    .map(cleanValue)
    .filter(Boolean)
    .slice(0, 16);
}

const FIELD_LINE = /^\s*(caption|subjects|text|tags)\s*[:\-]\s*(.*)$/i;

// Primary parse: the flat "LABEL: value" format the prompt requests. A line that
// starts with a known label opens that field; any following unlabeled lines are
// appended to it (so a wrapped caption survives). Returns null if no labeled line
// is present, signalling the JSON/bare-text fallback.
function parseLabeledLines(reply: string): Record<string, string> | null {
  const out: Record<string, string> = {};
  let current: string | null = null;
  let found = false;
  for (const line of reply.split(/\r?\n/)) {
    if (/^\s*```/.test(line)) continue; // ignore markdown code-fence lines
    const m = FIELD_LINE.exec(line);
    if (m) {
      found = true;
      current = m[1].toLowerCase();
      out[current] = out[current] ? `${out[current]} ${m[2].trim()}` : m[2].trim();
    } else if (current && line.trim()) {
      out[current] = `${out[current]} ${line.trim()}`.trim();
    }
  }
  return found ? out : null;
}

// ---- JSON fallback (defensive) ----------------------------------------------
// The contract is the flat format above, but a small model can drift back to
// JSON. These recover fields from JSON that may be wrapped in prose, truncated
// before its closing brace, or full of unfilled hints — without ever letting raw
// JSON structure leak into the caption.

function asStringArray(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  return v
    .map((x) => String(x).trim())
    .filter((s) => s && !isPlaceholder(s))
    .slice(0, 16);
}

function extractStringField(raw: string, key: string): string | null {
  const m = new RegExp(`"${key}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"`).exec(raw);
  if (!m) return null;
  try {
    const val = String(JSON.parse(`"${m[1]}"`)).trim();
    return val && !isPlaceholder(val) ? val : null;
  } catch {
    return null;
  }
}

function extractArrayField(raw: string, key: string): string[] {
  const m = new RegExp(`"${key}"\\s*:\\s*\\[([^\\]]*)`).exec(raw);
  if (!m) return [];
  const items = m[1].match(/"((?:[^"\\]|\\.)*)"/g) ?? [];
  return items
    .map((q) => {
      try {
        return String(JSON.parse(q)).trim();
      } catch {
        return '';
      }
    })
    .filter((s) => s && !isPlaceholder(s))
    .slice(0, 16);
}

function stripJsonArtifacts(raw: string): string {
  return raw
    .replace(/<[^>]*>/g, ' ')
    .replace(/"(?:caption|subjects|text|tags)"\s*:/gi, ' ')
    .replace(/[{}[\]"]/g, ' ')
    .replace(/[,:]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function parseJsonReply(reply: string): VisionResult {
  const start = reply.indexOf('{');
  const end = reply.lastIndexOf('}');
  let obj: Record<string, unknown> = {};
  if (start >= 0 && end > start) {
    try {
      obj = JSON.parse(reply.slice(start, end + 1)) as Record<string, unknown>;
    } catch {
      // fall through to field-by-field recovery below
    }
  }

  const fromObj = (key: string): string | null => {
    const v = obj[key];
    if (typeof v !== 'string') return null;
    const t = v.trim();
    return t && !isPlaceholder(t) ? t : null;
  };

  // Did the reply look like (possibly broken) JSON at all? If not, it's a bare
  // description and the whole thing is the caption.
  const looksLikeJson = start >= 0 || /"(?:caption|subjects|text|tags)"/.test(reply);

  let caption = fromObj('caption') ?? extractStringField(reply, 'caption');
  if (!caption) {
    caption = looksLikeJson
      ? stripJsonArtifacts(reply).slice(0, 240)
      : reply.replace(/\s+/g, ' ').trim().slice(0, 240);
  }

  const text = fromObj('text') ?? extractStringField(reply, 'text') ?? '';
  const subjects = obj.subjects !== undefined ? asStringArray(obj.subjects) : extractArrayField(reply, 'subjects');
  const tags = obj.tags !== undefined ? asStringArray(obj.tags) : extractArrayField(reply, 'tags');

  return { caption, subjects, text, tags };
}

// Coerce the model's reply into a VisionResult. The expected shape is the flat
// "LABEL: value" format; we fall back to recovering fields from JSON (or a bare
// description) so an off-format reply still produces a usable, clean caption.
export function parseVision(raw: string): VisionResult {
  const reply = (raw ?? '').trim();

  const lines = parseLabeledLines(reply);
  if (lines) {
    return {
      caption: cleanValue(lines.caption ?? ''),
      subjects: splitList(lines.subjects ?? ''),
      text: cleanValue(lines.text ?? ''),
      tags: splitList(lines.tags ?? ''),
    };
  }

  return parseJsonReply(reply);
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
