import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import {
  useLLM,
  LFM2_5_VL_450M_QUANTIZED,
  LFM2_5_VL_1_6B_QUANTIZED,
} from 'react-native-executorch';

import { getSetting, setSetting } from './db';
import {
  enrichLibrary,
  enrichNextMeme,
  type EnrichProgress,
  type EnrichResult,
  type VisionEnricher,
} from './indexer';

// LFM2.5-VL, on-device, via ExecuTorch's XNNPACK backend — the SAME runtime
// that already runs CLIP, so there's no second engine to ship. We use the
// vision-language model purely as an *enrichment* pass: CLIP stays the fast
// embedding/similarity + teach-by-example backbone; LFM reads each meme and
// writes back a human caption, the literal text, and open-vocabulary tags that
// CLIP's fixed 97-label vocabulary can never produce.
//
// Two sizes, user-selectable:
//   fast → 450M (smaller download, snappier per-image)
//   max  → 1.6B (sharper captions / OCR, heavier)
export type VisionQuality = 'fast' | 'max';

const QUALITY_KEY = 'vision.quality';
const ENABLED_KEY = 'vision.enabled';
const BG_ENABLED_KEY = 'vision.bg.enabled';
const BG_INTENSITY_KEY = 'vision.bg.intensity';

const MODEL = {
  fast: LFM2_5_VL_450M_QUANTIZED,
  max: LFM2_5_VL_1_6B_QUANTIZED,
} as const;

// Never hammer the accelerator faster than this between background items, even
// at "Extreme" — leaves the UI thread and the GPU/CPU some breathing room (a
// single caption usually takes longer than this anyway).
const MIN_BG_INTERVAL_MS = 1200;

// Map the intensity slider (0..1) to a target throughput in memes/hour.
// Bottom of the range trickles (~6/hr); the very top means "as fast as the
// model allows" (Infinity → just the floor interval between items).
export function memesPerHour(intensity: number): number {
  const v = Math.max(0, Math.min(1, intensity));
  if (v >= 0.97) return Infinity;
  const minRate = 6;
  const maxRate = 600;
  return Math.round(minRate * Math.pow(maxRate / minRate, v));
}

// Human label for the current intensity band.
export function intensityLabel(intensity: number): string {
  const v = Math.max(0, Math.min(1, intensity));
  if (v < 0.3) return 'Conservative';
  if (v < 0.6) return 'Balanced';
  if (v < 0.97) return 'Aggressive';
  return 'Extreme';
}

// Delay between background items for a given intensity.
function bgIntervalMs(intensity: number): number {
  const rate = memesPerHour(intensity);
  if (rate === Infinity) return MIN_BG_INTERVAL_MS;
  return Math.max(MIN_BG_INTERVAL_MS, Math.round(3_600_000 / rate));
}

// What a single description yields. `caption` is the display/search sentence;
// the rest are folded into the searchable term bag and the tag chips.
export interface VisionResult {
  caption: string;
  subjects: string[];
  text: string; // text visibly written in the meme, verbatim
  tags: string[]; // open-vocabulary search keywords (format/topic/emotion/name)
}

export interface VisionApi {
  enabled: boolean; // user has opted in (model is allowed to download/load)
  quality: VisionQuality;
  ready: boolean; // model loaded and able to describe
  progress: number; // 0..1 model download/load progress
  busy: boolean; // a generation is currently running
  error: string | null;
  setEnabled: (on: boolean) => void;
  setQuality: (q: VisionQuality) => void;
  describe: (jpegPath: string, ocrHint?: string) => Promise<VisionResult | null>;

  // Background processing — a paced trickle that describes the library while the
  // app is open, throttled by the intensity slider.
  backgroundEnabled: boolean;
  backgroundIntensity: number; // 0..1
  running: boolean; // an enrichment pass (burst or background tick) is active
  setBackgroundEnabled: (on: boolean) => void;
  setBackgroundIntensity: (v: number) => void;
  // Burst path for the "Describe N now" button — mutex-guarded so it can never
  // run concurrently with the background loop (one accelerator, one generation).
  // Resolves to 'busy' if another pass already holds the lock.
  runEnrichment: (
    opts?: { onProgress?: (p: EnrichProgress) => void; shouldCancel?: () => boolean }
  ) => Promise<EnrichResult | 'busy'>;
}

