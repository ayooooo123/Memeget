import {
  MEME_TEXT_BOUNDS,
  createMemeTextLayer,
  normalizeMemeTextFontSize,
  normalizeMemeTextWrapWidth,
} from './memeTextLayoutCore';
import type {
  BaseTransform,
  CoverCorrectionKeyframe,
  CoverLayer,
  DrawElement,
  DrawLayer,
  MaskTrackSpec,
  MediaOverlayLayer,
  MemeEditLayer,
  MemeEditProject,
  NormalizedPoint,
  NormalizedRect,
  QuarterRotation,
  RectCorrectionKeyframe,
  SubjectLayer,
  TextLayer,
  TextStyle,
  TransformKeyframe,
} from './memeEditProjectCore';

export const MAX_TEXT_REGION_CANDIDATES = 256;
export const MIN_NORMALIZED_CROP_AREA = 0.0025;
export const MIN_NORMALIZED_CROP_EDGE = 0.05;
export const MAX_TEXT_REPLACE_PIXEL_SIZE = 256;

/**
 * The base transform of the untouched source frame: no rotation, no flip, full
 * crop.
 *
 * Native code always works on the EXIF-oriented, UNCROPPED image — OCR boxes,
 * border samples and subject cutouts all come back in that frame — while project
 * layers and mask tracks live in the cropped, rotated frame the user is looking
 * at. Every conversion between the two goes through `remapNormalizedRect` with
 * this as the other end, so it is stated once here instead of being re-declared
 * by each caller.
 */
export const SOURCE_FRAME_BASE: BaseTransform = Object.freeze({
  rotation: 0,
  flipX: false,
  flipY: false,
  crop: Object.freeze({ x: 0, y: 0, width: 1, height: 1 }),
  outputAspect: 'source',
}) as BaseTransform;

export type ImageCropPreset = BaseTransform['outputAspect'];
export type TextRegionAction = 'cover' | 'pixelate' | 'replace';

export interface ImageDimensions {
  width: number;
  height: number;
}

export interface DetectedTextNode {
  text: string;
  box: NormalizedRect | null;
  cornerPoints: NormalizedPoint[];
  languages: string[];
}

export interface DetectedTextElement extends DetectedTextNode {}

export interface ImageViewRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export type CropHandle = 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right';

export interface DetectedTextLine extends DetectedTextNode {
  elements: DetectedTextElement[];
}

export interface DetectedTextBlock extends DetectedTextNode {
  lines: DetectedTextLine[];
}

export interface DetectedTextResult {
  sourceWidth: number;
  sourceHeight: number;
  rotation: QuarterRotation;
  languages: string[];
  blocks: DetectedTextBlock[];
}

export interface TextRegionCandidate {
  id: string;
  text: string;
  rect: NormalizedRect;
  source: 'block' | 'line' | 'element' | 'manual';
}

export interface TextRegionLayerInput {
  action: TextRegionAction;
  rect: NormalizedRect;
  text: string;
  coverId: string;
  textId: string;
  color: string;
  pixelSize: number;
  /**
   * The look of the text being replaced, when it could be read off the image.
   * Absent means "could not tell" — the caller must not fabricate one.
   */
  inferredStyle?: InferredTextStyle | null;
}

function round(value: number): number {
  const rounded = Math.round(value * 1_000_000_000_000) / 1_000_000_000_000;
  return Object.is(rounded, -0) ? 0 : rounded;
}

function finite(value: number, fallback: number): number {
  return Number.isFinite(value) ? value : fallback;
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}

function clippedUnitRect(rect: NormalizedRect): NormalizedRect | null {
  if (![rect.x, rect.y, rect.width, rect.height].every(Number.isFinite)) return null;
  const left = clamp(rect.x, 0, 1);
  const top = clamp(rect.y, 0, 1);
  const right = clamp(rect.x + rect.width, 0, 1);
  const bottom = clamp(rect.y + rect.height, 0, 1);
  if (right <= left || bottom <= top) return null;
  return {
    x: round(left),
    y: round(top),
    width: round(right - left),
    height: round(bottom - top),
  };
}

