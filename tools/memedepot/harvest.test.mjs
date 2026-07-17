// Unit tests for the harvester's pure helpers. Run with `node --test` (or
// `npm run harvest:test`). The network orchestration in main() is deliberately
// not covered — it can't run from the dev sandbox (memedepot is egress-blocked);
// that's exactly why the harvest runs in CI. These lock the deterministic
// parsing/normalization logic so a refactor can't silently break extraction.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  normalizeTerm,
  titleCase,
  guessCategory,
  templatePrompt,
  parseSitemap,
  extractTagsFromHtml,
  aggregate,
  buildBaseline,
  parseRobots,
  isDisallowed,
} from './harvest.mjs';

test('normalizeTerm lowercases, strips punctuation, drops junk', () => {
  assert.equal(normalizeTerm('  Gigachad! '), 'gigachad');
  assert.equal(normalizeTerm('Distracted_Boyfriend'), 'distracted boyfriend');
  assert.equal(normalizeTerm('the'), ''); // stopword
  assert.equal(normalizeTerm('meme'), ''); // stopword
  assert.equal(normalizeTerm('a'), ''); // too short
  assert.equal(normalizeTerm('12345'), ''); // pure number
  assert.equal(normalizeTerm(null), '');
});

test('titleCase keeps short connectors lowercase', () => {
  assert.equal(titleCase('distracted boyfriend'), 'Distracted Boyfriend');
  assert.equal(titleCase('is this a pigeon'), 'Is This a Pigeon');
});

test('guessCategory maps hints, defaults to topic', () => {
  assert.equal(guessCategory('drake format'), 'format');
  assert.equal(guessCategory('soyjak'), 'character');
  assert.equal(guessCategory('cursed'), 'emotion');
  assert.equal(guessCategory('crypto'), 'topic');
  assert.equal(guessCategory('something unknown'), 'topic');
});

test('templatePrompt builds a CLIP-style prompt', () => {
  assert.equal(templatePrompt('gigachad'), 'a gigachad meme');
});

test('parseSitemap extracts loc URLs', () => {
  const xml = `<urlset><url><loc>https://memedepot.com/d/a</loc></url>
    <url><loc> https://memedepot.com/d/b </loc></url></urlset>`;
  assert.deepEqual(parseSitemap(xml), ['https://memedepot.com/d/a', 'https://memedepot.com/d/b']);
});

test('extractTagsFromHtml pulls terms from keywords, JSON-LD, arrays, and tag hrefs', () => {
  const html = `
    <meta name="keywords" content="Gigachad, Wojak">
    <script type="application/ld+json">{"keywords":["Pepe","Doomer"]}</script>
    <div data-x='"tags":["Soyjak","Cheems"]'></div>
    <a href="/tag/distracted-boyfriend">x</a>
    <a href="/t/this-is-fine">y</a>`;
  const got = extractTagsFromHtml(html);
  for (const t of ['Gigachad', 'Wojak', 'Pepe', 'Doomer', 'Soyjak', 'Cheems', 'distracted-boyfriend', 'this-is-fine']) {
    assert.ok(got.includes(t), `expected ${t} in ${JSON.stringify(got)}`);
  }
});

test('extractTagsFromHtml tolerates malformed JSON-LD without throwing', () => {
  assert.deepEqual(extractTagsFromHtml('<script type="application/ld+json">{ not json </script>'), []);
});

test('aggregate normalizes and counts', () => {
  const freq = aggregate(['Gigachad', 'gigachad!', 'the', 'Wojak']);
  assert.equal(freq['gigachad'], 2);
  assert.equal(freq['wojak'], 1);
  assert.equal(freq['the'], undefined);
});

test('buildBaseline ranks, drops singletons, caps, and stays pure', () => {
  const freq = { gigachad: 10, wojak: 5, obscure: 1, cheems: 3 };
  const out = buildBaseline(freq, { max: 2, generatedAt: '2026-01-01T00:00:00Z' });
  assert.equal(out.generatedAt, '2026-01-01T00:00:00Z');
  assert.deepEqual(
    out.labels.map((l) => l.label),
    ['Gigachad', 'Wojak'] // ranked by count, capped at 2, singleton "obscure" dropped
  );
  assert.equal(out.labels[0].prompt, 'a gigachad meme');
  assert.equal(out.labels[0].count, 10);
});

test('parseRobots collects sitemaps and *-scoped disallows', () => {
  const txt = `Sitemap: https://memedepot.com/sitemap.xml
User-agent: *
Disallow: /api/
Disallow: /admin/
User-agent: BadBot
Disallow: /`;
  const { sitemaps, disallow } = parseRobots(txt);
  assert.deepEqual(sitemaps, ['https://memedepot.com/sitemap.xml']);
  assert.deepEqual(disallow, ['/api/', '/admin/']); // BadBot's rule ignored
});

test('isDisallowed honors robots disallow prefixes', () => {
  const base = 'https://memedepot.com';
  assert.equal(isDisallowed('https://memedepot.com/api/x', ['/api/'], base), true);
  assert.equal(isDisallowed('https://memedepot.com/d/funny', ['/api/'], base), false);
});
