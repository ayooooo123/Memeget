// Minimal streaming ZIP writer (STORE / no compression). Emits the archive as a
// sequence of byte chunks through an injected sink (the caller writes each chunk
// straight to a file handle), holding only ONE entry's bytes at a time. This
// replaces jszip's build-then-generate model for the collection export: jszip
// keeps every added file resident until generation, so a 2000+ item library
// materialized ~all images at once and OOM'd the JS runtime. Here peak memory is
// one entry plus a few hundred bytes of central-directory bookkeeping per file.
//
// STORE is the right method: entries are already-compressed JPEG/poster bytes
// plus one JSON manifest, so deflate would burn CPU for a marginal size win.
// Scope: fewer than 65535 entries and under 4GB total (no ZIP64 / no Zip64 EOCD)
// — orders of magnitude beyond any on-device meme library. All multi-byte fields
// are little-endian per the PKZIP APPNOTE.
//
// The byte helpers (crc32, utf8Encode, base64Decode) are pure and dependency-free
// so this works identically on Hermes (no atob/TextEncoder/Buffer needed) and in
// the Node unit tests.

export type ByteSink = (chunk: Uint8Array) => void;

const CRC_TABLE: Uint32Array = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

// CRC-32 (IEEE 802.3), the checksum every ZIP entry header carries. Exposed as
// an incremental fold (init/update/final) so a streamed entry can checksum its
// chunks without buffering the whole payload; `crc32` is the one-shot wrapper.
const CRC_INIT = 0xffffffff;
function crc32Update(state: number, bytes: Uint8Array): number {
  let c = state;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return c >>> 0;
}
export function crc32(bytes: Uint8Array): number {
  return (crc32Update(CRC_INIT, bytes) ^ 0xffffffff) >>> 0;
}

// Encode a JS string as UTF-8 bytes (handles astral code points via surrogate
// pairs). ZIP filenames and the manifest JSON go through here.
export function utf8Encode(str: string): Uint8Array {
  // Worst case is 3 bytes per UTF-16 code unit (an astral char is 2 units -> 4
  // bytes = 2 bytes/unit, so *3 is a safe upper bound). Write straight into a
  // preallocated typed array and return a view of the used prefix — a `number[]`
  // with `.push` would balloon to hundreds of MB of boxed values for a large
  // manifest.
  const buf = new Uint8Array(str.length * 3);
  let p = 0;
  for (let i = 0; i < str.length; i++) {
    let cp = str.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < str.length) {
      const lo = str.charCodeAt(i + 1);
      if (lo >= 0xdc00 && lo <= 0xdfff) {
        cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
        i++;
      }
    }
    if (cp < 0x80) {
      buf[p++] = cp;
    } else if (cp < 0x800) {
      buf[p++] = 0xc0 | (cp >> 6);
      buf[p++] = 0x80 | (cp & 0x3f);
    } else if (cp < 0x10000) {
      buf[p++] = 0xe0 | (cp >> 12);
      buf[p++] = 0x80 | ((cp >> 6) & 0x3f);
      buf[p++] = 0x80 | (cp & 0x3f);
    } else {
      buf[p++] = 0xf0 | (cp >> 18);
      buf[p++] = 0x80 | ((cp >> 12) & 0x3f);
      buf[p++] = 0x80 | ((cp >> 6) & 0x3f);
      buf[p++] = 0x80 | (cp & 0x3f);
    }
  }
  return buf.subarray(0, p);
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const B64_LOOKUP: Int16Array = (() => {
  const l = new Int16Array(256).fill(-1);
  for (let i = 0; i < B64.length; i++) l[B64.charCodeAt(i)] = i;
  return l;
})();

// Decode a base64 string (standard alphabet, tolerant of padding/whitespace) to
// bytes. The image loader hands us base64; the ZIP body needs raw bytes.
export function base64Decode(b64: string): Uint8Array {
  // Count real (non-padding, non-whitespace) symbols to size the output exactly.
  let symbols = 0;
  for (let i = 0; i < b64.length; i++) {
    if (B64_LOOKUP[b64.charCodeAt(i)] !== -1) symbols++;
  }
  const out = new Uint8Array((symbols * 3) >> 2);
  let acc = 0;
  let bits = 0;
  let o = 0;
  for (let i = 0; i < b64.length; i++) {
    const v = B64_LOOKUP[b64.charCodeAt(i)];
    if (v === -1) continue;
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[o++] = (acc >> bits) & 0xff;
    }
  }
  return out;
}

interface CentralEntry {
  nameBytes: Uint8Array;
  flags: number;
  crc: number;
  size: number;
  offset: number;
}

// A single entry being written incrementally: bytes arrive via `write` and are
// checksummed/counted on the fly, so the payload is never buffered whole. Used
// for the manifest, whose size is unknown until every meme is serialized.
export interface ZipEntryStream {
  write(chunk: Uint8Array): void;
  end(): void;
}

// Streaming ZIP archive. Use `add` for entries whose bytes are already in hand,
// or `addStream` for ones assembled incrementally; then `finish` to write the
// central directory. Only one stream entry may be open at a time, and it must be
// ended before any further `add`/`addStream`/`finish`.
export class ZipWriter {
  private offset = 0;
  private readonly central: CentralEntry[] = [];
  private readonly dosTime: number;
  private readonly dosDate: number;
  private done = false;
  private streaming = false;

