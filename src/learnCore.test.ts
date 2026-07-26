// Tests for the teach-by-example learner over synthetic embedding clusters.
// Geometry mimics what matters about CLIP image space: unit-norm vectors,
// same-label items very similar (cos ≳ 0.9), unrelated items near-orthogonal
// plus a shared anisotropic baseline direction so everything has an elevated
// floor similarity (the property that killed fixed cosine thresholds).

import {
  addManualPositives,
  classifyPrompts,
  fitLogistic,
  headProb,
  scoreExemplar,
  trainLabelModel,
  type PositiveGroup,
} from './learnCore';
import { MIN_LABEL_MARGIN, RECOGNIZED_CONFIDENCE } from './recognition';

const DIM = 64;

// Deterministic LCG → uniform → gaussian (Box-Muller), so tests can't flake.
function makeRng(seed: number) {
  let s = seed >>> 0;
  const uniform = () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return (s + 1) / 4294967297; // (0, 1)
  };
  return () => {
    const u = uniform();
    const v = uniform();
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  };
}

type Rng = () => number;

function normalize(v: number[]): number[] {
  let n = 0;
  for (const x of v) n += x * x;
  n = Math.sqrt(n) || 1;
  return v.map((x) => x / n);
}

function gaussianVec(rng: Rng): number[] {
  return Array.from({ length: DIM }, () => rng());
}

// Random unit direction (a raw gaussian vector has norm ~√DIM, so mixing
// coefficients only mean anything against normalized directions).
function unitVec(rng: Rng): number[] {
  return normalize(gaussianVec(rng));
}

// A shared "meme-ness" direction: mixed into every vector so unrelated items
// still share a high similarity baseline, like real CLIP embeddings do.
function makeBase(rng: Rng): number[] {
  return unitVec(rng);
}

// Random library item: base direction + its own identity. Two random items
// land around cos ≈ 0.7 — the anisotropic CLIP-like baseline.
function randomItem(rng: Rng, base: number[]): number[] {
  const u = unitVec(rng);
  return normalize(u.map((x, i) => 1.2 * base[i] + 0.8 * x));
}

// Member of a specific label cluster: within-cluster cos ≈ 0.95, cluster item
// to random item cos ≈ 0.76.
function clusterItem(rng: Rng, base: number[], center: number[]): number[] {
  const u = unitVec(rng);
  return normalize(center.map((c, i) => c + 0.8 * base[i] + 0.4 * u[i]));
}

function meanOf(vs: number[][]): number[] {
  const m = new Array(DIM).fill(0);
  for (const v of vs) for (let i = 0; i < DIM; i++) m[i] += v[i];
  return m.map((x) => x / (vs.length || 1));
}

function centerWith(mean: number[]) {
  return (v: number[]) => v.map((x, i) => x - mean[i]);
}

// Build a training scenario: a library whose background sample is polluted
// with untagged members of the very cluster being taught.
async function trainScenario(opts: { pollution: number; positives: number; negatives?: number[][] }) {
  const rng = makeRng(42);
  const base = makeBase(rng);
  const centerDir = unitVec(rng);
    const center = normalize(centerDir.map((x, i) => 2 * base[i] + 1.5 * x));

  const backgroundRaw: number[][] = [];
  for (let i = 0; i < opts.pollution; i++) backgroundRaw.push(clusterItem(rng, base, center));
  while (backgroundRaw.length < 200) backgroundRaw.push(randomItem(rng, base));

  const mean = meanOf(backgroundRaw);
  const center_ = centerWith(mean);

  const posRaw = Array.from({ length: opts.positives }, () => clusterItem(rng, base, center));
  const negRaw = opts.negatives ?? [];

  const head = await trainLabelModel({
    label: 'cluster',
    category: 'character',
    posRaw,
    posCentered: posRaw.map(center_),
    negRaw,
    negCentered: negRaw.map(center_),
    backgroundRaw,
    backgroundCentered: backgroundRaw.map(center_),
    otherPosRaw: [],
    otherPosCentered: [],
  });

  return { rng, base, center, head, mean, center_ };
}

