// Shared UI primitives. Everything here is presentation-only and animation is
// kept on the native driver so the grid/scroll never competes with JS work.
import React, { useRef } from 'react';
import {
  Animated,
  PanResponder,
  Pressable,
  StyleSheet,
  Text,
  View,
  type PressableProps,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from 'react-native';

import { colors, radius, type } from '../theme';
import { useConst } from '../reactUtils';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

// Pressable with a quick scale-down — the single biggest "native feel" win.
// Style and transform live on the same node so flex/width styles from callers
// lay out exactly like a plain Pressable would.
export function PressableScale({
  children,
  style,
  scaleTo = 0.96,
  disabled,
  ...rest
}: PressableProps & { children?: React.ReactNode; style?: StyleProp<ViewStyle>; scaleTo?: number }) {
  // Lazy: a fresh Animated.Value per render (the useRef(new …) trap) is pure
  // waste here — PressableScale wraps every grid cell, chip, and button.
  const scale = useConst(() => new Animated.Value(1));
  const animate = (to: number) =>
    Animated.spring(scale, { toValue: to, useNativeDriver: true, speed: 50, bounciness: 0 }).start();
  return (
    <AnimatedPressable
      onPressIn={() => animate(scaleTo)}
      onPressOut={() => animate(1)}
      disabled={disabled}
      style={[style, { transform: [{ scale }] }, disabled ? styles.disabled : null]}
      {...rest}
    >
      {children}
    </AnimatedPressable>
  );
}

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'dangerGhost';

export function Button({
  label,
  onPress,
  variant = 'primary',
  small,
  disabled,
  icon,
  style,
}: {
  label: string;
  onPress: () => void;
  variant?: ButtonVariant;
  small?: boolean;
  disabled?: boolean;
  icon?: string;
  style?: StyleProp<ViewStyle>;
}) {
  const box: StyleProp<ViewStyle> = [
    styles.btn,
    small && styles.btnSmall,
    variant === 'primary' && styles.btnPrimary,
    variant === 'secondary' && styles.btnSecondary,
    variant === 'ghost' && styles.btnGhost,
    variant === 'danger' && styles.btnDanger,
    variant === 'dangerGhost' && styles.btnDangerGhost,
    style,
  ];
  const text: StyleProp<TextStyle> = [
    styles.btnText,
    small && styles.btnTextSmall,
    variant === 'primary' && { color: colors.onVolt },
    variant === 'secondary' && { color: colors.text },
    variant === 'ghost' && { color: colors.textDim },
    variant === 'danger' && { color: '#fff' },
    variant === 'dangerGhost' && { color: colors.danger },
  ];
  return (
    <PressableScale style={box} onPress={onPress} disabled={disabled}>
      <Text style={text} numberOfLines={1}>
        {icon ? `${icon}  ` : ''}
        {label}
      </Text>
    </PressableScale>
  );
}

export function Chip({
  label,
  onPress,
  onLongPress,
  active,
  taught,
  style,
}: {
  label: string;
  onPress?: () => void;
  onLongPress?: () => void;
  active?: boolean;
  taught?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <PressableScale
      scaleTo={0.93}
      onPress={onPress}
      onLongPress={onLongPress}
      style={[styles.chip, taught && styles.chipTaught, active && styles.chipActive, style]}
    >
      <Text
        style={[styles.chipText, taught && styles.chipTextTaught, active && styles.chipTextActive]}
        numberOfLines={1}
      >
        {taught ? '★ ' : ''}
        {label}
      </Text>
    </PressableScale>
  );
}

// Slim determinate progress bar; animates width changes smoothly.
export function ProgressBar({ value, tint = colors.volt }: { value: number; tint?: string }) {
  const anim = useConst(() => new Animated.Value(0));
  React.useEffect(() => {
    Animated.timing(anim, {
      toValue: Math.max(0, Math.min(1, value)),
      duration: 220,
      useNativeDriver: false, // width can't use native driver
    }).start();
  }, [value, anim]);
  return (
    <View style={styles.track}>
      <Animated.View
        style={[
          styles.fill,
          { backgroundColor: tint, width: anim.interpolate({ inputRange: [0, 1], outputRange: ['0%', '100%'] }) },
        ]}
      />
    </View>
  );
}

// Self-contained horizontal slider (0..1) — PanResponder + percentage layout,
// so we don't pull in a native slider dependency / rebuild. Tap or drag anywhere
// on the track. Width is read via onLayout into a ref so the gesture math is
// always against the current track size.
export function Slider({
  value,
  onChange,
  tint = colors.volt,
}: {
  value: number;
  onChange: (v: number) => void;
  tint?: string;
}) {
  const widthRef = useRef(0);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const setFromX = (x: number) => {
    const w = widthRef.current;
    if (!w) return;
    onChangeRef.current(Math.max(0, Math.min(1, x / w)));
  };

  // Lazy: PanResponder.create() runs once instead of on every render. The
  // gesture reads the latest onChange via onChangeRef, so a stable responder is
  // correct.
  const pan = useConst(() =>
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (e) => setFromX(e.nativeEvent.locationX),
      onPanResponderMove: (e) => setFromX(e.nativeEvent.locationX),
    })
  );

  const pct = `${Math.max(0, Math.min(1, value)) * 100}%` as `${number}%`;
  return (
    <View
      style={styles.sliderHit}
      onLayout={(e) => {
        widthRef.current = e.nativeEvent.layout.width;
      }}
      {...pan.panHandlers}
    >
      <View style={styles.sliderTrack}>
        <View style={[styles.sliderFill, { width: pct, backgroundColor: tint }]} />
      </View>
      <View style={[styles.sliderThumb, { left: pct, borderColor: tint }]} />
    </View>
  );
}

