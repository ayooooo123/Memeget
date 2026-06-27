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

// A taught label is no longer a raw cosine-to-exemplar match. Raw CLIP image
// cosine has a high baseline (unrelated memes score ~0.6+ against any exemplar)
// because the embeddings are anisotropic and memes look alike — so no cosine
// threshold ever cleanly separates "is a Milady" from "isn't".
//
// Instead each taught label gets its own small logistic-regression head trained
// on top of the frozen CLIP features: the user's examples as positives, a random
// sample of the library as negatives. The head learns the *discriminative*
// direction (what makes a Milady different from everything else) and outputs a
// calibrated probability — a real Milady ~0.95, an unrelated meme ~0.03.
export const EXEMPLAR_PROB_THRESHOLD = 0.6; // sigmoid prob above which we tag

export interface LabelHead {
  label: string;
  category: string;
  w: Float32Array; // weights; operate on the mean-centered image vector
  b: number; // bias
}

// Cooperative time-slicer. Heavy on-device loops (head training, full-library
// re-tagging) run on the single JS thread, so without breaks they block React
// from rendering and handling touches — the app "freezes" while teaching.
// Yielding on a fixed iteration count is fragile: the work per iteration scales
// with the library/sample size, so the same `i & 63` that's fine on a small
// library still locks up a big one. Instead we yield on a *time* budget: call
// `await tick()` every iteration and it only actually hands a macrotask back to
// React once the current synchronous run has used up ~one frame (8 ms). That
// caps blocking at a frame regardless of how much data there is.
export function createYielder(budgetMs = 8): () => Promise<void> {
  let start = Date.now();
  return async function tick(): Promise<void> {
    if (Date.now() - start < budgetMs) return;
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    start = Date.now();
  };
}

// Probability that a (mean-centered) vector belongs to this head's label.
export function headProb(head: LabelHead, x: number[]): number {
  let z = head.b;
  const w = head.w;
  for (let i = 0; i < w.length; i++) z += w[i] * x[i];
  if (z < -30) return 0;
  if (z > 30) return 1;
  return 1 / (1 + Math.exp(-z));
}

// Train a binary logistic-regression head separating `positives` (the taught
// examples) from `negatives` (a background sample of the library). Classes are
// re-weighted because positives are few and negatives many; L2 keeps the few-
// shot boundary from overfitting. Pure vector math — no CLIP/api call.
//
// On a big library this is ~250 iters × hundreds of 512-dim negatives = tens of
// millions of multiply-adds per head, all on the single JS thread. Run straight
// through it blocks React from rendering/handling touch and the app freezes
// while teaching — so it's async and time-slices via createYielder(), handing a
// macrotask back to React whenever a pass has held the thread for ~a frame.
export async function trainHead(
  label: string,
  category: string,
  positives: number[][],
  negatives: number[][],
  opts: { iters?: number; lr?: number; l2?: number } = {}
): Promise<LabelHead> {
  const dim = positives[0]?.length ?? negatives[0]?.length ?? 512;
  const iters = opts.iters ?? 250;
  const lr = opts.lr ?? 0.5;
  const l2 = opts.l2 ?? 5e-3;
  const w = new Float32Array(dim);
  let b = 0;

  const nPos = positives.length;
  const nNeg = negatives.length;
  const total = nPos + nNeg || 1;
  // Balanced class weights so a handful of positives aren't drowned out.
  const wPos = nPos ? total / (2 * nPos) : 0;
  const wNeg = nNeg ? total / (2 * nNeg) : 0;

  const gw = new Float32Array(dim);
  const tick = createYielder();
  for (let it = 0; it < iters; it++) {
    gw.fill(0);
    let gb = 0;
    const accum = (x: number[], y: number, cw: number) => {
      let z = b;
      for (let i = 0; i < dim; i++) z += w[i] * x[i];
      const p = z < -30 ? 0 : z > 30 ? 1 : 1 / (1 + Math.exp(-z));
      const g = cw * (p - y);
      for (let i = 0; i < dim; i++) gw[i] += g * x[i];
      gb += g;
    };
    for (const x of positives) accum(x, 1, wPos);
    for (const x of negatives) accum(x, 0, wNeg);
    for (let i = 0; i < dim; i++) {
      gw[i] = gw[i] / total + l2 * w[i];
      w[i] -= lr * gw[i];
    }
    b -= lr * (gb / total);
    // Hand control back to React whenever this pass has held the JS thread for a
    // frame — so teaching stays responsive whether the negative set is 50 or
    // 5,000 vectors. (Fixed-interval yielding couldn't make that guarantee.)
    await tick();
  }
  return { label, category, w, b };
}

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
//  - taught labels (few-shot): each label's logistic-regression head scores the
//    mean-centered vector; kept if its probability beats EXEMPLAR_PROB_THRESHOLD.
// Merged and de-duplicated by label; taught matches (the user's ground truth)
// always win and sort first.
export function classifyImage(
  imageVec: number[],
  labelVecs: LabelVec[],
  exemplarHeads: LabelHead[],
  mean: Float32Array | null,
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

  const centered = mean ? imageVec.map((v, i) => v - mean[i]) : imageVec;
  const fromExemplars: Tag[] = exemplarHeads
    .map((h) => ({ label: h.label, category: h.category, score: headProb(h, centered), source: 'exemplar' as const }))
    .filter((t) => t.score > EXEMPLAR_PROB_THRESHOLD);

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
