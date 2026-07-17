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

// Normalize a raw term to a comparable key: lowercase, strip surrounding
// punctuation, collapse whitespace. Returns '' for anything too short or a
// stopword, so callers can drop it.
export function normalizeTerm(raw) {
  if (typeof raw !== 'string') return '';
  const t = raw
    .toLowerCase()
    .replace(/[_/]+/g, ' ')
    .replace(/[^a-z0-9 +-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (t.length < 3 || t.length > 40) return '';
  if (STOPWORDS.has(t)) return '';
  if (/^\d+$/.test(t)) return ''; // pure numbers
  return t;
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
        else if (Array.isArray(k)) terms.push(...k.map(String));
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
      if (Array.isArray(parsed)) terms.push(...parsed.map(String));
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

// Turn a frequency map into the baseline file shape the app consumes. Ranks by
// count, drops singletons (noise), Title-Cases the display label, dedupes, and
// caps. `generatedAt` is injected (not read from the clock) so this stays pure
// and testable.
export function buildBaseline(freq, { max = DEFAULTS.maxTags, source = 'memedepot.com', generatedAt = null } = {}) {
  const labels = Object.entries(freq)
    .filter(([, count]) => count >= 2) // seen on >1 page → likelier a real tag
    .sort((a, b) => b[1] - a[1])
    .slice(0, max)
    .map(([term, count]) => ({
      label: titleCase(term),
      prompt: templatePrompt(term),
      category: guessCategory(term),
      count,
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

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const host = new URL(opts.base).host;
  console.log(`Harvesting ${host} (max ${opts.maxPages} pages, ${opts.delayMs}ms delay)…`);

  const robotsTxt = await fetchText(new URL('/robots.txt', opts.base).href, opts.timeoutMs);
  const { sitemaps, disallow } = parseRobots(robotsTxt, opts.base);
  if (disallow.length) console.log(`  robots.txt disallows: ${disallow.join(', ')}`);

  // Discover URLs: sitemaps first (canonical + polite), else homepage anchors.
  const queue = new Set();
  for (const sm of sitemaps.length ? sitemaps : []) {
    const xml = await fetchText(sm, opts.timeoutMs);
    for (const loc of parseSitemap(xml)) {
      // A sitemap index points at more sitemaps; fetch one level down.
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

  const rawTerms = [];
  let done = 0;
  for (const url of urls) {
    const html = await fetchText(url, opts.timeoutMs);
    if (html) rawTerms.push(...extractTagsFromHtml(html));
    if (++done % 25 === 0) console.log(`  …${done}/${urls.length}`);
    await sleep(opts.delayMs);
  }

  const baseline = buildBaseline(aggregate(rawTerms), {
    max: opts.maxTags,
    source: host,
    generatedAt: new Date().toISOString(),
  });

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
