// Tests for the pure audio-transcription helpers: the base64 → PCM waveform
// path (which must exactly reverse what the native extractor writes — raw
// little-endian float32), the Moonshine greedy decode loop, and transcript
// cleanup. audioCore is deliberately free of react-native-executorch, so these
// run under plain Node with no native bindings or mocks.

import {
  AUDIO_SAMPLE_RATE,
  MOONSHINE_BOS,
  MOONSHINE_EOS,
  MOONSHINE_MAX_CHUNK_SAMPLES,
  MOONSHINE_MAX_DECODE_TOKENS,
  base64ToBytes,
  cleanTranscript,
  moonshineMaxTokens,
  pcmBase64ToWaveform,
  pickNextToken,
  planChunks,
  runMoonshine,
  type DecoderOutput,
  type MoonshineOps,
} from './audioCore';

describe('base64ToBytes', () => {
  it('decodes plain payloads', () => {
    const bytes = base64ToBytes(Buffer.from('meme').toString('base64'));
    expect(Buffer.from(bytes).toString()).toBe('meme');
  });

  it('handles padding and embedded whitespace', () => {
    for (const s of ['a', 'ab', 'abc', 'abcd', 'hello world!']) {
      const b64 = Buffer.from(s).toString('base64');
      const wrapped = b64.replace(/(.{4})/g, '$1\n'); // fake line wrapping
      expect(Buffer.from(base64ToBytes(wrapped)).toString()).toBe(s);
    }
  });

  it('decodes an empty string to zero bytes', () => {
    expect(base64ToBytes('').byteLength).toBe(0);
  });
});

describe('pcmBase64ToWaveform', () => {
  it('round-trips little-endian float32 samples', () => {
    const samples = Float32Array.from([0, 1, -1, 0.5, -0.25, 3.1415927]);
    // Buffer.from(TypedArray.buffer) is the same raw little-endian layout the
    // native extractor writes to disk.
    const b64 = Buffer.from(samples.buffer).toString('base64');
    const out = pcmBase64ToWaveform(b64);
    expect(out).toHaveLength(samples.length);
    for (let i = 0; i < samples.length; i++) expect(out[i]).toBeCloseTo(samples[i], 6);
  });

  it('drops trailing bytes that do not form a whole float', () => {
    const bytes = new Uint8Array(10); // 2 floats + 2 stray bytes
    const out = pcmBase64ToWaveform(Buffer.from(bytes).toString('base64'));
    expect(out).toHaveLength(2);
  });
});

describe('moonshineMaxTokens', () => {
  it('caps at ~6.5 tokens per second of audio', () => {
    expect(moonshineMaxTokens(AUDIO_SAMPLE_RATE)).toBe(6); // floor(1s * 6.5)
    expect(moonshineMaxTokens(AUDIO_SAMPLE_RATE * 4)).toBe(26); // floor(4s * 6.5)
  });

  it('never returns less than one token', () => {
    expect(moonshineMaxTokens(100)).toBe(1);
    expect(moonshineMaxTokens(0)).toBe(1);
  });

  it('clamps to the decoder bounded input length for long clips', () => {
    // A 30s window's raw cap is floor(30 * 6.5) = 195, but the decoder's
    // token_ids input is bounded at 178 — the cap must not exceed it.
    expect(moonshineMaxTokens(MOONSHINE_MAX_CHUNK_SAMPLES)).toBe(MOONSHINE_MAX_DECODE_TOKENS);
    expect(moonshineMaxTokens(AUDIO_SAMPLE_RATE * 600)).toBe(MOONSHINE_MAX_DECODE_TOKENS);
  });
});

describe('pickNextToken', () => {
  it('reads the last id from a token-id output', () => {
    expect(
      pickNextToken({ data: BigInt64Array.from([5n, 9n]), sizes: [1, 2], isTokenIds: true })
    ).toBe(9);
  });

  it('argmaxes the final row of a logits output', () => {
    // Two positions, vocab of 3; the last row's max is index 1.
    const logits: DecoderOutput = {
      data: [0.1, 0.2, 0.7, /* last row → */ 0.3, 0.9, 0.4],
      sizes: [1, 2, 3],
      isTokenIds: false,
    };
    expect(pickNextToken(logits)).toBe(1);
  });
});

describe('planChunks', () => {
  it('returns a single window when the clip fits', () => {
    expect(planChunks(100, 200)).toEqual([[0, 100]]);
    expect(planChunks(200, 200)).toEqual([[0, 200]]);
  });

  it('splits longer clips into back-to-back windows', () => {
    expect(planChunks(10, 4)).toEqual([
      [0, 4],
      [4, 8],
      [8, 10],
    ]);
  });
});

