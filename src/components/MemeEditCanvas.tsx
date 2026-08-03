import React, { useCallback, useEffect, useMemo, useRef } from 'react';
import { Animated, PanResponder, Platform, StyleSheet, Text, View, type ImageStyle, type LayoutChangeEvent, type ViewStyle } from 'react-native';
import { Image } from 'expo-image';
import { useVideoPlayer, VideoView } from 'expo-video';
import { requireNativeViewManager } from 'expo-modules-core';

import { measureMemeTextLayout, sampleImagePixelGrid, type NativeImagePixelGrid } from '../../modules/memeget-bg';
import {
  canvasLayerHidden,
  containedMediaRect,
  canvasLayerVisualDescriptor,
  captureTransformGesture,
  dragKeyframeByViewDelta,
  gestureMoveShouldClaim,
  layerBodyTouchInsideMedia,
  layerHandlePoints,
  nextCanvasPlayheadUs,
  layerHandleTouchInsideMedia,
  resizeKeyframeFromHandle,
  rotateKeyframeFromHandle,
  transformAccessibilityAction,
  transformHandleAccessibilityAction,
  upsertLayerKeyframeAtCapturedTime,
  viewPointToNormalizedPoint,
  viewRectToAbsoluteStyle,
  type ViewDelta,
  type ViewPoint,
  type ViewRect,
  type ViewSize,
} from '../memeEditCanvasCore';
import {
  moveNormalizedRegion,
  resizeNormalizedRegion,
  defaultManualTextRegion,
  sourceFrameForVisibleCrop,
  remapNormalizedRect,
  visibleImageDimensions,
  type TextRegionCandidate,
} from '../memeImageEditCore';
import {
  evaluateMaskTrackRect,
  interpolateTransformKeyframes,
  type CoverLayer,
  type KeyframedLayer,
  type MediaOverlayLayer,
  type MemeEditLayer,
  type MemeEditProject,
  type NormalizedRect,
  type NormalizedPoint,
  type SubjectLayer,
  type TextLayer,
  type TransformKeyframe,
} from '../memeEditProjectCore';
import {
  IMAGE_RENDER_TIME_US,
  MEME_MEDIA_LAYER_BASE_WIDTH,
  resolveCutoutPlacement,
} from '../memeImageRenderCore';
import { videoAudioPreview } from '../memeVideoAudioCore';
import { buildMemeTextLayoutSpec, compareNativeMemeTextLayoutResults, memeTextBackingRadiusForPreview, memeTextMeasureKey, nativeMemeTextLayoutInputFromSpec, type MemeTextLayoutSpec, type NativeMemeTextLayoutResult } from '../memeTextLayoutCore';
import { colors, radius, space, type } from '../theme';
import { useConst } from '../reactUtils';


const FULL_IMAGE_BASE = {
  rotation: 0 as const,
  flipX: false,
  flipY: false,
  crop: { x: 0, y: 0, width: 1, height: 1 },
  outputAspect: 'source' as const,
};
const PIXEL_GRID_CACHE = new Map<string, NativeImagePixelGrid>();
const MAX_PIXEL_GRID_CACHE_ENTRIES = 64;
const PREVIEW_TIME_POLL_MS = 33;

type CommitLayerKeyframes = (layerId: string, keyframes: TransformKeyframe[]) => void;
interface NativeMemeTextPreviewMetrics extends NativeMemeTextLayoutResult {
  outerWidthDip: number;
  outerHeightDip: number;
  contentOffsetXDip: number;
  contentOffsetYDip: number;
}

interface NativeMemeTextPreviewProps {
  text: string;
  fontFamily: string;
  fontWeight: number;
  fontSizeDip: number;
  lineHeightDip: number;
  letterSpacingEm: number;
  widthDip: number;
  align: 'left' | 'center' | 'right';
  fillColor: string;
  strokeColor: string;
  strokeWidthDip: number;
  opacity: number;
  onMetrics?: (event: { nativeEvent: NativeMemeTextPreviewMetrics }) => void;
  style?: unknown;
}

function resolveNativeMemeTextPreviewView() {
  if (Platform.OS !== 'android') return null;
  try {
    return requireNativeViewManager<NativeMemeTextPreviewProps>('MemegetBg');
  } catch {
    return null;
  }
}

const NativeMemeTextPreviewView = resolveNativeMemeTextPreviewView();

type CanvasLayerProps = {
  project: MemeEditProject;
  layer: MemeEditLayer;
  mediaRect: ViewRect;
  selected: boolean;
  hidden: boolean;
  activeTimeUs: number;
  disabled?: boolean;
  onSelectLayer: (id: string | null) => void;
  onCommitLayerKeyframes: CommitLayerKeyframes;
};

function firstKeyframe(layer: KeyframedLayer, timeUs: number): TransformKeyframe {
  const interpolated = interpolateTransformKeyframes(layer.keyframes, timeUs);
  const fallback = layer.keyframes[0];
  if (!interpolated) return fallback;
  return { ...fallback, ...interpolated };
}


function rectStyle(rect: NormalizedRect, mediaRect: ViewRect) {
  return {
    left: mediaRect.x + rect.x * mediaRect.width,
    top: mediaRect.y + rect.y * mediaRect.height,
    width: rect.width * mediaRect.width,
    height: rect.height * mediaRect.height,
  };
}


// A seek the studio wants applied to the preview player. The nonce is what makes
// it a one-shot command instead of a value: two seeks to the same source time
// during a throttled scrub must both land.
export interface VideoSeekRequest {
  timeUs: number;
  nonce: number;
}

const SourceVideo = React.memo(function SourceVideo({
  uri,
  style,
  muted,
  volume,
  speed,
  seekRequest,
  onTimeUs,
}: {
  uri: string;
  style: ViewStyle;
  muted: boolean;
  volume: number;
  speed: number;
  seekRequest: VideoSeekRequest | null;
  onTimeUs: (timeUs: number) => void;
}) {
  const player = useVideoPlayer(uri, (instance) => {
    instance.loop = true;
    instance.play();
  });
  // Written on every change, not just on creation: the audio tool's promise is
  // that what you hear now is what the export will carry, and the speed the
  // preview runs at is measured to match the exported rate.
  useEffect(() => {
    player.muted = muted;
    player.volume = volume;
  }, [muted, player, volume]);
  useEffect(() => {
    player.playbackRate = speed;
  }, [player, speed]);
  useEffect(() => {
    const timer = setInterval(() => {
      onTimeUs(Math.max(0, Math.round((player.currentTime ?? 0) * 1_000_000)));
    }, PREVIEW_TIME_POLL_MS);
    return () => clearInterval(timer);
  }, [onTimeUs, player]);
  // Nonce-keyed rather than time-keyed: scrubbing back to a time the player is
  // already near must still seek, and a throttled drag legitimately repeats a
  // position. Writing `currentTime` is the documented expo-video seek.
  useEffect(() => {
    if (seekRequest === null) return;
    player.currentTime = Math.max(0, seekRequest.timeUs) / 1_000_000;
  }, [player, seekRequest]);
  return <VideoView pointerEvents="none" style={[styles.sourceMedia, style]} player={player} contentFit="fill" nativeControls={false} />;
});

