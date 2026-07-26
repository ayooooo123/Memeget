// Tests for the calibrated recognition verdict. The thresholds themselves are
// measured, not asserted, here — `npm run recognition` is what re-derives them
// from real vectors. What these lock in is the CONTRACT the rest of the app
// leans on: a score is a probability, and a meme nothing matched must come out
// as 'unknown' rather than as a low-confidence claim.

import {
  MIN_LABEL_MARGIN,
  RECOGNIZED_CONFIDENCE,
  labelConfidence,
  recognitionTier,
} from './recognition';
import type { Tag } from './types';

const tag = (over: Partial<Tag> = {}): Tag => ({
  label: 'Pepe the Frog',
  category: 'character',
  score: 0.3,
  source: 'prompt',
  ...over,
});

describe('labelConfidence', () => {
  it('is a probability, monotonic in the margin', () => {
    let prev = -1;
    for (let m = 0; m <= 0.4; m += 0.005) {
      const p = labelConfidence(m);
      expect(p).toBeGreaterThanOrEqual(0);
      expect(p).toBeLessThanOrEqual(1);
      expect(p).toBeGreaterThanOrEqual(prev);
      prev = p;
    }
  });

  it('never promises much for a margin below the emission floor', () => {
    // Everything under the floor measured ~5% precise, so a stray caller that
    // scores one anyway must not get a confident number back.
    expect(labelConfidence(MIN_LABEL_MARGIN - 0.01)).toBeLessThan(0.15);
    expect(labelConfidence(0)).toBeLessThan(0.05);
    expect(labelConfidence(-1)).toBeLessThan(0.05);
  });

  it('separates the two tiers at the measured break', () => {
    // The 0.19 margin is where measured precision jumps 38% -> 74%.
    expect(labelConfidence(0.18)).toBeLessThan(RECOGNIZED_CONFIDENCE);
    expect(labelConfidence(0.2)).toBeGreaterThan(RECOGNIZED_CONFIDENCE);
  });

  it('saturates rather than extrapolating past the measured range', () => {
    expect(labelConfidence(5)).toBeLessThan(1);
    expect(labelConfidence(5)).toBe(labelConfidence(0.3));
  });
});

describe('recognitionTier', () => {
  it('is unknown with no tags — the non-standard case', () => {
    expect(recognitionTier([])).toBe('unknown');
  });

  it('is unknown when the VLM described it but nothing in the vocabulary matched', () => {
    // A vision tag is the model's own open-vocabulary read, not a match against
    // our labels, so it must not read as "we recognize this format".
    expect(recognitionTier([tag({ label: 'exhausted', source: 'vision', score: 0.9 })])).toBe(
      'unknown'
    );
  });

  it('is weak for a low-confidence guess and recognized for a confident one', () => {
    expect(recognitionTier([tag({ score: 0.2 })])).toBe('weak');
    expect(recognitionTier([tag({ score: 0.85 })])).toBe('recognized');
  });

  it('takes the best guess, not the first or last', () => {
    expect(recognitionTier([tag({ score: 0.1 }), tag({ label: 'Wojak', score: 0.8 })])).toBe(
      'recognized'
    );
  });

  it('treats the user’s own truth as recognized however weak the guesses are', () => {
    for (const source of ['manual', 'exemplar', 'ocr', 'propagated'] as const) {
      expect(recognitionTier([tag({ score: 0.05 }), tag({ label: 'Milady', source })])).toBe(
        'recognized'
      );
    }
  });
});
