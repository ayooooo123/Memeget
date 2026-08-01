import { createTagSearchRequest, shouldShowTaughtTags } from './settingsTagCore';

describe('createTagSearchRequest', () => {
  test('normalizes a tag and advances the request nonce', () => {
    expect(createTagSearchRequest('  speedtest  ', 4)).toEqual({ label: 'speedtest', nonce: 5 });
  });

  test('creates a fresh request when the same tag is selected again', () => {
    const first = createTagSearchRequest('speedtest', 0);
    const second = createTagSearchRequest('speedtest', first!.nonce);
    expect(second).toEqual({ label: 'speedtest', nonce: 2 });
  });

  test('ignores an empty tag', () => {
    expect(createTagSearchRequest('   ', 8)).toBeNull();
  });
});

describe('shouldShowTaughtTags', () => {
  test('hides a populated list while collapsed', () => {
    expect(shouldShowTaughtTags(12, false)).toBe(false);
  });

  test('shows a populated list only when expanded', () => {
    expect(shouldShowTaughtTags(12, true)).toBe(true);
    expect(shouldShowTaughtTags(0, true)).toBe(false);
  });
});
