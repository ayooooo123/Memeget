import { ASSOCIATIONS, MEME_LABELS } from './memeLabels';

export interface LexicalQuery {
  exactTerms: string[];
  expandedTerms?: string[];
}

export type LabelExpansionSource = 'curated' | 'library' | 'taught';

export interface LabelExpansionCandidate {
  label: string;
  terms: string[];
  vec: Float32Array;
  source: LabelExpansionSource;
}

export interface SemanticLabelHit {
  label: string;
  score: number;
  terms: string[];
  source: LabelExpansionSource;
}

function dot(a: Float32Array | number[], b: Float32Array | number[]): number {
  let s = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) s += a[i] * b[i];
  return s;
}

function cleanTerm(term: string): string {
  return term.trim().toLowerCase().replace(/\s+/g, ' ');
}

export function searchTermsForText(queryText: string, keepShort = false): string[] {
  return queryText
    .toLowerCase()
    .split(/\s+/)
    .filter((t) => (keepShort ? t.length > 0 : t.length > 2));
}

export function searchScopeEntries<T extends { kind: string }>(
  entries: readonly T[],
  kind: string | undefined,
  queryText: string
): readonly T[] {
  if (!kind) return entries;
  const scoped = entries.filter((e) => e.kind === kind);
  return scoped.length === 0 && queryText.trim().length > 0 ? entries : scoped;
}

function addUnique(out: string[], seen: Set<string>, term: string): void {
  const t = cleanTerm(term);
  if (!t || seen.has(t)) return;
  seen.add(t);
  out.push(t);
}

export function searchLabelPrompt(label: string): string {
  return `a meme about ${label}`;
}

export function labelTerms(label: string): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  addUnique(out, seen, label);
  for (const term of ASSOCIATIONS[label] ?? []) addUnique(out, seen, term);
  return out;
}

export function buildLabelExpansionCandidates(
  vectors: Map<string, Float32Array>,
  libraryLabels: readonly string[] = [],
  taughtLabels: readonly string[] = []
): LabelExpansionCandidate[] {
  const out: LabelExpansionCandidate[] = [];
  const seen = new Set<string>();
  const push = (label: string, source: LabelExpansionSource) => {
    const key = label.trim();
    const vec = vectors.get(key) ?? vectors.get(key.toLowerCase());
    if (!key || !vec || seen.has(key.toLowerCase())) return;
    seen.add(key.toLowerCase());
    out.push({ label: key, terms: labelTerms(key), vec, source });
  };

  for (const def of MEME_LABELS) push(def.label, 'curated');
  for (const label of taughtLabels) push(label, 'taught');
  for (const label of libraryLabels) push(label, 'library');
  return out;
}

export function rankSemanticLabels(
  queryVec: Float32Array | number[] | null,
  candidates: readonly LabelExpansionCandidate[],
  opts: { minScore?: number; limit?: number } = {}
): SemanticLabelHit[] {
  if (!queryVec || queryVec.length === 0) return [];
  const minScore = opts.minScore ?? 0.24;
  const limit = opts.limit ?? 6;
  return candidates
    .map((c) => ({ label: c.label, source: c.source, terms: c.terms, score: dot(queryVec, c.vec) }))
    .filter((h) => h.score >= minScore)
    .sort((a, b) => b.score - a.score)
    .slice(0, limit);
}

export function buildExpandedLexicalQuery(
  exactTerms: readonly string[],
  semanticHits: readonly SemanticLabelHit[]
): LexicalQuery {
  const exact: string[] = [];
  const exactSeen = new Set<string>();
  for (const term of exactTerms) addUnique(exact, exactSeen, term);

  const expanded: string[] = [];
  const expandedSeen = new Set(exactSeen);
  for (const hit of semanticHits) {
    for (const term of hit.terms) addUnique(expanded, expandedSeen, term);
  }

  return expanded.length ? { exactTerms: exact, expandedTerms: expanded } : { exactTerms: exact };
}

const TAG_EXACT_WEIGHT = 2.5;
const TAG_ALL_TERMS_BOOST = 3.0;

export function tagTermScore(
  tagLabels: readonly string[],
  query: Pick<LexicalQuery, 'exactTerms'>
): number {
  if (query.exactTerms.length === 0 || tagLabels.length === 0) return 0;
  const hay = tagLabels.map((label) => label.toLowerCase()).join(' ');
  let matched = 0;
  for (const term of query.exactTerms) {
    if (hay.includes(term)) matched += 1;
  }
  if (matched === 0) return 0;
  return (
    TAG_EXACT_WEIGHT * (matched / query.exactTerms.length) +
    (matched === query.exactTerms.length ? TAG_ALL_TERMS_BOOST : 0)
  );
}

function quoteFtsTerm(term: string): string {
  return `"${term.replace(/"/g, '""')}"`;
}

export function ftsMatchQuery(query: LexicalQuery): string {
  const terms: string[] = [];
  const seen = new Set<string>();
  for (const term of query.exactTerms) addUnique(terms, seen, term);
  for (const term of query.expandedTerms ?? []) addUnique(terms, seen, term);
  return terms.map(quoteFtsTerm).join(' OR ');
}

export interface RankedList {
  ids: readonly number[];
  weight?: number;
}

export function reciprocalRankFusion(
  lists: readonly RankedList[],
  k = 60
): { id: number; score: number }[] {
  const scores = new Map<number, number>();
  let firstSeen = 0;
  const order = new Map<number, number>();
  for (const list of lists) {
    const weight = list.weight ?? 1;
    for (let i = 0; i < list.ids.length; i++) {
      const id = list.ids[i];
      if (!order.has(id)) order.set(id, firstSeen++);
      scores.set(id, (scores.get(id) ?? 0) + weight / (k + i + 1));
    }
  }
  return [...scores.entries()]
    .map(([id, score]) => ({ id, score }))
    .sort((a, b) => b.score - a.score || (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0));
}

export interface DenseRankedEntry {
  id: number;
  score: number;
  modifiedAt?: number;
}

export function fuseDenseAndLexicalRanks(
  dense: readonly DenseRankedEntry[],
  lexicalIds: readonly number[]
): { id: number; score: number }[] {
  const lists: RankedList[] = [{ ids: dense.map((d) => d.id), weight: 1 }];
  if (lexicalIds.length) lists.push({ ids: lexicalIds, weight: 2 });
  const fused = reciprocalRankFusion(lists);
  const denseById = new Map(dense.map((d) => [d.id, d]));
  return fused.sort((a, b) =>
    b.score - a.score ||
    (denseById.get(b.id)?.score ?? 0) - (denseById.get(a.id)?.score ?? 0) ||
    (denseById.get(b.id)?.modifiedAt ?? 0) - (denseById.get(a.id)?.modifiedAt ?? 0) ||
    b.id - a.id
  );
}
