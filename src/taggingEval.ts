// Tagging test scorer — the deterministic, model-free core of the CI tagging
// action. It answers the two questions that matter for recall:
//   1. Is each meme findable by the words you'd actually search? (mustFind)
//   2. Does it carry a tag in the facets it should? (expectFacets)
// Given a set of PREDICTED tags per meme (from a device export or a proxy VLM
// run) and a hand-labeled set of expectations, it produces a pass/fail per meme
// plus aggregate rates, and a regression gate so a prompt change can't silently
// make tagging worse.
//
// mustFind matching mirrors the app's real search: the query is lowercased and
// matched with `.includes` against the meme's joined tag text — so "quiet"
// finds a meme tagged "be quiet".

import { tagFacets } from './facetCoverage';

export interface TaggingCase {
  id: string;
  file?: string; // path to the image within the test set (for the CI VLM run)
  note?: string; // human reminder of what the meme is
  mustFind: string[]; // findable by AT LEAST ONE of these search terms
  expectFacets?: string[]; // and should carry a tag in each of these facets
}

export interface Prediction {
  id: string;
  tags: string[];
}

export interface CaseResult {
  id: string;
  hasPrediction: boolean;
  found: boolean; // at least one mustFind term is present
  matchedTerms: string[];
  missingFacets: string[];
}

export interface TaggingScore {
  n: number;
  mustFindRate: number; // fraction of cases findable by a search term
  facetRecall: number; // covered expected-facets / total expected-facets
  cases: CaseResult[];
}

export function scoreTagging(cases: TaggingCase[], predictions: Prediction[]): TaggingScore {
  const byId = new Map(predictions.map((p) => [p.id, p.tags]));
  const results: CaseResult[] = [];
  let found = 0;
  let facetHit = 0;
  let facetTotal = 0;

  for (const c of cases) {
    const tags = byId.get(c.id);
    const hasPrediction = tags !== undefined;
    const hay = (tags ?? []).join(' ').toLowerCase();
    const matchedTerms = tags ? c.mustFind.filter((t) => hay.includes(t.toLowerCase())) : [];
    const isFound = matchedTerms.length > 0;
    if (isFound) found++;

    const covered = new Set<string>();
    for (const t of tags ?? []) for (const f of tagFacets(t)) covered.add(f);
    const expect = c.expectFacets ?? [];
    const missingFacets = expect.filter((f) => !covered.has(f));
    facetHit += expect.length - missingFacets.length;
    facetTotal += expect.length;

    results.push({ id: c.id, hasPrediction, found: isFound, matchedTerms, missingFacets });
  }

  const denom = cases.length || 1;
  return {
    n: cases.length,
    mustFindRate: found / denom,
    facetRecall: facetTotal ? facetHit / facetTotal : 1,
    cases: results,
  };
}

export function formatTaggingReport(s: TaggingScore): string {
  const pct = (x: number) => `${(x * 100).toFixed(0)}%`;
  const lines = [
    `cases:        ${s.n}`,
    `findable:     ${pct(s.mustFindRate)}   (a search term you'd use hits the meme)`,
    `facet recall: ${pct(s.facetRecall)}   (expected facets that got a tag)`,
  ];
  const fails = s.cases.filter((c) => !c.found || c.missingFacets.length > 0);
  if (fails.length) {
    lines.push('', 'needs work:');
    for (const c of fails) {
      const bits: string[] = [];
      if (!c.hasPrediction) bits.push('NO TAGS (undescribed / not exported)');
      else if (!c.found) bits.push('not findable by any search term');
      if (c.missingFacets.length) bits.push(`missing facets: ${c.missingFacets.join(', ')}`);
      lines.push(`  ${c.id.padEnd(18)} ${bits.join('; ')}`);
    }
  }
  return lines.join('\n');
}

// A/B gate for CI: does `candidate` regress against `baseline` beyond `tol`?
// Returns the regressions (empty = safe to ship the prompt change).
export function taggingRegressions(
  baseline: Pick<TaggingScore, 'mustFindRate' | 'facetRecall'>,
  candidate: Pick<TaggingScore, 'mustFindRate' | 'facetRecall'>,
  tol = 0.01
): string[] {
  const out: string[] = [];
  const check = (name: string, b: number, c: number) => {
    if (c < b - tol) out.push(`${name}: ${(b * 100).toFixed(0)}% → ${(c * 100).toFixed(0)}%`);
  };
  check('findable', baseline.mustFindRate, candidate.mustFindRate);
  check('facet recall', baseline.facetRecall, candidate.facetRecall);
  return out;
}
