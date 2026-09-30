import { startStallWatch } from './stallWatch';

// A fake clock + scheduler: tasks run only when the test advances time, so a
// "blocked thread" is just time passing without the scheduled tick running.
function harness() {
  let now = 0;
  const queue: { at: number; fn: () => void; handle: number }[] = [];
  let nextHandle = 1;
  const cancelled = new Set<number>();
  return {
    now: () => now,
    schedule: (fn: () => void, ms: number) => {
      const handle = nextHandle++;
      queue.push({ at: now + ms, fn, handle });
      return handle;
    },
    cancel: (handle: unknown) => {
      cancelled.add(handle as number);
    },
    // Jump the clock, then run whatever was due — the gap between "due" and
    // "actually ran" is the stall the watcher should report.
    advance(ms: number) {
      now += ms;
      const due = queue.filter((t) => t.at <= now && !cancelled.has(t.handle));
      queue.length = 0;
      for (const t of due) t.fn();
    },
    pending: () => queue.length,
  };
}

describe('startStallWatch', () => {
  it('says nothing when ticks fire on time', () => {
    const h = harness();
    const stalls: number[] = [];
    startStallWatch({ onStall: (ms) => stalls.push(ms), now: h.now, schedule: h.schedule, cancel: h.cancel });

    for (let i = 0; i < 10; i++) h.advance(500);

    expect(stalls).toEqual([]);
  });

  it('reports how long the thread was blocked', () => {
    const h = harness();
    const stalls: number[] = [];
    startStallWatch({ onStall: (ms) => stalls.push(ms), now: h.now, schedule: h.schedule, cancel: h.cancel });

    h.advance(500); // on time
    h.advance(5_000); // the tick due at 1000 only ran at 5500

    expect(stalls).toEqual([4_500]);
  });

  it('ignores a hitch below the report threshold', () => {
    const h = harness();
    const stalls: number[] = [];
    startStallWatch({ onStall: (ms) => stalls.push(ms), now: h.now, schedule: h.schedule, cancel: h.cancel });

    h.advance(1_400); // 900ms late — a hitch, not a freeze

    expect(stalls).toEqual([]);
  });

  it('measures each stall from the last tick, not from the start', () => {
    const h = harness();
    const stalls: number[] = [];
    startStallWatch({ onStall: (ms) => stalls.push(ms), now: h.now, schedule: h.schedule, cancel: h.cancel });

    h.advance(3_000); // 2500 late
    h.advance(2_000); // 1500 late, measured fresh

    expect(stalls).toEqual([2_500, 1_500]);
  });

  it('ignores a gap the app spent in the background, even though it is active again by the time the overdue tick runs', () => {
    // The shape that produced bogus 28-second and 4-minute "freezes" on device:
    // React Native holds JS timers while the activity is paused and releases
    // them on resume, so the late tick observes a foreground app.
    const h = harness();
    const stalls: number[] = [];
    let activeSince: number | null = 0;
    startStallWatch({
      onStall: (ms) => stalls.push(ms),
      activeSince: () => activeSince,
      now: h.now,
      schedule: h.schedule,
      cancel: h.cancel,
    });

    h.advance(500); // foreground, on time
    activeSince = null; // user leaves
    h.advance(25 * 60_000); // timers suspended with the activity
    activeSince = h.now(); // resumed — and only now do the ticks flush
    h.advance(500);

    expect(stalls).toEqual([]);
  });

  it('still reports a real freeze once the app has been foreground for a full tick', () => {
    const h = harness();
    const stalls: number[] = [];
    let activeSince: number | null = null;
    startStallWatch({
      onStall: (ms) => stalls.push(ms),
      activeSince: () => activeSince,
      now: h.now,
      schedule: h.schedule,
      cancel: h.cancel,
    });

    h.advance(600_000); // backgrounded — not a stall
    activeSince = h.now();
    h.advance(500); // first foreground tick re-baselines, reports nothing
    h.advance(4_000); // now a real 3.5s freeze with the user watching

    expect(stalls).toEqual([3_500]);
  });

  it('reports nothing while the app stays in the background', () => {
    const h = harness();
    const stalls: number[] = [];
    startStallWatch({
      onStall: (ms) => stalls.push(ms),
      activeSince: () => null,
      now: h.now,
      schedule: h.schedule,
      cancel: h.cancel,
    });

    h.advance(30_000);
    h.advance(30_000);

    expect(stalls).toEqual([]);
  });

  it('stops scheduling once stopped', () => {
    const h = harness();
    const stalls: number[] = [];
    const stop = startStallWatch({
      onStall: (ms) => stalls.push(ms),
      now: h.now,
      schedule: h.schedule,
      cancel: h.cancel,
    });

    stop();
    h.advance(10_000);

    expect(stalls).toEqual([]);
    expect(h.pending()).toBe(0);
  });
});
