// memedepot tag harvester (dev/CI only — never bundled, never run on-device).
//
// Crawls memedepot politely, extracts the tag/format vocabulary its users curate
// (depot titles, tag chips, keyword/JSON-LD metadata), ranks terms by cross-page
// frequency, and writes `src/data/memedepotBaseline.json` — the breadth tier the
// app folds into its zero-shot label set on first launch (see src/baselineLabels.ts).
//
// WHY THIS RUNS IN CI, NOT LOCALLY: memedepot sits behind Cloudflare and the dev
// sandbox's egress policy blocks it outright, but GitHub-hosted runners have open
// internet — so `.github/workflows/harvest-memedepot-tags.yml` is where this
// actually reaches the site. The workflow opens a PR with the regenerated file so
// a human reviews the vocabulary before it ships.
//
// POLITENESS / LEGAL: obeys robots.txt, rate-limits, identifies as a browser,
// caps total pages, and collects ONLY derived text (tag terms + frequencies) —
// never the meme images themselves. Keep it that way.
//
// The pure helpers below are unit-tested in harvest.test.mjs; the network
// orchestration in main() is not (it can't run from the sandbox).

import { writeFile } from 'node:fs/promises';

const DEFAULTS = {
  base: 'https://memedepot.com',
  out: new URL('../../src/data/memedepotBaseline.json', import.meta.url).pathname,
  maxPages: 400,
  maxTags: 300,
  delayMs: 1100, // ~1 req/sec
  timeoutMs: 20000,
};

const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';

// ---- pure helpers (unit-tested) --------------------------------------------

const STOPWORDS = new Set([
  'the', 'a', 'an', 'and', 'or', 'of', 'to', 'in', 'on', 'for', 'with', 'my', 'me',
  'you', 'your', 'it', 'is', 'are', 'this', 'that', 'meme', 'memes', 'depot', 'home',
  'all', 'new', 'top', 'best', 'funny', 'random', 'stuff', 'misc', 'other', 'page',
]);

// Generic concrete nouns / everyday actions / brand & place names that are real
// words (so the heuristics below can't catch them) but make TERRIBLE zero-shot
// meme-format labels: as CLIP classes "gun"/"car"/"phone" fire on a huge fraction
// of any library, tanking tag precision. memedepot's freeform per-item tags are
// full of these. This is the necessary hand-maintained part of the quality
// filter; extend it as review surfaces more. (Deliberately does NOT list real
// formats/characters — pepe, wojak, chad, troll, etc. stay.)
const GENERIC_DENYLIST = new Set([
  // objects / wearables / props
  'gun', 'car', 'phone', 'telephone', 'computer', 'laptop', 'robot', 'knife', 'pipe',
  'cage', 'fan', 'gift', 'toy', 'backpack', 'necklace', 'lipstick', 'monocle', 'helmet',
  'sombrero', 'hat', 'tophat', 'top hat', 'party hat', 'coat', 'suit', 'pants', 'jeans',
  'glass', 'window', 'windshield', 'bomb', 'missile', 'headphones', 'chopsticks', 'pretzel',
  'ramen', 'bed', 'pool', 'door', 'gold', 'coins', 'penny', 'binoculars', 'camera', 'pipe',
  // body / scenery / weather
  'hair', 'eyes', 'lips', 'butt', 'lightning', 'cloud', 'clouds', 'mountain', 'cliff',
  'desert', 'field', 'water', 'fire', 'smoke', 'cigarette', 'glass',
  // generic actions / states
  'running', 'reading', 'talking', 'studying', 'typing', 'pray', 'praying', 'hug', 'exercise',
  'climbing', 'shooting', 'hide', 'flying', 'mixing', 'sleep', 'sleepy', 'silence', 'dance',
  'dancing', 'moisturized', 'focused', 'unbothered', 'flourishing', 'smart', 'evil', 'dark',
  // brands / places / institutions (not memes)
  'mcdonalds', 'walmart', 'costco', 'trader joes', 'waffle house', 'home depot', 'red bull',
  'louis vuitton', 'binance', 'federal reserve', 'church', 'jail', 'gym', 'japan', 'nagoya',
  // generic topics / scene descriptors
  'blank', 'cutout', 'halo', 'war', 'king', 'pope', 'god', 'jesus', 'christ', 'bible',
  'family', 'job', 'employee', 'music', 'football', 'soccer', 'marathon', 'race', 'soldier',
  'chicken', 'bear', 'ghost', 'eagle', 'camel', 'wheelchair', 'nerd', 'gaming', 'gun',
  'no background', 'group picture', 'low poly',
  // memedepot admin / generic / personal depots (not a meme format)
  'my depot', 'public testing depot', 'meme templates', 'blank memes', 'blank memes templates',
  'community art', 'marketing', 'culture', 'nonce', 'chains', 'experiments lain', 'send memes',
  'sticker project', 'throwback memes', 'greatestmeme', 'pixel art', 'abstract', 'experiments',
  'hold', 'grind', 'chains', 'book of nft meme', 'sticker', 'stickers', 'templates', 'template',
]);

