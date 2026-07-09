import { hybridSearchScore } from './searchCore';

const v = (...xs: number[]) => Float32Array.from(xs);

describe('hybridSearchScore', () => {
  it('uses caption text vectors to rescue items whose image vector is weak', () => {
    const query = v(1, 0);
    const visuallyClose = v(0.8, 0.6);
    const captionClose = v(1, 0);
    const visuallyWeak = v(0.2, 0.98);

    const imageOnly = hybridSearchScore(query, visuallyClose, null);
    const withCaption = hybridSearchScore(query, visuallyWeak, captionClose);

    expect(withCaption).toBeGreaterThan(imageOnly);
  });

  it('keeps the old image score when a meme has no caption vector yet', () => {
    const query = v(0, 1);
    const image = v(0.6, 0.8);

    expect(hybridSearchScore(query, image, null)).toBeCloseTo(0.8);
  });
});