describe('trainLabelModel', () => {
  it('learns a taught label even when the background sample is polluted with untagged positives', async () => {
    // 30 of 200 "negatives" secretly belong to the taught cluster — the exact
    // situation of teaching a label that is common in the library.
    const { rng, base, center, head, center_ } = await trainScenario({ pollution: 30, positives: 3 });

    let hit = 0;
    for (let i = 0; i < 20; i++) {
      const raw = clusterItem(rng, base, center);
      if (scoreExemplar(head, raw, center_(raw)).matched) hit++;
    }
    expect(hit).toBeGreaterThanOrEqual(17);
  });

  it('does not tag unrelated items', async () => {
    const { rng, base, head, center_ } = await trainScenario({ pollution: 30, positives: 3 });

    let falsePositives = 0;
    for (let i = 0; i < 60; i++) {
      const raw = randomItem(rng, base);
      if (scoreExemplar(head, raw, center_(raw)).matched) falsePositives++;
    }
    expect(falsePositives).toBeLessThanOrEqual(1);
  });

  it('calibrates the threshold at or above the generic floor', async () => {
    const { head } = await trainScenario({ pollution: 0, positives: 2 });
    expect(head.threshold).toBeGreaterThanOrEqual(0.6);
    expect(head.threshold).toBeLessThanOrEqual(0.92);
  });

  it('matches a near-duplicate of a single exemplar via the kNN pathway', async () => {
    const { rng, base, center, head, center_ } = await trainScenario({ pollution: 0, positives: 1 });

    // A fresh item from the same tight cluster — nearly identical to the one
    // taught example. Whatever the (single-positive) head thinks, the
    // nearest-exemplar pathway must catch it.
    const dup = clusterItem(rng, base, center);
    const s = scoreExemplar(head, dup, center_(dup));
    expect(s.matched).toBe(true);
    expect(s.prob).toBeGreaterThan(0.5);
  });

  it('lets an explicit negative exemplar veto the kNN pathway', async () => {
    const rng = makeRng(7);
    const base = makeBase(rng);
    const centerDir = unitVec(rng);
    const center = normalize(centerDir.map((x, i) => 2 * base[i] + 1.5 * x));

    const backgroundRaw: number[][] = [];
    while (backgroundRaw.length < 200) backgroundRaw.push(randomItem(rng, base));
    const mean = meanOf(backgroundRaw);
    const center_ = centerWith(mean);

    const pos = clusterItem(rng, base, center);
    // The query sits in the same cluster; the user has explicitly said an
    // essentially identical item is NOT this label.
    const query = clusterItem(rng, base, center);
    const negExact = normalize(query.map((x, i) => x + 0.01 * (i % 2 === 0 ? 1 : -1)));

    const head = await trainLabelModel({
      label: 'cluster',
      category: 'character',
      posRaw: [pos],
      posCentered: [center_(pos)],
      negRaw: [negExact],
      negCentered: [center_(negExact)],
      backgroundRaw,
      backgroundCentered: backgroundRaw.map(center_),
      otherPosRaw: [],
      otherPosCentered: [],
    });

    const s = scoreExemplar(head, query, center_(query));
    // The negative proto is closer to the query than the positive proto, so the
    // kNN pathway must not fire for it (the boosted correction also drags the
    // head below threshold for the cluster).
    expect(s.matched).toBe(false);
  });

  it('does not use near-copies from sibling labels as negatives', async () => {
    const rng = makeRng(11);
    const base = makeBase(rng);
    const centerDir = unitVec(rng);
    const center = normalize(centerDir.map((x, i) => 2 * base[i] + 1.5 * x));

    const backgroundRaw: number[][] = [];
    while (backgroundRaw.length < 200) backgroundRaw.push(randomItem(rng, base));
    const mean = meanOf(backgroundRaw);
    const center_ = centerWith(mean);

    const posRaw = [clusterItem(rng, base, center), clusterItem(rng, base, center)];
    // A sibling label (think "Sad Pepe" next to "Pepe") taught with examples
    // from the SAME cluster: they must be excluded as negatives, not fought.
    const siblingRaw = [clusterItem(rng, base, center), clusterItem(rng, base, center)];

    const head = await trainLabelModel({
      label: 'pepe',
      category: 'character',
      posRaw,
      posCentered: posRaw.map(center_),
      negRaw: [],
      negCentered: [],
      backgroundRaw,
      backgroundCentered: backgroundRaw.map(center_),
      otherPosRaw: siblingRaw,
      otherPosCentered: siblingRaw.map(center_),
    });

    let hit = 0;
    for (let i = 0; i < 20; i++) {
      const raw = clusterItem(rng, base, center);
      if (scoreExemplar(head, raw, center_(raw)).matched) hit++;
    }
    expect(hit).toBeGreaterThanOrEqual(17);
  });
});

describe('fitLogistic', () => {
  it('separates two linearly separable clusters', async () => {
    const rng = makeRng(3);
    const a = Array.from({ length: 30 }, () => normalize(gaussianVec(rng).map((x, i) => x + (i === 0 ? 8 : 0))));
    const b = Array.from({ length: 30 }, () => normalize(gaussianVec(rng).map((x, i) => x - (i === 0 ? 8 : 0))));
    const { w, b: bias } = await fitLogistic(a, b);
    for (const x of a.slice(0, 5)) expect(headProb({ w, b: bias }, x)).toBeGreaterThan(0.8);
    for (const x of b.slice(0, 5)) expect(headProb({ w, b: bias }, x)).toBeLessThan(0.2);
  });
});

