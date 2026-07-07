import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import * as FileSystem from 'expo-file-system/legacy';
import { useSpeechToText } from 'react-native-executorch';

import {
  AUDIO_ENABLED_KEY,
  AUDIO_MAX_SECONDS,
  AUDIO_MIN_SAMPLES,
  AUDIO_MODEL,
  cleanTranscript,
  pcmBase64ToWaveform,
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
import { audioNativeAvailable, extractAudio } from '../modules/memeget-bg';
import { deleteCache, materialize } from './saf';

// Audio analysis: on-device Whisper (via ExecuTorch — the SAME runtime that
// already runs CLIP and LFM2-VL) transcribes the speech in video memes so
// "what was that clip where the guy says X" becomes a text search. Like the
// vision pass this is an *enrichment*: videos are already indexed and
// searchable by their keyframe; this adds what's SAID in them.
//
// Pipeline per video: native MediaCodec decode of the audio track to mono
// 16 kHz PCM (modules/memeget-bg) → Whisper transcription → transcript stored
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

// Transcribe ONE video: materialize the SAF file, decode its audio natively,
// run Whisper, persist. Cleans up its temp files whatever happens.
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

  const stt = useSpeechToText({ model: AUDIO_MODEL, preventLoad: !(hydrated && enabled) });

  const setEnabled = (on: boolean) => {
    setEnabledState(on);
    setSetting(AUDIO_ENABLED_KEY, on ? '1' : '0').catch(() => {});
  };

  // Latest transcribe fn for the pass below (stt gets a new identity per render).
  const sttRef = useRef(stt);
  sttRef.current = stt;
  const ready = enabled && audioNativeAvailable && stt.isReady;
  const readyRef = useRef(ready);
  readyRef.current = ready;

  // One pass at a time — Whisper shares the accelerator with the other models,
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
    try {
      const queue = await getMemesNeedingAudio();
      const total = queue.length;
      const result: TranscribeResult = { transcribed: 0, silent: 0, failed: 0 };
      for (let i = 0; i < queue.length; i++) {
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
      busyRef.current = false;
      setRunning(false);
    }
  };

  const api = useMemo<AudioApi>(
    () => ({
      enabled,
      ready,
      progress: stt.downloadProgress ?? 0,
      running,
      error: stt.error ? String((stt.error as any).message ?? stt.error) : null,
      nativeAvailable: audioNativeAvailable,
      setEnabled,
      runTranscription,
    }),
    // stt identity changes as its state updates; depend on the fields we read.
    [enabled, ready, running, stt.isReady, stt.downloadProgress, stt.error]
  );

  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useAudio(): AudioApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useAudio must be used inside <AudioProvider>');
  return ctx;
}
