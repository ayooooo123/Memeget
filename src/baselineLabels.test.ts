// Tests for folding the harvested memedepot baseline into the label vocabulary.
// These lock in the safety properties: curated labels always win, the tail is
// capped, junk is dropped, and categories are sanitized — so a bad harvest can't
// quietly corrupt or bloat the zero-shot tagging set.

import {
  buildBaselineLabels,
  buildAllBaselineLabels,
  MAX_BASELINE_LABELS,
  MAX_BASEDMEMES_LABELS,
  type BaselineTag,
} from './baselineLabels';
import type { LabelDef } from './memeLabels';

const curated: LabelDef[] = [
  { label: 'Gigachad', prompt: 'a Gigachad meme', category: 'character', associations: ['chad'] },
  { label: 'Doge', prompt: 'a Doge meme', category: 'character' },
];

const tag = (label: string, extra: Partial<BaselineTag> = {}): BaselineTag => ({
  label,
  prompt: `a ${label} meme`,
  category: 'topic',
  ...extra,
});

describe('buildBaselineLabels', () => {
  it('returns nothing for an empty harvest (the shipped default)', () => {
    expect(buildBaselineLabels(curated, [])).toEqual([]);
  });

  it('drops tags already covered by curated labels, case-insensitively', () => {
    const out = buildBaselineLabels(curated, [tag('gigachad'), tag('Trollface')]);
    expect(out.map((d) => d.label)).toEqual(['Trollface']);
  });

  it('de-duplicates within the baseline itself', () => {
    const out = buildBaselineLabels(curated, [tag('Bonk'), tag('bonk'), tag('Bonk')]);
    expect(out.map((d) => d.label)).toEqual(['Bonk']);
  });

  it('ranks by cross-depot frequency and caps the count', () => {
    const tags = [tag('Rare', { count: 1 }), tag('Common', { count: 500 }), tag('Mid', { count: 50 })];
    const out = buildBaselineLabels(curated, tags, 2);
    expect(out.map((d) => d.label)).toEqual(['Common', 'Mid']);
  });

  it('sanitizes an unknown category to topic', () => {
    const [out] = buildBaselineLabels(curated, [tag('Weird', { category: 'nonsense' })]);
    expect(out.category).toBe('topic');
  });

  it('keeps a valid category and carries associations', () => {
    const [out] = buildBaselineLabels(curated, [
      tag('Soyjak', { category: 'character', associations: ['soy', ' ', 'pointing'] }),
    ]);
    expect(out.category).toBe('character');
    expect(out.associations).toEqual(['soy', 'pointing']); // blanks trimmed out
  });

  it('drops tags missing a label or prompt', () => {
    const bad = [
      { label: '', prompt: 'a  meme', category: 'topic' },
      { label: 'NoPrompt', prompt: '   ', category: 'topic' },
      tag('Good'),
    ];
    expect(buildBaselineLabels(curated, bad).map((d) => d.label)).toEqual(['Good']);
  });
});

// buildAllBaselineLabels composes the two machine-generated breadth tiers (the
// CI-harvested memedepot baseline, then the local basedmemes.lol + KYM archive)
// with a single shared dedup. It reads the committed generated data files, so
// these assert structural invariants that must hold for ANY generated data — not
// exact contents that would churn on every re-harvest.
describe('buildAllBaselineLabels', () => {
  it('produces a non-empty composed vocabulary from the generated baselines', () => {
    expect(buildAllBaselineLabels(curated).length).toBeGreaterThan(0);
  });

  it('never re-emits a curated label', () => {
    const curatedKeys = new Set(curated.map((d) => d.label.trim().toLowerCase()));
    const all = buildAllBaselineLabels(curated);
    expect(all.some((d) => curatedKeys.has(d.label.trim().toLowerCase()))).toBe(false);
  });

  it('emits no duplicate labels across the two tiers', () => {
    const all = buildAllBaselineLabels(curated);
    const keys = all.map((d) => d.label.trim().toLowerCase());
    expect(new Set(keys).size).toBe(all.length);
  });

  it('respects both per-tier caps', () => {
    const all = buildAllBaselineLabels(curated);
    // The memedepot tier leads and is capped; the remainder is the basedmemes
    // tier, itself capped.
    const firstTier = buildBaselineLabels(curated); // defaults: memedepot data, MAX_BASELINE_LABELS
    const secondTierLen = all.length - firstTier.length;
    expect(firstTier.length).toBeLessThanOrEqual(MAX_BASELINE_LABELS);
    expect(secondTierLen).toBeLessThanOrEqual(MAX_BASEDMEMES_LABELS);
    expect(all.length).toBeLessThanOrEqual(MAX_BASELINE_LABELS + MAX_BASEDMEMES_LABELS);
  });

  it('leads with the memedepot tier, unchanged', () => {
    const all = buildAllBaselineLabels(curated);
    const firstTier = buildBaselineLabels(curated);
    expect(firstTier.length).toBeGreaterThan(0);
    expect(all.slice(0, firstTier.length)).toEqual(firstTier);
  });
});
