// Resolve a shared link (X/Twitter, Tenor, or any social post) into an actual
// downloadable media file, then fetch it into the cache so the normal share
// pipeline (saveSharedFiles → indexSavedFiles) can treat it like any other
// shared image/video.
//
// PRIVACY NOTE: unlike the rest of the app, this path *does* hit the network —
// but only when you explicitly share a link, and only to the host that link
// points at (plus, for tweets, X's public syndication endpoint). Nothing is
// uploaded; we only download the media you asked for.
import * as FileSystem from 'expo-file-system/legacy';

export interface ResolvedMedia {
  path: string; // file:// path in the cache
  fileName: string;
  mimeType: string;
}

// A media URL we've discovered but not yet downloaded.
interface MediaRef {
  url: string;
  mimeType?: string;
  fileName?: string;
}

export interface ResolveProgress {
  stage: 'resolving' | 'downloading';
}

// A desktop-browser UA — some hosts (and X's syndication endpoint) serve empty
// or app-gated responses to non-browser clients.
const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';

const URL_RE = /(https?:\/\/[^\s"'<>]+)/i;
const MEDIA_EXT_RE = /\.(mp4|m4v|mov|webm|gif|jpe?g|png|webp|bmp)(?:[?#]|$)/i;

// Pull the first http(s) URL out of whatever text was shared (the share sheet
// often hands over "caption text https://…" rather than a bare URL).
export function extractUrl(...candidates: (string | null | undefined)[]): string | null {
  for (const c of candidates) {
    if (!c) continue;
    const m = c.match(URL_RE);
    if (m) return m[1].replace(/[)\].,]+$/, ''); // trim trailing punctuation
  }
  return null;
}

// ---- generic helpers --------------------------------------------------------

function decodeEntities(s: string): string {
  return s
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#0*39;/g, "'")
    .replace(/&#x2F;/gi, '/')
    .replace(/&#0*47;/g, '/');
}

function absolutize(url: string, base: string): string {
  if (/^https?:\/\//i.test(url)) return url;
  if (url.startsWith('//')) return `https:${url}`;
  try {
    const u = new URL(url, base);
    return u.toString();
  } catch {
    return url;
  }
}

const MIME_EXT: Record<string, string> = {
  'video/mp4': 'mp4',
  'video/quicktime': 'mov',
  'video/webm': 'webm',
  'image/gif': 'gif',
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
};

function extFromMime(mime?: string): string | null {
  if (!mime) return null;
  return MIME_EXT[mime.split(';')[0].trim().toLowerCase()] ?? null;
}

function extFromUrl(url: string): string | null {
  const m = url.match(MEDIA_EXT_RE);
  return m ? m[1].toLowerCase().replace('jpeg', 'jpg') : null;
}

function mimeFromExt(ext: string): string {
  for (const [mime, e] of Object.entries(MIME_EXT)) if (e === ext) return mime;
  return ext === 'jpg' ? 'image/jpeg' : 'application/octet-stream';
}

async function fetchText(url: string): Promise<string> {
  const r = await fetch(url, { headers: { 'User-Agent': BROWSER_UA, Accept: 'text/html,*/*' } });
  if (!r.ok) throw new Error(`Couldn't open that link (HTTP ${r.status}).`);
  return await r.text();
}

// Read the content of the first matching <meta property|name="…"> tag, tolerant
// of attribute order (content before or after the property attribute).
function metaContent(html: string, ...names: string[]): string | null {
  for (const name of names) {
    const esc = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const after = new RegExp(
      `<meta[^>]+(?:property|name)=["']${esc}["'][^>]+content=["']([^"']+)["']`,
      'i'
    );
    const before = new RegExp(
      `<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${esc}["']`,
      'i'
    );
    const m = html.match(after) ?? html.match(before);
    if (m) return decodeEntities(m[1]);
  }
  return null;
}

// ---- platform resolvers -----------------------------------------------------

const isTwitter = (u: string) => /https?:\/\/(?:[\w-]+\.)*(?:twitter|x)\.com\//i.test(u);
const isTenor = (u: string) => /https?:\/\/(?:[\w-]+\.)*tenor\.com\//i.test(u);

function tweetId(url: string): string | null {
  const m = url.match(/(?:twitter|x)\.com\/[^/]+\/status(?:es)?\/(\d+)/i);
  return m ? m[1] : null;
}

// X's public tweet-syndication endpoint requires a token derived from the id
// (same scheme the react-tweet library uses). Works for public tweets, no auth.
function syndicationToken(id: string): string {
  return ((Number(id) / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, '');
}

async function resolveTwitter(url: string): Promise<MediaRef> {
  const id = tweetId(url);
  if (!id) return resolveGeneric(url); // profile/other link — best-effort OG
  const api = `https://cdn.syndication.twimg.com/tweet-result?id=${id}&token=${syndicationToken(
    id
  )}&lang=en`;
  const r = await fetch(api, { headers: { 'User-Agent': BROWSER_UA, Accept: 'application/json' } });
  if (!r.ok) throw new Error(`Couldn't read that tweet (HTTP ${r.status}).`);
  const data: any = await r.json();
  const media: any[] = data?.mediaDetails ?? [];

  // Prefer the highest-bitrate mp4 (covers both videos and animated GIFs, which
  // X serves as silent mp4).
  for (const m of media) {
    const variants: any[] = m?.video_info?.variants ?? [];
    const best = variants
      .filter((v) => v?.content_type === 'video/mp4' && v?.url)
      .sort((a, b) => (b.bitrate ?? 0) - (a.bitrate ?? 0))[0];
    if (best) return { url: best.url, mimeType: 'video/mp4', fileName: `tweet_${id}` };
  }
  // No video — grab a still photo at full resolution.
  const photo = media.find((m) => m?.media_url_https);
  if (photo) {
    return { url: `${photo.media_url_https}?name=large`, mimeType: 'image/jpeg', fileName: `tweet_${id}` };
  }
  throw new Error('That tweet has no image or video to save.');
}

// Open Graph / Twitter-card scrape — covers Tenor, Reddit, most social posts,
// and any site that advertises its media in meta tags.
async function resolveGeneric(url: string): Promise<MediaRef> {
  const html = await fetchText(url);
  const video = metaContent(
    html,
    'og:video:secure_url',
    'og:video:url',
    'og:video',
    'twitter:player:stream'
  );
  if (video) {
    return {
      url: absolutize(video, url),
      mimeType: metaContent(html, 'og:video:type') ?? undefined,
    };
  }
  const image = metaContent(html, 'og:image:secure_url', 'og:image', 'twitter:image', 'twitter:image:src');
  if (image) return { url: absolutize(image, url), mimeType: metaContent(html, 'og:image:type') ?? undefined };
  throw new Error('No image or video found at that link.');
}

// ---- download ---------------------------------------------------------------

async function download(ref: MediaRef, slug: number): Promise<ResolvedMedia> {
  let mimeType = ref.mimeType ?? (extFromUrl(ref.url) ? mimeFromExt(extFromUrl(ref.url)!) : undefined);
  let ext = extFromMime(mimeType) ?? extFromUrl(ref.url) ?? 'mp4';
  const dest = `${FileSystem.cacheDirectory}link_dl_${slug}.${ext}`;

  const res = await FileSystem.downloadAsync(ref.url, dest, {
    headers: { 'User-Agent': BROWSER_UA },
  });
  if (res.status < 200 || res.status >= 300) {
    await FileSystem.deleteAsync(dest, { idempotent: true }).catch(() => {});
    throw new Error(`Download failed (HTTP ${res.status}).`);
  }

  // Trust the server's content-type if it gave a usable one; refine ext to match.
  const served = (res.headers?.['Content-Type'] ?? res.headers?.['content-type'] ?? '').split(';')[0].trim();
  if (served && (served.startsWith('image/') || served.startsWith('video/'))) {
    mimeType = served;
    ext = extFromMime(served) ?? ext;
  }
  if (!mimeType) mimeType = mimeFromExt(ext);

  const base = (ref.fileName ?? `shared_${slug}`).replace(/[^a-zA-Z0-9._-]/g, '_');
  const fileName = base.toLowerCase().endsWith(`.${ext}`) ? base : `${base}.${ext}`;
  return { path: dest, fileName, mimeType };
}

// Resolve a shared link to a real media file on disk. Throws a user-readable
// error if the link can't be turned into a saveable image/video.
export async function resolveSharedLink(
  rawUrl: string,
  opts: { onProgress?: (p: ResolveProgress) => void } = {}
): Promise<ResolvedMedia> {
  const url = rawUrl.trim();
  opts.onProgress?.({ stage: 'resolving' });

  let ref: MediaRef;
  if (MEDIA_EXT_RE.test(url)) ref = { url }; // already a direct media link
  else if (isTwitter(url)) ref = await resolveTwitter(url);
  else if (isTenor(url)) ref = await resolveGeneric(url);
  else ref = await resolveGeneric(url);

  opts.onProgress?.({ stage: 'downloading' });
  // A stable-ish slug for the temp filename without Date.now()'s collision risk
  // across same-millisecond shares; the URL is unique enough.
  const slug = Math.abs(hashCode(ref.url)) % 1e9;
  return download(ref, slug);
}

function hashCode(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h << 5) - h + s.charCodeAt(i);
  return h | 0;
}
