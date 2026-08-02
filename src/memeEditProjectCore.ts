export type MediaEditKind = 'image' | 'video';
export type NormalizedPoint = { x: number; y: number };
export type NormalizedRect = { x: number; y: number; width: number; height: number };
export type TimeRangeUs = { startUs: number; endUs: number };
export type QuarterRotation = 0 | 90 | 180 | 270;

export interface BaseTransform {
  rotation: QuarterRotation;
  flipX: boolean;
  flipY: boolean;
  crop: NormalizedRect;
  outputAspect: 'source' | '1:1' | '4:5' | '9:16' | '16:9';
}

export interface TextStyle {
  preset: 'impact' | 'subtitle' | 'label' | 'news' | 'bubble' | 'plain';
  color: string;
  outlineColor: string;
  outlineScale: number;
  backgroundColor: string | null;
  opacity: number;
  align: 'left' | 'center' | 'right';
  uppercase: boolean;
}

export interface TransformKeyframe {
  timeUs: number;
  center: NormalizedPoint;
  scale: number;
  rotationDegrees: number;
  opacity: number;
  easing: 'linear' | 'hold';
}

export interface TextLayer {
  id: string;
  kind: 'text';
  text: string;
  width: number;
  style: TextStyle;
  active: TimeRangeUs | null;
  keyframes: TransformKeyframe[];
}

export interface CoverLayer {
  id: string;
  kind: 'cover';
  rect: NormalizedRect;
  mode: 'solid' | 'pixelate';
  color: string;
  pixelSize: number;
}

export interface SubjectLayer {
  id: string;
  kind: 'subject';
  subjectIndex: number | null;
  maskTrackId: string;
  active: TimeRangeUs | null;
  keyframes: TransformKeyframe[];
  outlineColor: string | null;
  outlineScale: number;
  shadowScale: number;
}

export interface MediaOverlayLayer {
  id: string;
  kind: 'media';
  assetUri: string;
  assetKind: 'image' | 'video';
  fit: 'contain' | 'cover';
  targetMaskTrackId: string | null;
  active: TimeRangeUs | null;
  keyframes: TransformKeyframe[];
}

export type MemeEditLayer = TextLayer | CoverLayer | SubjectLayer | MediaOverlayLayer;
export type KeyframedLayer = TextLayer | SubjectLayer | MediaOverlayLayer;

export interface BackgroundSpec {
  mode: 'source' | 'solid' | 'blurred-source' | 'image' | 'video';
  color: string;
  assetUri: string | null;
  blurScale: number;
}

export interface InsertedCard {
  uri: string;
  atUs: number;
  durationUs: number;
}

export interface VideoEditSpec {
  retainedRanges: TimeRangeUs[];
  speed: number;
  audio: { muted: boolean; volume: number };
  insertedCards: InsertedCard[];
}

export interface MemeEditSource {
  uri: string;
  name: string;
  kind: MediaEditKind;
  width: number;
  height: number;
  durationUs: number | null;
}

export interface MemeEditProject {
  version: 1;
  source: MemeEditSource;
  base: BaseTransform;
  video: VideoEditSpec | null;
  layers: MemeEditLayer[];
  background: BackgroundSpec;
  transient: {
    maskTracks: Record<string, string>;
    materializedSourceUri: string | null;
  };
}

export interface ImageSourceMetadata {
  uri: string;
  name: string;
  width: number;
  height: number;
}

export interface VideoSourceMetadata extends ImageSourceMetadata {
  durationUs: number;
}

export interface InterpolatedTransform {
  center: NormalizedPoint;
  scale: number;
  rotationDegrees: number;
  opacity: number;
}

export const PROJECT_LIMITS = Object.freeze({
  maxLayers: 64,
  maxRetainedRanges: 32,
  maxKeyframesPerLayer: 256,
  maxInsertedCards: 32,
  maxMaskTracks: 32,
  maxExternalAssets: 32,
  maxAssetUriLength: 4_096,
});

export const MAX_HISTORY_STATES = 30;

const MAX_ID_LENGTH = 256;
const MAX_NAME_LENGTH = 1_024;
const MAX_TEXT_LENGTH = 20_000;
const MAX_COLOR_LENGTH = 128;
const MAX_TRANSFORM_SCALE = 16;
const MIN_TRANSFORM_SCALE = 0.01;
const MAX_ROTATION_DEGREES = 36_000;
const MAX_PIXEL_SIZE = 256;
const MAX_MEDIA_DURATION_US = 24 * 60 * 60 * 1_000_000;
const OUTPUT_ASPECTS: Record<BaseTransform['outputAspect'], true> = {
  source: true,
  '1:1': true,
  '4:5': true,
  '9:16': true,
  '16:9': true,
};
const TEXT_PRESETS: Record<TextStyle['preset'], true> = {
  impact: true,
  subtitle: true,
  label: true,
  news: true,
  bubble: true,
  plain: true,
};
const TEXT_ALIGNS: Record<TextStyle['align'], true> = {
  left: true,
  center: true,
  right: true,
};
const BACKGROUND_MODES: Record<BackgroundSpec['mode'], true> = {
  source: true,
  solid: true,
  'blurred-source': true,
  image: true,
  video: true,
};

export type ProjectValidationErrorCode =
  | 'invalid_type'
  | 'invalid_value'
  | 'unsupported_version'
  | 'unknown_field'
  | 'not_finite'
  | 'not_integer'
  | 'out_of_bounds'
  | 'not_sorted'
  | 'overlap'
  | 'duplicate'
  | 'limit_exceeded';

export interface ProjectValidationError {
  path: string;
  code: ProjectValidationErrorCode;
  message: string;
}

export type ProjectValidationResult =
  | { ok: true; value: MemeEditProject }
  | { ok: false; errors: ProjectValidationError[] };

function roundGeometry(value: number): number {
  const rounded = Math.round(value * 1_000_000_000_000) / 1_000_000_000_000;
  return Object.is(rounded, -0) ? 0 : rounded;
}

function clampNumber(value: number, minimum: number, maximum: number, fallback: number): number {
  if (Number.isNaN(value)) return fallback;
  if (value <= minimum) return minimum;
  if (value >= maximum) return maximum;
  return value;
}

function clampUnit(value: number): number {
  return roundGeometry(clampNumber(value, 0, 1, 0));
}

export function clampNormalizedPoint(point: NormalizedPoint): NormalizedPoint {
  return { x: clampUnit(point.x), y: clampUnit(point.y) };
}

export function clampNormalizedRect(rect: NormalizedRect): NormalizedRect {
  const x = clampUnit(rect.x);
  const y = clampUnit(rect.y);
  const width = Math.min(clampUnit(rect.width), roundGeometry(1 - x));
  const height = Math.min(clampUnit(rect.height), roundGeometry(1 - y));
  return {
    x,
    y,
    width: roundGeometry(width),
    height: roundGeometry(height),
  };
}

export function rotateNormalizedPoint(
  point: NormalizedPoint,
  rotation: QuarterRotation
): NormalizedPoint {
  const normalized = clampNormalizedPoint(point);
  switch (rotation) {
    case 90:
      return { x: roundGeometry(1 - normalized.y), y: normalized.x };
    case 180:
      return {
        x: roundGeometry(1 - normalized.x),
        y: roundGeometry(1 - normalized.y),
      };
    case 270:
      return { x: normalized.y, y: roundGeometry(1 - normalized.x) };
    default:
      return normalized;
  }
}

export function rotateNormalizedRect(
  rect: NormalizedRect,
  rotation: QuarterRotation
): NormalizedRect {
  const normalized = clampNormalizedRect(rect);
  const topLeft = rotateNormalizedPoint({ x: normalized.x, y: normalized.y }, rotation);
  const topRight = rotateNormalizedPoint(
    { x: normalized.x + normalized.width, y: normalized.y },
    rotation
  );
  const bottomLeft = rotateNormalizedPoint(
    { x: normalized.x, y: normalized.y + normalized.height },
    rotation
  );
  const bottomRight = rotateNormalizedPoint(
    { x: normalized.x + normalized.width, y: normalized.y + normalized.height },
    rotation
  );
  const minX = Math.min(topLeft.x, topRight.x, bottomLeft.x, bottomRight.x);
  const minY = Math.min(topLeft.y, topRight.y, bottomLeft.y, bottomRight.y);
  const maxX = Math.max(topLeft.x, topRight.x, bottomLeft.x, bottomRight.x);
  const maxY = Math.max(topLeft.y, topRight.y, bottomLeft.y, bottomRight.y);
  return {
    x: roundGeometry(minX),
    y: roundGeometry(minY),
    width: roundGeometry(maxX - minX),
    height: roundGeometry(maxY - minY),
  };
}

