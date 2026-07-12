import { requireOptionalNativeModule } from 'expo-modules-core';

// Power / thermal snapshot used to throttle background description.
export interface NativePower {
  charging: boolean;
  level: number; // battery 0..1, or -1 if unknown
  // Thermal status. Android: 0 none … 4 severe (PowerManager.THERMAL_STATUS_*).
  // iOS: 0 nominal … 3 critical (ProcessInfo.thermalState). -1 if unknown.
  thermal: number;
  // Android predicted thermal headroom 0..1 (1 = imminent throttling), via
  // PowerManager.getThermalHeadroom. -1 when unavailable (iOS, or pre-Android 11).
  headroom: number;
}

// Result of decoding a video's audio track to Whisper-ready PCM.
export interface ExtractedAudio {
  path: string; // file:// path to raw little-endian float32 PCM (16 kHz mono)
  sampleRate: number; // always 16000
  samples: number;
  durationSec: number;
}

interface MemegetBgNative {
  getPower(): NativePower;
  startForeground(title: string, text: string): void;
  stopForeground(): void;
  getModifiedTime(uri: string): number | null;
  extractAudio(source: string, maxSeconds: number): Promise<ExtractedAudio | null>;
  copyFileToClipboard(path: string, label: string): boolean;
}

// Optional on purpose: in Expo Go, in the JS-only dev flow, or before a native
// `expo prebuild`, this resolves to null and every call below no-ops — the app
// runs fine, the background throttles just aren't available yet.
const native = requireOptionalNativeModule<MemegetBgNative>('MemegetBg');

// True once the native module has been built into the app.
export const bgNativeAvailable = native != null;

// Returns null when the native module isn't present (callers treat null as
// "no signal, don't throttle").
export function getPower(): NativePower | null {
  try {
    return native ? native.getPower() : null;
  } catch {
    return null;
  }
}

// Start/stop a foreground service (Android) that keeps the process alive so the
// in-app description loop survives backgrounding. No-op without the native
// module; on iOS it only requests a short background-execution extension.
export function startKeepAlive(title: string, text: string): void {
  try {
    native?.startForeground(title, text);
  } catch {
    // ignore — keep-alive is best-effort
  }
}

export function stopKeepAlive(): void {
  try {
    native?.stopForeground();
  } catch {
    // ignore
  }
}

// Last-modified time (ms since epoch) of a SAF content:// document, read
// straight off its DocumentFile in native code. Returns null when the native
// module isn't built in, the uri is unreadable, or the provider reports no time
// — callers fall back to the index time. This is the reliable source for the
// library's "most recently added first" order: expo-file-system doesn't surface
// modificationTime for SAF documents.
export function getFileModifiedTime(uri: string): number | null {
  try {
    const t = native?.getModifiedTime(uri);
    return typeof t === 'number' && t > 0 ? t : null;
  } catch {
    return null;
  }
}

// Put a whole file (image OR video) on the system clipboard as a content://
// uri, via ClipData in native code — expo-clipboard can only hold base64 image
// data, so this is the only way to copy an entire video. The path must be a
// file:// (or plain) path inside this app's cache or files dir, where the
// bundled FileProvider can serve it; the file must OUTLIVE the clipboard entry
// (don't delete it after copying — the launch-time cache sweep reclaims it).
// Returns false when the native module (or this function — older builds) isn't
// present or the copy failed, so callers can fall back to frame copy.
export function copyFileToClipboard(path: string, label: string): boolean {
  try {
    if (!native || typeof native.copyFileToClipboard !== 'function') return false;
    return native.copyFileToClipboard(path, label) === true;
  } catch {
    return false;
  }
}

// True once the native audio decoder is built into the app — the audio
// transcription feature is unavailable without it (there is no JS decoder for
// AAC/Opus tracks), so the UI gates on this.
export const audioNativeAvailable = native != null && typeof native.extractAudio === 'function';

// Decode a video's first audio track (Android MediaExtractor + MediaCodec in
// native code) to mono 16 kHz float32 PCM on disk. Resolves null when the file
// has no audio track OR when the native module isn't built in — callers treat
// both as "nothing to transcribe" (gate features on audioNativeAvailable to
// tell them apart). Rejects on decode errors.
export async function extractAudio(
  source: string,
  maxSeconds: number
): Promise<ExtractedAudio | null> {
  if (!native || typeof native.extractAudio !== 'function') return null;
  const res = await native.extractAudio(source, maxSeconds);
  return res && typeof res.path === 'string' ? res : null;
}
