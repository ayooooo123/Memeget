import type { EmbeddingModelSpec, EmbeddingSpace } from './embeddingModels';

export interface VisualSimilarityRecord {
  imageEmbedding: Float32Array;
  visualEmbedding: Float32Array | null;
  visualModel: string;
}

function hasActiveVisual(
  row: Pick<VisualSimilarityRecord, 'visualEmbedding' | 'visualModel'>,
  active: Pick<EmbeddingModelSpec, 'id' | 'available'>
): boolean {
  return (
    active.available &&
    !!row.visualEmbedding &&
    row.visualEmbedding.length > 0 &&
    row.visualModel === active.id
  );
}

export function selectVisualSimilarityVector(
  row: VisualSimilarityRecord,
  activeVisualModel: Pick<EmbeddingModelSpec, 'id' | 'available'>
): Float32Array {
  if (hasActiveVisual(row, activeVisualModel)) return row.visualEmbedding!;
  return row.imageEmbedding;
}

// Vectors for scoring ONE pair of memes. A similarity is only meaningful within
// a single space: dotting a DINO vector against a CLIP vector (different models,
// different dimensions) is noise. So the visual (DINO) space is used only when
// BOTH rows carry a vector stamped for the active visual model; any pair where
// either side is missing/stale falls back to the primary image space. (Scores
// from the two spaces are both cosines and rank tolerably together while a
// backfill is in flight; once backfill completes every pair is DINO.)
// The chosen space is reported alongside the vectors — callers that apply
// absolute cosine thresholds (tag propagation) need it, because the two spaces
// sit on very different baselines.
export function selectPairVectors(
  target: VisualSimilarityRecord,
  candidate: VisualSimilarityRecord,
  activeVisualModel: Pick<EmbeddingModelSpec, 'id' | 'available'>
): { a: Float32Array; b: Float32Array; space: EmbeddingSpace } {
  if (hasActiveVisual(target, activeVisualModel) && hasActiveVisual(candidate, activeVisualModel)) {
    return { a: target.visualEmbedding!, b: candidate.visualEmbedding!, space: 'visual' };
  }
  return { a: target.imageEmbedding, b: candidate.imageEmbedding, space: 'primary' };
}

export function visualEmbeddingNeedsRefresh(
  row: Pick<VisualSimilarityRecord, 'visualEmbedding' | 'visualModel'>,
  activeVisualModel: Pick<EmbeddingModelSpec, 'id'>
): boolean {
  return !row.visualEmbedding || row.visualEmbedding.length === 0 || row.visualModel !== activeVisualModel.id;
}