export function normalizeFreeCrop(
  crop: NormalizedRect,
  fallback: NormalizedRect = { x: 0, y: 0, width: 1, height: 1 }
): NormalizedRect {
  const safeFallback = clippedUnitRect(fallback) ?? { x: 0, y: 0, width: 1, height: 1 };
  let width = finite(crop.width, safeFallback.width);
  let height = finite(crop.height, safeFallback.height);
  if (width <= 0) width = safeFallback.width;
  if (height <= 0) height = safeFallback.height;
  width = clamp(width, MIN_NORMALIZED_CROP_EDGE, 1);
  height = clamp(height, MIN_NORMALIZED_CROP_EDGE, 1);
  if (width * height < MIN_NORMALIZED_CROP_AREA) {
    const expansion = Math.sqrt(MIN_NORMALIZED_CROP_AREA / (width * height));
    width = clamp(width * expansion, MIN_NORMALIZED_CROP_EDGE, 1);
    height = clamp(height * expansion, MIN_NORMALIZED_CROP_EDGE, 1);
  }
  const x = clamp(finite(crop.x, safeFallback.x), 0, 1 - width);
  const y = clamp(finite(crop.y, safeFallback.y), 0, 1 - height);
  return { x: round(x), y: round(y), width: round(width), height: round(height) };
}

function presetAspect(preset: Exclude<ImageCropPreset, 'source' | 'free'>): number {
  switch (preset) {
    case '1:1': return 1;
    case '4:5': return 4 / 5;
    case '9:16': return 9 / 16;
    case '16:9': return 16 / 9;
  }
}

export function orientedImageDimensions(
  source: ImageDimensions,
  rotation: QuarterRotation
): ImageDimensions {
  const width = Math.max(1, finite(source.width, 1));
  const height = Math.max(1, finite(source.height, 1));
  return rotation === 90 || rotation === 270
    ? { width: height, height: width }
    : { width, height };
}

export function applyCropPreset(
  base: BaseTransform,
  preset: ImageCropPreset,
  source: ImageDimensions
): BaseTransform {
  if (preset === 'free') {
    return { ...base, crop: normalizeFreeCrop(base.crop), outputAspect: 'free' };
  }
  if (preset === 'source') {
    return {
      ...base,
      crop: { x: 0, y: 0, width: 1, height: 1 },
      outputAspect: 'source',
    };
  }
  const oriented = orientedImageDimensions(source, base.rotation);
  const normalizedRatio = presetAspect(preset) * oriented.height / oriented.width;
  const width = normalizedRatio <= 1 ? normalizedRatio : 1;
  const height = normalizedRatio <= 1 ? 1 : 1 / normalizedRatio;
  return {
    ...base,
    crop: {
      x: round((1 - width) / 2),
      y: round((1 - height) / 2),
      width: round(width),
      height: round(height),
    },
    outputAspect: preset,
  };
}

export function visibleImageDimensions(
  source: ImageDimensions,
  base: BaseTransform
): ImageDimensions {
  const oriented = orientedImageDimensions(source, base.rotation);
  const crop = normalizeFreeCrop(base.crop);
  return {
    width: round(oriented.width * crop.width),
    height: round(oriented.height * crop.height),
  };
}

export function sourceFrameForVisibleCrop(
  visibleFrame: ImageViewRect,
  base: BaseTransform
): ImageViewRect {
  const crop = normalizeFreeCrop(base.crop);
  const width = visibleFrame.width / crop.width;
  const height = visibleFrame.height / crop.height;
  return {
    x: round(visibleFrame.x - crop.x * width),
    y: round(visibleFrame.y - crop.y * height),
    width: round(width),
    height: round(height),
  };
}

export function moveCropHandle(
  crop: NormalizedRect,
  handle: CropHandle,
  delta: NormalizedPoint
): NormalizedRect {
  const current = normalizeFreeCrop(crop);
  const right = current.x + current.width;
  const bottom = current.y + current.height;
  const movesLeft = handle === 'top-left' || handle === 'bottom-left';
  const movesTop = handle === 'top-left' || handle === 'top-right';
  const left = movesLeft
    ? clamp(current.x + finite(delta.x, 0), 0, right - MIN_NORMALIZED_CROP_EDGE)
    : current.x;
  const top = movesTop
    ? clamp(current.y + finite(delta.y, 0), 0, bottom - MIN_NORMALIZED_CROP_EDGE)
    : current.y;
  const nextRight = movesLeft
    ? right
    : clamp(right + finite(delta.x, 0), left + MIN_NORMALIZED_CROP_EDGE, 1);
  const nextBottom = movesTop
    ? bottom
    : clamp(bottom + finite(delta.y, 0), top + MIN_NORMALIZED_CROP_EDGE, 1);
  return normalizeFreeCrop({
    x: left,
    y: top,
    width: nextRight - left,
    height: nextBottom - top,
  }, current);
}