const SourceMedia = React.memo(function SourceMedia({
  project,
  uri,
  mediaRect,
  previewAudio,
  seekRequest,
  onTimeUs,
}: {
  project: MemeEditProject;
  uri: string;
  mediaRect: ViewRect;
  previewAudio: { muted: boolean; volume: number } | null;
  seekRequest: VideoSeekRequest | null;
  onTimeUs: (timeUs: number) => void;
}) {
  // An in-flight slider drag wins over the committed value so the preview
  // tracks the gesture; both go through the same honesty clamp.
  const audio = videoAudioPreview(previewAudio ?? project.video?.audio ?? { muted: false, volume: 1 });
  const frame = sourceFrameForVisibleCrop(
    { x: 0, y: 0, width: mediaRect.width, height: mediaRect.height },
    project.base
  );
  const swapsAxes = project.base.rotation === 90 || project.base.rotation === 270;
  const contentWidth = swapsAxes ? frame.height : frame.width;
  const contentHeight = swapsAxes ? frame.width : frame.height;
  const contentFrame = {
    left: (frame.width - contentWidth) / 2,
    top: (frame.height - contentHeight) / 2,
    width: contentWidth,
    height: contentHeight,
  };
  const videoStyle: ViewStyle = {
    ...contentFrame,
    transform: [{ rotate: `${project.base.rotation}deg` }],
  };
  const imageStyle: ImageStyle = {
    ...contentFrame,
    transform: [{ rotate: `${project.base.rotation}deg` }],
  };
  return (
    <View style={[styles.sourceClip, viewRectToAbsoluteStyle(mediaRect)]} pointerEvents="none">
      <View
        style={[
          styles.orientedSource,
          frame,
          {
            transform: [
              { scaleX: project.base.flipX ? -1 : 1 },
              { scaleY: project.base.flipY ? -1 : 1 },
            ],
          },
        ]}
      >
        {project.source.kind === 'video' ? (
          <SourceVideo
            uri={uri}
            style={videoStyle}
            muted={audio.muted}
            volume={audio.playerVolume}
            speed={project.video?.speed ?? 1}
            seekRequest={seekRequest}
            onTimeUs={onTimeUs}
          />
        ) : (
          <Image
            source={{ uri }}
            style={[styles.sourceMedia, imageStyle]}
            contentFit="fill"
            cachePolicy="none"
          />
        )}
      </View>
    </View>
  );
});

const OverlayVideo = React.memo(function OverlayVideo({ uri }: { uri: string }) {
  const player = useVideoPlayer(uri, (instance) => {
    instance.loop = true;
    instance.muted = true;
    instance.play();
  });
  return <VideoView pointerEvents="none" style={StyleSheet.absoluteFill} player={player} contentFit="contain" nativeControls={false} />;
});

const PixelatePreview = React.memo(function PixelatePreview({
  layer,
  sourceUri,
  sourceRect,
}: {
  layer: CoverLayer;
  sourceUri: string;
  sourceRect: NormalizedRect | null;
}) {
  const [grid, setGrid] = React.useState<NativeImagePixelGrid | null>(null);
  const [error, setError] = React.useState('');
  const requestRef = useRef(0);
  const key = sourceRect
    ? `${sourceUri}:${sourceRect.x}:${sourceRect.y}:${sourceRect.width}:${sourceRect.height}:${layer.pixelSize}`
    : '';
  useEffect(() => {
    const request = requestRef.current + 1;
    requestRef.current = request;
    setError('');
    if (!sourceRect) {
      setGrid(null);
      setError('Pixel preview is outside the source image.');
      return;
    }
    const cached = PIXEL_GRID_CACHE.get(key);
    if (cached) {
      setGrid(cached);
      return;
    }
    setGrid(null);
    sampleImagePixelGrid(sourceUri, sourceRect, layer.pixelSize)
      .then((sample) => {
        if (requestRef.current !== request) return;
        if (!sample) {
          setError('Actual pixel preview is unavailable in this build.');
          return;
        }
        if (PIXEL_GRID_CACHE.size >= MAX_PIXEL_GRID_CACHE_ENTRIES) {
          const oldest = PIXEL_GRID_CACHE.keys().next().value;
          if (oldest !== undefined) PIXEL_GRID_CACHE.delete(oldest);
        }
        PIXEL_GRID_CACHE.set(key, sample);
        setGrid(sample);
      })
      .catch((sampleError) => {
        if (requestRef.current === request) {
          setError(`Could not load actual pixel preview: ${String(sampleError)}`);
        }
      });
  }, [key, layer.pixelSize, sourceRect, sourceUri]);
  if (!grid) return error ? <Text style={styles.pixelError}>{error}</Text> : null;
  return (
    <View style={styles.pixelGrid} pointerEvents="none">
      {grid.colors.map((color, index) => (
        <View
          key={index}
          style={[
            styles.pixelCell,
            {
              width: `${100 / grid.columns}%`,
              height: `${100 / grid.rows}%`,
              backgroundColor: color,
            },
          ]}
        />
      ))}
    </View>
  );
});


const CoverLayerView = React.memo(function CoverLayerView({ project, layer, mediaRect, selected, hidden, activeTimeUs, disabled, onSelectLayer }: CanvasLayerProps & { layer: CoverLayer }) {
  const correction = evaluateMaskTrackRect({ id: layer.id, active: layer.active, corrections: layer.corrections }, activeTimeUs);
  const rect = correction ?? layer.rect;
  const sourceRect = remapNormalizedRect(rect, project.base, FULL_IMAGE_BASE);
  const sourceUri = project.transient.materializedSourceUri ?? project.source.uri;
  if (hidden) return null;
  return (
    <View
      style={[
        styles.coverLayer,
        rectStyle(rect, mediaRect),
        layer.mode === 'solid' && { backgroundColor: layer.color },
        selected && styles.selectedOutline,
      ]}
      onStartShouldSetResponder={(event) => !disabled && viewPointToNormalizedPoint({ x: event.nativeEvent.locationX + mediaRect.x, y: event.nativeEvent.locationY + mediaRect.y }, mediaRect) !== null}
      onResponderRelease={() => {
        if (!disabled) onSelectLayer(layer.id);
      }}
      accessibilityRole="button"
      accessibilityLabel={layer.mode === 'pixelate' ? 'Pixelate correction layer' : 'Solid cover correction layer'}
      accessibilityHint="Select this correction layer"
      accessibilityState={{ selected, disabled: !!disabled }}
    >
      {layer.mode === 'pixelate' && <PixelatePreview layer={layer} sourceUri={sourceUri} sourceRect={sourceRect} />}
      <Text style={styles.coverText}>{layer.mode === 'pixelate' ? 'Pixelate' : 'Cover'}</Text>
    </View>
  );
});

