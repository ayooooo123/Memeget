// React-free core of the teach-by-example learner and the zero-shot classifier.
// No imports from React or react-native-executorch, so it runs in a background
// JS context and — unlike its old home inside embeddings.tsx — is unit-testable
// against synthetic vectors (learnCore.test.ts).
//
// A taught label is not a raw cosine-to-exemplar match. Raw CLIP image cosine
// has a high baseline (unrelated memes score ~0.6+ against any exemplar)
// because the embeddings are anisotropic and memes look alike — so no fixed
// cosine threshold cleanly separates "is a Milady" from "isn't". Instead each
// taught label gets:
//
//  1. a small logistic-regression head trained on top of the frozen CLIP
//     features — the user's examples as positives, a CLEANED sample of the
//     library as negatives (see trainLabelModel for the cleaning), and
//  2. a nearest-exemplar (kNN) pathway that catches close template variants a
//     few-shot head can miss, judged relative to the label's own background
//     similarity distribution rather than a fixed cosine cutoff.
//
// A meme carries the tag if either pathway accepts it.

import type { Tag } from './types';

export interface LabelVec {
  label: string;
  category: string;
  vec: Float32Array;
}

// Floor for the calibrated per-label threshold (and the value used when
// calibration has nothing to calibrate against).
export const EXEMPLAR_PROB_THRESHOLD = 0.6;

// ---- learning-pipeline constants ----------------------------------------------

// Background vectors at least this cosine-similar to a taught positive are
// treated as *probably the same label, just not taught yet* and excluded from
// the negative set. Without this, teaching a label that is common in the
// library trains the head to reject its own class (the classic PU-learning
// failure). CLIP ViT-B/32 image-image: same template ≈0.85–0.95, same character
// in different scenes ≈0.7–0.85, unrelated memes ≈0.5–0.7.
const PU_EXCLUDE_COS = 0.85;

// Surviving background in this band is a *hard* negative — visually adjacent
// but below the "probably the same" line. Counted twice so the boundary is
// trained tight where the confusion actually lives instead of against easy,
// far-away negatives.
const HARD_NEG_LO = 0.65;

// Per-label threshold calibration: the acceptance threshold is pushed just
// above the highest probability the trained head assigns to any cleaned
// background vector, so a weakly-separating label can't spray false positives
// across the library. Capped so a usable label can't calibrate itself into
// never firing.
const THRESHOLD_MARGIN = 0.05;
const THRESHOLD_CAP = 0.92;

// kNN pathway: accept when the best-exemplar cosine is BOTH extreme relative to
// the label's own background distribution (z-score) AND high in absolute terms.
// The absolute floor keeps a degenerate (tiny-sigma) background from promoting
// mediocre matches; the z-score keeps the high CLIP baseline from mattering.
const KNN_MIN_COS = 0.88;
const KNN_MIN_Z = 3;
// Background stats are unreliable below this many samples — disable kNN.
const KNN_MIN_BG = 8;

export interface LabelHead {
  label: string;
  category: string;
  w: Float32Array; // weights; operate on the mean-centered image vector
  b: number; // bias
  threshold: number; // calibrated per-label acceptance probability
  protos: Float32Array[]; // raw normalized positive exemplars (kNN pathway)
  negProtos: Float32Array[]; // raw normalized explicit negatives (kNN veto)
  knnMu: number; // mean of background best-exemplar cosines
  knnSigma: number; // stddev of same; <= 0 disables the kNN pathway
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
export function headProb(head: { w: Float32Array; b: number }, x: ArrayLike<number>): number {
  let z = head.b;
  const w = head.w;
  for (let i = 0; i < w.length; i++) z += w[i] * x[i];
  if (z < -30) return 0;
  if (z > 30) return 1;
  return 1 / (1 + Math.exp(-z));
}

// Cosine of `a` against the most similar vector in `vs` (all unit-normalized).
function maxCosTo(a: ArrayLike<number>, vs: ArrayLike<number>[]): number {
  let best = -1;
  for (const v of vs) {
    let s = 0;
    const n = Math.min(a.length, v.length);
    for (let i = 0; i < n; i++) s += a[i] * v[i];
    if (s > best) best = s;
  }
  return best;
}

// Fit a binary logistic regression separating `positives` from `negatives`.
// Classes are re-weighted because positives are few and negatives many; L2
// keeps the few-shot boundary from overfitting. Pure vector math.
//
// On a big background this is up to ~250 iters × hundreds of 512-dim vectors on
// the single JS thread — so it's async and time-slices via createYielder(), and
// stops early once the largest per-step weight move is noise.
export async function fitLogistic(
  positives: number[][],
  negatives: number[][],
  opts: { iters?: number; lr?: number; l2?: number } = {}
): Promise<{ w: Float32Array; b: number }> {
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
    let gmax = 0;
    for (let i = 0; i < dim; i++) {
      const g = gw[i] / total + l2 * w[i];
      const ag = g < 0 ? -g : g;
      if (ag > gmax) gmax = ag;
      w[i] -= lr * g;
    }
    b -= lr * (gb / total);
    // Full-batch GD on a few-shot 512-dim problem converges well before the
    // iteration cap on most teach sets; once the largest per-step weight move is
    // noise (<1e-4 on unit-norm CLIP features), further passes only burn CPU.
    if (lr * gmax < 1e-4) break;
    // Hand control back to React whenever this pass has held the JS thread for a
    // frame — so teaching stays responsive whether the negative set is 50 or
    // 5,000 vectors. (Fixed-interval yielding couldn't make that guarantee.)
    await tick();
  }
  return { w, b };
}

