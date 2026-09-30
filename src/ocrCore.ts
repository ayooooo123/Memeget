// Pure assembly of on-device OCR output (react-native-executorch's CRAFT
// detector + CRNN recognizer) into the two shapes the app consumes: a flat
// string for indexing/search, and the block → line → element hierarchy the
// editor's text tool reads. No native imports, so it runs under Jest.
//
// The engine returns one detection per text box, in no guaranteed order, with
// pixel coordinates in the image it was given. Reading order is rebuilt here:
// boxes that share a horizontal band form a line, lines go top to bottom, and
// boxes within a line go left to right.
import type { DetectedTextNode, DetectedTextResult } from './memeImageEditCore';
import type { NormalizedPoint, NormalizedRect, QuarterRotation } from './memeEditProjectCore';

export interface OcrDetection {
  bbox: { x1: number; y1: number; x2: number; y2: number };
  text: string;
  score: number;
}

// Recognitions below this confidence are mostly texture read as letters
// (halftone, hair, brick). Dropping them costs little real text and keeps
// junk tokens out of search and the VLM hint.
export const MIN_OCR_SCORE = 0.3;

// Two boxes are on the same line when their vertical extents overlap by at
// least this share of the shorter box.
const SAME_LINE_OVERLAP = 0.5;

interface LineAcc {
  top: number;
  bottom: number;
  detections: OcrDetection[];
}

export function groupOcrLines(detections: readonly OcrDetection[]): OcrDetection[][] {
  const kept = detections
    .filter(
      (d) =>
        d.text.trim().length > 0 &&
        d.score >= MIN_OCR_SCORE &&
        d.bbox.x2 > d.bbox.x1 &&
        d.bbox.y2 > d.bbox.y1
    )
    .sort((a, b) => a.bbox.y1 + a.bbox.y2 - (b.bbox.y1 + b.bbox.y2));
  const lines: LineAcc[] = [];
  for (const d of kept) {
    const line = lines[lines.length - 1];
    if (line) {
      const overlap = Math.min(line.bottom, d.bbox.y2) - Math.max(line.top, d.bbox.y1);
      const shorter = Math.min(line.bottom - line.top, d.bbox.y2 - d.bbox.y1);
      if (overlap >= SAME_LINE_OVERLAP * shorter) {
        line.detections.push(d);
        line.top = Math.min(line.top, d.bbox.y1);
        line.bottom = Math.max(line.bottom, d.bbox.y2);
        continue;
      }
    }
    lines.push({ top: d.bbox.y1, bottom: d.bbox.y2, detections: [d] });
  }
  return lines.map((line) => line.detections.sort((a, b) => a.bbox.x1 - b.bbox.x1));
}

// The searchable text of an image: every kept word in reading order, one
// space apart (the same flat shape the previous engine's output was stored in).
export function ocrDetectionsToText(detections: readonly OcrDetection[]): string {
  return groupOcrLines(detections)
    .map((line) => line.map((d) => d.text.trim()).join(' '))
    .join(' ')
    .trim();
}

function normalizedBox(
  box: OcrDetection['bbox'],
  width: number,
  height: number
): { rect: NormalizedRect; corners: NormalizedPoint[] } | null {
  const left = Math.max(0, Math.min(width, box.x1));
  const top = Math.max(0, Math.min(height, box.y1));
  const right = Math.max(0, Math.min(width, box.x2));
  const bottom = Math.max(0, Math.min(height, box.y2));
  if (right <= left || bottom <= top) return null;
  const rect = {
    x: left / width,
    y: top / height,
    width: (right - left) / width,
    height: (bottom - top) / height,
  };
  const corners = [
    { x: rect.x, y: rect.y },
    { x: rect.x + rect.width, y: rect.y },
    { x: rect.x + rect.width, y: rect.y + rect.height },
    { x: rect.x, y: rect.y + rect.height },
  ];
  return { rect, corners };
}

function node(text: string, box: OcrDetection['bbox'], width: number, height: number): DetectedTextNode {
  const normalized = normalizedBox(box, width, height);
  return {
    text,
    box: normalized?.rect ?? null,
    cornerPoints: normalized?.corners ?? [],
    languages: [],
  };
}

// The editor's text-tool result. `width`/`height` are the pixels of the image
// OCR actually ran on (boxes are measured there); the source fields describe
// the upright original. Each line becomes a block with one line whose elements
// are the individual boxes, which is the level the tool offers for editing.
export function ocrDetectionsToTextResult(
  detections: readonly OcrDetection[],
  frame: { width: number; height: number; sourceWidth: number; sourceHeight: number; rotation: QuarterRotation }
): DetectedTextResult {
  const { width, height } = frame;
  const blocks = groupOcrLines(detections).map((line) => {
    const text = line.map((d) => d.text.trim()).join(' ');
    const union = {
      x1: Math.min(...line.map((d) => d.bbox.x1)),
      y1: Math.min(...line.map((d) => d.bbox.y1)),
      x2: Math.max(...line.map((d) => d.bbox.x2)),
      y2: Math.max(...line.map((d) => d.bbox.y2)),
    };
    const elements = line.map((d) => node(d.text.trim(), d.bbox, width, height));
    return { ...node(text, union, width, height), lines: [{ ...node(text, union, width, height), elements }] };
  });
  return {
    sourceWidth: frame.sourceWidth,
    sourceHeight: frame.sourceHeight,
    rotation: frame.rotation,
    languages: [],
    blocks,
  };
}
