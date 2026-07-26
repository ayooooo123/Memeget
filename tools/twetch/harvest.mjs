// Twetch Meme Library harvester (dev/CI only — never bundled, never on-device).
//
// Twetch's meme library ("Dank Rares") is an on-chain archive of ~6.5k memes,
// organised into curated folders and — unlike every other source we harvest —
// carrying HUMAN-APPLIED TAGS per meme. That makes it the closest public thing
// to the vocabulary this app's users actually search with: pepe/wojak/apu
// culture, crypto, and reaction words, tagged by the people who post them.
//
// It writes `src/data/twetchBaseline.json`, the third breadth tier under the
// curated core (see src/baselineLabels.ts). The pure helpers are shared with
// the memedepot harvester so both tiers normalize, filter and rank terms
// identically — one convention, one denylist, one quality bar.
//
// The API is public, unauthenticated JSON and reachable from the dev sandbox:
//   GET /v1/dank-rares/folders          -> { categories: [{ name, label, slug, count }] }
//   GET /v1/dank-rares?limit&cursor     -> { items: [{ tags, folder, title, … }], nextCursor, total }
// so unlike memedepot this one can be run locally as well as in CI.
//
// POLITENESS: paginates with a delay, identifies itself, caps total pages, and
// collects ONLY derived text (folder names, tag terms, frequencies) — never the
// meme images or any on-chain identifiers. Keep it that way.

import { writeFile } from 'node:fs/promises';

import {
  NAME_BASE,
  aggregatePages,
  asArray,
  buildMultiSourceBaseline,
  guessCategory,
  jsonTerm,
} from '../memedepot/harvest.mjs';

const DEFAULTS = {
  base: 'https://api.twetch.com',
  out: new URL('../../src/data/twetchBaseline.json', import.meta.url).pathname,
  maxItems: 8000, // the library is ~6.5k; the cap is a runaway guard
  pageSize: 200,
  maxTags: 300,
  delayMs: 400,
  timeoutMs: 20000,
};

const SOURCE = 'twetch.com';
const UA = 'memeget-harvester/1.0 (+https://github.com/ayooooo123/Memeget)';

// ---- pure helpers (unit-tested in harvest.test.mjs) -------------------------

// File-container words the API stores alongside real tags ("png", "gif"). They
// are the single most frequent "tag" in the corpus (png is on ~90% of memes) and
// describe nothing about the meme, so they must never reach the label set.
const FORMAT_NOISE = new Set(['png', 'gif', 'jpg', 'jpeg', 'webp', 'mp4', 'webm', 'apng', 'svg', 'image', 'video']);

// A folder's name as a meme label. Folders read "Pepe Memes", "$TOSHI Memes" —
// the trailing noun and the ticker sigil are packaging, and leaving them on
// would produce the prompt "a pepe memes meme".
export function folderLabel(raw) {
  if (typeof raw !== 'string') return '';
  return raw
    .replace(/^\$+/, '')
    .replace(/\s+memes?$/i, '')
    .trim();
}

// Twetch adapter. Two tiers, same convention as every other source:
//
//   NAME tier — the library's folders, read off the memes themselves rather
//   than from /v1/dank-rares/folders: that endpoint returns aspect buckets
//   (Laugh, Sad, Computer, Weapon), while a meme's own `folder` is the curated
//   format/character category the site organises around (Pepe, Wojak, Bobo,
//   Brainlet, Apu, Grug, BSV). Ranked by how many memes each holds, so the most
//   established format leads.
//
//   TAG tier — per-meme tags, counted across memes with a per-meme dedupe
//   (aggregatePages treats each meme as a "page"), so `count` means "how many
//   distinct memes carry this tag". Singletons are dropped by the floor below,
//   and the shared normalizeTerm/denylist strips the generic-object tail that
//   makes terrible zero-shot classes.
export function twetchCandidates(items = []) {
  const out = [];

  const folderCounts = new Map();
  for (const m of items) {
    const name = folderLabel(typeof m?.folder === 'string' ? m.folder : '');
    if (name) folderCounts.set(name, (folderCounts.get(name) ?? 0) + 1);
  }
  [...folderCounts.entries()]
    .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))
    .forEach(([term], i) => out.push({ term, weight: NAME_BASE - i, source: SOURCE }));

  const tagFreq = aggregatePages(
    items.map((m) =>
      (Array.isArray(m?.tags) ? m.tags.map(jsonTerm) : []).filter(
        (t) => !FORMAT_NOISE.has(String(t).trim().toLowerCase())
      )
    )
  );
  for (const [term, count] of Object.entries(tagFreq)) {
    if (count >= 2) out.push({ term, weight: count, source: SOURCE });
  }
  return out;
}

