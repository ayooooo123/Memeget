// Pure, serializable render plan for the full-resolution image exporter.
//
// The studio preview and the exported PNG have to agree, so this module never
// re-derives geometry that already exists: output pixel size comes from
// `visibleImageDimensions` (the same rotation+crop math the canvas uses) and
// every text layer is resolved through `buildMemeTextLayoutSpec` against the
// output pixel canvas — the same Anton/NotoSans + absolute-line-height + stroke
// contract that MemeTextLayout.kt is already proven against on-device.
//
// The plan is JSON only: no functions, no clock reads, no randomness. The
// native renderer receives exactly what these tests pin down.
import {
  evaluateMaskTrackRect,
  interpolateCoverCorrections,
  interpolateTransformKeyframes,
  isLayerActiveAt,
  type BackgroundSpec,
  type CoverLayer,
  type DrawLayer,
  type DrawShape,
  type MediaOverlayLayer,
  type MemeEditLayer,
  type MemeEditProject,
  type NormalizedPoint,
  type NormalizedRect,
  type QuarterRotation,
  type SubjectLayer,
  type TextLayer,
  type TransformKeyframe,
} from './memeEditProjectCore';
import { normalizeFreeCrop, visibleImageDimensions } from './memeImageEditCore';
import { buildMemeTextLayoutSpec, type MemeTextLayoutSpec } from './memeTextLayoutCore';

export const IMAGE_RENDER_PLAN_VERSION = 1;

// One ARGB_8888 output bitmap costs 4 bytes per pixel, and the renderer holds a
// decoded source bitmap alongside it. 16 MP keeps the pair near 128 MB, which a
// mid-range Android heap survives; anything larger is downscaled with an
// explicit factor rather than being allowed to OOM the app.
export const MAX_IMAGE_RENDER_PIXELS = 16_000_000;

// Media overlays have no intrinsic normalized width, so the canvas gives them a
// fixed square base box (see MemeEditCanvas). Exported here so preview and
// output cannot drift apart.
export const MEME_MEDIA_LAYER_BASE_WIDTH = 0.28;

// A pixelate cover costs one averaged cell per grid square, so cell size — not
// region size — is what bounds the work. At pixelSize 1 a full-canvas cover
// would ask for one cell per output pixel (16M reads plus 16M drawRects) to
// reproduce the image it started from. Cap the cell COUNT instead and let the
// cell edge grow with the region; MemeImageRenderer.kt clamps identically, so
// the plan always states the cell the renderer really uses.
export const MAX_MOSAIC_CELLS = 65_536;

/**
 * Sticker effect sizes, as a fraction of the cutout's own drawn short edge.
 *
 * Unit scales in the project have to become pixels somewhere, and that somewhere
 * has to be shared: the preview resolves them against the preview canvas and the
 * exporter against the output canvas, so a sticker that looks right at 640 px
 * only looks right at 4000 px if BOTH use these numbers. The fractions are tied
 * to the cutout rather than to the canvas so a small sticker gets a small
 * outline instead of a slab.
 */
export const CUTOUT_OUTLINE_MAX_FRACTION = 0.06;
export const CUTOUT_SHADOW_BLUR_MAX_FRACTION = 0.08;
export const CUTOUT_SHADOW_OFFSET_MAX_FRACTION = 0.05;

/** Sticker shadow colour: black at 55%, dark enough to read on any background. */
export const CUTOUT_SHADOW_COLOR = '#8C000000';

export function mosaicCellFloorPx(rect: ImageRenderPixelRect): number {
  const area = Math.max(0, rect.width) * Math.max(0, rect.height);
  return Math.max(1, Math.ceil(Math.sqrt(area / MAX_MOSAIC_CELLS)));
}

// Still images are always resolved at the head of the timeline.
export const IMAGE_RENDER_TIME_US = 0;

export interface ImageRenderPixelRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ImageRenderPlanSource {
  uri: string;
  widthPx: number;
  heightPx: number;
  rotation: QuarterRotation;
  flipX: boolean;
  flipY: boolean;
  crop: NormalizedRect;
}