const TransformableLayerView = React.memo(function TransformableLayerView({
  project,
  layer,
  mediaRect,
  selected,
  hidden,
  activeTimeUs,
  onSelectLayer,
  disabled,
  onCommitLayerKeyframes,
}: CanvasLayerProps & { layer: TextLayer | SubjectLayer | MediaOverlayLayer }) {
  const translate = useConst(() => new Animated.ValueXY({ x: 0, y: 0 }));
  const scalePreview = useConst(() => new Animated.Value(1));
  const rotatePreview = useConst(() => new Animated.Value(0));
  const gestureStart = useRef<{
    keyframe: TransformKeyframe;
    timeUs: number;
    center: ViewPoint;
    handle: ViewPoint;
  } | null>(null);
  const dragStartAccepted = useRef(false);
  const resizeStartAccepted = useRef(false);
  const rotateStartAccepted = useRef(false);
  const evaluatedKeyframe = firstKeyframe(layer, activeTimeUs);
  const keyframe = gestureStart.current?.keyframe ?? evaluatedKeyframe;
  const visualWidth = layer.kind === 'text' ? layer.width : MEME_MEDIA_LAYER_BASE_WIDTH;
  // A cutout's natural size is the region segmentation found it in, resolved
  // through the exporter's own function against the PREVIEW canvas. The scale is
  // deliberately left at 1: the transform below applies it, and it also scales
  // the stamped effects — which is exactly how the exporter resolves them, off
  // the SCALED short edge.
  const cutoutPlacement = useMemo(() => {
    if (layer.kind !== 'subject') return null;
    const track = project.maskTracks.find((candidate) => candidate.id === layer.maskTrackId);
    const trackRect = track ? evaluateMaskTrackRect(track, IMAGE_RENDER_TIME_US) : null;
    if (!trackRect || mediaRect.width <= 0 || mediaRect.height <= 0) return null;
    return resolveCutoutPlacement(
      layer,
      { ...keyframe, scale: 1 },
      trackRect,
      { widthPx: mediaRect.width, heightPx: mediaRect.height }
    );
  }, [keyframe, layer, mediaRect.height, mediaRect.width, project.maskTracks]);
  const cutoutBase = cutoutPlacement
    ? { width: cutoutPlacement.rect.width, height: cutoutPlacement.rect.height }
    : undefined;
  const cutoutEffects = {
    outlinePx: cutoutPlacement?.outlinePx ?? 0,
    shadowOffsetPx: cutoutPlacement?.shadowOffsetPx ?? 0,
  };
  const visualDescriptor = useMemo(
    () => canvasLayerVisualDescriptor(keyframe, visualWidth, mediaRect, cutoutBase),
    [cutoutBase, keyframe.center.x, keyframe.center.y, keyframe.rotationDegrees, keyframe.scale, mediaRect, visualWidth]
  );
  const box = useMemo(() => ({
    x: visualDescriptor.center.x - visualDescriptor.content.baseWidthDip / 2,
    y: visualDescriptor.center.y - visualDescriptor.content.baseHeightDip / 2,
    width: visualDescriptor.content.baseWidthDip,
    height: visualDescriptor.content.baseHeightDip,
  }), [visualDescriptor]);
  const handles = useMemo(
    () => layerHandlePoints(keyframe, visualWidth, mediaRect),
    [keyframe.center.x, keyframe.center.y, keyframe.rotationDegrees, keyframe.scale, mediaRect, visualWidth]
  );
  const textSpec = useMemo(() => layer.kind === 'text'
    ? buildMemeTextLayoutSpec(layer, keyframe, { canvasWidthDip: mediaRect.width, canvasHeightDip: mediaRect.height })
    : null, [keyframe.center.x, keyframe.center.y, keyframe.opacity, keyframe.rotationDegrees, keyframe.scale, layer, mediaRect.height, mediaRect.width]);

  useEffect(() => {
    translate.setValue({ x: 0, y: 0 });
    scalePreview.setValue(1);
    rotatePreview.setValue(0);
  }, [keyframe.center.x, keyframe.center.y, keyframe.scale, keyframe.rotationDegrees, scalePreview, translate, rotatePreview]);

  const commit = useCallback((nextKeyframe: TransformKeyframe, timeUs: number) => {
    onCommitLayerKeyframes(layer.id, upsertLayerKeyframeAtCapturedTime(layer.keyframes, nextKeyframe, timeUs));
    translate.setValue({ x: 0, y: 0 });
    scalePreview.setValue(1);
    rotatePreview.setValue(0);
  }, [layer.id, layer.keyframes, onCommitLayerKeyframes, rotatePreview, scalePreview, translate]);

  const dragPan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: (event) => {
      if (disabled) return false;
      const accepted = layerBodyTouchInsideMedia(keyframe, visualWidth, mediaRect, {
        x: event.nativeEvent.locationX,
        y: event.nativeEvent.locationY,
      });
      dragStartAccepted.current = accepted;
      return accepted;
    },
    onMoveShouldSetPanResponder: (_event, gesture) => gestureMoveShouldClaim(dragStartAccepted.current, gesture),
    onPanResponderGrant: () => {
      if (!dragStartAccepted.current) return;
      onSelectLayer(layer.id);
      gestureStart.current = { ...captureTransformGesture(keyframe, activeTimeUs), center: handles.center, handle: handles.resize };
      translate.setValue({ x: 0, y: 0 });
    },
    onPanResponderMove: (_event, gesture) => {
      if (dragStartAccepted.current) translate.setValue({ x: gesture.dx, y: gesture.dy });
    },
    onPanResponderRelease: (_event, gesture) => {
      const start = gestureStart.current;
      if (dragStartAccepted.current && start) {
        commit(dragKeyframeByViewDelta(start.keyframe, { dx: gesture.dx, dy: gesture.dy } satisfies ViewDelta, mediaRect), start.timeUs);
      }
      dragStartAccepted.current = false;
      gestureStart.current = null;
    },
    onPanResponderTerminate: () => {
      translate.setValue({ x: 0, y: 0 });
      dragStartAccepted.current = false;
      gestureStart.current = null;
    },
  }), [commit, disabled, handles.center, handles.resize, keyframe, layer.id, mediaRect, onSelectLayer, translate, visualWidth]);

  const resizePan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: (event) => {
      if (disabled) return false;
      const accepted = selected && layerHandleTouchInsideMedia(keyframe, visualWidth, mediaRect, 'resize', {
        x: event.nativeEvent.locationX,
        y: event.nativeEvent.locationY,
      });
      resizeStartAccepted.current = accepted;
      return accepted;
    },
    onMoveShouldSetPanResponder: (_event, gesture) => gestureMoveShouldClaim(resizeStartAccepted.current, gesture),
    onPanResponderGrant: () => {
      if (!resizeStartAccepted.current) return;
      gestureStart.current = { ...captureTransformGesture(keyframe, activeTimeUs), center: handles.center, handle: handles.resize };
      scalePreview.setValue(1);
    },
    onPanResponderMove: (_event, gesture) => {
      const start = gestureStart.current;
      if (!resizeStartAccepted.current || !start) return;
      const next = resizeKeyframeFromHandle(start.keyframe, start.center, start.handle, { x: start.handle.x + gesture.dx, y: start.handle.y + gesture.dy });
      scalePreview.setValue(next.scale / Math.max(0.01, start.keyframe.scale));
    },
    onPanResponderRelease: (_event, gesture) => {
      const start = gestureStart.current;
      if (resizeStartAccepted.current && start) {
        commit(resizeKeyframeFromHandle(start.keyframe, start.center, start.handle, { x: start.handle.x + gesture.dx, y: start.handle.y + gesture.dy }), start.timeUs);
      }
      resizeStartAccepted.current = false;
      gestureStart.current = null;
    },
    onPanResponderTerminate: () => {
      scalePreview.setValue(1);
      resizeStartAccepted.current = false;
      gestureStart.current = null;
    },
  }), [commit, disabled, handles.center, handles.resize, keyframe, mediaRect, scalePreview, selected, visualWidth]);

  const rotatePan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: (event) => {
      if (disabled) return false;
      const accepted = selected && layerHandleTouchInsideMedia(keyframe, visualWidth, mediaRect, 'rotate', {
        x: event.nativeEvent.locationX,
        y: event.nativeEvent.locationY,
      });
      rotateStartAccepted.current = accepted;
      return accepted;
    },
    onMoveShouldSetPanResponder: (_event, gesture) => gestureMoveShouldClaim(rotateStartAccepted.current, gesture),
    onPanResponderGrant: () => {
      if (!rotateStartAccepted.current) return;
      gestureStart.current = { ...captureTransformGesture(keyframe, activeTimeUs), center: handles.center, handle: handles.rotate };
      rotatePreview.setValue(0);
    },
    onPanResponderMove: (_event, gesture) => {
      const start = gestureStart.current;
      if (!rotateStartAccepted.current || !start) return;
      const next = rotateKeyframeFromHandle(start.keyframe, start.center, start.handle, { x: start.handle.x + gesture.dx, y: start.handle.y + gesture.dy });
      rotatePreview.setValue(next.rotationDegrees - start.keyframe.rotationDegrees);
    },
    onPanResponderRelease: (_event, gesture) => {
      const start = gestureStart.current;
      if (rotateStartAccepted.current && start) {
        commit(rotateKeyframeFromHandle(start.keyframe, start.center, start.handle, { x: start.handle.x + gesture.dx, y: start.handle.y + gesture.dy }), start.timeUs);
      }
      rotateStartAccepted.current = false;
      gestureStart.current = null;
    },
    onPanResponderTerminate: () => {
      rotatePreview.setValue(0);
      rotateStartAccepted.current = false;
      gestureStart.current = null;
    },
  }), [commit, disabled, handles.center, handles.rotate, keyframe, mediaRect, rotatePreview, selected, visualWidth]);

  const rotation = useMemo(() => rotatePreview.interpolate({
    inputRange: [-360, 360],
    outputRange: [`${keyframe.rotationDegrees - 360}deg`, `${keyframe.rotationDegrees + 360}deg`],
  }), [keyframe.rotationDegrees, rotatePreview]);
  const visualScale = useMemo(() => Animated.multiply(scalePreview, keyframe.scale), [keyframe.scale, scalePreview]);
  const controlFrame = useMemo(() => {
    if (!selected) return null;
    const width = Animated.multiply(visualScale, visualDescriptor.content.baseWidthDip);
    const height = Animated.multiply(visualScale, visualDescriptor.content.baseHeightDip);
    return {
      width,
      height,
      left: Animated.add(visualDescriptor.center.x, Animated.multiply(width, -0.5)),
      top: Animated.add(visualDescriptor.center.y, Animated.multiply(height, -0.5)),
    };
  }, [selected, visualDescriptor, visualScale]);

  if (hidden && gestureStart.current === null) return null;


  return (
    <>
      <Animated.View
        style={[
          styles.transformLayer,
          { left: box.x, top: box.y, width: box.width, height: box.height, opacity: keyframe.opacity },
          {
            transform: [
              { translateX: translate.x },
              { translateY: translate.y },
              { scale: visualScale },
              { rotate: rotation },
            ],
          },
        ]}
        {...dragPan.panHandlers}
        accessibilityRole="adjustable"
        accessibilityHint="Drag to move. Accessibility actions nudge the layer."
        accessibilityState={{ selected, disabled: !!disabled }}
        accessibilityActions={[{ name: 'activate', label: 'Select layer' }, { name: 'increment', label: 'Nudge right' }, { name: 'decrement', label: 'Nudge left' }]}
        onAccessibilityAction={(event) => {
          if (disabled) return;
          const next = transformAccessibilityAction(event.nativeEvent.actionName as 'increment' | 'decrement' | 'longpress' | 'escape', keyframe, mediaRect);
          if (next !== keyframe) commit(next, activeTimeUs);
          else onSelectLayer(layer.id);
        }}
      >
        {layer.kind === 'media' && <MediaLayerContent layer={layer} />}
        {layer.kind === 'text' && textSpec && <TextLayerContent spec={textSpec} />}
        {layer.kind === 'subject' && (
          <SubjectLayerContent
            project={project}
            layer={layer}
            outlinePx={cutoutEffects.outlinePx}
            shadowOffsetPx={cutoutEffects.shadowOffsetPx}
          />
        )}
      </Animated.View>
      {selected && controlFrame && (
        <Animated.View
          pointerEvents="box-none"
          style={[
            styles.transformControls,
            styles.selectedOutline,
            {
              left: controlFrame.left,
              top: controlFrame.top,
              width: controlFrame.width,
              height: controlFrame.height,
              transform: [
                { translateX: translate.x },
                { translateY: translate.y },
                { rotate: rotation },
              ],
            },
          ]}
        >
          <Animated.View
            style={styles.rotateHandle}
            {...rotatePan.panHandlers}
            accessibilityRole="adjustable"
            accessibilityLabel="Rotate selected layer"
            accessibilityState={{ disabled: !!disabled }}
            accessibilityActions={[{ name: 'increment', label: 'Rotate clockwise' }, { name: 'decrement', label: 'Rotate counter-clockwise' }]}
            onAccessibilityAction={(event) => {
              if (!disabled) commit(transformHandleAccessibilityAction('rotate', event.nativeEvent.actionName as 'increment' | 'decrement', keyframe, mediaRect), activeTimeUs);
            }}
          />
          <Animated.View
            style={styles.resizeHandle}
            {...resizePan.panHandlers}
            accessibilityRole="adjustable"
            accessibilityLabel="Resize selected layer"
            accessibilityState={{ disabled: !!disabled }}
            accessibilityActions={[{ name: 'increment', label: 'Make larger' }, { name: 'decrement', label: 'Make smaller' }]}
            onAccessibilityAction={(event) => {
              if (!disabled) commit(transformHandleAccessibilityAction('resize', event.nativeEvent.actionName as 'increment' | 'decrement', keyframe, mediaRect), activeTimeUs);
            }}
          />
        </Animated.View>
      )}
    </>
  );
});

