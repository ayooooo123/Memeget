import React, { useCallback, useEffect, useMemo, useRef } from 'react';
import { Animated, PanResponder, StyleSheet, Text, View, type LayoutChangeEvent } from 'react-native';
import { Image } from 'expo-image';
import { useVideoPlayer, VideoView } from 'expo-video';

import { measureMemeTextLayout } from '../../modules/memeget-bg';
import {
  containedMediaRect,
  dragKeyframeByViewDelta,
  gestureMoveShouldClaim,
  layerBodyTouchInsideMedia,
  layerHandlePoints,
  layerHandleTouchInsideMedia,
  normalizedPointToViewPoint,
  resizeKeyframeFromHandle,
  rotateKeyframeFromHandle,
  transformAccessibilityAction,
  transformHandleAccessibilityAction,
  viewPointToNormalizedPoint,
  viewRectToAbsoluteStyle,
  type AbsoluteRectStyle,
  type ViewDelta,
  type ViewPoint,
  type ViewRect,
  type ViewSize,
} from '../memeEditCanvasCore';
import {
  evaluateMaskTrackRect,
  interpolateTransformKeyframes,
  isLayerActiveAt,
  type CoverLayer,
  type KeyframedLayer,
  type MediaOverlayLayer,
  type MemeEditLayer,
  type MemeEditProject,
  type NormalizedRect,
  type SubjectLayer,
  type TextLayer,
  type TransformKeyframe,
} from '../memeEditProjectCore';
import { buildMemeTextLayoutSpec, compareNativeMemeTextLayoutToSpec, memeTextBackingRadiusForPreview, memeTextMeasureKey, nativeMemeTextLayoutInputFromSpec, type MemeTextLayoutSpec } from '../memeTextLayoutCore';
import { colors, radius, space, type } from '../theme';
import { useConst } from '../reactUtils';

const PREVIEW_TIME_POLL_MS = 33;
const DEFAULT_LAYER_ASPECT = 1;

type CommitLayerKeyframes = (layerId: string, keyframes: TransformKeyframe[]) => void;

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

function writeFirstKeyframe(layer: KeyframedLayer, keyframe: TransformKeyframe): TransformKeyframe[] {
  if (layer.keyframes.length === 0) return [keyframe];
  return [{ ...keyframe, timeUs: layer.keyframes[0].timeUs }, ...layer.keyframes.slice(1)];
}

function rectStyle(rect: NormalizedRect, mediaRect: ViewRect) {
  return {
    left: mediaRect.x + rect.x * mediaRect.width,
    top: mediaRect.y + rect.y * mediaRect.height,
    width: rect.width * mediaRect.width,
    height: rect.height * mediaRect.height,
  };
}

function layerCenterPoint(keyframe: TransformKeyframe, mediaRect: ViewRect): ViewPoint {
  return normalizedPointToViewPoint(keyframe.center, mediaRect);
}

function keyframeLayerBox(keyframe: TransformKeyframe, layerWidth: number, mediaRect: ViewRect): ViewRect {
  const center = layerCenterPoint(keyframe, mediaRect);
  const width = Math.max(44, mediaRect.width * Math.max(0.04, layerWidth));
  const height = Math.max(44, width * DEFAULT_LAYER_ASPECT);
  return { x: center.x - width / 2, y: center.y - height / 2, width, height };
}

const SourceVideo = React.memo(function SourceVideo({ uri, style, onTimeUs }: { uri: string; style: AbsoluteRectStyle; onTimeUs: (timeUs: number) => void }) {
  const player = useVideoPlayer(uri, (instance) => {
    instance.loop = true;
    instance.play();
  });
  useEffect(() => {
    const timer = setInterval(() => {
      onTimeUs(Math.max(0, Math.round((player.currentTime ?? 0) * 1_000_000)));
    }, PREVIEW_TIME_POLL_MS);
    return () => clearInterval(timer);
  }, [onTimeUs, player]);
  return <VideoView pointerEvents="none" style={[styles.sourceMedia, style]} player={player} contentFit="contain" nativeControls={false} />;
});

const OverlayVideo = React.memo(function OverlayVideo({ uri }: { uri: string }) {
  const player = useVideoPlayer(uri, (instance) => {
    instance.loop = true;
    instance.muted = true;
    instance.play();
  });
  return <VideoView pointerEvents="none" style={StyleSheet.absoluteFill} player={player} contentFit="contain" nativeControls={false} />;
});

