import {
  applyProjectAction,
  beginProjectTransaction,
  commitProjectTransaction,
  type MemeEditLayer,
  type MemeEditProject,
  type MemeEditProjectAction,
  type ProjectHistory,
  type QuarterRotation,
  type TransformKeyframe,
  type NormalizedPoint,
} from './memeEditProjectCore';

export interface ViewSize {
  width: number;
  height: number;
}

export interface MediaDisplaySize {
  width: number;
  height: number;
  rotation?: QuarterRotation;
}

export interface ViewPoint {
  x: number;
  y: number;
}

export interface ViewDelta {
  dx: number;
  dy: number;
}

export interface ViewRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface AbsoluteRectStyle {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface LayerHandlePoints {
  center: ViewPoint;
  resize: ViewPoint;
  rotate: ViewPoint;
}

export type LayerHandleTouchKind = 'resize' | 'rotate';
export type TransformAccessibilityAction = 'increment' | 'decrement' | 'longpress' | 'escape';
export type TransformHandleKind = 'resize' | 'rotate';

export interface CanvasLayerDescriptor {
  id: string;
  kind: MemeEditLayer['kind'];
  unavailable: boolean;
  label: string;
}

const MIN_SCALE = 0.01;
const MAX_SCALE = 16;
const TRANSFORM_HANDLE_SIZE = 44;
const TRANSFORM_HANDLE_HALF = TRANSFORM_HANDLE_SIZE / 2;
const ROTATE_HANDLE_TOP = -44;

function layerRenderedSize(layerWidth: number, mediaRect: ViewRect, scale: number): { width: number; height: number } {
  const width = Math.max(TRANSFORM_HANDLE_SIZE, mediaRect.width * Math.max(0.04, layerWidth) * clampScale(scale));
  return { width, height: width };
}
const EPSILON = 1e-6;

function finite(value: number): boolean {
  return Number.isFinite(value);
}

function finitePositive(value: number): boolean {
  return finite(value) && value > 0;
}

function roundCanvas(value: number): number {
  if (!finite(value)) return 0;
  return Math.round(value * 1_000_000) / 1_000_000;
}

function clampUnit(value: number): number {
  if (!finite(value)) return 0;
  return roundCanvas(Math.max(0, Math.min(1, value)));
}

function clampScale(value: number): number {
  if (!finite(value)) return MIN_SCALE;
  return roundCanvas(Math.max(MIN_SCALE, Math.min(MAX_SCALE, value)));
}

function validRect(rect: ViewRect): boolean {
  return finite(rect.x) && finite(rect.y) && finitePositive(rect.width) && finitePositive(rect.height);
}

function validPoint(point: ViewPoint): boolean {
  return finite(point.x) && finite(point.y);
}

export function containedMediaRect(view: ViewSize, media: MediaDisplaySize): ViewRect | null {
  if (!finitePositive(view.width) || !finitePositive(view.height)) return null;
  const rotated = media.rotation === 90 || media.rotation === 270;
  const mediaWidth = rotated ? media.height : media.width;
  const mediaHeight = rotated ? media.width : media.height;
  if (!finitePositive(mediaWidth) || !finitePositive(mediaHeight)) return null;

  const scale = Math.min(view.width / mediaWidth, view.height / mediaHeight);
  if (!finitePositive(scale)) return null;
  const width = mediaWidth * scale;
  const height = mediaHeight * scale;
  return {
    x: roundCanvas((view.width - width) / 2),
    y: roundCanvas((view.height - height) / 2),
    width: roundCanvas(width),
    height: roundCanvas(height),
  };
}

export function viewRectToAbsoluteStyle(rect: ViewRect): AbsoluteRectStyle {
  return { left: rect.x, top: rect.y, width: rect.width, height: rect.height };
}

export function gesturePointInsideMedia(point: ViewPoint, mediaRect: ViewRect): boolean {
  return viewPointToNormalizedPoint(point, mediaRect) !== null;
}

export function gestureMoveShouldClaim(startAccepted: boolean, delta: ViewDelta): boolean {
  return startAccepted && finite(delta.dx) && finite(delta.dy) && Math.abs(delta.dx) + Math.abs(delta.dy) > 2;
}

export function viewPointToNormalizedPoint(point: ViewPoint, mediaRect: ViewRect): NormalizedPoint | null {
  if (!validPoint(point) || !validRect(mediaRect)) return null;
  if (
    point.x < mediaRect.x ||
    point.x > mediaRect.x + mediaRect.width ||
    point.y < mediaRect.y ||
    point.y > mediaRect.y + mediaRect.height
  ) {
    return null;
  }
  return {
    x: clampUnit((point.x - mediaRect.x) / mediaRect.width),
    y: clampUnit((point.y - mediaRect.y) / mediaRect.height),
  };
}

export function normalizedPointToViewPoint(point: NormalizedPoint, mediaRect: ViewRect): ViewPoint {
  if (!validRect(mediaRect)) return { x: 0, y: 0 };
  return {
    x: roundCanvas(mediaRect.x + clampUnit(point.x) * mediaRect.width),
    y: roundCanvas(mediaRect.y + clampUnit(point.y) * mediaRect.height),
  };
}

export function dragKeyframeByViewDelta(
  start: TransformKeyframe,
  delta: ViewDelta,
  mediaRect: ViewRect
): TransformKeyframe {
  if (!validRect(mediaRect) || !finite(delta.dx) || !finite(delta.dy)) return start;
  return {
    ...start,
    center: {
      x: clampUnit(start.center.x + delta.dx / mediaRect.width),
      y: clampUnit(start.center.y + delta.dy / mediaRect.height),
    },
  };
}

function distance(a: ViewPoint, b: ViewPoint): number {
  if (!validPoint(a) || !validPoint(b)) return Number.NaN;
  return Math.hypot(a.x - b.x, a.y - b.y);
}

export function resizeKeyframeFromHandle(
  start: TransformKeyframe,
  center: ViewPoint,
  startHandle: ViewPoint,
  currentHandle: ViewPoint
): TransformKeyframe {
  const startDistance = distance(center, startHandle);
  const currentDistance = distance(center, currentHandle);
  if (!finitePositive(startDistance) || !finitePositive(currentDistance)) return start;
  return { ...start, scale: clampScale(start.scale * (currentDistance / startDistance)) };
}

function angleDegrees(center: ViewPoint, point: ViewPoint): number {
  if (!validPoint(center) || !validPoint(point)) return Number.NaN;
  const dx = point.x - center.x;
  const dy = point.y - center.y;
  if (Math.hypot(dx, dy) <= EPSILON) return Number.NaN;
  return Math.atan2(dy, dx) * 180 / Math.PI;
}

function normalizeAngleDelta(delta: number): number {
  if (!finite(delta)) return 0;
  let value = delta;
  while (value > 180) value -= 360;
  while (value < -180) value += 360;
  return value;
}

export function rotateKeyframeFromHandle(
  start: TransformKeyframe,
  center: ViewPoint,
  startHandle: ViewPoint,
  currentHandle: ViewPoint
): TransformKeyframe {
  const startAngle = angleDegrees(center, startHandle);
  const currentAngle = angleDegrees(center, currentHandle);
  if (!finite(startAngle) || !finite(currentAngle)) return start;
  return { ...start, rotationDegrees: roundCanvas(start.rotationDegrees + normalizeAngleDelta(currentAngle - startAngle)) };
}

export function layerHandlePoints(
  keyframe: TransformKeyframe,
  layerWidth: number,
  mediaRect: ViewRect
): LayerHandlePoints {
  const center = normalizedPointToViewPoint(keyframe.center, mediaRect);
  const size = layerRenderedSize(layerWidth, mediaRect, keyframe.scale);
  const width = size.width;
  const height = size.height;
  const radians = keyframe.rotationDegrees * Math.PI / 180;
  const cos = Math.cos(radians);
  const sin = Math.sin(radians);
  const rotate = (x: number, y: number): ViewPoint => ({
    x: roundCanvas(center.x + x * cos - y * sin),
    y: roundCanvas(center.y + x * sin + y * cos),
  });
  return {
    center,
    resize: rotate(width / 2, height / 2),
    rotate: layerLocalPointToCanvasPoint(keyframe, layerWidth, mediaRect, { x: size.width / 2, y: ROTATE_HANDLE_TOP + TRANSFORM_HANDLE_HALF }),
  };
}

export function layerLocalPointToCanvasPoint(
  keyframe: TransformKeyframe,
  layerWidth: number,
  mediaRect: ViewRect,
  localPoint: ViewPoint
): ViewPoint {
  const center = normalizedPointToViewPoint(keyframe.center, mediaRect);
  const size = layerRenderedSize(layerWidth, mediaRect, keyframe.scale);
  const width = size.width;
  const height = size.height;
  const x = localPoint.x - width / 2;
  const y = localPoint.y - height / 2;
  const radians = keyframe.rotationDegrees * Math.PI / 180;
  const cos = Math.cos(radians);
  const sin = Math.sin(radians);
  return {
    x: roundCanvas(center.x + x * cos - y * sin),
    y: roundCanvas(center.y + x * sin + y * cos),
  };
}

export function layerHandleTouchInsideMedia(
  keyframe: TransformKeyframe,
  layerWidth: number,
  mediaRect: ViewRect,
  handle: LayerHandleTouchKind,
  handleLocalPoint: ViewPoint
): boolean {
  const size = layerRenderedSize(layerWidth, mediaRect, keyframe.scale);
  const localPoint = handle === 'resize'
    ? { x: size.width - TRANSFORM_HANDLE_HALF + handleLocalPoint.x, y: size.height - TRANSFORM_HANDLE_HALF + handleLocalPoint.y }
    : { x: size.width / 2 - TRANSFORM_HANDLE_HALF + handleLocalPoint.x, y: ROTATE_HANDLE_TOP + handleLocalPoint.y };
  return gesturePointInsideMedia(layerLocalPointToCanvasPoint(keyframe, layerWidth, mediaRect, localPoint), mediaRect);
}

export function transformAccessibilityAction(
  action: TransformAccessibilityAction,
  keyframe: TransformKeyframe,
  mediaRect: ViewRect
): TransformKeyframe {
  if (action === 'increment') {
    return dragKeyframeByViewDelta(keyframe, { dx: mediaRect.width * 0.01, dy: 0 }, mediaRect);
  }
  if (action === 'decrement') {
    return dragKeyframeByViewDelta(keyframe, { dx: -mediaRect.width * 0.01, dy: 0 }, mediaRect);
  }
  if (action === 'longpress') {
    return { ...keyframe, scale: clampScale(keyframe.scale * 1.05) };
  }
  return keyframe;
}

export function transformHandleAccessibilityAction(
  handle: TransformHandleKind,
  action: TransformAccessibilityAction,
  keyframe: TransformKeyframe,
  _mediaRect: ViewRect
): TransformKeyframe {
  if (handle === 'resize') {
    if (action === 'increment') return { ...keyframe, scale: clampScale(keyframe.scale * 1.05) };
    if (action === 'decrement') return { ...keyframe, scale: clampScale(keyframe.scale / 1.05) };
    return keyframe;
  }
  if (action === 'increment') return { ...keyframe, rotationDegrees: roundCanvas(keyframe.rotationDegrees + 5) };
  if (action === 'decrement') return { ...keyframe, rotationDegrees: roundCanvas(keyframe.rotationDegrees - 5) };
  return keyframe;
}

export function nextDuplicateLayerId(prefix: string, ids: readonly string[]): string {
  const escaped = prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const suffixPattern = new RegExp(`^${escaped}-dup-(\\d+)$`);
  let maximum = 0;
  for (const id of ids) {
    const match = suffixPattern.exec(id);
    if (!match) continue;
    const value = Number(match[1]);
    if (Number.isSafeInteger(value) && value > maximum) maximum = value;
  }
  return `${prefix}-dup-${maximum + 1}`;
}

export function describeCanvasLayers(project: MemeEditProject): CanvasLayerDescriptor[] {
  return project.layers.map((layer) => {
    if (layer.kind === 'text') return { id: layer.id, kind: layer.kind, unavailable: false, label: 'Text layer' };
    if (layer.kind === 'cover') return { id: layer.id, kind: layer.kind, unavailable: false, label: layer.mode === 'pixelate' ? 'Pixelate region' : 'Cover region' };
    if (layer.kind === 'subject') {
      const unavailable = !project.transient.maskTracks[layer.maskTrackId];
      return { id: layer.id, kind: layer.kind, unavailable, label: unavailable ? 'Subject mask unavailable' : 'Subject mask' };
    }
    return { id: layer.id, kind: layer.kind, unavailable: false, label: layer.assetKind === 'video' ? 'Video overlay' : 'Image overlay' };
  });
}

export function commitGestureTransaction(
  history: ProjectHistory,
  actions: readonly MemeEditProjectAction[]
): ProjectHistory {
  if (actions.length === 0) return history;
  let next = beginProjectTransaction(history);
  for (const action of actions) {
    next = applyProjectAction(next, action);
  }
  return commitProjectTransaction(next);
}
