let mockNative: {
  probeMedia?(source: string): Promise<unknown>;
  measureMemeTextLayout?(
    text: string,
    fontFamily: string,
    fontWeight: number,
    fontSizeDip: number,
    lineHeightDip: number,
    letterSpacingEm: number,
    widthDip: number,
    align: string
  ): Promise<unknown>;
  detectTextRegions?(source: string): Promise<unknown>;
  sampleImageBorderColor?(
    source: string,
    x: number,
    y: number,
    width: number,
    height: number
  ): Promise<unknown>;
  sampleImagePixelGrid?(
    source: string,
    x: number,
    y: number,
    width: number,
    height: number,
    pixelSize: number
  ): Promise<unknown>;
} | null = null;

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

describe('meme text layout DIP bridge', () => {
  beforeEach(() => {
    jest.resetModules();
    mockNative = null;
  });

  test('passes density-independent values to native without JS scaling', async () => {
    const metrics = {
      widthDip: 320,
      heightDip: 91,
      includeFontPadding: false,
      toleranceDip: 2,
      lines: [{ text: 'ÁG py', start: 0, end: 5, widthDip: 88, topDip: 0, baselineDip: 46 }],
    };
    const nativeMeasure = jest.fn().mockResolvedValue(metrics);
    mockNative = { measureMemeTextLayout: nativeMeasure };
    const { measureMemeTextLayout } = await import('./index');

    await expect(measureMemeTextLayout({
      text: 'ÁG py',
      fontFamily: 'Anton',
      fontWeight: 900,
      fontSizeDip: 48,
      lineHeightDip: 45.6,
      letterSpacingEm: 0.018,
      widthDip: 320,
      align: 'center',
    })).resolves.toEqual(metrics);
    expect(nativeMeasure).toHaveBeenCalledWith('ÁG py', 'Anton', 900, 48, 45.6, 0.018, 320, 'center');
  });
});

describe('image text detector and border sampler bridge', () => {
  beforeEach(() => {
    jest.resetModules();
    mockNative = null;
  });

  test('returns null only when the optional native methods are absent', async () => {
    const { detectTextRegions, sampleImageBorderColor, sampleImagePixelGrid } = await import('./index');

    await expect(detectTextRegions('file:///source.jpg')).resolves.toBeNull();
    await expect(
      sampleImageBorderColor('file:///source.jpg', { x: 0.1, y: 0.2, width: 0.3, height: 0.4 })
    ).resolves.toBeNull();
    await expect(
      sampleImagePixelGrid('file:///source.jpg', { x: 0, y: 0, width: 1, height: 1 }, 8)
    ).resolves.toBeNull();
  });

  test('passes normalized inputs through and propagates real native failures', async () => {
    const detection = {
      sourceWidth: 120,
      sourceHeight: 80,
      rotation: 90,
      languages: ['en'],
      blocks: [],
    };
    const sample = { hex: '#123456', sampleCount: 42 };
    const detector = jest.fn().mockResolvedValue(detection);
    const sampler = jest.fn().mockResolvedValueOnce(sample).mockRejectedValueOnce(new Error('decode failed'));
    const pixelGrid = { rows: 1, columns: 2, colors: ['#FFFF0000', '#FF0000FF'] };
    const gridSampler = jest.fn().mockResolvedValue(pixelGrid);
    mockNative = { detectTextRegions: detector, sampleImageBorderColor: sampler, sampleImagePixelGrid: gridSampler };
    const { detectTextRegions, sampleImageBorderColor, sampleImagePixelGrid } = await import('./index');

    await expect(detectTextRegions('content://image')).resolves.toEqual(detection);
    await expect(
      sampleImageBorderColor('content://image', { x: 0.1, y: 0.2, width: 0.3, height: 0.4 })
    ).resolves.toEqual(sample);
    await expect(
      sampleImageBorderColor('content://broken', { x: 0, y: 0, width: 1, height: 1 })
    ).rejects.toThrow('decode failed');
    await expect(
      sampleImagePixelGrid('content://image', { x: 0.1, y: 0.2, width: 0.3, height: 0.4 }, 12)
    ).resolves.toEqual(pixelGrid);
    expect(detector).toHaveBeenCalledWith('content://image');
    expect(sampler).toHaveBeenNthCalledWith(1, 'content://image', 0.1, 0.2, 0.3, 0.4);
    expect(gridSampler).toHaveBeenCalledWith('content://image', 0.1, 0.2, 0.3, 0.4, 12);
  });
});
