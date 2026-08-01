import { createTeachApplyQueue } from './teachApplyQueue';

beforeEach(() => {
  jest.useFakeTimers();
});

afterEach(() => {
  jest.useRealTimers();
});

describe('teach apply queue', () => {
  it('coalesces rapid teach requests into one delayed apply', async () => {
    const apply = jest.fn(async () => {});
    const queue = createTeachApplyQueue({ debounceMs: 250, apply });

    queue.request();
    queue.request();
    queue.request();

    jest.advanceTimersByTime(249);
    await Promise.resolve();
    expect(apply).not.toHaveBeenCalled();

    jest.advanceTimersByTime(1);
    await Promise.resolve();
    expect(apply).toHaveBeenCalledTimes(1);
  });

  it('restarts the debounce timer when another teach arrives before apply starts', async () => {
    const apply = jest.fn(async () => {});
    const queue = createTeachApplyQueue({ debounceMs: 100, apply });

    queue.request();
    jest.advanceTimersByTime(90);
    queue.request();
    jest.advanceTimersByTime(99);
    await Promise.resolve();
    expect(apply).not.toHaveBeenCalled();

    jest.advanceTimersByTime(1);
    await Promise.resolve();
    expect(apply).toHaveBeenCalledTimes(1);
  });

  it('runs one trailing apply when new teaches arrive during an apply', async () => {
    let finishFirst!: () => void;
    const apply = jest
      .fn<Promise<void>, []>()
      .mockImplementationOnce(() => new Promise<void>((resolve) => (finishFirst = resolve)))
      .mockResolvedValue(undefined);
    const queue = createTeachApplyQueue({ debounceMs: 100, apply });

    queue.request();
    jest.advanceTimersByTime(100);
    await Promise.resolve();
    expect(apply).toHaveBeenCalledTimes(1);

    queue.request();
    queue.request();
    jest.advanceTimersByTime(500);
    await Promise.resolve();
    expect(apply).toHaveBeenCalledTimes(1);

    finishFirst();
    await Promise.resolve();
    jest.advanceTimersByTime(100);
    await Promise.resolve();
    expect(apply).toHaveBeenCalledTimes(2);
  });

  it('defers pending applies while inactive and resumes with one apply', async () => {
    let active = false;
    const apply = jest.fn(async () => {});
    const queue = createTeachApplyQueue({ debounceMs: 100, apply, isActive: () => active });

    queue.request();
    jest.advanceTimersByTime(500);
    await Promise.resolve();
    expect(apply).not.toHaveBeenCalled();

    active = true;
    queue.resume();
    jest.advanceTimersByTime(100);
    await Promise.resolve();
    expect(apply).toHaveBeenCalledTimes(1);
  });

  it('keeps retrying a pending apply until work is allowed', async () => {
    let active = false;
    const apply = jest.fn(async () => {});
    const queue = createTeachApplyQueue({ debounceMs: 100, retryMs: 50, apply, isActive: () => active });

    queue.request();
    jest.advanceTimersByTime(200);
    await Promise.resolve();
    expect(apply).not.toHaveBeenCalled();

    active = true;
    jest.advanceTimersByTime(50);
    await Promise.resolve();
    expect(apply).toHaveBeenCalledTimes(1);

    jest.advanceTimersByTime(100);
    await Promise.resolve();
    expect(apply).toHaveBeenCalledTimes(1);
  });

  it('reports apply errors and keeps later teaches runnable', async () => {
    const onError = jest.fn();
    const apply = jest.fn<Promise<void>, []>().mockRejectedValueOnce(new Error('boom')).mockResolvedValue(undefined);
    const queue = createTeachApplyQueue({ debounceMs: 100, apply, onError });

    queue.request();
    jest.advanceTimersByTime(100);
    await Promise.resolve();
    await Promise.resolve();
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'boom' }));

    queue.request();
    jest.advanceTimersByTime(100);
    await Promise.resolve();
    expect(apply).toHaveBeenCalledTimes(2);
  });
});