export interface ImageRenderPlanOutput {
  // Final bitmap size the renderer must allocate.
  widthPx: number;
  heightPx: number;
  // Size the project asked for, before the memory guard.
  fullWidthPx: number;
  fullHeightPx: number;
  downscaled: boolean;
  scale: number;
  maxPixels: number;
}

export interface ImageRenderPlanBackground {
  mode: BackgroundSpec['mode'];
  color: string;
  assetUri: string | null;
  blurScale: number;
}

export interface ImageRenderCoverLayerPlan {
  kind: 'cover';
  id: string;
  rect: ImageRenderPixelRect;
  mode: CoverLayer['mode'];
  color: string;
  // Mosaic cell edge in OUTPUT pixels. The canvas preview samples a coarser,
  // grid-capped approximation; this is the real output cell size.
  pixelSizePx: number;
}

export interface ImageRenderTextLayerPlan {
  kind: 'text';
  id: string;
  spec: MemeTextLayoutSpec;
}

export interface ImageRenderMediaLayerPlan {
  kind: 'media';
  id: string;
  assetUri: string;
  assetKind: MediaOverlayLayer['assetKind'];
  fit: MediaOverlayLayer['fit'];
  rect: ImageRenderPixelRect;
  rotationDegrees: number;
  opacity: number;
}

/**
 * Where a resolved cutout is drawn and how, in OUTPUT pixels.
 *
 * The renderer receives pixels, not scales: the alpha lives in `cutoutUri` (a
 * native PNG), and every effect size has already been resolved against this
 * output size by [resolveCutoutPlacement] — the same function the preview calls
 * with the preview canvas. That is the only reason a sticker can look identical
 * at 640 px and at 16 MP.
 */
export interface ImageRenderSubjectLayerPlan {
  kind: 'subject';
  id: string;
  cutoutUri: string;
  rect: ImageRenderPixelRect;
  rotationDegrees: number;
  opacity: number;
  /** null when the layer has no outline; then `outlinePx` is 0. */
  outlineColor: string | null;
  outlinePx: number;
  shadowColor: string;
  shadowBlurPx: number;
  shadowOffsetPx: number;
}

export interface ImageRenderDrawElementPlan {
  shape: DrawShape;
  color: string;
  // Stroke width already resolved to OUTPUT pixels off the canvas short edge.
  strokeWidthPx: number;
  filled: boolean;
  // Polyline / endpoints / corners, in OUTPUT pixels.
  points: { x: number; y: number }[];
}

export interface ImageRenderDrawLayerPlan {
  kind: 'draw';
  id: string;
  opacity: number;
  elements: ImageRenderDrawElementPlan[];
}

export type ImageRenderUnavailableReason =
  // The subject layer's mask track has no materialized cutout to draw. Nothing
  // else about a subject layer is unsupported any more: a resolved mask IS
  // composited (see subjectPlan), so a reason for "we could but won't" would be
  // a limitation that no longer exists.
  | 'subject-mask-missing'
  // Video overlays would need a decoded frame at a chosen timestamp; the still
  // exporter does not pick one silently.
  | 'video-overlay-unsupported'
  // A pixelate cover reads the pixels underneath it, which a static video
  // overlay bitmap does not have — the frames live only on the native encoder.
  | 'pixelate-video-overlay-unsupported';

export interface ImageRenderUnavailableLayerPlan {
  kind: 'unavailable';
  id: string;
  layerKind: MemeEditLayer['kind'];
  reason: ImageRenderUnavailableReason;
}

export type ImageRenderLayerPlan =
  | ImageRenderCoverLayerPlan
  | ImageRenderTextLayerPlan
  | ImageRenderMediaLayerPlan
  | ImageRenderSubjectLayerPlan
  | ImageRenderDrawLayerPlan
  | ImageRenderUnavailableLayerPlan;