export function mapPointToCroppedCanvas(
  point: NormalizedPoint,
  crop: NormalizedRect
): NormalizedPoint {
  const normalizedCrop = clampNormalizedRect(crop);
  if (normalizedCrop.width === 0 || normalizedCrop.height === 0) return { x: 0, y: 0 };
  const normalizedPoint = clampNormalizedPoint(point);
  return clampNormalizedPoint({
    x: roundGeometry((normalizedPoint.x - normalizedCrop.x) / normalizedCrop.width),
    y: roundGeometry((normalizedPoint.y - normalizedCrop.y) / normalizedCrop.height),
  });
}

export function mapRectToCroppedCanvas(
  rect: NormalizedRect,
  crop: NormalizedRect
): NormalizedRect {
  const normalizedRect = clampNormalizedRect(rect);
  const normalizedCrop = clampNormalizedRect(crop);
  if (normalizedCrop.width === 0 || normalizedCrop.height === 0) {
    return { x: 0, y: 0, width: 0, height: 0 };
  }
  const left = Math.max(normalizedRect.x, normalizedCrop.x);
  const top = Math.max(normalizedRect.y, normalizedCrop.y);
  const right = Math.min(
    normalizedRect.x + normalizedRect.width,
    normalizedCrop.x + normalizedCrop.width
  );
  const bottom = Math.min(
    normalizedRect.y + normalizedRect.height,
    normalizedCrop.y + normalizedCrop.height
  );
  if (right <= left || bottom <= top) return { x: 0, y: 0, width: 0, height: 0 };
  return {
    x: roundGeometry((left - normalizedCrop.x) / normalizedCrop.width),
    y: roundGeometry((top - normalizedCrop.y) / normalizedCrop.height),
    width: roundGeometry((right - left) / normalizedCrop.width),
    height: roundGeometry((bottom - top) / normalizedCrop.height),
  };
}

export function transformPointToWorkingCanvas(
  point: NormalizedPoint,
  base: BaseTransform
): NormalizedPoint {
  const rotated = rotateNormalizedPoint(point, base.rotation);
  const flipped = {
    x: base.flipX ? roundGeometry(1 - rotated.x) : rotated.x,
    y: base.flipY ? roundGeometry(1 - rotated.y) : rotated.y,
  };
  return mapPointToCroppedCanvas(flipped, base.crop);
}

export function transformRectToWorkingCanvas(
  rect: NormalizedRect,
  base: BaseTransform
): NormalizedRect {
  const rotated = rotateNormalizedRect(rect, base.rotation);
  const flipped = {
    x: base.flipX ? roundGeometry(1 - rotated.x - rotated.width) : rotated.x,
    y: base.flipY ? roundGeometry(1 - rotated.y - rotated.height) : rotated.y,
    width: rotated.width,
    height: rotated.height,
  };
  return mapRectToCroppedCanvas(flipped, base.crop);
}

function normalizedTime(value: number, durationUs: number): number {
  if (Number.isNaN(value)) return 0;
  return Math.round(clampNumber(value, 0, durationUs, 0));
}

export function normalizeRetainedRanges(
  ranges: readonly TimeRangeUs[],
  durationUs: number
): TimeRangeUs[] {
  const boundedDuration = Math.max(0, Math.round(Number.isFinite(durationUs) ? durationUs : 0));
  const sorted = ranges
    .map((range) => ({
      startUs: normalizedTime(range.startUs, boundedDuration),
      endUs: normalizedTime(range.endUs, boundedDuration),
    }))
    .filter((range) => range.startUs < range.endUs)
    .sort((left, right) => left.startUs - right.startUs || left.endUs - right.endUs);
  const merged: TimeRangeUs[] = [];
  for (const range of sorted) {
    const previous = merged[merged.length - 1];
    if (previous && range.startUs <= previous.endUs) {
      previous.endUs = Math.max(previous.endUs, range.endUs);
    } else {
      merged.push(range);
    }
  }
  return merged.slice(0, PROJECT_LIMITS.maxRetainedRanges);
}

export function outputDurationUs(ranges: readonly TimeRangeUs[], speed: number): number {
  if (!Number.isFinite(speed) || speed <= 0) return 0;
  let retainedDurationUs = 0;
  for (const range of ranges) retainedDurationUs += range.endUs - range.startUs;
  return Math.round(retainedDurationUs / speed);
}

export function sourceTimeToOutputTimeUs(
  sourceTimeUs: number,
  ranges: readonly TimeRangeUs[],
  speed: number
): number | null {
  if (!Number.isSafeInteger(sourceTimeUs) || !Number.isFinite(speed) || speed <= 0) return null;
  let retainedBeforeUs = 0;
  for (const range of ranges) {
    if (sourceTimeUs < range.startUs) return null;
    if (sourceTimeUs <= range.endUs) {
      return Math.round((retainedBeforeUs + sourceTimeUs - range.startUs) / speed);
    }
    retainedBeforeUs += range.endUs - range.startUs;
  }
  return null;
}

export function outputTimeToSourceTimeUs(
  outputTimeUs: number,
  ranges: readonly TimeRangeUs[],
  speed: number
): number | null {
  if (
    !Number.isSafeInteger(outputTimeUs) ||
    outputTimeUs < 0 ||
    !Number.isFinite(speed) ||
    speed <= 0
  ) {
    return null;
  }
  let retainedBeforeUs = 0;
  for (let index = 0; index < ranges.length; index += 1) {
    const range = ranges[index];
    const rangeDurationUs = range.endUs - range.startUs;
    const endOutputUs = Math.round((retainedBeforeUs + rangeDurationUs) / speed);
    const isLast = index === ranges.length - 1;
    if (outputTimeUs < endOutputUs || (isLast && outputTimeUs === endOutputUs)) {
      const sourceTimeUs = range.startUs + Math.round(outputTimeUs * speed - retainedBeforeUs);
      return Math.min(range.endUs, Math.max(range.startUs, sourceTimeUs));
    }
    retainedBeforeUs += rangeDurationUs;
  }
  return null;
}

export function isLayerActiveAt(layer: MemeEditLayer, timeUs: number): boolean {
  if (layer.kind === 'cover' || layer.active === null) return true;
  return timeUs >= layer.active.startUs && timeUs <= layer.active.endUs;
}

function transformAtKeyframe(keyframe: TransformKeyframe): InterpolatedTransform {
  return {
    center: { ...keyframe.center },
    scale: keyframe.scale,
    rotationDegrees: keyframe.rotationDegrees,
    opacity: keyframe.opacity,
  };
}

export function interpolateTransformKeyframes(
  keyframes: readonly TransformKeyframe[],
  timeUs: number
): InterpolatedTransform | null {
  if (keyframes.length === 0) return null;
  if (timeUs <= keyframes[0].timeUs) return transformAtKeyframe(keyframes[0]);
  const last = keyframes[keyframes.length - 1];
  if (timeUs >= last.timeUs) return transformAtKeyframe(last);

  let low = 0;
  let high = keyframes.length - 1;
  while (low + 1 < high) {
    const middle = Math.floor((low + high) / 2);
    if (keyframes[middle].timeUs <= timeUs) low = middle;
    else high = middle;
  }
  const left = keyframes[low];
  const right = keyframes[high];
  if (left.easing === 'hold') return transformAtKeyframe(left);
  const progress = (timeUs - left.timeUs) / (right.timeUs - left.timeUs);
  return {
    center: {
      x: roundGeometry(left.center.x + (right.center.x - left.center.x) * progress),
      y: roundGeometry(left.center.y + (right.center.y - left.center.y) * progress),
    },
    scale: roundGeometry(left.scale + (right.scale - left.scale) * progress),
    rotationDegrees: roundGeometry(
      left.rotationDegrees + (right.rotationDegrees - left.rotationDegrees) * progress
    ),
    opacity: roundGeometry(left.opacity + (right.opacity - left.opacity) * progress),
  };
}

