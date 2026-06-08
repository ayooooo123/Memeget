import React, { createContext, useContext, useMemo } from 'react';
import {
  useImageEmbeddings,
  useTextEmbeddings,
  CLIP_VIT_BASE_PATCH32_IMAGE,
  CLIP_VIT_BASE_PATCH32_TEXT,
} from 'react-native-executorch';

import { normalize } from './db';
import type { Tag } from './types';

// NOTE: image and text MUST come from the same CLIP model so their vectors
// share a space. If react-native-executorch ever renames these constants,
// this is the only place to change.
const IMAGE_MODEL = CLIP_VIT_BASE_PATCH32_IMAGE;
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

// Zero-shot classify a normalized image vector against precomputed label
// vectors. Keeps labels scoring above the strongest negative anchor.
export function classifyImage(
  imageVec: number[],
  labelVecs: { label: string; category: string; vec: Float32Array }[],
  negativeVecs: Float32Array[],
  topK = 4
): Tag[] {
  const cos = (a: number[], b: Float32Array) => {
    let s = 0;
    for (let i = 0; i < b.length; i++) s += a[i] * b[i];
    return s;
  };

  const negFloor = negativeVecs.reduce((m, n) => Math.max(m, cos(imageVec, n)), 0);

  const scored = labelVecs
    .map((l) => ({ label: l.label, category: l.category, score: cos(imageVec, l.vec) }))
    .filter((l) => l.score > negFloor)
    .sort((a, b) => b.score - a.score)
    .slice(0, topK);

  return scored;
}
