// React-free core of tag spreading: when the user manually tags memes, the tag
// can also be applied to the library's visual look-alikes — the other crops,
// re-captions, and template variants of the same meme — ranked in the stored
// visual-similarity space (DINOv2 when a compatible export is configured, else
// the primary CLIP image space, chosen per pair by selectPairVectors).
//
// Spreading writes tags without a confirmation step, so the thresholds are
// deliberately strict — "same template" territory, not "same character". They
// differ per space because the spaces sit on very different baselines:
// CLIP ViT-B/32 image-image cosines are anisotropic and high (unrelated memes
// score ~0.5–0.7, same template ≈0.85–0.95 — see learnCore's PU notes), while
// DINOv2 cosines spread much lower (unrelated ≈0.2–0.5, near-duplicates and
// tight template variants ≈0.7+).
//
// Candidates from the two spaces are ranked together by MARGIN above their own
// space's threshold, not by raw cosine — a raw sort would let the high CLIP
// baseline crowd out every DINO match while a backfill is in flight.

import type { EmbeddingModelSpec } from './embeddingModels';
import type { Tag } from './types';
import { selectPairVectors, type VisualSimilarityRecord } from './visualSearch';

// Minimum cosine, per space, for a look-alike to receive the spread tag.
export const PROPAGATE_MIN_COS_VISUAL = 0.7;
export const PROPAGATE_MIN_COS_PRIMARY = 0.88;

// Hard cap on how many memes one tag application can spread to. Bounds both the
// write and the blast radius of a mis-tag in a very homogeneous library; the
// cap keeps the closest matches (ranking is margin-descending).
export const PROPAGATE_MAX_TARGETS = 40;

export interface PropagationCandidate {
  id: number;
  hasLabel: boolean; // already carries the label (case-insensitive) — never re-tagged
  record: VisualSimilarityRecord;
}

export interface PropagationHit {
  id: number;
  score: number; // cosine in whichever space scored the pair (stored on the tag)
  margin: number; // score minus that space's threshold (cross-space ranking key)
}

function dot(a: Float32Array, b: Float32Array): number {
  let s = 0;
  for (let i = 0; i < a.length; i++) s += a[i] * b[i];
  return s;
}

// Score ONE candidate against every tagged source and return its best
// above-threshold hit, or null when no source clears the bar. Exposed
// separately from planTagPropagation so a caller scanning a large library can
// chunk the loop and yield between chunks.
export function scorePropagationCandidate(
  sources: VisualSimilarityRecord[],
  candidate: PropagationCandidate,
  activeVisualModel: Pick<EmbeddingModelSpec, 'id' | 'available'>
): PropagationHit | null {
  if (candidate.hasLabel) return null;
  if (candidate.record.imageEmbedding.length === 0) return null; // degraded row

  let best: PropagationHit | null = null;
  for (const source of sources) {
    if (source.imageEmbedding.length === 0) continue;
    const { a, b, space } = selectPairVectors(source, candidate.record, activeVisualModel);
    if (a.length !== b.length) continue; // mixed-dim rows from an older index
    const score = dot(a, b);
    const margin =
      score - (space === 'visual' ? PROPAGATE_MIN_COS_VISUAL : PROPAGATE_MIN_COS_PRIMARY);
    if (margin >= 0 && (!best || margin > best.margin)) {
      best = { id: candidate.id, score, margin };
    }
  }
  return best;
}

// Rank hits for writing: closest first (by margin, so DINO and CLIP matches
// compare fairly), truncated to the spread cap.
export function rankPropagationHits(hits: PropagationHit[]): PropagationHit[] {
  return [...hits].sort((x, y) => y.margin - x.margin).slice(0, PROPAGATE_MAX_TARGETS);
}

// Full plan in one call (small-library / test path): which candidates get the
// tag, best-first, capped.
export function planTagPropagation(
  sources: VisualSimilarityRecord[],
  candidates: PropagationCandidate[],
  activeVisualModel: Pick<EmbeddingModelSpec, 'id' | 'available'>
): PropagationHit[] {
  const hits: PropagationHit[] = [];
  for (const c of candidates) {
    const hit = scorePropagationCandidate(sources, c, activeVisualModel);
    if (hit) hits.push(hit);
  }
  return rankPropagationHits(hits);
}

// The tag a spread writes: same user category as a manual tag, but stamped
// 'propagated' (and scored with the actual cosine) so a spread tag is
// distinguishable from one the user applied by hand.
export function propagatedTag(label: string, score: number): Tag {
  return { label, category: 'user', score, source: 'propagated' };
}

// Append the label's words to a meme's extra_terms (deduped) so the new tag is
// also reachable by text search, not just visible as a chip. Shared by the
// bulk-tag sheet (for the memes tagged directly) and the spread (for the
// look-alikes it reaches).
export function termsWithLabel(extraTerms: string, label: string): string {
  const set = new Set(extraTerms.split(/\s+/).filter(Boolean));
  for (const w of label.toLowerCase().split(/\s+/)) if (w) set.add(w);
  return [...set].join(' ');
}
