import React, { useCallback, useMemo, useRef, useState } from 'react';
import {
  PanResponder,
  ScrollView,
  StyleSheet,
  Text,
  View,
  type AccessibilityActionEvent,
  type LayoutChangeEvent,
} from 'react-native';

import {
  applyCropPreset,
  moveCropHandle,
  nextQuarterRotation,
  type CropHandle,
  type ImageCropPreset,
} from '../memeImageEditCore';
import type { BaseTransform, MemeEditProject } from '../memeEditProjectCore';
import { colors, radius, space, type } from '../theme';
import { PressableScale } from './ui';

const CROP_HANDLES: readonly CropHandle[] = [
  'top-left',
  'top-right',
  'bottom-left',
  'bottom-right',
];

const PRESETS: readonly { id: ImageCropPreset; label: string }[] = [
  { id: 'source', label: 'Source' },
  { id: '1:1', label: '1:1' },
  { id: '4:5', label: '4:5' },
  { id: '9:16', label: '9:16' },
  { id: '16:9', label: '16:9' },
  { id: 'free', label: 'Free' },
];

interface CropHandleViewProps {
  handle: CropHandle;
  base: BaseTransform;
  width: number;
  height: number;
  disabled: boolean;
  onPreviewBase: (base: BaseTransform) => void;
  onCommitBase: (base: BaseTransform) => void;
}

const CropHandleView = React.memo(function CropHandleView({
  handle,
  base,
  width,
  height,
  disabled,
  onPreviewBase,
  onCommitBase,
}: CropHandleViewProps) {
  const startRef = useRef<BaseTransform | null>(null);
  const nextRef = useRef<BaseTransform | null>(null);
  const pan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => !disabled && width > 0 && height > 0,
    onMoveShouldSetPanResponder: (_event, gesture) =>
      !disabled && Math.abs(gesture.dx) + Math.abs(gesture.dy) > 2,
    onPanResponderGrant: () => {
      startRef.current = base;
      nextRef.current = base;
    },
    onPanResponderMove: (_event, gesture) => {
      const start = startRef.current;
      if (!start) return;
      const next = {
        ...start,
        crop: moveCropHandle(start.crop, handle, {
          x: gesture.dx / width,
          y: gesture.dy / height,
        }),
        outputAspect: 'free' as const,
      };
      nextRef.current = next;
      onPreviewBase(next);
    },
    onPanResponderRelease: () => {
      const next = nextRef.current;
      startRef.current = null;
      nextRef.current = null;
      if (next) onCommitBase(next);
    },
    onPanResponderTerminate: () => {
      const start = startRef.current;
      startRef.current = null;
      nextRef.current = null;
      if (start) onPreviewBase(start);
    },
  }), [base, disabled, handle, height, onCommitBase, onPreviewBase, width]);

  const nudge = useCallback((event: AccessibilityActionEvent) => {
    if (disabled) return;
    const direction = event.nativeEvent.actionName === 'increment' ? 1 : -1;
    const signX = handle.includes('left') ? -1 : 1;
    const signY = handle.includes('top') ? -1 : 1;
    const next = {
      ...base,
      crop: moveCropHandle(base.crop, handle, {
        x: direction * signX * 0.02,
        y: direction * signY * 0.02,
      }),
      outputAspect: 'free' as const,
    };
    onPreviewBase(next);
    onCommitBase(next);
  }, [base, disabled, handle, onCommitBase, onPreviewBase]);
  const handleX = handle.includes('left') ? base.crop.x : base.crop.x + base.crop.width;
  const handleY = handle.includes('top') ? base.crop.y : base.crop.y + base.crop.height;

  return (
    <View
      {...pan.panHandlers}
      style={[
        styles.cropHandle,
        {
          left: `${handleX * 100}%`,
          top: `${handleY * 100}%`,
          marginLeft: -22,
          marginTop: -22,
        },
      ]}
      accessible
      accessibilityRole="adjustable"
      accessibilityLabel={`${handle.replace('-', ' ')} free crop handle`}
      accessibilityHint="Drag to resize the visible crop. Increment expands and decrement contracts."
      accessibilityState={{ disabled }}
      accessibilityActions={[
        { name: 'increment', label: 'Expand crop from this corner' },
        { name: 'decrement', label: 'Contract crop from this corner' },
      ]}
      onAccessibilityAction={nudge}
    />
  );
});