// Everything trainLabelModel needs, in both spaces: `raw` vectors are the
// unit-normalized CLIP embeddings (similarity space — used for cleaning and the
// kNN pathway), `centered` are the same vectors with the library mean removed
// (the space the logistic head operates in).
export interface TrainLabelInputs {
  label: string;
  category: string;
  posRaw: number[][];
  posCentered: number[][];
  negRaw: number[][]; // explicit "this is NOT a <label>" corrections
  negCentered: number[][];
  backgroundRaw: ArrayLike<number>[];
  backgroundCentered: number[][];
  otherPosRaw: number[][]; // other taught labels' positives
  otherPosCentered: number[][];
}

// Build the full model for one taught label. This is where the learning-quality
// work happens; fitLogistic is just the optimizer.
export async function trainLabelModel(inp: TrainLabelInputs): Promise<LabelHead> {
  const { posRaw, posCentered } = inp;

  // ---- clean the unlabeled background (PU learning) + mine hard negatives ----
  const keptBg: number[][] = [];
  const bgMaxCos: number[] = [];
  for (let i = 0; i < inp.backgroundRaw.length; i++) {
    const c = maxCosTo(inp.backgroundRaw[i], posRaw);
    if (c >= PU_EXCLUDE_COS) continue; // probably this label, just untagged
    keptBg.push(inp.backgroundCentered[i]);
    bgMaxCos.push(c);
    if (c >= HARD_NEG_LO) keptBg.push(inp.backgroundCentered[i]); // hard negative ×2
  }

  // Other labels' positives are definitionally not this label — EXCEPT when
  // taxonomies overlap (a "Sad Pepe" exemplar is also a "Pepe"). Ones that are
  // near-copies of this label's positives are ambiguous, and using them as
  // negatives makes sibling labels fight; drop those, keep the rest.
  const keptOther: number[][] = [];
  for (let i = 0; i < inp.otherPosRaw.length; i++) {
    if (maxCosTo(inp.otherPosRaw[i], posRaw) >= PU_EXCLUDE_COS) continue;
    keptOther.push(inp.otherPosCentered[i]);
  }

  // A handful of explicit "not this" corrections would be drowned out by
  // hundreds of background samples, so replicate each so it carries real weight
  // (~1 copy per 25 background items) — one correction visibly moves the
  // boundary.
  const negBoost = Math.max(1, Math.round(keptBg.length / 25));
  const corrections: number[][] = [];
  for (const n of inp.negCentered) for (let k = 0; k < negBoost; k++) corrections.push(n);

  const { w, b } = await fitLogistic(posCentered, [...keptBg, ...keptOther, ...corrections]);

  // ---- calibrate the acceptance threshold against the cleaned background ----
  let maxBgProb = 0;
  for (const x of keptBg) {
    const p = headProb({ w, b }, x);
    if (p > maxBgProb) maxBgProb = p;
  }
  const threshold = Math.min(
    THRESHOLD_CAP,
    Math.max(EXEMPLAR_PROB_THRESHOLD, maxBgProb + THRESHOLD_MARGIN)
  );

  // ---- background stats for the kNN pathway ----
  let knnMu = 0;
  let knnSigma = 0;
  if (bgMaxCos.length >= KNN_MIN_BG) {
    for (const c of bgMaxCos) knnMu += c;
    knnMu /= bgMaxCos.length;
    let v = 0;
    for (const c of bgMaxCos) v += (c - knnMu) * (c - knnMu);
    knnSigma = Math.sqrt(v / bgMaxCos.length);
  }

  return {
    label: inp.label,
    category: inp.category,
    w,
    b,
    threshold,
    protos: posRaw.map((p) => Float32Array.from(p)),
    negProtos: inp.negRaw.map((p) => Float32Array.from(p)),
    knnMu,
    knnSigma,
  };
}

export interface ExemplarScore {
  prob: number; // best pathway probability, for display/ranking
  matched: boolean; // does the meme carry the tag
}

// Score one meme against one taught label: the head pathway against its
// calibrated threshold, plus the kNN pathway — nearest positive exemplar,
// vetoed when an explicit negative exemplar is even closer (the user's "NOT
// this" beats template similarity), judged as a z-score against the label's own
// background distribution.
export function scoreExemplar(
  head: LabelHead,
  raw: ArrayLike<number>,
  centered: ArrayLike<number>
): ExemplarScore {
  let prob = headProb(head, centered);
  let matched = prob >= head.threshold;

  if (head.knnSigma > 0 && head.protos.length > 0) {
    const pos = maxCosTo(raw, head.protos);
    const neg = head.negProtos.length ? maxCosTo(raw, head.negProtos) : -1;
    if (pos > neg && pos >= KNN_MIN_COS) {
      const z = (pos - head.knnMu) / head.knnSigma;
      if (z >= KNN_MIN_Z) {
        matched = true;
        // Squash the z-margin into a display probability that starts above the
        // floor threshold at the acceptance boundary and grows with the margin.
        const pKnn = 1 / (1 + Math.exp(-(z - KNN_MIN_Z + 0.5)));
        if (pKnn > prob) prob = pKnn;
      }
    }
  }
  return { prob, matched };
}