  constructor(
    private readonly sink: ByteSink,
    when: Date = new Date()
  ) {
    // DOS date/time: 2-second resolution, year relative to 1980. Clamped so a
    // pre-1980 clock can't produce a negative (invalid) field.
    const y = Math.max(1980, when.getFullYear());
    this.dosTime =
      ((when.getHours() & 0x1f) << 11) |
      ((when.getMinutes() & 0x3f) << 5) |
      ((when.getSeconds() >> 1) & 0x1f);
    this.dosDate = (((y - 1980) & 0x7f) << 9) | (((when.getMonth() + 1) & 0x0f) << 5) | (when.getDate() & 0x1f);
  }

  private emit(chunk: Uint8Array): void {
    this.sink(chunk);
    this.offset += chunk.length;
  }

  private guard(op: string): void {
    if (this.done) throw new Error(`ZipWriter.${op} called after finish`);
    if (this.streaming) throw new Error(`ZipWriter.${op} called while a stream entry is open`);
  }

  private writeLocalHeader(nameBytes: Uint8Array, flags: number, crc: number, size: number): void {
    const header = new DataView(new ArrayBuffer(30));
    header.setUint32(0, 0x04034b50, true); // local file header signature
    header.setUint16(4, 20, true); // version needed
    header.setUint16(6, flags, true);
    header.setUint16(8, 0, true); // method: STORE
    header.setUint16(10, this.dosTime, true);
    header.setUint16(12, this.dosDate, true);
    header.setUint32(14, crc, true);
    header.setUint32(18, size, true); // compressed size (== uncompressed for STORE)
    header.setUint32(22, size, true); // uncompressed size
    header.setUint16(26, nameBytes.length, true);
    header.setUint16(28, 0, true); // extra field length
    this.emit(new Uint8Array(header.buffer));
    this.emit(nameBytes);
  }

  add(name: string, data: Uint8Array): void {
    this.guard('add');
    const nameBytes = utf8Encode(name);
    const crc = crc32(data);
    const localOffset = this.offset;
    this.writeLocalHeader(nameBytes, 0x0800, crc, data.length); // bit 11 = UTF-8
    this.emit(data);
    this.central.push({ nameBytes, flags: 0x0800, crc, size: data.length, offset: localOffset });
  }

  // Begin a streamed entry. The local header advertises a trailing data
  // descriptor (general-purpose bit 3) with zeroed crc/sizes; the real values
  // are written in the descriptor — and the central directory — once `end` runs.
  addStream(name: string): ZipEntryStream {
    this.guard('addStream');
    this.streaming = true;
    const nameBytes = utf8Encode(name);
    const flags = 0x0808; // bit 3 = data descriptor, bit 11 = UTF-8
    const localOffset = this.offset;
    this.writeLocalHeader(nameBytes, flags, 0, 0);
    let crc = CRC_INIT;
    let size = 0;
    return {
      write: (chunk) => {
        crc = crc32Update(crc, chunk);
        size += chunk.length;
        this.emit(chunk);
      },
      end: () => {
        const finalCrc = (crc ^ 0xffffffff) >>> 0;
        const desc = new DataView(new ArrayBuffer(16));
        desc.setUint32(0, 0x08074b50, true); // data descriptor signature
        desc.setUint32(4, finalCrc, true);
        desc.setUint32(8, size, true); // compressed size
        desc.setUint32(12, size, true); // uncompressed size
        this.emit(new Uint8Array(desc.buffer));
        this.central.push({ nameBytes, flags, crc: finalCrc, size, offset: localOffset });
        this.streaming = false;
      },
    };
  }

  finish(): void {
    if (this.done) return;
    if (this.streaming) throw new Error('ZipWriter.finish called while a stream entry is open');
    this.done = true;
    const cdStart = this.offset;
    for (const e of this.central) {
      const h = new DataView(new ArrayBuffer(46));
      h.setUint32(0, 0x02014b50, true); // central directory header signature
      h.setUint16(4, 20, true); // version made by
      h.setUint16(6, 20, true); // version needed
      h.setUint16(8, e.flags, true);
      h.setUint16(10, 0, true); // method: STORE
      h.setUint16(12, this.dosTime, true);
      h.setUint16(14, this.dosDate, true);
      h.setUint32(16, e.crc, true);
      h.setUint32(20, e.size, true); // compressed size
      h.setUint32(24, e.size, true); // uncompressed size
      h.setUint16(28, e.nameBytes.length, true);
      h.setUint16(30, 0, true); // extra length
      h.setUint16(32, 0, true); // comment length
      h.setUint16(34, 0, true); // disk number start
      h.setUint16(36, 0, true); // internal attrs
      h.setUint32(38, 0, true); // external attrs
      h.setUint32(42, e.offset, true); // local header offset
      this.emit(new Uint8Array(h.buffer));
      this.emit(e.nameBytes);
    }
    const cdSize = this.offset - cdStart;

    const eocd = new DataView(new ArrayBuffer(22));
    eocd.setUint32(0, 0x06054b50, true); // EOCD signature
    eocd.setUint16(4, 0, true); // disk number
    eocd.setUint16(6, 0, true); // disk with central directory
    eocd.setUint16(8, this.central.length, true); // records on this disk
    eocd.setUint16(10, this.central.length, true); // total records
    eocd.setUint32(12, cdSize, true);
    eocd.setUint32(16, cdStart, true);
    eocd.setUint16(20, 0, true); // comment length
    this.emit(new Uint8Array(eocd.buffer));
  }
}
