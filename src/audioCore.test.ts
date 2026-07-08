// Tests for the pure audio-transcription helpers: the base64 → PCM waveform
// path (which must exactly reverse what the native extractor writes — raw
// little-endian float32) and the Whisper transcript cleanup.

// audioCore only imports a model descriptor constant from the executorch
// package; stub it so the test never loads native bindings.
jest.mock('react-native-executorch', () => ({
  WHISPER_TINY_EN: { modelName: 'whisper-tiny-en' },
}));

import { base64ToBytes, cleanTranscript, pcmBase64ToWaveform } from './audioCore';

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
