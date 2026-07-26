// Does the classifier know when it does NOT know?
//
// Every other eval here asks whether the right label ranks first. This one asks
// the question that decides how a NON-STANDARD meme is handled: given an image,
// can we tell "I recognize this format" from "I have never seen this and should
// say so"? Get that wrong and the pipeline asserts a famous template over an
// AI-gen remix, feeds the name to the VLM as grounding, and writes it into the
// caption, the tags and the search text — a wrong answer that looks like a
// right one.
//
// Model-free and deterministic like the rest of the harness: it scores
// precomputed vectors (tools/eval/label-vectors.json + golden.json), so it runs
// in CI with no torch and no device.
//
// Ground truth is free: each golden meme carries its own corpus tags, and a
// predicted label counts as correct when the label or one of its association
// terms appears among them. That under-counts a few near-misses ("Protest" on a
// Charlottesville meme), which is fine — every rule is graded by the same
// yardstick, and the comparison is what the thresholds are set from.

import { ANCHOR_BIAS, MIN_LABEL_MARGIN, RECOGNIZED_CONFIDENCE, labelConfidence } from './recognition';

export interface EvalLabel {
  label: string;
  terms: string[]; // label + associations, lowercased — the correctness key
  vec: number[];
}

export interface EvalImage {
  id: string;
  vec: number[];
  truthTerms: string[]; // the meme's own corpus tags, lowercased
}

function cosine(a: number[], b: number[]): number {
  let s = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) s += a[i] * b[i];
  return s; // both L2-normalized
}

// Word-level overlap, so "sad pepe" matches a meme tagged "pepe" but "pepe"
// never matches "pepehands" by substring.
function words(text: string): string[] {
  return text.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
}

export function isCorrect(label: EvalLabel, truthTerms: readonly string[]): boolean {
  const truth = new Set(truthTerms.flatMap(words));
  return label.terms.some((t) => words(t).some((w) => truth.has(w)));
}

export interface Prediction {
  label: EvalLabel;
  margin: number;
  confidence: number;
}

// The shipped rule: rank by cosine minus a fraction of the image's similarity to
// the "not a recognizable format" anchors, keep what clears the margin floor.
export function predict(
  image: EvalImage,
  labels: readonly EvalLabel[],
  anchors: readonly number[][],
  topK = 3
): Prediction[] {
  let anchorMax = -Infinity;
  for (const a of anchors) anchorMax = Math.max(anchorMax, cosine(image.vec, a));
  const bias = Number.isFinite(anchorMax) ? ANCHOR_BIAS * anchorMax : 0;
  return labels
    .map((label) => {
      const margin = cosine(image.vec, label.vec) - bias;
      return { label, margin, confidence: labelConfidence(margin) };
    })
    .filter((p) => p.margin >= MIN_LABEL_MARGIN)
    .sort((a, b) => b.margin - a.margin)
    .slice(0, topK);
}

// The rule this replaced: softmax over [labels, anchors] at temperature 50,
// keep labels beating both every anchor and a flat 0.05 floor. Kept as the A/B
// floor — the shipped rule has to stay better than it.
export function predictSoftmax(
  image: EvalImage,
  labels: readonly EvalLabel[],
  anchors: readonly number[][],
  topK = 3
): Prediction[] {
  const cos = [...labels.map((l) => cosine(image.vec, l.vec)), ...anchors.map((a) => cosine(image.vec, a))];
  const max = Math.max(...cos);
  const exp = cos.map((c) => Math.exp(50 * (c - max)));
  const sum = exp.reduce((s, e) => s + e, 0) || 1;
  const probs = exp.map((e) => e / sum);
  const negMax = Math.max(0, ...probs.slice(labels.length));
  return labels
    .map((label, i) => ({ label, margin: probs[i], confidence: probs[i] }))
    .filter((p) => p.confidence > negMax && p.confidence > 0.05)
    .sort((a, b) => b.confidence - a.confidence)
    .slice(0, topK);
}

export interface RuleMetrics {
  coverage: number; // memes given at least one label
  top1Precision: number; // of those, the top label is right
  tagPrecision: number; // over every emitted label
  right: number; // correct labels emitted
  wrong: number; // incorrect labels emitted
}

