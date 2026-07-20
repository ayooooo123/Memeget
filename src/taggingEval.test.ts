// Unit tests for the tagging scorer, plus the CI/local runner: `npm run tagtest`
// scores tools/eval/tagging-cases.json (hand-labeled expectations) against
// tools/eval/described.json (predicted tags — a device export or a proxy-VLM
// run) when both are present, else no-ops with a hint.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  scoreTagging,
  formatTaggingReport,
  taggingRegressions,
  type TaggingCase,
  type Prediction,
} from './taggingEval';

const CASES: TaggingCase[] = [
  { id: 'shush', mustFind: ['shush', 'quiet', 'be quiet'], expectFacets: ['situation', 'action'] },
  { id: 'facepalm', mustFind: ['facepalm', 'disbelief'], expectFacets: ['action'] },
];

describe('scoreTagging', () => {
  it('passes a meme findable by a search term with the expected facets', () => {
    const preds: Prediction[] = [
      { id: 'shush', tags: ['shushing', 'be quiet', 'keep it a secret'] }, // situation + action
    ];
    const s = scoreTagging([CASES[0]], preds);
    expect(s.mustFindRate).toBeCloseTo(1, 6); // "be quiet" is present
    expect(s.facetRecall).toBeCloseTo(1, 6); // shushing→action, be quiet→situation
    expect(s.cases[0].matchedTerms).toContain('be quiet');
  });

  it('flags an appearance-only tag set as not findable + missing facets', () => {
    const preds: Prediction[] = [{ id: 'shush', tags: ['man', 'hand near mouth', 'intense look'] }];
    const s = scoreTagging([CASES[0]], preds);
    expect(s.mustFindRate).toBe(0); // none of shush/quiet/be quiet present
    expect(s.cases[0].missingFacets).toEqual(expect.arrayContaining(['situation', 'action']));
  });

  it('treats a meme with no prediction as a miss (undescribed black hole)', () => {
    const s = scoreTagging(CASES, [{ id: 'shush', tags: ['be quiet', 'shushing'] }]); // facepalm absent
    expect(s.mustFindRate).toBeCloseTo(0.5, 6); // shush found, facepalm missing
    const fp = s.cases.find((c) => c.id === 'facepalm')!;
    expect(fp.hasPrediction).toBe(false);
    expect(fp.found).toBe(false);
  });

  it('regression gate fires when findability drops', () => {
    const base = { mustFindRate: 0.9, facetRecall: 0.8 };
    expect(taggingRegressions(base, { mustFindRate: 0.7, facetRecall: 0.8 })).toHaveLength(1);
    expect(taggingRegressions(base, { mustFindRate: 0.905, facetRecall: 0.85 })).toEqual([]);
  });
});

describe('tagging test runner (npm run tagtest)', () => {
  it('scores the labeled set against predictions when both files exist', () => {
    const casesPath = join(process.cwd(), 'tools/eval/tagging-cases.json');
    const predsPath = join(process.cwd(), 'tools/eval/described.json');
    if (!existsSync(casesPath) || !existsSync(predsPath)) {
      console.log(
        '\n[tagtest] need tools/eval/tagging-cases.json (labeled expectations) AND ' +
          'tools/eval/described.json (predicted tags) — export a collection and label it, ' +
          'then re-run. Showing nothing to score.\n'
      );
      return;
    }
    const cases = JSON.parse(readFileSync(casesPath, 'utf8')) as TaggingCase[];
    const preds = JSON.parse(readFileSync(predsPath, 'utf8')) as Prediction[];
    const s = scoreTagging(cases, preds);
    console.log(`\n--- tagging test (${s.n} labeled memes) ---\n${formatTaggingReport(s)}\n`);
    expect(s.n).toBeGreaterThan(0);
  });
});
