import { runProgressiveSearch } from './searchCoordinator';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('progressive search', () => {
  it('starts lexical and dense work together, then upgrades the visible results', async () => {
    const lexical = deferred<string[]>();
    const vector = deferred<number[]>();
    const hybrid = deferred<string[]>();
    const visible: string[][] = [];
    const lexicalSearch = jest.fn(() => lexical.promise);
    const embed = jest.fn(() => vector.promise);
    const hybridSearch = jest.fn(() => hybrid.promise);

    const done = runProgressiveSearch({
      lexicalSearch,
      embed,
      hybridSearch,
      publish: (results) => visible.push(results),
      isCurrent: () => true,
    });

    expect(lexicalSearch).toHaveBeenCalledTimes(1);
    expect(embed).toHaveBeenCalledTimes(1);

    lexical.resolve(['lexical']);
    await Promise.resolve();
    expect(visible).toEqual([['lexical']]);

    vector.resolve([1, 0]);
    await Promise.resolve();
    expect(hybridSearch).toHaveBeenCalledWith([1, 0]);
    hybrid.resolve(['hybrid']);
    await done;
    expect(visible).toEqual([['lexical'], ['hybrid']]);
  });

  it('never lets a slower lexical result replace an already-published hybrid result', async () => {
    const lexical = deferred<string[]>();
    const visible: string[][] = [];
    const hybridVisible = deferred<void>();

    const done = runProgressiveSearch({
      lexicalSearch: () => lexical.promise,
      embed: async () => [1],
      hybridSearch: async () => ['hybrid'],
      publish: (results) => {
        visible.push(results);
        if (results[0] === 'hybrid') hybridVisible.resolve();
      },
      isCurrent: () => true,
    });

    await hybridVisible.promise;
    expect(visible).toEqual([['hybrid']]);
    lexical.resolve(['late lexical']);
    await done;

    expect(visible).toEqual([['hybrid']]);
  });

  it('drops both result stages after the query becomes stale', async () => {
    let current = true;
    const lexical = deferred<string[]>();
    const vector = deferred<number[]>();
    const visible: string[][] = [];

    const done = runProgressiveSearch({
      lexicalSearch: () => lexical.promise,
      embed: () => vector.promise,
      hybridSearch: async () => ['hybrid'],
      publish: (results) => visible.push(results),
      isCurrent: () => current,
    });

    current = false;
    lexical.resolve(['lexical']);
    vector.resolve([1]);
    await done;

    expect(visible).toEqual([]);
  });

  it('publishes lexical results when the embedding model is unavailable', async () => {
    const visible: string[][] = [];

    await runProgressiveSearch<string[], number[]>({
      lexicalSearch: async () => ['lexical'],
      publish: (results) => visible.push(results),
      isCurrent: () => true,
    });

    expect(visible).toEqual([['lexical']]);
  });
});
