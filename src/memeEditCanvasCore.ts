import {
  applyProjectAction,
  beginProjectTransaction,
  commitProjectTransaction,
  isLayerActiveAt,
  redoProjectHistory,
  undoProjectHistory,
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

export interface CanvasLayerVisualDescriptor {
  center: ViewPoint;
  content: {
    baseWidthDip: number;
    baseHeightDip: number;
    scale: number;
    rotationDegrees: number;
  };
  controls: {
    widthDip: number;
    heightDip: number;
    handleSizeDip: 44;
    rotationDegrees: number;
  };
}

export type LayerHandleTouchKind = 'resize' | 'rotate';
export type TransformAccessibilityAction = 'increment' | 'decrement' | 'longpress' | 'escape';
export type TransformHandleKind = 'resize' | 'rotate';

export interface CapturedTransformGesture {
  keyframe: TransformKeyframe;
  timeUs: number;
}

export type ProjectHistoryCommand = 'undo' | 'redo';

export type MemeEditToolId =
  | 'layers'
  | 'text'
  | 'transform'
  | 'replace-text'
  | 'timeline'
  | 'frames'
  | 'motion'
  | 'audio';

export function memeEditToolsForSource(kind: MemeEditProject['source']['kind']): MemeEditToolId[] {
  return kind === 'image'
    ? ['layers', 'text', 'transform', 'replace-text']
    : ['layers', 'text', 'timeline', 'frames', 'motion', 'audio'];
}

// One 60fps frame. Below this a report is decoder jitter rather than a new
// frame, and re-evaluating every overlay for it buys nothing visible.
const PLAYHEAD_DEAD_BAND_US = 16_667;

/**
 * The preview playhead after a player time report. This is the ONLY thing that
 * moves it: the Before/After hold is not an input here, which is what makes
 * releasing the hold land on the frame it hid rather than on a reset player.
 */
export function nextCanvasPlayheadUs(
  currentUs: number,
  reportedUs: number,
  durationUs: number | null
): number {
  const boundedUs = durationUs === null || durationUs <= 0 ? reportedUs : Math.min(durationUs, reportedUs);
  return Math.abs(currentUs - boundedUs) < PLAYHEAD_DEAD_BAND_US ? currentUs : boundedUs;
}

/**
 * Whether an overlay is off screen at this instant. Before/After is a pure
 * render-time answer laid over the layer's own active range — it suppresses
 * drawing and nothing else.
 */
export function canvasLayerHidden(layer: MemeEditLayer, timeUs: number, before: boolean): boolean {
  return before || !isLayerActiveAt(layer, timeUs);
}

export interface ProjectHistoryCommandAvailability {
  canUndo: boolean;
  canRedo: boolean;
}

export interface CanvasLayerDescriptor {
  id: string;
  kind: MemeEditLayer['kind'];
  unavailable: boolean;
  label: string;
}

export type StudioHeaderRowKey = 'single' | 'identity' | 'commands';

export interface StudioHeaderRowLayout {
  key: StudioHeaderRowKey;
  controls: string[];
  maxWidth: number;
  minControlSize: number;
}

export interface StudioHeaderLayout {
  mode: 'single-row' | 'compact-two-row';
  showFullExportLabel: boolean;
  exportLabel: 'Export' | 'Out';
  rows: StudioHeaderRowLayout[];
}

export interface StudioExportControlInput {
  ready: boolean;
  exportBusy: boolean;
  discarding: boolean;
  hasExport: boolean;
}

export interface StudioExportControlState {
  label: 'Export' | 'Out' | 'No export' | 'Export unavailable';
  disabled: boolean;
  accessibilityState: { disabled: boolean };
}

const MIN_SCALE = 0.01;
const MAX_SCALE = 16;
const TRANSFORM_HANDLE_SIZE = 44;
const TRANSFORM_HANDLE_HALF = TRANSFORM_HANDLE_SIZE / 2;

function layerBaseSize(layerWidth: number, mediaRect: ViewRect): { width: number; height: number } {
  const width = Math.max(TRANSFORM_HANDLE_SIZE, mediaRect.width * Math.max(0.04, layerWidth));
  return { width, height: width };
}

function layerRenderedSize(layerWidth: number, mediaRect: ViewRect, scale: number): { width: number; height: number } {
  const base = layerBaseSize(layerWidth, mediaRect);
  const boundedScale = clampScale(scale);
  return { width: base.width * boundedScale, height: base.height * boundedScale };
}
const EPSILON = 1e-6;

function finite(value: number): boolean {
  return Number.isFinite(value);
}

export function canDuplicateLayer(layerCount: number, maxLayers: number): boolean {
  return finite(layerCount) && finite(maxLayers) && layerCount < maxLayers;
}

export function beforeAfterPointerNextState(_current: boolean, event: 'press-in' | 'press-out'): boolean {
  return event === 'press-in';
}

export function beforeAfterAccessibilityNextState(current: boolean, action: 'activate'): boolean {
  return action === 'activate' ? !current : current;
}

export function memeRemixHeaderLayout(width: number): StudioHeaderLayout {
  if (width < 430) {
    return {
      mode: 'compact-two-row',
      showFullExportLabel: false,
      exportLabel: 'Out',
      rows: [
        { key: 'identity', controls: ['Cancel', 'TitleStatus'], maxWidth: width, minControlSize: 44 },
        { key: 'commands', controls: ['Before', 'Undo', 'Redo', 'Out'], maxWidth: width, minControlSize: 44 },
      ],
    };
  }
  return {
    mode: 'single-row',
    showFullExportLabel: true,
    exportLabel: 'Export',
    rows: [
      { key: 'single', controls: ['Cancel', 'TitleStatus', 'Before', 'Undo', 'Redo', 'Export'], maxWidth: width, minControlSize: 44 },
    ],
  };
}

export function memeRemixExportControlState(
  layout: StudioHeaderLayout,
  input: StudioExportControlInput
): StudioExportControlState {
  const disabled = !input.ready || input.exportBusy || input.discarding || !input.hasExport;
  const label = input.hasExport
    ? layout.exportLabel
    : layout.mode === 'compact-two-row'
      ? 'No export'
      : 'Export unavailable';
  return {
    label,
    disabled,
    accessibilityState: { disabled },
  };
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

export function canvasLayerVisualDescriptor(
  keyframe: TransformKeyframe,
  layerWidth: number,
  mediaRect: ViewRect
): CanvasLayerVisualDescriptor {
  const center = normalizedPointToViewPoint(keyframe.center, mediaRect);
  const base = layerBaseSize(layerWidth, mediaRect);
  const scale = clampScale(keyframe.scale);
  return {
    center,
    content: {
      baseWidthDip: base.width,
      baseHeightDip: base.height,
      scale,
      rotationDegrees: keyframe.rotationDegrees,
    },
    controls: {
      widthDip: base.width * scale,
      heightDip: base.height * scale,
      handleSizeDip: TRANSFORM_HANDLE_SIZE,
      rotationDegrees: keyframe.rotationDegrees,
    },
  };
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
    rotate: rotate(0, -height / 2 - TRANSFORM_HANDLE_HALF),
  };
}

export function layerLocalPointToCanvasPoint(
  keyframe: TransformKeyframe,
  layerWidth: number,
  mediaRect: ViewRect,
  localPoint: ViewPoint
): ViewPoint {
  const center = normalizedPointToViewPoint(keyframe.center, mediaRect);
  const base = layerBaseSize(layerWidth, mediaRect);
  const scale = clampScale(keyframe.scale);
  const x = (localPoint.x - base.width / 2) * scale;
  const y = (localPoint.y - base.height / 2) * scale;
  const radians = keyframe.rotationDegrees * Math.PI / 180;
  const cos = Math.cos(radians);
  const sin = Math.sin(radians);
  return {
    x: roundCanvas(center.x + x * cos - y * sin),
    y: roundCanvas(center.y + x * sin + y * cos),
  };
}

export function layerBodyTouchInsideMedia(
  keyframe: TransformKeyframe,
  layerWidth: number,
  mediaRect: ViewRect,
  localPoint: ViewPoint
): boolean {
  return gesturePointInsideMedia(layerLocalPointToCanvasPoint(keyframe, layerWidth, mediaRect, localPoint), mediaRect);
}

export function layerHandleTouchInsideMedia(
  keyframe: TransformKeyframe,
  layerWidth: number,
  mediaRect: ViewRect,
  handle: LayerHandleTouchKind,
  handleLocalPoint: ViewPoint
): boolean {
  const size = layerRenderedSize(layerWidth, mediaRect, keyframe.scale);
  const offset = { x: handleLocalPoint.x - TRANSFORM_HANDLE_HALF, y: handleLocalPoint.y - TRANSFORM_HANDLE_HALF };
  const vector = handle === 'resize'
    ? { x: size.width / 2 + offset.x, y: size.height / 2 + offset.y }
    : { x: offset.x, y: -size.height / 2 - TRANSFORM_HANDLE_HALF + offset.y };
  const center = normalizedPointToViewPoint(keyframe.center, mediaRect);
  const radians = keyframe.rotationDegrees * Math.PI / 180;
  const point = {
    x: roundCanvas(center.x + vector.x * Math.cos(radians) - vector.y * Math.sin(radians)),
    y: roundCanvas(center.y + vector.x * Math.sin(radians) + vector.y * Math.cos(radians)),
  };
  return gesturePointInsideMedia(point, mediaRect);
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

export function captureTransformGesture(keyframe: TransformKeyframe, timeUs: number): CapturedTransformGesture {
  return {
    keyframe: { ...keyframe, center: { ...keyframe.center } },
    timeUs,
  };
}

export function upsertLayerKeyframeAtCapturedTime(
  keyframes: readonly TransformKeyframe[],
  keyframe: TransformKeyframe,
  timeUs: number
): TransformKeyframe[] {
  const nextKeyframe = { ...keyframe, center: { ...keyframe.center }, timeUs };
  const existingIndex = keyframes.findIndex((candidate) => candidate.timeUs === timeUs);
  if (existingIndex >= 0) {
    return keyframes.map((candidate, index) => index === existingIndex ? nextKeyframe : candidate);
  }
  return [...keyframes, nextKeyframe].sort((left, right) => left.timeUs - right.timeUs);
}

export function projectHistoryCommandAvailability(history: ProjectHistory): ProjectHistoryCommandAvailability {
  return {
    canUndo: history.transaction !== null || history.past.length > 0,
    canRedo: history.transaction === null && history.future.length > 0,
  };
}

export function runProjectHistoryCommand(
  command: ProjectHistoryCommand,
  flushPendingText: () => void,
  updateHistory: (updater: (history: ProjectHistory) => ProjectHistory) => void,
  announceExternalTextRevision: () => void
): void {
  flushPendingText();
  updateHistory(command === 'undo' ? undoProjectHistory : redoProjectHistory);
  announceExternalTextRevision();
}

export function selectedLayerIdAfterDelete(
  orderedIds: readonly string[],
  deletedId: string,
  selectedLayerId: string | null
): string | null {
  if (selectedLayerId !== deletedId) return selectedLayerId;
  const deletedIndex = orderedIds.indexOf(deletedId);
  if (deletedIndex < 0) return null;
  return orderedIds[deletedIndex + 1] ?? orderedIds[deletedIndex - 1] ?? null;
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