export interface ImageRenderPlan {
  version: typeof IMAGE_RENDER_PLAN_VERSION;
  id: string;
  timeUs: typeof IMAGE_RENDER_TIME_US;
  source: ImageRenderPlanSource;
  output: ImageRenderPlanOutput;
  background: ImageRenderPlanBackground;
  // Project order preserved; `unavailable` entries are skipped by the renderer
  // so a caller can report exactly which layers did not make it into the PNG.
  layers: ImageRenderLayerPlan[];
}

export interface ImageRenderPlanOptions {
  // Caller-supplied so the plan stays deterministic (no clock, no randomness).
  planId: string;
  maxOutputPixels?: number;
}

function round(value: number): number {
  return Math.round(value * 1_000_000) / 1_000_000;
}

function finite(value: number, fallback: number): number {
  return Number.isFinite(value) ? value : fallback;
}

function resolvedKeyframe(keyframes: readonly TransformKeyframe[]): TransformKeyframe | null {
  const fallback = keyframes[0];
  if (!fallback) return null;
  const interpolated = interpolateTransformKeyframes(keyframes, IMAGE_RENDER_TIME_US);
  return interpolated ? { ...fallback, ...interpolated } : fallback;
}

function pixelRect(rect: NormalizedRect, output: ImageRenderPlanOutput): ImageRenderPixelRect {
  const widthPx = output.widthPx;
  const heightPx = output.heightPx;
  return {
    x: round(rect.x * widthPx),
    y: round(rect.y * heightPx),
    width: round(rect.width * widthPx),
    height: round(rect.height * heightPx),
  };
}

function planOutput(
  project: MemeEditProject,
  maxOutputPixels: number
): ImageRenderPlanOutput {
  const visible = visibleImageDimensions(
    { width: project.source.width, height: project.source.height },
    project.base
  );
  const fullWidthPx = Math.max(1, Math.round(finite(visible.width, 1)));
  const fullHeightPx = Math.max(1, Math.round(finite(visible.height, 1)));
  const cap = Math.max(1, Math.floor(finite(maxOutputPixels, MAX_IMAGE_RENDER_PIXELS)));
  const total = fullWidthPx * fullHeightPx;
  if (total <= cap) {
    return {
      widthPx: fullWidthPx,
      heightPx: fullHeightPx,
      fullWidthPx,
      fullHeightPx,
      downscaled: false,
      scale: 1,
      maxPixels: cap,
    };
  }
  // Round the factor before applying it so the emitted size is reproducible
  // from the reported scale alone.
  const scale = round(Math.sqrt(cap / total));
  return {
    widthPx: Math.max(1, Math.floor(fullWidthPx * scale)),
    heightPx: Math.max(1, Math.floor(fullHeightPx * scale)),
    fullWidthPx,
    fullHeightPx,
    downscaled: true,
    scale,
    maxPixels: cap,
  };
}

function coverPlan(layer: CoverLayer, output: ImageRenderPlanOutput): ImageRenderCoverLayerPlan {
  const correction = interpolateCoverCorrections(layer.corrections, IMAGE_RENDER_TIME_US);
  const rect = pixelRect(correction?.rect ?? layer.rect, output);
  const requested = Math.max(1, Math.round(finite(layer.pixelSize, 1) * output.scale));
  return {
    kind: 'cover',
    id: layer.id,
    rect,
    mode: correction?.mode ?? layer.mode,
    color: layer.color,
    pixelSizePx: Math.max(requested, mosaicCellFloorPx(rect)),
  };
}

function drawPlan(layer: DrawLayer, output: ImageRenderPlanOutput): ImageRenderDrawLayerPlan {
  // Stroke width tracks the canvas short edge, so a line drawn in the preview
  // keeps its visual weight at full export resolution.
  const shortEdgePx = Math.min(output.widthPx, output.heightPx);
  return {
    kind: 'draw',
    id: layer.id,
    opacity: layer.opacity,
    elements: layer.elements.map((element) => ({
      shape: element.shape,
      color: element.color,
      filled: element.filled,
      strokeWidthPx: round(Math.max(1, element.strokeScale * shortEdgePx)),
      points: element.points.map((point) => ({
        x: round(point.x * output.widthPx),
        y: round(point.y * output.heightPx),
      })),
    })),
  };
}

