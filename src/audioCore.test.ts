// Tests for the pure audio-transcription helpers: the base64 → PCM waveform
// path (which must exactly reverse what the native extractor writes — raw
// little-endian float32), model identity, cleanup, and diagnostics. audioCore
// stays free of native bindings so these run under plain Node.

import {
  AUDIO_MODEL_INFO,
  base64ToBytes,
  cleanTranscript,
  pcmBase64ToWaveform,
  waveformLevel,
} from './audioCore';

describe('audio model selection', () => {
  it('uses the supported Whisper tiny English runner and a model-specific requeue key', () => {
    expect(AUDIO_MODEL_INFO).toEqual({
      id: 'whisper-tiny-en',
      label: 'Whisper tiny (English)',
      requeueKey: 'audio_requeue_whisper_tiny_en_v1',
    });
  });
});


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

describe('waveformLevel', () => {
  it('reports ~0 for a silent (all-zero) waveform', () => {
    const { rms, peak } = waveformLevel(new Float32Array(16000));
    expect(rms).toBe(0);
    expect(peak).toBe(0);
  });

  it('reports the peak amplitude regardless of sign', () => {
    const { peak } = waveformLevel(new Float32Array([0.1, -0.8, 0.3]));
    expect(peak).toBeCloseTo(0.8, 6);
  });

  it('computes RMS of a full-scale square wave as 1', () => {
    const { rms } = waveformLevel(new Float32Array([1, -1, 1, -1]));
    expect(rms).toBeCloseTo(1, 6);
  });

  it('ignores non-finite samples so a corrupt decode cannot poison the figure', () => {
    const { rms, peak } = waveformLevel([0.5, NaN, Infinity, -0.5]);
    expect(rms).toBeCloseTo(0.5, 6);
    expect(peak).toBeCloseTo(0.5, 6);
  });
});
