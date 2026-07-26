// `npm run recognition` — the accept-gate for the classifier's abstention.
//
// Scores tools/eval/golden.json (real MobileCLIP-S2 image vectors from the
// basedmemes+KYM holdout) against tools/eval/label-vectors.json (the app's own
// 399 label prompts + 14 negative anchors, same encoder) and prints the table
// the thresholds in src/recognition.ts were read off. Re-run it after touching
// a prompt, an anchor, or the calibration curve: those numbers ARE the tuning.
//
// Both files are committed, so this runs in CI with no model and no device. If
// the vocabulary drifts from the committed vectors the run fails loudly instead
// of grading yesterday's prompts.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { MEME_LABELS, NEGATIVE_ANCHORS } from './memeLabels';
import { RECOGNIZED_CONFIDENCE, labelConfidence } from './recognition';
import {
  calibration,
  evaluateRule,
  formatRecognition,
  predict,
  predictSoftmax,
  signalAucs,
  tierTable,
  type EvalImage,
  type EvalLabel,
} from './recognitionEval';

const EVAL_DIR = join(__dirname, '..', 'tools', 'eval');
const GOLDEN = join(EVAL_DIR, 'golden.json');
const VECTORS = join(EVAL_DIR, 'label-vectors.json');

// The negative anchors the app shipped with before this eval existed. The A/B
// floor is "what we had", so it is pinned here rather than read from the
// current list.
const LEGACY_ANCHOR_TEXT = ['a random photograph', 'a plain screenshot', 'an ordinary picture'];

interface VectorFile {
  _model?: string;
  labels: { label: string; prompt: string; vec: number[] }[];
  anchors: { text: string; vec: number[] }[];
}

interface GoldenFile {
  memes: { id: string; imageVec: number[] }[];
  queries: { query: string; expectedId: string }[];
}

const have = existsSync(GOLDEN) && existsSync(VECTORS);
const load = <T>(path: string): T => JSON.parse(readFileSync(path, 'utf8')) as T;

// The eval is only meaningful if the committed vectors are the CURRENT
// vocabulary, position for position — the association terms it grades against
// are read from MEME_LABELS by index.
function assertFresh(file: VectorFile): void {
  const stale = MEME_LABELS.filter(
    (d, i) => file.labels[i]?.prompt !== d.prompt || file.labels[i]?.label !== d.label
  );
  const anchorsMoved =
    file.anchors.length !== NEGATIVE_ANCHORS.length ||
    NEGATIVE_ANCHORS.some((t, i) => file.anchors[i]?.text !== t);
  if (file.labels.length !== MEME_LABELS.length || stale.length > 0 || anchorsMoved) {
    throw new Error(
      `label-vectors.json is stale (${stale.length} label(s)/prompt(s) changed, ${
        file.labels.length
      } vs ${MEME_LABELS.length} labels${anchorsMoved ? ', anchors changed' : ''}). ` +
        'Re-run: python tools/eval/build_label_vectors.py'
    );
  }
}

