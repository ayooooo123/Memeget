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
  const text = createMemeTextLayer(input.textId, 'plain', {
    text: input.text,
    width: clamp(rect.width, MEME_TEXT_BOUNDS.minWrapWidth, MEME_TEXT_BOUNDS.maxWrapWidth),
    fontSize: clamp(rect.height * 0.72, MEME_TEXT_BOUNDS.minFontSize, MEME_TEXT_BOUNDS.maxFontSize),
    style: replacementTextColorsForCover(input.color),
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