export const MemeTransformInspector = React.memo(function MemeTransformInspector({
  project,
  base,
  disabled,
  onPreviewBase,
  onCommitBase,
}: {
  project: MemeEditProject;
  base: BaseTransform;
  disabled?: boolean;
  onPreviewBase: (base: BaseTransform) => void;
  onCommitBase: (base: BaseTransform) => void;
}) {
  const [cropSize, setCropSize] = useState({ width: 0, height: 0 });
  const source = { width: project.source.width, height: project.source.height };
  const applyImmediate = useCallback((next: BaseTransform) => {
    onPreviewBase(next);
    onCommitBase(next);
  }, [onCommitBase, onPreviewBase]);
  const selectPreset = useCallback((preset: ImageCropPreset) => {
    if (!disabled) applyImmediate(applyCropPreset(base, preset, source));
  }, [applyImmediate, base, disabled, source.height, source.width]);
  const rotate = useCallback(() => {
    if (disabled) return;
    const rotated = { ...base, rotation: nextQuarterRotation(base.rotation) };
    applyImmediate(applyCropPreset(rotated, base.outputAspect, source));
  }, [applyImmediate, base, disabled, source.height, source.width]);
  const flipHorizontal = useCallback(() => {
    if (!disabled) applyImmediate({ ...base, flipX: !base.flipX });
  }, [applyImmediate, base, disabled]);
  const flipVertical = useCallback(() => {
    if (!disabled) applyImmediate({ ...base, flipY: !base.flipY });
  }, [applyImmediate, base, disabled]);
  const onCropLayout = useCallback((event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    setCropSize((current) =>
      Math.abs(current.width - width) < 0.5 && Math.abs(current.height - height) < 0.5
        ? current
        : { width, height }
    );
  }, []);

  return (
    <ScrollView contentContainerStyle={styles.root} keyboardShouldPersistTaps="handled">
      <Text style={styles.title}>Image transform</Text>
      <Text style={styles.copy}>
        Crop, rotate, or flip the image. Existing text, media, cover, and mask geometry stays attached to the image and each gesture is one undo step.
      </Text>

      <Text style={styles.sectionLabel}>Crop preset</Text>
      <View style={styles.chips}>
        {PRESETS.map((preset) => (
          <PressableScale
            key={preset.id}
            onPress={() => selectPreset(preset.id)}
            disabled={disabled}
            style={[styles.chip, base.outputAspect === preset.id && styles.chipSelected]}
            accessibilityRole="button"
            accessibilityLabel={`${preset.label} crop`}
            accessibilityHint={preset.id === 'free' ? 'Enable draggable free crop handles' : `Crop to ${preset.label}`}
            accessibilityState={{ selected: base.outputAspect === preset.id, disabled: !!disabled }}
          >
            <Text style={[styles.chipText, base.outputAspect === preset.id && styles.chipTextSelected]}>{preset.label}</Text>
          </PressableScale>
        ))}
      </View>

      <View style={styles.cropStage} onLayout={onCropLayout} accessibilityLabel="Free crop preview">
        <View
          pointerEvents="none"
          style={[
            styles.cropWindow,
            {
              left: `${base.crop.x * 100}%`,
              top: `${base.crop.y * 100}%`,
              width: `${base.crop.width * 100}%`,
              height: `${base.crop.height * 100}%`,
            },
          ]}
        />
        {base.outputAspect === 'free' && CROP_HANDLES.map((handle) => (
          <CropHandleView
            key={handle}
            handle={handle}
            base={base}
            width={cropSize.width}
            height={cropSize.height}
            disabled={!!disabled}
            onPreviewBase={onPreviewBase}
            onCommitBase={onCommitBase}
          />
        ))}
      </View>
      <Text style={styles.metric}>
        Crop {Math.round(base.crop.width * 100)}% × {Math.round(base.crop.height * 100)}% · rotation {base.rotation}° · horizontal flip {base.flipX ? 'on' : 'off'} · vertical flip {base.flipY ? 'on' : 'off'}
      </Text>

      <View style={styles.actionRow}>
        <PressableScale
          onPress={rotate}
          disabled={disabled}
          style={styles.action}
          accessibilityRole="button"
          accessibilityLabel="Rotate image 90 degrees clockwise"
          accessibilityState={{ disabled: !!disabled }}
        >
          <Text style={styles.actionText}>Rotate 90°</Text>
        </PressableScale>
        <PressableScale
          onPress={flipHorizontal}
          disabled={disabled}
          style={[styles.action, base.flipX && styles.actionSelected]}
          accessibilityRole="button"
          accessibilityLabel="Flip image horizontally"
          accessibilityState={{ selected: base.flipX, disabled: !!disabled }}
        >
          <Text style={styles.actionText}>Flip horizontal</Text>
        </PressableScale>
        <PressableScale
          onPress={flipVertical}
          disabled={disabled}
          style={[styles.action, base.flipY && styles.actionSelected]}
          accessibilityRole="button"
          accessibilityLabel="Flip image vertically"
          accessibilityState={{ selected: base.flipY, disabled: !!disabled }}
        >
          <Text style={styles.actionText}>Flip vertical</Text>
        </PressableScale>
      </View>
    </ScrollView>
  );
});

const styles = StyleSheet.create({
  root: { padding: space.md, gap: space.sm, paddingBottom: space.xxl },
  title: { ...type.title, color: colors.text },
  copy: { ...type.body, color: colors.textDim },
  sectionLabel: { ...type.micro, color: colors.textDim, textTransform: 'uppercase', marginTop: space.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  chip: {
    minHeight: 44,
    minWidth: 56,
    paddingHorizontal: space.md,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  chipSelected: { borderColor: colors.volt, backgroundColor: colors.voltDim },
  chipText: { ...type.caption, color: colors.textDim },
  chipTextSelected: { color: colors.volt, fontWeight: '800' },
  cropStage: {
    height: 180,
    marginHorizontal: space.lg,
    overflow: 'visible',
    borderWidth: 1,
    borderColor: colors.borderLight,
    backgroundColor: colors.bg,
  },
  cropWindow: {
    position: 'absolute',
    borderWidth: 2,
    borderColor: colors.volt,
    backgroundColor: colors.voltDim,
  },
  cropHandle: {
    position: 'absolute',
    width: 44,
    height: 44,
    borderRadius: 22,
    borderWidth: 2,
    borderColor: colors.volt,
    backgroundColor: colors.surface,
  },
  metric: { ...type.micro, color: colors.textDim, textAlign: 'center' },
  actionRow: { gap: space.xs },
  action: {
    minHeight: 44,
    paddingHorizontal: space.md,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  actionSelected: { borderColor: colors.volt, backgroundColor: colors.voltDim },
  actionText: { ...type.body, color: colors.text, fontWeight: '700' },
});