const TextLayerContent = React.memo(function TextLayerContent({ spec }: { spec: MemeTextLayoutSpec }) {
  const [diagnostic, setDiagnostic] = React.useState<string | null>(null);
  const [nativeLayout, setNativeLayout] = React.useState<Awaited<ReturnType<typeof measureMemeTextLayout>> | null>(null);
  const [previewLayout, setPreviewLayout] = React.useState<NativeMemeTextPreviewMetrics | null>(null);
  const boxStyle = useMemo(() => ({
    backgroundColor: spec.backing.color ?? 'transparent',
    borderRadius: memeTextBackingRadiusForPreview(spec),
    paddingHorizontal: spec.backing.paddingXDip,
    paddingVertical: spec.backing.paddingYDip,
  }), [spec]);
  const textStyle = useMemo(() => ({
    color: spec.fill.color,
    fontSize: spec.canvas.fontSizeDip,
    lineHeight: spec.layout.lineHeightDip,
    fontWeight: spec.font.weight,
    fontFamily: spec.font.family,
    letterSpacing: spec.font.letterSpacingEm * spec.canvas.fontSizeDip,
    textAlign: spec.align,
    includeFontPadding: false,
  }), [spec]);
  const outlineOffsets = useMemo(() => {
    if (spec.outline.widthDip <= 0) return [];
    const amount = Math.max(1, spec.outline.widthDip);
    return [
      { left: -amount, top: 0 },
      { left: amount, top: 0 },
      { left: 0, top: -amount },
      { left: 0, top: amount },
      { left: -amount, top: -amount },
      { left: amount, top: -amount },
      { left: -amount, top: amount },
      { left: amount, top: amount },
    ];
  }, [spec.outline.widthDip]);
  const displayText = spec.displayText;
  const measureKey = useMemo(() => memeTextMeasureKey(spec), [spec]);
  const specRef = useRef(spec);
  specRef.current = spec;

  useEffect(() => {
    if (!NativeMemeTextPreviewView) {
      setNativeLayout(null);
      return;
    }
    let cancelled = false;
    const currentSpec = specRef.current;
    setNativeLayout(null);
    measureMemeTextLayout(nativeMemeTextLayoutInputFromSpec(currentSpec)).then((measuredLayout) => {
      if (cancelled) return;
      setNativeLayout(measuredLayout);
    }).catch(() => {
      if (!cancelled) setNativeLayout(null);
    });
    return () => {
      cancelled = true;
    };
  }, [measureKey]);
  useEffect(() => {
    if (!nativeLayout || !previewLayout) {
      setDiagnostic(null);
      return;
    }
    const comparison = compareNativeMemeTextLayoutResults(nativeLayout, previewLayout, spec.transform.scale, nativeLayout.toleranceDip);
    setDiagnostic(comparison.ok
      ? null
      : `Text metrics drift: ${comparison.lineCountDrift} lines, ${comparison.maxBaselineDriftDip.toFixed(1)}dp baseline`);
  }, [nativeLayout, previewLayout, spec.transform.scale]);

  return (
    <View style={[styles.textFill, { opacity: spec.fill.opacity }]} pointerEvents="none">
      <View style={[styles.textBacking, boxStyle]}>
        {NativeMemeTextPreviewView ? (
          <NativeMemeTextPreviewView
            text={displayText}
            fontFamily={spec.font.family}
            fontWeight={Number(spec.font.weight)}
            fontSizeDip={spec.canvas.fontSizeDip}
            lineHeightDip={spec.layout.lineHeightDip}
            letterSpacingEm={spec.font.letterSpacingEm}
            widthDip={spec.canvas.wrapWidthDip}
            align={spec.align}
            fillColor={spec.fill.color}
            strokeColor={spec.outline.color}
            strokeWidthDip={spec.outline.widthDip}
            opacity={1}
            onMetrics={(event) => setPreviewLayout(event.nativeEvent)}
            style={{
              width: previewLayout?.outerWidthDip ?? spec.canvas.wrapWidthDip + spec.outline.widthDip,
              height: previewLayout?.outerHeightDip ?? Math.max(spec.layout.lineHeightDip, spec.canvas.fontSizeDip) + spec.outline.widthDip,
            }}
          />
        ) : (
          <>
            {outlineOffsets.map((offset) => (
              <Text key={`${offset.left}:${offset.top}`} style={[styles.layerText, textStyle, styles.outlineText, { color: spec.outline.color, left: offset.left, top: offset.top }]}>
                {displayText}
              </Text>
            ))}
            <Text style={[styles.layerText, textStyle]}>{displayText}</Text>
          </>
        )}
        {spec.backing.tail === 'bottom-left' && <View style={[styles.bubbleTail, { backgroundColor: spec.backing.color ?? colors.text }]} />}
      </View>
      {!!diagnostic && <Text style={styles.textDiagnostic}>{diagnostic}</Text>}
    </View>
  );
});