const CoverLayerView = React.memo(function CoverLayerView({ layer, mediaRect, selected, hidden, activeTimeUs, disabled, onSelectLayer }: CanvasLayerProps & { layer: CoverLayer }) {
  const correction = evaluateMaskTrackRect({ id: layer.id, active: layer.active, corrections: layer.corrections }, activeTimeUs);
  const rect = correction ?? layer.rect;
  if (hidden) return null;
  return (
    <View
      style={[styles.coverLayer, rectStyle(rect, mediaRect), selected && styles.selectedOutline]}
      onStartShouldSetResponder={(event) => !disabled && viewPointToNormalizedPoint({ x: event.nativeEvent.locationX + mediaRect.x, y: event.nativeEvent.locationY + mediaRect.y }, mediaRect) !== null}
      onResponderRelease={() => {
        if (!disabled) onSelectLayer(layer.id);
      }}
      accessibilityRole="button"
      accessibilityLabel="Cover correction layer"
      accessibilityHint="Select this correction layer"
      accessibilityState={{ selected, disabled: !!disabled }}
    >
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
  const gestureStart = useRef<{ keyframe: TransformKeyframe; center: ViewPoint; handle: ViewPoint } | null>(null);
  const dragStartAccepted = useRef(false);
  const resizeStartAccepted = useRef(false);
  const rotateStartAccepted = useRef(false);
  const keyframe = firstKeyframe(layer, activeTimeUs);
  const visualWidth = layer.kind === 'text' ? layer.width : 0.28;
  const box = keyframeLayerBox(keyframe, visualWidth, mediaRect);
  const handles = layerHandlePoints(keyframe, visualWidth, mediaRect);
  const textSpec = useMemo(() => layer.kind === 'text'
    ? buildMemeTextLayoutSpec(layer, keyframe, { canvasWidthPx: mediaRect.width, canvasHeightPx: mediaRect.height })
    : null, [keyframe, layer, mediaRect.height, mediaRect.width]);

  useEffect(() => {
    translate.setValue({ x: 0, y: 0 });
    scalePreview.setValue(1);
    rotatePreview.setValue(0);
  }, [keyframe.center.x, keyframe.center.y, keyframe.scale, keyframe.rotationDegrees, scalePreview, translate, rotatePreview]);

  const commit = useCallback((nextKeyframe: TransformKeyframe) => {
    onCommitLayerKeyframes(layer.id, writeFirstKeyframe(layer, nextKeyframe));
    translate.setValue({ x: 0, y: 0 });
    scalePreview.setValue(1);
    rotatePreview.setValue(0);
  }, [layer, onCommitLayerKeyframes, rotatePreview, scalePreview, translate]);

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
      gestureStart.current = { keyframe, center: handles.center, handle: handles.resize };
      translate.setValue({ x: 0, y: 0 });
    },
    onPanResponderMove: (_event, gesture) => {
      if (dragStartAccepted.current) translate.setValue({ x: gesture.dx, y: gesture.dy });
    },
    onPanResponderRelease: (_event, gesture) => {
      if (dragStartAccepted.current) {
        const start = gestureStart.current?.keyframe ?? keyframe;
        commit(dragKeyframeByViewDelta(start, { dx: gesture.dx, dy: gesture.dy } satisfies ViewDelta, mediaRect));
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
      gestureStart.current = { keyframe, center: handles.center, handle: handles.resize };
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
      if (resizeStartAccepted.current && start) commit(resizeKeyframeFromHandle(start.keyframe, start.center, start.handle, { x: start.handle.x + gesture.dx, y: start.handle.y + gesture.dy }));
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
      gestureStart.current = { keyframe, center: handles.center, handle: handles.rotate };
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
      if (rotateStartAccepted.current && start) commit(rotateKeyframeFromHandle(start.keyframe, start.center, start.handle, { x: start.handle.x + gesture.dx, y: start.handle.y + gesture.dy }));
      rotateStartAccepted.current = false;
      gestureStart.current = null;
    },
    onPanResponderTerminate: () => {
      rotatePreview.setValue(0);
      rotateStartAccepted.current = false;
      gestureStart.current = null;
    },
  }), [commit, disabled, handles.center, handles.rotate, keyframe, mediaRect, rotatePreview, selected, visualWidth]);

  if (hidden) return null;

  const rotation = rotatePreview.interpolate({
    inputRange: [-360, 360],
    outputRange: [`${keyframe.rotationDegrees - 360}deg`, `${keyframe.rotationDegrees + 360}deg`],
  });

  return (
    <Animated.View
      style={[
        styles.transformLayer,
        { left: box.x, top: box.y, width: box.width, height: box.height, opacity: keyframe.opacity },
        selected && styles.selectedOutline,
        {
          transform: [
            { translateX: translate.x },
            { translateY: translate.y },
            { scale: Animated.multiply(scalePreview, keyframe.scale) },
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
        if (next !== keyframe) commit(next);
        else onSelectLayer(layer.id);
      }}
    >
      {layer.kind === 'media' && <MediaLayerContent layer={layer} />}
      {layer.kind === 'text' && textSpec && <TextLayerContent spec={textSpec} />}
      {layer.kind === 'subject' && <SubjectLayerContent project={project} layer={layer} />}
      {selected && (
        <>
          <Animated.View
            style={styles.rotateHandle}
            {...rotatePan.panHandlers}
            accessibilityRole="adjustable"
            accessibilityLabel="Rotate selected layer"
            accessibilityState={{ disabled: !!disabled }}
            accessibilityActions={[{ name: 'increment', label: 'Rotate clockwise' }, { name: 'decrement', label: 'Rotate counter-clockwise' }]}
            onAccessibilityAction={(event) => {
              if (!disabled) commit(transformHandleAccessibilityAction('rotate', event.nativeEvent.actionName as 'increment' | 'decrement', keyframe, mediaRect));
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
              if (!disabled) commit(transformHandleAccessibilityAction('resize', event.nativeEvent.actionName as 'increment' | 'decrement', keyframe, mediaRect));
            }}
          />
        </>
      )}
    </Animated.View>
  );
});

