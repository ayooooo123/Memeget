import { createPhaseLog } from './phaseTimer';

function fixture(slowMs?: number) {
  let now = 0;
  const lines: string[] = [];
  const log = createPhaseLog('share', {
    slowMs,
    log: (line) => lines.push(line),
    now: () => now,
  });
  return { lines, log, advance: (ms: number) => (now += ms) };
}

describe('createPhaseLog', () => {
  it('stays quiet for a fast phase', async () => {
    const f = fixture();
    await f.log.time('save', async () => f.advance(120));
    expect(f.lines).toEqual([]);
  });

  it('names the phase and its duration when it is slow', async () => {
    const f = fixture();
    await f.log.time('resolve-link', async () => f.advance(9_400));
    expect(f.lines).toEqual(['[memeget/share] resolve-link took 9400ms']);
  });

  it('passes the result through untouched', async () => {
    const f = fixture();
    await expect(f.log.time('save', async () => 'saved')).resolves.toBe('saved');
  });

  it('reports a slow phase that then failed, and rethrows', async () => {
    const f = fixture();
    await expect(
      f.log.time('download', async () => {
        f.advance(5_000);
        throw new Error('network died');
      })
    ).rejects.toThrow('network died');
    expect(f.lines).toEqual(['[memeget/share] download failed after 5000ms: network died']);
  });

  it('honours a custom threshold', async () => {
    const f = fixture(10_000);
    await f.log.time('save', async () => f.advance(9_000));
    expect(f.lines).toEqual([]);
  });
});
