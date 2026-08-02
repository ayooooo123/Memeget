import {
  applyProjectAction,
  beginProjectTransaction,
  commitProjectTransaction,
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

const MIN_SCALE = 0.01;
const MAX_SCALE = 16;
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
