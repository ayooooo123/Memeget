// React-free core for the audio-transcription pass: the active model identity
// and pure byte-wrangling / cleanup helpers. Native model loading and inference
// live in audio.tsx; keeping this file runtime-free makes it unit-testable.

// Persisted-setting key: has the user opted in to downloading/loading the STT model.
export const AUDIO_ENABLED_KEY = 'audio.enabled';

// The active speech model. react-native-executorch 0.9.2's supported native STT
// runner is Whisper-only; use its smallest English preset for meme audio. The
// model-specific requeue key guarantees a clean pass whenever the model changes.
export const AUDIO_MODEL_INFO = {
  id: 'whisper-tiny-en',
  label: 'Whisper tiny (English)',
  requeueKey: 'audio_requeue_whisper_tiny_en_v1',
} as const;

// Cap how much audio is decoded + transcribed per video. The native Whisper
// runner handles its strict 30 s context internally by walking longer waveforms
// in chunks, so we can safely hand it up to two minutes.
export const AUDIO_MAX_SECONDS = 120;

// Whisper expects 16 kHz mono — the native extractor resamples to this.
export const AUDIO_SAMPLE_RATE = 16000;

// Under ~0.4s of audio there is nothing intelligible to transcribe; treat the
// clip as silent instead of feeding Whisper a stub (which tends to hallucinate).
export const AUDIO_MIN_SAMPLES = Math.round(AUDIO_SAMPLE_RATE * 0.4);

// ---- raw PCM decoding ---------------------------------------------------------

const B64_LOOKUP = (() => {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const table = new Int8Array(128).fill(-1);
  for (let i = 0; i < alphabet.length; i++) table[alphabet.charCodeAt(i)] = i;
  return table;
})();

// Decode base64 to bytes with a plain lookup loop. Used for multi-megabyte PCM
// payloads, so it avoids intermediate strings entirely (atob would produce a
// second multi-MB string just to be picked apart char by char). Skips
// whitespace/padding; any other foreign character is simply ignored too, which
// is fine for our own well-formed payloads.
export function base64ToBytes(b64: string): Uint8Array {
  const out = new Uint8Array((b64.length * 3) >> 2);
  let acc = 0;
  let accBits = 0;
  let n = 0;
  for (let i = 0; i < b64.length; i++) {
    const code = b64.charCodeAt(i);
    const v = code < 128 ? B64_LOOKUP[code] : -1;
    if (v < 0) continue; // '=', '\n', etc.
    acc = (acc << 6) | v;
    accBits += 6;
    if (accBits >= 8) {
      accBits -= 8;
      out[n++] = (acc >> accBits) & 0xff;
    }
  }
  return out.subarray(0, n);
}

// Interpret a base64 payload of raw little-endian float32 PCM (what the native
// extractor writes) as a model-ready waveform. Android and every JS engine we
// run on are little-endian, so viewing the bytes as Float32Array directly is
// correct and copy-free.
export function pcmBase64ToWaveform(b64: string): Float32Array {
  const bytes = base64ToBytes(b64);
  const usable = bytes.byteLength - (bytes.byteLength % 4);
  return new Float32Array(bytes.buffer, bytes.byteOffset, usable / 4);
}

// ---- transcript cleanup --------------------------------------------------------

// STT models decorate non-speech audio with bracketed/parenthesized event tokens
// ("[Music]", "(laughing)", "♪ ... ♪"). They aren't words anyone will search
// for, so strip them; a transcript that was ONLY such tokens collapses to '' —
// which downstream treats as "no speech in this video".
export function cleanTranscript(raw: string | null | undefined): string {
  if (!raw) return '';
  return raw
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/\([^)]*\)/g, ' ')
    .replace(/[♪♫]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}


// ---- diagnostics ---------------------------------------------------------------

// RMS + peak amplitude of a normalized [-1,1] waveform. Used only to classify an
// empty transcript: a near-silent waveform (tiny rms/peak) is a genuinely
// speechless clip, whereas an empty transcript over a waveform with real signal
// points at the STT itself (decoder emitting EOS immediately, or cleanup
// stripping everything). Cheap single pass; NaN/Inf samples are ignored so a
// corrupt decode can't poison the figure.
export function waveformLevel(waveform: ArrayLike<number>): { rms: number; peak: number } {
  let sumSq = 0;
  let peak = 0;
  let n = 0;
  for (let i = 0; i < waveform.length; i++) {
    const v = waveform[i];
    if (!Number.isFinite(v)) continue;
    const a = Math.abs(v);
    if (a > peak) peak = a;
    sumSq += v * v;
    n++;
  }
  return { rms: n ? Math.sqrt(sumSq / n) : 0, peak };
}
