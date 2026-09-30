import { flattenDetectedTextRegions } from './memeImageEditCore';
import { MIN_OCR_SCORE, ocrDetectionsToText, ocrDetectionsToTextResult, type OcrDetection } from './ocrCore';

const expectRect = (actual: { x: number; y: number; width: number; height: number }, expected: typeof actual) => {
  for (const key of ['x', 'y', 'width', 'height'] as const) expect(actual[key]).toBeCloseTo(expected[key], 9);
};

const det = (text: string, x1: number, y1: number, x2: number, y2: number, score = 0.9): OcrDetection => ({
  text,
  bbox: { x1, y1, x2, y2 },
  score,
});

// Classic top/bottom caption meme, detections returned out of order.
const TOP_BOTTOM = [
  det('HOLD', 300, 900, 420, 960),
  det('ONE', 20, 10, 110, 70),
  det('DOES', 240, 14, 360, 72),
  det('MY', 180, 905, 280, 958),
  det('NOT', 130, 12, 220, 68),
  det('BEER', 440, 902, 560, 962),
];

describe('ocrDetectionsToText', () => {
  it('rebuilds reading order: lines top to bottom, words left to right', () => {
    expect(ocrDetectionsToText(TOP_BOTTOM)).toBe('ONE NOT DOES MY HOLD BEER');
  });

  it('keeps words on one line when their boxes are offset vertically by less than half a box', () => {
    const wavy = [det('b', 60, 18, 100, 58), det('a', 0, 0, 40, 40), det('c', 120, 5, 160, 45)];
    expect(ocrDetectionsToText(wavy)).toBe('a b c');
  });

  it('drops low-confidence and empty recognitions', () => {
    const noisy = [
      det('real', 0, 0, 50, 20),
      det('~#', 60, 0, 90, 20, MIN_OCR_SCORE - 0.01),
      det('   ', 100, 0, 120, 20),
    ];
    expect(ocrDetectionsToText(noisy)).toBe('real');
  });

  it('returns empty text for an image with no text', () => {
    expect(ocrDetectionsToText([])).toBe('');
  });
});

describe('ocrDetectionsToTextResult', () => {
  const frame = { width: 600, height: 1000, sourceWidth: 1200, sourceHeight: 2000, rotation: 90 as const };

  it('gives the editor one candidate per word, normalized to the OCR frame, in reading order', () => {
    const regions = flattenDetectedTextRegions(ocrDetectionsToTextResult(TOP_BOTTOM, frame));
    expect(regions.map((r) => r.text)).toEqual(['ONE', 'NOT', 'DOES', 'MY', 'HOLD', 'BEER']);
    expectRect(regions[0].rect, { x: 20 / 600, y: 10 / 1000, width: 90 / 600, height: 60 / 1000 });
    expect(regions.every((r) => r.source === 'element')).toBe(true);
  });

  it('clips boxes that spill past the image and passes the source frame through', () => {
    const result = ocrDetectionsToTextResult([det('EDGE', 550, -10, 700, 40)], frame);
    const [region] = flattenDetectedTextRegions(result);
    expectRect(region.rect, { x: 550 / 600, y: 0, width: 50 / 600, height: 40 / 1000 });
    expect(result).toMatchObject({ sourceWidth: 1200, sourceHeight: 2000, rotation: 90 });
  });

  it('groups a line into one block whose box spans its words', () => {
    const result = ocrDetectionsToTextResult(TOP_BOTTOM, frame);
    expect(result.blocks).toHaveLength(2);
    expect(result.blocks[1].text).toBe('MY HOLD BEER');
    expect(result.blocks[1].box).toEqual({ x: 180 / 600, y: 900 / 1000, width: 380 / 600, height: 62 / 1000 });
  });
});