// Resolve one project layer into its render plan, or null when it contributes
// nothing (a keyframed layer with no keyframe). `context` distinguishes the
// still exporter — which draws the source and can pixelate against it — from
// the video overlay, a transparent bitmap with no frames beneath it.
function planLayer(
  layer: MemeEditLayer,
  project: MemeEditProject,
  output: ImageRenderPlanOutput,
  context: 'image' | 'video-overlay'
): ImageRenderLayerPlan | null {
  if (layer.kind === 'draw') return layer.elements.length === 0 ? null : drawPlan(layer, output);
  if (layer.kind === 'cover') {
    const mode = interpolateCoverCorrections(layer.corrections, IMAGE_RENDER_TIME_US)?.mode ?? layer.mode;
    if (context === 'video-overlay' && mode === 'pixelate') {
      return { kind: 'unavailable', id: layer.id, layerKind: 'cover', reason: 'pixelate-video-overlay-unsupported' };
    }
    return coverPlan(layer, output);
  }
  const keyframe = resolvedKeyframe(layer.keyframes);
  if (!keyframe) return null;
  if (layer.kind === 'subject') return subjectPlan(layer, keyframe, project, output);
  if (layer.kind === 'media') return mediaPlan(layer, keyframe, output);
  return {
    kind: 'text',
    id: layer.id,
    spec: buildMemeTextLayoutSpec(layer, keyframe, {
      canvasWidthDip: output.widthPx,
      canvasHeightDip: output.heightPx,
    }),
  };
}

function planSource(project: MemeEditProject): ImageRenderPlanSource {
  return {
    uri: project.transient.materializedSourceUri ?? project.source.uri,
    widthPx: project.source.width,
    heightPx: project.source.height,
    rotation: project.base.rotation,
    flipX: project.base.flipX,
    flipY: project.base.flipY,
    crop: normalizeFreeCrop(project.base.crop),
  };
}


function mediaPlan(
  layer: MediaOverlayLayer,
  keyframe: TransformKeyframe,
  output: ImageRenderPlanOutput
): ImageRenderLayerPlan {
  if (layer.assetKind === 'video') {
    return {
      kind: 'unavailable',
      id: layer.id,
      layerKind: 'media',
      reason: 'video-overlay-unsupported',
    };
  }
  // Same square base box the canvas gives a media overlay, scaled by the
  // keyframe and centered on the keyframe center.
  const side = round(output.widthPx * MEME_MEDIA_LAYER_BASE_WIDTH * Math.max(0.01, keyframe.scale));
  const centerX = output.widthPx * Math.min(1, Math.max(0, keyframe.center.x));
  const centerY = output.heightPx * Math.min(1, Math.max(0, keyframe.center.y));
  return {
    kind: 'media',
    id: layer.id,
    assetUri: layer.assetUri,
    assetKind: layer.assetKind,
    fit: layer.fit,
    rect: {
      x: round(centerX - side / 2),
      y: round(centerY - side / 2),
      width: side,
      height: side,
    },
    rotationDegrees: round(keyframe.rotationDegrees),
    opacity: round(Math.min(1, Math.max(0, keyframe.opacity))),
  };
}


/** Canvas a cutout is resolved against: the preview surface or the output. */
export interface CutoutCanvasSize {
  widthPx: number;
  heightPx: number;
}

export interface CutoutPlacement {
  rect: ImageRenderPixelRect;
  rotationDegrees: number;
  opacity: number;
  outlinePx: number;
  shadowBlurPx: number;
  shadowOffsetPx: number;
}

/**
 * Resolve a subject layer onto a canvas.
 *
 * `trackRect` is where segmentation found the subject, in the project's own
 * normalized frame; the keyframe then moves and scales it. At the moment a
 * cutout is applied the keyframe centre IS the track rect's centre and the scale
 * is 1, so an untouched cutout lands exactly on its own pixels — dragging it is
 * what makes it a sticker.
 *
 * Preview and export both call this, with different canvases and nothing else
 * different. A mask that composites at preview scale but not at full resolution
 * is the classic version of this bug, and one shared resolver is what makes it
 * impossible rather than merely unlikely.
 */
