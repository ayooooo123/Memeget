import {
  INTERACTIVE_WINDOW_MS,
  interactiveActive,
  noteInteractive,
  yieldToSearch,
} from './interactive';

// `lastInteractive` is module state that persists across tests. Anchor each test
// to a fresh clock far past any earlier stamp so a never-stamped window always
// reads as stale (rather than inheriting a previous test's stamp).
let anchor = 1_000_000;
beforeEach(() => {
  jest.useFakeTimers();
  anchor += 10 * INTERACTIVE_WINDOW_MS;
  jest.setSystemTime(anchor);
});
afterEach(() => {
  jest.useRealTimers();
});

describe('interactive window', () => {
  it('is inactive until a search stamps it', () => {
    expect(interactiveActive()).toBe(false);
  });

  it('is active right after a stamp and expires after the window', () => {
    noteInteractive();
    expect(interactiveActive()).toBe(true);

    jest.advanceTimersByTime(INTERACTIVE_WINDOW_MS - 1);
    expect(interactiveActive()).toBe(true);

    jest.advanceTimersByTime(2);
    expect(interactiveActive()).toBe(false);
  });
});

describe('yieldToSearch', () => {
  it('returns immediately when no search is active', async () => {
    await expect(yieldToSearch()).resolves.toBeUndefined();
  });

  it('bails out the moment a cancel is requested', async () => {
    noteInteractive();
    expect(interactiveActive()).toBe(true);
    // Cancel short-circuits the wait even while the window is active.
    await expect(yieldToSearch(() => true)).resolves.toBeUndefined();
  });

  it('resolves once the interactive window elapses', async () => {
    noteInteractive();
    let done = false;
    const p = yieldToSearch().then(() => {
      done = true;
    });

    // Still within the window: the loop keeps napping.
    await jest.advanceTimersByTimeAsync(INTERACTIVE_WINDOW_MS - 500);
    expect(done).toBe(false);

    // Past the window: the next step sees it inactive and resolves.
    await jest.advanceTimersByTimeAsync(1_000);
    await p;
    expect(done).toBe(true);
  });

  it('is bounded even if the window is continuously re-stamped', async () => {
    noteInteractive();
    let done = false;
    const p = yieldToSearch().then(() => {
      done = true;
    });

    // Keep the window perpetually active; the cap (INTERACTIVE_WINDOW_MS from
    // the call's start) must still let the loop out so a burst can't wedge.
    for (let elapsed = 0; elapsed <= INTERACTIVE_WINDOW_MS + 1_000; elapsed += 250) {
      noteInteractive();
      await jest.advanceTimersByTimeAsync(250);
    }
    await p;
    expect(done).toBe(true);
  });
});
