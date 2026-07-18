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
  aggregatePages,
  buildBaseline,
  asArray,
  depotName,
  depotCandidates,
  imgflipCandidates,
  kymCandidates,
  giphyCandidates,
  tenorCandidates,
  buildMultiSourceBaseline,
  parseRobots,
  isDisallowed,
  jsonTerm,
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

test('normalizeTerm guards against [object Object] leakage', () => {
  // String([object Object]) after punctuation-stripping collapses to this.
  assert.equal(normalizeTerm('[object Object]'), '');
  assert.equal(normalizeTerm('object object'), '');
  assert.equal(normalizeTerm('object object object'), '');
});

test('normalizeTerm drops generic denylisted nouns', () => {
  for (const junk of ['Gun', 'car', 'Phone', 'eyes', 'Walmart', 'family', 'gaming']) {
    assert.equal(normalizeTerm(junk), '', `${junk} should be denylisted`);
  }
});

test('normalizeTerm folds apostrophes instead of leaving orphan letters', () => {
  assert.equal(normalizeTerm("Don't Leave Babe"), 'dont leave babe'); // not "don t leave babe"
  assert.equal(normalizeTerm("Auntie Anne's"), 'auntie annes');
  assert.equal(normalizeTerm('E.T.'), ''); // both letters orphaned -> empty
});

test('normalizeTerm drops crypto-ticker/id noise (incl. multi-word)', () => {
  assert.equal(normalizeTerm('Hpos10i Ticker Bitcoin'), ''); // letter+digit token
  assert.equal(normalizeTerm('Btcs 1000u'), ''); // 1000u
  assert.equal(normalizeTerm('Alkanes Monkey 21711'), ''); // 4+ digit run
  assert.equal(normalizeTerm('Lt3 Memes'), ''); // lt3
  assert.equal(normalizeTerm('Web3 Playboys'), ''); // web3
  // ...but legitimate numbers survive
  assert.equal(normalizeTerm('Mario 64'), 'mario 64');
  assert.equal(normalizeTerm('The Simpsons'), 'the simpsons');
});

test('normalizeTerm drops admin/generic depot names', () => {
  for (const junk of ['My Depot', 'Public Testing Depot', 'Meme Templates', 'Marketing', 'Community Art']) {
    assert.equal(normalizeTerm(junk), '', `${junk} should be denylisted`);
  }
  assert.equal(normalizeTerm('Milady'), 'milady'); // real depot survives
  assert.equal(normalizeTerm('Woman Yelling at Cat'), 'woman yelling at cat');
});

test('normalizeTerm drops single-token junk (short, tickers, no-vowel)', () => {
  assert.equal(normalizeTerm('rrs'), ''); // no vowel
  assert.equal(normalizeTerm('gme'), ''); // too short (<4)
  assert.equal(normalizeTerm('usd1'), ''); // contains a digit
  assert.equal(normalizeTerm('51349b'), ''); // id fragment
  assert.equal(normalizeTerm('esq'), ''); // too short
  // ...but real multi-word and normal tokens survive
  assert.equal(normalizeTerm('space odyssey'), 'space odyssey');
  assert.equal(normalizeTerm('Trollface'), 'trollface');
});

test('jsonTerm extracts a name from tag objects, never "[object Object]"', () => {
  assert.equal(jsonTerm('Gigachad'), 'Gigachad');
  assert.equal(jsonTerm({ name: 'Wojak' }), 'Wojak');
  assert.equal(jsonTerm({ title: 'Doomer' }), 'Doomer');
  assert.equal(jsonTerm({ slug: 'this-is-fine', id: 7 }), 'this-is-fine');
  assert.equal(jsonTerm({ id: 7, color: 'green' }), 'green'); // lone string field
  assert.equal(jsonTerm({ id: 7 }), ''); // nothing string-like → dropped
  assert.equal(jsonTerm(null), '');
});

