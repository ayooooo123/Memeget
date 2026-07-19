// Tests for the facet-coverage scorer, and the runner for the prompt-tuning
// loop: `npm run coverage` scores tools/eval/described.json (a device export of
// described memes) when present, else prints a synthetic sample + how to get one.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tagFacets, facetCoverage, formatCoverage, type DescribedMeme } from './facetCoverage';

describe('tagFacets', () => {
  it('routes a tag into the facet its meaning belongs to', () => {
    expect(tagFacets('smug')).toContain('emotion');
    expect(tagFacets('pointing')).toContain('action');
    expect(tagFacets('fire')).toContain('object');
    expect(tagFacets('office')).toContain('setting');
    expect(tagFacets('ironic')).toContain('tone');
    expect(tagFacets('pepe')).toContain('character');
  });

  it('can assign a tag to more than one facet', () => {
    // "smug pepe" carries both an emotion word and a character word.
    const f = tagFacets('smug pepe');
    expect(f).toContain('emotion');
    expect(f).toContain('character');
  });

  it('returns nothing for a tag that matches no known facet vocabulary', () => {
    expect(tagFacets('qwzxlkjh')).toEqual([]);
    expect(tagFacets('')).toEqual([]);
  });
});

describe('facetCoverage', () => {
  it('reports the fraction of memes with a tag in each facet', () => {
    const memes: DescribedMeme[] = [
      { tags: ['smug', 'pepe'] }, // emotion + character
      { tags: ['pointing', 'office'] }, // action + setting
      { tags: ['zzzz'] }, // nothing
    ];
    const m = facetCoverage(memes);
    expect(m.n).toBe(3);
    expect(m.perFacet.emotion).toBeCloseTo(1 / 3, 6);
    expect(m.perFacet.action).toBeCloseTo(1 / 3, 6);
    expect(m.perFacet.setting).toBeCloseTo(1 / 3, 6);
    expect(m.unclassifiedRate).toBeCloseTo(1 / 5, 6); // 1 of 5 tags matched nothing
  });

  it('is 0, not NaN, on an empty set', () => {
    const m = facetCoverage([]);
    expect(m.n).toBe(0);
    expect(m.avgFacetsPerMeme).toBe(0);
    expect(m.unclassifiedRate).toBe(0);
  });
});

describe('coverage runner (npm run coverage)', () => {
  it('scores tools/eval/described.json if present, else a synthetic sample', () => {
    const path = join(process.cwd(), 'tools/eval/described.json');
    if (existsSync(path)) {
      const memes = JSON.parse(readFileSync(path, 'utf8')) as DescribedMeme[];
      const m = facetCoverage(memes);
      console.log(`\n--- facet coverage (device export, ${m.n} memes) ---\n${formatCoverage(m)}\n`);
      expect(m.n).toBeGreaterThan(0);
    } else {
      const sample: DescribedMeme[] = [
        { tags: ['this is fine', 'dog', 'sipping coffee', 'forced calm', 'denial', 'fire', 'when everything is falling apart'] },
        { tags: ['distracted boyfriend', 'jealousy', 'turning to look', 'temptation'] },
        { tags: ['gigachad', 'flexing', 'gym', 'confidence'] },
      ];
      console.log(
        '\n[coverage] no tools/eval/described.json — showing a SYNTHETIC sample. ' +
          'Export described memes from a device (see tools/eval/README.md) to score real output.\n' +
          `--- facet coverage (synthetic, ${sample.length} memes) ---\n${formatCoverage(facetCoverage(sample))}\n`
      );
      expect(sample.length).toBeGreaterThan(0);
    }
  });
});
