// LIVE smoke tests for the shared-link resolver. Unlike linkResolver.test.ts
// (which mocks the network and is the deterministic, merge-gating suite), this
// suite hits the REAL endpoints — X's tweet-syndication API, Tenor, a generic
// Open-Graph page — and then does a real ranged GET on the resolved media URL
// to confirm it's actually downloadable. It's how we find out *beforehand* that
// a platform changed its shape or started blocking us.
//
// Why it's opt-in (RUN_SMOKE=1) and NOT part of `npm test`:
//   • It depends on third-party uptime + that specific fixtures still exist.
//   • X's syndication endpoint often blocks datacenter IPs (incl. CI runners),
//     so a failure here can mean "X blocked the runner", not "our code broke".
// Gating merges on any of that would make CI flap for reasons unrelated to a
// PR. So this runs on a schedule / on demand (see .github/workflows/smoke.yml),
// surfacing breakage without blocking work.
//
// Run locally:  npm run test:smoke
// Update fixtures below if a post gets deleted (tests will tell you which).

// The resolver's final step writes the file with expo-file-system (a native
// module). Replace ONLY that step with a real, cheap reachability probe: a
// 2-byte ranged GET of the resolved media URL. Everything before it — the
// fetch + parse that actually does the extraction — runs for real.
jest.mock('expo-file-system/legacy', () => ({
  cacheDirectory: 'file:///cache/',
  deleteAsync: jest.fn(async () => {}),
  downloadAsync: jest.fn(async (url: string, dest: string) => {
    const r = await fetch(url, {
      headers: {
        'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36',
        Range: 'bytes=0-1',
      },
    });
    return {
      status: r.status,
      uri: dest,
      headers: { 'Content-Type': r.headers.get('content-type') ?? '' },
    };
  }),
}));

import { resolveSharedLink } from './linkResolver';

// Only run when explicitly asked — keeps the network out of the default suite.
const live = process.env.RUN_SMOKE ? describe : describe.skip;

interface Fixture {
  name: string;
  url: string;
  expect: 'image' | 'video' | 'either';
}

// Stable-ish public fixtures. If one rots, the failing test names it so you can
// swap the URL.
const FIXTURES: Fixture[] = [
  {
    name: 'X / Twitter video tweet',
    // NASA — long-lived public account; swap the status id if it ever 404s.
    url: 'https://twitter.com/NASA/status/1410624005669343233',
    expect: 'video',
  },
  {
    name: 'Tenor GIF page (og scrape)',
    url: 'https://tenor.com/view/cat-cute-gif-12281000',
    expect: 'either',
  },
  {
    name: 'Generic Open Graph page',
    // A GitHub repo page reliably exposes an og:image.
    url: 'https://github.com/expo/expo',
    expect: 'image',
  },
  {
    name: 'Direct media URL',
    // Google's public sample bucket — a very stable direct .mp4.
    url: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4',
    expect: 'video',
  },
];

live('live link extraction', () => {
  jest.setTimeout(30000);

  for (const f of FIXTURES) {
    it(`resolves & downloads: ${f.name}`, async () => {
      const media = await resolveSharedLink(f.url);

      // We got a real media URL and a plausible filename.
      expect(media.fileName).toMatch(/\.[a-z0-9]+$/i);

      // The resolved media URL was actually reachable (the mocked downloader did
      // a real ranged GET) and the server agrees on the media type.
      const top = media.mimeType.split('/')[0];
      if (f.expect === 'either') {
        expect(['image', 'video']).toContain(top);
      } else {
        expect(top).toBe(f.expect);
      }
    });
  }
});
