import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import * as FileSystem from 'expo-file-system/legacy';
import { ScalarType, useExecutorchModule, useTokenizer, type TensorPtr } from 'react-native-executorch';

import {
  AUDIO_ENABLED_KEY,
  AUDIO_MAX_SECONDS,
  AUDIO_MIN_SAMPLES,
  MOONSHINE_DECODER,
  MOONSHINE_DECODER_BYTES,
  MOONSHINE_ENCODER,
  MOONSHINE_ENCODER_BYTES,
  MOONSHINE_TOKENIZER,
  cleanTranscript,
  pcmBase64ToWaveform,
  runMoonshine,
} from './audioCore';
import {
  getMemesNeedingAudio,
  getSetting,
  markAudioFailed,
  setMemeTranscript,
  setSetting,
  type MemeNeedingAudioRow,
} from './db';
import { emitLibraryChanged } from './events';
import { yieldToSearch } from './interactive';
import { acquireKeepAlive } from './keepAlive';
import { audioNativeAvailable, extractAudio } from '../modules/memeget-bg';
import { deleteCache, materialize } from './saf';

// Audio analysis: on-device Moonshine (via ExecuTorch — the SAME runtime that
// already runs CLIP and the VLM) transcribes the speech in video memes so
// "what was that clip where the guy says X" becomes a text search. Like the
// vision pass this is an *enrichment*: videos are already indexed and
// searchable by their keyframe; this adds what's SAID in them.
//
// Pipeline per video: native MediaCodec decode of the audio track to mono
// 16 kHz PCM (modules/memeget-bg) → Moonshine transcription → transcript stored
// on the meme row and folded into the lexical side of search.

export interface TranscribeProgress {
  done: number;
  total: number;
  current: string;
}

export interface TranscribeResult {
  transcribed: number; // got actual speech
  silent: number; // analyzed, but no audio track / no recognizable speech
  failed: number;
}

export interface AudioApi {
  enabled: boolean; // user opted in (model may download/load)
  ready: boolean; // model loaded, can transcribe
  progress: number; // 0..1 model download/load progress
  running: boolean; // a transcription pass is active
  error: string | null;
  // False in a JS-only build: decoding AAC/Opus needs the native module.
  nativeAvailable: boolean;
  setEnabled: (on: boolean) => void;
  // Burst pass over every pending video. Mutex-guarded; resolves 'busy' when a
  // pass is already running.
  runTranscription: (
    opts?: { onProgress?: (p: TranscribeProgress) => void; shouldCancel?: () => boolean }
  ) => Promise<TranscribeResult | 'busy'>;
}

const Ctx = createContext<AudioApi | null>(null);

// react-native-executorch's generic `forward` hands each output tensor's dataPtr
// back as a raw ArrayBuffer (see JsiConversions.h getJsiValue(JSTensorViewOut)),
// NOT a typed array — an ArrayBuffer has no `.length` and no element access, so
// feeding it straight to the decode loop's argmax makes every read NaN and the
// model "transcribes" nothing but token 0. View it as the typed array its
// scalarType implies before the pure loop indexes it.
function tensorData(o: TensorPtr): ArrayLike<number> | BigInt64Array {
  const buf = o.dataPtr;
  if (!(buf instanceof ArrayBuffer)) return buf as ArrayLike<number> | BigInt64Array;
  switch (o.scalarType) {
    case ScalarType.FLOAT:
      return new Float32Array(buf);
    case ScalarType.DOUBLE:
      return new Float64Array(buf);
    case ScalarType.LONG:
      return new BigInt64Array(buf);
    case ScalarType.INT:
      return new Int32Array(buf);
    case ScalarType.SHORT:
      return new Int16Array(buf);
    default:
      throw new Error(`Unsupported decoder output scalarType ${o.scalarType}`);
  }
}

// Transcribe ONE video: materialize the SAF file, decode its audio natively,
// run Moonshine, persist. Cleans up its temp files whatever happens.
async function transcribeOne(
  stt: { transcribe: (waveform: Float32Array) => Promise<{ text: string }> },
  m: MemeNeedingAudioRow
): Promise<'done' | 'silent' | 'failed'> {
  const temp: string[] = [];
  try {
    // Copy out of SAF first — MediaExtractor is happier with a plain file
    // path, and this matches how the indexer/thumbnailer treat content:// uris.
    const local = await materialize(m.uri, m.name);
    temp.push(local);

    const pcm = await extractAudio(local, AUDIO_MAX_SECONDS);
    if (pcm) temp.push(pcm.path);
    if (!pcm || pcm.samples < AUDIO_MIN_SAMPLES) {
      // No audio track, or too short to contain speech: analyzed, nothing to say.
      await setMemeTranscript(m.id, '');
      return 'silent';
    }

    const b64 = await FileSystem.readAsStringAsync(pcm.path, {
      encoding: FileSystem.EncodingType.Base64,
    });
    const waveform = pcmBase64ToWaveform(b64);
    const res = await stt.transcribe(waveform);
    const text = cleanTranscript(res.text);

    await setMemeTranscript(m.id, text);
    return text ? 'done' : 'silent';
  } catch {
    await markAudioFailed(m.id).catch(() => {});
    return 'failed';
  } finally {
    for (const t of temp) await deleteCache(t);
  }
}

