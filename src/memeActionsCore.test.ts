import { compatibleCopyTarget, formatBuildLabel, makeVariationName } from './memeActionsCore';

describe('compatibleCopyTarget', () => {
  test('transcodes WebM videos to real MP4 output', () => {
    expect(compatibleCopyTarget('reaction.webm', 'video')).toEqual({
      transcode: true,
      name: 'reaction.mp4',
      mimeType: 'video/mp4',
    });
  });

  test('keeps already-compatible media unchanged', () => {
    expect(compatibleCopyTarget('reaction.mp4', 'video')).toEqual({
      transcode: false,
      name: 'reaction.mp4',
      mimeType: 'video/mp4',
    });
  });
});

describe('makeVariationName', () => {
  test('creates a distinct filename with the rendered extension', () => {
    expect(makeVariationName('Distracted Boyfriend.WEBM', 'mp4', 1_774_828_923_000)).toBe(
      'Distracted_Boyfriend-variation-20260330-000203.mp4'
    );
  });
});

describe('formatBuildLabel', () => {
  test('shows the application version and native build code', () => {
    expect(formatBuildLabel('0.1.0', '1152')).toBe('Memeget 0.1.0 · build 1152');
  });

  test('does not render missing metadata as undefined', () => {
    expect(formatBuildLabel(null, null)).toBe('Memeget · development build');
  });
});