export function evaluateRule(
  images: readonly EvalImage[],
  predictor: (image: EvalImage) => Prediction[]
): RuleMetrics {
  let covered = 0;
  let top1 = 0;
  let right = 0;
  let wrong = 0;
  for (const img of images) {
    const preds = predictor(img);
    if (preds.length === 0) continue;
    covered++;
    if (isCorrect(preds[0].label, img.truthTerms)) top1++;
    for (const p of preds) {
      if (isCorrect(p.label, img.truthTerms)) right++;
      else wrong++;
    }
  }
  const emitted = right + wrong;
  return {
    coverage: images.length ? covered / images.length : 0,
    top1Precision: covered ? top1 / covered : 0,
    tagPrecision: emitted ? right / emitted : 0,
    right,
    wrong,
  };
}

// Area under the ROC curve for "top-1 label is correct", i.e. how well a
// confidence signal ORDERS recognized memes above unrecognized ones. This is
// the number that says whether an abstention rule is even possible; 0.5 is a
// coin flip. Computed from ranks (ties averaged) rather than by sweeping
// thresholds.
export function auc(scores: readonly number[], correct: readonly boolean[]): number {
  const idx = scores.map((_, i) => i).sort((a, b) => scores[a] - scores[b]);
  const rank = new Array<number>(scores.length);
  for (let i = 0; i < idx.length; ) {
    let j = i;
    while (j + 1 < idx.length && scores[idx[j + 1]] === scores[idx[i]]) j++;
    const avg = (i + j) / 2 + 1;
    for (let k = i; k <= j; k++) rank[idx[k]] = avg;
    i = j + 1;
  }
  let pos = 0;
  let rankSum = 0;
  for (let i = 0; i < scores.length; i++) {
    if (correct[i]) {
      pos++;
      rankSum += rank[i];
    }
  }
  const neg = scores.length - pos;
  if (pos === 0 || neg === 0) return NaN;
  return (rankSum - (pos * (pos + 1)) / 2) / (pos * neg);
}

// Every candidate ranking signal, scored on the same question: is the top-1
// label correct? All of them are monotone transforms of the label cosine WITHIN
// an image, so they pick the same top label and differ only in how they order
// memes against each other — which is exactly the abstention decision. This
// table is why the shipped signal is the shipped signal.
export function signalAucs(
  images: readonly EvalImage[],
  labels: readonly EvalLabel[],
  anchors: readonly number[][]
): { name: string; auc: number }[] {
  const rows: Record<string, number[]> = {
    'cos - 0.5*anchor (shipped)': [],
    'cos alone': [],
    'cos - anchor': [],
    'softmax probability': [],
    'cos top1 - top2': [],
  };
  const correct: boolean[] = [];
  for (const img of images) {
    const cos = labels.map((l) => cosine(img.vec, l.vec));
    const anchorCos = anchors.map((a) => cosine(img.vec, a));
    let top = 0;
    for (let i = 1; i < cos.length; i++) if (cos[i] > cos[top]) top = i;
    const sorted = [...cos].sort((a, b) => b - a);
    const anchorMax = anchorCos.length ? Math.max(...anchorCos) : 0;
    const all = [...cos, ...anchorCos];
    const max = Math.max(...all);
    const exp = all.map((c) => Math.exp(50 * (c - max)));
    const sum = exp.reduce((s, e) => s + e, 0) || 1;

    rows['cos - 0.5*anchor (shipped)'].push(cos[top] - ANCHOR_BIAS * anchorMax);
    rows['cos alone'].push(cos[top]);
    rows['cos - anchor'].push(cos[top] - anchorMax);
    rows['softmax probability'].push(exp[top] / sum);
    rows['cos top1 - top2'].push(sorted[0] - (sorted[1] ?? 0));
    correct.push(isCorrect(labels[top], img.truthTerms));
  }
  return Object.entries(rows).map(([name, scores]) => ({ name, auc: auc(scores, correct) }));
}

export interface TierStats {
  tier: 'recognized' | 'weak' | 'unknown';
  n: number;
  share: number;
  precision: number; // of the memes in this tier, top label correct
}

// The tier table: what fraction of a real library lands in each tier and how
// often the tier's claim holds. `unknown` covers the memes the shipped rule
// emits nothing for; its "precision" is what we WOULD have been right about had
// we guessed anyway — the cost of abstaining, and the evidence that abstaining
// is right.
export function tierTable(
  images: readonly EvalImage[],
  labels: readonly EvalLabel[],
  anchors: readonly number[][]
): TierStats[] {
  const counts = { recognized: [0, 0], weak: [0, 0], unknown: [0, 0] };
  for (const img of images) {
    const [best] = predict(img, labels, anchors, 1);
    const forced = best ?? forcedTop1(img, labels, anchors);
    const tier = !best ? 'unknown' : best.confidence >= RECOGNIZED_CONFIDENCE ? 'recognized' : 'weak';
    counts[tier][0]++;
    if (forced && isCorrect(forced.label, img.truthTerms)) counts[tier][1]++;
  }
  return (['recognized', 'weak', 'unknown'] as const).map((tier) => ({
    tier,
    n: counts[tier][0],
    share: images.length ? counts[tier][0] / images.length : 0,
    precision: counts[tier][0] ? counts[tier][1] / counts[tier][0] : 0,
  }));
}

