// Pure logic for the draw tool: the settings a drawing session carries, how a
// gesture becomes a `DrawElement`, and how elements accumulate on a `DrawLayer`.
//
// Kept out of the React component so the geometry rules — point thinning, the
// minimum extent below which a shape is a stray tap, the element cap — are
// unit-tested and cannot drift from what the renderer expects. Points here are
// normalized [0,1] in the working (post base-transform) canvas: the same space
// `cover.rect` and the render plan speak, so no coordinate re-derivation
// happens between the finger and the exported pixel.
import {
  MAX_DRAW_STROKE_SCALE,
  PROJECT_LIMITS,
  type DrawElement,
  type DrawLayer,
  type DrawShape,
  type MemeEditLayer,
  type NormalizedPoint,
} from './memeEditProjectCore';

// A readable, high-contrast starter palette. First entry is the default — a
// red that reads as "cross this out" on most memes.
export const DRAW_COLORS = [
  '#ff2d55',
  '#ffcc00',
  '#34c759',
  '#0a84ff',
  '#af52de',
  '#ffffff',
  '#000000',
] as const;

export interface DrawStrokePreset {
  id: 'fine' | 'medium' | 'bold';
  label: string;
  // Fraction of the canvas short edge, matching DrawElement.strokeScale.
  scale: number;
}

export const DRAW_STROKE_PRESETS: readonly DrawStrokePreset[] = [
  { id: 'fine', label: 'Fine', scale: 0.006 },
  { id: 'medium', label: 'Medium', scale: 0.014 },
  { id: 'bold', label: 'Bold', scale: 0.03 },
];

// Rail order for the shape picker. `free` first: freehand is the default and
// the one most people reach for to scribble something out.
export const DRAW_SHAPE_ORDER: readonly DrawShape[] = ['free', 'line', 'arrow', 'rectangle', 'ellipse'];

export const DRAW_SHAPE_LABELS: Readonly<Record<DrawShape, string>> = {
  free: 'Draw',
  line: 'Line',
  arrow: 'Arrow',
  rectangle: 'Box',
  ellipse: 'Circle',
};

export interface DrawSettings {
  shape: DrawShape;
  color: string;
  // Fraction of the canvas short edge; bounded by MAX_DRAW_STROKE_SCALE.
  strokeScale: number;
  filled: boolean;
}

export const DEFAULT_DRAW_SETTINGS: DrawSettings = {
  shape: 'free',
  color: DRAW_COLORS[0],
  strokeScale: DRAW_STROKE_PRESETS[1].scale,
  filled: false,
};

// A slow finger emits touch moves far faster than the stroke needs; points
// closer than this (in normalized units) are dropped so a scribble is tens of
// points, not thousands, and stays inside the per-element cap.
export const DRAW_MIN_POINT_DISTANCE = 0.004;

// Below this drag distance a fixed shape is a stray tap, not a shape, and
// building one would litter the layer with zero-size boxes.
export const DRAW_SHAPE_MIN_EXTENT = 0.01;

export function createDrawLayer(id: string): DrawLayer {
  return { id, kind: 'draw', opacity: 1, active: null, elements: [] };
}

function clampStrokeScale(scale: number): number {
  if (!Number.isFinite(scale)) return DEFAULT_DRAW_SETTINGS.strokeScale;
  return Math.min(MAX_DRAW_STROKE_SCALE, Math.max(1e-4, scale));
}

// Append a captured point to an in-progress freehand stroke, thinning points
// that land within `minDistance` of the last kept one. Returns the SAME array
// reference when the point is dropped, so a caller can cheaply skip a re-render.
export function appendFreehandPoint(
  points: readonly NormalizedPoint[],
  point: NormalizedPoint,
  minDistance = DRAW_MIN_POINT_DISTANCE
): NormalizedPoint[] {
  const last = points[points.length - 1];
  if (last && Math.hypot(point.x - last.x, point.y - last.y) < minDistance) {
    return points as NormalizedPoint[];
  }
  // A single stroke never exceeds the per-element ceiling: past it, keep
  // capturing nothing so the committed element stays inside the model bound and
  // the live preview stops growing.
  if (points.length >= PROJECT_LIMITS.maxPointsPerDrawElement) return points as NormalizedPoint[];
  return [...points, { x: point.x, y: point.y }];
}

