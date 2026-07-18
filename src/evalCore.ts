// Search-quality eval harness — core logic.
//
// The point of this module: measure whether a change (new labels, a tweaked
// caption weight, the memedepot baseline cap, a fine-tuned model) makes search
// BETTER or WORSE, instead of guessing. It does that by scoring a golden set
// with the APP'S OWN ranking code — `scoreEntry` from ./searchCore, the exact
// function the on-device DB scan uses — so the benchmark can't drift from what
// the phone actually does.
//
// Embeddings are supplied *precomputed* in the golden set (produced offline with
// the same CLIP encoder the app ships — see tools/eval/README.md). That keeps
// this module free of any model/native-runtime dependency, fully deterministic,
// and unit-testable in plain Node. Populating real vectors is a separate,
// offline step; the math here is what turns them into Recall@k / MRR.

import { scoreEntry } from './searchCore';

// One meme in the evaluation corpus. Vectors are L2-normalized CLIP embeddings,
// exactly as stored on-device.
export interface EvalMeme {
  id: string;
  imageVec: number[];
  captionVec?: number[] | null; // CLIP text vector of the meme's caption/tags, if described
  searchText?: string; // lexical haystack (caption + tags + OCR), matched case-as-stored
}

// One test query and the meme it's expected to retrieve.
export interface EvalQuery {
  query: string; // human-readable query text (for the report)
  queryVec: number[]; // CLIP text embedding of `query`
  expectedId: string; // id of the meme this query should surface
  terms?: string[]; // lowercased lexical terms; defaults to splitting `query`
}

// One single-word (or short) aspect query: "smug", "crying", "pointing",
// "wojak". Unlike EvalQuery it has a *set* of correct answers — every meme that
// carries that aspect — because that's how you actually search: one word, many
// valid hits. relevantIds is that set (from memedepot's per-meme tags).
export interface AspectQuery {
  query: string;
  queryVec: number[];
  relevantIds: string[];
  terms?: string[]; // lowercased lexical terms; defaults to splitting `query`
}

export interface GoldenSet {
  memes: EvalMeme[];
  queries: EvalQuery[];
  aspects?: AspectQuery[]; // single-word aspect-search set (optional; older golden sets omit it)
}

export interface RankedHit {
  id: string;
  score: number;
}

// Rank every meme for one query using the app's real hybrid+lexical scoring.
export function rankQuery(q: EvalQuery, memes: EvalMeme[]): RankedHit[] {
  const terms = q.terms ?? q.query.toLowerCase().split(/\s+/).filter(Boolean);
  return memes
    .map((m) => ({
      id: m.id,
      score: scoreEntry(q.queryVec, terms, {
        imageVec: m.imageVec,
        captionVec: m.captionVec ?? null,
        searchText: m.searchText ?? '',
      }),
    }))
    .sort((a, b) => b.score - a.score);
}

// 1-based rank of the expected meme (Infinity if it never appears — e.g. an id typo).
export function rankOfExpected(q: EvalQuery, memes: EvalMeme[]): number {
  const ranked = rankQuery(q, memes);
  const idx = ranked.findIndex((h) => h.id === q.expectedId);
  return idx === -1 ? Infinity : idx + 1;
}

export interface RetrievalMetrics {
  n: number; // number of queries scored
  recallAt1: number;
  recallAt5: number;
  recallAt10: number;
  mrr: number; // mean reciprocal rank
}

// Aggregate retrieval quality over the whole golden set. Recall@k = fraction of
// queries whose target meme lands in the top k; MRR rewards ranking it at #1.
export function evaluateRetrieval(golden: GoldenSet): RetrievalMetrics {
  const ranks = golden.queries.map((q) => rankOfExpected(q, golden.memes));
  const n = ranks.length;
  const denom = n || 1; // avoid /0 on an empty set; metrics are 0 then
  const recallAt = (k: number) => ranks.filter((r) => r <= k).length / denom;
  const mrr = ranks.reduce((s, r) => s + (Number.isFinite(r) ? 1 / r : 0), 0) / denom;
  return {
    n,
    recallAt1: recallAt(1),
    recallAt5: recallAt(5),
    recallAt10: recallAt(10),
    mrr,
  };
}

