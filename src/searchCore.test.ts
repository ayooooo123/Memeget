import { hybridSearchScore, scoreEntry } from './searchCore';

const v = (...xs: number[]) => Float32Array.from(xs);

const entry = (
  imageVec: Float32Array,
  captionVec: Float32Array | null,
  searchText = ''
) => ({ imageVec, captionVec, searchText });

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

describe('scoreEntry', () => {
  it('equals the hybrid dense score when there are no query terms', () => {
    const query = v(1, 0);
    const image = v(0.3, 0.95);
    const caption = v(0.9, 0.44);
    expect(scoreEntry(query, [], entry(image, caption, 'anything at all'))).toBeCloseTo(
      hybridSearchScore(query, image, caption)
    );
  });

  it('adds a partial lexical boost for some-but-not-all term matches', () => {
    const query = v(0, 1);
    const image = v(0, 1); // dense score 1.0
    const e = entry(image, null, 'gigachad flexing');
    // 1 of 2 terms present → +0.35 * 0.5, no all-match boost.
    expect(scoreEntry(query, ['gigachad', 'wojak'], e)).toBeCloseTo(1 + 0.35 * 0.5);
  });

  it('adds the decisive all-terms boost when every term is present', () => {
    const query = v(0, 1);
    const image = v(0, 1);
    const e = entry(image, null, 'distracted boyfriend meme');
    // both terms present → +0.35 (full lexical) +0.6 (all-match).
    expect(scoreEntry(query, ['distracted', 'boyfriend'], e)).toBeCloseTo(1 + 0.35 + 0.6);
  });

  it('scores by lexical alone in null-query (lexical-only) mode', () => {
    const e = entry(v(1, 0), v(1, 0), 'crying wojak');
    // No dense channel; both terms hit → 0.35 + 0.6.
    expect(scoreEntry(null, ['crying', 'wojak'], e)).toBeCloseTo(0.35 + 0.6);
    // No terms hit → 0.
    expect(scoreEntry(null, ['gigachad'], e)).toBeCloseTo(0);
  });

  it('matches lowercased query terms case-as-stored via includes', () => {
    // Haystack is stored raw; the caller lowercases the query. A capitalized
    // stored word is therefore not matched by a lowercase term — preserving the
    // exact pre-cache behavior.
    const e = entry(v(0, 1), null, 'Gigachad');
    expect(scoreEntry(null, ['gigachad'], e)).toBeCloseTo(0);
  });
});