/**
 * The cutout itself, in the preview.
 *
 * The PNG the segmenter wrote is what gets drawn — the same file the exporter
 * composites — so position, size and the alpha edge are the real thing rather
 * than a placeholder box. The sticker effects are stamped copies here (expo-image
 * tints them) which matches the export's geometry and colour; the export blurs
 * its shadow and the preview cannot, so a soft shadow reads slightly harder here.
 *
 * With no materialized mask there is nothing to show, and saying so is the point:
 * the exporter reports that layer as skipped for the same reason.
 */
const SubjectLayerContent = React.memo(function SubjectLayerContent({
  project,
  layer,
  outlinePx,
  shadowOffsetPx,
}: {
  project: MemeEditProject;
  layer: SubjectLayer;
  outlinePx: number;
  shadowOffsetPx: number;
}) {
  const cutoutUri = project.transient.maskTracks[layer.maskTrackId];
  if (!cutoutUri) {
    return (
      <View style={[styles.subjectFill, styles.unavailableFill]} pointerEvents="none">
        <Text style={styles.unavailableText}>Subject mask unavailable</Text>
      </View>
    );
  }
  const outlineStamps = outlinePx > 0 && layer.outlineColor
    ? Array.from({ length: 8 }, (_unused, index) => {
        const angle = (index * Math.PI) / 4;
        return { left: Math.cos(angle) * outlinePx, top: Math.sin(angle) * outlinePx };
      })
    : [];
  return (
    <View style={styles.subjectFill} pointerEvents="none">
      {shadowOffsetPx > 0 && (
        <Image
          source={{ uri: cutoutUri }}
          style={[StyleSheet.absoluteFill, { left: shadowOffsetPx, top: shadowOffsetPx, opacity: 0.55 }]}
          contentFit="contain"
          tintColor="#000000"
          cachePolicy="none"
        />
      )}
      {outlineStamps.map((offset) => (
        <Image
          key={`${offset.left}:${offset.top}`}
          source={{ uri: cutoutUri }}
          style={[StyleSheet.absoluteFill, { left: offset.left, top: offset.top }]}
          contentFit="contain"
          tintColor={layer.outlineColor ?? undefined}
          cachePolicy="none"
        />
      ))}
      <Image
        source={{ uri: cutoutUri }}
        style={StyleSheet.absoluteFill}
        contentFit="contain"
        cachePolicy="none"
      />
    </View>
  );
});