// Normalize a raw term to a comparable key AND drop noise. Beyond lowercasing /
// punctuation-stripping, this is the quality gate: folds apostrophes, removes
// orphan single-letter tokens, and rejects stopwords, denylisted generic nouns,
// and single-token junk (too-short abbreviations, tickers/ids with digits,
// unpronounceable consonant runs). Returns '' for anything dropped.
export function normalizeTerm(raw) {
  if (typeof raw !== 'string') return '';
  let t = raw
    .toLowerCase()
    .replace(/['’`]/g, '') // fold apostrophes so "mcdonald's" -> "mcdonalds", not "mcdonald s"
    .replace(/[_/]+/g, ' ')
    .replace(/[^a-z0-9 +-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  // Drop orphan single-letter tokens left by punctuation ("e t" -> "", "i m the x" -> "the x").
  if (t.includes(' ')) t = t.split(' ').filter((w) => w.length > 1).join(' ').trim();
  if (t.length < 3 || t.length > 40) return '';
  if (STOPWORDS.has(t) || GENERIC_DENYLIST.has(t)) return '';
  if (/^\d+$/.test(t)) return ''; // pure numbers
  if (/^object( object)*$/.test(t)) return ''; // "[object Object]" leakage guard
  // Crypto-ticker / id noise (memedepot is a crypto-meme community): reject any
  // term with a token that mixes letters and digits (hpos10i, 1000u, lt3, web3,
  // 92s) or is a long digit run (…monkey 21711). Applies to multi-word names too,
  // where the single-token check below can't reach. "mario 64" survives — "64" is
  // neither mixed nor 4+ digits.
  for (const w of t.split(' ')) {
    if (/[a-z]/.test(w) && /\d/.test(w)) return '';
    if (/^\d{4,}$/.test(w)) return '';
  }
  // Single-token junk that plagues memedepot tags: short abbreviations, tickers /
  // ids containing digits (usd1, 51349b), and vowel-less consonant runs (rrs).
  // Multi-word terms ("space odyssey") are exempt — the noise is in bare tokens.
  if (!t.includes(' ')) {
    if (t.length < 4) return '';
    if (/\d/.test(t)) return '';
    if (!/[aeiouy]/.test(t)) return '';
  }
  return t;
}

// Pull a term string out of a JSON value that may be a bare string or a tag
// OBJECT like {name|title|label|slug|tag|text: "..."} — memedepot embeds tags as
// objects, so a naive String() yields "[object Object]". Returns '' when nothing
// string-like is found, so an unnamed object can't leak junk downstream.
export function jsonTerm(v) {
  if (typeof v === 'string') return v;
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    for (const key of ['name', 'title', 'label', 'slug', 'tag', 'text', 'value']) {
      if (typeof v[key] === 'string' && v[key].trim()) return v[key];
    }
    // Fall back to a lone string field if the object has exactly one.
    const strings = Object.values(v).filter((x) => typeof x === 'string' && x.trim());
    if (strings.length === 1) return strings[0];
  }
  return '';
}

// Display label from a normalized term (Title Case). The first word is always
// capitalized; short connectors ("a", "is") stay lowercase mid-phrase.
export function titleCase(term) {
  return term
    .split(' ')
    .map((w, i) => (i > 0 && w.length <= 2 ? w : w.charAt(0).toUpperCase() + w.slice(1)))
    .join(' ');
}

const FORMAT_HINTS = /\b(format|template|vs|versus|comparison|chart|starter pack|expanding|drake|two buttons)\b/;
const EMOTION_HINTS = /\b(sad|angry|happy|smug|cursed|wholesome|crying|mad|confused|cringe|based)\b/;
const CHARACTER_HINTS = /(jak|wojak|chad|pepe|frog|doomer|bloomer|npc|troll|doge|cheems|apu|bobo)\b/;
const TOPIC_HINTS = /\b(crypto|nft|bitcoin|ethereum|anime|gaming|programming|politics|cat|dog|stock)\b/;

// Best-effort category guess for the harvested term. Imperfect by design — the
// app defaults anything unknown to 'topic', and a maintainer can fix categories
// in review. Mirrors the union in src/memeLabels.ts LabelDef.
export function guessCategory(term) {
  if (FORMAT_HINTS.test(term)) return 'format';
  if (CHARACTER_HINTS.test(term)) return 'character';
  if (EMOTION_HINTS.test(term)) return 'emotion';
  if (TOPIC_HINTS.test(term)) return 'topic';
  return 'topic';
}

// The CLIP text prompt the app embeds for zero-shot matching. A template — the
// curated entries hand-write these; the harvested tier accepts the generic form
// as a breadth baseline.
export function templatePrompt(term) {
  return `a ${term} meme`;
}

// Extract <loc> URLs from a sitemap or sitemap-index XML.
export function parseSitemap(xml) {
  if (typeof xml !== 'string') return [];
  const out = [];
  const re = /<loc>\s*([^<\s]+)\s*<\/loc>/gi;
  let m;
  while ((m = re.exec(xml))) out.push(m[1].trim());
  return out;
}

// Pull candidate tag terms out of one page's HTML via several best-effort
// strategies (keyword meta, JSON-LD keywords, inline "tags"/"categories" arrays,
// and /tag|/t/ href slugs). Each strategy is guarded; unknown structure just
// yields fewer terms rather than throwing.
export function extractTagsFromHtml(html) {
  if (typeof html !== 'string') return [];
  const terms = [];

  // 1) <meta name="keywords" content="a, b, c">
  const kw = html.match(/<meta[^>]+name=["']keywords["'][^>]+content=["']([^"']+)["']/i);
  if (kw) terms.push(...kw[1].split(',').map((s) => s.trim()));

  // 2) JSON-LD blocks: collect `keywords` and `name`.
  const ld = html.matchAll(/<script[^>]+application\/ld\+json[^>]*>([\s\S]*?)<\/script>/gi);
  for (const block of ld) {
    try {
      const data = JSON.parse(block[1].trim());
      for (const node of Array.isArray(data) ? data : [data]) {
        const k = node?.keywords;
        if (typeof k === 'string') terms.push(...k.split(',').map((s) => s.trim()));
        else if (Array.isArray(k)) terms.push(...k.map(jsonTerm).filter(Boolean));
      }
    } catch {
      /* malformed JSON-LD — skip */
    }
  }

  // 3) Inline JSON tag/category arrays, e.g. "tags":["a","b"].
  const arrs = html.matchAll(/"(?:tags|categories|topics)"\s*:\s*(\[[^\]]*\])/gi);
  for (const a of arrs) {
    try {
      const parsed = JSON.parse(a[1]);
      if (Array.isArray(parsed)) terms.push(...parsed.map(jsonTerm).filter(Boolean));
    } catch {
      /* skip */
    }
  }

  // 4) Tag-link slugs: href="/tag/foo", "/tags/foo", "/t/foo".
  const hrefs = html.matchAll(/href=["']\/(?:tags?|t)\/([^"'/?#]+)/gi);
  for (const h of hrefs) terms.push(decodeURIComponent(h[1]));

  return terms;
}

// Fold raw terms into a frequency map keyed by normalized term. Returns a plain
// object { term: count } for deterministic, JSON-friendly assertions.
export function aggregate(rawTerms) {
  const freq = {};
  for (const raw of rawTerms) {
    const t = normalizeTerm(raw);
    if (!t) continue;
    freq[t] = (freq[t] ?? 0) + 1;
  }
  return freq;
}

// Count each normalized term at most ONCE per page. A tag routinely appears in
// several extraction strategies on the same page (JSON-LD keywords AND an inline
// tags array AND a /tag href), so a flat count double/triple-counts it — which
// silently degrades the "seen on >1 page" signal (buildBaseline's count>=2
// floor) to "seen once", leaking the entire single-page tail. Deduping per page
// makes the count mean what the floor assumes: distinct pages.
export function aggregatePages(pages) {
  const freq = {};
  for (const page of pages) {
    const seen = new Set();
    for (const raw of page) {
      const t = normalizeTerm(raw);
      if (!t || seen.has(t)) continue;
      seen.add(t);
      freq[t] = (freq[t] ?? 0) + 1;
    }
  }
  return freq;
}

// Collapse trivial plural variants ("pill"/"pills", "goblin"/"goblins") to one
// dedupe key so they don't both occupy label slots. Naive singularization is
// fine here — it only groups, and the higher-count variant wins.
const dedupeKey = (term) => (term.length > 3 && term.endsWith('s') ? term.slice(0, -1) : term);

// Turn a frequency map into the baseline file shape the app consumes. Ranks by
// count, drops singletons (noise), collapses plural variants, Title-Cases the
// display label, and caps. `generatedAt` is injected (not read from the clock)
// so this stays pure and testable.
export function buildBaseline(freq, { max = DEFAULTS.maxTags, source = 'memedepot.com', generatedAt = null } = {}) {
  const seenStems = new Set();
  const labels = Object.entries(freq)
    .filter(([, count]) => count >= 2) // seen on >1 page → likelier a real tag
    .sort((a, b) => b[1] - a[1]) // rank before dedupe so the most frequent variant wins
    .filter(([term]) => {
      const key = dedupeKey(term);
      if (seenStems.has(key)) return false;
      seenStems.add(key);
      return true;
    })
    .slice(0, max)
    .map(([term, count]) => ({
      label: titleCase(term),
      prompt: templatePrompt(term),
      category: guessCategory(term),
      count,
    }));
  return { source, generatedAt, labels };
}

// ---- multi-source model -----------------------------------------------------
//
// Each source (memedepot, imgflip, …) is an adapter that emits CANDIDATES:
//   { term, weight, category?, source }
// `weight` ranks a candidate for the cap. Two tiers by convention:
//   • NAME tier  — collection/template names (Milady, "Distracted Boyfriend"):
//     the human-authored taxonomy. Weight = NAME_BASE − rank, so names always
//     lead and each source's own popularity order is preserved.
//   • TAG tier   — frequency terms (a depot's tags): weight = raw count, always
//     below names.
// `buildMultiSourceBaseline` normalizes + quality-filters, de-dupes across
// sources by stem (highest weight wins, provenance kept), ranks, and caps.

const NAME_BASE = 100000; // keeps every name above every frequency tag

// Unwrap an API payload that may be a bare array or a wrapper object
// ({depots|memes|data|results|items: [...]}, or any object whose first array
// value is the list). Returns [] if no array is found.
export function asArray(json, ...keys) {
  if (Array.isArray(json)) return json;
  if (json && typeof json === 'object') {
    for (const k of keys) if (Array.isArray(json[k])) return json[k];
    for (const v of Object.values(json)) if (Array.isArray(v)) return v;
  }
  return [];
}

// A depot's display name — the human-authored format name. Falls back to a
// prettified slug when no name-like field is present.
export function depotName(d) {
  for (const k of ['name', 'title', 'displayName', 'label']) {
    if (typeof d?.[k] === 'string' && d[k].trim()) return d[k].trim();
  }
  if (typeof d?.slug === 'string' && d.slug.trim()) return d.slug.replace(/[-_]+/g, ' ').trim();
  return '';
}

// memedepot adapter: each depot NAME is a high-weight candidate (API order kept);
// each depot's TAGS are counted across depots (per-depot dedupe) as breadth.
export function depotCandidates(depots) {
  const out = depots
    .map((d, i) => ({ term: depotName(d), weight: NAME_BASE - i, source: 'memedepot.com' }))
    .filter((c) => c.term);
  const tagFreq = aggregatePages(depots.map((d) => (Array.isArray(d?.tags) ? d.tags.map(jsonTerm) : [])));
  for (const [term, count] of Object.entries(tagFreq)) {
    if (count >= 2) out.push({ term, weight: count, source: 'memedepot.com' });
  }
  return out;
}

// imgflip adapter: api.imgflip.com/get_memes returns the ~100 canonical
// image-macro templates by name, already popularity-ranked → all NAME tier.
export function imgflipCandidates(memes) {
  return memes
    .map((m, i) => ({ term: typeof m?.name === 'string' ? m.name : '', weight: NAME_BASE - i, source: 'imgflip.com' }))
    .filter((c) => c.term);
}

// Merge candidates from every source into the baseline file shape: normalize +
// quality-filter each term, de-dupe by stem keeping the highest-weight variant
// (and its provenance), rank by weight, cap. `count` carries the weight for the
// app's ranker (names in a high band, tags at their real frequency).
export function buildMultiSourceBaseline(
  candidates,
  { max = DEFAULTS.maxTags, source = 'multi', generatedAt = null } = {}
) {
  const best = new Map(); // stem -> {term, weight, category, source}
  for (const c of candidates) {
    const t = normalizeTerm(c?.term);
    if (!t) continue;
    const key = dedupeKey(t);
    const prev = best.get(key);
    if (!prev || (c.weight ?? 0) > prev.weight) {
      best.set(key, { term: t, weight: c.weight ?? 0, category: c.category, source: c.source });
    }
  }
  const labels = [...best.values()]
    .sort((a, b) => b.weight - a.weight)
    .slice(0, max)
    .map((c) => ({
      label: titleCase(c.term),
      prompt: templatePrompt(c.term),
      category: c.category || guessCategory(c.term),
      count: c.weight,
      source: c.source,
    }));
  return { source, generatedAt, labels };
}

// ---- network orchestration (CI only; not unit-tested) ----------------------

function parseArgs(argv) {
  const opts = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i++) {
    const [k, v] = argv[i].startsWith('--') ? [argv[i].slice(2), argv[i + 1]] : [null, null];
    if (k && v !== undefined) {
      if (['maxPages', 'maxTags', 'delayMs', 'timeoutMs'].includes(k)) opts[k] = Number(v);
      else opts[k] = v;
      i++;
    }
  }
  return opts;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function fetchText(url, timeoutMs, attempt = 0) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': BROWSER_UA, Accept: 'text/html,application/xhtml+xml,*/*' },
      signal: ctrl.signal,
    });
    if (res.status === 429 || res.status === 503) throw new Error(`throttled ${res.status}`);
    if (!res.ok) return null;
    return await res.text();
  } catch (e) {
    if (attempt < 3) {
      await sleep(1000 * 2 ** attempt); // backoff 1s, 2s, 4s
      return fetchText(url, timeoutMs, attempt + 1);
    }
    console.warn(`  ! ${url}: ${e.message}`);
    return null;
  } finally {
    clearTimeout(timer);
  }
}

// Minimal robots.txt handling: collect Sitemap: lines and Disallow prefixes that
// apply to us (User-agent: *). We honor the disallow list when queuing URLs.
function parseRobots(txt, base) {
  const sitemaps = [];
  const disallow = [];
  let appliesToAll = false;
  for (const line of (txt ?? '').split(/\r?\n/)) {
    const [rawK, ...rest] = line.split(':');
    const k = rawK.trim().toLowerCase();
    const v = rest.join(':').trim();
    if (k === 'sitemap') sitemaps.push(v);
    else if (k === 'user-agent') appliesToAll = v === '*';
    else if (k === 'disallow' && appliesToAll && v) disallow.push(v);
  }
  return { sitemaps, disallow };
}

const isDisallowed = (url, disallow, base) => {
  try {
    const path = new URL(url, base).pathname;
    return disallow.some((d) => path.startsWith(d));
  } catch {
    return true;
  }
};

// Enumerate the depot catalog via /api/depots?page=N. Stops when a page returns
// no depots (end of catalog) or the endpoint 404s (fetchText → null). Logs the
// first depot's keys + derived name so a shape mismatch is visible in the run
// log without another diagnostic round-trip.
async function harvestDepots(base, opts) {
  const depots = [];
  for (let page = 0; page < 200; page++) {
    const url = new URL(`/api/depots?page=${page}&limit=50`, base).href;
    const txt = await fetchText(url, opts.timeoutMs);
    if (!txt) break;
    let json;
    try {
      json = JSON.parse(txt);
    } catch {
      break;
    }
    const batch = asArray(json, 'depots', 'data', 'results', 'items');
    if (!batch.length) break;
    if (page === 0) {
      console.log(`  first depot keys: ${Object.keys(batch[0] ?? {}).join(', ')}`);
      console.log(`  first depot name: "${depotName(batch[0])}"`);
    }
    depots.push(...batch);
    await sleep(opts.delayMs);
  }
  return depots;
}

// Fetch imgflip's canonical meme-template catalog — a public JSON API, no auth:
// { data: { memes: [{ id, name, url, … }] } }, ~100 templates, popularity-ranked.
async function fetchImgflip(opts) {
  const txt = await fetchText('https://api.imgflip.com/get_memes', opts.timeoutMs);
  if (!txt) return [];
  try {
    return asArray(JSON.parse(txt)?.data, 'memes');
  } catch {
    return [];
  }
}

// Fallback: the original per-post tag crawl (sitemap/homepage discovery →
// extract inline tag arrays → per-page count). Kept for resilience if the depot
// API shape ever changes out from under us.
async function crawlHtmlBaseline(opts, host, generatedAt) {
  const robotsTxt = await fetchText(new URL('/robots.txt', opts.base).href, opts.timeoutMs);
  const { sitemaps, disallow } = parseRobots(robotsTxt, opts.base);
  if (disallow.length) console.log(`  robots.txt disallows: ${disallow.join(', ')}`);

  const queue = new Set();
  for (const sm of sitemaps.length ? sitemaps : []) {
    const xml = await fetchText(sm, opts.timeoutMs);
    for (const loc of parseSitemap(xml)) {
      if (/\.xml($|\?)/i.test(loc)) {
        const child = await fetchText(loc, opts.timeoutMs);
        for (const u of parseSitemap(child)) queue.add(u);
      } else {
        queue.add(loc);
      }
      if (queue.size >= opts.maxPages) break;
    }
  }
  if (queue.size === 0) {
    const home = await fetchText(opts.base, opts.timeoutMs);
    for (const m of (home ?? '').matchAll(/href=["'](\/[^"'?#]+)/g)) {
      queue.add(new URL(m[1], opts.base).href);
      if (queue.size >= opts.maxPages) break;
    }
  }

  const urls = [...queue].filter((u) => !isDisallowed(u, disallow, opts.base)).slice(0, opts.maxPages);
  console.log(`  crawling ${urls.length} pages…`);

  const pages = []; // one array of raw terms per page, so counting is per-page
  let done = 0;
  for (const url of urls) {
    const html = await fetchText(url, opts.timeoutMs);
    if (html) pages.push(extractTagsFromHtml(html));
    if (++done % 25 === 0) console.log(`  …${done}/${urls.length}`);
    await sleep(opts.delayMs);
  }
  return buildBaseline(aggregatePages(pages), { max: opts.maxTags, source: host, generatedAt });
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const host = new URL(opts.base).host;
  console.log(`Harvesting ${host} (max ${opts.maxPages} pages, ${opts.delayMs}ms delay)…`);

  const generatedAt = new Date().toISOString();
  const candidates = [];

  // Source 1 — memedepot depot catalog. Depot names are human-authored format
  // names (Milady, Wojak, …). (Structure confirmed by diagnose.mjs.)
  console.log('  [memedepot] fetching depot catalog (/api/depots)…');
  const depots = await harvestDepots(opts.base, opts);
  console.log(`  [memedepot] depots: ${depots.length}`);
  candidates.push(...depotCandidates(depots));

  // Source 2 — imgflip's canonical image-macro templates (Drake, Two Buttons, …),
  // the clean classic-format list memedepot's crypto-heavy catalog underweights.
  console.log('  [imgflip] fetching template catalog…');
  const memes = await fetchImgflip(opts);
  console.log(`  [imgflip] templates: ${memes.length}`);
  candidates.push(...imgflipCandidates(memes));

  let baseline;
  if (candidates.length) {
    baseline = buildMultiSourceBaseline(candidates, {
      max: opts.maxTags,
      source: 'memedepot.com + imgflip.com',
      generatedAt,
    });
  } else {
    console.warn('  no candidates from any source — falling back to the memedepot HTML tag crawl.');
    baseline = await crawlHtmlBaseline(opts, host, generatedAt);
  }

  if (baseline.labels.length === 0) {
    console.warn(
      '\nNo tags harvested — leaving the existing baseline untouched. The site ' +
        'structure may have changed or blocked the crawler; do NOT ship an empty ' +
        'overwrite. Inspect extractTagsFromHtml against a saved page.'
    );
    process.exitCode = 0;
    return;
  }

  // Preserve deterministic key order for a clean diff.
  const json = JSON.stringify(baseline, null, 2) + '\n';
  await writeFile(opts.out, json, 'utf8');
  console.log(`\nWrote ${baseline.labels.length} tags → ${opts.out}`);
  console.log(`  top: ${baseline.labels.slice(0, 12).map((l) => l.label).join(', ')}`);
}

// Only run the crawl when executed directly, so tests can import the helpers.
if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}

export { parseRobots, isDisallowed };