// Turn a finished gesture into a committable element, or null when the gesture
// drew nothing (an empty freehand path, or a fixed shape dragged nowhere).
export function buildDrawElement(
  settings: DrawSettings,
  points: readonly NormalizedPoint[]
): DrawElement | null {
  const strokeScale = clampStrokeScale(settings.strokeScale);
  if (settings.shape === 'free') {
    if (points.length === 0) return null;
    const capped = points.length > PROJECT_LIMITS.maxPointsPerDrawElement
      ? points.slice(0, PROJECT_LIMITS.maxPointsPerDrawElement)
      : points;
    return { shape: 'free', color: settings.color, strokeScale, filled: false, points: capped.map((point) => ({ ...point })) };
  }
  const start = points[0];
  const end = points[points.length - 1];
  if (!start || !end) return null;
  if (Math.hypot(end.x - start.x, end.y - start.y) < DRAW_SHAPE_MIN_EXTENT) return null;
  const filled = settings.shape === 'rectangle' || settings.shape === 'ellipse' ? settings.filled : false;
  return { shape: settings.shape, color: settings.color, strokeScale, filled, points: [{ ...start }, { ...end }] };
}

export function drawLayerPointCount(layer: DrawLayer): number {
  return layer.elements.reduce((total, element) => total + element.points.length, 0);
}

export function isDrawLayerFull(layer: DrawLayer, maxElements: number = PROJECT_LIMITS.maxDrawElements): boolean {
  return layer.elements.length >= maxElements;
}

// Whether `element` fits within BOTH the element count and the per-layer point
// budget — the two ceilings normalizeDrawElements throws past. Checked before a
// commit so a full drawing refuses gracefully instead of throwing in a reducer.
export function canAppendElement(layer: DrawLayer, element: DrawElement): boolean {
  if (layer.elements.length >= PROJECT_LIMITS.maxDrawElements) return false;
  return drawLayerPointCount(layer) + element.points.length <= PROJECT_LIMITS.maxDrawPointsPerLayer;
}

// Append one element immutably; returns the layer unchanged when it would break
// either ceiling so a caller can surface "that's the last mark this layer holds".
export function withAppendedElement(layer: DrawLayer, element: DrawElement): DrawLayer {
  if (!canAppendElement(layer, element)) return layer;
  return { ...layer, elements: [...layer.elements, element] };
}

export function withClearedElements(layer: DrawLayer): DrawLayer {
  return { ...layer, elements: [] };
}

// Drop the most recent mark — the draw tool's "undo last" that stays local to
// the active drawing rather than walking global history.
export function withoutLastElement(layer: DrawLayer): DrawLayer {
  if (layer.elements.length === 0) return layer;
  return { ...layer, elements: layer.elements.slice(0, -1) };
}

export function drawLayerSummary(layer: DrawLayer): string {
  const count = layer.elements.length;
  return count === 0 ? 'Empty drawing' : `${count} mark${count === 1 ? '' : 's'}`;
}

// The draw layer a new mark should land on: the selected layer when it is a
// drawing, otherwise the top-most drawing, otherwise none (the caller creates
// one). Keeps "where does this stroke go" out of the component and testable.
export function activeDrawLayer(
  layers: readonly MemeEditLayer[],
  selectedLayerId: string | null
): DrawLayer | null {
  const selected = selectedLayerId ? layers.find((layer) => layer.id === selectedLayerId) : undefined;
  if (selected && selected.kind === 'draw') return selected;
  for (let index = layers.length - 1; index >= 0; index -= 1) {
    const layer = layers[index];
    if (layer.kind === 'draw') return layer;
  }
  return null;
}
