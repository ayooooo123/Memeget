// A content fingerprint for a shared meme's bytes, used to skip saving a file
// whose exact bytes are already in the library. Every save writes a brand-new
// file with a brand-new URI, so URI-uniqueness alone can't catch a re-shared
// meme — or one the OS redelivers (some Android launchers hand the same share
// intent to a cold-started process twice). Both land as visible duplicates in
// the grid; matching on content is what actually stops them.
//
// The input is the file's base64 — exactly what the save path already reads into
// memory — so hashing adds no extra I/O. We combine the exact byte length with
// an FNV-1a over the content: length alone separates almost all distinct files,
// and the hash guards the rare same-length case. For very large payloads (a
// multi-minute video is tens of MB of base64) we stride-sample so the share
// path stays responsive — identical files still sample identically, and the
// exact-length prefix keeps an accidental merge of two *distinct* files
// vanishingly unlikely.
export function hashBase64(base64: string): string {
  const len = base64.length;
  // Cap the scan at ~1M character reads: a full pass over a long video's base64
  // would block the JS thread for a noticeable beat mid-share.
  const step = len > 1_000_000 ? Math.ceil(len / 1_000_000) : 1;
  let h = 0x811c9dc5; // FNV-1a offset basis
  for (let i = 0; i < len; i += step) {
    h ^= base64.charCodeAt(i);
    h = Math.imul(h, 0x01000193); // FNV-1a prime
  }
  // length.hash, both base-36 for a compact key.
  return `${len.toString(36)}.${(h >>> 0).toString(36)}`;
}
