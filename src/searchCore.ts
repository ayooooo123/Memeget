// Hybrid retrieval scoring: the query vector against the meme's image embedding
// (cross-modal) plus, when present, against the CLIP TEXT embedding of the
// meme's VLM caption+tags (text↔text).
//
// The two channels live on very different scales. Image↔text cosines top out
// around ~0.35 for a good match because of CLIP's modality gap; text↔text
// cosines sit on a high anisotropic baseline — ~0.5+ even for UNRELATED text.
// Blending the raw caption cosine therefore hands every described meme a large
// flat boost and buries undescribed-but-relevant results. Instead only the
// margin ABOVE the unrelated-text baseline counts, reweighted so a strong
// caption match (~0.85+) is worth about as much as a strong image match. A meme
// with no caption vector simply scores by image alone — never penalized.
const CAPTION_COS_FLOOR = 0.55;
const CAPTION_WEIGHT = 0.9;

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
  return imageScore + CAPTION_WEIGHT * Math.max(0, captionScore - CAPTION_COS_FLOOR);
}