// ---- zero-shot classification --------------------------------------------------

// Zero-shot tuning. We softmax label + negative-anchor similarities together
// (CLIP-style temperature) and only keep labels that beat every "this is just
// an ordinary photo" anchor. A plain photo of a person therefore gets few or
// zero format tags instead of being forced into the top-K wrong ones.
const LOGIT_SCALE = 50; // softmax sharpness over cosine scores
const MIN_PROB = 0.05; // floor so near-zero matches are dropped

function cosTo(a: ArrayLike<number>, b: Float32Array): number {
  let s = 0;
  for (let i = 0; i < b.length; i++) s += a[i] * b[i];
  return s;
}

// Zero-shot half: text-prompt labels softmaxed against the negative anchors,
// kept only if their probability exceeds the best anchor and MIN_PROB. Written
// as tight loops over one scratch array — this runs once per meme per re-tag
// (library × ~100 vectors × 512 dims), so intermediate map/spread allocations
// were real time. Pure function of (embedding, curated labels): teaching never
// changes its output, which is what lets retagAll cache it per meme.
export function classifyPrompts(
  imageVec: number[],
  labelVecs: LabelVec[],
  negativeVecs: Float32Array[]
): Tag[] {
  const n = labelVecs.length;
  const m = negativeVecs.length;
  if (n === 0) return [];
  const scratch = new Float64Array(n + m);
  let max = -Infinity;
  for (let i = 0; i < n; i++) {
    const c = cosTo(imageVec, labelVecs[i].vec);
    scratch[i] = c;
    if (c > max) max = c;
  }
  for (let j = 0; j < m; j++) {
    const c = cosTo(imageVec, negativeVecs[j]);
    scratch[n + j] = c;
    if (c > max) max = c;
  }
  // softmax over [labels, negatives] with temperature LOGIT_SCALE
  let sum = 0;
  for (let k = 0; k < n + m; k++) {
    const e = Math.exp(LOGIT_SCALE * (scratch[k] - max));
    scratch[k] = e;
    sum += e;
  }
  if (sum === 0) sum = 1;
  let negMax = 0;
  for (let j = 0; j < m; j++) {
    const p = scratch[n + j] / sum;
    if (p > negMax) negMax = p;
  }
  const out: Tag[] = [];
  for (let i = 0; i < n; i++) {
    const p = scratch[i] / sum;
    if (p > negMax && p > MIN_PROB) {
      out.push({ label: labelVecs[i].label, category: labelVecs[i].category, score: p, source: 'prompt' });
    }
  }
  return out;
}

// Few-shot half: score every taught label via both pathways (see
// scoreExemplar). This is the only part whose output changes when the user
// teaches.
export function classifyExemplars(
  imageVec: number[],
  exemplarHeads: LabelHead[],
  mean: Float32Array | null
): Tag[] {
  if (exemplarHeads.length === 0) return [];
  const centered = mean ? imageVec.map((v, i) => v - mean[i]) : imageVec;
  const out: Tag[] = [];
  for (const h of exemplarHeads) {
    const s = scoreExemplar(h, imageVec, centered);
    if (s.matched) {
      out.push({ label: h.label, category: h.category, score: s.prob, source: 'exemplar' });
    }
  }
  return out;
}

// Merge the two halves: de-dupe by label (an exemplar match — the user's ground
// truth — always wins over a prompt match), taught matches sort first, cap topK.
export function mergeClassified(fromPrompts: Tag[], fromExemplars: Tag[], topK = 3): Tag[] {
  const best = new Map<string, Tag>();
  for (const t of fromPrompts) {
    const cur = best.get(t.label);
    if (!cur || t.score > cur.score) best.set(t.label, t);
  }
  for (const t of fromExemplars) best.set(t.label, t);

  return [...best.values()]
    .sort((a, b) => {
      if (a.source !== b.source) return a.source === 'exemplar' ? -1 : 1;
      return b.score - a.score;
    })
    .slice(0, topK);
}

// Classify a normalized image vector against both sources. Kept as the one-call
// form for fresh indexing; re-tagging composes the halves itself so it can cache
// the (teach-invariant) prompt half per meme.
export function classifyImage(
  imageVec: number[],
  labelVecs: LabelVec[],
  exemplarHeads: LabelHead[],
  mean: Float32Array | null,
  negativeVecs: Float32Array[],
  topK = 3
): Tag[] {
  return mergeClassified(
    classifyPrompts(imageVec, labelVecs, negativeVecs),
    classifyExemplars(imageVec, exemplarHeads, mean),
    topK
  );
}
