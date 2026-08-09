import { hashFileSample } from './contentHash';

describe('hashFileSample', () => {
  it('is stable: identical (length, windows) hash identically', () => {
    expect(hashFileSample(1024, ['aGVsbG8='])).toBe(hashFileSample(1024, ['aGVsbG8=']));
  });

  it('separates content that differs within the same length', () => {
    expect(hashFileSample(8, ['AAAAAAAA'])).not.toBe(hashFileSample(8, ['AAAAAAAB']));
  });

  it('separates files of different byte length (length is part of the key)', () => {
    expect(hashFileSample(4, ['AAAA'])).not.toBe(hashFileSample(8, ['AAAA']));
    // The length prefix means two files of different size can never collide,
    // even when their sampled windows are byte-for-byte identical.
    expect(hashFileSample(4, ['AAAA']).split('.')[0]).not.toBe(
      hashFileSample(8, ['AAAA']).split('.')[0]
    );
  });

  it('folds every window in, so an edit to any region moves the hash', () => {
    const head = hashFileSample(1000, ['HEAD', 'MID', 'TAIL']);
    expect(hashFileSample(1000, ['HEADx', 'MID', 'TAIL'])).not.toBe(head);
    expect(hashFileSample(1000, ['HEAD', 'MIDx', 'TAIL'])).not.toBe(head);
    expect(hashFileSample(1000, ['HEAD', 'MID', 'TAILx'])).not.toBe(head);
  });

  it('handles a zero-length file with no windows without NaN or throwing', () => {
    const h = hashFileSample(0, []);
    expect(typeof h).toBe('string');
    expect(h).not.toMatch(/NaN/);
  });

  it('produces a compact "length.hash" shape', () => {
    expect(hashFileSample(4, ['AAAA'])).toMatch(/^[0-9a-z]+\.[0-9a-z]+$/);
  });
});
