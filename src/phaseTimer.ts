// Phase timing for the paths that freeze.
//
// `stallWatch` says HOW LONG the JS thread was blocked; this says WHICH step was
// running. Together they turn "the app froze when I shared a link with a meme
// open" into a line naming the phase and its duration.
//
// Only slow phases print. A share touches a dozen steps and logging every one
// would bury the one that matters — and logging is not free on the thread we are
// trying to keep unblocked.

export interface PhaseLog {
  // Runs `work`, returning its result untouched. Logs only when it was slow, and
  // logs on failure too: a phase that blocks and then throws is exactly the
  // shape we are hunting.
  time<T>(phase: string, work: () => Promise<T>): Promise<T>;
}

export interface PhaseLogOptions {
  slowMs?: number;
  log?: (line: string) => void;
  now?: () => number;
}

// Anything under this is not what a person notices, and the share path has
// several sub-second steps that would only add noise.
export const PHASE_SLOW_MS = 400;

export function createPhaseLog(scope: string, options: PhaseLogOptions = {}): PhaseLog {
  const slowMs = options.slowMs ?? PHASE_SLOW_MS;
  const log = options.log ?? ((line: string) => console.log(line));
  const now = options.now ?? Date.now;

  return {
    async time(phase, work) {
      const started = now();
      try {
        const result = await work();
        const ms = Math.round(now() - started);
        if (ms >= slowMs) log(`[memeget/${scope}] ${phase} took ${ms}ms`);
        return result;
      } catch (error) {
        const ms = Math.round(now() - started);
        const reason = String((error as Error)?.message ?? error).slice(0, 120);
        if (ms >= slowMs) log(`[memeget/${scope}] ${phase} failed after ${ms}ms: ${reason}`);
        throw error;
      }
    },
  };
}
