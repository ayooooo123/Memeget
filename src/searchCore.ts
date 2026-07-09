const CAPTION_VECTOR_WEIGHT = 0.8;

function dot(a: Float32Array | number[], b: Float32Array | number[]): number {
  let s = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) s += a[i] * b[i];
  return s;
}

export function hybridSearchScore(
  queryVec: Float32Array | number[],
  imageVec: Float32Array | number[],
  captionVec: Float32Array | number[] | null
): number {
  const imageScore = dot(queryVec, imageVec);
  if (!captionVec || captionVec.length === 0) return imageScore;

  const captionScore = dot(queryVec, captionVec);
  return Math.max(imageScore, imageScore * (1 - CAPTION_VECTOR_WEIGHT) + captionScore * CAPTION_VECTOR_WEIGHT);
}