function defaultBaseTransform(): BaseTransform {
  return {
    rotation: 0,
    flipX: false,
    flipY: false,
    crop: { x: 0, y: 0, width: 1, height: 1 },
    outputAspect: 'source',
  };
}

function defaultBackground(): BackgroundSpec {
  return { mode: 'source', color: '#000000', assetUri: null, blurScale: 0 };
}

export function createDefaultImageProject(source: ImageSourceMetadata): MemeEditProject {
  return {
    version: 1,
    source: { ...source, kind: 'image', durationUs: null },
    base: defaultBaseTransform(),
    video: null,
    layers: [],
    background: defaultBackground(),
    transient: { maskTracks: {}, materializedSourceUri: null },
  };
}

export function createDefaultVideoProject(source: VideoSourceMetadata): MemeEditProject {
  return {
    version: 1,
    source: { ...source, kind: 'video' },
    base: defaultBaseTransform(),
    video: {
      retainedRanges: [{ startUs: 0, endUs: source.durationUs }],
      speed: 1,
      audio: { muted: false, volume: 1 },
      insertedCards: [],
    },
    layers: [],
    background: defaultBackground(),
    transient: { maskTracks: {}, materializedSourceUri: null },
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function addError(
  errors: ProjectValidationError[],
  path: string,
  code: ProjectValidationErrorCode,
  message: string
): void {
  errors.push({ path, code, message });
}

function rejectUnknownFields(
  value: Record<string, unknown>,
  allowed: readonly string[],
  path: string,
  errors: ProjectValidationError[]
): void {
  const allowedFields = new Set(allowed);
  for (const field of Object.keys(value)) {
    if (!allowedFields.has(field)) {
      addError(
        errors,
        path ? `${path}.${field}` : field,
        'unknown_field',
        `Unknown field "${field}" is not part of MemeEditProject version 1.`
      );
    }
  }
}

function validateRecord(
  value: unknown,
  path: string,
  errors: ProjectValidationError[]
): value is Record<string, unknown> {
  if (isRecord(value)) return true;
  addError(errors, path, 'invalid_type', `${path || 'value'} must be an object.`);
  return false;
}

function validateString(
  value: unknown,
  path: string,
  maximumLength: number,
  errors: ProjectValidationError[],
  allowEmpty = false
): value is string {
  if (typeof value !== 'string') {
    addError(errors, path, 'invalid_type', `${path} must be a string.`);
    return false;
  }
  if (!allowEmpty && value.length === 0) {
    addError(errors, path, 'invalid_value', `${path} must not be empty.`);
    return false;
  }
  if (value.length > maximumLength) {
    addError(
      errors,
      path,
      'limit_exceeded',
      `${path} exceeds the maximum length of ${maximumLength}.`
    );
    return false;
  }
  return true;
}

function validateFiniteNumber(
  value: unknown,
  path: string,
  errors: ProjectValidationError[]
): value is number {
  if (typeof value !== 'number') {
    addError(errors, path, 'invalid_type', `${path} must be a number.`);
    return false;
  }
  if (!Number.isFinite(value)) {
    addError(errors, path, 'not_finite', `${path} must be finite.`);
    return false;
  }
  return true;
}

function validateIntegerTime(
  value: unknown,
  path: string,
  errors: ProjectValidationError[]
): value is number {
  if (!validateFiniteNumber(value, path, errors)) return false;
  if (!Number.isSafeInteger(value)) {
    addError(errors, path, 'not_integer', `${path} must be an integer number of microseconds.`);
    return false;
  }
  return true;
}

function validateUnitNumber(
  value: unknown,
  path: string,
  errors: ProjectValidationError[]
): value is number {
  if (!validateFiniteNumber(value, path, errors)) return false;
  if (value < 0 || value > 1) {
    addError(errors, path, 'out_of_bounds', `${path} must be between 0 and 1.`);
    return false;
  }
  return true;
}

function validateEnum(
  value: unknown,
  path: string,
  accepted: Readonly<Record<string, true>>,
  errors: ProjectValidationError[]
): value is string {
  if (
    typeof value === 'string' &&
    Object.prototype.hasOwnProperty.call(accepted, value)
  ) {
    return true;
  }
  addError(
    errors,
    path,
    'invalid_value',
    `${path} must be one of: ${Object.keys(accepted).join(', ')}.`
  );
  return false;
}

function validateNormalizedPoint(
  value: unknown,
  path: string,
  errors: ProjectValidationError[]
): void {
  if (!validateRecord(value, path, errors)) return;
  rejectUnknownFields(value, ['x', 'y'], path, errors);
  validateUnitNumber(value.x, `${path}.x`, errors);
  validateUnitNumber(value.y, `${path}.y`, errors);
}

function validateNormalizedRect(
  value: unknown,
  path: string,
  errors: ProjectValidationError[],
  requireArea: boolean
): void {
  if (!validateRecord(value, path, errors)) return;
  rejectUnknownFields(value, ['x', 'y', 'width', 'height'], path, errors);
  const x = value.x;
  const y = value.y;
  const width = value.width;
  const height = value.height;
  const validX = validateUnitNumber(x, `${path}.x`, errors);
  const validY = validateUnitNumber(y, `${path}.y`, errors);
  const validWidth = validateUnitNumber(width, `${path}.width`, errors);
  const validHeight = validateUnitNumber(height, `${path}.height`, errors);
  if (validWidth && requireArea && width === 0) {
    addError(errors, `${path}.width`, 'out_of_bounds', `${path}.width must be greater than zero.`);
  }
  if (validHeight && requireArea && height === 0) {
    addError(errors, `${path}.height`, 'out_of_bounds', `${path}.height must be greater than zero.`);
  }
  if (validX && validWidth && x + width > 1) {
    addError(errors, path, 'out_of_bounds', `${path} must fit inside the normalized canvas.`);
  }
  if (validY && validHeight && y + height > 1) {
    addError(errors, path, 'out_of_bounds', `${path} must fit inside the normalized canvas.`);
  }
}

function validateTimeRange(
  value: unknown,
  path: string,
  durationUs: number,
  errors: ProjectValidationError[]
): value is TimeRangeUs {
  if (!validateRecord(value, path, errors)) return false;
  rejectUnknownFields(value, ['startUs', 'endUs'], path, errors);
  const startUs = value.startUs;
  const endUs = value.endUs;
  const validStart = validateIntegerTime(startUs, `${path}.startUs`, errors);
  const validEnd = validateIntegerTime(endUs, `${path}.endUs`, errors);
  if (!validStart || !validEnd) return false;
  let valid = true;
  if (startUs < 0 || startUs > durationUs) {
    addError(
      errors,
      `${path}.startUs`,
      'out_of_bounds',
      `${path}.startUs must be inside the source duration.`
    );
    valid = false;
  }
  if (endUs < 0 || endUs > durationUs) {
    addError(
      errors,
      `${path}.endUs`,
      'out_of_bounds',
      `${path}.endUs must be inside the source duration.`
    );
    valid = false;
  }
  if (startUs >= endUs) {
    addError(errors, `${path}.endUs`, 'out_of_bounds', `${path} must have positive duration.`);
    valid = false;
  }
  return valid;
}

function validateSource(value: unknown, errors: ProjectValidationError[]): void {
  const path = 'source';
  if (!validateRecord(value, path, errors)) return;
  rejectUnknownFields(value, ['uri', 'name', 'kind', 'width', 'height', 'durationUs'], path, errors);
  validateString(value.uri, 'source.uri', PROJECT_LIMITS.maxAssetUriLength, errors);
  validateString(value.name, 'source.name', MAX_NAME_LENGTH, errors);
  if (value.kind !== 'image' && value.kind !== 'video') {
    addError(errors, 'source.kind', 'invalid_value', 'source.kind must be image or video.');
  }
  const width = value.width;
  const height = value.height;
  const validWidth = validateFiniteNumber(width, 'source.width', errors);
  const validHeight = validateFiniteNumber(height, 'source.height', errors);
  if (validWidth && (!Number.isInteger(width) || width <= 0)) {
    addError(errors, 'source.width', 'out_of_bounds', 'source.width must be a positive integer.');
  }
  if (validHeight && (!Number.isInteger(height) || height <= 0)) {
    addError(errors, 'source.height', 'out_of_bounds', 'source.height must be a positive integer.');
  }
  if (value.kind === 'image' && value.durationUs !== null) {
    addError(errors, 'source.durationUs', 'invalid_value', 'Image source durationUs must be null.');
  }
  if (value.kind === 'video') {
    if (validateIntegerTime(value.durationUs, 'source.durationUs', errors)) {
      if (value.durationUs <= 0 || value.durationUs > MAX_MEDIA_DURATION_US) {
        addError(
          errors,
          'source.durationUs',
          'out_of_bounds',
          `source.durationUs must be between 1 and ${MAX_MEDIA_DURATION_US}.`
        );
      }
    }
  }
}

function validateBase(value: unknown, errors: ProjectValidationError[]): void {
  const path = 'base';
  if (!validateRecord(value, path, errors)) return;
  rejectUnknownFields(value, ['rotation', 'flipX', 'flipY', 'crop', 'outputAspect'], path, errors);
  if (value.rotation !== 0 && value.rotation !== 90 && value.rotation !== 180 && value.rotation !== 270) {
    addError(errors, 'base.rotation', 'invalid_value', 'base.rotation must be 0, 90, 180, or 270.');
  }
  if (typeof value.flipX !== 'boolean') {
    addError(errors, 'base.flipX', 'invalid_type', 'base.flipX must be boolean.');
  }
  if (typeof value.flipY !== 'boolean') {
    addError(errors, 'base.flipY', 'invalid_type', 'base.flipY must be boolean.');
  }
  validateNormalizedRect(value.crop, 'base.crop', errors, true);
  validateEnum(value.outputAspect, 'base.outputAspect', OUTPUT_ASPECTS, errors);
}

function validateTextStyle(value: unknown, path: string, errors: ProjectValidationError[]): void {
  if (!validateRecord(value, path, errors)) return;
  rejectUnknownFields(
    value,
    [
      'preset',
      'color',
      'outlineColor',
      'outlineScale',
      'backgroundColor',
      'opacity',
      'align',
      'uppercase',
    ],
    path,
    errors
  );
  validateEnum(value.preset, `${path}.preset`, TEXT_PRESETS, errors);
  validateString(value.color, `${path}.color`, MAX_COLOR_LENGTH, errors);
  validateString(value.outlineColor, `${path}.outlineColor`, MAX_COLOR_LENGTH, errors);
  validateUnitNumber(value.outlineScale, `${path}.outlineScale`, errors);
  if (value.backgroundColor !== null) {
    validateString(value.backgroundColor, `${path}.backgroundColor`, MAX_COLOR_LENGTH, errors);
  }
  validateUnitNumber(value.opacity, `${path}.opacity`, errors);
  validateEnum(value.align, `${path}.align`, TEXT_ALIGNS, errors);
  if (typeof value.uppercase !== 'boolean') {
    addError(errors, `${path}.uppercase`, 'invalid_type', `${path}.uppercase must be boolean.`);
  }
}

function validateKeyframes(
  value: unknown,
  path: string,
  sourceKind: MediaEditKind | null,
  active: TimeRangeUs | null,
  errors: ProjectValidationError[]
): void {
  if (!Array.isArray(value)) {
    addError(errors, path, 'invalid_type', `${path} must be an array.`);
    return;
  }
  if (value.length === 0) {
    addError(errors, path, 'invalid_value', `${path} must contain at least one sparse keyframe.`);
  }
  if (value.length > PROJECT_LIMITS.maxKeyframesPerLayer) {
    addError(
      errors,
      path,
      'limit_exceeded',
      `${path} exceeds the ${PROJECT_LIMITS.maxKeyframesPerLayer}-keyframe limit.`
    );
  }
  let previousTimeUs: number | null = null;
  value.forEach((candidate, index) => {
    const framePath = `${path}[${index}]`;
    if (!validateRecord(candidate, framePath, errors)) return;
    rejectUnknownFields(
      candidate,
      ['timeUs', 'center', 'scale', 'rotationDegrees', 'opacity', 'easing'],
      framePath,
      errors
    );
    const timeUs = candidate.timeUs;
    const scale = candidate.scale;
    const rotationDegrees = candidate.rotationDegrees;
    const validTime = validateIntegerTime(timeUs, `${framePath}.timeUs`, errors);
    validateNormalizedPoint(candidate.center, `${framePath}.center`, errors);
    const validScale = validateFiniteNumber(scale, `${framePath}.scale`, errors);
    if (validScale && (scale < MIN_TRANSFORM_SCALE || scale > MAX_TRANSFORM_SCALE)) {
      addError(
        errors,
        `${framePath}.scale`,
        'out_of_bounds',
        `${framePath}.scale must be between ${MIN_TRANSFORM_SCALE} and ${MAX_TRANSFORM_SCALE}.`
      );
    }
    const validRotation = validateFiniteNumber(
      rotationDegrees,
      `${framePath}.rotationDegrees`,
      errors
    );
    if (validRotation && Math.abs(rotationDegrees) > MAX_ROTATION_DEGREES) {
      addError(
        errors,
        `${framePath}.rotationDegrees`,
        'out_of_bounds',
        `${framePath}.rotationDegrees is outside the supported range.`
      );
    }
    validateUnitNumber(candidate.opacity, `${framePath}.opacity`, errors);
    if (candidate.easing !== 'linear' && candidate.easing !== 'hold') {
      addError(
        errors,
        `${framePath}.easing`,
        'invalid_value',
        `${framePath}.easing must be linear or hold.`
      );
    }
    if (validTime) {
      if (previousTimeUs !== null) {
        if (timeUs === previousTimeUs) {
          addError(
            errors,
            `${framePath}.timeUs`,
            'duplicate',
            `${framePath}.timeUs duplicates the previous keyframe timestamp.`
          );
        } else if (timeUs < previousTimeUs) {
          addError(
            errors,
            `${framePath}.timeUs`,
            'not_sorted',
            `${path} must be sorted by timeUs.`
          );
        }
      }
      previousTimeUs = timeUs;
      if (sourceKind === 'image' && timeUs !== 0) {
        addError(
          errors,
          `${framePath}.timeUs`,
          'out_of_bounds',
          'Image keyframes must use timeUs=0.'
        );
      }
      if (
        sourceKind === 'video' &&
        active !== null &&
        (timeUs < active.startUs || timeUs > active.endUs)
      ) {
        addError(
          errors,
          `${framePath}.timeUs`,
          'out_of_bounds',
          `${framePath}.timeUs must be inside the layer active range.`
        );
      }
    }
  });
  if (sourceKind === 'image' && value.length !== 1) {
    addError(errors, path, 'invalid_value', 'Image layers must contain exactly one timeUs=0 keyframe.');
  }
}

function readValidActiveRange(
  value: unknown,
  path: string,
  sourceKind: MediaEditKind | null,
  durationUs: number,
  errors: ProjectValidationError[]
): TimeRangeUs | null {
  if (sourceKind === 'image') {
    if (value !== null) {
      addError(errors, path, 'invalid_value', `${path} must be null for an image project.`);
    }
    return null;
  }
  if (sourceKind === 'video') {
    if (validateTimeRange(value, path, durationUs, errors)) return value;
    return null;
  }
  return null;
}

function validateLayer(
  value: unknown,
  index: number,
  sourceKind: MediaEditKind | null,
  durationUs: number,
  errors: ProjectValidationError[]
): void {
  const path = `layers[${index}]`;
  if (!validateRecord(value, path, errors)) return;
  validateString(value.id, `${path}.id`, MAX_ID_LENGTH, errors);
  if (typeof value.kind !== 'string') {
    addError(errors, `${path}.kind`, 'invalid_type', `${path}.kind must be a string.`);
    return;
  }
  if (value.kind === 'text') {
    rejectUnknownFields(value, ['id', 'kind', 'text', 'width', 'style', 'active', 'keyframes'], path, errors);
    validateString(value.text, `${path}.text`, MAX_TEXT_LENGTH, errors, true);
    const width = value.width;
    const validWidth = validateFiniteNumber(width, `${path}.width`, errors);
    if (validWidth && (width <= 0 || width > 1)) {
      addError(errors, `${path}.width`, 'out_of_bounds', `${path}.width must be greater than 0 and at most 1.`);
    }
    validateTextStyle(value.style, `${path}.style`, errors);
    const active = readValidActiveRange(value.active, `${path}.active`, sourceKind, durationUs, errors);
    validateKeyframes(value.keyframes, `${path}.keyframes`, sourceKind, active, errors);
    return;
  }
  if (value.kind === 'cover') {
    rejectUnknownFields(value, ['id', 'kind', 'rect', 'mode', 'color', 'pixelSize'], path, errors);
    validateNormalizedRect(value.rect, `${path}.rect`, errors, true);
    if (value.mode !== 'solid' && value.mode !== 'pixelate') {
      addError(errors, `${path}.mode`, 'invalid_value', `${path}.mode must be solid or pixelate.`);
    }
    validateString(value.color, `${path}.color`, MAX_COLOR_LENGTH, errors);
    const pixelSize = value.pixelSize;
    const validPixelSize = validateFiniteNumber(pixelSize, `${path}.pixelSize`, errors);
    if (validPixelSize && (pixelSize <= 0 || pixelSize > MAX_PIXEL_SIZE)) {
      addError(
        errors,
        `${path}.pixelSize`,
        'out_of_bounds',
        `${path}.pixelSize must be greater than 0 and at most ${MAX_PIXEL_SIZE}.`
      );
    }
    return;
  }
  if (value.kind === 'subject') {
    rejectUnknownFields(
      value,
      [
        'id',
        'kind',
        'subjectIndex',
        'maskTrackId',
        'active',
        'keyframes',
        'outlineColor',
        'outlineScale',
        'shadowScale',
      ],
      path,
      errors
    );
    const subjectIndex = value.subjectIndex;
    if (
      subjectIndex !== null &&
      (typeof subjectIndex !== 'number' ||
        !Number.isSafeInteger(subjectIndex) ||
        subjectIndex < 0)
    ) {
      addError(
        errors,
        `${path}.subjectIndex`,
        'out_of_bounds',
        `${path}.subjectIndex must be null or a non-negative integer.`
      );
    }
    validateString(value.maskTrackId, `${path}.maskTrackId`, MAX_ID_LENGTH, errors);
    const active = readValidActiveRange(value.active, `${path}.active`, sourceKind, durationUs, errors);
    validateKeyframes(value.keyframes, `${path}.keyframes`, sourceKind, active, errors);
    if (value.outlineColor !== null) {
      validateString(value.outlineColor, `${path}.outlineColor`, MAX_COLOR_LENGTH, errors);
    }
    validateUnitNumber(value.outlineScale, `${path}.outlineScale`, errors);
    validateUnitNumber(value.shadowScale, `${path}.shadowScale`, errors);
    return;
  }
  if (value.kind === 'media') {
    rejectUnknownFields(
      value,
      ['id', 'kind', 'assetUri', 'assetKind', 'fit', 'targetMaskTrackId', 'active', 'keyframes'],
      path,
      errors
    );
    validateString(value.assetUri, `${path}.assetUri`, PROJECT_LIMITS.maxAssetUriLength, errors);
    if (value.assetKind !== 'image' && value.assetKind !== 'video') {
      addError(
        errors,
        `${path}.assetKind`,
        'invalid_value',
        `${path}.assetKind must be image or video.`
      );
    }
    if (value.fit !== 'contain' && value.fit !== 'cover') {
      addError(errors, `${path}.fit`, 'invalid_value', `${path}.fit must be contain or cover.`);
    }
    if (value.targetMaskTrackId !== null) {
      validateString(
        value.targetMaskTrackId,
        `${path}.targetMaskTrackId`,
        MAX_ID_LENGTH,
        errors
      );
    }
    const active = readValidActiveRange(value.active, `${path}.active`, sourceKind, durationUs, errors);
    validateKeyframes(value.keyframes, `${path}.keyframes`, sourceKind, active, errors);
    return;
  }
  addError(
    errors,
    `${path}.kind`,
    'invalid_value',
    `${path}.kind must be text, cover, subject, or media.`
  );
}

function validateVideo(
  value: unknown,
  sourceKind: MediaEditKind | null,
  durationUs: number,
  errors: ProjectValidationError[]
): void {
  if (sourceKind === 'image') {
    if (value !== null) addError(errors, 'video', 'invalid_value', 'video must be null for image projects.');
    return;
  }
  if (sourceKind !== 'video') return;
  if (!validateRecord(value, 'video', errors)) return;
  rejectUnknownFields(value, ['retainedRanges', 'speed', 'audio', 'insertedCards'], 'video', errors);
  if (!Array.isArray(value.retainedRanges)) {
    addError(errors, 'video.retainedRanges', 'invalid_type', 'video.retainedRanges must be an array.');
  } else {
    if (value.retainedRanges.length === 0) {
      addError(
        errors,
        'video.retainedRanges',
        'invalid_value',
        'video.retainedRanges must retain at least one range.'
      );
    }
    if (value.retainedRanges.length > PROJECT_LIMITS.maxRetainedRanges) {
      addError(
        errors,
        'video.retainedRanges',
        'limit_exceeded',
        `video.retainedRanges exceeds the ${PROJECT_LIMITS.maxRetainedRanges}-range limit.`
      );
    }
    let previous: TimeRangeUs | null = null;
    value.retainedRanges.forEach((candidate, index) => {
      const rangePath = `video.retainedRanges[${index}]`;
      if (!validateTimeRange(candidate, rangePath, durationUs, errors)) return;
      if (previous !== null) {
        if (candidate.startUs < previous.startUs) {
          addError(
            errors,
            `${rangePath}.startUs`,
            'not_sorted',
            'video.retainedRanges must be sorted by startUs.'
          );
        }
        if (candidate.startUs < previous.endUs) {
          addError(
            errors,
            `${rangePath}.startUs`,
            'overlap',
            'video.retainedRanges must not overlap.'
          );
        }
      }
      previous = candidate;
    });
  }
  const speedValue = value.speed;
  const validSpeed = validateFiniteNumber(speedValue, 'video.speed', errors);
  if (validSpeed && (speedValue < 0.5 || speedValue > 2)) {
    addError(errors, 'video.speed', 'out_of_bounds', 'video.speed must be between 0.5 and 2.');
  }
  if (validateRecord(value.audio, 'video.audio', errors)) {
    rejectUnknownFields(value.audio, ['muted', 'volume'], 'video.audio', errors);
    if (typeof value.audio.muted !== 'boolean') {
      addError(errors, 'video.audio.muted', 'invalid_type', 'video.audio.muted must be boolean.');
    }
    const volume = value.audio.volume;
    const validVolume = validateFiniteNumber(volume, 'video.audio.volume', errors);
    if (validVolume && (volume < 0 || volume > 2)) {
      addError(errors, 'video.audio.volume', 'out_of_bounds', 'video.audio.volume must be between 0 and 2.');
    }
  }
  if (!Array.isArray(value.insertedCards)) {
    addError(errors, 'video.insertedCards', 'invalid_type', 'video.insertedCards must be an array.');
  } else {
    if (value.insertedCards.length > PROJECT_LIMITS.maxInsertedCards) {
      addError(
        errors,
        'video.insertedCards',
        'limit_exceeded',
        `video.insertedCards exceeds the ${PROJECT_LIMITS.maxInsertedCards}-card limit.`
      );
    }
    const retainedRanges = Array.isArray(value.retainedRanges)
      ? value.retainedRanges.filter((range): range is TimeRangeUs => {
          return (
            isRecord(range) &&
            Number.isSafeInteger(range.startUs) &&
            Number.isSafeInteger(range.endUs)
          );
        })
      : [];
    const speed = typeof value.speed === 'number' && value.speed > 0 ? value.speed : 1;
    const timelineDurationUs = outputDurationUs(retainedRanges, speed);
    value.insertedCards.forEach((candidate, index) => {
      const cardPath = `video.insertedCards[${index}]`;
      if (!validateRecord(candidate, cardPath, errors)) return;
      rejectUnknownFields(candidate, ['uri', 'atUs', 'durationUs'], cardPath, errors);
      validateString(candidate.uri, `${cardPath}.uri`, PROJECT_LIMITS.maxAssetUriLength, errors);
      const atUs = candidate.atUs;
      const cardDurationUs = candidate.durationUs;
      const validAt = validateIntegerTime(atUs, `${cardPath}.atUs`, errors);
      const validDuration = validateIntegerTime(
        cardDurationUs,
        `${cardPath}.durationUs`,
        errors
      );
      if (validAt && (atUs < 0 || atUs > timelineDurationUs)) {
        addError(
          errors,
          `${cardPath}.atUs`,
          'out_of_bounds',
          `${cardPath}.atUs must be inside the retained output timeline.`
        );
      }
      if (validDuration && (cardDurationUs <= 0 || cardDurationUs > MAX_MEDIA_DURATION_US)) {
        addError(
          errors,
          `${cardPath}.durationUs`,
          'out_of_bounds',
          `${cardPath}.durationUs must be positive and bounded.`
        );
      }
    });
  }
}

function validateBackground(value: unknown, errors: ProjectValidationError[]): void {
  if (!validateRecord(value, 'background', errors)) return;
  rejectUnknownFields(value, ['mode', 'color', 'assetUri', 'blurScale'], 'background', errors);
  const validMode = validateEnum(value.mode, 'background.mode', BACKGROUND_MODES, errors);
  validateString(value.color, 'background.color', MAX_COLOR_LENGTH, errors);
  if (value.assetUri !== null) {
    validateString(value.assetUri, 'background.assetUri', PROJECT_LIMITS.maxAssetUriLength, errors);
  }
  if (validMode) {
    const requiresAsset = value.mode === 'image' || value.mode === 'video';
    if (requiresAsset && value.assetUri === null) {
      addError(
        errors,
        'background.assetUri',
        'invalid_value',
        `background.assetUri is required for ${String(value.mode)} mode.`
      );
    }
    if (!requiresAsset && value.assetUri !== null) {
      addError(
        errors,
        'background.assetUri',
        'invalid_value',
        `background.assetUri must be null for ${String(value.mode)} mode.`
      );
    }
  }
  validateUnitNumber(value.blurScale, 'background.blurScale', errors);
}

function validateTransient(value: unknown, errors: ProjectValidationError[]): void {
  if (!validateRecord(value, 'transient', errors)) return;
  rejectUnknownFields(value, ['maskTracks', 'materializedSourceUri'], 'transient', errors);
  if (validateRecord(value.maskTracks, 'transient.maskTracks', errors)) {
    const trackEntries = Object.entries(value.maskTracks);
    if (trackEntries.length > PROJECT_LIMITS.maxMaskTracks) {
      addError(
        errors,
        'transient.maskTracks',
        'limit_exceeded',
        `transient.maskTracks exceeds the ${PROJECT_LIMITS.maxMaskTracks}-track limit.`
      );
    }
    for (const [trackId, uri] of trackEntries) {
      validateString(trackId, `transient.maskTracks.${trackId}`, MAX_ID_LENGTH, errors);
      validateString(
        uri,
        `transient.maskTracks.${trackId}`,
        PROJECT_LIMITS.maxAssetUriLength,
        errors
      );
    }
  }
  if (value.materializedSourceUri !== null) {
    validateString(
      value.materializedSourceUri,
      'transient.materializedSourceUri',
      PROJECT_LIMITS.maxAssetUriLength,
      errors
    );
  }
}

function validateExternalAssetCount(root: Record<string, unknown>, errors: ProjectValidationError[]): void {
  const assets = new Set<string>();
  if (isRecord(root.background) && typeof root.background.assetUri === 'string') {
    assets.add(root.background.assetUri);
  }
  if (Array.isArray(root.layers)) {
    for (const candidate of root.layers) {
      if (isRecord(candidate) && candidate.kind === 'media' && typeof candidate.assetUri === 'string') {
        assets.add(candidate.assetUri);
      }
    }
  }
  if (isRecord(root.video) && Array.isArray(root.video.insertedCards)) {
    for (const candidate of root.video.insertedCards) {
      if (isRecord(candidate) && typeof candidate.uri === 'string') assets.add(candidate.uri);
    }
  }
  if (assets.size > PROJECT_LIMITS.maxExternalAssets) {
    addError(
      errors,
      'externalAssets',
      'limit_exceeded',
      `Project references ${assets.size} distinct external assets; maximum is ${PROJECT_LIMITS.maxExternalAssets}.`
    );
  }
}

export function validateMemeEditProject(input: unknown): ProjectValidationResult {
  const errors: ProjectValidationError[] = [];
  if (!validateRecord(input, '', errors)) return { ok: false, errors };
  rejectUnknownFields(
    input,
    ['version', 'source', 'base', 'video', 'layers', 'background', 'transient'],
    '',
    errors
  );
  if (input.version !== 1) {
    addError(errors, 'version', 'unsupported_version', 'Only MemeEditProject version 1 is supported.');
  }
  validateSource(input.source, errors);
  validateBase(input.base, errors);

  const source = isRecord(input.source) ? input.source : null;
  const sourceKind: MediaEditKind | null =
    source?.kind === 'image' || source?.kind === 'video' ? source.kind : null;
  const sourceDurationUs = source?.durationUs;
  const durationUs =
    sourceKind === 'video' &&
    typeof sourceDurationUs === 'number' &&
    Number.isSafeInteger(sourceDurationUs)
      ? sourceDurationUs
      : 0;
  validateVideo(input.video, sourceKind, durationUs, errors);

  if (!Array.isArray(input.layers)) {
    addError(errors, 'layers', 'invalid_type', 'layers must be an array.');
  } else {
    if (input.layers.length > PROJECT_LIMITS.maxLayers) {
      addError(
        errors,
        'layers',
        'limit_exceeded',
        `layers exceeds the ${PROJECT_LIMITS.maxLayers}-layer limit.`
      );
    }
    const ids = new Set<string>();
    input.layers.forEach((layer, index) => {
      validateLayer(layer, index, sourceKind, durationUs, errors);
      if (isRecord(layer) && typeof layer.id === 'string') {
        if (ids.has(layer.id)) {
          addError(errors, `layers[${index}].id`, 'duplicate', `Layer ID "${layer.id}" is duplicated.`);
        }
        ids.add(layer.id);
      }
    });
  }
  validateBackground(input.background, errors);
  validateTransient(input.transient, errors);
  validateExternalAssetCount(input, errors);

  if (errors.length > 0) return { ok: false, errors };
  const validatedProject = input as unknown as MemeEditProject;
  return { ok: true, value: validatedProject };
}

function normalizeCrop(crop: NormalizedRect, fallback: NormalizedRect): NormalizedRect {
  const normalized = clampNormalizedRect(crop);
  if (normalized.width === 0 || normalized.height === 0) return { ...fallback };
  return normalized;
}

function normalizeBase(base: BaseTransform, fallback: BaseTransform): BaseTransform {
  const rotation = base.rotation === 90 || base.rotation === 180 || base.rotation === 270 ? base.rotation : 0;
  const outputAspect = Object.prototype.hasOwnProperty.call(OUTPUT_ASPECTS, base.outputAspect)
    ? base.outputAspect
    : fallback.outputAspect;
  return {
    rotation,
    flipX: Boolean(base.flipX),
    flipY: Boolean(base.flipY),
    crop: normalizeCrop(base.crop, fallback.crop),
    outputAspect,
  };
}

function normalizeActiveRange(
  active: TimeRangeUs | null,
  durationUs: number,
  fallback: TimeRangeUs | null
): TimeRangeUs {
  if (active !== null) {
    const startUs = normalizedTime(active.startUs, durationUs);
    const endUs = normalizedTime(active.endUs, durationUs);
    if (startUs < endUs) return { startUs, endUs };
  }
  if (fallback !== null && fallback.startUs < fallback.endUs) return { ...fallback };
  return { startUs: 0, endUs: durationUs };
}

export function normalizeTransformKeyframes(
  keyframes: readonly TransformKeyframe[],
  minimumTimeUs: number,
  maximumTimeUs: number
): TransformKeyframe[] {
  const byTime = new Map<number, TransformKeyframe>();
  for (const frame of keyframes.slice(0, PROJECT_LIMITS.maxKeyframesPerLayer)) {
    const timeUs = Math.round(
      clampNumber(frame.timeUs, minimumTimeUs, maximumTimeUs, minimumTimeUs)
    );
    byTime.set(timeUs, {
      timeUs,
      center: clampNormalizedPoint(frame.center),
      scale: roundGeometry(
        clampNumber(frame.scale, MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE, 1)
      ),
      rotationDegrees: roundGeometry(
        clampNumber(frame.rotationDegrees, -MAX_ROTATION_DEGREES, MAX_ROTATION_DEGREES, 0)
      ),
      opacity: clampUnit(frame.opacity),
      easing: frame.easing === 'hold' ? 'hold' : 'linear',
    });
  }
  return Array.from(byTime.values()).sort((left, right) => left.timeUs - right.timeUs);
}

function cloneLayer(layer: MemeEditLayer): MemeEditLayer {
  if (layer.kind === 'cover') return { ...layer, rect: { ...layer.rect } };
  const keyframes = layer.keyframes.map((frame) => ({ ...frame, center: { ...frame.center } }));
  if (layer.kind === 'text') {
    return {
      ...layer,
      style: { ...layer.style },
      active: layer.active ? { ...layer.active } : null,
      keyframes,
    };
  }
  return {
    ...layer,
    active: layer.active ? { ...layer.active } : null,
    keyframes,
  };
}

function assertBoundedString(
  value: string,
  maximumLength: number,
  label: string,
  allowEmpty = false
): void {
  if ((!allowEmpty && value.length === 0) || value.length > maximumLength) {
    throw new RangeError(
      `${label} must contain ${allowEmpty ? 'at most' : 'between 1 and'} ${maximumLength} characters.`
    );
  }
}

function assertLayerStringsAreBounded(layer: MemeEditLayer): void {
  assertBoundedString(layer.id, MAX_ID_LENGTH, 'Layer ID');
  if (layer.kind === 'text') {
    assertBoundedString(layer.text, MAX_TEXT_LENGTH, 'Layer text', true);
    assertBoundedString(layer.style.color, MAX_COLOR_LENGTH, 'Text color');
    assertBoundedString(layer.style.outlineColor, MAX_COLOR_LENGTH, 'Text outline color');
    if (layer.style.backgroundColor !== null) {
      assertBoundedString(
        layer.style.backgroundColor,
        MAX_COLOR_LENGTH,
        'Text background color'
      );
    }
  } else if (layer.kind === 'cover') {
    assertBoundedString(layer.color, MAX_COLOR_LENGTH, 'Cover color');
  } else if (layer.kind === 'subject') {
    assertBoundedString(layer.maskTrackId, MAX_ID_LENGTH, 'Mask track ID');
    if (layer.outlineColor !== null) {
      assertBoundedString(layer.outlineColor, MAX_COLOR_LENGTH, 'Subject outline color');
    }
  } else {
    assertBoundedString(
      layer.assetUri,
      PROJECT_LIMITS.maxAssetUriLength,
      'Media overlay asset URI'
    );
    if (layer.targetMaskTrackId !== null) {
      assertBoundedString(layer.targetMaskTrackId, MAX_ID_LENGTH, 'Target mask track ID');
    }
  }
}

function normalizeLayer(layer: MemeEditLayer, project: MemeEditProject): MemeEditLayer {
  assertLayerStringsAreBounded(layer);
  if (layer.kind === 'cover') {
    return {
      ...layer,
      rect: normalizeCrop(layer.rect, { x: 0, y: 0, width: 1, height: 1 }),
      pixelSize: roundGeometry(clampNumber(layer.pixelSize, 1, MAX_PIXEL_SIZE, 8)),
    };
  }
  const durationUs = project.source.durationUs ?? 0;
  const active =
    project.source.kind === 'image'
      ? null
      : normalizeActiveRange(layer.active, durationUs, { startUs: 0, endUs: durationUs });
  const minimumTimeUs = active?.startUs ?? 0;
  const maximumTimeUs = active?.endUs ?? 0;
  let keyframes = normalizeTransformKeyframes(layer.keyframes, minimumTimeUs, maximumTimeUs);
  if (keyframes.length === 0) {
    keyframes = [
      {
        timeUs: minimumTimeUs,
        center: { x: 0.5, y: 0.5 },
        scale: 1,
        rotationDegrees: 0,
        opacity: 1,
        easing: 'linear',
      },
    ];
  }
  if (layer.kind === 'text') {
    return {
      ...layer,
      width: roundGeometry(clampNumber(layer.width, 0.01, 1, 0.5)),
      style: {
        ...layer.style,
        outlineScale: clampUnit(layer.style.outlineScale),
        opacity: clampUnit(layer.style.opacity),
      },
      active,
      keyframes,
    };
  }
  if (layer.kind === 'subject') {
    return {
      ...layer,
      subjectIndex:
        layer.subjectIndex === null ? null : Math.max(0, Math.round(layer.subjectIndex)),
      active,
      keyframes,
      outlineScale: clampUnit(layer.outlineScale),
      shadowScale: clampUnit(layer.shadowScale),
    };
  }
  return { ...layer, active, keyframes };
}

function normalizeBackground(background: BackgroundSpec): BackgroundSpec {
  assertBoundedString(background.color, MAX_COLOR_LENGTH, 'Background color');
  if (background.assetUri !== null) {
    assertBoundedString(
      background.assetUri,
      PROJECT_LIMITS.maxAssetUriLength,
      'Background asset URI'
    );
  }
  return { ...background, blurScale: clampUnit(background.blurScale) };
}

function externalAssetCount(project: MemeEditProject): number {
  const assets = new Set<string>();
  if (project.background.assetUri !== null) assets.add(project.background.assetUri);
  for (const layer of project.layers) {
    if (layer.kind === 'media') assets.add(layer.assetUri);
  }
  for (const card of project.video?.insertedCards ?? []) assets.add(card.uri);
  return assets.size;
}

function enforceProjectBounds(project: MemeEditProject): MemeEditProject {
  if (externalAssetCount(project) > PROJECT_LIMITS.maxExternalAssets) {
    throw new RangeError(`Project cannot reference more than ${PROJECT_LIMITS.maxExternalAssets} external assets.`);
  }
  return project;
}

export type MemeEditProjectAction =
  | { type: 'set-base-transform'; base: BaseTransform }
  | { type: 'set-video-retained-ranges'; retainedRanges: TimeRangeUs[] }
  | { type: 'set-video-speed'; speed: number }
  | { type: 'set-video-audio'; audio: VideoEditSpec['audio'] }
  | { type: 'add-layer'; layer: MemeEditLayer; index?: number }
  | { type: 'update-layer'; layer: MemeEditLayer }
  | { type: 'remove-layer'; id: string }
  | { type: 'move-layer'; id: string; toIndex: number }
  | { type: 'duplicate-layer'; id: string; newId: string }
  | { type: 'set-layer-active-range'; id: string; active: TimeRangeUs | null }
  | { type: 'set-layer-keyframes'; id: string; keyframes: TransformKeyframe[] }
  | { type: 'set-background'; background: BackgroundSpec }
  | { type: 'set-materialized-source-uri'; uri: string | null }
  | { type: 'set-mask-track-uri'; trackId: string; uri: string }
  | { type: 'remove-mask-track-uri'; trackId: string };

export function reduceMemeEditProject(
  project: MemeEditProject,
  action: MemeEditProjectAction
): MemeEditProject {
  switch (action.type) {
    case 'set-base-transform':
      return { ...project, base: normalizeBase(action.base, project.base) };
    case 'set-video-retained-ranges': {
      if (project.video === null || project.source.durationUs === null) return project;
      const retainedRanges = normalizeRetainedRanges(
        action.retainedRanges,
        project.source.durationUs
      );
      if (retainedRanges.length === 0) return project;
      return { ...project, video: { ...project.video, retainedRanges } };
    }
    case 'set-video-speed':
      if (project.video === null) return project;
      return {
        ...project,
        video: {
          ...project.video,
          speed: roundGeometry(clampNumber(action.speed, 0.5, 2, project.video.speed)),
        },
      };
    case 'set-video-audio':
      if (project.video === null) return project;
      return {
        ...project,
        video: {
          ...project.video,
          audio: {
            muted: Boolean(action.audio.muted),
            volume: roundGeometry(clampNumber(action.audio.volume, 0, 2, project.video.audio.volume)),
          },
        },
      };
    case 'add-layer': {
      if (project.layers.length >= PROJECT_LIMITS.maxLayers) {
        throw new RangeError(`Project cannot contain more than ${PROJECT_LIMITS.maxLayers} layers.`);
      }
      if (project.layers.some((layer) => layer.id === action.layer.id)) {
        throw new Error(`Layer ID "${action.layer.id}" already exists.`);
      }
      const index = Math.max(0, Math.min(action.index ?? project.layers.length, project.layers.length));
      const layers = project.layers.slice();
      layers.splice(index, 0, normalizeLayer(action.layer, project));
      return enforceProjectBounds({ ...project, layers });
    }
    case 'update-layer': {
      const index = project.layers.findIndex((layer) => layer.id === action.layer.id);
      if (index < 0) return project;
      const layers = project.layers.slice();
      layers[index] = normalizeLayer(action.layer, project);
      return enforceProjectBounds({ ...project, layers });
    }
    case 'remove-layer': {
      const index = project.layers.findIndex((layer) => layer.id === action.id);
      if (index < 0) return project;
      return {
        ...project,
        layers: [...project.layers.slice(0, index), ...project.layers.slice(index + 1)],
      };
    }
    case 'move-layer': {
      const index = project.layers.findIndex((layer) => layer.id === action.id);
      if (index < 0) return project;
      const toIndex = Math.max(0, Math.min(Math.round(action.toIndex), project.layers.length - 1));
      if (index === toIndex) return project;
      const layers = project.layers.slice();
      const [layer] = layers.splice(index, 1);
      layers.splice(toIndex, 0, layer);
      return { ...project, layers };
    }
    case 'duplicate-layer': {
      assertBoundedString(action.newId, MAX_ID_LENGTH, 'Duplicate layer ID');
      if (project.layers.length >= PROJECT_LIMITS.maxLayers) {
        throw new RangeError(`Project cannot contain more than ${PROJECT_LIMITS.maxLayers} layers.`);
      }
      if (project.layers.some((layer) => layer.id === action.newId)) {
        throw new Error(`Layer ID "${action.newId}" already exists.`);
      }
      const index = project.layers.findIndex((layer) => layer.id === action.id);
      if (index < 0) return project;
      const duplicate = cloneLayer(project.layers[index]);
      duplicate.id = action.newId;
      const layers = project.layers.slice();
      layers.splice(index + 1, 0, duplicate);
      return enforceProjectBounds({ ...project, layers });
    }
    case 'set-layer-active-range': {
      const index = project.layers.findIndex((layer) => layer.id === action.id);
      const candidate = project.layers[index];
      if (candidate === undefined || candidate.kind === 'cover') return project;
      const original = candidate;
      const durationUs = project.source.durationUs ?? 0;
      const active =
        project.source.kind === 'image'
          ? null
          : normalizeActiveRange(action.active, durationUs, original.active);
      const minimumTimeUs = active?.startUs ?? 0;
      const maximumTimeUs = active?.endUs ?? 0;
      const keyframes = normalizeTransformKeyframes(
        original.keyframes,
        minimumTimeUs,
        maximumTimeUs
      );
      const layers = project.layers.slice();
      layers[index] = { ...original, active, keyframes };
      return { ...project, layers };
    }
    case 'set-layer-keyframes': {
      const index = project.layers.findIndex((layer) => layer.id === action.id);
      const candidate = project.layers[index];
      if (candidate === undefined || candidate.kind === 'cover') return project;
      const original = candidate;
      const minimumTimeUs = original.active?.startUs ?? 0;
      const maximumTimeUs = original.active?.endUs ?? 0;
      const keyframes = normalizeTransformKeyframes(
        action.keyframes,
        minimumTimeUs,
        maximumTimeUs
      );
      if (keyframes.length === 0) return project;
      const layers = project.layers.slice();
      layers[index] = { ...original, keyframes };
      return { ...project, layers };
    }
    case 'set-background':
      return enforceProjectBounds({
        ...project,
        background: normalizeBackground(action.background),
      });
    case 'set-materialized-source-uri':
      if (action.uri !== null) {
        assertBoundedString(
          action.uri,
          PROJECT_LIMITS.maxAssetUriLength,
          'Materialized source URI'
        );
      }
      return {
        ...project,
        transient: { ...project.transient, materializedSourceUri: action.uri },
      };
    case 'set-mask-track-uri': {
      assertBoundedString(action.trackId, MAX_ID_LENGTH, 'Mask track ID');
      assertBoundedString(
        action.uri,
        PROJECT_LIMITS.maxAssetUriLength,
        'Mask track URI'
      );
      const alreadyExists = Object.prototype.hasOwnProperty.call(
        project.transient.maskTracks,
        action.trackId
      );
      if (!alreadyExists && Object.keys(project.transient.maskTracks).length >= PROJECT_LIMITS.maxMaskTracks) {
        throw new RangeError(`Project cannot contain more than ${PROJECT_LIMITS.maxMaskTracks} mask tracks.`);
      }
      return {
        ...project,
        transient: {
          ...project.transient,
          maskTracks: { ...project.transient.maskTracks, [action.trackId]: action.uri },
        },
      };
    }
    case 'remove-mask-track-uri': {
      if (!Object.prototype.hasOwnProperty.call(project.transient.maskTracks, action.trackId)) {
        return project;
      }
      const maskTracks = { ...project.transient.maskTracks };
      delete maskTracks[action.trackId];
      return { ...project, transient: { ...project.transient, maskTracks } };
    }
  }
}

export interface ProjectHistory {
  past: MemeEditProject[];
  present: MemeEditProject;
  future: MemeEditProject[];
  transaction: { baseline: MemeEditProject } | null;
}

export function createProjectHistory(project: MemeEditProject): ProjectHistory {
  return { past: [], present: project, future: [], transaction: null };
}

export function beginProjectTransaction(history: ProjectHistory): ProjectHistory {
  if (history.transaction !== null) return history;
  return { ...history, transaction: { baseline: history.present } };
}

export function applyProjectAction(
  history: ProjectHistory,
  action: MemeEditProjectAction
): ProjectHistory {
  const present = reduceMemeEditProject(history.present, action);
  if (present === history.present) return history;
  if (history.transaction !== null) return { ...history, present };
  const past = [...history.past, history.present].slice(-MAX_HISTORY_STATES);
  return { past, present, future: [], transaction: null };
}

export function commitProjectTransaction(history: ProjectHistory): ProjectHistory {
  if (history.transaction === null) return history;
  const baseline = history.transaction.baseline;
  if (history.present === baseline) return { ...history, transaction: null };
  const past = [...history.past, baseline].slice(-MAX_HISTORY_STATES);
  return { past, present: history.present, future: [], transaction: null };
}

export function cancelProjectTransaction(history: ProjectHistory): ProjectHistory {
  if (history.transaction === null) return history;
  return { ...history, present: history.transaction.baseline, transaction: null };
}

export function undoProjectHistory(history: ProjectHistory): ProjectHistory {
  if (history.transaction !== null || history.past.length === 0) return history;
  const previous = history.past[history.past.length - 1];
  return {
    past: history.past.slice(0, -1),
    present: previous,
    future: [history.present, ...history.future].slice(0, MAX_HISTORY_STATES),
    transaction: null,
  };
}

export function redoProjectHistory(history: ProjectHistory): ProjectHistory {
  if (history.transaction !== null || history.future.length === 0) return history;
  const [next, ...future] = history.future;
  return {
    past: [...history.past, history.present].slice(-MAX_HISTORY_STATES),
    present: next,
    future,
    transaction: null,
  };
}