// Human-readable one-metric-per-line report for the CLI / test output.
export function formatMetrics(m: RetrievalMetrics): string {
  const pct = (x: number) => `${(x * 100).toFixed(1)}%`;
  return [
    `queries:   ${m.n}`,
    `Recall@1:  ${pct(m.recallAt1)}`,
    `Recall@5:  ${pct(m.recallAt5)}`,
    `Recall@10: ${pct(m.recallAt10)}`,
    `MRR:       ${m.mrr.toFixed(3)}`,
  ].join('\n');
}

// A/B gate: does `candidate` regress against `baseline` beyond `tol`? Returns the
// regressions (empty = safe to ship). This is how a memeLabels/searchCore/model
// change proves it didn't make search worse before it merges.
export function regressions(
  baseline: RetrievalMetrics,
  candidate: RetrievalMetrics,
  tol = 0.01
): string[] {
  const out: string[] = [];
  const check = (name: string, b: number, c: number) => {
    if (c < b - tol) out.push(`${name}: ${(b * 100).toFixed(1)}% → ${(c * 100).toFixed(1)}%`);
  };
  check('Recall@1', baseline.recallAt1, candidate.recallAt1);
  check('Recall@5', baseline.recallAt5, candidate.recallAt5);
  check('Recall@10', baseline.recallAt10, candidate.recallAt10);
  if (candidate.mrr < baseline.mrr - tol) {
    out.push(`MRR: ${baseline.mrr.toFixed(3)} → ${candidate.mrr.toFixed(3)}`);
  }
  return out;
}

// ---- tagging eval -----------------------------------------------------------
//
// The dual of retrieval: given a meme IMAGE, does zero-shot classification pick
// its right FORMAT? This is the metric that responds to label/prompt quality
// (retrieval doesn't route through labels). Ground truth is free: each golden
// meme's depot IS its format, and every depot contributes a text vector (its
// name query), so the depots ARE the label set. We rank a meme against every
// label vector and check where its own label lands.

function cosine(a: number[], b: number[]): number {
  let s = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) s += a[i] * b[i];
  return s; // vectors are L2-normalized, so dot == cosine
}

export interface TaggingMetrics {
  n: number; // memes classified
  labels: number; // size of the label set (classes)
  recallAt1: number; // top label is the right format
  recallAt3: number;
  recallAt5: number;
  mrr: number;
}

export function evaluateTagging(golden: GoldenSet): TaggingMetrics {
  // Distinct labels + their text vectors, and each meme's ground-truth label,
  // both derived from the (query → expected meme) pairs.
  const labelVec = new Map<string, number[]>();
  const memeLabel = new Map<string, string>();
  for (const q of golden.queries) {
    if (!labelVec.has(q.query)) labelVec.set(q.query, q.queryVec);
    memeLabel.set(q.expectedId, q.query);
  }
  const labels = [...labelVec.entries()];

  const ranks: number[] = [];
  for (const m of golden.memes) {
    const expected = memeLabel.get(m.id);
    if (!expected) continue;
    const scored = labels
      .map(([label, vec]) => ({ label, score: cosine(m.imageVec, vec) }))
      .sort((a, b) => b.score - a.score);
    const idx = scored.findIndex((s) => s.label === expected);
    ranks.push(idx === -1 ? Infinity : idx + 1);
  }
  const n = ranks.length;
  const denom = n || 1;
  const rAt = (k: number) => ranks.filter((r) => r <= k).length / denom;
  const mrr = ranks.reduce((s, r) => s + (Number.isFinite(r) ? 1 / r : 0), 0) / denom;
  return { n, labels: labels.length, recallAt1: rAt(1), recallAt3: rAt(3), recallAt5: rAt(5), mrr };
}