const MediaLayerContent = React.memo(function MediaLayerContent({ layer }: { layer: MediaOverlayLayer }) {
  if (layer.assetKind === 'video') return <OverlayVideo uri={layer.assetUri} />;
  return <Image source={{ uri: layer.assetUri }} style={StyleSheet.absoluteFill} contentFit={layer.fit} cachePolicy="none" />;
});

const TextRegionOverlay = React.memo(function TextRegionOverlay({
  region,
  mediaRect,
  selected,
  disabled,
  onSelect,
}: {
  region: TextRegionCandidate;
  mediaRect: ViewRect;
  selected: boolean;
  disabled: boolean;
  onSelect: (region: TextRegionCandidate) => void;
}) {
  const visual = rectStyle(region.rect, mediaRect);
  const targetWidth = Math.max(44, visual.width);
  const targetHeight = Math.max(44, visual.height);
  const targetLeft = Math.max(mediaRect.x, Math.min(
    visual.left - (targetWidth - visual.width) / 2,
    mediaRect.x + mediaRect.width - targetWidth
  ));
  const targetTop = Math.max(mediaRect.y, Math.min(
    visual.top - (targetHeight - visual.height) / 2,
    mediaRect.y + mediaRect.height - targetHeight
  ));
  return (
    <View
      style={[
        styles.textRegionTarget,
        { left: targetLeft, top: targetTop, width: targetWidth, height: targetHeight },
      ]}
      onStartShouldSetResponder={() => !disabled}
      onResponderRelease={() => {
        if (!disabled) onSelect(region);
      }}
      accessibilityRole="button"
      accessibilityLabel={`${region.source} text box: ${region.text || 'blank manual region'}`}
      accessibilityHint="Select this region for Cover, Pixelate, or Replace"
      accessibilityState={{ selected, disabled }}
    >
      <View
        pointerEvents="none"
        style={[
          styles.textRegionBox,
          {
            left: visual.left - targetLeft,
            top: visual.top - targetTop,
            width: visual.width,
            height: visual.height,
          },
          selected && styles.textRegionSelected,
        ]}
      />
    </View>
  );
});

const SelectedTextRegionOverlay = React.memo(function SelectedTextRegionOverlay({
  region,
  mediaRect,
  disabled,
  onChange,
}: {
  region: TextRegionCandidate;
  mediaRect: ViewRect;
  disabled: boolean;
  onChange: (region: TextRegionCandidate) => void;
}) {
  const drag = useConst(() => new Animated.ValueXY({ x: 0, y: 0 }));
  const resize = useConst(() => new Animated.ValueXY({ x: 0, y: 0 }));
  const dragAccepted = useRef(false);
  const resizeAccepted = useRef(false);
  const dragPan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => {
      dragAccepted.current = !disabled;
      return dragAccepted.current;
    },
    onMoveShouldSetPanResponder: (_event, gesture) =>
      dragAccepted.current && Math.abs(gesture.dx) + Math.abs(gesture.dy) > 2,
    onPanResponderMove: (_event, gesture) => {
      if (dragAccepted.current) drag.setValue({ x: gesture.dx, y: gesture.dy });
    },
    onPanResponderRelease: (_event, gesture) => {
      if (dragAccepted.current) {
        onChange({
          ...region,
          rect: moveNormalizedRegion(region.rect, {
            x: gesture.dx / mediaRect.width,
            y: gesture.dy / mediaRect.height,
          }),
        });
      }
      dragAccepted.current = false;
      drag.setValue({ x: 0, y: 0 });
    },
    onPanResponderTerminate: () => {
      dragAccepted.current = false;
      drag.setValue({ x: 0, y: 0 });
    },
  }), [disabled, drag, mediaRect.height, mediaRect.width, onChange, region]);
  const resizePan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => {
      resizeAccepted.current = !disabled;
      return resizeAccepted.current;
    },
    onMoveShouldSetPanResponder: (_event, gesture) =>
      resizeAccepted.current && Math.abs(gesture.dx) + Math.abs(gesture.dy) > 2,
    onPanResponderMove: (_event, gesture) => {
      if (resizeAccepted.current) resize.setValue({ x: gesture.dx, y: gesture.dy });
    },
    onPanResponderRelease: (_event, gesture) => {
      if (resizeAccepted.current) {
        onChange({
          ...region,
          rect: resizeNormalizedRegion(region.rect, {
            x: gesture.dx / mediaRect.width,
            y: gesture.dy / mediaRect.height,
          }),
        });
      }
      resizeAccepted.current = false;
      resize.setValue({ x: 0, y: 0 });
    },
    onPanResponderTerminate: () => {
      resizeAccepted.current = false;
      resize.setValue({ x: 0, y: 0 });
    },
  }), [disabled, mediaRect.height, mediaRect.width, onChange, region, resize]);
  const visual = rectStyle(region.rect, mediaRect);
  const nudge = useCallback((direction: number) => {
    if (!disabled) onChange({
      ...region,
      rect: moveNormalizedRegion(region.rect, { x: direction * 0.02, y: direction * 0.02 }),
    });
  }, [disabled, onChange, region]);
  const resizeBy = useCallback((direction: number) => {
    if (!disabled) onChange({
      ...region,
      rect: resizeNormalizedRegion(region.rect, { x: direction * 0.02, y: direction * 0.02 }),
    });
  }, [disabled, onChange, region]);
  return (
    <Animated.View
      {...dragPan.panHandlers}
      style={[
        styles.selectedTextRegion,
        visual,
        {
          width: Animated.add(visual.width, resize.x),
          height: Animated.add(visual.height, resize.y),
          transform: drag.getTranslateTransform(),
        },
      ]}
      accessible
      accessibilityRole="adjustable"
      accessibilityLabel={`Selected text region: ${region.text || 'manual box'}`}
      accessibilityHint="Drag to move. Increment and decrement nudge the region."
      accessibilityState={{ disabled }}
      accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
      onAccessibilityAction={(event) => nudge(event.nativeEvent.actionName === 'increment' ? 1 : -1)}
    >
      <Text style={styles.textRegionLabel} numberOfLines={1}>{region.source === 'manual' ? 'Manual region' : region.text}</Text>
      <View
        {...resizePan.panHandlers}
        style={styles.textRegionResizeHandle}
        accessible
        accessibilityRole="adjustable"
        accessibilityLabel="Resize selected text region"
        accessibilityHint="Drag the corner. Increment expands and decrement contracts."
        accessibilityState={{ disabled }}
        accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
        onAccessibilityAction={(event) => resizeBy(event.nativeEvent.actionName === 'increment' ? 1 : -1)}
      />
    </Animated.View>
  );
});

