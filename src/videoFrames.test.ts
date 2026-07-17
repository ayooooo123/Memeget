// Tests for the pure multi-frame video helpers. These lock in the math that
// turns several sampled frames into one meme record — the timestamp ladder,
// gist mean-pooling, distinct-frame collapsing, and the OCR/caption folding —
// so a future change can't silently regress video search quality. No native
// modules are touched; the module is deliberately dependency-free.
import {
  frameLadderMs,
  meanPoolNormalized,
  dedupeFrames,
  unionOcrText,
  flattenFrameTags,
  visionResultsSimilar,
  mergeVisionResults,
  FRAME_DEDUP_COSINE,
} from './videoFrames';
import type { VisionResult } from './visionCore';

const norm = (v: number[]): number => Math.sqrt(v.reduce((a, x) => a + x * x, 0));

describe('frameLadderMs', () => {
  it('returns a single start rung when asked for one frame', () => {
    expect(frameLadderMs(1, 18000, 300)).toEqual([300]);
  });

  it('is strictly increasing and spans start..horizon', () => {
    const ladder = frameLadderMs(8, 18000, 300);
    expect(ladder).toHaveLength(8);
    expect(ladder[0]).toBe(300);
    expect(ladder[ladder.length - 1]).toBe(18000);
    for (let i = 1; i < ladder.length; i++) {
      expect(ladder[i]).toBeGreaterThan(ladder[i - 1]);
    }
  });

  it('front-loads: gaps grow toward the horizon (geometric, not linear)', () => {
    const ladder = frameLadderMs(6, 16000, 500);
    const firstGap = ladder[1] - ladder[0];
    const lastGap = ladder[ladder.length - 1] - ladder[ladder.length - 2];
    expect(lastGap).toBeGreaterThan(firstGap);
  });

  it('clamps a nonsensical count to at least one rung', () => {
    expect(frameLadderMs(0)).toHaveLength(1);
    expect(frameLadderMs(-3)).toHaveLength(1);
  });
});

describe('meanPoolNormalized', () => {
  it('returns empty for no vectors', () => {
    expect(meanPoolNormalized([])).toEqual([]);
  });

  it('re-normalizes a single vector', () => {
    const out = meanPoolNormalized([[3, 4]]); // |v| = 5
    expect(norm(out)).toBeCloseTo(1, 6);
    expect(out[0]).toBeCloseTo(0.6, 6);
    expect(out[1]).toBeCloseTo(0.8, 6);
  });

  it('averages then normalizes to a unit vector', () => {
    const out = meanPoolNormalized([
      [1, 0],
      [0, 1],
    ]);
    expect(norm(out)).toBeCloseTo(1, 6);
    // mean is (0.5, 0.5) → normalized is symmetric
    expect(out[0]).toBeCloseTo(out[1], 6);
  });
});

describe('dedupeFrames', () => {
  it('collapses near-identical frames but keeps distinct ones', () => {
    const a = { id: 'a', embedding: [1, 0] };
    const aDup = { id: 'aDup', embedding: [1, 0] }; // cosine 1 with a
    const b = { id: 'b', embedding: [0, 1] }; // orthogonal → kept
    const kept = dedupeFrames([a, aDup, b]);
    expect(kept.map((f) => f.id)).toEqual(['a', 'b']);
  });

  it('keeps frames just below the similarity threshold', () => {
    // Build a unit vector whose cosine with [1,0] is below the dedup threshold.
    const theta = Math.acos(FRAME_DEDUP_COSINE) * 1.2; // safely more separated
    const near = { id: 'near', embedding: [Math.cos(theta), Math.sin(theta)] };
    const kept = dedupeFrames([{ id: 'base', embedding: [1, 0] }, near]);
    expect(kept).toHaveLength(2);
  });
});

describe('unionOcrText', () => {
  it('drops empties and exact duplicates', () => {
    expect(unionOcrText(['hello world', '', 'hello world'])).toBe('hello world');
  });

  it('drops a frame whose text is contained in another kept frame', () => {
    // The longer, subsuming string wins; the substring is dropped.
    expect(unionOcrText(['top text', 'top text bottom text'])).toBe('top text bottom text');
  });

  it('unions genuinely different captions across frames', () => {
    const out = unionOcrText(['scene one', 'scene two']);
    expect(out.split('\n').sort()).toEqual(['scene one', 'scene two']);
  });
});

describe('flattenFrameTags', () => {
  it('flattens without de-duplicating (indexer ranks/dedupes later)', () => {
    const tags = flattenFrameTags([
      [{ label: 'pepe', category: 'character', score: 0.9, source: 'prompt' }],
      [{ label: 'pepe', category: 'character', score: 0.8, source: 'prompt' }],
    ]);
    expect(tags).toHaveLength(2);
  });
});

describe('visionResultsSimilar', () => {
  const mk = (caption: string, text = ''): VisionResult => ({
    caption,
    text,
    subjects: [],
    tags: [],
  });

  it('treats equal caption + text as similar', () => {
    expect(visionResultsSimilar(mk('a cat', 'meow'), mk('a cat', 'meow'))).toBe(true);
  });

  it('treats a caption that contains the other as similar when text matches', () => {
    expect(visionResultsSimilar(mk('a cat sits', ''), mk('a cat', ''))).toBe(true);
  });

  it('is not similar when the on-screen text differs', () => {
    expect(visionResultsSimilar(mk('a cat', 'hello'), mk('a cat', 'goodbye'))).toBe(false);
  });
});

describe('mergeVisionResults', () => {
  it('passes a single result through untouched', () => {
    const one: VisionResult = { caption: 'solo', subjects: ['x'], text: 't', tags: ['a'] };
    expect(mergeVisionResults([one])).toBe(one);
  });

  it('unions subjects/tags/text and joins up to two distinct captions', () => {
    const merged = mergeVisionResults([
      { caption: 'a man waves', subjects: ['man'], text: 'hi', tags: ['greeting'] },
      { caption: 'a dog barks', subjects: ['dog', 'man'], text: 'woof', tags: ['greeting', 'animal'] },
    ]);
    expect(merged.caption).toBe('a man waves / a dog barks');
    expect(merged.subjects.sort()).toEqual(['dog', 'man']);
    expect(merged.tags.sort()).toEqual(['animal', 'greeting']);
    expect(merged.text.split('\n').sort()).toEqual(['hi', 'woof']);
  });

  it('collapses identical captions instead of repeating them', () => {
    const merged = mergeVisionResults([
      { caption: 'same scene', subjects: [], text: '', tags: [] },
      { caption: 'same scene', subjects: [], text: '', tags: [] },
    ]);
    expect(merged.caption).toBe('same scene');
  });
});
