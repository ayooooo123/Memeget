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

interface MemegetBgNative {
  getPower(): NativePower;
  startForeground(title: string, text: string): void;
  stopForeground(): void;
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
