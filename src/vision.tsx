import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useLLM } from 'react-native-executorch';

import { getSetting, setSetting } from './db';
import {
  backfillCaptionEmbeddings,
  backfillVisualEmbeddings,
  heavyPassActive,
  enrichLibrary,
  enrichNextMeme,
  type EnrichProgress,
  type EnrichResult,
  type VisionEnricher,
} from './indexer';
import {
  bgNativeAvailable,
  getPower,
  startKeepAlive,
  stopKeepAlive,
  type NativePower,
} from '../modules/memeget-bg';
import {
  bgIntervalMs,
  parseVision,
  powerBlockReason,
  userTurn,
  BG_ENABLED_KEY,
  BG_INTENSITY_KEY,
  BG_ONLY_CHARGING_KEY,
  BG_PAUSE_HOT_KEY,
  BG_PAUSE_LOW_KEY,
  DEFAULT_QUALITY,
  ENABLED_KEY,
  MODEL,
  POWER_CACHE_MS,
  QUALITY_KEY,
  SYSTEM_PROMPT,
  type BgThrottles,
  type VisionQuality,
  type VisionResult,
} from './visionCore';
import { registerBackgroundDescribe, unregisterBackgroundDescribe } from './backgroundTask';
import { useEmbeddings } from './embeddings';

// Re-export the pure helpers/types screens import from this module.
export { memesPerHour, intensityLabel } from './visionCore';
export type { VisionQuality, VisionResult, BgThrottles } from './visionCore';

// Gemma 4 E2B (multimodal), on-device, via ExecuTorch — the SAME runtime that
// already runs CLIP, so there's no second engine to ship. Used purely as an
// *enrichment* pass: CLIP stays the fast embedding/similarity + teach-by-example
// backbone; the VLM reads each meme and writes back a human caption, the literal
// text, and open-vocabulary tags CLIP's fixed 97-label vocabulary can never
// produce. A smaller LFM2.5-VL 450M stays available as the "fast" tier.
//
// This module owns the FOREGROUND path (the React hook + in-app paced loop).
// The headless, OS-scheduled background path lives in backgroundTask.ts and
// shares the pure pieces in visionCore.ts.

export interface VisionApi {
  enabled: boolean; // user has opted in (model is allowed to download/load)
  quality: VisionQuality;
  ready: boolean; // model loaded and able to describe
  progress: number; // 0..1 model download/load progress
  busy: boolean; // a generation is currently running
  error: string | null;
  setEnabled: (on: boolean) => void;
  setQuality: (q: VisionQuality) => void;
  describe: (jpegPath: string, ocrHint?: string) => Promise<VisionResult | null>;

  // Background processing — a paced trickle that describes the library while the
  // app is open, throttled by the intensity slider and battery/thermal state.
  backgroundEnabled: boolean;
  backgroundIntensity: number; // 0..1
  running: boolean; // an enrichment pass (burst or background tick) is active
  pausedReason: string | null; // non-null when a throttle is holding work
  nativeBackgroundAvailable: boolean; // power/keep-alive native module is built
  throttles: BgThrottles;
  setBackgroundEnabled: (on: boolean) => void;
  setBackgroundIntensity: (v: number) => void;
  setThrottle: (key: keyof BgThrottles, value: boolean) => void;
  // Burst path for the "Describe N now" button — mutex-guarded so it can never
  // run concurrently with the background loop (one accelerator, one generation).
  // Resolves to 'busy' if another pass already holds the lock.
  runEnrichment: (
    opts?: { onProgress?: (p: EnrichProgress) => void; shouldCancel?: () => boolean }
  ) => Promise<EnrichResult | 'busy'>;
}

const Ctx = createContext<VisionApi | null>(null);

