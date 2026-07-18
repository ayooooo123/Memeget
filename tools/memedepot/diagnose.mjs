// memedepot structure diagnostic (dev/CI only). One-shot recon so we can build
// the DEPOT-level extractor without guessing: the current harvester scrapes
// per-post tag arrays, but memedepot's high-signal taxonomy lives in DEPOTS
// (collections named by format — "Milady", "Wojak", …). This dumps enough of the
// site's shape into the CI log to see the depot URL scheme and where depot names
// live (page title / <h1> / __NEXT_DATA__ / an API), then we write the extractor.
//
// Runs in CI because memedepot is egress-blocked from the dev sandbox. Polite:
// a handful of requests, browser UA, short timeout. Prints only structure, never
// bulk content.

const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';
const BASE = process.argv[2] || 'https://memedepot.com';
const TIMEOUT = 20000;

async function get(url) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), TIMEOUT);
  try {
    const r = await fetch(url, {
      headers: { 'User-Agent': BROWSER_UA, Accept: 'text/html,application/json,*/*' },
      signal: ctrl.signal,
    });
    const body = await r.text();
    return { status: r.status, ct: r.headers.get('content-type') || '', body };
  } catch (e) {
    return { status: 0, ct: '', body: '', err: e.message };
  } finally {
    clearTimeout(t);
  }
}

const trunc = (s, n = 400) => (s.length > n ? s.slice(0, n) + `…(+${s.length - n})` : s);

// Group internal links by their first path segment — reveals the URL scheme
// (e.g. a big "/d/" bucket ⇒ depots live at /d/<slug>).
function linkBuckets(html) {
  const buckets = {};
  const samples = {};
  for (const m of html.matchAll(/href=["'](\/[^"'?#\s]*)/g)) {
    const seg = m[1].split('/').filter(Boolean)[0] || '(root)';
    buckets[seg] = (buckets[seg] ?? 0) + 1;
    if (!samples[seg]) samples[seg] = m[1];
  }
  return { buckets, samples };
}

// Pull the __NEXT_DATA__ / __NUXT__ / first big application/json blob and show
// its shape (top-level keys + a search for depot/collection/name fields).
function inspectEmbeddedJson(html, label) {
  const next = html.match(/<script[^>]+id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script>/i);
  const generic = html.match(/<script[^>]+type=["']application\/json["'][^>]*>([\s\S]*?)<\/script>/i);
  const raw = next?.[1] ?? generic?.[1];
  if (!raw) {
    console.log(`  [${label}] no __NEXT_DATA__ / application/json script block`);
    return;
  }
  console.log(`  [${label}] embedded JSON: ${next ? '__NEXT_DATA__' : 'application/json'} (${raw.length} chars)`);
  try {
    const data = JSON.parse(raw.trim());
    const walkKeys = (o, depth = 0, path = '') => {
      if (!o || typeof o !== 'object' || depth > 3) return;
      for (const k of Object.keys(o)) {
        const kp = path ? `${path}.${k}` : k;
        if (/depot|collection|category|tag|name|title|slug/i.test(k)) {
          const v = o[k];
          const preview =
            typeof v === 'string' ? JSON.stringify(v) : Array.isArray(v) ? `[array len ${v.length}]` : typeof v;
          console.log(`      ${kp} = ${trunc(String(preview), 120)}`);
        }
        walkKeys(o[k], depth + 1, kp);
      }
    };
    console.log(`      top-level keys: ${Object.keys(data).join(', ')}`);
    walkKeys(data);
  } catch (e) {
    console.log(`      (JSON parse failed: ${e.message}) raw head: ${trunc(raw.trim(), 300)}`);
  }
}

function metaBits(html) {
  const g = (re) => (html.match(re)?.[1] ?? '').trim();
  console.log(`  title:     ${g(/<title[^>]*>([\s\S]*?)<\/title>/i)}`);
  console.log(`  og:title:  ${g(/<meta[^>]+og:title[^>]+content=["']([^"']+)["']/i)}`);
  console.log(`  h1:        ${trunc(g(/<h1[^>]*>([\s\S]*?)<\/h1>/i).replace(/<[^>]+>/g, ' ').trim(), 120)}`);
  const apis = [...html.matchAll(/["'](\/api\/[^"'?#\s]+)/g)].map((m) => m[1]);
  if (apis.length) console.log(`  /api refs: ${[...new Set(apis)].slice(0, 10).join(', ')}`);
}

async function main() {
  console.log(`=== memedepot structure diagnostic: ${BASE} ===\n`);

  // robots + sitemaps
  const robots = await get(new URL('/robots.txt', BASE).href);
  const sitemaps = [...robots.body.matchAll(/^sitemap:\s*(\S+)/gim)].map((m) => m[1]);
  console.log(`[robots.txt] status ${robots.status}; sitemaps: ${sitemaps.join(', ') || '(none)'}\n`);
  for (const sm of sitemaps.slice(0, 2)) {
    const x = await get(sm);
    const locs = [...x.body.matchAll(/<loc>\s*([^<\s]+)\s*<\/loc>/gi)].map((m) => m[1]);
    const paths = locs.map((u) => {
      try {
        return new URL(u).pathname.split('/').filter(Boolean)[0] || '(root)';
      } catch {
        return '?';
      }
    });
    const buckets = paths.reduce((a, p) => ((a[p] = (a[p] ?? 0) + 1), a), {});
    console.log(`[sitemap ${sm}] ${locs.length} locs; first-segment buckets: ${JSON.stringify(buckets)}`);
    console.log(`  samples: ${locs.slice(0, 5).join(', ')}\n`);
  }

  // homepage
  const home = await get(BASE);
  console.log(`[homepage] status ${home.status}, content-type ${home.ct}`);
  const { buckets, samples } = linkBuckets(home.body);
  console.log(`  link buckets (first path segment → count): ${JSON.stringify(buckets)}`);
  console.log(`  bucket samples: ${JSON.stringify(samples)}`);
  metaBits(home.body);
  inspectEmbeddedJson(home.body, 'homepage');
  console.log('');

  // Probe a few internal pages from the most common non-static buckets, to find
  // the depot page shape. Skip obvious asset/segment buckets.
  const skip = new Set(['(root)', '_next', 'static', 'assets', 'api', 'images', 'img', 'css', 'js']);
  const probeSegs = Object.entries(buckets)
    .filter(([seg]) => !skip.has(seg))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 4)
    .map(([seg]) => samples[seg]);
  for (const path of probeSegs) {
    const url = new URL(path, BASE).href;
    const p = await get(url);
    console.log(`[page ${path}] status ${p.status}, content-type ${p.ct}`);
    metaBits(p.body);
    inspectEmbeddedJson(p.body, path);
    console.log('');
  }

  console.log('=== end diagnostic — look for the depot URL segment + where depot names appear ===');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
