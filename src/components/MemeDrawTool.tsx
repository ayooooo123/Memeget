import React from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';

import {
  DRAW_COLORS,
  DRAW_SHAPE_LABELS,
  DRAW_SHAPE_ORDER,
  DRAW_STROKE_PRESETS,
  type DrawSettings,
} from '../memeDrawToolCore';
import type { DrawShape } from '../memeEditProjectCore';
import { tap } from '../haptics';
import { colors, radius, space, type } from '../theme';
import { PressableScale, Slider } from './ui';

export interface MemeDrawToolProps {
  settings: DrawSettings;
  onChangeSettings: (settings: DrawSettings) => void;
  /** How many marks the active drawing holds, for the footer + Clear state. */
  markCount: number;
  /** Whether the layer is at its element ceiling; drives the note only. */
  full?: boolean;
  onClear: () => void;
  sourceKind: 'image' | 'video';
  disabled?: boolean;
  onRemoveLast: () => void;
  opacity: number;
  onChangeOpacity: (opacity: number) => void;
}

const SHAPE_GLYPH: Readonly<Record<DrawShape, string>> = {
  free: '\u270E',
  line: '\u2571',
  arrow: '\u2197',
  rectangle: '\u25AD',
  ellipse: '\u25EF',
};

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

export const MemeDrawTool = React.memo(function MemeDrawTool({
  settings,
  onChangeSettings,
  markCount,
  full,
  onClear,
  sourceKind,
  onRemoveLast,
  opacity,
  onChangeOpacity,
  disabled,
}: MemeDrawToolProps) {
  const change = (patch: Partial<DrawSettings>) => {
    if (disabled) return;
    tap();
    onChangeSettings({ ...settings, ...patch });
  };
  const fillable = settings.shape === 'rectangle' || settings.shape === 'ellipse';

  return (
    <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.root}>
      <Text style={styles.lead}>
        {`Draw directly on the ${sourceKind === 'video' ? 'video' : 'image'}. Every mark is burned into the export.`}
      </Text>

      <Section title="Tool">
        <View style={styles.grid} accessibilityRole="radiogroup" accessibilityLabel="Drawing tool">
          {DRAW_SHAPE_ORDER.map((shape) => {
            const selected = shape === settings.shape;
            return (
              <PressableScale
                key={shape}
                scaleTo={0.93}
                disabled={disabled}
                onPress={() => change({ shape })}
                style={[styles.tool, selected && styles.toolSelected, disabled && styles.disabledBlock]}
                accessibilityRole="radio"
                accessibilityLabel={DRAW_SHAPE_LABELS[shape]}
                accessibilityState={{ selected, disabled: !!disabled }}
              >
                <Text style={[styles.toolGlyph, selected && styles.toolGlyphSelected]}>{SHAPE_GLYPH[shape]}</Text>
                <Text style={[styles.toolLabel, selected && styles.toolLabelSelected]}>{DRAW_SHAPE_LABELS[shape]}</Text>
              </PressableScale>
            );
          })}
        </View>
      </Section>

      <Section title="Colour">
        <View style={styles.swatches} accessibilityRole="radiogroup" accessibilityLabel="Colour">
          {DRAW_COLORS.map((color) => {
            const selected = color.toLowerCase() === settings.color.toLowerCase();
            return (
              <PressableScale
                key={color}
                scaleTo={0.9}
                disabled={disabled}
                onPress={() => change({ color })}
                style={[styles.swatch, { backgroundColor: color }, selected && styles.swatchSelected, disabled && styles.disabledBlock]}
                accessibilityRole="radio"
                accessibilityLabel={`Colour ${color}`}
                accessibilityState={{ selected, disabled: !!disabled }}
              >
                {selected ? <Text style={[styles.swatchCheck, isLight(color) ? styles.swatchCheckDark : null]}>{'\u2713'}</Text> : null}
              </PressableScale>
            );
          })}
        </View>
      </Section>

      <Section title="Thickness">
        <View style={styles.buttonWrap} accessibilityRole="radiogroup" accessibilityLabel="Line thickness">
          {DRAW_STROKE_PRESETS.map((preset) => {
            const selected = Math.abs(preset.scale - settings.strokeScale) < 1e-6;
            return (
              <PressableScale
                key={preset.id}
                scaleTo={0.93}
                disabled={disabled}
                onPress={() => change({ strokeScale: preset.scale })}
                style={[styles.thickness, selected && styles.thicknessSelected, disabled && styles.disabledBlock]}
                accessibilityRole="radio"
                accessibilityLabel={`${preset.label} thickness`}
                accessibilityState={{ selected, disabled: !!disabled }}
              >
                <View style={[styles.thicknessBar, { height: Math.max(2, preset.scale * 120) }, selected && styles.thicknessBarSelected]} />
                <Text style={[styles.thicknessLabel, selected && styles.thicknessLabelSelected]}>{preset.label}</Text>
              </PressableScale>
            );
          })}
        </View>
      </Section>

      {fillable && (
        <Section title="Fill">
          <PressableScale
            scaleTo={0.95}
            disabled={disabled}
            onPress={() => change({ filled: !settings.filled })}
            style={[styles.toggle, settings.filled && styles.toggleOn, disabled && styles.disabledBlock]}
            accessibilityRole="switch"
            accessibilityLabel="Fill the shape"
            accessibilityState={{ checked: settings.filled, disabled: !!disabled }}
          >
            <View style={[styles.toggleMark, settings.filled && styles.toggleMarkOn]}>
              <Text style={[styles.toggleMarkText, settings.filled && styles.toggleMarkTextOn]}>{settings.filled ? '\u25A0' : '\u25A1'}</Text>
            </View>
            <View style={styles.toggleCopy}>
              <Text style={styles.toggleTitle}>{settings.filled ? 'Filled' : 'Outline'}</Text>
              <Text style={styles.toggleMeta}>{settings.filled ? 'Solid shape in the chosen colour' : 'Just the shape\u2019s edge'}</Text>
            </View>
          </PressableScale>
        </Section>
      )}

      {markCount > 0 && (
        <Section title="Opacity">
          <View style={styles.opacityRow}>
            <Text style={styles.opacityLabel}>Opacity</Text>
            <Text style={styles.opacityValue}>{`${Math.round(opacity * 100)}%`}</Text>
          </View>
          <Slider value={opacity} onChange={disabled ? () => {} : onChangeOpacity} accessibilityLabel="Drawing opacity" accessibilityDisabled={disabled} />
        </Section>
      )}

      <Section title="Drawing">
        <View style={styles.footerRow}>
          <Text style={styles.footerMeta} accessibilityLabel={`${markCount} mark${markCount === 1 ? '' : 's'} on this drawing`}>
            {markCount === 0 ? 'No marks yet' : `${markCount} mark${markCount === 1 ? '' : 's'}`}
          </Text>
          <View style={styles.footerActions}>
            <PressableScale
              scaleTo={0.95}
              disabled={disabled || markCount === 0}
              onPress={onRemoveLast}
              style={[styles.clear, (disabled || markCount === 0) && styles.disabledBlock]}
              accessibilityRole="button"
              accessibilityLabel="Undo last mark"
              accessibilityHint="Remove the most recent mark on this drawing"
            >
              <Text style={styles.clearText}>Undo</Text>
            </PressableScale>
            <PressableScale
              scaleTo={0.95}
              disabled={disabled || markCount === 0}
              onPress={onClear}
              style={[styles.clear, (disabled || markCount === 0) && styles.disabledBlock]}
              accessibilityRole="button"
              accessibilityLabel="Clear drawing"
              accessibilityHint="Remove every mark on this drawing layer"
            >
              <Text style={styles.clearText}>Clear</Text>
            </PressableScale>
          </View>
        </View>
        {full && <Text style={styles.note}>This drawing is full. Add a new draw layer for more marks.</Text>}
      </Section>
    </ScrollView>
  );
});

