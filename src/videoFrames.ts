// Pure, React-free core for multi-frame video analysis. A video used to be
// indexed from a single keyframe (the frame at 1s); everything here is the math
// for sampling several frames across a clip and folding their analysis back into
// one meme record — WITHOUT changing the DB schema (still one embedding, one
// OCR string, one tag set per meme).
//
// The honest translation of "analyze every frame" on a phone: sample frames
// densely, collapse the ones that are visually identical (a static shot, or an
// out-of-range timestamp that clamped to the last frame), and analyze each
// DISTINCT moment once. Cost then scales with a clip's real visual diversity —
// a talking-head collapses to ~1 frame, a multi-scene edit expands to several.
//
// Kept dependency-free (only a type import, which is erased) so it's trivially
// unit-testable under plain Node — no expo-sqlite / executorch / native modules.
import type { Tag } from './types';
import type { VisionResult } from './visionCore';

// ---- tuning knobs -----------------------------------------------------------

// Frames pulled for the fast CLIP-embed + OCR + zero-shot pass. Each is a
// thumbnail decode + a CLIP forward + an ML Kit OCR, so this bounds the per-video
// cost of the fast path; visually-identical frames are collapsed before the
// (slightly heavier) OCR step, so the real work is usually well under this.
export const MAX_VIDEO_FRAMES = 8;

// Distinct frames the heavy on-device VLM (Gemma 4 E2B) will caption. Far smaller
// because each is a full generation — the dominant cost of the whole app. A
// static clip stops after the first non-repeating frame, so most videos cost 1.
export const MAX_VLM_FRAMES = 3;

// Two frames whose normalized CLIP vectors are at least this similar are treated
// as the same shot and analyzed once. High enough that genuine scene/text
// changes survive, low enough that re-encodes and clamped out-of-range frames
// collapse.
export const FRAME_DEDUP_COSINE = 0.985;

// The frame-timestamp ladder reaches toward this horizon. It is an upper reach,
// NOT an assumed duration: the caller climbs the ladder and stops as soon as a
// timestamp lands past the video's real end, so short clips simply use the
// early rungs.
export const FRAME_LADDER_HORIZON_MS = 18000;
export const FRAME_LADDER_START_MS = 300;

// ---- frame sampling ----------------------------------------------------------

// A geometric ladder of timestamps (ms) to pull frames at: front-loaded (most
// memes make their point early) but spreading toward the horizon so a handful of
// samples still cover a longer clip. Geometric rather than linear so we don't
// waste half our budget in the first second of a 15s edit.
export function frameLadderMs(
  n: number,
  horizonMs: number = FRAME_LADDER_HORIZON_MS,
  startMs: number = FRAME_LADDER_START_MS
): number[] {
  const count = Math.max(1, Math.floor(n));
  if (count === 1) return [startMs];
  const ratio = Math.pow(horizonMs / startMs, 1 / (count - 1));
  const out: number[] = [];
  for (let i = 0; i < count; i++) out.push(Math.round(startMs * Math.pow(ratio, i)));
  return out;
}

// ---- pure vector helpers (inlined to keep this module native-free) -----------

function l2normalize(vec: number[]): number[] {
  let norm = 0;
  for (const v of vec) norm += v * v;
  norm = Math.sqrt(norm) || 1;
  return vec.map((v) => v / norm);
}

function cosine(a: ArrayLike<number>, b: ArrayLike<number>): number {
  let s = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) s += a[i] * b[i];
  return s;
}

// Average a set of (already normalized) CLIP frame vectors and re-normalize, so
// one vector stands in for the whole clip's "gist" — a better search/similarity
// anchor than any single keyframe.
export function meanPoolNormalized(vectors: number[][]): number[] {
  if (vectors.length === 0) return [];
  if (vectors.length === 1) return l2normalize(vectors[0].slice());
  const dim = vectors[0].length;
  const mean = new Array<number>(dim).fill(0);
  for (const v of vectors) {
    for (let i = 0; i < dim; i++) mean[i] += v[i] ?? 0;
  }
  for (let i = 0; i < dim; i++) mean[i] /= vectors.length;
  return l2normalize(mean);
}

