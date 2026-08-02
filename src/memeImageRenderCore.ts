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
  interpolateCoverCorrections,
  interpolateTransformKeyframes,
  isLayerActiveAt,
  type BackgroundSpec,
  type CoverLayer,
  type MediaOverlayLayer,
  type MemeEditLayer,
  type MemeEditProject,
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

export type ImageRenderUnavailableReason =
  // The subject layer's mask track has no materialized bitmap at all.
  | 'subject-mask-missing'
  // A mask exists, but subject compositing is owned by the segmentation task;
  // rendering a guess here would fake a cutout.
  | 'subject-compositing-unsupported'
  // Video overlays would need a decoded frame at a chosen timestamp; the still
  // exporter does not pick one silently.
  | 'video-overlay-unsupported';

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
  return {
    kind: 'cover',
    id: layer.id,
    rect: pixelRect(correction?.rect ?? layer.rect, output),
    mode: correction?.mode ?? layer.mode,
    color: layer.color,
    pixelSizePx: Math.max(1, Math.round(layer.pixelSize * output.scale)),
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
    if (layer.kind === 'cover') {
      layers.push(coverPlan(layer, output));
      continue;
    }
    if (layer.kind === 'subject') {
      layers.push({
        kind: 'unavailable',
        id: layer.id,
        layerKind: 'subject',
        reason: project.transient.maskTracks[layer.maskTrackId]
          ? 'subject-compositing-unsupported'
          : 'subject-mask-missing',
      });
      continue;
    }
    const keyframe = resolvedKeyframe(layer.keyframes);
    if (!keyframe) continue;
    if (layer.kind === 'media') {
      layers.push(mediaPlan(layer, keyframe, output));
      continue;
    }
    layers.push({
      kind: 'text',
      id: layer.id,
      spec: buildMemeTextLayoutSpec(layer, keyframe, {
        canvasWidthDip: output.widthPx,
        canvasHeightDip: output.heightPx,
      }),
    });
  }
  return {
    version: IMAGE_RENDER_PLAN_VERSION,
    id: options.planId,
    timeUs: IMAGE_RENDER_TIME_US,
    source: {
      uri: project.transient.materializedSourceUri ?? project.source.uri,
      widthPx: project.source.width,
      heightPx: project.source.height,
      rotation: project.base.rotation,
      flipX: project.base.flipX,
      flipY: project.base.flipY,
      crop: normalizeFreeCrop(project.base.crop),
    },
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

// Layers the renderer will honestly skip. Callers surface these instead of
// letting a silently incomplete PNG pass for a finished export.
export function imageRenderPlanUnavailableLayers(
  plan: ImageRenderPlan
): ImageRenderUnavailableLayerPlan[] {
  return plan.layers.filter(
    (layer): layer is ImageRenderUnavailableLayerPlan => layer.kind === 'unavailable'
  );
}