export function moveNormalizedRegion(
  rect: NormalizedRect,
  delta: NormalizedPoint
): NormalizedRect {
  const current = clippedUnitRect(rect);
  if (!current) return {
    x: 0,
    y: 0,
    width: MIN_NORMALIZED_CROP_EDGE,
    height: MIN_NORMALIZED_CROP_EDGE,
  };
  return {
    ...current,
    x: round(clamp(current.x + finite(delta.x, 0), 0, 1 - current.width)),
    y: round(clamp(current.y + finite(delta.y, 0), 0, 1 - current.height)),
  };
}

export function resizeNormalizedRegion(
  rect: NormalizedRect,
  delta: NormalizedPoint
): NormalizedRect {
  const current = clippedUnitRect(rect);
  if (!current) return {
    x: 0,
    y: 0,
    width: MIN_NORMALIZED_CROP_EDGE,
    height: MIN_NORMALIZED_CROP_EDGE,
  };
  return {
    ...current,
    width: round(clamp(
      current.width + finite(delta.x, 0),
      MIN_NORMALIZED_CROP_EDGE,
      1 - current.x
    )),
    height: round(clamp(
      current.height + finite(delta.y, 0),
      MIN_NORMALIZED_CROP_EDGE,
      1 - current.y
    )),
  };
}

export function nextQuarterRotation(rotation: QuarterRotation): QuarterRotation {
  switch (rotation) {
    case 0: return 90;
    case 90: return 180;
    case 180: return 270;
    default: return 0;
  }
}

function rotateSourcePoint(point: NormalizedPoint, rotation: QuarterRotation): NormalizedPoint {
  switch (rotation) {
    case 90: return { x: 1 - point.y, y: point.x };
    case 180: return { x: 1 - point.x, y: 1 - point.y };
    case 270: return { x: point.y, y: 1 - point.x };
    default: return point;
  }
}

function unrotateSourcePoint(point: NormalizedPoint, rotation: QuarterRotation): NormalizedPoint {
  switch (rotation) {
    case 90: return { x: point.y, y: 1 - point.x };
    case 180: return { x: 1 - point.x, y: 1 - point.y };
    case 270: return { x: 1 - point.y, y: point.x };
    default: return point;
  }
}

function workingToSource(point: NormalizedPoint, base: BaseTransform): NormalizedPoint {
  const crop = normalizeFreeCrop(base.crop);
  let x = crop.x + point.x * crop.width;
  let y = crop.y + point.y * crop.height;
  if (base.flipX) x = 1 - x;
  if (base.flipY) y = 1 - y;
  return unrotateSourcePoint({ x, y }, base.rotation);
}

function sourceToWorking(point: NormalizedPoint, base: BaseTransform): NormalizedPoint {
  const crop = normalizeFreeCrop(base.crop);
  const rotated = rotateSourcePoint(point, base.rotation);
  const x = base.flipX ? 1 - rotated.x : rotated.x;
  const y = base.flipY ? 1 - rotated.y : rotated.y;
  return {
    x: (x - crop.x) / crop.width,
    y: (y - crop.y) / crop.height,
  };
}

function remapPointRaw(
  point: NormalizedPoint,
  oldBase: BaseTransform,
  newBase: BaseTransform
): NormalizedPoint {
  return sourceToWorking(workingToSource(point, oldBase), newBase);
}

export function remapNormalizedPoint(
  point: NormalizedPoint,
  oldBase: BaseTransform,
  newBase: BaseTransform
): NormalizedPoint | null {
  if (![point.x, point.y].every(Number.isFinite)) return null;
  const mapped = remapPointRaw(point, oldBase, newBase);
  const epsilon = 1e-10;
  if (mapped.x < -epsilon || mapped.y < -epsilon || mapped.x > 1 + epsilon || mapped.y > 1 + epsilon) {
    return null;
  }
  return { x: round(clamp(mapped.x, 0, 1)), y: round(clamp(mapped.y, 0, 1)) };
}

