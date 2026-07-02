// Global, non-blocking toast. showToast() can be called from anywhere (no
// context needed); the single <ToastHost/> in App renders the queue. Replaces
// Alert for transient feedback so success/info never interrupts the flow.
import React, { useEffect, useRef, useState } from 'react';
import { Animated, StyleSheet, Text, View } from 'react-native';

import { colors, radius, shadow } from '../theme';
import { useConst } from '../reactUtils';

export type ToastTone = 'info' | 'success' | 'error';

interface ToastMsg {
  id: number;
  text: string;
  tone: ToastTone;
}

type Listener = (t: ToastMsg) => void;
let listener: Listener | null = null;
let nextId = 1;

export function showToast(text: string, tone: ToastTone = 'info'): void {
  listener?.({ id: nextId++, text, tone });
}

const TONE_COLOR: Record<ToastTone, string> = {
  info: colors.accent,
  success: colors.good,
  error: colors.danger,
};
const TONE_ICON: Record<ToastTone, string> = { info: '', success: '✓ ', error: '⚠ ' };

export function ToastHost({ bottomOffset = 96 }: { bottomOffset?: number }) {
  const [msg, setMsg] = useState<ToastMsg | null>(null);
  const anim = useConst(() => new Animated.Value(0));
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    listener = (t) => {
      if (hideTimer.current) clearTimeout(hideTimer.current);
      setMsg(t);
      Animated.spring(anim, { toValue: 1, useNativeDriver: true, speed: 24, bounciness: 6 }).start();
      hideTimer.current = setTimeout(() => {
        Animated.timing(anim, { toValue: 0, duration: 180, useNativeDriver: true }).start(
          ({ finished }) => finished && setMsg(null)
        );
      }, 2200);
    };
    return () => {
      listener = null;
      if (hideTimer.current) clearTimeout(hideTimer.current);
    };
  }, [anim]);

  if (!msg) return null;
  const tint = TONE_COLOR[msg.tone];
  return (
    <Animated.View
      pointerEvents="none"
      style={[
        styles.wrap,
        { bottom: bottomOffset },
        {
          opacity: anim,
          transform: [
            { translateY: anim.interpolate({ inputRange: [0, 1], outputRange: [16, 0] }) },
            { scale: anim.interpolate({ inputRange: [0, 1], outputRange: [0.95, 1] }) },
          ],
        },
      ]}
    >
      <View style={styles.toast}>
        <View style={[styles.bar, { backgroundColor: tint }]} />
        <Text style={styles.text} numberOfLines={2}>
          {TONE_ICON[msg.tone]}
          {msg.text}
        </Text>
      </View>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: 16, right: 16, alignItems: 'center' },
  toast: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surface2,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.borderLight,
    overflow: 'hidden',
    maxWidth: 420,
    ...shadow.float,
  },
  bar: { width: 3, alignSelf: 'stretch' },
  text: {
    color: colors.text,
    fontSize: 13,
    fontWeight: '600',
    paddingVertical: 12,
    paddingHorizontal: 14,
    flexShrink: 1,
  },
});
