// The ZIP export path is hand-rolled byte layout, so the value worth locking is
// that what ZipWriter emits is a real, readable archive with intact contents —
// verified by loading it back with jszip (the same library used to READ zips
// elsewhere in the app) — plus the byte-primitive helpers it relies on.
import JSZip from 'jszip';

import { ZipWriter, crc32, utf8Encode, utf8Length, base64Decode, base64Encode } from './zipWriter';

function collect(build: (w: ZipWriter) => void): Uint8Array {
  const chunks: Uint8Array[] = [];
  const w = new ZipWriter((c) => chunks.push(c.slice()), new Date('2026-07-21T12:34:56Z'));
  build(w);
  w.finish();
  const total = chunks.reduce((n, c) => n + c.length, 0);
  const out = new Uint8Array(total);
  let o = 0;
  for (const c of chunks) {
    out.set(c, o);
    o += c.length;
  }
  return out;
}

describe('crc32', () => {
  it('matches the canonical "123456789" check value', () => {
    expect(crc32(utf8Encode('123456789'))).toBe(0xcbf43926);
  });

  it('is 0 for empty input', () => {
    expect(crc32(new Uint8Array(0))).toBe(0);
  });
});

describe('utf8Encode', () => {
  it('encodes ASCII, multibyte, and astral code points', () => {
    expect(Array.from(utf8Encode('A'))).toEqual([0x41]);
    expect(Array.from(utf8Encode('é'))).toEqual([0xc3, 0xa9]);
    expect(Array.from(utf8Encode('€'))).toEqual([0xe2, 0x82, 0xac]);
    expect(Array.from(utf8Encode('😀'))).toEqual([0xf0, 0x9f, 0x98, 0x80]);
  });
});

describe('utf8Length', () => {
  // The sidecar pads an overwrite up to the previous file's BYTE length, so a
  // wrong count here leaves part of the stale tail exposed and the JSON invalid.
  it('agrees with utf8Encode on every class of code point', () => {
    for (const s of ['', 'A', 'é', '€', '😀', 'pepe 🐸 wojak', 'ascii only', '中文字']) {
      expect(utf8Length(s)).toBe(utf8Encode(s).length);
    }
  });

  it('counts a lone surrogate the way the encoder writes it', () => {
    const lone = '\ud83d';
    expect(utf8Length(lone)).toBe(utf8Encode(lone).length);
    expect(utf8Length(`a${lone}b`)).toBe(utf8Encode(`a${lone}b`).length);
  });
});

describe('base64Decode', () => {
  it('round-trips arbitrary bytes and tolerates padding/whitespace', () => {
    const bytes = Uint8Array.from({ length: 256 }, (_, i) => i);
    const b64 = Buffer.from(bytes).toString('base64');
    expect(Array.from(base64Decode(b64))).toEqual(Array.from(bytes));
    // embedded newlines (as some encoders wrap) must not corrupt the output
    const wrapped = b64.replace(/(.{20})/g, '$1\n');
    expect(Array.from(base64Decode(wrapped))).toEqual(Array.from(bytes));
  });
});

describe('base64Encode', () => {
  // The sidecar stores every embedding as base64 of its float32 bytes, so a
  // padding or slice-boundary bug here is silent vector corruption in a backup
  // that only gets read back after the original is gone.
  it('matches the platform encoder across every padding remainder', () => {
    for (let len = 0; len <= 8; len++) {
      const bytes = Uint8Array.from({ length: len }, (_, i) => (i * 37 + 11) & 0xff);
      expect(base64Encode(bytes)).toBe(Buffer.from(bytes).toString('base64'));
    }
  });

  it('handles high bytes and payloads spanning many internal slices', () => {
    // 4096 groups per slice: go well past the boundary, and land off it.
    const bytes = Uint8Array.from({ length: 4096 * 3 * 2 + 5 }, (_, i) => (i * 251) & 0xff);
    expect(base64Encode(bytes)).toBe(Buffer.from(bytes).toString('base64'));
    expect(base64Encode(Uint8Array.of(0xff, 0xff, 0xff))).toBe('////');
  });

  it('round-trips through base64Decode', () => {
    const bytes = Uint8Array.from({ length: 257 }, (_, i) => (i * 13) & 0xff);
    expect(Array.from(base64Decode(base64Encode(bytes)))).toEqual(Array.from(bytes));
  });
});

describe('ZipWriter', () => {
  it('produces an archive jszip can read back with exact contents', async () => {
    const img = Uint8Array.from({ length: 500 }, (_, i) => (i * 7) % 256);
    const manifest = utf8Encode(JSON.stringify({ hello: 'wörld 😀', n: 42 }));
    const bytes = collect((w) => {
      w.add('images/1.jpg', img);
      w.add('manifest.json', manifest);
    });

    const zip = await JSZip.loadAsync(bytes);
    expect(Object.keys(zip.files).sort()).toEqual(['images/1.jpg', 'manifest.json']);

    const back = await zip.file('images/1.jpg')!.async('uint8array');
    expect(Array.from(back)).toEqual(Array.from(img));

    const json = JSON.parse(await zip.file('manifest.json')!.async('string'));
    expect(json).toEqual({ hello: 'wörld 😀', n: 42 });
  });

  it('reads back an empty entry', async () => {
    const bytes = collect((w) => w.add('empty.bin', new Uint8Array(0)));
    const zip = await JSZip.loadAsync(bytes);
    expect((await zip.file('empty.bin')!.async('uint8array')).length).toBe(0);
  });

  it('rejects use after finish', () => {
    const w = new ZipWriter(() => {});
    w.add('a.txt', utf8Encode('a'));
    w.finish();
    expect(() => w.add('b.txt', utf8Encode('b'))).toThrow(/after finish/);
  });

  it('reads back a streamed (data-descriptor) entry alongside a plain one', async () => {
    const parts = ['{"memes":[', '{"id":"1"}', ',{"id":"2"}', ']}'];
    const bytes = collect((w) => {
      w.add('images/1.jpg', Uint8Array.from([1, 2, 3]));
      const s = w.addStream('manifest.json');
      for (const p of parts) s.write(utf8Encode(p));
      s.end();
    });
    const zip = await JSZip.loadAsync(bytes);
    expect(Object.keys(zip.files).sort()).toEqual(['images/1.jpg', 'manifest.json']);
    const json = JSON.parse(await zip.file('manifest.json')!.async('string'));
    expect(json).toEqual({ memes: [{ id: '1' }, { id: '2' }] });
  });

  it('forbids overlapping/again-after-finish stream operations', () => {
    const w = new ZipWriter(() => {});
    w.addStream('a');
    expect(() => w.add('b', new Uint8Array(0))).toThrow(/stream entry is open/);
    expect(() => w.finish()).toThrow(/stream entry is open/);
  });
});