const TextLayerContent = React.memo(function TextLayerContent({ spec }: { spec: MemeTextLayoutSpec }) {
  const [diagnostic, setDiagnostic] = React.useState<string | null>(null);
  const [nativeLayout, setNativeLayout] = React.useState<Awaited<ReturnType<typeof measureMemeTextLayout>> | null>(null);
  const boxStyle = useMemo(() => ({
    backgroundColor: spec.backing.color ?? 'transparent',
    borderRadius: memeTextBackingRadiusForPreview(spec),
    paddingHorizontal: spec.backing.paddingXPx,
    paddingVertical: spec.backing.paddingYPx,
    opacity: spec.fill.opacity,
  }), [spec]);
  const textStyle = useMemo(() => ({
    color: spec.fill.color,
    fontSize: spec.canvas.fontSizePx,
    lineHeight: spec.layout.lineHeightPx,
    fontWeight: spec.font.weight,
    fontFamily: spec.font.family,
    letterSpacing: spec.font.letterSpacingEm * spec.canvas.fontSizePx,
    textAlign: spec.align,
    includeFontPadding: false,
  }), [spec]);
  const outlineOffsets = useMemo(() => {
    if (spec.outline.widthPx <= 0) return [];
    const amount = Math.max(1, spec.outline.widthPx);
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
  }, [spec.outline.widthPx]);
  const displayText = spec.displayText;
  const measureKey = useMemo(() => memeTextMeasureKey(spec), [spec]);
  const specRef = useRef(spec);
  specRef.current = spec;

  useEffect(() => {
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
    if (!nativeLayout) {
      setDiagnostic(null);
      return;
    }
    const comparison = compareNativeMemeTextLayoutToSpec(spec, nativeLayout);
    setDiagnostic(comparison.ok
      ? null
      : `Text metrics drift: ${comparison.lineCountDrift} lines, ${comparison.maxBaselineDriftPx.toFixed(1)}px baseline`);
  }, [nativeLayout, spec]);

  return (
    <View style={styles.textFill} pointerEvents="none">
      <View style={[styles.textBacking, boxStyle]}>
        {outlineOffsets.map((offset) => (
          <Text key={`${offset.left}:${offset.top}`} style={[styles.layerText, textStyle, styles.outlineText, { color: spec.outline.color, left: offset.left, top: offset.top }]}>
            {displayText}
          </Text>
        ))}
        <Text style={[styles.layerText, textStyle]}>{displayText}</Text>
        {spec.backing.tail === 'bottom-left' && <View style={[styles.bubbleTail, { backgroundColor: spec.backing.color ?? colors.text }]} />}
      </View>
      {!!diagnostic && <Text style={styles.textDiagnostic}>{diagnostic}</Text>}
    </View>
  );
});