test('extractTagsFromHtml unwraps OBJECT-valued tag arrays (the real-run bug)', () => {
  // memedepot embeds tags as objects; a naive String() produced "[object Object]".
  const html = `<div data-x='"tags":[{"id":1,"name":"Gigachad"},{"id":2,"name":"Wojak"}]'></div>`;
  const got = extractTagsFromHtml(html);
  assert.ok(got.includes('Gigachad'), JSON.stringify(got));
  assert.ok(got.includes('Wojak'), JSON.stringify(got));
  assert.ok(!got.some((t) => /object/i.test(t)), `no object leakage in ${JSON.stringify(got)}`);
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

test('aggregatePages counts each term once per page (no double-count)', () => {
  // "gigachad" appears 3x on page 1 (JSON-LD + inline array + href) but must
  // count as ONE page; it's on 2 of 2 pages, wojak on 1.
  const pages = [
    ['Gigachad', 'gigachad!', 'GIGACHAD', 'Wojak'],
    ['gigachad', 'Pepe'],
  ];
  const freq = aggregatePages(pages);
  assert.equal(freq['gigachad'], 2); // 2 pages, not 4 occurrences
  assert.equal(freq['wojak'], 1);
  assert.equal(freq['pepe'], 1);
});

test('buildBaseline collapses plural variants, higher count wins', () => {
  const freq = { pills: 6, pill: 4, goblin: 3, goblins: 2, trollface: 5 };
  const out = buildBaseline(freq, { generatedAt: '2026-01-01T00:00:00Z' });
  const labels = out.labels.map((l) => l.label);
  assert.deepEqual(labels, ['Pills', 'Trollface', 'Goblin']); // pill folded into Pills, goblins into Goblin
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

test('asArray unwraps bare arrays and common wrappers', () => {
  assert.deepEqual(asArray([1, 2]), [1, 2]);
  assert.deepEqual(asArray({ depots: [{ a: 1 }] }, 'depots'), [{ a: 1 }]);
  assert.deepEqual(asArray({ data: ['x'] }, 'depots', 'data'), ['x']);
  assert.deepEqual(asArray({ whatever: [9] }), [9]); // first array value
  assert.deepEqual(asArray({ n: 1 }), []);
  assert.deepEqual(asArray(null), []);
});

test('depotName prefers a name-like field, falls back to a prettified slug', () => {
  assert.equal(depotName({ name: 'Milady' }), 'Milady');
  assert.equal(depotName({ title: 'Distracted Boyfriend' }), 'Distracted Boyfriend');
  assert.equal(depotName({ slug: 'meme-templates' }), 'meme templates'); // slug fallback
  assert.equal(depotName({ id: 7 }), '');
});

test('depotCandidates: names (high weight) + tags on ≥2 depots (raw count)', () => {
  const depots = [
    { name: 'Milady', tags: [{ name: 'ethereum' }, 'zorp'] },
    { name: 'Wojak', tags: ['ethereum'] },
    { slug: 'this-is-fine', tags: ['reaction'] },
  ];
  const cands = depotCandidates(depots);
  const byTerm = Object.fromEntries(cands.map((c) => [c.term, c.weight]));
  assert.ok(byTerm['Milady'] > 1000); // NAME_BASE band
  assert.ok(byTerm['Wojak'] > 1000);
  assert.equal(byTerm['ethereum'], 2); // tag on 2 depots → raw count
  assert.equal(byTerm['reaction'], undefined); // 1 depot, below the ≥2 floor
  assert.ok(cands.every((c) => c.source === 'memedepot.com'));
});

test('imgflipCandidates: each template a high-weight name, popularity-ranked', () => {
  const cands = imgflipCandidates([{ name: 'Drake Hotline Bling' }, { name: 'Two Buttons' }, { bad: 1 }]);
  assert.deepEqual(
    cands.map((c) => c.term),
    ['Drake Hotline Bling', 'Two Buttons'] // the nameless entry is dropped
  );
  assert.ok(cands[0].weight > cands[1].weight); // popularity order preserved
  assert.ok(cands.every((c) => c.source === 'imgflip.com'));
});

test('kymCandidates: scrapes /memes slugs → names, skips nav, dedupes', () => {
  const html = `
    <a href="/memes/distracted-boyfriend">x</a>
    <a href="/memes/this-is-fine">y</a>
    <a href="/memes/page/2">next</a>       <!-- nav, skipped -->
    <a href="/memes/popular">popular</a>    <!-- nav, skipped -->
    <a href="/memes/distracted-boyfriend">dup</a>`;
  const cands = kymCandidates(html);
  assert.deepEqual(
    cands.map((c) => c.term),
    ['distracted boyfriend', 'this is fine']
  );
  assert.ok(cands.every((c) => c.source === 'knowyourmeme.com'));
  assert.ok(cands[0].weight > cands[1].weight); // page order preserved
});

test('giphyCandidates: categories + subcategories as low-weight tags', () => {
  const json = { data: [{ name: 'Reactions', subcategories: [{ name: 'facepalm' }] }, { name: 'Emotions' }] };
  const cands = giphyCandidates(json);
  assert.deepEqual(cands.map((c) => c.term).sort(), ['Emotions', 'Reactions', 'facepalm']);
  assert.ok(cands.every((c) => c.weight <= 3 && c.source === 'giphy.com'));
});

test('tenorCandidates: featured search-terms as tags', () => {
  const json = { tags: [{ searchterm: 'wojak' }, { searchterm: 'facepalm', name: '#facepalm' }, { image: 'x' }] };
  assert.deepEqual(
    tenorCandidates(json).map((c) => c.term),
    ['wojak', 'facepalm'] // entry without a searchterm/name dropped
  );
});

test('buildMultiSourceBaseline: cross-source stem-dedupe, names lead, filter applies', () => {
  const candidates = [
    ...depotCandidates([
      { name: 'Milady', tags: ['ethereum', 'ethereum'] },
      { name: 'Wojak', tags: ['ethereum'] },
      { name: 'Gun' }, // denylisted → dropped
    ]),
    ...imgflipCandidates([{ name: 'Wojak' }, { name: 'Distracted Boyfriend' }]),
  ];
  const { labels } = buildMultiSourceBaseline(candidates, { generatedAt: '2026-01-01T00:00:00Z' });
  const names = labels.map((l) => l.label);
  assert.ok(names.includes('Milady'));
  assert.ok(names.includes('Distracted Boyfriend'));
  assert.ok(!names.includes('Gun')); // denylisted noun filtered
  assert.equal(names.filter((n) => n === 'Wojak').length, 1); // deduped across sources
  // A name outranks the "ethereum" tag (which appears on 2 depots).
  assert.ok(labels[0].count > (labels.find((l) => l.label === 'Ethereum')?.count ?? 0));
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
