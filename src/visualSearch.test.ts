import {
  selectPairVectors,
  selectVisualSimilarityVector,
  visualEmbeddingNeedsRefresh,
  type VisualSimilarityRecord,
} from './visualSearch';
import { VISUAL_EMBEDDING_MODEL } from './embeddingModels';

const v = (...xs: number[]) => Float32Array.from(xs);
const ACTIVE_DINO = { ...VISUAL_EMBEDDING_MODEL, available: true };

function rec(overrides: Partial<VisualSimilarityRecord> = {}): VisualSimilarityRecord {
  return {
    imageEmbedding: v(1, 0),
    visualEmbedding: null,
    visualModel: '',
    ...overrides,
  };
}

describe('visual search routing', () => {
  it('uses the stored visual vector when it matches the active visual model', () => {
    const visualEmbedding = v(0, 1);

    expect(
      selectVisualSimilarityVector(
        rec({ visualEmbedding, visualModel: VISUAL_EMBEDDING_MODEL.id }),
        ACTIVE_DINO
      )
    ).toBe(visualEmbedding);
  });

  it('falls back to the primary image vector when visual data is missing or stale', () => {
    const imageEmbedding = v(1, 0);
    const staleVisual = v(0, 1);

    expect(
      selectVisualSimilarityVector(rec({ imageEmbedding }), VISUAL_EMBEDDING_MODEL)
    ).toBe(imageEmbedding);
    expect(
      selectVisualSimilarityVector(
        rec({ imageEmbedding, visualEmbedding: staleVisual, visualModel: 'other-dino-export' }),
        ACTIVE_DINO
      )
    ).toBe(imageEmbedding);
  });

  it('falls back while the active visual model is unavailable', () => {
    const imageEmbedding = v(1, 0);
    const visualEmbedding = v(0, 1);

    expect(
      selectVisualSimilarityVector(
        rec({ imageEmbedding, visualEmbedding, visualModel: VISUAL_EMBEDDING_MODEL.id }),
        { ...VISUAL_EMBEDDING_MODEL, available: false }
      )
    ).toBe(imageEmbedding);
  });

  it('scores a pair in the visual space only when BOTH rows carry a stamped vector', () => {
    const dinoA = v(0, 1, 0);
    const dinoB = v(0, 0.9, 0.1);
    const both = selectPairVectors(
      rec({ visualEmbedding: dinoA, visualModel: VISUAL_EMBEDDING_MODEL.id }),
      rec({ visualEmbedding: dinoB, visualModel: VISUAL_EMBEDDING_MODEL.id }),
      ACTIVE_DINO
    );
    expect(both.a).toBe(dinoA);
    expect(both.b).toBe(dinoB);
    expect(both.space).toBe('visual');
  });

  it('never mixes spaces in a pair: one stale side drops BOTH to the image space', () => {
    const targetImage = v(1, 0);
    const candidateImage = v(0.8, 0.6);
    const { a, b, space } = selectPairVectors(
      rec({
        imageEmbedding: targetImage,
        visualEmbedding: v(0, 1, 0), // target has a fresh DINO vector…
        visualModel: VISUAL_EMBEDDING_MODEL.id,
      }),
      rec({ imageEmbedding: candidateImage }), // …but the candidate is not backfilled yet
      ACTIVE_DINO
    );
    expect(a).toBe(targetImage);
    expect(b).toBe(candidateImage);
    expect(space).toBe('primary');
  });

  it('knows whether a row needs visual embedding refresh', () => {
    expect(visualEmbeddingNeedsRefresh(rec(), VISUAL_EMBEDDING_MODEL)).toBe(true);
    expect(
      visualEmbeddingNeedsRefresh(
        rec({ visualEmbedding: v(0, 1), visualModel: VISUAL_EMBEDDING_MODEL.id }),
        VISUAL_EMBEDDING_MODEL
      )
    ).toBe(false);
    expect(
      visualEmbeddingNeedsRefresh(
        rec({ visualEmbedding: v(0, 1), visualModel: 'stale' }),
        VISUAL_EMBEDDING_MODEL
      )
    ).toBe(true);
  });
});