export function remapNormalizedRect(
  rect: NormalizedRect,
  oldBase: BaseTransform,
  newBase: BaseTransform
): NormalizedRect | null {
  const normalized = clippedUnitRect(rect);
  if (!normalized) return null;
  const points = [
    { x: normalized.x, y: normalized.y },
    { x: normalized.x + normalized.width, y: normalized.y },
    { x: normalized.x, y: normalized.y + normalized.height },
    { x: normalized.x + normalized.width, y: normalized.y + normalized.height },
  ].map((point) => remapPointRaw(point, oldBase, newBase));
  const left = Math.max(0, Math.min(...points.map((point) => point.x)));
  const top = Math.max(0, Math.min(...points.map((point) => point.y)));
  const right = Math.min(1, Math.max(...points.map((point) => point.x)));
  const bottom = Math.min(1, Math.max(...points.map((point) => point.y)));
  if (right <= left || bottom <= top) return null;
  return {
    x: round(left),
    y: round(top),
    width: round(right - left),
    height: round(bottom - top),
  };
}

function remapVector(
  vector: NormalizedPoint,
  oldBase: BaseTransform,
  newBase: BaseTransform
): NormalizedPoint {
  const origin = remapPointRaw({ x: 0, y: 0 }, oldBase, newBase);
  const endpoint = remapPointRaw(vector, oldBase, newBase);
  return { x: endpoint.x - origin.x, y: endpoint.y - origin.y };
}

function remapKeyframe(
  keyframe: TransformKeyframe,
  oldBase: BaseTransform,
  newBase: BaseTransform,
  oldDisplay: ImageDimensions,
  newDisplay: ImageDimensions,
  remapScale: boolean
): TransformKeyframe | null {
  const center = remapNormalizedPoint(keyframe.center, oldBase, newBase);
  if (!center) return null;
  const radians = keyframe.rotationDegrees * Math.PI / 180;
  const direction = remapVector({
    x: Math.cos(radians) / oldDisplay.width,
    y: Math.sin(radians) / oldDisplay.height,
  }, oldBase, newBase);
  const pixelDirection = {
    x: direction.x * newDisplay.width,
    y: direction.y * newDisplay.height,
  };
  const directionScale = Math.hypot(pixelDirection.x, pixelDirection.y);
  const rotationDelta = ((newBase.rotation - oldBase.rotation + 540) % 360) - 180;
  return {
    ...keyframe,
    center,
    scale: round(keyframe.scale * (remapScale && Number.isFinite(directionScale) ? directionScale : 1)),
    rotationDegrees: round(keyframe.rotationDegrees + rotationDelta),
  };
}

function remapCorrection<T extends RectCorrectionKeyframe>(
  correction: T,
  oldBase: BaseTransform,
  newBase: BaseTransform
): T | null {
  const rect = remapNormalizedRect(correction.rect, oldBase, newBase);
  return rect ? { ...correction, rect } : null;
}

function remapMaskTrack(
  track: MaskTrackSpec,
  oldBase: BaseTransform,
  newBase: BaseTransform
): MaskTrackSpec | null {
  const corrections = track.corrections
    .map((correction) => remapCorrection(correction, oldBase, newBase))
    .filter((correction): correction is RectCorrectionKeyframe => correction !== null);
  return corrections.length > 0 ? { ...track, corrections } : null;
}

function remapCover(
  layer: CoverLayer,
  oldBase: BaseTransform,
  newBase: BaseTransform
): CoverLayer | null {
  const corrections = layer.corrections
    .map((correction) => remapCorrection(correction, oldBase, newBase))
    .filter((correction): correction is CoverCorrectionKeyframe => correction !== null);
  const rect = remapNormalizedRect(layer.rect, oldBase, newBase) ?? corrections[0]?.rect ?? null;
  return rect ? { ...layer, rect, corrections } : null;
}

// Remap a drawing across a base-transform change: every point moves through the
// same crop/rotate/flip map cover rects use. Points clipped out of the new frame
// are dropped; a fixed shape whose endpoint leaves the frame is dropped whole,
// and the layer disappears once nothing survives — the same rule remapCover
// follows for a rect that maps away entirely.
function remapDrawLayer(
  layer: DrawLayer,
  oldBase: BaseTransform,
  newBase: BaseTransform
): DrawLayer | null {
  const elements: DrawElement[] = [];
  for (const element of layer.elements) {
    if (element.shape === 'free') {
      // Split into contiguous runs at every dropped point: a stroke that leaves
      // the new crop and re-enters must NOT be bridged by a straight segment
      // across the frame — that would be a mark the user never drew.
      let run: NormalizedPoint[] = [];
      const flush = () => {
        if (run.length >= 1) elements.push({ ...element, points: run });
        run = [];
      };
      for (const point of element.points) {
        const mapped = remapNormalizedPoint(point, oldBase, newBase);
        if (mapped) run.push(mapped);
        else flush();
      }
      flush();
    } else {
      // A fixed shape is defined by two corners; if either leaves the frame the
      // shape is largely outside it, so drop it whole (as remapCover does).
      const first = remapNormalizedPoint(element.points[0], oldBase, newBase);
      const last = remapNormalizedPoint(element.points[element.points.length - 1], oldBase, newBase);
      if (first && last) elements.push({ ...element, points: [first, last] });
    }
  }
  return elements.length > 0 ? { ...layer, elements } : null;
}