// The best label with the margin floor switched OFF — what the classifier would
// have said if it were never allowed to abstain. Carries the raw cosine too, so
// the eval can compare ranking signals (cosine alone vs anchor-corrected).
export function forcedTop1(
  image: EvalImage,
  labels: readonly EvalLabel[],
  anchors: readonly number[][]
): (Prediction & { cos: number }) | null {
  let anchorMax = -Infinity;
  for (const a of anchors) anchorMax = Math.max(anchorMax, cosine(image.vec, a));
  const bias = Number.isFinite(anchorMax) ? ANCHOR_BIAS * anchorMax : 0;
  let best: (Prediction & { cos: number }) | null = null;
  for (const label of labels) {
    const cos = cosine(image.vec, label.vec);
    const margin = cos - bias;
    if (!best || margin > best.margin) {
      best = { label, margin, cos, confidence: labelConfidence(margin) };
    }
  }
  return best;
}

// Calibration check: within each score band, how often the label is actually
// right. A calibrated score means the two columns track each other — that is
// what lets `recognitionTier` (and any future UI) treat a score as a
// probability instead of a vibe.
export interface CalibrationBin {
  lo: number;
  hi: number;
  n: number;
  claimed: number; // mean predicted confidence in the band
  actual: number; // measured precision in the band
}

export function calibration(
  images: readonly EvalImage[],
  labels: readonly EvalLabel[],
  anchors: readonly number[][],
  edges: readonly number[] = [0.1, 0.25, 0.4, 0.6, 0.8, 1.01]
): CalibrationBin[] {
  const bins: CalibrationBin[] = [];
  let lo = 0;
  for (const hi of edges) {
    bins.push({ lo, hi, n: 0, claimed: 0, actual: 0 });
    lo = hi;
  }
  for (const img of images) {
    for (const p of predict(img, labels, anchors)) {
      const bin = bins.find((b) => p.confidence >= b.lo && p.confidence < b.hi);
      if (!bin) continue;
      bin.n++;
      bin.claimed += p.confidence;
      if (isCorrect(p.label, img.truthTerms)) bin.actual++;
    }
  }
  for (const b of bins) {
    if (b.n === 0) continue;
    b.claimed /= b.n;
    b.actual /= b.n;
  }
  return bins.filter((b) => b.n > 0);
}

export interface RecognitionReport {
  rules: { name: string; metrics: RuleMetrics }[];
  signals: { name: string; auc: number }[];
  tiers: TierStats[];
  bins: CalibrationBin[];
}

export function formatRecognition(r: RecognitionReport): string {
  const pct = (x: number) => `${(x * 100).toFixed(1)}%`;
  return [
    'rule (up to 3 labels emitted per meme):',
    ...r.rules.map(
      ({ name, metrics: m }) =>
        `  ${name.padEnd(26)} coverage ${pct(m.coverage).padStart(6)}   top-1 ${pct(
          m.top1Precision
        ).padStart(6)}   tag-precision ${pct(m.tagPrecision).padStart(6)}   ${m.right} right / ${
          m.wrong
        } wrong`
    ),
    '',
    'ranking signal (AUC: orders a correct top-1 above a wrong one):',
    ...r.signals.map(({ name, auc: a }) => `  ${name.padEnd(26)} ${a.toFixed(3)}`),
    '',
    'tiers (what the app tells the VLM):',
    ...r.tiers.map(
      (t) =>
        `  ${t.tier.padEnd(11)} ${pct(t.share).padStart(6)} of memes   top label right ${pct(
          t.precision
        )}   (n=${t.n})`
    ),
    '',
    'calibration (claimed confidence vs measured precision):',
    ...r.bins.map(
      (b) =>
        `  [${b.lo.toFixed(2)},${b.hi.toFixed(2)})  n=${String(b.n).padStart(4)}  claimed ${pct(
          b.claimed
        ).padStart(6)}  actual ${pct(b.actual).padStart(6)}`
    ),
  ].join('\n');
}
