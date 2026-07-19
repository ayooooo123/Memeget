import { VISUAL_EMBEDDING_MODEL } from './embeddingModels';
import {
  planTagPropagation,
  propagatedTag,
  PROPAGATE_MAX_TARGETS,
  PROPAGATE_MIN_COS_PRIMARY,
  PROPAGATE_MIN_COS_VISUAL,
  scorePropagationCandidate,
  termsWithLabel,
  type PropagationCandidate,
} from './tagPropagation';
import type { VisualSimilarityRecord } from './visualSearch';

const ACTIVE_DINO = { ...VISUAL_EMBEDDING_MODEL, available: true };
const INACTIVE_DINO = { ...VISUAL_EMBEDDING_MODEL, available: false };

// Unit vector at a chosen cosine against the base direction [1, 0].
const atCos = (c: number) => Float32Array.from([c, Math.sqrt(Math.max(0, 1 - c * c))]);
const BASE = atCos(1);

function rec(overrides: Partial<VisualSimilarityRecord> = {}): VisualSimilarityRecord {
  return { imageEmbedding: BASE, visualEmbedding: null, visualModel: '', ...overrides };
}

// A source/candidate pair fully stamped for the active DINO model, whose DINO
// cosine is `c` (the CLIP image vectors are deliberately identical, so a test
// that leaks into the primary space would score 1 and give itself away by
// tagging things the DINO threshold rejects).
function dinoRec(c: number): VisualSimilarityRecord {
  return rec({ visualEmbedding: atCos(c), visualModel: VISUAL_EMBEDDING_MODEL.id });
}

function cand(id: number, record: VisualSimilarityRecord, hasLabel = false): PropagationCandidate {
  return { id, record, hasLabel };
}

describe('tag propagation', () => {
  it('spreads in the DINO space at the visual threshold, not below it', () => {
    const sources = [dinoRec(1)];
    const near = cand(1, dinoRec(PROPAGATE_MIN_COS_VISUAL + 0.05));
    const far = cand(2, dinoRec(PROPAGATE_MIN_COS_VISUAL - 0.05));

    const hits = planTagPropagation(sources, [near, far], ACTIVE_DINO);
    expect(hits.map((h) => h.id)).toEqual([1]);
    expect(hits[0].score).toBeCloseTo(PROPAGATE_MIN_COS_VISUAL + 0.05, 5);
  });

  it('holds the CLIP fallback to its stricter threshold', () => {
    // No DINO vectors anywhere -> every pair scores in the primary space.
    const sources = [rec()];
    const sameTemplate = cand(1, rec({ imageEmbedding: atCos(PROPAGATE_MIN_COS_PRIMARY + 0.03) }));
    // High for CLIP, but below the primary bar — would have passed the DINO bar.
    const merelyAlike = cand(2, rec({ imageEmbedding: atCos(PROPAGATE_MIN_COS_VISUAL + 0.1) }));

    const hits = planTagPropagation(sources, [sameTemplate, merelyAlike], ACTIVE_DINO);
    expect(hits.map((h) => h.id)).toEqual([1]);
  });

  it('ignores stored DINO vectors while the visual model is unavailable', () => {
    // Both sides stamped, but the model is off: the pair must fall back to the
    // primary space, where these identical image vectors clear the bar.
    const hits = planTagPropagation([dinoRec(1)], [cand(1, dinoRec(0.2))], INACTIVE_DINO);
    expect(hits.map((h) => h.id)).toEqual([1]);
    expect(hits[0].score).toBeCloseTo(1, 5);
  });

  it('never re-tags a candidate that already carries the label', () => {
    expect(scorePropagationCandidate([rec()], cand(1, rec(), true), ACTIVE_DINO)).toBeNull();
  });

  it('skips degraded rows and mixed-dimension pairs without scoring them', () => {
    const degraded = cand(1, rec({ imageEmbedding: Float32Array.from([]) }));
    const wrongDim = cand(2, rec({ imageEmbedding: Float32Array.from([1, 0, 0]) }));
    expect(planTagPropagation([rec()], [degraded, wrongDim], ACTIVE_DINO)).toEqual([]);
  });

  it('keeps the best source when several tagged memes match one candidate', () => {
    const sources = [rec({ imageEmbedding: atCos(0.1) }), rec()];
    const hit = scorePropagationCandidate(sources, cand(1, rec()), ACTIVE_DINO);
    expect(hit?.score).toBeCloseTo(1, 5);
  });

  it('ranks across spaces by margin above each threshold, not raw cosine', () => {
    const dinoSource = dinoRec(1);
    // DINO cosine 0.85 -> margin 0.15; CLIP cosine 0.90 -> margin 0.02. The
    // DINO match must outrank the higher raw CLIP cosine.
    const dinoHit = cand(1, dinoRec(0.85));
    const clipHit = cand(2, rec({ imageEmbedding: atCos(0.9) }));

    const hits = planTagPropagation([dinoSource], [clipHit, dinoHit], ACTIVE_DINO);
    expect(hits.map((h) => h.id)).toEqual([1, 2]);
  });

  it('caps a spread at PROPAGATE_MAX_TARGETS, keeping the closest matches', () => {
    const candidates = Array.from({ length: PROPAGATE_MAX_TARGETS + 10 }, (_, i) =>
      // Cosines descend with the id, so the cap must keep the low ids.
      cand(i, rec({ imageEmbedding: atCos(0.99 - i * 0.0001) }))
    );
    const hits = planTagPropagation([rec()], candidates, ACTIVE_DINO);
    expect(hits).toHaveLength(PROPAGATE_MAX_TARGETS);
    expect(hits[0].id).toBe(0);
    expect(Math.max(...hits.map((h) => h.id))).toBe(PROPAGATE_MAX_TARGETS - 1);
  });

  it('stamps spread tags as propagated user tags carrying the match cosine', () => {
    expect(propagatedTag('pepe', 0.91)).toEqual({
      label: 'pepe',
      category: 'user',
      score: 0.91,
      source: 'propagated',
    });
  });

  it('folds the label words into extra_terms without duplicates', () => {
    expect(termsWithLabel('frog green', 'Pepe the Frog')).toBe('frog green pepe the');
    expect(termsWithLabel('', 'pepe')).toBe('pepe');
  });
});