// Small status dot + label, e.g. model readiness.
export function StatusDot({ tone, label }: { tone: 'good' | 'busy' | 'bad'; label: string }) {
  const c = tone === 'good' ? colors.good : tone === 'bad' ? colors.danger : colors.accent;
  return (
    <View style={styles.statusRow}>
      <View style={[styles.dot, { backgroundColor: c }]} />
      <Text style={styles.statusText} numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  disabled: { opacity: 0.45 },
  btn: {
    paddingVertical: 13,
    paddingHorizontal: 18,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnSmall: { paddingVertical: 9, paddingHorizontal: 14, borderRadius: radius.sm + 2 },
  btnPrimary: { backgroundColor: colors.volt },
  btnSecondary: { backgroundColor: colors.surface2, borderWidth: 1, borderColor: colors.border },
  btnGhost: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.border },
  btnDanger: { backgroundColor: colors.danger },
  btnDangerGhost: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.danger },
  btnText: { ...type.label, fontSize: 14, fontWeight: '700' },
  btnTextSmall: { fontSize: 13 },
  chip: {
    backgroundColor: colors.chip,
    borderRadius: radius.pill,
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  chipActive: { backgroundColor: colors.voltDim, borderColor: colors.volt },
  chipTaught: { backgroundColor: colors.chipTaught, borderColor: colors.good },
  chipText: { color: colors.textDim, fontSize: 13, fontWeight: '600' },
  chipTextActive: { color: colors.volt },
  chipTextTaught: { color: colors.good },
  track: {
    height: 4,
    borderRadius: 2,
    backgroundColor: colors.surface3,
    overflow: 'hidden',
  },
  fill: { height: '100%', borderRadius: 2 },
  sliderHit: { height: 28, justifyContent: 'center' },
  sliderTrack: {
    height: 6,
    borderRadius: 3,
    backgroundColor: colors.surface3,
    overflow: 'hidden',
  },
  sliderFill: { height: '100%', borderRadius: 3 },
  sliderThumb: {
    position: 'absolute',
    width: 18,
    height: 18,
    marginLeft: -9,
    borderRadius: 9,
    backgroundColor: colors.text,
    borderWidth: 2,
  },
  statusRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  dot: { width: 7, height: 7, borderRadius: 4 },
  statusText: { color: colors.muted, fontSize: 12, fontWeight: '600' },
});