describe('runMoonshine', () => {
  // A fake decoder scripted by the current token count: the nth step returns
  // script[n] as the newest token. Encoder output is opaque to the loop.
  const scriptedOps = (script: number[]): MoonshineOps<string> & {
    detokenizeArgs: number[][];
  } => {
    const detokenizeArgs: number[][] = [];
    return {
      detokenizeArgs,
      encode: async () => 'enc',
      decode: async (tokens) => ({
        data: [script[tokens.length - 1]],
        sizes: [1, 1],
        isTokenIds: true,
      }),
      detokenize: async (ids) => {
        detokenizeArgs.push(ids);
        return ids.map((i) => `w${i}`).join(' ');
      },
    };
  };

  it('decodes greedily, drops BOS, and stops at EOS', async () => {
    const ops = scriptedOps([10, 11, MOONSHINE_EOS]);
    const text = await runMoonshine(new Float32Array(AUDIO_SAMPLE_RATE), ops);
    expect(text).toBe('w10 w11');
    // BOS is seeded but must never reach the tokenizer.
    expect(ops.detokenizeArgs).toEqual([[10, 11]]);
    expect(ops.detokenizeArgs[0]).not.toContain(MOONSHINE_BOS);
  });

  it('stops at the per-clip token cap when EOS never arrives', async () => {
    // 1s of audio → cap of 6 tokens; the decoder always emits a non-EOS token.
    const ops = scriptedOps(new Array(50).fill(7));
    const text = await runMoonshine(new Float32Array(AUDIO_SAMPLE_RATE), ops);
    expect(ops.detokenizeArgs[0]).toHaveLength(6);
    expect(text).toBe('w7 w7 w7 w7 w7 w7');
  });

  it('never feeds the decoder more than its bounded token_ids length', async () => {
    // Regression: the SWM export bounds decoder input 0 at 178. Cache-less decode
    // re-feeds the whole prefix, so an EOS-less 30s clip must stop feeding at 178
    // — one more and the native forward aborts ("resize a bounded tensor ... 179").
    let maxFed = 0;
    const ops: MoonshineOps<string> = {
      encode: async () => 'enc',
      decode: async (tokens) => {
        maxFed = Math.max(maxFed, tokens.length);
        return { data: [7], sizes: [1, 1], isTokenIds: true }; // never EOS
      },
      detokenize: async (ids) => ids.join(','),
    };
    await runMoonshine(new Float32Array(MOONSHINE_MAX_CHUNK_SAMPLES), ops);
    expect(maxFed).toBe(MOONSHINE_MAX_DECODE_TOKENS);
  });

  it('windows long audio and joins the pieces', async () => {
    // Each chunk emits one token then EOS.
    const perChunk = [20, MOONSHINE_EOS];
    let encodeCalls = 0;
    const ops: MoonshineOps<string> = {
      encode: async () => {
        encodeCalls++;
        return 'enc';
      },
      decode: async (tokens) => ({
        data: [perChunk[tokens.length - 1]],
        sizes: [1, 1],
        isTokenIds: true,
      }),
      detokenize: async (ids) => ids.map((i) => `w${i}`).join(' '),
    };
    // length 10 with chunk size 4 → windows [0,4],[4,8],[8,10]; minChunkSamples:0
    // keeps the tiny synthetic tail so this exercises pure windowing.
    const text = await runMoonshine(new Float32Array(10), ops, { chunkSamples: 4, minChunkSamples: 0 });
    expect(encodeCalls).toBe(3);
    expect(text).toBe('w20 w20 w20');
  });

  it('skips a degenerate trailing chunk that would segfault the encoder', async () => {
    // A ~120s clip capped at AUDIO_MAX_SECONDS comes back a few samples over a
    // whole number of 30s windows, leaving an ~11-sample tail (real case: id 5326,
    // 1_920_011 samples). Feeding that to the encoder null-derefs ExecuTorch, so
    // runMoonshine must skip sub-min chunks under the default floor.
    const fed: number[] = [];
    const ops: MoonshineOps<string> = {
      encode: async (w) => {
        fed.push(w.length);
        return 'enc';
      },
      decode: async () => ({ data: [MOONSHINE_EOS], sizes: [1, 1], isTokenIds: true }),
      detokenize: async (ids) => ids.join(','),
    };
    await runMoonshine(new Float32Array(4 * MOONSHINE_MAX_CHUNK_SAMPLES + 11), ops);
    // four full windows encoded; the 11-sample tail skipped, never encoded.
    expect(fed).toEqual([
      MOONSHINE_MAX_CHUNK_SAMPLES,
      MOONSHINE_MAX_CHUNK_SAMPLES,
      MOONSHINE_MAX_CHUNK_SAMPLES,
      MOONSHINE_MAX_CHUNK_SAMPLES,
    ]);
  });
});

describe('cleanTranscript', () => {
  it('trims and collapses whitespace', () => {
    expect(cleanTranscript('  why are you   running  ')).toBe('why are you running');
  });

  it('strips non-speech event tokens and music glyphs', () => {
    expect(cleanTranscript('[Music] why are you running (laughs) ♪♪')).toBe(
      'why are you running'
    );
  });

  it('collapses a tokens-only transcript to empty (treated as no speech)', () => {
    expect(cleanTranscript('[MUSIC] (applause) ♪')).toBe('');
    expect(cleanTranscript('')).toBe('');
    expect(cleanTranscript(null)).toBe('');
    expect(cleanTranscript(undefined)).toBe('');
  });
});
