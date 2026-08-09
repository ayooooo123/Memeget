// A content fingerprint for a shared meme's bytes, used to skip saving a file
// whose exact bytes are already in the library. Every save writes a brand-new
// file with a brand-new URI, so URI-uniqueness alone can't catch a re-shared
// meme — or one the OS redelivers (some Android launchers hand the same share
// intent to a cold-started process twice). Both land as visible duplicates in
// the grid; matching on content is what actually stops them.
//
// The share path streams a shared file straight to disk with a native copy
// rather than reading it into JS — a multi-hundred-MB video turned into a
// base64 string on the JS heap OOM'd/froze the app. So instead of the whole
// payload we get the exact byte length plus a few small base64 `windows` read
// (with positioned reads) from the head, middle, and tail. Length is the
// primary key — two files of different size can NEVER collide — and the FNV-1a
// over the windows guards the rare same-length case while still moving if a
// re-encode changes the start, middle, or end. Not cryptographic: it only has
// to change when the bytes do.
export function hashFileSample(byteLength: number, windows: readonly string[]): string {
  let h = 0x811c9dc5; // FNV-1a offset basis
  for (const w of windows) {
    for (let i = 0; i < w.length; i++) {
      h ^= w.charCodeAt(i);
      h = Math.imul(h, 0x01000193); // FNV-1a prime
    }
  }
  // length.hash, both base-36 for a compact key.
  return `${byteLength.toString(36)}.${(h >>> 0).toString(36)}`;
}

// FNV-1a over a whole string, base36. The same primitive `hashFileSample` uses,
// exposed for the places that fingerprint short text rather than file bytes:
// a label prompt (so an edit invalidates its cached vector) or the set of
// hand-applied tags (so the trained heads notice a rename). Not cryptographic —
// it only has to change when the text does.
export function hashText(s: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36);
}
