// React-free core for the audio-transcription pass: setting keys, the Moonshine
// STT model sources, and the pure byte-wrangling / decode-loop / cleanup helpers.
// Kept free of React and react-native-executorch (no hooks, no native enums —
// tensor building lives in audio.tsx) so it's trivially unit-testable and usable
// from any JS context. Mirrors visionCore.ts.

// Persisted-setting key: has the user opted in to downloading/loading the STT model.
export const AUDIO_ENABLED_KEY = 'audio.enabled';

// Moonshine tiny (English) — a fast on-device STT model. Unlike Whisper, which
// always pads audio to a fixed 30 s mel window, Moonshine's compute scales with
// the actual clip length, so short meme audio transcribes markedly faster. The
// pinned react-native-executorch (0.9.x) ships a Whisper-only STT runner, so we
// drive Moonshine's encoder + decoder ourselves through the generic ExecuTorch
// module runner (see audio.tsx) — same runtime as CLIP/the VLM, no new engine.
//
// Hosted by Software Mansion (the executorch lib authors): .pte exports for the
// XNNPACK backend plus a HuggingFace tokenizer.json.
const MOONSHINE_BASE =
  'https://huggingface.co/software-mansion/react-native-executorch-moonshine-tiny/resolve/v0.4.0';
export const MOONSHINE_ENCODER = `${MOONSHINE_BASE}/xnnpack/moonshine_tiny_xnnpack_encoder.pte`;
export const MOONSHINE_DECODER = `${MOONSHINE_BASE}/xnnpack/moonshine_tiny_xnnpack_decoder.pte`;
export const MOONSHINE_TOKENIZER = `${MOONSHINE_BASE}/moonshine_tiny_tokenizer.json`;

// Approximate on-disk sizes of the two model binaries (bytes), used only to
// size-weight the combined download-progress bar — the decoder dwarfs the
// encoder, so a naive average would jump misleadingly.
export const MOONSHINE_ENCODER_BYTES = 30_870_016;
export const MOONSHINE_DECODER_BYTES = 118_008_224;

// Moonshine's tokenizer special ids (from the model config): the decoder is
// seeded with BOS and we stop the moment it emits EOS.
export const MOONSHINE_BOS = 1;
export const MOONSHINE_EOS = 2;

// Moonshine caps generation at ~6.5 tokens per second of audio to keep the
// autoregressive loop from hallucinating past the end of speech (same factor
// the upstream model card uses).
export const MOONSHINE_TOKENS_PER_SEC = 6.5;

// Cap how much audio is decoded + transcribed per video. Memes are short; two
// minutes covers effectively all of them while bounding worst-case decode time
// and waveform memory (120s × 16kHz × 4B ≈ 7.7 MB) for stray long videos.
export const AUDIO_MAX_SECONDS = 120;

// Moonshine was trained on segments up to ~30 s; feed it no more than that per
// forward pass. Longer clips are split into back-to-back windows (planChunks)
// and their transcripts joined.
export const MOONSHINE_MAX_CHUNK_SECONDS = 30;

// Moonshine expects 16 kHz mono — the native extractor resamples to this.
export const AUDIO_SAMPLE_RATE = 16000;

export const MOONSHINE_MAX_CHUNK_SAMPLES = MOONSHINE_MAX_CHUNK_SECONDS * AUDIO_SAMPLE_RATE;

// Under ~0.4s of audio there is nothing intelligible to transcribe; treat the
// clip as silent instead of feeding the model a stub (STT models love to
// hallucinate a caption for near-empty audio).
export const AUDIO_MIN_SAMPLES = Math.round(AUDIO_SAMPLE_RATE * 0.4);

// ---- Moonshine greedy decode ---------------------------------------------------

// The most tokens Moonshine should emit for a clip of `numSamples` at 16 kHz —
// a hard stop for the autoregressive loop when EOS never arrives.
export function moonshineMaxTokens(numSamples: number, sampleRate = AUDIO_SAMPLE_RATE): number {
  return Math.max(1, Math.floor((numSamples / sampleRate) * MOONSHINE_TOKENS_PER_SEC));
}

// One decoder forward pass, normalized so the pure loop doesn't care how the
// native tensor was shaped. Moonshine's exported decoder may return either
// argmaxed token ids (int64 → `isTokenIds`) or raw logits; we handle both.
export interface DecoderOutput {
  data: ArrayLike<number> | BigInt64Array;
  sizes: number[]; // [1, seq] for token ids, [1, seq, vocab] for logits
  isTokenIds: boolean;
}

// The decoder emits one prediction per input position; the *newest* token is the
// last row. Return its id: read it straight off for token-id outputs, or argmax
// the final logit row otherwise.
export function pickNextToken(out: DecoderOutput): number {
  const d = out.data;
  if (out.isTokenIds) {
    return Number(d[d.length - 1]);
  }
  const vocab = out.sizes[out.sizes.length - 1];
  const seq = d.length / vocab;
  const base = (seq - 1) * vocab;
  let best = 0;
  let bestVal = -Infinity;
  for (let v = 0; v < vocab; v++) {
    const x = Number(d[base + v]);
    if (x > bestVal) {
      bestVal = x;
      best = v;
    }
  }
  return best;
}

// Split a waveform into back-to-back windows no longer than `chunkSamples`.
// Non-overlapping: simple and predictable, and a single window covers the vast
// majority of memes (only >30 s clips split at all).
export function planChunks(numSamples: number, chunkSamples: number): Array<[number, number]> {
  if (numSamples <= chunkSamples) return [[0, numSamples]];
  const chunks: Array<[number, number]> = [];
  for (let start = 0; start < numSamples; start += chunkSamples) {
    chunks.push([start, Math.min(start + chunkSamples, numSamples)]);
  }
  return chunks;
}

// Native-facing ops the decode loop drives. `E` is the opaque encoder-output
// tensor (built + consumed in audio.tsx); the loop only shuttles it from encode
// to decode.
export interface MoonshineOps<E = unknown> {
  encode(waveform: Float32Array): Promise<E>;
  decode(tokens: number[], encoderOutput: E): Promise<DecoderOutput>;
  detokenize(ids: number[]): Promise<string>;
}

// Transcribe a full 16 kHz waveform with Moonshine: window it, and for each
// window run encoder once then a greedy autoregressive decoder loop (cache-less
// — we re-feed the whole token sequence each step, which is fine for short
// clips) until EOS or the per-clip token cap. Detokenize each window and join.
export async function runMoonshine<E>(
  waveform: Float32Array,
  ops: MoonshineOps<E>,
  opts: { chunkSamples?: number; sampleRate?: number } = {}
): Promise<string> {
  const sampleRate = opts.sampleRate ?? AUDIO_SAMPLE_RATE;
  const chunkSamples = opts.chunkSamples ?? MOONSHINE_MAX_CHUNK_SAMPLES;
  const pieces: string[] = [];
  for (const [start, end] of planChunks(waveform.length, chunkSamples)) {
    const chunk = waveform.subarray(start, end);
    const encoderOutput = await ops.encode(chunk);
    const maxTokens = moonshineMaxTokens(chunk.length, sampleRate);
    const tokens: number[] = [MOONSHINE_BOS];
    for (let step = 0; step < maxTokens; step++) {
      const next = pickNextToken(await ops.decode(tokens, encoderOutput));
      if (next === MOONSHINE_EOS) break;
      tokens.push(next);
    }
    const generated = tokens.slice(1); // drop the seeded BOS
    if (generated.length) {
      pieces.push((await ops.detokenize(generated)).trim());
    }
  }
  return pieces.filter(Boolean).join(' ');
}

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
