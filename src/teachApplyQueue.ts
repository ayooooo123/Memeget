type TimerHandle = number;

export interface TeachApplyQueueOptions {
  debounceMs: number;
  retryMs?: number;
  apply: () => Promise<void>;
  isActive?: () => boolean;
  onError?: (error: unknown) => void;
}

export interface TeachApplyQueue {
  request(): void;
  resume(): void;
  cancel(): void;
}

// Coalesces bursts of teach-by-example saves into at most one full-library apply
// pass. A pass already in flight is never duplicated; teaches arriving during it
// arm one trailing pass. If the UI is active (tagging/searching/etc.), the queue
// keeps a cheap retry timer instead of dropping the pending apply.
export function createTeachApplyQueue({ debounceMs, retryMs = debounceMs, apply, isActive, onError }: TeachApplyQueueOptions): TeachApplyQueue {
  let pending = false;
  let running = false;
  let timer: TimerHandle | null = null;

  const active = () => isActive?.() ?? true;

  const clear = () => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
  };

  const schedule = (restartTimer = false) => {
    if (!pending || running) return;
    if (timer) {
      if (!restartTimer) return;
      clear();
    }
    const delay = active() ? debounceMs : retryMs;
    timer = setTimeout(run, delay) as unknown as TimerHandle;
  };

  const run = async () => {
    timer = null;
    if (!pending || running) return;
    if (!active()) {
      schedule();
      return;
    }
    pending = false;
    running = true;
    try {
      await apply();
    } catch (error) {
      onError?.(error);
    } finally {
      running = false;
      schedule();
    }
  };

  return {
    request() {
      pending = true;
      schedule(!running);
    },
    resume() {
      schedule();
    },
    cancel() {
      clear();
    },
  };
}
