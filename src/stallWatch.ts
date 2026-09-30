// Detector for a blocked JS thread.
//
// A frozen RN UI leaves almost no trace. Hermes logs nothing, the native main
// thread keeps pumping, and the only hint in logcat is the input system giving
// up on the gesture ("Motion - Cancel") — which is how a dead tap on the search
// bar's ✕ showed up as 4.5 seconds of silence and nothing else. This turns that
// silence into a number.
//
// An interval scheduled every TICK_MS can only fire late if the thread was busy,
// so its lateness IS the time the UI could not respond. Cheap enough to leave on
// in release: one timer, one subtraction per tick, and it reports only when the
// thread was genuinely stuck.
//
// The one thing that lateness does NOT mean is a freeze: React Native drives JS
// timers off the activity's frame callbacks and stops them at onHostPause, so
// every trip to another app looks from here like a multi-minute block that ends
// the instant the user comes back. Those false reports are worse than no
// detector — they were read as constant freezing and sent an investigation
// chasing paths that were never slow. Hence `activeSince` below: nothing is
// reported unless the app owned the whole gap.
//
// Timers and the clock are injected so this is unit-testable without waiting in
// real time.

export const STALL_TICK_MS = 500;
// Below ~1s a stall is a hitch, not a freeze, and GC pauses alone can eat a few
// hundred ms. Report only what a person would call frozen.
export const STALL_REPORT_MS = 1_000;

export interface StallWatchOptions {
  onStall: (blockedMs: number) => void;
  // When the app most recently came to the foreground, or null if it isn't
  // there now. NOT a plain "is it active?" sample: React Native stops
  // delivering timers while the activity is paused and flushes the overdue ones
  // on resume, so by the time a tick discovers it is 25 minutes late the app is
  // active again and any is-it-active-now check says yes. Reporting needs the
  // app to have been in the foreground for the WHOLE gap, which is what
  // comparing this timestamp against the missed tick's due time establishes.
  activeSince?: () => number | null;
  tickMs?: number;
  reportMs?: number;
  now?: () => number;
  schedule?: (fn: () => void, ms: number) => unknown;
  cancel?: (handle: unknown) => void;
}

// Starts watching; returns a stop function.
export function startStallWatch(options: StallWatchOptions): () => void {
  const tickMs = options.tickMs ?? STALL_TICK_MS;
  const reportMs = options.reportMs ?? STALL_REPORT_MS;
  const now = options.now ?? Date.now;
  const schedule = options.schedule ?? ((fn, ms) => setTimeout(fn, ms));
  // The handle is opaque here: web and RN each hand back their own shape, and
  // this module only ever passes it straight back to the canceller.
  const cancel =
    options.cancel ?? ((handle) => clearTimeout(handle as Parameters<typeof clearTimeout>[0]));
  let stopped = false;
  let handle: unknown = null;
  let expected = now() + tickMs;
  const activeSince = options.activeSince ?? (() => 0);

  const tick = (): void => {
    if (stopped) return;
    const late = now() - expected;
    if (late >= reportMs) {
      const since = activeSince();
      // `expected - tickMs` is when the previous tick ran. Requiring the
      // foreground to predate it means the entire measured gap was time the
      // user was looking at the app.
      if (since !== null && since <= expected - tickMs) options.onStall(Math.round(late));
    }
    expected = now() + tickMs;
    handle = schedule(tick, tickMs);
  };

  handle = schedule(tick, tickMs);
  return () => {
    stopped = true;
    if (handle !== null) cancel(handle);
  };
}
