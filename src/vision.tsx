import React, { createContext, useContext, useEffect, useMemo, useState } from 'react';
import {
  useLLM,
  LFM2_5_VL_450M_QUANTIZED,
  LFM2_5_VL_1_6B_QUANTIZED,
} from 'react-native-executorch';

import { getSetting, setSetting } from './db';

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

const MODEL = {
  fast: LFM2_5_VL_450M_QUANTIZED,
  max: LFM2_5_VL_1_6B_QUANTIZED,
} as const;

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
  describe: (jpegPath: string) => Promise<VisionResult | null>;
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
  const [hydrated, setHydrated] = useState(false);

  // Load persisted preferences once. Until hydrated we keep the model from
  // loading (preventLoad) so a re-install never auto-downloads ~hundreds of MB.
  useEffect(() => {
    (async () => {
      const [en, q] = await Promise.all([getSetting(ENABLED_KEY), getSetting(QUALITY_KEY)]);
      if (q === 'max' || q === 'fast') setQualityState(q);
      setEnabledState(en === '1');
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
      describe: async (jpegPath: string) => {
        if (!llm.isReady) return null;
        // Stateless one-shot: generate() does NOT accumulate conversation
        // history, so every meme is described from a clean slate (no drift,
        // no unbounded context growth across a whole library).
        const reply = await llm.generate([
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: USER_PROMPT, mediaPath: jpegPath },
        ]);
        return parseVision(reply);
      },
    };
    // llm identity changes as state updates; depend on the fields we read.
  }, [enabled, quality, hydrated, llm.isReady, llm.isGenerating, llm.downloadProgress, llm.error]);

  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useVision(): VisionApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useVision must be used inside <VisionProvider>');
  return ctx;
}