export const MemeEditCanvas = React.memo(function MemeEditCanvas({
  project,
  selectedLayerId,
  before,
  onSelectLayer,
  onCommitLayerKeyframes,
  disabled,
  textRegions = [],
  selectedTextRegion = null,
  manualTextRegionMode = false,
  onSelectTextRegion,
  onChangeSelectedTextRegion,
  onManualTextRegionComplete,
  previewAudio = null,
  seekRequest = null,
  onPlaybackTimeUs,
}: {
  project: MemeEditProject;
  selectedLayerId: string | null;
  before: boolean;
  onSelectLayer: (id: string | null) => void;
  onCommitLayerKeyframes: CommitLayerKeyframes;
  disabled?: boolean;
  textRegions?: readonly TextRegionCandidate[];
  selectedTextRegion?: TextRegionCandidate | null;
  manualTextRegionMode?: boolean;
  onSelectTextRegion?: (region: TextRegionCandidate | null) => void;
  onChangeSelectedTextRegion?: (region: TextRegionCandidate) => void;
  onManualTextRegionComplete?: () => void;
  previewAudio?: { muted: boolean; volume: number } | null;
  seekRequest?: VideoSeekRequest | null;
  /** Attach only while something outside needs the playhead; it fires ~30×/s. */
  onPlaybackTimeUs?: (timeUs: number) => void;
}) {
  const [viewSize, setViewSize] = React.useState<ViewSize | null>(null);
  const [activeTimeUs, setActiveTimeUs] = React.useState(0);
  const sourceUri = project.transient.materializedSourceUri ?? project.source.uri;
  const visibleDimensions = useMemo(
    () => visibleImageDimensions(
      { width: project.source.width, height: project.source.height },
      project.base
    ),
    [
      project.base.crop.height,
      project.base.crop.width,
      project.base.rotation,
      project.source.height,
      project.source.width,
    ]
  );
  const mediaRect = useMemo(
    () => viewSize ? containedMediaRect(viewSize, { ...visibleDimensions, rotation: 0 }) : null,
    [viewSize, visibleDimensions]
  );
  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const next = event.nativeEvent.layout;
    setViewSize((current) => {
      if (current && Math.abs(current.width - next.width) < 0.5 && Math.abs(current.height - next.height) < 0.5) return current;
      return { width: next.width, height: next.height };
    });
  }, []);
  // One settled playhead for both the overlays and whatever the studio is
  // showing, so a timeline readout can never disagree with what is drawn.
  const playheadRef = useRef(0);
  const onVideoTime = useCallback((timeUs: number) => {
    const next = nextCanvasPlayheadUs(playheadRef.current, timeUs, project.source.durationUs);
    if (next === playheadRef.current) return;
    playheadRef.current = next;
    onPlaybackTimeUs?.(next);
    setActiveTimeUs(next);
  }, [onPlaybackTimeUs, project.source.durationUs]);
  const manualStart = useRef<NormalizedPoint | null>(null);
  const manualPan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () =>
      manualTextRegionMode && !disabled && !!mediaRect && !!onChangeSelectedTextRegion,
    onMoveShouldSetPanResponder: (_event, gesture) =>
      manualTextRegionMode && Math.abs(gesture.dx) + Math.abs(gesture.dy) > 2,
    onPanResponderGrant: (event) => {
      if (!mediaRect) return;
      manualStart.current = {
        x: Math.max(0, Math.min(1, event.nativeEvent.locationX / mediaRect.width)),
        y: Math.max(0, Math.min(1, event.nativeEvent.locationY / mediaRect.height)),
      };
    },
    onPanResponderMove: (event) => {
      const start = manualStart.current;
      if (!start || !mediaRect || !onChangeSelectedTextRegion) return;
      const current = {
        x: Math.max(0, Math.min(1, event.nativeEvent.locationX / mediaRect.width)),
        y: Math.max(0, Math.min(1, event.nativeEvent.locationY / mediaRect.height)),
      };
      const x = Math.min(start.x, current.x);
      const y = Math.min(start.y, current.y);
      onChangeSelectedTextRegion({
        id: 'manual-current',
        text: '',
        source: 'manual',
        rect: {
          x,
          y,
          width: Math.min(1 - x, Math.max(0.05, Math.abs(current.x - start.x))),
          height: Math.min(1 - y, Math.max(0.05, Math.abs(current.y - start.y))),
        },
      });
    },
    onPanResponderRelease: () => {
      manualStart.current = null;
      onManualTextRegionComplete?.();
    },
    onPanResponderTerminate: () => {
      manualStart.current = null;
    },
  }), [
    disabled,
    manualTextRegionMode,
    mediaRect,
    onChangeSelectedTextRegion,
    onManualTextRegionComplete,
  ]);
  const selectPan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: (event) => {
      if (!mediaRect || manualTextRegionMode) return false;
      return viewPointToNormalizedPoint({ x: event.nativeEvent.locationX, y: event.nativeEvent.locationY }, mediaRect) !== null;
    },
    onPanResponderRelease: () => {
      onSelectLayer(null);
      onSelectTextRegion?.(null);
    },
  }), [manualTextRegionMode, mediaRect, onSelectLayer, onSelectTextRegion]);

  return (
    <View style={styles.root} onLayout={onLayout} {...selectPan.panHandlers} accessibilityLabel="Meme editing canvas">
      <View style={styles.checker} pointerEvents="none" />
      {mediaRect ? (
        <SourceMedia project={project} uri={sourceUri} mediaRect={mediaRect} previewAudio={previewAudio} seekRequest={seekRequest} onTimeUs={onVideoTime} />
      ) : (
        <View style={styles.loadingBox}><Text style={styles.unavailableText}>Measuring canvas…</Text></View>
      )}
      {mediaRect && project.layers.map((layer) => {
        const hidden = canvasLayerHidden(layer, activeTimeUs, before);
        if (layer.kind === 'cover') {
          return <CoverLayerView key={layer.id} project={project} layer={layer} mediaRect={mediaRect} selected={selectedLayerId === layer.id} hidden={hidden} activeTimeUs={activeTimeUs} disabled={disabled} onSelectLayer={onSelectLayer} onCommitLayerKeyframes={onCommitLayerKeyframes} />;
        }
        return <TransformableLayerView key={layer.id} project={project} layer={layer} mediaRect={mediaRect} selected={selectedLayerId === layer.id} hidden={hidden} activeTimeUs={activeTimeUs} disabled={disabled} onSelectLayer={onSelectLayer} onCommitLayerKeyframes={onCommitLayerKeyframes} />;
      })}
      {mediaRect && textRegions.map((region) => (
        <TextRegionOverlay
          key={region.id}
          region={region}
          mediaRect={mediaRect}
          selected={selectedTextRegion?.id === region.id}
          disabled={!!disabled}
          onSelect={(next) => onSelectTextRegion?.(next)}
        />
      ))}
      {mediaRect && selectedTextRegion && onChangeSelectedTextRegion && (
        <SelectedTextRegionOverlay
          region={selectedTextRegion}
          mediaRect={mediaRect}
          disabled={!!disabled}
          onChange={onChangeSelectedTextRegion}
        />
      )}
      {mediaRect && manualTextRegionMode && (
        <View
          {...manualPan.panHandlers}
          style={[styles.manualRegionSurface, viewRectToAbsoluteStyle(mediaRect)]}
          accessible
          accessibilityRole="button"
          accessibilityLabel="Manual text region drawing surface"
          accessibilityHint="Drag to draw a box over text that detection missed"
          accessibilityState={{ disabled: !!disabled }}
          accessibilityActions={[{ name: 'activate', label: 'Create centered manual region' }]}
          onAccessibilityAction={(event) => {
            if (event.nativeEvent.actionName === 'activate' && onChangeSelectedTextRegion) {
              onChangeSelectedTextRegion(defaultManualTextRegion());
              onManualTextRegionComplete?.();
            }
          }}
        />
      )}
      <View style={styles.bounds} pointerEvents="none">
        {mediaRect && <View style={[styles.mediaBounds, viewRectToAbsoluteStyle(mediaRect)]} />}
      </View>
    </View>
  );
});