(have ? describe : describe.skip)('recognition (does the classifier know when it does not know?)', () => {
  const vectors = have ? load<VectorFile>(VECTORS) : ({ labels: [], anchors: [] } as VectorFile);
  const golden = have ? load<GoldenFile>(GOLDEN) : ({ memes: [], queries: [] } as GoldenFile);

  // The corpus tags each meme carries — the query text is the meme's own tag
  // list, which is exactly the ground truth a predicted label is graded on.
  const truth = new Map(golden.queries.map((q) => [q.expectedId, q.query.split(/\s*,\s*/)]));
  const images: EvalImage[] = golden.memes
    .filter((m) => truth.has(m.id))
    .map((m) => ({ id: m.id, vec: m.imageVec, truthTerms: truth.get(m.id)! }));
  const labels: EvalLabel[] = vectors.labels.map((l, i) => ({
    label: l.label,
    terms: [l.label, ...(MEME_LABELS[i]?.associations ?? [])],
    vec: l.vec,
  }));
  const anchors = vectors.anchors.map((a) => a.vec);

  it('grades the shipped vocabulary, not a stale copy of it', () => {
    assertFresh(vectors);
    expect(images.length).toBeGreaterThan(100);
  });

  it('reports the tier table the thresholds are set from', () => {
    const shipped = evaluateRule(images, (img) => predict(img, labels, anchors));
    // What the app actually shipped before: a softmax over labels + the three
    // generic photo anchors, cut at a flat 0.05. Looked up by text so a
    // reordered anchor list can't silently redefine the baseline.
    const legacyAnchors = LEGACY_ANCHOR_TEXT.map((t) => {
      const found = vectors.anchors.find((a) => a.text === t);
      if (!found) throw new Error(`legacy anchor missing from label-vectors.json: "${t}"`);
      return found.vec;
    });
    const previous = evaluateRule(images, (img) => predictSoftmax(img, labels, legacyAnchors));
    // The same softmax rule over TODAY's anchors, to separate how much of the
    // gain is the rule and how much is the anchor set.
    const softmaxNewAnchors = evaluateRule(images, (img) => predictSoftmax(img, labels, anchors));

    // How well each candidate signal orders a correct top-1 above a wrong one.
    // The shipped one has to stay on top of this table.
    const signals = signalAucs(images, labels, anchors);
    const aucOf = (name: string) => signals.find((s) => s.name.startsWith(name))!.auc;

    const tiers = tierTable(images, labels, anchors);
    const bins = calibration(images, labels, anchors);
    console.log(
      `\nrecognition eval — ${images.length} memes, ${labels.length} labels, ${anchors.length} anchors\n` +
        formatRecognition({
          rules: [
            { name: 'calibrated (shipped)', metrics: shipped },
            { name: 'softmax + 3 anchors (was)', metrics: previous },
            { name: 'softmax + all anchors', metrics: softmaxNewAnchors },
          ],
          signals,
          tiers,
          bins,
        }) +
        '\n'
    );

    // 1. Against the configuration it replaced, the shipped rule emits MORE
    //    correct labels and FEWER wrong ones. This is the whole claim.
    expect(shipped.right).toBeGreaterThan(previous.right);
    expect(shipped.wrong).toBeLessThan(previous.wrong);
    expect(shipped.tagPrecision).toBeGreaterThan(previous.tagPrecision);

    // 2. No cheaper signal orders recognition better than the shipped one —
    //    in particular not raw cosine (no bias term) and not the softmax
    //    probability the old rule thresholded.
    const best = signals.reduce((a, b) => (b.auc > a.auc ? b : a));
    expect(best.name).toBe('cos - 0.5*anchor (shipped)');
    expect(aucOf('cos - 0.5*anchor')).toBeGreaterThan(aucOf('cos alone'));
    expect(aucOf('cos - 0.5*anchor')).toBeGreaterThan(aucOf('softmax probability'));
    expect(aucOf('cos - 0.5*anchor')).toBeGreaterThan(0.8);

    // 3. The tiers mean what they say: 'recognized' is mostly right, and the
    //    memes we decline to label are ones we'd have been mostly wrong about.
    const byTier = Object.fromEntries(tiers.map((t) => [t.tier, t]));
    expect(byTier.recognized.precision).toBeGreaterThan(0.7);
    expect(byTier.weak.precision).toBeLessThan(byTier.recognized.precision);
    expect(byTier.unknown.precision).toBeLessThan(0.2);
    // A real library is mostly non-standard; if 'unknown' ever collapses to
    // nothing the floor has drifted and we're back to naming everything.
    expect(byTier.unknown.share).toBeGreaterThan(0.1);
  });

  it('emits scores that mean what they claim', () => {
    for (const b of calibration(images, labels, anchors)) {
      if (b.n < 30) continue; // too few to say anything
      expect(Math.abs(b.claimed - b.actual)).toBeLessThan(0.2);
    }
  });

  it('puts the recognized tier where the calibration curve breaks', () => {
    // Guards the two constants against being nudged apart by hand.
    expect(labelConfidence(0.19)).toBeCloseTo(RECOGNIZED_CONFIDENCE, 1);
  });
});
