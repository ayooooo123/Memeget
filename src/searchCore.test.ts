import { hybridSearchScore } from './searchCore';

const v = (...xs: number[]) => Float32Array.from(xs);

describe('hybridSearchScore', () => {
  it('keeps the plain image score when a meme has no caption vector yet', () => {
    const query = v(0, 1);
    const image = v(0.6, 0.8);

    expect(hybridSearchScore(query, image, null)).toBeCloseTo(0.8);
    expect(hybridSearchScore(query, image, v())).toBeCloseTo(0.8);
  });

  it('boosts a meme whose caption strongly matches the query', () => {
    const query = v(1, 0);
    const image = v(0.2, 0.98);
    const caption = v(0.95, 0.31);

    const withCaption = hybridSearchScore(query, image, caption);
    const withoutCaption = hybridSearchScore(query, image, null);
    expect(withCaption).toBeGreaterThan(withoutCaption);
  });

  it('gives NO boost for a caption at the unrelated-text baseline', () => {
    const query = v(1, 0);
    const image = v(0.3, 0.95);
    // Text-text cosine of ~0.5 is what two UNRELATED captions score — it must
    // not move the ranking.
    const baselineCaption = v(0.5, 0.87);

    expect(hybridSearchScore(query, image, baselineCaption)).toBeCloseTo(
      hybridSearchScore(query, image, null)
    );
  });

  it('never lets a described-but-unrelated meme outrank an undescribed relevant one', () => {
    const query = v(1, 0);

    // Undescribed meme whose image genuinely matches (strong for cross-modal).
    const relevantImage = v(0.32, 0.947);
    const relevant = hybridSearchScore(query, relevantImage, null);

    // Described meme that is visually and textually unrelated: weak image
    // cosine, caption cosine at the unrelated-text baseline.
    const unrelatedImage = v(0.12, 0.993);
    const unrelatedCaption = v(0.55, 0.835);
    const unrelated = hybridSearchScore(query, unrelatedImage, unrelatedCaption);

    expect(relevant).toBeGreaterThan(unrelated);
  });
});
