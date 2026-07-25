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
import { CURATED_MEME_LABELS, type LabelDef } from './memeLabels';

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

// buildAllBaselineLabels composes the two committed machine-generated tiers
// (memedepot + basedmemes) under the curated core with a shared de-dupe pass.
// It reads the real committed data files, so these assert composition INVARIANTS
// that must hold for any data rather than exact contents.
describe('buildAllBaselineLabels', () => {
  const norm = (s: string) => s.trim().toLowerCase();

  it('produces breadth from the committed baselines', () => {
    const out = buildAllBaselineLabels(CURATED_MEME_LABELS);
    expect(Array.isArray(out)).toBe(true);
    expect(out.length).toBeGreaterThan(0); // basedmemes tier is committed non-empty
  });

  it('never re-emits a curated label and never duplicates within itself', () => {
    const out = buildAllBaselineLabels(CURATED_MEME_LABELS);
    const curated = new Set(CURATED_MEME_LABELS.map((d) => norm(d.label)));
    const seen = new Set<string>();
    for (const d of out) {
      const key = norm(d.label);
      expect(curated.has(key)).toBe(false); // curated always wins
      expect(seen.has(key)).toBe(false); // cross-tier + intra-tier de-dupe
      seen.add(key);
      expect(d.prompt.length).toBeGreaterThan(0);
    }
  });

  it('respects both per-tier caps', () => {
    const out = buildAllBaselineLabels(CURATED_MEME_LABELS);
    expect(out.length).toBeLessThanOrEqual(MAX_BASELINE_LABELS + MAX_BASEDMEMES_LABELS);
  });

  it('leads with the memedepot tier, then appends the basedmemes tier', () => {
    const out = buildAllBaselineLabels(CURATED_MEME_LABELS);
    const firstTier = buildBaselineLabels(
      CURATED_MEME_LABELS,
      undefined, // defaults to the memedepot baseline file
      MAX_BASELINE_LABELS
    );
    // The composed list starts with exactly the memedepot tier.
    expect(out.slice(0, firstTier.length).map((d) => d.label)).toEqual(
      firstTier.map((d) => d.label)
    );
  });
});
