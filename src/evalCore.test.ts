// Tests for the search-quality eval harness core. Uses tiny hand-built vectors
// so the expected ranks/metrics are exact and independent of any real model —
// what's under test is the ranking + metric math and the reuse of the app's
// scoreEntry, not CLIP itself.

import {
  rankQuery,
  rankOfExpected,
  evaluateRetrieval,
  evaluateTagging,
  regressions,
  formatMetrics,
  type GoldenSet,
} from './evalCore';

// Three memes with orthogonal one-hot image vectors; queries carry no lexical
// terms so ranking is pure dense (image↔query) and fully determined.
const DENSE: GoldenSet = {
  memes: [
    { id: 'm1', imageVec: [1, 0, 0] },
    { id: 'm2', imageVec: [0, 1, 0] },
    { id: 'm3', imageVec: [0, 0, 1] },
  ],
  queries: [
    { query: 'first', queryVec: [1, 0, 0], expectedId: 'm1', terms: [] }, // rank 1
    { query: 'second', queryVec: [0, 1, 0], expectedId: 'm2', terms: [] }, // rank 1
    { query: 'third-ish', queryVec: [0.6, 0, 0.5], expectedId: 'm3', terms: [] }, // m1(.6) > m3(.5) → rank 2
  ],
};

describe('ranking', () => {
  it('ranks the best dense match first', () => {
    expect(rankQuery(DENSE.queries[0], DENSE.memes)[0].id).toBe('m1');
  });

  it('reports the 1-based rank of the expected meme', () => {
    expect(rankOfExpected(DENSE.queries[0], DENSE.memes)).toBe(1);
    expect(rankOfExpected(DENSE.queries[2], DENSE.memes)).toBe(2); // buried under m1
  });

  it('returns Infinity when the expected id is absent', () => {
    const q = { query: 'x', queryVec: [1, 0, 0], expectedId: 'nope', terms: [] };
    expect(rankOfExpected(q, DENSE.memes)).toBe(Infinity);
  });

  it('lets the lexical channel break a dense tie', () => {
    const memes = [
      { id: 'a', imageVec: [1, 0], searchText: '' },
      { id: 'b', imageVec: [1, 0], searchText: 'a wojak feels meme' }, // same image, but text hit
    ];
    const q = { query: 'wojak', queryVec: [1, 0], expectedId: 'b', terms: ['wojak'] };
    expect(rankQuery(q, memes)[0].id).toBe('b');
  });
});

describe('metrics', () => {
  it('computes Recall@k and MRR over the golden set', () => {
    const m = evaluateRetrieval(DENSE); // ranks: [1, 1, 2]
    expect(m.n).toBe(3);
    expect(m.recallAt1).toBeCloseTo(2 / 3, 6);
    expect(m.recallAt5).toBeCloseTo(1, 6);
    expect(m.mrr).toBeCloseTo((1 + 1 + 0.5) / 3, 6);
  });

  it('is 0, not NaN, on an empty set', () => {
    const m = evaluateRetrieval({ memes: [], queries: [] });
    expect(m.mrr).toBe(0);
    expect(m.recallAt1).toBe(0);
  });
});

describe('tagging (zero-shot format)', () => {
  it('classifies each meme image against the label set', () => {
    // In DENSE, each meme's image points straight at its own label vector, so
    // zero-shot classification puts the right format at #1 for all three.
    const t = evaluateTagging(DENSE);
    expect(t.n).toBe(3);
    expect(t.labels).toBe(3); // one distinct label per query
    expect(t.recallAt1).toBeCloseTo(1, 6);
    expect(t.mrr).toBeCloseTo(1, 6);
  });

  it('buries the right label when the image leans toward a wrong one', () => {
    // m3's image is closer to label "a" than to its own label "c" → rank 2.
    const g: GoldenSet = {
      memes: [
        { id: 'm1', imageVec: [1, 0, 0] },
        { id: 'm3', imageVec: [0.6, 0, 0.5] },
      ],
      queries: [
        { query: 'a', queryVec: [1, 0, 0], expectedId: 'm1' },
        { query: 'c', queryVec: [0, 0, 1], expectedId: 'm3' },
      ],
    };
    const t = evaluateTagging(g);
    expect(t.recallAt1).toBeCloseTo(0.5, 6); // m1 #1, m3 #2
    expect(t.recallAt3).toBeCloseTo(1, 6);
    expect(t.mrr).toBeCloseTo((1 + 0.5) / 2, 6);
  });

  it('is 0, not NaN, on an empty set', () => {
    const t = evaluateTagging({ memes: [], queries: [] });
    expect(t.mrr).toBe(0);
    expect(t.recallAt1).toBe(0);
  });
});

describe('regression gate', () => {
  const base = { n: 3, recallAt1: 0.8, recallAt5: 1, recallAt10: 1, mrr: 0.9 };

  it('flags a metric that drops beyond tolerance', () => {
    const worse = { ...base, recallAt1: 0.6 };
    expect(regressions(base, worse)).toHaveLength(1);
  });

  it('ignores noise within tolerance and any improvement', () => {
    const better = { ...base, recallAt1: 0.805, mrr: 0.95 };
    expect(regressions(base, better)).toEqual([]);
  });
});

it('prints a sample report (npm run eval)', () => {
  // Visible when this file runs; gives `npm run eval` a real, if synthetic, number.
  console.log('\n--- eval harness (synthetic sample) ---\n' + formatMetrics(evaluateRetrieval(DENSE)) + '\n');
  expect(true).toBe(true);
});