function remapTextLayer(
  layer: TextLayer,
  oldBase: BaseTransform,
  newBase: BaseTransform,
  oldDisplay: ImageDimensions,
  newDisplay: ImageDimensions
): TextLayer | null {
  const keyframes = layer.keyframes
    .map((keyframe) => remapKeyframe(keyframe, oldBase, newBase, oldDisplay, newDisplay, false))
    .filter((keyframe): keyframe is TransformKeyframe => keyframe !== null);
  if (keyframes.length === 0) return null;
  return {
    ...layer,
    width: normalizeMemeTextWrapWidth(layer.width * oldDisplay.width / newDisplay.width),
    fontSize: normalizeMemeTextFontSize(layer.fontSize * oldDisplay.height / newDisplay.height),
    style: { ...layer.style },
    keyframes,
  };
}

function remapSubjectLayer(
  layer: SubjectLayer,
  oldBase: BaseTransform,
  newBase: BaseTransform,
  oldDisplay: ImageDimensions,
  newDisplay: ImageDimensions
): SubjectLayer | null {
  const keyframes = layer.keyframes
    .map((keyframe) => remapKeyframe(keyframe, oldBase, newBase, oldDisplay, newDisplay, true))
    .filter((keyframe): keyframe is TransformKeyframe => keyframe !== null);
  return keyframes.length > 0 ? { ...layer, keyframes } : null;
}

function remapMediaLayer(
  layer: MediaOverlayLayer,
  oldBase: BaseTransform,
  newBase: BaseTransform,
  oldDisplay: ImageDimensions,
  newDisplay: ImageDimensions,
  survivingMaskIds: ReadonlySet<string>
): MediaOverlayLayer | null {
  const keyframes = layer.keyframes
    .map((keyframe) => remapKeyframe(keyframe, oldBase, newBase, oldDisplay, newDisplay, true))
    .filter((keyframe): keyframe is TransformKeyframe => keyframe !== null);
  if (keyframes.length === 0) return null;
  return {
    ...layer,
    targetMaskTrackId: layer.targetMaskTrackId && survivingMaskIds.has(layer.targetMaskTrackId)
      ? layer.targetMaskTrackId
      : null,
    keyframes,
  };
}

export function remapImageProject(project: MemeEditProject, requestedBase: BaseTransform): MemeEditProject {
  if (project.source.kind !== 'image') return project;
  const newBase = { ...requestedBase, crop: normalizeFreeCrop(requestedBase.crop, project.base.crop) };
  const source = { width: project.source.width, height: project.source.height };
  const oldDisplay = visibleImageDimensions(source, project.base);
  const newDisplay = visibleImageDimensions(source, newBase);
  const maskTracks = project.maskTracks
    .map((track) => remapMaskTrack(track, project.base, newBase))
    .filter((track): track is MaskTrackSpec => track !== null);
  const maskIds = new Set(maskTracks.map((track) => track.id));
  const layers: MemeEditLayer[] = [];
  for (const layer of project.layers) {
    if (layer.kind === 'cover') {
      const remapped = remapCover(layer, project.base, newBase);
      if (remapped) layers.push(remapped);
    } else if (layer.kind === 'text') {
      const remapped = remapTextLayer(layer, project.base, newBase, oldDisplay, newDisplay);
      if (remapped) layers.push(remapped);
    } else if (layer.kind === 'subject') {
      if (maskIds.has(layer.maskTrackId)) {
        const remapped = remapSubjectLayer(layer, project.base, newBase, oldDisplay, newDisplay);
        if (remapped) layers.push(remapped);
      }
    } else if (layer.kind === 'draw') {
      const remapped = remapDrawLayer(layer, project.base, newBase);
      if (remapped) layers.push(remapped);
    } else {
      const remapped = remapMediaLayer(layer, project.base, newBase, oldDisplay, newDisplay, maskIds);
      if (remapped) layers.push(remapped);
    }
  }
  const transientMaskTracks: Record<string, string> = {};
  for (const track of maskTracks) {
    const uri = project.transient.maskTracks[track.id];
    if (uri !== undefined) transientMaskTracks[track.id] = uri;
  }
  return {
    ...project,
    base: newBase,
    layers,
    maskTracks,
    transient: { ...project.transient, maskTracks: transientMaskTracks },
  };
}

