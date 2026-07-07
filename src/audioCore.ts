// React-free core for the audio-transcription pass: setting keys, the Whisper
// model choice, and the pure byte-wrangling/cleanup helpers. Kept free of React
// and react-native-executorch HOOKS (mirrors visionCore.ts) so it's trivially
// unit-testable and usable from any JS context.
import { WHISPER_TINY_EN } from 'react-native-executorch';

// Persisted-setting key: has the user opted in to downloading/loading Whisper.
export const AUDIO_ENABLED_KEY = 'audio.enabled';

// Whisper tiny (English) — the smallest STT build react-native-executorch
// ships. Meme audio is short and mostly English; tiny keeps the one-time
// download and RAM cost far below the vision models while still nailing the
// "what was that TikTok voice saying" use case. Same ExecuTorch engine as
// CLIP/LFM2-VL, so no new runtime is shipped.
export const AUDIO_MODEL = WHISPER_TINY_EN;

// Cap how much audio is decoded + transcribed per video. Memes are short; two
// minutes covers effectively all of them while bounding worst-case decode time
// and waveform memory (120s × 16kHz × 4B ≈ 7.7 MB) for stray long videos.
export const AUDIO_MAX_SECONDS = 120;

// Whisper expects 16 kHz mono — the native extractor resamples to this.
export const AUDIO_SAMPLE_RATE = 16000;

// Under ~0.4s of audio there is nothing intelligible to transcribe; treat the
// clip as silent instead of feeding Whisper a stub (which loves to hallucinate
// a caption for near-empty audio).
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
// extractor writes) as a Whisper-ready waveform. Android and every JS engine we
// run on are little-endian, so viewing the bytes as Float32Array directly is
// correct and copy-free.
export function pcmBase64ToWaveform(b64: string): Float32Array {
  const bytes = base64ToBytes(b64);
  const usable = bytes.byteLength - (bytes.byteLength % 4);
  return new Float32Array(bytes.buffer, bytes.byteOffset, usable / 4);
}

// ---- transcript cleanup --------------------------------------------------------

// Whisper decorates non-speech audio with bracketed/parenthesized event tokens
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
