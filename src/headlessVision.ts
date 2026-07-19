import { LLMModule } from 'react-native-executorch';

import {
  MODEL,
  SYSTEM_PROMPT,
  runVision,
  userTurn,
  type VisionQuality,
  type VisionResult,
} from './visionCore';
import type { VisionEnricher } from './indexer';

// Headless (no-React) VLM (Gemma 4 E2B / LFM2.5-VL) via the LLMModule CLASS — the same model the
// useLLM hook drives, but instantiable OUTSIDE a component tree. That is the one
// capability that makes true background indexing possible: the OS-scheduled task
// (backgroundTask.ts) has no React provider, so it can't use the hook, but it
// can `new`/load this and run inference with the app closed.
let instance: { quality: VisionQuality; mod: LLMModule } | null = null;
let loading: Promise<void> | null = null;

export function headlessReady(): boolean {
  return instance != null;
}

// Load (or switch to) the model for the given quality. Idempotent and
// serialized so overlapping callers don't double-load a ~hundreds-of-MB model.
export async function loadHeadless(quality: VisionQuality): Promise<void> {
  if (instance && instance.quality === quality) return;
  if (loading) return loading;
  loading = (async () => {
    if (instance) {
      try {
        instance.mod.delete();
      } catch {
        // ignore
      }
      instance = null;
    }
    const mod = await LLMModule.fromModelName(MODEL[quality]);
    // The class does NOT auto-apply the model card's sampling config (the hook
    // does), so set it here; also widen the token-batch window to trim needless
    // native→JS callbacks during background generation.
    mod.configure({
      generationConfig: {
        ...MODEL[quality].generationConfig,
        outputTokenBatchSize: 64,
        batchTimeInterval: 500,
      },
    });
    instance = { quality, mod };
  })().finally(() => {
    loading = null;
  });
  return loading;
}

// Free the model from memory — call at the end of a background session so the
// process footprint drops before the OS freezes/reclaims it.
export function unloadHeadless(): void {
  if (instance) {
    try {
      instance.mod.delete();
    } catch {
      // ignore
    }
    instance = null;
  }
}

// A VisionEnricher backed by the headless model, for runBackgroundSession. Same
// stateless one-shot generate() + JSON parse as the foreground describe path.
export function headlessEnricher(): VisionEnricher {
  return {
    ready: instance != null,
    describe: async (
      jpegPath: string,
      ocrHint?: string,
      grounding?: string
    ): Promise<VisionResult | null> => {
      const inst = instance;
      if (!inst) return null;
      // runVision adds the hard output cap + prefill/decode telemetry. The class
      // exposes getPromptTokensCount (plural) — adapted to the runner's getter.
      return runVision(
        {
          generate: (m) => inst.mod.generate(m),
          interrupt: () => inst.mod.interrupt(),
          getGeneratedTokenCount: () => inst.mod.getGeneratedTokenCount(),
          getPromptTokenCount: () => inst.mod.getPromptTokensCount(),
        },
        [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: userTurn(ocrHint, grounding), mediaPath: jpegPath },
        ],
        inst.quality
      );
    },
  };
}
