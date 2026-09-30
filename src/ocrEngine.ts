// On-device OCR without Google Play Services: react-native-executorch's CRAFT
// text detector + CRNN recognizer, the same runtime the app already uses for
// CLIP, Gemma and Whisper. The weights download on first use (like theirs).
//
// ML Kit's text recognizer — what this replaces — is the "unbundled" Play
// Services build: its model is fetched by GMS, so on a phone without Google
// services it never loaded and OCR silently returned nothing. See
// docs/ocr-engine-decision.md.
import * as FileSystem from 'expo-file-system/legacy';
import { OCRModule, OCR_ENGLISH } from 'react-native-executorch';

import { prepareTextDetectionImage } from '../modules/memeget-bg';
import type { DetectedTextResult } from './memeImageEditCore';
import { ocrDetectionsToText, ocrDetectionsToTextResult, type OcrDetection } from './ocrCore';
import { onDemandModel } from './onDemandModel';

const engine = onDemandModel<OCRModule>({
  name: 'ocr',
  load: (onProgress) => OCRModule.fromModelName(OCR_ENGLISH, onProgress),
  // Long enough to stay warm across an index pass (one call per frame), short
  // enough that one editor lookup doesn't pin the weights for the session.
  idleMs: 60_000,
  retryMs: 10 * 60_000,
});

// The engine reads local images by file:// uri only.
function fileUri(pathOrUri: string): string {
  return pathOrUri.startsWith('file://') ? pathOrUri : `file://${pathOrUri}`;
}

// Raw detections for a local image, or null when the OCR model isn't available.
export function recognizeText(imagePath: string): Promise<OcrDetection[] | null> {
  return engine.run((ocr) => ocr.forward(fileUri(imagePath)));
}

// The searchable text of a local JPEG/PNG (a frame the indexer transcoded).
// Never throws: OCR is one signal among several, and a failure here must not
// cost the meme its index row.
export async function extractImageText(imagePath: string): Promise<string> {
  try {
    const detections = await recognizeText(imagePath);
    return detections ? ocrDetectionsToText(detections) : '';
  } catch (e) {
    console.log(`[memeget/ocr] recognition failed: ${String(e).slice(0, 200)}`);
    return '';
  }
}

// Text boxes for the editor, in the EXIF-upright source frame, normalized.
// Null when this build has no native image preparation. Throws when the OCR
// model can't be loaded, so the tool can say why instead of "no text found".
export async function detectTextRegions(source: string): Promise<DetectedTextResult | null> {
  const prepared = await prepareTextDetectionImage(source);
  if (!prepared) return null;
  try {
    const detections = await recognizeText(prepared.uri);
    if (!detections) {
      throw new Error('the text recognition model is not available yet — it downloads on first use, so check the connection and try again');
    }
    return ocrDetectionsToTextResult(detections, prepared);
  } finally {
    await FileSystem.deleteAsync(prepared.uri, { idempotent: true }).catch(() => {});
  }
}