function addCandidate(
  output: TextRegionCandidate[],
  id: string,
  text: string,
  rect: NormalizedRect | null,
  source: TextRegionCandidate['source']
): void {
  if (output.length >= MAX_TEXT_REGION_CANDIDATES) return;
  if (!rect || !text.trim()) return;
  const clipped = clippedUnitRect(rect);
  if (clipped) output.push({ id, text, rect: clipped, source });
}

export function flattenDetectedTextRegions(result: DetectedTextResult): TextRegionCandidate[] {
  const output: TextRegionCandidate[] = [];
  result.blocks.forEach((block, blockIndex) => {
    if (block.lines.length === 0) {
      addCandidate(output, `ocr-${blockIndex}`, block.text, block.box, 'block');
      return;
    }
    block.lines.forEach((line, lineIndex) => {
      if (line.elements.length === 0) {
        addCandidate(output, `ocr-${blockIndex}-${lineIndex}`, line.text, line.box, 'line');
        return;
      }
      line.elements.forEach((element, elementIndex) => {
        addCandidate(
          output,
          `ocr-${blockIndex}-${lineIndex}-${elementIndex}`,
          element.text,
          element.box,
          'element'
        );
      });
    });
  });

  return output;
}

export function defaultManualTextRegion(): TextRegionCandidate {
  return {
    id: 'manual-current',
    text: '',
    source: 'manual',
    rect: { x: 0.25, y: 0.35, width: 0.5, height: 0.3 },
  };
}

export function textRegionFingerprint(region: TextRegionCandidate): string {
  const { x, y, width, height } = region.rect;
  return `${region.id}:${round(x)}:${round(y)}:${round(width)}:${round(height)}`;
}

export class BorderSampleRequestGate {
  private sequence = 0;
  private regionKey: string | null = null;

  begin(regionKey: string): number {
    this.sequence += 1;
    this.regionKey = regionKey;
    return this.sequence;
  }

  accepts(sequence: number, regionKey: string): boolean {
    return sequence === this.sequence && regionKey === this.regionKey;
  }
}

export function canApplyTextRegionAction(
  action: TextRegionAction,
  currentRegionKey: string,
  sampledRegionKey: string | null,
  paletteRegionKey: string | null
): boolean {
  return action === 'pixelate' ||
    sampledRegionKey === currentRegionKey ||
    paletteRegionKey === currentRegionKey;
}

function colorChannels(color: string): [number, number, number] {
  const value = color.trim();
  const short = /^#([0-9a-f]{3})$/i.exec(value)?.[1];
  const full = /^#([0-9a-f]{6})(?:[0-9a-f]{2})?$/i.exec(value)?.[1];
  const hex = full ?? (short ? short.split('').map((part) => `${part}${part}`).join('') : null);
  if (!hex) return [0, 0, 0];
  return [
    Number.parseInt(hex.slice(0, 2), 16),
    Number.parseInt(hex.slice(2, 4), 16),
    Number.parseInt(hex.slice(4, 6), 16),
  ];
}

