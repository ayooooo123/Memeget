import { LLMModule } from 'react-native-executorch';

import { MODEL, SYSTEM_PROMPT, parseVision, userTurn, type VisionResult } from './visionCore';
import type { VisionEnricher } from './indexer';

// Headless (no-React) VLM (LFM2.5-VL 1.6B) via the LLMModule CLASS — the same model the
// useLLM hook drives, but instantiable OUTSIDE a component tree. That is the one
// capability that makes true background indexing possible: the OS-scheduled task
// (backgroundTask.ts) has no React provider, so it can't use the hook, but it
// can `new`/load this and run inference with the app closed.
let instance: { mod: LLMModule } | null = null;
let loading: Promise<void> | null = null;

export function headlessReady(): boolean {
  return instance != null;
}

// Load the model. Idempotent and serialized so overlapping callers don't
// double-load a ~hundreds-of-MB model.
export async function loadHeadless(): Promise<void> {
  if (instance) return;
  if (loading) return loading;
  loading = (async () => {
    const mod = await LLMModule.fromModelName(MODEL);
    // Apply the model card's recommended generation settings (temp 0.1 etc.),
    // which the hook applies automatically but the class does not.
    mod.configure({ generationConfig: MODEL.generationConfig });
    instance = { mod };
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
      if (!instance) return null;
      const reply = await instance.mod.generate([
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: userTurn(ocrHint, grounding), mediaPath: jpegPath },
      ]);
      return parseVision(reply);
    },
  };
}
