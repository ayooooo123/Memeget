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
  'search using four labeled lines. Tag what the meme MEANS and the moment someone would ' +
  'send it — never how it merely looks. Output ONLY those lines — no prose, no JSON, no ' +
  'markdown, no code fences.';

// A flat "LABEL: value" format instead of JSON. A small on-device model frequently
// botches nested JSON (unbalanced braces/brackets, bad quote-escaping), and any
// truncation there loses the whole object. Line-delimited output has no nesting
// to corrupt and degrades gracefully: a reply cut off early still yields every
// line that finished. One filled-in example anchors the format and stops the
// model echoing the field hints back verbatim. (react-native-executorch has no
// hard max-token knob, so brevity is enforced via the prompt.)
export const USER_PROMPT =
  'Describe this meme so it can be found later by search. People search with a SINGLE ' +
  'word for any aspect — a feeling, an action, a character, a format, or the situation ' +
  'they would send it in — so name each aspect explicitly. Reply with EXACTLY these four ' +
  'lines, each starting with the label in caps, and nothing else:\n' +
  'CAPTION: one sentence, <=20 words: the action taking place, the feeling or mood, the situation it is used to react to, and why it is funny\n' +
  'TEXT: text visible in the image, verbatim; leave blank if none\n' +
  'SUBJECTS: comma-separated main people, characters, or objects\n' +
  'TAGS: 6-12 comma-separated lowercase keywords for how a person would SEARCH for this meme. ' +
  'Lead with the real-life situation or feeling you would send it to react to. For any gesture, ' +
  'tag what it MEANS, not how it looks (a finger to the lips = "shushing, be quiet"; a palm on the ' +
  'face = "facepalm, disbelief"; a pointing hand = "pointing, look at this"). Also include the ' +
  'emotion, the action, the meme format/template name if known, and named characters or people. ' +
  'Do NOT tag generic appearance — never "facial expression", "intense look", "serious face", ' +
  '"cute animal", "direct gaze"; nobody searches those. Name the meaning instead.\n' +
  '\nExample 1 (a gesture meme):\n' +
  'CAPTION: a man holds a finger to his lips, telling you to keep something quiet\n' +
  'TEXT: \n' +
  'SUBJECTS: man\n' +
  'TAGS: shushing, be quiet, keep it a secret, quiet, shhh, telling someone to hush, knowing look\n' +
  '\nExample 2 (a format meme with text):\n' +
  'CAPTION: a man turns to admire another woman while his girlfriend glares, used when tempted by something new\n' +
  'TEXT: me, new framework, the project i should be working on\n' +
  'SUBJECTS: man, girlfriend, other woman\n' +
  'TAGS: distracted boyfriend, temptation, tempted by something new, jealousy, choosing the exciting new thing\n' +
  '\nNow describe the image. If it is not a meme, still describe it the same way. Be concise.';

// Cap the injected OCR so it can't bloat the prompt (prefill cost) — a hint.
export const OCR_HINT_MAX = 280;

// ---- retrieval-augmented grounding ------------------------------------------
//
// The on-device VLM has limited meme knowledge: it can SEE the action/emotion
// but often can't NAME an obscure template or a niche character ("Milady",
// "gigachad", a specific format). The CLIP zero-shot pass already guessed those
// from the harvested label vocabulary — knowledge that otherwise dies in a
// separate channel the VLM never sees. Feeding the top guesses into the prompt
// (with a strict "only if it matches" caveat, so a wrong guess is harmless) lets
// the VLM name what it couldn't recognize on its own.

export interface GroundingLabel {
  label: string;
  category: string; // 'format' | 'character' | 'person' | 'topic' | 'emotion' | …
}

// Facet order in the grounding line: identity-bearing and most visually grounded
// first. EVERY facet the CLIP pass guessed is surfaced grouped by name — not just
// the format — because the point is to hand the VLM a full aspect breakdown
// (what it is, who's in it, what's happening, how it feels, the moment it fits).
const GROUNDING_FACET_ORDER = [
  'format',
  'character',
  'person',
  'action',
  'object',
  'setting',
  'emotion',
  'situation',
  'tone',
  'topic',
];
const MAX_PER_FACET = 2; // keep one loud facet from crowding out the rest
const MAX_GROUNDING_LABELS = 8;
const MAX_GROUNDING_RELATED = 6;

// Build the grounding line from CLIP's per-facet guesses (+ their association
// terms), grouped and labeled by facet: "format: drake; emotion: smug; action:
// pointing". Returns '' when there's nothing to offer.
export function formatGrounding(labels: GroundingLabel[], related: string[] = []): string {
  const byFacet = new Map<string, string[]>();
  const seen = new Set<string>();
  for (const l of labels) {
    const label = l.label.trim();
    const key = label.toLowerCase();
    if (!label || seen.has(key)) continue;
    seen.add(key);
    const arr = byFacet.get(l.category) ?? [];
    if (arr.length < MAX_PER_FACET) {
      arr.push(label);
      byFacet.set(l.category, arr);
    }
  }

  // Known facets in order, then any unrecognized facet after them.
  const order = [
    ...GROUNDING_FACET_ORDER,
    ...[...byFacet.keys()].filter((c) => !GROUNDING_FACET_ORDER.includes(c)),
  ];
  const segments: string[] = [];
  let total = 0;
  for (const facet of order) {
    const arr = byFacet.get(facet);
    if (!arr || arr.length === 0 || total >= MAX_GROUNDING_LABELS) continue;
    const take = arr.slice(0, MAX_GROUNDING_LABELS - total);
    segments.push(`${facet}: ${take.join(', ')}`);
    total += take.length;
  }
  if (segments.length === 0) return '';

  const rel = [...new Set(related.map((r) => r.trim().toLowerCase()).filter(Boolean))]
    .slice(0, MAX_GROUNDING_RELATED)
    .join(', ');
  return (
    `\nA visual recognizer suggests — ${segments.join('; ')}` +
    (rel ? ` (related: ${rel})` : '') +
    `. Use any that match what you actually see in SUBJECTS and TAGS; ignore any that do not.`
  );
}

// Build the user turn, optionally grounding it with (1) text ML Kit already read
// so the small model doesn't have to re-OCR a downscaled frame, and (2) the CLIP
// format/character guess (see formatGrounding).
export function userTurn(ocrHint?: string, grounding?: string): string {
  let turn = USER_PROMPT;
  const hint = (ocrHint ?? '').replace(/\s+/g, ' ').trim();
  if (hint) {
    turn +=
      `\nText already extracted from this image by OCR — use it verbatim for the TEXT line and ` +
      `as a hint for the caption: "${hint.slice(0, OCR_HINT_MAX)}"`;
  }
  const g = (grounding ?? '').trim();
  if (g) turn += `\n${g}`;
  return turn;
}

// Fragments of the prompt's own field descriptions. The small model occasionally
// echoes a hint instead of filling it in; a value containing one of these is
// noise and must never reach the UI.
const HINT_FRAGMENTS = [
  'the action taking place, the feeling or mood, the situation it is used to react to, and why it is funny',
  'the feeling or mood',
  'the situation it is used to react to',
  'the real-life situation you would send it to react to',
  'the real-life situation or feeling you would send it to react to',
  'how a person would search for this meme',
  'do not tag generic appearance',
  'name the meaning instead',
  'covering every searchable facet',
  'a visual recognizer suggests',
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