export function AudioProvider({ children }: { children: React.ReactNode }) {
  const [enabled, setEnabledState] = useState(false);
  const [hydrated, setHydrated] = useState(false);
  const [running, setRunning] = useState(false);

  // Load the persisted opt-in once. Until hydrated, preventLoad keeps the hook
  // from auto-downloading the model on a fresh install.
  useEffect(() => {
    getSetting(AUDIO_ENABLED_KEY)
      .then((v) => setEnabledState(v === '1'))
      .catch(() => {})
      .finally(() => setHydrated(true));
  }, []);

  const encoder = useExecutorchModule({
    modelSource: MOONSHINE_ENCODER,
    preventLoad: !(hydrated && enabled),
  });
  const decoder = useExecutorchModule({
    modelSource: MOONSHINE_DECODER,
    preventLoad: !(hydrated && enabled),
  });
  const tokenizer = useTokenizer({
    tokenizer: { tokenizerSource: MOONSHINE_TOKENIZER },
    preventLoad: !(hydrated && enabled),
  });

  const setEnabled = (on: boolean) => {
    setEnabledState(on);
    setSetting(AUDIO_ENABLED_KEY, on ? '1' : '0').catch(() => {});
  };

  // Moonshine runs as three separate ExecuTorch resources we drive by hand:
  // encoder (waveform → hidden states), decoder (greedy autoregressive loop),
  // and the tokenizer for detokenizing. transcribeOne only needs a
  // { transcribe(waveform) } shape, so adapt the raw forwards behind one.
  const transcribe = async (waveform: Float32Array): Promise<{ text: string }> => {
    const text = await runMoonshine<TensorPtr>(waveform, {
      encode: async (w) => {
        const out = await encoder.forward([
          { dataPtr: w, sizes: [1, w.length], scalarType: ScalarType.FLOAT },
        ]);
        return out[0];
      },
      decode: async (tokens, encoderOutput) => {
        const ids = BigInt64Array.from(tokens, (t) => BigInt(t));
        const out = await decoder.forward([
          { dataPtr: ids, sizes: [1, tokens.length], scalarType: ScalarType.LONG },
          encoderOutput,
        ]);
        const o = out[0];
        return {
          data: tensorData(o),
          sizes: o.sizes,
          isTokenIds: o.scalarType === ScalarType.LONG || o.scalarType === ScalarType.INT,
        };
      },
      detokenize: (genIds) => tokenizer.decode(genIds, true),
    });
    return { text };
  };
  // The stt-like handle gets a new identity per render (the hooks do); keep the
  // latest for the long-running pass below.
  const sttRef = useRef({ transcribe });
  sttRef.current = { transcribe };
  const ready =
    enabled && audioNativeAvailable && encoder.isReady && decoder.isReady && tokenizer.isReady;
  const readyRef = useRef(ready);
  readyRef.current = ready;
  // Size-weighted download progress — the decoder binary dwarfs the encoder, so
  // a plain average of the two bars would lurch.
  const downloadProgress =
    (encoder.downloadProgress * MOONSHINE_ENCODER_BYTES +
      decoder.downloadProgress * MOONSHINE_DECODER_BYTES) /
    (MOONSHINE_ENCODER_BYTES + MOONSHINE_DECODER_BYTES);
  const loadError = encoder.error ?? decoder.error ?? tokenizer.error;

  // One pass at a time — Moonshine shares the accelerator with the other models,
  // and a second concurrent pass would double-process the same queue anyway.
  const busyRef = useRef(false);

  const runTranscription = async (
    opts: { onProgress?: (p: TranscribeProgress) => void; shouldCancel?: () => boolean } = {}
  ): Promise<TranscribeResult | 'busy'> => {
    if (busyRef.current) return 'busy';
    if (!readyRef.current) {
      throw new Error('Speech model is still loading — try again shortly.');
    }
    busyRef.current = true;
    setRunning(true);
    // A full transcription pass over a video-heavy library runs for a long
    // time — hold the keep-alive foreground service so it survives the user
    // switching apps or the screen sleeping.
    const release = acquireKeepAlive('Transcribing your videos');
    try {
      const queue = await getMemesNeedingAudio();
      const total = queue.length;
      const result: TranscribeResult = { transcribed: 0, silent: 0, failed: 0 };
      for (let i = 0; i < queue.length; i++) {
        if (opts.shouldCancel?.() || !readyRef.current) break;
        // Moonshine shares the accelerator with the CLIP text embed a search
        // needs; a full transcription pass runs generations back-to-back, so
        // stand down between clips while the user is searching and let their
        // query vector land instead of starving behind the whole queue.
        await yieldToSearch(opts.shouldCancel);
        if (opts.shouldCancel?.() || !readyRef.current) break;
        opts.onProgress?.({ done: i, total, current: queue[i].name });
        const r = await transcribeOne(
          { transcribe: (w) => sttRef.current.transcribe(w) },
          queue[i]
        );
        if (r === 'done') result.transcribed++;
        else if (r === 'silent') result.silent++;
        else result.failed++;
      }
      opts.onProgress?.({ done: total, total, current: '' });
      // Transcripts changed under the Library's feet — let the grid re-fetch.
      emitLibraryChanged();
      return result;
    } finally {
      release();
      busyRef.current = false;
      setRunning(false);
    }
  };

  const api = useMemo<AudioApi>(
    () => ({
      enabled,
      ready,
      progress: downloadProgress,
      running,
      error: loadError ? loadError.message : null,
      nativeAvailable: audioNativeAvailable,
      setEnabled,
      runTranscription,
    }),
    [enabled, ready, running, downloadProgress, loadError]
  );

  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useAudio(): AudioApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useAudio must be used inside <AudioProvider>');
  return ctx;
}
