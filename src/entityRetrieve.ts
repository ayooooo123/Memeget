// Pure entity-pack retrieval: score image embeddings against offline-curated
// entity exemplars and emit Tag-ready hits for indexer merge.
//
// Ranking reuses recognition's anchor-bias margin (cos - ANCHOR_BIAS * anchorSim)
// and labelConfidence calibration so entity scores stay on the same 0..1 scale
// as prompt/format labels until entity-specific calibration exists.

import { ANCHOR_BIAS, labelConfidence } from './recognition';
import { mergeDurableTags } from './tagMerge';
import type { Tag } from './types';

export { ANCHOR_BIAS };

/** Default margin floor (same starting point as recognition MIN_LABEL_MARGIN). */
export const DEFAULT_ENTITY_MIN_MARGIN = 0.13;

const DEFAULT_TOP_K = 5;

export interface EntityExemplar {
  label: string;
  category: string;
  vector: number[];
  associations: string[];
  positive: boolean;
}

export interface EntityHit {
  label: string;
  category: string;
  score: number;
  margin: number;
  associations: string[];
  source: 'entity_pack';
}

export interface RetrieveParams {
  imageVec: number[];
  exemplars: readonly EntityExemplar[];
  /** Optional per-image bias, e.g. max cos to NEGATIVE_ANCHORS. */
  anchorSim?: number;
  /** Max labels emitted. Default 5. */
  topK?: number;
  /** Min margin after anchor correction. Default 0.13. */
  minMargin?: number;
}

function cosine(a: ArrayLike<number>, b: ArrayLike<number>): number {
  let s = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) s += a[i] * b[i];
  return s;
}

/**
 * Best positive exemplar per label.
 * margin = cos - ANCHOR_BIAS * (anchorSim ?? 0)
 * score  = labelConfidence(margin)
 * Filtered by minMargin (default 0.13), sorted score desc, truncated to topK (default 5).
 */
export function retrieveEntities(p: RetrieveParams): EntityHit[] {
  const {
    imageVec,
    exemplars,
    anchorSim = 0,
    topK = DEFAULT_TOP_K,
    minMargin = DEFAULT_ENTITY_MIN_MARGIN,
  } = p;

  const bias = ANCHOR_BIAS * (Number.isFinite(anchorSim) ? anchorSim : 0);

  // Best cosine (hence best margin) per label among positive exemplars only.
  const best = new Map<
    string,
    { category: string; cos: number; associations: string[] }
  >();

  for (const ex of exemplars) {
    if (!ex.positive) continue;
    const cos = cosine(imageVec, ex.vector);
    const prev = best.get(ex.label);
    if (!prev || cos > prev.cos) {
      best.set(ex.label, {
        category: ex.category,
        cos,
        associations: ex.associations,
      });
    }
  }

  const hits: EntityHit[] = [];
  for (const [label, row] of best) {
    const margin = row.cos - bias;
    if (margin < minMargin) continue;
    hits.push({
      label,
      category: row.category,
      score: labelConfidence(margin),
      margin,
      associations: row.associations,
      source: 'entity_pack',
    });
  }

  hits.sort((a, b) => b.score - a.score || b.margin - a.margin);
  if (hits.length > topK) hits.length = topK;
  return hits;
}

/** Map retrieval hits → Tag[] for indexer merge (label lower-cased). */
export function entityHitsToTags(hits: EntityHit[]): Tag[] {
  return hits.map((h) => ({
    label: h.label.toLowerCase(),
    category: h.category,
    score: h.score,
    source: 'entity_pack' as const,
  }));
}

/**
 * Retrieve entity hits for an image, merge them into tags, and fold hit
 * associations into the caller's world-knowledge map (mutates `assoc`).
 */
export function applyEntityTags(
  imageVec: number[],
  tags: Tag[],
  exemplars: readonly EntityExemplar[],
  assoc: Map<string, string[]>,
  negativeAnchorSim?: number,
  cap = 4
): { tags: Tag[]; hits: EntityHit[] } {
  if (!exemplars.length) return { tags, hits: [] };
  const hits = retrieveEntities({
    imageVec,
    exemplars,
    ...(negativeAnchorSim !== undefined ? { anchorSim: negativeAnchorSim } : {}),
  });
  if (!hits.length) return { tags, hits };
  for (const h of hits) {
    if (!h.associations.length) continue;
    // entityHitsToTags lower-cases labels; keep assoc keys aligned.
    const key = h.label.toLowerCase();
    const prev = assoc.get(key) ?? assoc.get(h.label) ?? [];
    const merged = [...prev, ...h.associations];
    assoc.set(key, merged);
    if (h.label !== key) assoc.set(h.label, merged);
  }
  return { tags: mergeDurableTags([...tags, ...entityHitsToTags(hits)], cap), hits };
}
