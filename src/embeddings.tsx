import React, { createContext, useContext, useMemo } from 'react';
import {
  useImageEmbeddings,
  useTextEmbeddings,
  CLIP_VIT_BASE_PATCH32_IMAGE_QUANTIZED,
  CLIP_VIT_BASE_PATCH32_TEXT,
} from 'react-native-executorch';

import { normalize } from './db';

// NOTE: image and text MUST come from the same CLIP model so their vectors
// share a space. If react-native-executorch ever renames these constants,
// this is the only place to change.
//
// The image encoder is the int8-quantized build: ~4x smaller to download and
// markedly lighter on RAM than the fp32 one (which competed with the rest of the
// app at launch). It targets the same 512-dim CLIP space as the fp32 text
// encoder, so text-query↔image search still works. There is no quantized text
// build, and the text side must stay CLIP to share the vector space, so it
// remains fp32. Switching the image encoder changes embedding values slightly,
// so a one-time re-index (Settings → Clear index, then Index) keeps old and new
// images consistent.
const IMAGE_MODEL = CLIP_VIT_BASE_PATCH32_IMAGE_QUANTIZED;
const TEXT_MODEL = CLIP_VIT_BASE_PATCH32_TEXT;

export interface EmbeddingsApi {
  ready: boolean;
  progress: number; // 0..1 model download/load progress
  error: string | null;
  embedImage: (uri: string) => Promise<number[]>; // normalized
  embedText: (text: string) => Promise<number[]>; // normalized
}

const Ctx = createContext<EmbeddingsApi | null>(null);

export function EmbeddingsProvider({ children }: { children: React.ReactNode }) {
  const image = useImageEmbeddings({ model: IMAGE_MODEL });
  const text = useTextEmbeddings({ model: TEXT_MODEL });

  const api = useMemo<EmbeddingsApi>(() => {
    const imageReady = (image as any).isReady ?? false;
    const textReady = (text as any).isReady ?? false;
    const dp = (image as any).downloadProgress;
    const tp = (text as any).downloadProgress;
    const progress =
      typeof dp === 'number' && typeof tp === 'number' ? (dp + tp) / 2 : dp ?? tp ?? 0;
    const err = (image as any).error ?? (text as any).error ?? null;

    return {
      ready: imageReady && textReady,
      progress,
      error: err ? String(err) : null,
      embedImage: async (uri: string) => normalize(Array.from(await image.forward(uri))),
      embedText: async (t: string) => normalize(Array.from(await text.forward(t))),
    };
  }, [image, text]);

  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useEmbeddings(): EmbeddingsApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useEmbeddings must be used inside <EmbeddingsProvider>');
  return ctx;
}

// The learner and zero-shot classifier live in learnCore.ts (React-free, so
// they run headlessly and are unit-testable); re-exported here so existing
// imports keep working.
export {
  EXEMPLAR_PROB_THRESHOLD,
  classifyExemplars,
  classifyImage,
  classifyPrompts,
  createYielder,
  fitLogistic,
  headProb,
  mergeClassified,
  scoreExemplar,
  trainLabelModel,
} from './learnCore';
export type { ExemplarScore, LabelHead, LabelVec, TrainLabelInputs } from './learnCore';
