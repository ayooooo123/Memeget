import type { EmbeddingModelSpec } from './embeddingModels';

export interface VisualSimilarityRecord {
  imageEmbedding: Float32Array;
  visualEmbedding: Float32Array | null;
  visualModel: string;
}

export function selectVisualSimilarityVector(
  row: VisualSimilarityRecord,
  activeVisualModel: Pick<EmbeddingModelSpec, 'id' | 'available'>
): Float32Array {
  if (
    activeVisualModel.available &&
    row.visualEmbedding &&
    row.visualEmbedding.length > 0 &&
    row.visualModel === activeVisualModel.id
  ) {
    return row.visualEmbedding;
  }
  return row.imageEmbedding;
}

export function visualEmbeddingNeedsRefresh(
  row: Pick<VisualSimilarityRecord, 'visualEmbedding' | 'visualModel'>,
  activeVisualModel: Pick<EmbeddingModelSpec, 'id'>
): boolean {
  return !row.visualEmbedding || row.visualEmbedding.length === 0 || row.visualModel !== activeVisualModel.id;
}