export function VisionProvider({ children }: { children: React.ReactNode }) {
  const embeddings = useEmbeddings();
  const [enabled, setEnabledState] = useState(false);
  const [quality, setQualityState] = useState<VisionQuality>(DEFAULT_QUALITY);
  const [bgEnabled, setBgEnabledState] = useState(false);
  const [bgIntensity, setBgIntensityState] = useState(0.25);
  const [throttles, setThrottles] = useState<BgThrottles>({
    onlyWhileCharging: false,
    pauseWhenHot: true,
    pauseOnLowBattery: true,
  });
  const [pausedReason, setPausedReason] = useState<string | null>(null);
  const [hydrated, setHydrated] = useState(false);
  const [running, setRunning] = useState(false);
  // Latest throttle prefs for the loop closure (avoids re-arming on every edit).
  const throttlesRef = useRef(throttles);
  throttlesRef.current = throttles;
  // Cached battery/thermal snapshot so we don't poll native every tick.
  const powerRef = useRef<{ at: number; value: NativePower | null }>({ at: 0, value: null });

  // Load persisted preferences once. Until hydrated we keep the model from
  // loading (preventLoad) so a re-install never auto-downloads ~hundreds of MB.
  useEffect(() => {
    (async () => {
      const [en, q, bg, bi, oc, ph, pl] = await Promise.all([
        getSetting(ENABLED_KEY),
        getSetting(QUALITY_KEY),
        getSetting(BG_ENABLED_KEY),
        getSetting(BG_INTENSITY_KEY),
        getSetting(BG_ONLY_CHARGING_KEY),
        getSetting(BG_PAUSE_HOT_KEY),
        getSetting(BG_PAUSE_LOW_KEY),
      ]);
      if (q === 'max' || q === 'fast') setQualityState(q);
      setEnabledState(en === '1');
      setBgEnabledState(bg === '1');
      const parsed = bi != null ? Number(bi) : NaN;
      if (Number.isFinite(parsed)) setBgIntensityState(Math.max(0, Math.min(1, parsed)));
      // Defaults apply when a key was never written (null).
      setThrottles({
        onlyWhileCharging: oc === '1',
        pauseWhenHot: ph !== '0',
        pauseOnLowBattery: pl !== '0',
      });
      setHydrated(true);
    })().catch(() => setHydrated(true));
  }, []);

  const llm = useLLM({ model: MODEL[quality], preventLoad: !(hydrated && enabled) });

  const setEnabled = (on: boolean) => {
    setEnabledState(on);
    setSetting(ENABLED_KEY, on ? '1' : '0').catch(() => {});
  };
  const setQuality = (q: VisionQuality) => {
    setQualityState(q);
    setSetting(QUALITY_KEY, q).catch(() => {});
  };
  const setBackgroundEnabled = (on: boolean) => {
    setBgEnabledState(on);
    setSetting(BG_ENABLED_KEY, on ? '1' : '0').catch(() => {});
  };
  const setBackgroundIntensity = (v: number) => {
    const c = Math.max(0, Math.min(1, v));
    setBgIntensityState(c);
    setSetting(BG_INTENSITY_KEY, String(c)).catch(() => {});
  };
  const setThrottle = (key: keyof BgThrottles, value: boolean) => {
    setThrottles((cur) => ({ ...cur, [key]: value }));
    const storageKey =
      key === 'onlyWhileCharging'
        ? BG_ONLY_CHARGING_KEY
        : key === 'pauseWhenHot'
          ? BG_PAUSE_HOT_KEY
          : BG_PAUSE_LOW_KEY;
    setSetting(storageKey, value ? '1' : '0').catch(() => {});
  };

  // Cached battery/thermal read so the loop doesn't poll native every tick.
  const readPower = (): NativePower | null => {
    const now = Date.now();
    if (now - powerRef.current.at < POWER_CACHE_MS) return powerRef.current.value;
    const value = getPower();
    powerRef.current = { at: now, value };
    return value;
  };

  // The describe primitive. Kept in a ref so the background loop / burst pass
  // always call the latest version (llm gets a new identity each render).
  const describe = async (jpegPath: string, ocrHint?: string): Promise<VisionResult | null> => {
    if (!llm.isReady) return null;
    // Stateless one-shot: generate() does NOT accumulate conversation history,
    // so every meme is described from a clean slate (no drift, no unbounded
    // context growth across a whole library).
    const reply = await llm.generate([
      { role: 'system', content: SYSTEM_PROMPT },
      { role: 'user', content: userTurn(ocrHint), mediaPath: jpegPath },
    ]);
    return parseVision(reply);
  };
  const enricherRef = useRef<VisionEnricher>({ ready: false, describe });
  enricherRef.current = {
    ready: enabled && llm.isReady,
    describe,
    embedText: embeddings.ready ? embeddings.embedText : undefined,
  };

  useEffect(() => {
    if (!embeddings.ready) return;
    let cancelled = false;
    const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
    const loop = async () => {
      while (!cancelled) {
        // Idle work: stand down whenever indexing/re-tagging holds the device.
        if (heavyPassActive()) {
          await sleep(10_000);
          continue;
        }
        const n = await backfillCaptionEmbeddings(embeddings, { limit: 20 }).catch(() => 0);
        if (n === 0) break;
        await sleep(500);
      }
    };
    loop();
    return () => {
      cancelled = true;
    };
  }, [embeddings.ready]);

  useEffect(() => {
    if (!embeddings.visualReady || !embeddings.embedVisualImage) return;
    let cancelled = false;
    const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
    const loop = async () => {
      while (!cancelled) {
        // DINO is the heaviest model in the app and this loop has all day:
        // stand down completely while any heavy pass runs (the un-gated loop is
        // what starved "Preparing to index…" for minutes), and take a real
        // breather between batches even when idle.
        if (heavyPassActive()) {
          await sleep(10_000);
          continue;
        }
        const n = await backfillVisualEmbeddings(embeddings, { limit: 5 }).catch(() => 0);
        if (cancelled) break;
        // Drained → poll slowly so memes indexed later this session still get
        // their DINO vectors without an app restart.
        await sleep(n === 0 ? 60_000 : 2_000);
      }
    };
    loop();
    return () => {
      cancelled = true;
    };
  }, [embeddings.visualReady, embeddings.embedVisualImage]);

  // One generation at a time on a single accelerator: this mutex makes the
  // background trickle and the manual burst mutually exclusive. A blocked caller
  // gets 'busy' rather than racing the model.
  const busyRef = useRef(false);
  const runGuarded = async <T,>(fn: () => Promise<T>): Promise<T | 'busy'> => {
    if (busyRef.current) return 'busy';
    busyRef.current = true;
    setRunning(true);
    try {
      return await fn();
    } finally {
      busyRef.current = false;
      setRunning(false);
    }
  };

  const runEnrichment = (
    opts?: { onProgress?: (p: EnrichProgress) => void; shouldCancel?: () => boolean }
  ) => runGuarded(() => enrichLibrary(enricherRef.current, opts ?? {}));

  // Paced background loop: describe one pending meme, wait the interval implied
  // by the intensity slider, repeat. Only alive while the app is open (a true
  // OS-level background service needs the native module — see the research
  // notes). Re-runs whenever readiness or intensity changes.
  useEffect(() => {
    if (!(hydrated && bgEnabled && enabled && llm.isReady)) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const interval = bgIntervalMs(bgIntensity);

    const loop = async () => {
      if (cancelled) return;

      // Battery/thermal gate first — cheaper than describing, and the whole
      // point of running in the background politely.
      const block = powerBlockReason(readPower(), throttlesRef.current);
      if (block) {
        setPausedReason(block);
        timer = setTimeout(loop, 30_000); // re-check conditions soon
        return;
      }
      setPausedReason(null);

      // Stand down while the user is actively searching or a heavy pass runs —
      // a generation can't be preempted once started, so at least don't start
      // one under the user's fingers.
      if (heavyPassActive()) {
        timer = setTimeout(loop, 3_000);
        return;
      }

      let status: 'done' | 'deduped' | 'failed' | 'empty' | 'busy' = 'busy';
      try {
        status = await runGuarded(() => enrichNextMeme(enricherRef.current));
      } catch {
        status = 'failed';
      }
      if (cancelled) return;
      // empty → poll slowly for newly-indexed memes; busy → a burst holds the
      // lock, retry soon; otherwise pace by the chosen throughput.
      const delay = status === 'empty' ? 60_000 : status === 'busy' ? 3_000 : interval;
      timer = setTimeout(loop, delay);
    };
    timer = setTimeout(loop, 2_000); // small settle delay after becoming ready
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
      setPausedReason(null);
    };
  }, [hydrated, bgEnabled, enabled, llm.isReady, bgIntensity]);

  // Keep-alive foreground service (Android): hold the process alive while
  // background mode is active so the in-app loop survives backgrounding. No-op
  // without the native module. On iOS this only buys a short extension.
  useEffect(() => {
    if (!(hydrated && bgEnabled && enabled && llm.isReady)) return;
    startKeepAlive('Memeget', 'Describing your memes in the background');
    return () => stopKeepAlive();
  }, [hydrated, bgEnabled, enabled, llm.isReady]);

  // OS-scheduled background task (WorkManager / BGTaskScheduler): runs the model
  // HEADLESSLY (no React) when the app isn't open, via headlessVision.ts. Toggle
  // registration with background mode; the task itself re-checks settings and
  // throttles before doing anything.
  useEffect(() => {
    if (!hydrated) return;
    if (bgEnabled && enabled) registerBackgroundDescribe();
    else unregisterBackgroundDescribe();
  }, [hydrated, bgEnabled, enabled]);

  const api = useMemo<VisionApi>(() => {
    return {
      enabled,
      quality,
      ready: enabled && llm.isReady,
      progress: llm.downloadProgress ?? 0,
      busy: llm.isGenerating,
      error: llm.error ? String(llm.error.message ?? llm.error) : null,
      setEnabled,
      setQuality,
      describe,
      backgroundEnabled: bgEnabled,
      backgroundIntensity: bgIntensity,
      running,
      pausedReason,
      nativeBackgroundAvailable: bgNativeAvailable,
      throttles,
      setBackgroundEnabled,
      setBackgroundIntensity,
      setThrottle,
      runEnrichment,
    };
    // llm identity changes as state updates; depend on the fields we read.
  }, [
    enabled,
    quality,
    bgEnabled,
    bgIntensity,
    throttles,
    pausedReason,
    running,
    hydrated,
    llm.isReady,
    llm.isGenerating,
    llm.downloadProgress,
    llm.error,
  ]);

  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useVision(): VisionApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useVision must be used inside <VisionProvider>');
  return ctx;
}