const Ctx = createContext<VisionApi | null>(null);

// Keep the model terse and machine-readable. Low temperature is baked into the
// model constant's generationConfig (0.1), so with a strict schema it returns
// compact JSON we can parse deterministically.
const SYSTEM_PROMPT =
  'You are a meme cataloging engine. You look at a single image and output ONLY ' +
  'a compact JSON object describing it for search. No prose, no markdown, no code fences.';

const USER_PROMPT =
  'Describe this meme so it can be found later by search. Respond with ONLY this JSON ' +
  'object and nothing else:\n' +
  '{"caption": "<one vivid sentence: what is happening and why it is funny>", ' +
  '"subjects": ["<main people, characters, or objects>"], ' +
  '"text": "<all text visible in the image, verbatim; empty string if none>", ' +
  '"tags": ["<5-10 lowercase keywords: meme format/template name if known, topic, emotion, named characters>"]}\n' +
  'If it is not a meme, still describe the image the same way.';

// Cap the injected OCR so it can't bloat the prompt (prefill cost) — a hint,
// not a transcript.
const OCR_HINT_MAX = 280;

// Build the user turn, optionally grounding it with text ML Kit already read so
// the small model doesn't have to re-OCR small text from a downscaled frame.
function userTurn(ocrHint?: string): string {
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
  return v.map((x) => String(x).trim()).filter(Boolean).slice(0, 16);
}

// Pull a JSON object out of the model's reply and coerce it into a VisionResult.
// Defensive: models occasionally wrap JSON in prose or fences, or emit a bare
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
      : // No parseable caption: use the raw reply (sans any JSON braces) so the
        // meme is still describable rather than blank.
        reply.replace(/[{}]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 240);
  return {
    caption,
    subjects: asStringArray(obj.subjects),
    text: typeof obj.text === 'string' ? obj.text.trim() : '',
    tags: asStringArray(obj.tags),
  };
}