// Full baseline file from a fetched corpus. Split from the network code so the
// whole transformation is testable against a fixture.
export function buildTwetchBaseline(items, { max = DEFAULTS.maxTags, generatedAt = null } = {}) {
  return buildMultiSourceBaseline(twetchCandidates(items), {
    max,
    source: SOURCE,
    generatedAt,
  });
}

// Coverage report for the harvest log: which folders contributed, and how much
// of the corpus carries tags at all. A silent drop in either is how a scraper
// rots without anyone noticing.
export function harvestStats(items) {
  const tagged = items.filter(
    (m) =>
      Array.isArray(m?.tags) &&
      m.tags.some((t) => !FORMAT_NOISE.has(String(t).trim().toLowerCase()))
  ).length;
  const perFolder = {};
  for (const m of items) {
    const f = folderLabel(typeof m?.folder === 'string' ? m.folder : '') || '(none)';
    perFolder[f] = (perFolder[f] ?? 0) + 1;
  }
  return {
    items: items.length,
    folders: Object.keys(perFolder).filter((f) => f !== '(none)').length,
    tagged,
    taggedShare: items.length ? tagged / items.length : 0,
    perFolder,
  };
}

// ---- network orchestration (not unit-tested) --------------------------------

function parseArgs(argv) {
  const opts = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) continue;
    const k = argv[i].slice(2);
    const v = argv[i + 1];
    if (v === undefined || v.startsWith('--')) continue;
    opts[k] = ['maxItems', 'pageSize', 'maxTags', 'delayMs', 'timeoutMs'].includes(k) ? Number(v) : v;
    i++;
  }
  return opts;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function fetchJson(url, timeoutMs) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      headers: { accept: 'application/json', 'user-agent': UA },
      signal: ctrl.signal,
    });
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  console.log(`[twetch] ${opts.base}/v1/dank-rares — up to ${opts.maxItems} memes`);

  const items = [];
  let cursor = '';
  while (items.length < opts.maxItems) {
    const qs = new URLSearchParams({ limit: String(opts.pageSize) });
    if (cursor) qs.set('cursor', cursor);
    const page = await fetchJson(`${opts.base}/v1/dank-rares?${qs}`, opts.timeoutMs);
    const batch = asArray(page, 'items');
    if (batch.length === 0) break;
    items.push(...batch);
    process.stdout.write(`\r[twetch] memes: ${items.length}${page.total ? `/${page.total}` : ''}`);
    cursor = typeof page.nextCursor === 'string' ? page.nextCursor : '';
    if (!cursor) break;
    await sleep(opts.delayMs);
  }
  process.stdout.write('\n');

  const stats = harvestStats(items);
  console.log(
    `[twetch] ${stats.items} memes, ${stats.folders} folders, ` +
      `${stats.tagged} carrying real tags (${(100 * stats.taggedShare).toFixed(1)}%)`
  );
  for (const [folder, n] of Object.entries(stats.perFolder).sort((a, b) => b[1] - a[1])) {
    console.log(`[twetch]   ${folder}: ${n}`);
  }
  // A shape change (renamed `items`, `tags` gone) reads as an empty harvest.
  // Fail loudly instead of overwriting a good baseline with nothing.
  if (stats.items === 0 || stats.taggedShare < 0.2) {
    console.error('[twetch] corpus looks wrong — inspect /v1/dank-rares before trusting this');
    process.exitCode = 1;
    return;
  }

  const baseline = buildTwetchBaseline(items, {
    max: opts.maxTags,
    generatedAt: new Date().toISOString(),
  });
  await writeFile(opts.out, `${JSON.stringify(baseline, null, 2)}\n`);
  console.log(`[twetch] wrote ${baseline.labels.length} labels -> ${opts.out}`);
  console.log(`[twetch] top: ${baseline.labels.slice(0, 15).map((l) => l.label).join(', ')}`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((err) => {
    console.error('[twetch] harvest failed:', err.message);
    process.exitCode = 1;
  });
}