export function resolveCutoutPlacement(
  layer: Pick<SubjectLayer, 'outlineColor' | 'outlineScale' | 'shadowScale'>,
  keyframe: TransformKeyframe,
  trackRect: NormalizedRect,
  canvas: CutoutCanvasSize
): CutoutPlacement {
  const scale = Math.max(0.01, finite(keyframe.scale, 1));
  const width = Math.max(0, finite(trackRect.width, 0)) * canvas.widthPx * scale;
  const height = Math.max(0, finite(trackRect.height, 0)) * canvas.heightPx * scale;
  const centerX = canvas.widthPx * Math.min(1, Math.max(0, finite(keyframe.center.x, 0.5)));
  const centerY = canvas.heightPx * Math.min(1, Math.max(0, finite(keyframe.center.y, 0.5)));
  // Effects scale with the cutout's short edge, so a thumbnail-sized sticker
  // gets a hairline and a full-frame one gets a border.
  const reference = Math.min(width, height);
  const outlineScale = Math.min(1, Math.max(0, finite(layer.outlineScale, 0)));
  const shadowScale = Math.min(1, Math.max(0, finite(layer.shadowScale, 0)));
  return {
    rect: {
      x: round(centerX - width / 2),
      y: round(centerY - height / 2),
      width: round(width),
      height: round(height),
    },
    rotationDegrees: round(finite(keyframe.rotationDegrees, 0)),
    opacity: round(Math.min(1, Math.max(0, finite(keyframe.opacity, 1)))),
    // No colour means no outline; the two always travel together.
    outlinePx:
      layer.outlineColor === null
        ? 0
        : round(outlineScale * CUTOUT_OUTLINE_MAX_FRACTION * reference),
    shadowBlurPx: round(shadowScale * CUTOUT_SHADOW_BLUR_MAX_FRACTION * reference),
    shadowOffsetPx: round(shadowScale * CUTOUT_SHADOW_OFFSET_MAX_FRACTION * reference),
  };
}

function subjectPlan(
  layer: SubjectLayer,
  keyframe: TransformKeyframe,
  project: MemeEditProject,
  output: ImageRenderPlanOutput
): ImageRenderLayerPlan {
  const cutoutUri = project.transient.maskTracks[layer.maskTrackId];
  const track = project.maskTracks.find((candidate) => candidate.id === layer.maskTrackId);
  const trackRect = track ? evaluateMaskTrackRect(track, IMAGE_RENDER_TIME_US) : null;
  // No materialized cutout, or no mask geometry to place it with: there is
  // nothing to draw and the caller has to be told, not shown a blank sticker.
  if (!cutoutUri || !trackRect) {
    return { kind: 'unavailable', id: layer.id, layerKind: 'subject', reason: 'subject-mask-missing' };
  }
  const placement = resolveCutoutPlacement(layer, keyframe, trackRect, {
    widthPx: output.widthPx,
    heightPx: output.heightPx,
  });
  return {
    kind: 'subject',
    id: layer.id,
    cutoutUri,
    rect: placement.rect,
    rotationDegrees: placement.rotationDegrees,
    opacity: placement.opacity,
    outlineColor: layer.outlineColor,
    outlinePx: placement.outlinePx,
    shadowColor: CUTOUT_SHADOW_COLOR,
    shadowBlurPx: placement.shadowBlurPx,
    shadowOffsetPx: placement.shadowOffsetPx,
  };
}

export function buildImageRenderPlan(
  project: MemeEditProject,
  options: ImageRenderPlanOptions
): ImageRenderPlan {
  if (project.source.kind !== 'image') {
    throw new TypeError('buildImageRenderPlan requires an image source project.');
  }
  const output = planOutput(project, options.maxOutputPixels ?? MAX_IMAGE_RENDER_PIXELS);
  const layers: ImageRenderLayerPlan[] = [];
  for (const layer of project.layers) {
    if (!isLayerActiveAt(layer, IMAGE_RENDER_TIME_US)) continue;
    const plan = planLayer(layer, project, output, 'image');
    if (plan) layers.push(plan);
  }
  return {
    version: IMAGE_RENDER_PLAN_VERSION,
    id: options.planId,
    timeUs: IMAGE_RENDER_TIME_US,
    source: planSource(project),
    output,
    background: {
      mode: project.background.mode,
      color: project.background.color,
      assetUri: project.background.assetUri,
      blurScale: project.background.blurScale,
    },
    layers,
  };
}

