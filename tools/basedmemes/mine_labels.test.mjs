// Unit tests for the local basedmemes.lol + KYM label miner. Run with
// `node --test` (or `npm run mine:basedmemes:test`). Fully deterministic: no
// network, no dependence on the real archive — every case runs against a temp
// fixture dir built here. These lock the loader's merge/union/coercion semantics
// (each meme image = one page) and the shape of the baseline it feeds.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { aggregatePages, buildBaseline } from '../memedepot/harvest.mjs';
import { loadDataset } from './dataset.mjs';

// Build a throwaway archive dir; `files` maps relative name -> file contents.
async function fixtureDir(files) {
  const dir = await mkdtemp(join(tmpdir(), 'basedmemes-'));
  for (const [name, contents] of Object.entries(files)) {
    await writeFile(join(dir, name), contents);
  }
  return dir;
}

const jsonl = (rows) => rows.map((r) => (typeof r === 'string' ? r : JSON.stringify(r))).join('\n');

// The one page containing a probe tag — pages are anonymous tag arrays, so we
// identify an image by a tag unique to it.
const pageWith = (pages, probe) => pages.find((p) => p.includes(probe));

test('loadDataset merges the two sources by filename and unions tags', async () => {
  const dir = await fixtureDir({
    'dataset.jsonl': jsonl([
      { image: 'shared.jpg', suffix: 'cat, dog' },
      { image: 'a.webp', suffix: 'pepe, brainlet' },
    ]),
    'meme_dataset_kym.json': JSON.stringify([
      { image: 'http://x', tags: ['dog', 'bird'], file: 'images/shared.jpg' },
    ]),
  });
  try {
    const pages = await loadDataset(dir);
    const shared = pageWith(pages, 'cat');
    assert.ok(shared, 'shared page exists');
    // Union across both sources; "dog" appears in both but only once.
    assert.deepEqual([...shared].sort(), ['bird', 'cat', 'dog']);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('loadDataset de-dupes tags within an image case-insensitively', async () => {
  const dir = await fixtureDir({
    'dataset.jsonl': jsonl([{ image: 'a.webp', suffix: 'wojak, pepe, Wojak' }]),
  });
  try {
    const pages = await loadDataset(dir);
    const page = pageWith(pages, 'pepe');
    assert.deepEqual(page, ['wojak', 'pepe']); // first-seen form kept, dup dropped
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('loadDataset keys KYM entries on the basename of file', async () => {
  const dir = await fixtureDir({
    'dataset.jsonl': jsonl([{ image: 'nested.jpg', suffix: 'j1' }]),
    'meme_dataset_kym.json': JSON.stringify([
      { image: 'http://z', tags: ['j2'], file: 'deep/path/nested.jpg' },
    ]),
  });
  try {
    const pages = await loadDataset(dir);
    const page = pageWith(pages, 'j1');
    assert.ok(page, 'basename-keyed page merged');
    assert.ok(page.includes('j2'), 'nested-path KYM tags merged by basename');
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('loadDataset coerces object-valued KYM tags and skips empties', async () => {
  const dir = await fixtureDir({
    'meme_dataset_kym.json': JSON.stringify([
      { image: 'http://y', tags: ['frog', { name: 'chad' }, '', { nothing: 123 }], file: 'images/k.png' },
    ]),
  });
  try {
    const pages = await loadDataset(dir);
    const page = pageWith(pages, 'frog');
    assert.deepEqual(page, ['frog', 'chad']); // object coerced, empty + unnamed dropped
    assert.ok(!page.some((t) => t.includes('object Object')), 'no [object Object] leakage');
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('loadDataset skips empty comma segments and tolerates malformed lines', async () => {
  const dir = await fixtureDir({
    'dataset.jsonl': ['not valid json', JSON.stringify({ image: 'c.webp', suffix: 'foo,,bar' })].join('\n'),
  });
  try {
    const pages = await loadDataset(dir);
    const page = pageWith(pages, 'foo');
    assert.deepEqual(page, ['foo', 'bar']); // empty middle segment dropped, bad line ignored
    assert.equal(pages.length, 1);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('loadDataset is robust to missing source files', async () => {
  // Only dataset.jsonl present.
  const onlyJsonl = await fixtureDir({ 'dataset.jsonl': jsonl([{ image: 'a.webp', suffix: 'pepe' }]) });
  // Only KYM present.
  const onlyKym = await fixtureDir({
    'meme_dataset_kym.json': JSON.stringify([{ image: 'u', tags: ['wojak'], file: 'images/b.png' }]),
  });
  // Neither present.
  const empty = await fixtureDir({});
  try {
    assert.deepEqual(await loadDataset(onlyJsonl), [['pepe']]);
    assert.deepEqual(await loadDataset(onlyKym), [['wojak']]);
    assert.deepEqual(await loadDataset(empty), []);
    assert.deepEqual(await loadDataset(join(empty, 'does-not-exist')), []);
  } finally {
    await rm(onlyJsonl, { recursive: true, force: true });
    await rm(onlyKym, { recursive: true, force: true });
    await rm(empty, { recursive: true, force: true });
  }
});

test('synthetic pages -> baseline: count>=2 floor and correct schema', () => {
  // Each inner array is one image's tags. pepe & wojak appear on 2 images; frog
  // & solo on 1 -> the floor drops the singletons.
  const pages = [['pepe', 'wojak'], ['pepe', 'frog'], ['wojak'], ['solo']];
  const freq = aggregatePages(pages);
  const generatedAt = '2020-01-01T00:00:00.000Z';
  const baseline = buildBaseline(freq, { max: 300, source: 'test', generatedAt });

  assert.equal(baseline.source, 'test');
  assert.equal(baseline.generatedAt, generatedAt); // injected, not read from clock
  assert.deepEqual(
    baseline.labels.map((l) => l.label).sort(),
    ['Pepe', 'Wojak'] // frog + solo dropped by the count>=2 floor
  );
  for (const l of baseline.labels) {
    assert.equal(typeof l.label, 'string');
    assert.equal(l.prompt, `a ${l.label.toLowerCase()} meme`);
    assert.ok(typeof l.category === 'string' && l.category.length > 0);
    assert.ok(l.count >= 2, 'every kept label seen on >=2 images');
  }
  // Ranked descending by count.
  const counts = baseline.labels.map((l) => l.count);
  assert.deepEqual(counts, [...counts].sort((a, b) => b - a));
});