// Collapse visually near-identical frames so each distinct moment is analyzed
// once. Greedy against everything kept so far (order preserved) — a frame is
// dropped only if it's ~identical to one we're already keeping.
export function dedupeFrames<T extends { embedding: number[] }>(
  frames: T[],
  threshold: number = FRAME_DEDUP_COSINE
): T[] {
  const kept: T[] = [];
  for (const f of frames) {
    if (kept.some((k) => cosine(k.embedding, f.embedding) >= threshold)) continue;
    kept.push(f);
  }
  return kept;
}

// ---- text / tag folding ------------------------------------------------------

// Merge OCR text read from several frames into one deduped bag — this is how a
// caption that only appears partway through a video gets indexed. Each frame's
// OCR is one whitespace-joined string; we drop any that's already fully
// contained in what we've kept (identical frames, or a later frame that only
// adds a word) and drop earlier strings a longer one subsumes.
export function unionOcrText(texts: string[]): string {
  const kept: string[] = [];
  for (const raw of texts) {
    const t = (raw ?? '').replace(/\s+/g, ' ').trim();
    if (!t) continue;
    const lower = t.toLowerCase();
    if (kept.some((k) => k.toLowerCase().includes(lower))) continue;
    for (let i = kept.length - 1; i >= 0; i--) {
      if (lower.includes(kept[i].toLowerCase())) kept.splice(i, 1);
    }
    kept.push(t);
  }
  return kept.join('\n');
}

// Flatten per-frame classification results into one list. De-duplication by
// label (keeping the highest-confidence source/score) is left to the indexer's
// existing dedupeRankTags, so a character that appears in only one frame still
// carries its true score.
export function flattenFrameTags(perFrame: Tag[][]): Tag[] {
  return perFrame.flat();
}

// ---- VLM (caption) folding ---------------------------------------------------

function uniqLower(items: string[], cap = 16): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const it of items) {
    const t = (it ?? '').trim();
    if (!t) continue;
    const k = t.toLowerCase();
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(t);
    if (out.length >= cap) break;
  }
  return out;
}

const normCap = (s: string) => (s ?? '').toLowerCase().replace(/\s+/g, ' ').trim();

// True when a freshly-described frame says essentially the same thing as the
// previous one — its caption and on-screen text are equal or one contains the
// other. Lets the VLM pass stop early on a static clip instead of paying for a
// near-identical generation on every rung of the ladder.
export function visionResultsSimilar(a: VisionResult, b: VisionResult): boolean {
  const ca = normCap(a.caption);
  const cb = normCap(b.caption);
  const capSame = !!ca && !!cb && (ca === cb || ca.includes(cb) || cb.includes(ca));
  const ta = normCap(a.text);
  const tb = normCap(b.text);
  const textSame = ta === tb; // both blank counts as same
  return capSame && textSame;
}

// Fold several frames' VLM descriptions into one. Subjects/tags/text are unioned
// (the whole point — everything anyone typed or that appears anywhere in the
// clip becomes searchable); the caption joins up to two DISTINCT scene captions
// so a multi-scene edit reads as more than its first frame.
export function mergeVisionResults(results: VisionResult[]): VisionResult {
  if (results.length === 0) return { caption: '', subjects: [], text: '', tags: [] };
  if (results.length === 1) return results[0];

  const captions = uniqLower(
    results.map((r) => r.caption).filter(Boolean),
    3
  );
  const caption = captions.slice(0, 2).join(' / ').slice(0, 240);

  return {
    caption,
    subjects: uniqLower(results.flatMap((r) => r.subjects)),
    text: unionOcrText(results.map((r) => r.text)),
    tags: uniqLower(results.flatMap((r) => r.tags)),
  };
}