// Build the transparent overlay-bitmap plan for a VIDEO export: every burn-in
// layer resolved once over a transparent canvas at the composition's output
// size. The native still renderer draws this PNG, and the video exporter
// composites it over every frame via a media3 overlay — the one path that makes
// text, covers, cutouts and drawings appear on an exported clip. Time ranges
// are intentionally flattened: a static overlay shows for the whole clip, which
// is what an annotation wants.
export function buildVideoOverlayRenderPlan(
  project: MemeEditProject,
  options: ImageRenderPlanOptions
): ImageRenderPlan {
  if (project.source.kind !== 'video') {
    throw new TypeError('buildVideoOverlayRenderPlan requires a video source project.');
  }
  const output = planOutput(project, options.maxOutputPixels ?? MAX_IMAGE_RENDER_PIXELS);
  const layers: ImageRenderLayerPlan[] = [];
  for (const layer of project.layers) {
    const plan = planLayer(layer, project, output, 'video-overlay');
    if (plan) layers.push(plan);
  }
  return {
    version: IMAGE_RENDER_PLAN_VERSION,
    id: options.planId,
    timeUs: IMAGE_RENDER_TIME_US,
    source: planSource(project),
    output,
    background: { mode: 'transparent', color: project.background.color, assetUri: null, blurScale: 0 },
    layers,
  };
}

// Layers the renderer will honestly skip. Callers surface these instead of
// letting a silently incomplete PNG pass for a finished export.
export function imageRenderPlanUnavailableLayers(
  plan: ImageRenderPlan
): ImageRenderUnavailableLayerPlan[] {
  return plan.layers.filter(
    (layer): layer is ImageRenderUnavailableLayerPlan => layer.kind === 'unavailable'
  );
}

/**
 * Preview/export parity fixtures for subject cutouts.
 *
 * The pattern MemeDynamicOverlay and MemeTextLayout already use: one committed
 * JSON, generated here, read by both a Jest test and an instrumented test on the
 * device. Eyeballing a preview against an export cannot catch a cutout that is
 * placed correctly at 300 px and two pixels off at 4000 px; a fixture both sides
 * are measured against can.
 *
 * Every canvas here shares one aspect ratio on purpose: it makes the drawn
 * rectangle's aspect identical at both scales, so the device test can generate a
 * matching cutout and compare the OPAQUE PIXEL bounds it actually rendered
 * against `normalizedRect`, rather than trusting the plan it was handed.
 */
export const CUTOUT_PARITY_FIXTURE_VERSION = 1;

/** Device-side tolerance: rounding plus one pixel of antialiased edge. */
export const CUTOUT_PARITY_TOLERANCE_PX = 2;

export interface CutoutParityPlacement extends CutoutPlacement {
  /** Index into `CutoutParityFixtures.canvases`. */
  canvas: number;
}

export interface CutoutParityCase {
  id: string;
  trackRect: NormalizedRect;
  center: NormalizedPoint;
  scale: number;
  rotationDegrees: number;
  opacity: number;
  outlineColor: string | null;
  outlineScale: number;
  shadowScale: number;
  /**
   * True when the drawn pixels' bounding box IS the placement rect — no
   * rotation, no outline or shadow spilling past it, nothing clipped by the
   * canvas edge. Only those cases can be checked against real rendered pixels.
   */
  pixelVerifiable: boolean;
  placements: CutoutParityPlacement[];
  /** The placement as a fraction of the canvas: identical at every scale. */
  normalizedRect: NormalizedRect;
}

export interface CutoutParityFixtures {
  version: number;
  tolerancePx: number;
  canvases: CutoutCanvasSize[];
  cases: CutoutParityCase[];
}

