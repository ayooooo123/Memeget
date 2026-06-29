// LIVE smoke tests for the shared-link resolver. Unlike linkResolver.test.ts
// (which mocks the network and is the deterministic, merge-gating suite), this
// suite hits the REAL endpoints — X's tweet-syndication API, Tenor, a generic
// Open-Graph page — so we find out *beforehand* when a platform changes shape.
//
// What it asserts (and what it deliberately doesn't):
//   • HARD: real fetch + parse resolves the RIGHT media URL (correct host) and
//     media kind. This is what breaks when X/Tenor change their response shape —
//     the thing worth failing CI over.
//   • SOFT: whether that media URL is actually reachable from here. CI/datacenter
//     IPs get rate-limited (429) or blocked (403) by media CDNs and by X itself,
//     so a download hiccup is logged as a warning, NOT a failure — otherwise the
//     job would flap for reasons unrelated to our code. (On a real phone, on a
//     residential IP, the download just works.)
//
// Why it's opt-in (RUN_SMOKE=1) and NOT part of `npm test`: it depends on
// third-party uptime + fixtures still existing. It runs on a schedule / on
// demand (see .github/workflows/smoke.yml), surfacing breakage without gating.
//
// Run locally:  npm run test:smoke
// If a fixture post is deleted, the failing test names it so you can swap it.

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';

// The resolver's final step writes the file with expo-file-system (a native
// module). Replace ONLY that step: capture the media URL the resolver chose, do
// a best-effort reachability HEAD (logged, never fatal), and always report
// success so resolution can be asserted independent of CDN/IP blocking.
let lastDownloadUrl = '';
let lastReachable: boolean | null = null;

jest.mock('expo-file-system/legacy', () => ({
  cacheDirectory: 'file:///cache/',
  deleteAsync: jest.fn(async () => {}),
  downloadAsync: jest.fn(async (url: string, dest: string) => {
    lastDownloadUrl = url;
    lastReachable = null;
    let contentType = '';
    try {
      const r = await fetch(url, { method: 'HEAD', headers: { 'User-Agent': UA } });
      lastReachable = r.ok;
      if (r.ok) contentType = r.headers.get('content-type') ?? '';
    } catch {
      lastReachable = false;
    }
    return { status: 200, uri: dest, headers: { 'Content-Type': contentType } };
  }),
}));

import { resolveSharedLink } from './linkResolver';

// Only run when explicitly asked — keeps the network out of the default suite.
const live = process.env.RUN_SMOKE ? describe : describe.skip;

interface Fixture {
  name: string;
  url: string;
  expect: 'image' | 'video' | 'either';
  // The resolved media URL must come from this host — proves the parse picked
  // real media, not some unrelated link on the page.
  host: RegExp;
  // For X: a deleted fixture tweet (404) is a fixture problem, not a resolver
  // regression, so warn-and-skip rather than fail. A *shape* change still fails
  // (resolution would throw "no media" / wrong host).
  skipIfGone?: boolean;
}

const FIXTURES: Fixture[] = [
  {
    name: 'X / Twitter video tweet',
    // Long-lived public tweet with video. If it 404s, the test warns and skips;
    // swap in any current tweet-with-video URL to restore hard coverage.
    url: 'https://twitter.com/SpaceX/status/1732824684683784516',
    expect: 'video',
    host: /(?:video|pbs)\.twimg\.com/i,
    skipIfGone: true,
  },
  {
    name: 'Tenor GIF page (og scrape)',
    url: 'https://tenor.com/view/cat-cute-gif-12281000',
    expect: 'either',
    host: /(?:media\d*|c)\.tenor\.com/i,
  },
  {
    name: 'Generic Open Graph page',
    // Wikipedia article — stable, and its og:image is a real file URL with an
    // extension (so media kind is unambiguous even if the CDN blocks the probe).
    url: 'https://en.wikipedia.org/wiki/Cat',
    expect: 'image',
    host: /upload\.wikimedia\.org/i,
  },
  {
    name: 'Direct media URL',
    url: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4',
    expect: 'video',
    host: /commondatastorage\.googleapis\.com/i,
  },
];

live('live link extraction', () => {
  jest.setTimeout(30000);

  for (const f of FIXTURES) {
    it(`resolves: ${f.name}`, async () => {
      let media;
      try {
        media = await resolveSharedLink(f.url);
      } catch (e) {
        const msg = String((e as Error)?.message ?? e);
        // A vanished fixture (X tweet deleted) isn't a resolver bug — warn, skip.
        if (f.skipIfGone && /HTTP 404/.test(msg)) {
          console.warn(`[smoke] ${f.name}: fixture gone (${msg}). Swap the URL to restore coverage.`);
          return;
        }
        throw e;
      }

      // HARD: the resolver picked a real media URL from the expected host…
      expect(lastDownloadUrl).toMatch(f.host);
      // …with a sane filename and the right media kind.
      expect(media.fileName).toMatch(/\.[a-z0-9]+$/i);
      const top = media.mimeType.split('/')[0];
      if (f.expect === 'either') expect(['image', 'video']).toContain(top);
      else expect(top).toBe(f.expect);

      // SOFT: note reachability without failing on CDN/IP blocking.
      if (lastReachable === false) {
        console.warn(
          `[smoke] ${f.name}: resolved ${lastDownloadUrl} but the media URL wasn't reachable from this runner (CDN/IP block). Resolution still verified.`
        );
      }
    });
  }
});
