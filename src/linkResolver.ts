// Resolve a shared link (X/Twitter, Tenor, memedepot, or any social post) into
// an actual downloadable media file, then fetch it into the cache so the normal share
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
const isMemedepot = (u: string) => /https?:\/\/(?:[\w-]+\.)*memedepot\.com\//i.test(u);
// X's share sheet (and many apps) hand over a t.co wrapper rather than the real
// post URL. Follow the redirect once to recover the x.com/…/status/… link so
// the tweet resolver can run instead of falling through to a generic OG scrape.
const isShortlink = (u: string) => /https?:\/\/t\.co\//i.test(u);

async function unwrapShortlink(url: string): Promise<string> {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': BROWSER_UA }, redirect: 'follow' });
    if (r.url && r.url !== url) return r.url;
  } catch {
    // Fall back to the original URL; the normal resolvers still get a shot.
  }
  return url;
}

function tweetId(url: string): string | null {
  const m = url.match(/(?:twitter|x)\.com\/[^/]+\/status(?:es)?\/(\d+)/i);
  return m ? m[1] : null;
}

// X's public tweet-syndication endpoint requires a token derived from the id
// (same scheme the react-tweet library uses). Works for public tweets, no auth.
function syndicationToken(id: string): string {
  return ((Number(id) / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, '');
}

// The syndication endpoint also gates on a `features` flag set. Without it X
// stopped returning the tweet body (it answers with a TweetTombstone instead),
// which is what silently broke "share an X video → save it". This is the same
// flag list react-tweet sends; the exact values don't matter much, but the
// parameter must be present for the endpoint to hand back `mediaDetails`.
const SYNDICATION_FEATURES = [
  'tfw_timeline_list:',
  'tfw_follower_count_sunset:true',
  'tfw_tweet_edit_backend:on',
  'tfw_refsrc_session:on',
  'tfw_fosnr_soft_interventions_enabled:on',
  'tfw_show_birdwatch_pivots_enabled:on',
  'tfw_show_business_verified_badge:on',
  'tfw_duplicate_scribes_to_settings:on',
  'tfw_use_profile_image_shape_enabled:on',
  'tfw_show_blue_verified_badge:on',
  'tfw_legacy_timeline_sunset:true',
  'tfw_show_gov_verified_badge:on',
  'tfw_show_business_affiliate_badge:on',
  'tfw_tweet_edit_frontend:on',
].join(';');

async function resolveTwitter(url: string): Promise<MediaRef> {
  const id = tweetId(url);
  if (!id) return resolveGeneric(url); // profile/other link — best-effort OG
  const params = new URLSearchParams({
    id,
    token: syndicationToken(id),
    features: SYNDICATION_FEATURES,
    lang: 'en',
  });
  const api = `https://cdn.syndication.twimg.com/tweet-result?${params.toString()}`;
  const r = await fetch(api, { headers: { 'User-Agent': BROWSER_UA, Accept: 'application/json' } });
  if (!r.ok) throw new Error(`Couldn't read that tweet (HTTP ${r.status}).`);
  const data: any = await r.json();
  // A tombstone means X won't serve this tweet to logged-out clients (protected,
  // age-restricted, or removed) — there's no public media to fetch.
  if (data?.__typename === 'TweetTombstone' || data?.tombstone) {
    throw new Error("That tweet can't be opened without signing in to X (it may be age-restricted or protected).");
  }
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

  // Last resort for JS-rendered galleries (e.g. memedepot) that don't emit
  // Open Graph media tags: pull a media URL straight out of the markup.
  const embedded = embeddedMedia(html, url);
  if (embedded) return embedded;
  throw new Error('No image or video found at that link.');
}

// Fallback media discovery when a page advertises no Open Graph/Twitter-card
// media: an inline <video>/<source> element, or a direct media URL sitting in
// the page markup or an embedded JSON blob (how single-page meme galleries
// often ship their asset). Deliberately narrow — a <video>/<source> src or a
// full media-extension URL — so we don't mistake a logo or favicon for content.
function embeddedMedia(html: string, base: string): MediaRef | null {
  const tag = html.match(/<(?:video|source)\b[^>]*\bsrc=["']([^"']+)["']/i);
  if (tag && MEDIA_EXT_RE.test(tag[1])) return { url: absolutize(decodeEntities(tag[1]), base) };

  const direct = html.match(
    /https?:\/\/[^\s"'<>\\]+\.(?:mp4|m4v|webm|gif|jpe?g|png|webp)(?:[?#][^\s"'<>\\]*)?/i
  );
  if (direct) return { url: decodeEntities(direct[0]) };
  return null;
}

// memedepot.com is a meme-hosting/curation site (depots = collections). It's a
// client-rendered app, so resolution leans on its share-preview Open Graph tags,
// with the embedded-media scan above as a backstop. A path-derived filename
// keeps saved memes recognizable in the linked folder.
async function resolveMemedepot(url: string): Promise<MediaRef> {
  const ref = await resolveGeneric(url);
  return { ...ref, fileName: ref.fileName ?? `memedepot_${slugFromUrl(url)}` };
}

function slugFromUrl(url: string): string {
  try {
    const parts = new URL(url).pathname.split('/').filter(Boolean);
    return parts[parts.length - 1] || 'meme';
  } catch {
    return 'meme';
  }
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
  let url = rawUrl.trim();
  opts.onProgress?.({ stage: 'resolving' });

  // Unwrap a t.co (or other) shortener to its destination first, so an X post
  // shared as a t.co link is recognized as a tweet rather than scraped blindly.
  if (isShortlink(url)) url = await unwrapShortlink(url);

  let ref: MediaRef;
  if (MEDIA_EXT_RE.test(url)) ref = { url }; // already a direct media link
  else if (isTwitter(url)) ref = await resolveTwitter(url);
  else if (isTenor(url)) ref = await resolveGeneric(url);
  else if (isMemedepot(url)) ref = await resolveMemedepot(url);
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
