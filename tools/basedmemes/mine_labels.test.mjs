// Unit tests for the basedmemes.lol + KYM label miner. Run with `node --test`
// (or `npm run mine:basedmemes:test`). Fully deterministic: no network, and no
// dependence on the real archive — `loadDataset` is exercised against a tiny
// temp fixture, and the baseline shape against a synthetic page set. These lock
// the merge/union/basename semantics and the baseline schema so a refactor
// can't silently change what feeds the app's zero-shot vocabulary.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { loadDataset } from './dataset.mjs';
import { aggregatePages, buildBaseline } from '../memedepot/harvest.mjs';

// Build a temp archive dir with the two files (either optional) and return its path.
function fixture({ jsonl, kym } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'basedmemes-'));
  if (jsonl != null) writeFileSync(join(dir, 'dataset.jsonl'), jsonl, 'utf8');
  if (kym != null) writeFileSync(join(dir, 'meme_dataset_kym.json'), kym, 'utf8');
  return dir;
}

test('loadDataset merges both sources by image filename, unions & dedupes tags', () => {
  const jsonl =
    '{"image": "shared.jpg", "prefix": "p", "suffix": "alpha, beta"}\n' +
    '{"image": "solo.webp", "prefix": "p", "suffix": "gamma, gamma"}\n'; // intra-record dupe
  const kym = JSON.stringify([
    // Same meme as dataset.jsonl's shared.jpg, keyed on basename("images/shared.jpg").
    { image: 'https://cdn/x.jpg', tags: ['beta', 'delta'], file: 'images/shared.jpg' },
    // Object-valued tag must be coerced via jsonTerm, not stringified to [object Object].
    { image: 'https://cdn/y.jpg', tags: [{ name: 'epsilon' }, 'zeta'], file: 'images/kymonly.jpg' },
    // Empty tag list → no page.
    { image: 'https://cdn/z.jpg', tags: [], file: 'images/empty.jpg' },
  ]);
  const dir = fixture({ jsonl, kym });
  try {
    const pages = loadDataset(dir);
    assert.equal(pages.length, 3, 'shared.jpg + solo.webp + kymonly.jpg; empty.jpg skipped');

    const shared = pages.find((p) => p.includes('alpha'));
    assert.ok(shared, 'shared.jpg page present');
    assert.deepEqual([...shared].sort(), ['alpha', 'beta', 'delta'], 'cross-file union, beta deduped');

    const solo = pages.find((p) => p.includes('gamma'));
    assert.deepEqual([...solo].sort(), ['gamma'], 'intra-record duplicate collapsed');

    const kymOnly = pages.find((p) => p.includes('epsilon'));
    assert.deepEqual([...kymOnly].sort(), ['epsilon', 'zeta'], 'object tag coerced, basename keyed');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('loadDataset is robust to missing files (returns what exists)', () => {
  const empty = fixture({});
  try {
    assert.deepEqual(loadDataset(empty), [], 'no files → no pages');
  } finally {
    rmSync(empty, { recursive: true, force: true });
  }

  const onlyJsonl = fixture({ jsonl: '{"image":"a.webp","suffix":"pepe, wojak"}\n' });
  try {
    const pages = loadDataset(onlyJsonl);
    assert.equal(pages.length, 1);
    assert.deepEqual([...pages[0]].sort(), ['pepe', 'wojak']);
  } finally {
    rmSync(onlyJsonl, { recursive: true, force: true });
  }
});

test('mining a synthetic page set yields the baseline schema, count>=2 floor drops singletons', () => {
  // gigachad appears on 2 pages; every other term on exactly 1.
  const pages = [
    ['gigachad', 'soyjak'],
    ['gigachad', 'doomer'],
    ['bloomer'],
  ];
  const freq = aggregatePages(pages);
  const baseline = buildBaseline(freq, { max: 300, source: 'test', generatedAt: null });

  assert.equal(baseline.source, 'test');
  assert.equal(baseline.generatedAt, null);
  assert.ok(Array.isArray(baseline.labels));

  // Only the >=2-page term survives the floor.
  assert.equal(baseline.labels.length, 1, 'singletons dropped');
  const [g] = baseline.labels;
  assert.equal(g.label, 'Gigachad');
  assert.equal(g.prompt, 'a gigachad meme');
  assert.equal(g.count, 2);

  // Every label carries the full schema the app consumes.
  for (const l of baseline.labels) {
    assert.equal(typeof l.label, 'string');
    assert.ok(l.label.length > 0);
    assert.equal(typeof l.prompt, 'string');
    assert.ok(l.prompt.length > 0);
    assert.equal(typeof l.category, 'string');
    assert.ok(l.category.length > 0);
    assert.ok(Number.isInteger(l.count) && l.count >= 2);
  }
});
