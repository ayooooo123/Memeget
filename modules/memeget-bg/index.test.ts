let mockNative: { probeMedia(source: string): Promise<unknown> } | null = null;

jest.mock('expo-modules-core', () => ({
  requireOptionalNativeModule: jest.fn(() => mockNative),
}));

const nativeProbeResult = {
  kind: 'image' as const,
  width: 40,
  height: 24,
  rotationDegrees: 90 as const,
  flipX: true,
  flipY: false,
  durationUs: null,
  frameRate: null,
  videoMime: null,
  audioMime: null,
  hasAudio: false,
  seekable: true,
  byteSize: 1_024,
  modifiedTimeMs: null,
  stableId: 'stable-source',
  displayName: 'oriented.jpg',
};

describe('probeMedia bridge', () => {
  beforeEach(() => {
    jest.resetModules();
    mockNative = null;
  });

  test('returns null only when the optional native module is absent', async () => {
    const { probeMedia } = await import('./index');

    await expect(probeMedia('file:///source.jpg')).resolves.toBeNull();
  });

  test('passes through typed native facts and propagates native rejection', async () => {
    const nativeError = new Error('native probe failed');
    const nativeProbe = jest
      .fn<Promise<unknown>, [string]>()
      .mockResolvedValueOnce(nativeProbeResult)
      .mockRejectedValueOnce(nativeError);
    mockNative = { probeMedia: nativeProbe };
    const { probeMedia } = await import('./index');

    await expect(probeMedia('content://provider/image')).resolves.toEqual(nativeProbeResult);
    await expect(probeMedia('content://provider/broken')).rejects.toBe(nativeError);
    expect(nativeProbe).toHaveBeenNthCalledWith(1, 'content://provider/image');
    expect(nativeProbe).toHaveBeenNthCalledWith(2, 'content://provider/broken');
  });
});