const SubjectLayerContent = React.memo(function SubjectLayerContent({ project, layer }: { project: MemeEditProject; layer: SubjectLayer }) {
  const hasMask = !!project.transient.maskTracks[layer.maskTrackId];
  return (
    <View style={[styles.subjectFill, !hasMask && styles.unavailableFill]} pointerEvents="none">
      <Text style={styles.unavailableText}>{hasMask ? 'Subject mask' : 'Subject mask unavailable'}</Text>
    </View>
  );
});

const MediaLayerContent = React.memo(function MediaLayerContent({ layer }: { layer: MediaOverlayLayer }) {
  if (layer.assetKind === 'video') return <OverlayVideo uri={layer.assetUri} />;
  return <Image source={{ uri: layer.assetUri }} style={StyleSheet.absoluteFill} contentFit={layer.fit} cachePolicy="none" />;
});

export const MemeEditCanvas = React.memo(function MemeEditCanvas({
  project,
  selectedLayerId,
  before,
  onSelectLayer,
  onCommitLayerKeyframes,
  disabled,
}: {
  project: MemeEditProject;
  selectedLayerId: string | null;
  before: boolean;
  onSelectLayer: (id: string | null) => void;
  onCommitLayerKeyframes: CommitLayerKeyframes;
  disabled?: boolean;
}) {
  const [viewSize, setViewSize] = React.useState<ViewSize | null>(null);
  const [activeTimeUs, setActiveTimeUs] = React.useState(0);
  const sourceUri = project.transient.materializedSourceUri ?? project.source.uri;
  const mediaRect = useMemo(
    () => viewSize ? containedMediaRect(viewSize, { width: project.source.width, height: project.source.height, rotation: project.base.rotation }) : null,
    [project.base.rotation, project.source.height, project.source.width, viewSize]
  );
  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const next = event.nativeEvent.layout;
    setViewSize((current) => {
      if (current && Math.abs(current.width - next.width) < 0.5 && Math.abs(current.height - next.height) < 0.5) return current;
      return { width: next.width, height: next.height };
    });
  }, []);
  const onVideoTime = useCallback((timeUs: number) => {
    const durationUs = project.source.durationUs;
    setActiveTimeUs((current) => {
      if (Math.abs(current - timeUs) < 16_667) return current;
      if (durationUs === null || durationUs <= 0) return timeUs;
      return Math.min(durationUs, timeUs);
    });
  }, [project.source.durationUs]);
  const selectPan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: (event) => {
      if (!mediaRect) return false;
      return viewPointToNormalizedPoint({ x: event.nativeEvent.locationX, y: event.nativeEvent.locationY }, mediaRect) !== null;
    },
    onPanResponderRelease: () => onSelectLayer(null),
  }), [mediaRect, onSelectLayer]);

  return (
    <View style={styles.root} onLayout={onLayout} {...selectPan.panHandlers} accessibilityLabel="Meme editing canvas">
      <View style={styles.checker} pointerEvents="none" />
      {mediaRect ? (
        project.source.kind === 'video' ? (
          <SourceVideo uri={sourceUri} style={viewRectToAbsoluteStyle(mediaRect)} onTimeUs={onVideoTime} />
        ) : (
          <Image source={{ uri: sourceUri }} style={[styles.sourceMedia, viewRectToAbsoluteStyle(mediaRect)]} contentFit="contain" cachePolicy="none" />
        )
      ) : (
        <View style={styles.loadingBox}><Text style={styles.unavailableText}>Measuring canvas…</Text></View>
      )}
      {mediaRect && project.layers.map((layer) => {
        const hidden = before || !isLayerActiveAt(layer, activeTimeUs);
        if (layer.kind === 'cover') {
          return <CoverLayerView key={layer.id} project={project} layer={layer} mediaRect={mediaRect} selected={selectedLayerId === layer.id} hidden={hidden} activeTimeUs={activeTimeUs} disabled={disabled} onSelectLayer={onSelectLayer} onCommitLayerKeyframes={onCommitLayerKeyframes} />;
        }
        return <TransformableLayerView key={layer.id} project={project} layer={layer} mediaRect={mediaRect} selected={selectedLayerId === layer.id} hidden={hidden} activeTimeUs={activeTimeUs} disabled={disabled} onSelectLayer={onSelectLayer} onCommitLayerKeyframes={onCommitLayerKeyframes} />;
      })}
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
  coverText: { ...type.micro, color: colors.textDim },
  transformLayer: {
    position: 'absolute',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.sm,
  },
  selectedOutline: {
    borderWidth: 2,
    borderColor: colors.volt,
    borderStyle: 'solid',
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
