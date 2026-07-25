import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import * as FileSystem from 'expo-file-system/legacy';
import { models, useSpeechToText } from 'react-native-executorch';

import {
  AUDIO_ENABLED_KEY,
  AUDIO_MAX_SECONDS,
  AUDIO_MIN_SAMPLES,
  cleanTranscript,
  pcmBase64ToWaveform,
  waveformLevel,
} from './audioCore';
import {
  getMemeForAudio,
  getMemesNeedingAudio,
  requeueMemeAudio,
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

// Audio analysis: on-device Whisper through react-native-executorch's supported
// native STT runner transcribes speech in video memes so "what was that clip
// where the guy says X" becomes a text search. Like vision, this is enrichment:
// videos are already indexed by their keyframe; this adds what's said in them.
//
// Pipeline per video: native MediaCodec decode to mono 16 kHz PCM
// (modules/memeget-bg) → native Whisper transcription → transcript stored and
// folded into the lexical side of search.

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

// Outcome of a single-clip retranscription. The transcription states mirror
// transcribeOne; the extra variants report why nothing ran.
export type RegenerateResult =
  | 'done' // got speech
  | 'silent' // analyzed, no audio track / no recognizable speech
  | 'failed' // extraction/transcription errored
  | 'busy' // a pass (bulk or single) is already running
  | 'not-ready' // model still loading / audio disabled
  | 'missing'; // id isn't a transcribable video

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
  // Retranscribe ONE video now (the per-meme "regenerate" action). Shares the
  // same mutex as runTranscription, so it can't race a bulk pass.
  regenerateMeme: (id: number) => Promise<RegenerateResult>;
}

const Ctx = createContext<AudioApi | null>(null);


// Transcribe ONE video: materialize the SAF file, decode its audio natively,
// run Whisper, persist. Cleans up its temp files whatever happens.
async function transcribeOne(
  stt: { transcribe: (waveform: Float32Array) => Promise<{ text: string }> },
  m: MemeNeedingAudioRow
): Promise<'done' | 'silent' | 'failed'> {
  const temp: string[] = [];
  // Checkpoint this clip as failed BEFORE any native work. ExecuTorch can hard-
  // crash (SIGSEGV in libexecutorch_jni) on certain clips, killing the whole
  // process before we can persist a result. Pre-marking means such a clip is left
  // 'failed' — skipped on the next pass — instead of stuck 'pending' and crashing
  // the pass on the same clip forever. Overwritten to done/silent on success.
  await markAudioFailed(m.id).catch(() => {});
  try {
    // Copy out of SAF first — MediaExtractor is happier with a plain file
    // path, and this matches how the indexer/thumbnailer treat content:// uris.
    const local = await materialize(m.uri, m.name);
    temp.push(local);

    const pcm = await extractAudio(local, AUDIO_MAX_SECONDS);
    if (pcm) temp.push(pcm.path);
    if (!pcm || pcm.samples < AUDIO_MIN_SAMPLES) {
      // No audio track, or too short to contain speech: analyzed, nothing to say.
      // Log it so the next run can attribute empties here (extraction returned
      // null / near-empty PCM) versus the STT-side empties logged below.
      console.log(
        `[stt] empty id=${m.id} reason=${pcm ? 'too-short' : 'no-audio'} samples=${pcm?.samples ?? 0} ${m.name}`
      );
      await setMemeTranscript(m.id, '');
      return 'silent';
    }

    const b64 = await FileSystem.readAsStringAsync(pcm.path, {
      encoding: FileSystem.EncodingType.Base64,
    });
    const waveform = pcmBase64ToWaveform(b64);
    // Breadcrumb right before the native forward: if ExecuTorch segfaults, this is
    // the last line in logcat, naming the clip (and its length) that crashed it.
    // rms/peak let us tell a genuinely silent clip from one the model just failed.
    const { rms, peak } = waveformLevel(waveform);
    console.log(
      `[stt] transcribe id=${m.id} samples=${waveform.length} rms=${rms.toFixed(4)} peak=${peak.toFixed(4)} ${m.name}`
    );
    const res = await stt.transcribe(waveform);
    const raw = res.text ?? '';
    const text = cleanTranscript(raw);
    // Keep raw/stored text visible in logcat for on-device model evaluation.
    if (text) {
      console.log(`[stt] text id=${m.id} ${JSON.stringify(text.slice(0, 200))}`);
    } else {
      console.log(
        `[stt] empty id=${m.id} rms=${rms.toFixed(4)} rawLen=${raw.length} raw=${JSON.stringify(raw.slice(0, 120))} ${m.name}`
      );
    }

    await setMemeTranscript(m.id, text);
    return text ? 'done' : 'silent';
  } catch (e) {
    // Pre-marked failed above; re-assert in case that initial write didn't land,
    // so a caught exception can never leave the row 'pending'. Record why too.
    console.log(`[stt] error id=${m.id} ${String(e)}`);
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

  // Supported react-native-executorch native STT path. The English-only preset
  // must not receive a language option; the module rejects one for `.en` models.
  const stt = useSpeechToText({
    model: models.speech_to_text.whisper_tiny_en(),
    preventLoad: !(hydrated && enabled),
  });

  const setEnabled = (on: boolean) => {
    setEnabledState(on);
    setSetting(AUDIO_ENABLED_KEY, on ? '1' : '0').catch(() => {});
  };

  // Latest hook handle for long-running passes (the hook object changes as its
  // ready/generating state updates).
  const sttRef = useRef(stt);
  sttRef.current = stt;
  const ready = enabled && audioNativeAvailable && stt.isReady;
  const readyRef = useRef(ready);
  readyRef.current = ready;
  const downloadProgress = stt.downloadProgress ?? 0;
  const loadError = stt.error;

  // One pass at a time — Whisper shares the accelerator with other models, and a
  // second pass would race the same queue.
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
        // Whisper shares the accelerator with the CLIP text embed a search needs;
        // stand down between clips so interactive search gets priority.
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
        // useSpeechToText flips `isGenerating` through React state. Yield one
        // macrotask after native completion so the hook renders back to false
        // before the next clip reads sttRef; otherwise a tight loop can hit its
        // transient ModelGenerating guard and falsely mark clip 2+ as failed.
        await new Promise<void>((resolve) => setTimeout(resolve, 0));
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

  // Retranscribe a single video on demand (the per-meme "regenerate" button).
  // Shares busyRef with the bulk pass so the two can't race the same row, and
  // holds the keep-alive for the one clip. requeueMemeAudio clears the stale
  // transcript up front so search reflects the retry even if it comes back empty.
  const regenerateMeme = async (id: number): Promise<RegenerateResult> => {
    if (busyRef.current) return 'busy';
    if (!readyRef.current) return 'not-ready';
    const row = await getMemeForAudio(id);
    if (!row) return 'missing';
    busyRef.current = true;
    setRunning(true);
    const release = acquireKeepAlive('Transcribing a video');
    try {
      await requeueMemeAudio(id);
      const r = await transcribeOne(
        { transcribe: (w) => sttRef.current.transcribe(w) },
        row
      );
      // Transcript changed under the Library's feet — let the grid re-fetch.
      emitLibraryChanged();
      return r;
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
      regenerateMeme,
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
