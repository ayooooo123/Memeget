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

// Weights for the lexical channel, factored out of the db scan so the whole
// score is unit-testable in one place. A partial keyword match nudges the score;
// an ALL-terms literal hit (the words actually appear in the meme's text, name,
// or tags) adds a decisive boost so keyword results outrank pure-semantic
// near-misses — image/text cosines top out around ~0.35, so without this a
// literal match could be buried past the result cap.
const LEXICAL_WEIGHT = 0.35;
const ALL_TERMS_BOOST = 0.6;

// One meme's full search score: the dense hybrid channel (image + optional
// caption text) plus the lexical channel. `queryVec` may be null (lexical-only
// mode, served while the text-embed model is busy); `terms` are the
// already-lowercased query words. `searchText` is the raw haystack, matched
// case-as-stored via `.includes` — the query is lowercased, the haystack is not,
// exactly as the previous inline scan did.
export function scoreEntry(
  queryVec: Float32Array | number[] | null,
  terms: string[],
  entry: {
    imageVec: Float32Array | number[];
    captionVec: Float32Array | number[] | null;
    searchText: string;
  }
): number {
  let score = queryVec ? hybridSearchScore(queryVec, entry.imageVec, entry.captionVec) : 0;
  if (terms.length) {
    const hay = entry.searchText;
    let matched = 0;
    for (const t of terms) if (hay.includes(t)) matched++;
    score += LEXICAL_WEIGHT * (matched / terms.length);
    if (matched === terms.length) score += ALL_TERMS_BOOST;
  }
  return score;
}