export function formatTagging(m: TaggingMetrics): string {
  const pct = (x: number) => `${(x * 100).toFixed(1)}%`;
  return [
    `memes:     ${m.n}   (over ${m.labels} format labels)`,
    `top-1:     ${pct(m.recallAt1)}   (right format is #1)`,
    `top-3:     ${pct(m.recallAt3)}`,
    `top-5:     ${pct(m.recallAt5)}`,
    `MRR:       ${m.mrr.toFixed(3)}`,
  ].join('\n');
}

// ---- aspect search (single-word queries) ------------------------------------
//
// This is the eval that models how the app is ACTUALLY searched: you type one
// word — an emotion ("smug"), an action ("pointing"), a character ("wojak"), a
// format — and expect the memes that carry that aspect to surface. It's the
// direct test of the north star (every aspect of a meme findable by a plain-word
// description), and unlike retrieval it runs the query through the real lexical
// `searchText`/`scoreEntry` channel that single words hit — so it's the metric
// that moves when tags get deeper or a caption/label changes.
//
// A single-word query has MANY correct answers (every meme tagged with it), so
// this is multi-relevant retrieval: we score every meme, then measure how well
// the relevant set floats to the top with MAP (the standard multi-relevant
// metric), precision@5 ("are my top 5 actually on-topic"), recall@10, and the
// reciprocal rank of the first hit.

export interface AspectMetrics {
  n: number; // aspect queries scored
  avgRelevant: number; // mean size of the relevant set
  precisionAt5: number; // of the top 5, fraction that carry the aspect
  recallAt10: number; // of the relevant memes, fraction landing in the top 10
  map: number; // mean average precision (the headline multi-relevant metric)
  mrr: number; // mean reciprocal rank of the first relevant hit
}

export function evaluateAspectSearch(golden: GoldenSet): AspectMetrics {
  const aspects = golden.aspects ?? [];
  const memes = golden.memes;
  let pSum = 0;
  let rSum = 0;
  let apSum = 0;
  let rrSum = 0;
  let relSum = 0;
  let n = 0;

  for (const a of aspects) {
    const rel = new Set(a.relevantIds);
    if (rel.size === 0) continue;
    const terms = a.terms ?? a.query.toLowerCase().split(/\s+/).filter(Boolean);
    const ranked = memes
      .map((m) => ({
        id: m.id,
        score: scoreEntry(a.queryVec, terms, {
          imageVec: m.imageVec,
          captionVec: m.captionVec ?? null,
          searchText: m.searchText ?? '',
        }),
      }))
      .sort((x, y) => y.score - x.score);

    const top5 = ranked.slice(0, 5).filter((h) => rel.has(h.id)).length;
    const top10 = ranked.slice(0, 10).filter((h) => rel.has(h.id)).length;

    // Average precision: precision sampled at each relevant hit, averaged over
    // the relevant set — rewards ranking ALL the on-topic memes high, not just one.
    let hits = 0;
    let ap = 0;
    let firstRank = Infinity;
    ranked.forEach((h, i) => {
      if (rel.has(h.id)) {
        hits++;
        ap += hits / (i + 1);
        if (firstRank === Infinity) firstRank = i + 1;
      }
    });
    ap /= rel.size;

    pSum += top5 / 5;
    rSum += top10 / rel.size;
    apSum += ap;
    rrSum += Number.isFinite(firstRank) ? 1 / firstRank : 0;
    relSum += rel.size;
    n++;
  }

  const d = n || 1;
  return {
    n,
    avgRelevant: relSum / d,
    precisionAt5: pSum / d,
    recallAt10: rSum / d,
    map: apSum / d,
    mrr: rrSum / d,
  };
}

export function formatAspect(m: AspectMetrics): string {
  const pct = (x: number) => `${(x * 100).toFixed(1)}%`;
  return [
    `queries:   ${m.n}   (avg ${m.avgRelevant.toFixed(1)} relevant memes each)`,
    `MAP:       ${m.map.toFixed(3)}   (headline: relevant memes ranked high)`,
    `Prec@5:    ${pct(m.precisionAt5)}   (top 5 actually on-topic)`,
    `Recall@10: ${pct(m.recallAt10)}`,
    `MRR:       ${m.mrr.toFixed(3)}   (first on-topic hit)`,
  ].join('\n');
}