const styles = StyleSheet.create({
  root: { flex: 1, minHeight: 260, backgroundColor: colors.bg, overflow: 'hidden' },
  checker: { ...StyleSheet.absoluteFill, backgroundColor: colors.bg },
  sourceMedia: { position: 'absolute', backgroundColor: colors.surface },
  sourceClip: { position: 'absolute', overflow: 'hidden', backgroundColor: colors.surface },
  orientedSource: { position: 'absolute' },
  bounds: { ...StyleSheet.absoluteFill },
  mediaBounds: { position: 'absolute', borderWidth: StyleSheet.hairlineWidth, borderColor: colors.borderLight },
  loadingBox: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  coverLayer: {
    position: 'absolute',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.overlay,
    borderWidth: 1,
    borderColor: colors.borderLight,
  },
  pixelGrid: {
    ...StyleSheet.absoluteFill,
    flexDirection: 'row',
    flexWrap: 'wrap',
    overflow: 'hidden',
  },
  pixelCell: { borderWidth: StyleSheet.hairlineWidth, borderColor: colors.border },
  pixelError: { ...type.micro, color: colors.text, padding: space.xs, textAlign: 'center' },
  coverText: { ...type.micro, color: colors.textDim },
  transformLayer: {
    position: 'absolute',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.sm,
  },
  transformControls: {
    position: 'absolute',
    borderRadius: radius.sm,
  },
  selectedOutline: {
    borderWidth: 2,
    borderColor: colors.volt,
    borderStyle: 'solid',
  },
  textRegionTarget: { position: 'absolute', zIndex: 20 },
  textRegionBox: {
    position: 'absolute',
    borderWidth: 2,
    borderColor: colors.accent,
    borderStyle: 'dashed',
    backgroundColor: 'rgba(255,255,255,0.08)',
  },
  textRegionSelected: { borderColor: colors.volt, borderStyle: 'solid' },
  selectedTextRegion: {
    position: 'absolute',
    zIndex: 21,
    minWidth: 44,
    minHeight: 44,
    borderWidth: 2,
    borderColor: colors.volt,
    backgroundColor: colors.voltDim,
    alignItems: 'center',
    justifyContent: 'center',
  },
  textRegionLabel: {
    ...type.micro,
    color: colors.text,
    backgroundColor: colors.surface,
    paddingHorizontal: space.xs,
    maxWidth: '100%',
  },
  textRegionResizeHandle: {
    position: 'absolute',
    right: -22,
    bottom: -22,
    width: 44,
    height: 44,
    borderRadius: 22,
    borderWidth: 2,
    borderColor: colors.volt,
    backgroundColor: colors.surface,
  },
  manualRegionSurface: {
    position: 'absolute',
    zIndex: 22,
    borderWidth: 2,
    borderColor: colors.accent,
    borderStyle: 'dashed',
    backgroundColor: 'rgba(255,255,255,0.03)',
  },
  textDiagnostic: { ...type.micro, color: colors.danger, marginTop: space.xs },
  textFill: { flex: 1, alignSelf: 'stretch', alignItems: 'center', justifyContent: 'center', padding: space.xs },
  textBacking: {
    maxWidth: '100%',
    alignSelf: 'stretch',
    justifyContent: 'center',
  },
  bubbleTail: {
    position: 'absolute',
    left: space.md,
    bottom: -space.xs,
    width: space.md,
    height: space.md,
    transform: [{ rotate: '45deg' }],
  },
  layerText: {
    width: '100%',
    color: colors.text,
    fontWeight: '900',
  },
  outlineText: {
    position: 'absolute',
    width: '100%',
  },
  subjectFill: {
    flex: 1,
    alignSelf: 'stretch',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: colors.good,
    backgroundColor: colors.goodDim,
  },
  unavailableFill: {
    borderColor: colors.danger,
    borderStyle: 'dashed',
    backgroundColor: colors.dangerDim,
  },
  unavailableText: { ...type.caption, color: colors.textDim, textAlign: 'center' },
  rotateHandle: {
    position: 'absolute',
    top: -44,
    left: '50%',
    marginLeft: -22,
    width: 44,
    height: 44,
    borderRadius: 22,
    borderWidth: 2,
    borderColor: colors.volt,
    backgroundColor: colors.voltDim,
  },
  resizeHandle: {
    position: 'absolute',
    right: -22,
    bottom: -22,
    width: 44,
    height: 44,
    borderRadius: 22,
    borderWidth: 2,
    borderColor: colors.volt,
    backgroundColor: colors.surface2,
  },
});
