// How confident is the zero-shot pass that it actually RECOGNIZED this meme?
//
// The app's north star assumes a meme can be named. Most memes can't: there is
// no enumerable set of templates (docs/composite-meme-understanding.md), so a
// large share of any real library is "non-standard" — an AI-gen remix, a
// screenshot, a niche shitpost, an original. The classifier will still rank 399
// curated labels for those, and the top one is nearly always wrong. Emitting it
// unqualified is what turns "we don't know" into a confident lie that then gets
// fed to the VLM as grounding and written into the caption.
//
// So classification carries a calibrated confidence, and callers that treat a
// label as an ASSERTION (the VLM grounding line above all) branch on the tier.
//
// ---- how the numbers were derived -------------------------------------------
// Measured on the 595-meme basedmemes+KYM holdout (tools/eval/golden.json) with
// the shipped encoder (MobileCLIP-S2) and the shipped 399-label vocabulary; a
// prediction counts as correct when the label or one of its associations
// appears in the meme's own corpus tags. `npm run recognition` reproduces every
// number below and gates them.
//
// Ranking signal — AUC at separating a correct top-1 label from a wrong one:
//
//   cos(image, label) - 0.5 * max cos(image, anchor)   0.853   <- shipped
//   cos(image, label)                                  0.834
//   cos(image, label) - max cos(image, anchor)         0.829
//   softmax probability (the previous rule)            0.732
//   cos(top1) - cos(top2)                              0.495
//
// The anchor term is a per-image bias correction, not a threshold: some images
// simply sit closer to all text ("a random photograph" included), and half the
// anchor similarity is the empirically best amount of that offset to remove
// (0.25-0.75 all score >= 0.847; 1.0 over-corrects and throws away the absolute
// similarity that carries most of the recognition signal). The old rule ranked
// by a softmax over the label set, which normalizes that absolute similarity
// away entirely — the worst of the five signals tested.

import type { Tag } from './types';

// The anchor-bias weight above.
export const ANCHOR_BIAS = 0.5;

// Margin floor for emitting a label at all. At 0.13 the classifier keeps MORE
// correct labels than the old rule (546 vs 510) while emitting fewer wrong ones
// (632 vs 669) — a strict improvement on both axes — and aspect-search MAP is
// unchanged (0.171 -> 0.169, within noise on 52 queries). Below it, precision
// collapses to ~5%: that band is the "non-standard" bulk, and the honest output
// there is no label at all.
export const MIN_LABEL_MARGIN = 0.13;

// Calibration: margin -> P(this label is correct), measured per bin (n=595).
// Piecewise linear through the bin midpoints, clamped at both ends, so a stored
// tag score means something a human can act on ("0.8" really is ~80% right).
const CALIBRATION: readonly (readonly [margin: number, prob: number])[] = [
  [0.115, 0.05], // [0.10,0.13) n=112 -> 4.5%
  [0.14, 0.14], // [0.13,0.15) n=77  -> 14.3%
  [0.16, 0.26], // [0.15,0.17) n=118 -> 26.3%
  [0.18, 0.38], // [0.17,0.19) n=98  -> 37.8%
  [0.2, 0.74], // [0.19,0.21) n=70  -> 74.3%
  [0.225, 0.86], // [0.21,0.24) n=71  -> 85.9%
];
const MIN_CONFIDENCE = 0.03;
const MAX_CONFIDENCE = 0.9;

export function labelConfidence(margin: number): number {
  const first = CALIBRATION[0];
  if (margin <= first[0]) return MIN_CONFIDENCE;
  for (let i = 1; i < CALIBRATION.length; i++) {
    const [x1, y1] = CALIBRATION[i];
    if (margin <= x1) {
      const [x0, y0] = CALIBRATION[i - 1];
      return y0 + ((margin - x0) / (x1 - x0)) * (y1 - y0);
    }
  }
  return MAX_CONFIDENCE;
}

// Confidence at which a guess is worth stating as a fact. margin 0.19 -> 0.56,
// where measured precision jumps from 38% to 74%; that break is the only sharp
// one in the curve, which is why the tiers sit on it.
export const RECOGNIZED_CONFIDENCE = 0.56;

// What the visual pass knows about this meme's identity:
//   recognized — a named label we'd stand behind (~81% precise, 26% of memes)
//   weak       — a guess worth offering, not asserting (~27%, 49% of memes)
//   unknown    — nothing in the vocabulary matched (25% of memes; the
//                non-standard class, where forcing a name is the failure mode)
export type RecognitionTier = 'recognized' | 'weak' | 'unknown';

// Sources that are ground truth rather than a guess: the user typed it, the
// user taught it, or it is literally written in the image.
const CERTAIN_SOURCE: Record<string, true> = {
  manual: true,
  ocr: true,
  exemplar: true,
  propagated: true,
};

// Tier for a meme's stored tags. 'vision' tags are deliberately ignored: they
// are the VLM's own open-vocabulary read, not a match against our vocabulary,
// and this verdict exists to tell the VLM how much to trust what we hand it.
export function recognitionTier(tags: readonly Tag[]): RecognitionTier {
  let best = 0;
  for (const t of tags) {
    if (t.source && CERTAIN_SOURCE[t.source]) return 'recognized';
    if (t.source === 'vision') continue;
    if (t.score > best) best = t.score;
  }
  if (best >= RECOGNIZED_CONFIDENCE) return 'recognized';
  return best > 0 ? 'weak' : 'unknown';
}
