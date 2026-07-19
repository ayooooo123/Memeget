import { hashBase64 } from './contentHash';

describe('hashBase64', () => {
  it('is stable: identical bytes hash identically', () => {
    const b64 = 'aGVsbG8gd29ybGQ='; // "hello world"
    expect(hashBase64(b64)).toBe(hashBase64(b64));
  });

  it('separates content that differs at the same length', () => {
    expect(hashBase64('AAAAAAAA')).not.toBe(hashBase64('AAAAAAAB'));
  });

  it('separates content of different lengths (length is part of the key)', () => {
    expect(hashBase64('AAAA')).not.toBe(hashBase64('AAAAAAAA'));
    // The length prefix means a short string can never collide with a long one.
    expect(hashBase64('AAAA').split('.')[0]).not.toBe(hashBase64('AAAAAAAA').split('.')[0]);
  });

  it('handles the empty string without NaN or throwing', () => {
    const h = hashBase64('');
    expect(typeof h).toBe('string');
    expect(h).not.toMatch(/NaN/);
  });

  it('stays stable on a large payload (stride-sampled path) and stays fast', () => {
    // ~4MB of base64 exercises the len > 1_000_000 stride sampling.
    const big = 'QUJDRA'.repeat(700_000);
    const started = Date.now();
    const h1 = hashBase64(big);
    const elapsed = Date.now() - started;
    expect(h1).toBe(hashBase64(big)); // deterministic
    expect(elapsed).toBeLessThan(1000); // sampled, not a full O(n) pass

    // A single flipped byte deep inside a large payload changes the hash when it
    // lands on a sampled position; length always changes when a byte is added.
    const grown = big + 'X';
    expect(hashBase64(grown)).not.toBe(h1);
  });

  it('produces a compact "length.hash" shape', () => {
    expect(hashBase64('AAAA')).toMatch(/^[0-9a-z]+\.[0-9a-z]+$/);
  });
});
