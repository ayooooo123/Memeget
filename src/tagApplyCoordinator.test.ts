import { applyTagResponsively } from './tagApplyCoordinator';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('responsive tag apply', () => {
  it('finishes the durable UI step without waiting for look-alike propagation', async () => {
    const spread = deferred<number>();
    const events: string[] = [];

    await applyTagResponsively({
      persist: async () => events.push('persisted'),
      onPersisted: () => events.push('closed'),
      propagate: () => spread.promise,
      onPropagated: (count) => events.push(`spread:${count}`),
    });

    expect(events).toEqual(['persisted', 'closed']);
    spread.resolve(4);
    await Promise.resolve();
    await Promise.resolve();
    expect(events).toEqual(['persisted', 'closed', 'spread:4']);
  });

  it('keeps propagation failure separate from the durable tag write', async () => {
    const onPropagationError = jest.fn();

    await applyTagResponsively({
      persist: async () => {},
      onPersisted: () => {},
      propagate: async () => {
        throw new Error('scan failed');
      },
      onPropagated: () => {},
      onPropagationError,
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(onPropagationError).toHaveBeenCalledWith(expect.objectContaining({ message: 'scan failed' }));
  });
});