// A rough luma test so a check mark stays visible on light swatches.
function isLight(hex: string): boolean {
  const value = hex.replace('#', '');
  if (value.length < 6) return true;
  const r = parseInt(value.slice(0, 2), 16);
  const g = parseInt(value.slice(2, 4), 16);
  const b = parseInt(value.slice(4, 6), 16);
  return 0.299 * r + 0.587 * g + 0.114 * b > 160;
}

const styles = StyleSheet.create({
  root: { gap: space.md, padding: space.md },
  lead: { ...type.body, color: colors.muted },
  section: { gap: space.sm },
  sectionTitle: { ...type.micro, color: colors.textDim },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  tool: {
    width: 64,
    paddingVertical: space.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
    alignItems: 'center',
    gap: 2,
  },
  toolSelected: { borderColor: colors.volt, backgroundColor: colors.voltDim },
  toolGlyph: { ...type.title, fontSize: 20, color: colors.textDim },
  toolGlyphSelected: { color: colors.volt },
  toolLabel: { ...type.micro, color: colors.textDim },
  toolLabelSelected: { color: colors.text },
  swatches: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  swatch: {
    width: 40,
    height: 40,
    borderRadius: radius.pill,
    borderWidth: 2,
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  swatchSelected: { borderColor: colors.volt },
  swatchCheck: { ...type.body, color: '#ffffff', fontWeight: '700' },
  swatchCheckDark: { color: '#000000' },
  buttonWrap: { flexDirection: 'row', gap: space.sm },
  thickness: {
    flex: 1,
    minHeight: 64,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.xs,
    paddingVertical: space.sm,
  },
  thicknessSelected: { borderColor: colors.volt, backgroundColor: colors.voltDim },
  thicknessBar: { width: 36, backgroundColor: colors.textDim, borderRadius: radius.pill },
  thicknessBarSelected: { backgroundColor: colors.volt },
  thicknessLabel: { ...type.micro, color: colors.textDim },
  thicknessLabelSelected: { color: colors.text },
  toggle: {
    minHeight: 56,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    paddingHorizontal: space.md,
    paddingVertical: space.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  toggleOn: { borderColor: colors.volt, backgroundColor: colors.voltDim },
  toggleMark: {
    width: 32,
    height: 32,
    borderRadius: radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surface,
  },
  toggleMarkOn: { backgroundColor: colors.volt },
  toggleMarkText: { ...type.body, color: colors.textDim },
  toggleMarkTextOn: { color: colors.onVolt },
  toggleCopy: { flex: 1, gap: 2 },
  toggleTitle: { ...type.body, color: colors.text },
  toggleMeta: { ...type.micro, color: colors.muted },
  footerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  footerMeta: { ...type.body, color: colors.muted },
  footerActions: { flexDirection: 'row', gap: space.sm },
  opacityRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  opacityLabel: { ...type.body, color: colors.text },
  opacityValue: { ...type.caption, color: colors.muted, fontVariant: ['tabular-nums'] },
  clear: {
    paddingHorizontal: space.md,
    paddingVertical: space.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  clearText: { ...type.body, color: colors.text },
  note: { ...type.micro, color: colors.muted },
  disabledBlock: { opacity: 0.4 },
});
