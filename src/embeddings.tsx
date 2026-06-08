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

export interface LabelVec {
  label: string;
  category: string;
  vec: Float32Array;
}

// Above this image-to-image cosine, a taught exemplar is considered a match.
// Image/image similarity runs much higher than image/text, so exemplars use
// their own absolute threshold rather than the text negative-anchor floor.
const EXEMPLAR_THRESHOLD = 0.62;

// Zero-shot tuning. We softmax label + negative-anchor similarities together
// (CLIP-style temperature) and only keep labels that beat every "this is just
// an ordinary photo" anchor. A plain photo of a person therefore gets few or
// zero format tags instead of being forced into the top-K wrong ones.
const LOGIT_SCALE = 50; // softmax sharpness over cosine scores
const MIN_PROB = 0.05; // floor so near-zero matches are dropped

function cosTo(a: number[], b: Float32Array): number {
  let s = 0;
  for (let i = 0; i < b.length; i++) s += a[i] * b[i];
  return s;
}

// Classify a normalized image vector against two sources:
//  - text-prompt labels (zero-shot): softmaxed against the negative anchors;
//    kept only if their probability exceeds the best anchor and MIN_PROB.
//  - taught exemplars (image-to-image): kept if above EXEMPLAR_THRESHOLD.
// Merged and de-duplicated by label; exemplar matches (the user's ground
// truth) always win and sort first.
export function classifyImage(
  imageVec: number[],
  labelVecs: LabelVec[],
  exemplarVecs: LabelVec[],
  negativeVecs: Float32Array[],
  topK = 3
): Tag[] {
  const labelCos = labelVecs.map((l) => cosTo(imageVec, l.vec));
  const negCos = negativeVecs.map((n) => cosTo(imageVec, n));

  // softmax over [labels, negatives] with temperature LOGIT_SCALE
  const allCos = [...labelCos, ...negCos];
  const max = allCos.reduce((m, c) => Math.max(m, c), -Infinity);
  const exps = allCos.map((c) => Math.exp(LOGIT_SCALE * (c - max)));
  const sum = exps.reduce((a, b) => a + b, 0) || 1;
  const probs = exps.map((e) => e / sum);

  const n = labelVecs.length;
  const negMax = probs.slice(n).reduce((m, p) => Math.max(m, p), 0);

  const fromPrompts: Tag[] = labelVecs
    .map((l, i) => ({ label: l.label, category: l.category, score: probs[i], source: 'prompt' as const }))
    .filter((t) => t.score > negMax && t.score > MIN_PROB);

  const fromExemplars: Tag[] = exemplarVecs
    .map((l) => ({ label: l.label, category: l.category, score: cosTo(imageVec, l.vec), source: 'exemplar' as const }))
    .filter((t) => t.score > EXEMPLAR_THRESHOLD);

  // De-dupe by label: an exemplar match always wins over a prompt match.
  const best = new Map<string, Tag>();
  for (const t of [...fromPrompts, ...fromExemplars]) {
    const cur = best.get(t.label);
    if (!cur || t.source === 'exemplar' || t.score > cur.score) best.set(t.label, t);
  }

  return [...best.values()]
    .sort((a, b) => {
      if (a.source !== b.source) return a.source === 'exemplar' ? -1 : 1;
      return b.score - a.score;
    })
    .slice(0, topK);
}