// Zero-shot half. Geometry is exact rather than sampled: an image built from
// two orthonormal directions has a cosine to each that we choose outright, so
// each case states the margin it is testing.
describe('classifyPrompts', () => {
  const e = (i: number): number[] => Array.from({ length: DIM }, (_, k) => (k === i ? 1 : 0));
  // Image with cosine `toLabel` to e0 and `toAnchor` to e1 (both orthonormal),
  // padded along e2 to stay unit-norm.
  const image = (toLabel: number, toAnchor: number): number[] => {
    const rest = Math.sqrt(Math.max(0, 1 - toLabel * toLabel - toAnchor * toAnchor));
    return e(0)
      .map((x) => x * toLabel)
      .map((x, i) => x + e(1)[i] * toAnchor + e(2)[i] * rest);
  };
  const labels = [{ label: 'Pepe the Frog', category: 'character', vec: Float32Array.from(e(0)) }];
  const anchors = [Float32Array.from(e(1))];

  it('names a meme it is close to, with a confident score', () => {
    const [tag] = classifyPrompts(image(0.34, 0.12), labels, anchors);
    expect(tag.label).toBe('Pepe the Frog');
    expect(tag.source).toBe('prompt');
    expect(tag.score).toBeGreaterThan(RECOGNIZED_CONFIDENCE);
  });

  it('says nothing about a meme that matches nothing', () => {
    // The non-standard case: middling similarity to every label. The old
    // softmax rule emitted the best of them anyway.
    expect(classifyPrompts(image(0.2, 0.18), labels, anchors)).toEqual([]);
  });

  it('discounts a label by how photo-generic the image is', () => {
    // Identical cosine to the label; the second image also sits close to the
    // "not a recognizable format" anchor, so its margin falls under the floor.
    const cos = MIN_LABEL_MARGIN + 0.06;
    expect(classifyPrompts(image(cos, 0.02), labels, anchors)).toHaveLength(1);
    expect(classifyPrompts(image(cos, 0.24), labels, anchors)).toEqual([]);
  });

  it('scores a stronger match above a weaker one', () => {
    const strong = classifyPrompts(image(0.32, 0.1), labels, anchors)[0];
    const weak = classifyPrompts(image(0.24, 0.1), labels, anchors)[0];
    expect(strong.score).toBeGreaterThan(weak.score);
  });

  it('works with no anchors and no labels', () => {
    expect(classifyPrompts(image(0.34, 0), labels, [])).toHaveLength(1);
    expect(classifyPrompts(image(0.34, 0), [], anchors)).toEqual([]);
  });
});

// Hand-applied tags as training data. The behaviour that matters: a label the
// user only ever bulk-tagged still gets a head (so it reaches memes indexed
// later), without disturbing what the user taught deliberately.
describe('addManualPositives', () => {
  const v = (n: number) => [n, 0, 0];
  const center = (x: ArrayLike<number>) => Array.from(x, (y) => y - 0.5);
  const group = (over: Partial<PositiveGroup> = {}): PositiveGroup => ({
    category: 'character',
    posRaw: [],
    posCentered: [],
    negRaw: [],
    negCentered: [],
    ...over,
  });

  it('creates a trainable group for a label that was only ever bulk-tagged', () => {
    const byLabel = new Map<string, PositiveGroup>();
    addManualPositives(byLabel, new Map([['crt', { category: 'object', vectors: [v(1), v(2)] }]]), center);
    const g = byLabel.get('crt')!;
    expect(g.category).toBe('object');
    expect(g.posRaw).toEqual([v(1), v(2)]);
    expect(g.posCentered).toEqual([center(v(1)), center(v(2))]);
  });

  it('appends to a taught label without displacing its examples or corrections', () => {
    const byLabel = new Map<string, PositiveGroup>([
      ['pepe', group({ posRaw: [v(9)], posCentered: [center(v(9))], negRaw: [v(8)], negCentered: [center(v(8))] })],
    ]);
    addManualPositives(byLabel, new Map([['pepe', { category: 'topic', vectors: [v(1)] }]]), center);
    const g = byLabel.get('pepe')!;
    expect(g.posRaw).toEqual([v(9), v(1)]); // taught example stays first
    expect(g.negRaw).toEqual([v(8)]); // "NOT a pepe" survives
    expect(g.category).toBe('character'); // the teaching's facet wins
  });

  it('caps how many bulk tags become positives', () => {
    const byLabel = new Map<string, PositiveGroup>();
    const vectors = Array.from({ length: 50 }, (_, i) => v(i));
    addManualPositives(byLabel, new Map([['schizo', { category: 'topic', vectors }]]), center, 4);
    expect(byLabel.get('schizo')!.posRaw).toHaveLength(4);
  });

  it('leaves the cap room already used by explicit teachings', () => {
    const byLabel = new Map<string, PositiveGroup>([
      ['wojak', group({ posRaw: [v(1), v(2), v(3)], posCentered: [v(1), v(2), v(3)].map(center) })],
    ]);
    addManualPositives(byLabel, new Map([['wojak', { category: 'topic', vectors: [v(4), v(5)] }]]), center, 4);
    expect(byLabel.get('wojak')!.posRaw).toEqual([v(1), v(2), v(3), v(4)]);
  });
});
