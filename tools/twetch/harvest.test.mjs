// Unit tests for the Twetch harvester's pure helpers. Run with `node --test`
// (or `npm run harvest:twetch:test`). The network orchestration in main() is
// not covered; the fixture below mirrors a real /v1/dank-rares response, so a
// change in the API shape surfaces here as a failing extraction rather than as
// a silently empty baseline.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildTwetchBaseline, folderLabel, harvestStats, twetchCandidates } from './harvest.mjs';

// Field names and values as the API returns them (unread fields trimmed). Note
// the "png" tag: the API stores the file container alongside real tags, and it
// is the single most common one in the live corpus.
const ITEMS = [
  { folder: 'Pepe Memes', title: 'comfy pepe', tags: ['pepe', 'comfy', 'png'] },
  { folder: 'Pepe Memes', title: 'smug pepe', tags: ['pepe', 'smug', 'png'] },
  { folder: 'Pepe Memes', title: 'sad pepe', tags: ['pepe', 'sad', 'gif'] },
  { folder: 'Wojak Memes', title: 'doomer', tags: ['wojak', 'doomer', 'comfy', 'png'] },
  { folder: 'Wojak Memes', title: 'untagged', tags: ['png'] },
  { folder: '$TOSHI Memes', title: 'toshi', tags: ['toshi', 'bsv'] },
];

test('folderLabel strips the packaging the site puts around a category', () => {
  assert.equal(folderLabel('Pepe Memes'), 'Pepe');
  assert.equal(folderLabel('$TOSHI Memes'), 'TOSHI');
  assert.equal(folderLabel('Meme Templates'), 'Meme Templates'); // only a trailing "meme(s)"
  assert.equal(folderLabel('Crypto'), 'Crypto');
  assert.equal(folderLabel(''), '');
  assert.equal(folderLabel(null), '');
});

test('folders come from the memes and rank by how many they hold', () => {
  const names = twetchCandidates(ITEMS)
    .filter((c) => c.weight > 1000)
    .map((c) => c.term);
  assert.deepEqual(names, ['Pepe', 'Wojak', 'TOSHI']);
});

test('folder ranking is deterministic when two folders tie', () => {
  const tied = [{ folder: 'Bobo Memes' }, { folder: 'Apu Memes' }];
  const run = () => twetchCandidates(tied).filter((c) => c.weight > 1000).map((c) => c.term);
  assert.deepEqual(run(), ['Apu', 'Bobo']);
  assert.deepEqual(run(), twetchCandidates([...tied].reverse()).filter((c) => c.weight > 1000).map((c) => c.term));
});

test('per-meme tags become frequency candidates, singletons dropped', () => {
  const byTerm = Object.fromEntries(
    twetchCandidates(ITEMS).filter((c) => c.weight < 1000).map((c) => [c.term, c.weight])
  );
  assert.equal(byTerm.pepe, 3);
  assert.equal(byTerm.comfy, 2); // across two folders
  assert.equal(byTerm.smug, undefined); // seen once
  assert.equal(byTerm.doomer, undefined);
});

test('file containers are never labels', () => {
  const terms = twetchCandidates(ITEMS).map((c) => c.term);
  for (const noise of ['png', 'gif', 'jpg', 'webp']) assert.ok(!terms.includes(noise));
});

test('a tag repeated within one meme is still counted once', () => {
  const dupes = [{ tags: ['pepe', 'pepe', 'pepe'] }, { tags: ['pepe'] }];
  const byTerm = Object.fromEntries(twetchCandidates(dupes).map((c) => [c.term, c.weight]));
  assert.equal(byTerm.pepe, 2);
});

test('every candidate is attributed to twetch', () => {
  for (const c of twetchCandidates(ITEMS)) assert.equal(c.source, 'twetch.com');
});

test('buildTwetchBaseline emits the baseline file shape the app reads', () => {
  const out = buildTwetchBaseline(ITEMS, { generatedAt: '2026-01-01T00:00:00Z' });
  assert.equal(out.source, 'twetch.com');
  assert.equal(out.generatedAt, '2026-01-01T00:00:00Z');
  const pepe = out.labels.find((l) => l.label === 'Pepe');
  assert.ok(pepe, 'the biggest folder must survive to the baseline');
  assert.equal(pepe.prompt, 'a pepe meme');
  assert.equal(pepe.category, 'character');
  for (const l of out.labels) {
    assert.equal(typeof l.label, 'string');
    assert.equal(typeof l.prompt, 'string');
    assert.equal(typeof l.count, 'number');
    assert.equal(l.source, 'twetch.com');
  }
});

test('a folder that is also a frequent tag appears once, at name rank', () => {
  const labels = buildTwetchBaseline(ITEMS, { generatedAt: null }).labels.map((l) => l.label);
  assert.equal(new Set(labels).size, labels.length);
  assert.ok(labels.indexOf('Pepe') < labels.indexOf('Comfy'));
});

test('the cap is honoured', () => {
  assert.equal(buildTwetchBaseline(ITEMS, { max: 2 }).labels.length, 2);
});

test('harvestStats reports tag coverage so a silent scraper rot is visible', () => {
  const s = harvestStats(ITEMS);
  assert.equal(s.items, 6);
  assert.equal(s.folders, 3);
  assert.equal(s.tagged, 5); // the "png"-only meme does not count as tagged
  assert.equal(s.perFolder.Pepe, 3);
});

test('an empty harvest yields an empty baseline instead of throwing', () => {
  assert.deepEqual(buildTwetchBaseline([], { generatedAt: null }).labels, []);
  assert.deepEqual(harvestStats([]).perFolder, {});
});