export function buildCutoutParityFixtures(): CutoutParityFixtures {
  const canvases: CutoutCanvasSize[] = [
    { widthPx: 1200, heightPx: 800 },
    // Same 3:2 aspect, a sixteenth of the pixels: the preview surface.
    { widthPx: 300, heightPx: 200 },
  ];
  const inputs: Omit<CutoutParityCase, 'placements' | 'normalizedRect'>[] = [
    {
      id: 'identity',
      trackRect: { x: 0.3, y: 0.25, width: 0.4, height: 0.5 },
      center: { x: 0.5, y: 0.5 },
      scale: 1,
      rotationDegrees: 0,
      opacity: 1,
      outlineColor: null,
      outlineScale: 0,
      shadowScale: 0,
      pixelVerifiable: true,
    },
    {
      id: 'moved-and-scaled',
      trackRect: { x: 0.1, y: 0.2, width: 0.3, height: 0.3 },
      center: { x: 0.6, y: 0.45 },
      scale: 1.5,
      rotationDegrees: 0,
      opacity: 1,
      outlineColor: null,
      outlineScale: 0,
      shadowScale: 0,
      pixelVerifiable: true,
    },
    {
      id: 'tiny-sticker',
      trackRect: { x: 0.4, y: 0.4, width: 0.08, height: 0.12 },
      center: { x: 0.5, y: 0.5 },
      scale: 1,
      rotationDegrees: 0,
      opacity: 1,
      outlineColor: null,
      outlineScale: 0,
      shadowScale: 0,
      pixelVerifiable: true,
    },
    {
      id: 'half-opacity',
      trackRect: { x: 0.2, y: 0.3, width: 0.5, height: 0.4 },
      center: { x: 0.5, y: 0.5 },
      scale: 1,
      rotationDegrees: 0,
      opacity: 0.5,
      outlineColor: null,
      outlineScale: 0,
      shadowScale: 0,
      pixelVerifiable: true,
    },
    {
      // Effects spill past the placement rect, so pixels cannot be compared to
      // it — but the resolved sizes still have to agree across scales.
      id: 'outline-and-shadow',
      trackRect: { x: 0.25, y: 0.25, width: 0.4, height: 0.4 },
      center: { x: 0.45, y: 0.55 },
      scale: 1.2,
      rotationDegrees: 0,
      opacity: 1,
      outlineColor: '#FFFFFF',
      outlineScale: 0.5,
      shadowScale: 0.75,
      pixelVerifiable: false,
    },
    {
      id: 'rotated',
      trackRect: { x: 0.3, y: 0.3, width: 0.3, height: 0.45 },
      center: { x: 0.5, y: 0.5 },
      scale: 1,
      rotationDegrees: 30,
      opacity: 1,
      outlineColor: null,
      outlineScale: 0,
      shadowScale: 0,
      pixelVerifiable: false,
    },
  ];
  return {
    version: CUTOUT_PARITY_FIXTURE_VERSION,
    tolerancePx: CUTOUT_PARITY_TOLERANCE_PX,
    canvases,
    cases: inputs.map((input) => {
      const keyframe: TransformKeyframe = {
        timeUs: IMAGE_RENDER_TIME_US,
        center: input.center,
        scale: input.scale,
        rotationDegrees: input.rotationDegrees,
        opacity: input.opacity,
        easing: 'hold',
      };
      const layer = {
        outlineColor: input.outlineColor,
        outlineScale: input.outlineScale,
        shadowScale: input.shadowScale,
      };
      const placements = canvases.map((canvas, index) => ({
        canvas: index,
        ...resolveCutoutPlacement(layer, keyframe, input.trackRect, canvas),
      }));
      const reference = placements[0];
      return {
        ...input,
        placements,
        normalizedRect: {
          x: round(reference.rect.x / canvases[0].widthPx),
          y: round(reference.rect.y / canvases[0].heightPx),
          width: round(reference.rect.width / canvases[0].widthPx),
          height: round(reference.rect.height / canvases[0].heightPx),
        },
      };
    }),
  };
}
