// Tests for the shared-link → media resolver. These guard the per-platform
// download paths (X/Twitter, Tenor, generic Open Graph, direct media URLs) so a
// future change can't quietly break "share a link → save the meme".
//
// Strategy: mock the two things that touch the outside world —
//   • global.fetch       (tweet-syndication JSON + Open Graph HTML)
//   • expo-file-system   (the actual file download)
// — then assert resolveSharedLink picks the right media URL and returns a sane
// file descriptor for the existing save pipeline.

jest.mock('expo-file-system/legacy', () => ({
  cacheDirectory: 'file:///cache/',
  downloadAsync: jest.fn(),
  deleteAsync: jest.fn(async () => {}),
}));

import * as FileSystem from 'expo-file-system/legacy';
import { extractUrl, resolveSharedLink } from './linkResolver';

const downloadAsync = FileSystem.downloadAsync as jest.Mock;

// Capture the URL handed to the downloader; echo the dest back like the real one.
function mockDownload(headers: Record<string, string> = { 'Content-Type': 'video/mp4' }, status = 200) {
  downloadAsync.mockImplementation(async (_url: string, dest: string) => ({
    status,
    uri: dest,
    headers,
  }));
}
const downloadedUrl = () => downloadAsync.mock.calls[0][0] as string;

function mockFetch(handler: (url: string) => { ok?: boolean; status?: number; json?: any; text?: string }) {
  (global as any).fetch = jest.fn(async (url: string) => {
    const r = handler(url);
    return {
      ok: r.ok ?? true,
      status: r.status ?? 200,
      json: async () => r.json,
      text: async () => r.text ?? '',
    };
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  (global as any).fetch = jest.fn(async () => {
    throw new Error('unexpected fetch');
  });
  mockDownload();
});

describe('extractUrl', () => {
  it('returns a bare URL', () => {
    expect(extractUrl('https://x.com/a/status/1')).toBe('https://x.com/a/status/1');
  });
  it('pulls the first URL out of surrounding caption text', () => {
    expect(extractUrl('lol look at this https://tenor.com/view/foo-123 😂')).toBe(
      'https://tenor.com/view/foo-123'
    );
  });
  it('trims trailing punctuation', () => {
    expect(extractUrl('see (https://example.com/a.mp4).')).toBe('https://example.com/a.mp4');
  });
  it('checks multiple candidates and ignores non-URL text', () => {
    expect(extractUrl(null, 'just words', 'https://x.com/p')).toBe('https://x.com/p');
  });
  it('returns null when there is no URL', () => {
    expect(extractUrl('no link here', null, undefined)).toBeNull();
  });
});

describe('X / Twitter', () => {
  const VIDEO_TWEET = {
    mediaDetails: [
      {
        type: 'video',
        media_url_https: 'https://pbs.twimg.com/thumb.jpg',
        video_info: {
          variants: [
            { bitrate: 256000, content_type: 'video/mp4', url: 'https://video.twimg.com/low.mp4' },
            { bitrate: 2176000, content_type: 'video/mp4', url: 'https://video.twimg.com/high.mp4' },
            { content_type: 'application/x-mpegURL', url: 'https://video.twimg.com/playlist.m3u8' },
          ],
        },
      },
    ],
  };

  it('hits the syndication endpoint with the tweet id + a token', async () => {
    mockFetch(() => ({ json: VIDEO_TWEET }));
    await resolveSharedLink('https://x.com/someone/status/1790000000000000001');
    const apiUrl = (global.fetch as jest.Mock).mock.calls[0][0] as string;
    expect(apiUrl).toContain('cdn.syndication.twimg.com/tweet-result');
    expect(apiUrl).toContain('id=1790000000000000001');
    expect(apiUrl).toMatch(/token=[^&]+/);
  });

  it('downloads the highest-bitrate mp4 variant', async () => {
    mockFetch(() => ({ json: VIDEO_TWEET }));
    const res = await resolveSharedLink('https://x.com/someone/status/1790000000000000001');
    expect(downloadedUrl()).toBe('https://video.twimg.com/high.mp4');
    expect(res.mimeType).toBe('video/mp4');
    expect(res.fileName).toBe('tweet_1790000000000000001.mp4');
  });

  it('works for twitter.com host too', async () => {
    mockFetch(() => ({ json: VIDEO_TWEET }));
    await resolveSharedLink('https://twitter.com/someone/status/123');
    expect(downloadedUrl()).toBe('https://video.twimg.com/high.mp4');
  });

  it('falls back to a full-res photo when the tweet has no video', async () => {
    mockFetch(() => ({
      json: { mediaDetails: [{ type: 'photo', media_url_https: 'https://pbs.twimg.com/media/pic.jpg' }] },
    }));
    mockDownload({ 'Content-Type': 'image/jpeg' });
    const res = await resolveSharedLink('https://x.com/u/status/555');
    expect(downloadedUrl()).toBe('https://pbs.twimg.com/media/pic.jpg?name=large');
    expect(res.mimeType).toBe('image/jpeg');
    expect(res.fileName).toBe('tweet_555.jpg');
  });

  it('errors when a tweet has no saveable media', async () => {
    mockFetch(() => ({ json: { mediaDetails: [] } }));
    await expect(resolveSharedLink('https://x.com/u/status/9')).rejects.toThrow(/no image or video/i);
  });
});

describe('Tenor (Open Graph)', () => {
  it('prefers og:video and downloads it', async () => {
    mockFetch(() => ({
      text: `<html><head>
        <meta property="og:image" content="https://media.tenor.com/abc/tenor.gif">
        <meta property="og:video" content="https://media.tenor.com/abc/tenor.mp4">
      </head></html>`,
    }));
    const res = await resolveSharedLink('https://tenor.com/view/funny-cat-gif-12345');
    expect(downloadedUrl()).toBe('https://media.tenor.com/abc/tenor.mp4');
    expect(res.mimeType).toBe('video/mp4'); // from the mocked download Content-Type
  });

  it('falls back to og:image when there is no video', async () => {
    mockFetch(() => ({
      text: `<meta property="og:image" content="https://media.tenor.com/xyz/tenor.gif">`,
    }));
    mockDownload({ 'Content-Type': 'image/gif' });
    const res = await resolveSharedLink('https://tenor.com/view/no-video-gif-1');
    expect(downloadedUrl()).toBe('https://media.tenor.com/xyz/tenor.gif');
    expect(res.mimeType).toBe('image/gif');
    expect(res.fileName).toMatch(/\.gif$/);
  });
});

describe('generic Open Graph', () => {
  it('handles content-before-property attribute order and decodes entities', async () => {
    mockFetch(() => ({
      text: `<meta content="https://cdn.site.com/v.mp4?a=1&amp;b=2" property="og:video:secure_url" />`,
    }));
    await resolveSharedLink('https://some.blog/post/42');
    expect(downloadedUrl()).toBe('https://cdn.site.com/v.mp4?a=1&b=2');
  });

  it('uses twitter:image as a last resort', async () => {
    mockFetch(() => ({ text: `<meta name="twitter:image" content="https://img.site.com/p.png">` }));
    mockDownload({ 'Content-Type': 'image/png' });
    const res = await resolveSharedLink('https://some.site/x');
    expect(downloadedUrl()).toBe('https://img.site.com/p.png');
    expect(res.fileName).toMatch(/\.png$/);
  });

  it('absolutizes a protocol-relative media URL', async () => {
    mockFetch(() => ({ text: `<meta property="og:image" content="//cdn.site.com/p.jpg">` }));
    mockDownload({ 'Content-Type': 'image/jpeg' });
    await resolveSharedLink('https://some.site/page');
    expect(downloadedUrl()).toBe('https://cdn.site.com/p.jpg');
  });

  it('errors when the page advertises no media', async () => {
    mockFetch(() => ({ text: `<html><head><title>nothing</title></head></html>` }));
    await expect(resolveSharedLink('https://some.site/empty')).rejects.toThrow(/no image or video/i);
  });
});

describe('memedepot', () => {
  it('resolves via Open Graph and names the file from the URL slug', async () => {
    mockFetch(() => ({
      text: `<meta property="og:video" content="https://cdn.memedepot.com/abc/clip.mp4">`,
    }));
    const res = await resolveSharedLink('https://memedepot.com/d/funny/media/gigachad-clip');
    expect(downloadedUrl()).toBe('https://cdn.memedepot.com/abc/clip.mp4');
    expect(res.fileName).toBe('memedepot_gigachad-clip.mp4');
  });

  it('falls back to an inline <video> when the page has no Open Graph media', async () => {
    mockFetch(() => ({
      text: `<html><body><video controls src="https://cdn.memedepot.com/x/v.mp4"></video></body></html>`,
    }));
    const res = await resolveSharedLink('https://memedepot.com/d/clips/media/42');
    expect(downloadedUrl()).toBe('https://cdn.memedepot.com/x/v.mp4');
    expect(res.fileName).toBe('memedepot_42.mp4');
  });

  it('falls back to a direct media URL embedded in the page JSON', async () => {
    mockFetch(() => ({
      text: `<script>window.__DATA__={"media":{"url":"https://cdn.memedepot.com/y/pic.jpg?v=2"}}</script>`,
    }));
    mockDownload({ 'Content-Type': 'image/jpeg' });
    const res = await resolveSharedLink('https://memedepot.com/d/pics/media/hello');
    expect(downloadedUrl()).toBe('https://cdn.memedepot.com/y/pic.jpg?v=2');
    expect(res.fileName).toBe('memedepot_hello.jpg');
  });

  it('errors when a memedepot page exposes no media at all', async () => {
    mockFetch(() => ({ text: `<html><head><title>Memedepot</title></head></html>` }));
    await expect(resolveSharedLink('https://memedepot.com/d/empty')).rejects.toThrow(/no image or video/i);
  });
});

describe('embedded-media fallback (generic)', () => {
  it('uses a <source> element when Open Graph tags are absent', async () => {
    mockFetch(() => ({
      text: `<video><source src="//cdn.site.com/a.webm" type="video/webm"></video>`,
    }));
    mockDownload({ 'Content-Type': 'video/webm' });
    await resolveSharedLink('https://some.gallery/view/1');
    expect(downloadedUrl()).toBe('https://cdn.site.com/a.webm');
  });

  it('prefers Open Graph over an incidental embedded URL', async () => {
    mockFetch(() => ({
      text: `<meta property="og:image" content="https://cdn.site.com/real.png">
             <img src="https://cdn.site.com/logo.png">`,
    }));
    mockDownload({ 'Content-Type': 'image/png' });
    await resolveSharedLink('https://some.gallery/view/2');
    expect(downloadedUrl()).toBe('https://cdn.site.com/real.png');
  });
});

describe('direct media URLs', () => {
  it('downloads a direct .mp4 without any page fetch', async () => {
    const res = await resolveSharedLink('https://cdn.example.com/clip.mp4');
    expect(global.fetch).not.toHaveBeenCalled();
    expect(downloadedUrl()).toBe('https://cdn.example.com/clip.mp4');
    expect(res.fileName).toMatch(/\.mp4$/);
  });

  it('downloads a direct image with a query string', async () => {
    mockDownload({ 'Content-Type': 'image/jpeg' });
    const res = await resolveSharedLink('https://cdn.example.com/pic.jpg?size=large');
    expect(global.fetch).not.toHaveBeenCalled();
    expect(res.mimeType).toBe('image/jpeg');
  });
});

describe('download behaviour', () => {
  it("trusts the server's content-type over the URL extension", async () => {
    // URL has no extension; server says it's an mp4.
    mockFetch(() => ({ text: `<meta property="og:video" content="https://cdn.site.com/stream?id=9">` }));
    mockDownload({ 'Content-Type': 'video/mp4; charset=binary' });
    const res = await resolveSharedLink('https://some.site/post');
    expect(res.mimeType).toBe('video/mp4');
    expect(res.fileName).toMatch(/\.mp4$/);
  });

  it('throws on a non-2xx download', async () => {
    mockDownload({}, 404);
    await expect(resolveSharedLink('https://cdn.example.com/missing.mp4')).rejects.toThrow(/download failed/i);
    expect(FileSystem.deleteAsync).toHaveBeenCalled(); // cleans up the partial file
  });

  it('surfaces an HTTP error from the page fetch', async () => {
    mockFetch(() => ({ ok: false, status: 403, text: '' }));
    await expect(resolveSharedLink('https://some.site/blocked')).rejects.toThrow(/HTTP 403/);
  });
});