export function relativeLuminance(color: string): number {
  const channels = colorChannels(color).map((channel) => {
    const normalized = channel / 255;
    return normalized <= 0.04045
      ? normalized / 12.92
      : ((normalized + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

export function contrastRatio(first: string, second: string): number {
  const firstLuminance = relativeLuminance(first);
  const secondLuminance = relativeLuminance(second);
  const lighter = Math.max(firstLuminance, secondLuminance);
  const darker = Math.min(firstLuminance, secondLuminance);
  return (lighter + 0.05) / (darker + 0.05);
}

export function replacementTextColorsForCover(
  coverColor: string
): Pick<TextLayer['style'], 'color' | 'outlineColor' | 'outlineScale'> {
  const dark = '#000000';
  const light = '#FFFFFF';
  const darkContrast = contrastRatio(dark, coverColor);
  const lightContrast = contrastRatio(light, coverColor);
  return darkContrast >= lightContrast
    ? { color: dark, outlineColor: light, outlineScale: 0.04 }
    : { color: light, outlineColor: dark, outlineScale: 0.04 };
}

/** What the text being replaced actually looked like. */
export interface InferredTextStyle {
  /** Colour of the glyphs themselves, read from the image. */
  color: string;
  outlineColor: string;
  outlineScale: number;
  /** Dominant colour behind the glyphs — what a cover should be painted with. */
  backgroundColor: string;
  preset: TextStyle['preset'];
  uppercase: boolean;
  /** Fraction of the region height one line of text occupies. */
  fontSize: number;
  /** How separable the glyphs were from their background, 1..21. */
  contrast: number;
}

function averageColor(colors: readonly string[]): string {
  if (colors.length === 0) return '#000000';
  let r = 0;
  let g = 0;
  let b = 0;
  for (const color of colors) {
    const [cr, cg, cb] = colorChannels(color);
    r += cr;
    g += cg;
    b += cb;
  }
  const to2 = (value: number) => Math.round(value / colors.length).toString(16).padStart(2, '0');
  return `#${to2(r)}${to2(g)}${to2(b)}`.toUpperCase();
}

/**
 * Split sampled pixels into glyphs and background.
 *
 * One-dimensional 2-means over luminance, which is the right tool here: text on
 * a meme is deliberately high-contrast against whatever it sits on, so the
 * histogram is genuinely bimodal, and luminance alone separates it without the
 * cost or instability of clustering in full RGB.
 *
 * The SMALLER cluster is the text. Glyph strokes cover less of a text box than
 * the space around them — that is what makes text legible — so area separates
 * them without caring whether the text is light-on-dark or dark-on-light.
 *
 * KNOWN LIMIT, stated because it is real rather than hypothetical: this assumes
 * the box is reasonably tight around the text. A very loose box drags in enough
 * surrounding image to shift which cluster is larger, and heavy letterforms
 * cropped tightly can push glyph coverage past half. Both invert the result. A
 * border-of-the-grid prior was tried as a fix and removed again — it does not
 * survive either case (the edge of a loose box is mixed, and the edge of a
 * tight box is glyph), and no test could justify the extra code. The honest
 * mitigation is the contrast floor in inferOriginalTextStyle, which refuses to
 * claim a style it cannot read.
 */
export function splitGlyphAndBackground(colors: readonly string[]): {
  glyph: string;
  background: string;
  contrast: number;
} | null {
  const usable = colors.filter((color) => /^#?[0-9a-f]{3,8}$/i.test(color.trim()));
  if (usable.length < 4) return null;

  const points = usable.map((color) => ({ color, luminance: relativeLuminance(color) }));
  let low = Math.min(...points.map((p) => p.luminance));
  let high = Math.max(...points.map((p) => p.luminance));
  if (high - low < 0.02) return null; // flat region: no text to read a style from

  // Lloyd's algorithm converges in a handful of passes on one dimension.
  for (let pass = 0; pass < 12; pass += 1) {
    const lowGroup: number[] = [];
    const highGroup: number[] = [];
    for (const point of points) {
      (Math.abs(point.luminance - low) <= Math.abs(point.luminance - high) ? lowGroup : highGroup).push(point.luminance);
    }
    if (lowGroup.length === 0 || highGroup.length === 0) break;
    const nextLow = lowGroup.reduce((a, b) => a + b, 0) / lowGroup.length;
    const nextHigh = highGroup.reduce((a, b) => a + b, 0) / highGroup.length;
    if (Math.abs(nextLow - low) < 1e-6 && Math.abs(nextHigh - high) < 1e-6) {
      low = nextLow;
      high = nextHigh;
      break;
    }
    low = nextLow;
    high = nextHigh;
  }

  const lowMembers = points.filter((p) => Math.abs(p.luminance - low) <= Math.abs(p.luminance - high));
  const highMembers = points.filter((p) => Math.abs(p.luminance - low) > Math.abs(p.luminance - high));
  if (lowMembers.length === 0 || highMembers.length === 0) return null;

  const glyphMembers = lowMembers.length <= highMembers.length ? lowMembers : highMembers;
  const backgroundMembers = glyphMembers === lowMembers ? highMembers : lowMembers;

  const glyph = averageColor(glyphMembers.map((p) => p.color));
  const background = averageColor(backgroundMembers.map((p) => p.color));
  return { glyph, background, contrast: contrastRatio(glyph, background) };
}

/** True when the original was written in caps — the default for meme text. */
export function looksAllCaps(text: string): boolean {
  const letters = text.replace(/[^\p{L}]/gu, '');
  if (letters.length < 2) return false;
  return letters === letters.toUpperCase();
}

/**
 * Reconstruct the look of the text being replaced, so the replacement reads as
 * an edit of the meme rather than a sticker dropped on top of it.
 *
 * Everything here is derived from the original: the glyph colour is sampled
 * rather than assumed black-or-white, the size comes from the height of ONE
 * line rather than the whole block, and capitalisation is carried over.
 */
export function inferOriginalTextStyle(input: {
  /** Pixels sampled from inside the region, row-major. */
  sampledColors: readonly string[];
  originalText: string;
  /** Region height as a fraction of the image. */
  regionHeight: number;
  /** Lines of text inside the region; 1 when unknown. */
  lineCount: number;
}): InferredTextStyle {
  const lines = Math.max(1, Math.round(finite(input.lineCount, 1)));
  const height = Math.max(0, finite(input.regionHeight, 0));
  const uppercase = looksAllCaps(input.originalText);
  const split = splitGlyphAndBackground(input.sampledColors);

  // Cap height is roughly 72% of the line box; dividing by the line count is
  // what stops a three-line block from producing one enormous line of text.
  const fontSize = clamp((height / lines) * 0.72, MEME_TEXT_BOUNDS.minFontSize, MEME_TEXT_BOUNDS.maxFontSize);

  if (!split || split.contrast < 1.6) {
    // Could not read the original — say so by falling back to the cover-based
    // choice rather than inventing a colour that might vanish into the image.
    const fallbackBackground = split?.background ?? '#000000';
    return {
      ...replacementTextColorsForCover(fallbackBackground),
      backgroundColor: fallbackBackground,
      preset: uppercase ? 'impact' : 'plain',
      uppercase,
      fontSize,
      contrast: split?.contrast ?? 1,
    };
  }

  // A meme caption is caps with a hard outline; a subtitle or watermark is not.
  // Getting this wrong is very visible, so it keys off the two signals that are
  // actually reliable: capitalisation and how hard the contrast is.
  const preset: TextStyle['preset'] = uppercase && split.contrast >= 4 ? 'impact' : uppercase ? 'label' : 'plain';
  const outlineColor = contrastRatio(split.glyph, '#000000') >= contrastRatio(split.glyph, '#FFFFFF')
    ? '#000000'
    : '#FFFFFF';
  return {
    color: split.glyph,
    outlineColor,
    // Impact-style text carries a visible stroke; quieter text should not gain
    // one it never had.
    outlineScale: preset === 'impact' ? 0.06 : 0.02,
    backgroundColor: split.background,
    preset,
    uppercase,
    fontSize,
    contrast: split.contrast,
  };
}

export function createTextRegionLayers(input: TextRegionLayerInput): MemeEditLayer[] {
  const rect = clippedUnitRect(input.rect);
  if (!rect) throw new RangeError('Text region must overlap the visible image.');
  const pixelSize = Math.round(clamp(finite(input.pixelSize, 8), 1, MAX_TEXT_REPLACE_PIXEL_SIZE));
  const cover: CoverLayer = {
    id: input.coverId,
    kind: 'cover',
    rect,
    mode: input.action === 'pixelate' ? 'pixelate' : 'solid',
    color: input.color,
    pixelSize,
    active: null,
    corrections: [],
  };
  if (input.action !== 'replace') return [cover];
  const center = { x: round(rect.x + rect.width / 2), y: round(rect.y + rect.height / 2) };
  // When the original's look was readable, inherit it: preset, glyph colour,
  // outline, capitalisation and per-line size. That is what makes a replacement
  // read as an edit of the meme rather than a sticker dropped onto it. Without
  // it every replacement came out as the same default caption regardless of
  // what it was standing in for.
  const inferred = input.inferredStyle ?? null;
  const text = createMemeTextLayer(input.textId, inferred?.preset ?? 'plain', {
    text: input.text,
    width: clamp(rect.width, MEME_TEXT_BOUNDS.minWrapWidth, MEME_TEXT_BOUNDS.maxWrapWidth),
    fontSize: inferred
      ? inferred.fontSize
      : clamp(rect.height * 0.72, MEME_TEXT_BOUNDS.minFontSize, MEME_TEXT_BOUNDS.maxFontSize),
    style: inferred
      ? {
          color: inferred.color,
          outlineColor: inferred.outlineColor,
          outlineScale: inferred.outlineScale,
          uppercase: inferred.uppercase,
        }
      : replacementTextColorsForCover(input.color),
    active: null,
    keyframes: [{
      timeUs: 0,
      center,
      scale: 1,
      rotationDegrees: 0,
      opacity: 1,
      easing: 'linear',
    }],
  });
  return [cover, text];
}