export function VisionProvider({ children }: { children: React.ReactNode }) {
  const [enabled, setEnabledState] = useState(false);
  const [quality, setQualityState] = useState<VisionQuality>('fast');
  const [bgEnabled, setBgEnabledState] = useState(false);
  const [bgIntensity, setBgIntensityState] = useState(0.25);
  const [hydrated, setHydrated] = useState(false);
  const [running, setRunning] = useState(false);

  // Load persisted preferences once. Until hydrated we keep the model from
  // loading (preventLoad) so a re-install never auto-downloads ~hundreds of MB.
  useEffect(() => {
    (async () => {
      const [en, q, bg, bi] = await Promise.all([
        getSetting(ENABLED_KEY),
        getSetting(QUALITY_KEY),
        getSetting(BG_ENABLED_KEY),
        getSetting(BG_INTENSITY_KEY),
      ]);
      if (q === 'max' || q === 'fast') setQualityState(q);
      setEnabledState(en === '1');
      setBgEnabledState(bg === '1');
      const parsed = bi != null ? Number(bi) : NaN;
      if (Number.isFinite(parsed)) setBgIntensityState(Math.max(0, Math.min(1, parsed)));
      setHydrated(true);
    })().catch(() => setHydrated(true));
  }, []);

  const llm = useLLM({ model: MODEL[quality], preventLoad: !(hydrated && enabled) });

  const setEnabled = (on: boolean) => {
    setEnabledState(on);
    setSetting(ENABLED_KEY, on ? '1' : '0').catch(() => {});
  };
  const setQuality = (q: VisionQuality) => {
    setQualityState(q);
    setSetting(QUALITY_KEY, q).catch(() => {});
  };
  const setBackgroundEnabled = (on: boolean) => {
    setBgEnabledState(on);
    setSetting(BG_ENABLED_KEY, on ? '1' : '0').catch(() => {});
  };
  const setBackgroundIntensity = (v: number) => {
    const c = Math.max(0, Math.min(1, v));
    setBgIntensityState(c);
    setSetting(BG_INTENSITY_KEY, String(c)).catch(() => {});
  };

  // The describe primitive. Kept in a ref so the background loop / burst pass
  // always call the latest version (llm gets a new identity each render).
  const describe = async (jpegPath: string, ocrHint?: string): Promise<VisionResult | null> => {
    if (!llm.isReady) return null;
    // Stateless one-shot: generate() does NOT accumulate conversation history,
    // so every meme is described from a clean slate (no drift, no unbounded
    // context growth across a whole library).
    const reply = await llm.generate([
      { role: 'system', content: SYSTEM_PROMPT },
      { role: 'user', content: userTurn(ocrHint), mediaPath: jpegPath },
    ]);
    return parseVision(reply);
  };
  const enricherRef = useRef<VisionEnricher>({ ready: false, describe });
  enricherRef.current = { ready: enabled && llm.isReady, describe };

  // One generation at a time on a single accelerator: this mutex makes the
  // background trickle and the manual burst mutually exclusive. A blocked caller
  // gets 'busy' rather than racing the model.
  const busyRef = useRef(false);
  const runGuarded = async <T,>(fn: () => Promise<T>): Promise<T | 'busy'> => {
    if (busyRef.current) return 'busy';
    busyRef.current = true;
    setRunning(true);
    try {
      return await fn();
    } finally {
      busyRef.current = false;
      setRunning(false);
    }
  };

  const runEnrichment = (
    opts?: { onProgress?: (p: EnrichProgress) => void; shouldCancel?: () => boolean }
  ) => runGuarded(() => enrichLibrary(enricherRef.current, opts ?? {}));

  // Paced background loop: describe one pending meme, wait the interval implied
  // by the intensity slider, repeat. Only alive while the app is open (a true
  // OS-level background service needs the native module — see the research
  // notes). Re-runs whenever readiness or intensity changes.
  useEffect(() => {
    if (!(hydrated && bgEnabled && enabled && llm.isReady)) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const interval = bgIntervalMs(bgIntensity);

    const loop = async () => {
      if (cancelled) return;
      let status: 'done' | 'failed' | 'empty' | 'busy' = 'busy';
      try {
        status = await runGuarded(() => enrichNextMeme(enricherRef.current));
      } catch {
        status = 'failed';
      }
      if (cancelled) return;
      // empty → poll slowly for newly-indexed memes; busy → a burst holds the
      // lock, retry soon; otherwise pace by the chosen throughput.
      const delay = status === 'empty' ? 60_000 : status === 'busy' ? 3_000 : interval;
      timer = setTimeout(loop, delay);
    };
    timer = setTimeout(loop, 2_000); // small settle delay after becoming ready
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [hydrated, bgEnabled, enabled, llm.isReady, bgIntensity]);

  const api = useMemo<VisionApi>(() => {
    return {
      enabled,
      quality,
      ready: enabled && llm.isReady,
      progress: llm.downloadProgress ?? 0,
      busy: llm.isGenerating,
      error: llm.error ? String(llm.error.message ?? llm.error) : null,
      setEnabled,
      setQuality,
      describe,
      backgroundEnabled: bgEnabled,
      backgroundIntensity: bgIntensity,
      running,
      setBackgroundEnabled,
      setBackgroundIntensity,
      runEnrichment,
    };
    // llm identity changes as state updates; depend on the fields we read.
  }, [
    enabled,
    quality,
    bgEnabled,
    bgIntensity,
    running,
    hydrated,
    llm.isReady,
    llm.isGenerating,
    llm.downloadProgress,
    llm.error,
  ]);

  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useVision(): VisionApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useVision must be used inside <VisionProvider>');
  return ctx;
}
