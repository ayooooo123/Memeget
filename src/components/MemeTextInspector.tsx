import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';

import { PROJECT_LIMITS, type MemeEditProject, type MemeEditProjectAction, type TextLayer } from '../memeEditProjectCore';
import { nextDuplicateLayerId } from '../memeEditCanvasCore';
import {
  MEME_TEXT_BOUNDS,
  MEME_TEXT_COLOR_SWATCHES,
  MEME_TEXT_MAX_LENGTH,
  MEME_TEXT_PRESET_IDS,
  applyMemeTextPreset,
  clampMemeTextContent,
  createMemeTextLayer,
  getMemeTextPresetDefaults,
  normalizeMemeTextFontSize,
  normalizeMemeTextWrapWidth,
  type MemeTextPresetId,
} from '../memeTextLayoutCore';
import { composePendingTextLayer } from '../memeTextInspectorCore';
import { colors, radius, space, type } from '../theme';
import { PressableScale, Slider } from './ui';

const TEXT_COMMIT_DELAY_MS = 250;

type InspectorAction = Extract<
  MemeEditProjectAction,
  { type: 'add-layer' | 'update-layer' | 'duplicate-layer' | 'remove-layer' | 'move-layer' | 'set-layer-active-range' }
>;

interface MemeTextInspectorProps {
  project: MemeEditProject;
  selectedLayerId: string | null;
  idPrefix: string;
  disabled?: boolean;
  bottomInset?: number;
  onApplyAction: (action: InspectorAction) => void;
  onSelectLayer: (id: string | null) => void;
  onDuplicateLayer: (id: string) => void;
  onDeleteLayer: (id: string) => void;
  onMoveLayer: (id: string, toIndex: number) => void;
  onRegisterPendingTextFlush?: (flush: () => void) => () => void;
  onBeginTextTransaction?: () => void;
  onCommitTextTransaction?: () => void;
}

function selectedTextLayer(project: MemeEditProject, selectedLayerId: string | null): TextLayer | null {
  const layer = selectedLayerId ? project.layers.find((candidate) => candidate.id === selectedLayerId) : null;
  return layer?.kind === 'text' ? layer : null;
}

function sliderValue(value: number, min: number, max: number): number {
  return (value - min) / (max - min);
}

function valueFromSlider(value: number, min: number, max: number): number {
  return min + value * (max - min);
}

function layerTitle(layer: TextLayer | null): string {
  if (!layer) return 'No text layer selected';
  return layer.text.trim() || getMemeTextPresetDefaults(layer.style.preset).preset;
}

function ControlButton({
  label,
  hint,
  selected,
  disabled,
  danger,
  onPress,
}: {
  label: string;
  hint: string;
  selected?: boolean;
  disabled?: boolean;
  danger?: boolean;
  onPress: () => void;
}) {
  return (
    <PressableScale
      scaleTo={0.93}
      disabled={disabled}
      onPress={onPress}
      style={[styles.controlButton, selected && styles.controlButtonSelected, danger && styles.controlButtonDanger]}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ selected: !!selected, disabled: !!disabled }}
    >
      <Text style={[styles.controlButtonText, selected && styles.controlButtonTextSelected, danger && styles.controlButtonTextDanger]} numberOfLines={1}>
        {label}
      </Text>
    </PressableScale>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

function LabeledSlider({
  label,
  valueLabel,
  value,
  disabled,
  onChange,
}: {
  label: string;
  valueLabel: string;
  value: number;
  disabled?: boolean;
  onChange: (value: number) => void;
}) {
  const [draftValue, setDraftValue] = useState(value);
  useEffect(() => setDraftValue(value), [value]);
  const boundedValue = Math.max(0, Math.min(1, draftValue));
  return (
    <View style={[styles.sliderRow, disabled && styles.disabledBlock]} accessibilityLabel={`${label}, ${valueLabel}`} accessibilityState={{ disabled: !!disabled }}>
      <View style={styles.sliderLabels}>
        <Text style={styles.sliderLabel}>{label}</Text>
        <Text style={styles.sliderValue}>{valueLabel}</Text>
      </View>
      <Slider value={boundedValue} onChange={disabled ? () => {} : setDraftValue} onComplete={disabled ? undefined : onChange} accessibilityLabel={label} accessibilityDisabled={disabled} />
    </View>
  );
}

function ColorSwatches({
  label,
  selected,
  disabled,
  onSelect,
}: {
  label: string;
  selected: string;
  disabled?: boolean;
  onSelect: (color: string) => void;
}) {
  return (
    <View style={styles.swatchGroup} accessibilityLabel={label}>
      <Text style={styles.inlineLabel}>{label}</Text>
      <View style={styles.swatches}>
        {MEME_TEXT_COLOR_SWATCHES.map((swatch) => {
          const active = swatch === selected;
          return (
            <PressableScale
              key={swatch}
              scaleTo={0.9}
              disabled={disabled}
              onPress={() => onSelect(swatch)}
              style={[styles.swatch, active && styles.swatchSelected, { backgroundColor: swatch }]}
              accessibilityRole="button"
              accessibilityLabel={`${label} ${swatch}`}
              accessibilityState={{ selected: active, disabled: !!disabled }}
            />
          );
        })}
      </View>
    </View>
  );
}

export const MemeTextInspector = React.memo(function MemeTextInspector({
  project,
  selectedLayerId,
  idPrefix,
  disabled = false,
  bottomInset = 0,
  onApplyAction,
  onSelectLayer,
  onDuplicateLayer,
  onDeleteLayer,
  onMoveLayer,
  onRegisterPendingTextFlush,
  onBeginTextTransaction,
  onCommitTextTransaction,
}: MemeTextInspectorProps) {
  const projectRef = useRef(project);
  const inputRef = useRef<TextInput | null>(null);
  const selectedLayerRef = useRef<TextLayer | null>(selectedTextLayer(project, selectedLayerId));
  const pendingTextRef = useRef<{ layerId: string; text: string } | null>(null);
  const textTimerRef = useRef<number | null>(null);
  const [draftText, setDraftText] = useState(selectedLayerRef.current?.text ?? '');
  const layer = selectedTextLayer(project, selectedLayerId);
  const canAdd = project.layers.length < PROJECT_LIMITS.maxLayers && !disabled;
  const selectedIndex = layer ? project.layers.findIndex((candidate) => candidate.id === layer.id) : -1;
  const durationUs = project.source.kind === 'video' ? project.source.durationUs ?? 0 : 0;
  const activeStartUs = layer?.active?.startUs ?? 0;
  const activeEndUs = layer?.active?.endUs ?? durationUs;
  const activeStartValue = durationUs > 0 ? activeStartUs / durationUs : 0;
  const activeEndValue = durationUs > 0 ? activeEndUs / durationUs : 1;

  projectRef.current = project;
  selectedLayerRef.current = layer;

  const flushText = useCallback(() => {
    const pending = pendingTextRef.current;
    if (!pending) return;
    pendingTextRef.current = null;
    clearTimeout(textTimerRef.current ?? undefined);
    textTimerRef.current = null;
    const current = projectRef.current.layers.find((candidate): candidate is TextLayer => candidate.id === pending.layerId && candidate.kind === 'text');
    if (!current || current.text === pending.text) return;
    onApplyAction({ type: 'update-layer', layer: { ...current, text: pending.text } });
    onCommitTextTransaction?.();
  }, [onApplyAction, onCommitTextTransaction]);

  const layerWithPendingText = useCallback((current: TextLayer): TextLayer => {
    const pending = pendingTextRef.current;
    const next = composePendingTextLayer(current, pending);
    if (next === current) return current;
    pendingTextRef.current = null;
    clearTimeout(textTimerRef.current ?? undefined);
    textTimerRef.current = null;
    return next;
  }, []);

  useEffect(() => {
    flushText();
    const nextText = layer?.text ?? '';
    setDraftText(nextText);
    pendingTextRef.current = null;
  }, [flushText, selectedLayerId]);

  useEffect(() => {
    if (pendingTextRef.current) return;
    const nextText = layer?.text ?? '';
    setDraftText(nextText);
    inputRef.current?.setNativeProps({ text: nextText });
  }, [layer?.id, layer?.text]);

  useEffect(() => () => { flushText(); onCommitTextTransaction?.(); }, [flushText, onCommitTextTransaction]);
  useEffect(() => onRegisterPendingTextFlush?.(() => { flushText(); onCommitTextTransaction?.(); }), [flushText, onCommitTextTransaction, onRegisterPendingTextFlush]);

  const queueText = useCallback((text: string) => {
    if (!layer || disabled) return;
    const boundedText = clampMemeTextContent(text);
    setDraftText(boundedText);
    onBeginTextTransaction?.();
    pendingTextRef.current = { layerId: layer.id, text: boundedText };
    clearTimeout(textTimerRef.current ?? undefined);
    textTimerRef.current = setTimeout(flushText, TEXT_COMMIT_DELAY_MS) as unknown as number;
  }, [disabled, flushText, layer, onBeginTextTransaction]);

  const updateLayer = useCallback((updater: (current: TextLayer) => TextLayer) => {
    const current = selectedLayerRef.current;
    if (!current || disabled) return;
    onApplyAction({ type: 'update-layer', layer: updater(layerWithPendingText(current)) });
  }, [disabled, layerWithPendingText, onApplyAction]);

  const addText = useCallback((preset: MemeTextPresetId = 'impact') => {
    if (!canAdd) return;
    flushText();
    const id = nextDuplicateLayerId(idPrefix, projectRef.current.layers.map((candidate) => candidate.id));
    const next = createMemeTextLayer(id, preset, { text: 'Meme text' });
    onApplyAction({ type: 'add-layer', layer: next });
    onSelectLayer(id);
  }, [canAdd, flushText, idPrefix, onApplyAction, onSelectLayer]);

  const presetSummary = useMemo(() => MEME_TEXT_PRESET_IDS.map((preset) => getMemeTextPresetDefaults(preset)), []);

  return (
    <ScrollView
      keyboardShouldPersistTaps="handled"
      contentContainerStyle={[styles.root, { paddingBottom: bottomInset + space.lg }]}
    >
      <View style={styles.headerRow}>
        <View style={styles.headerCopy}>
          <Text style={styles.title}>Text layers</Text>
          <Text style={styles.meta}>{layerTitle(layer)}</Text>
        </View>
        <ControlButton
          label="Add text"
          hint={canAdd ? 'Add a new editable meme text layer' : 'Project is at the layer limit'}
          disabled={!canAdd}
          onPress={() => addText('impact')}
        />
      </View>

      <Section title="Presets">
        <View style={styles.buttonWrap}>
          {presetSummary.map((preset) => (
            <ControlButton
              key={preset.preset}
              label={preset.preset === 'news' ? 'Breaking news' : preset.preset}
              hint={`Use the ${preset.preset} meme text preset`}
              selected={layer?.style.preset === preset.preset}
              disabled={!layer || disabled}
              onPress={() => updateLayer((current) => applyMemeTextPreset(current, preset.preset))}
            />
          ))}
        </View>
      </Section>
      <Section title="Content">
        <TextInput
          ref={inputRef}
          key={layer?.id ?? 'empty-text'}
          defaultValue={layer?.text ?? ''}
          editable={!!layer && !disabled}
          multiline
          textAlignVertical="top"
          onChangeText={queueText}
          onBlur={flushText}
          maxLength={MEME_TEXT_MAX_LENGTH}
          placeholder="Type a caption"
          placeholderTextColor={colors.faint}
          style={[styles.input, !layer && styles.inputDisabled]}
          accessibilityLabel="Text layer content"
          accessibilityHint="Typing is saved after you pause or leave the field"
        />
        {!!layer && draftText !== layer.text && <Text style={styles.pendingText}>Preview will save after pause</Text>}
      </Section>

      <Section title="Layout">
        <LabeledSlider
          label="Text size"
          valueLabel={`${Math.round((layer?.fontSize ?? MEME_TEXT_BOUNDS.defaultFontSize) * 100)}% height`}
          disabled={!layer || disabled}
          value={sliderValue(layer?.fontSize ?? MEME_TEXT_BOUNDS.defaultFontSize, MEME_TEXT_BOUNDS.minFontSize, MEME_TEXT_BOUNDS.maxFontSize)}
          onChange={(value) => updateLayer((current) => ({ ...current, fontSize: normalizeMemeTextFontSize(valueFromSlider(value, MEME_TEXT_BOUNDS.minFontSize, MEME_TEXT_BOUNDS.maxFontSize)) }))}
        />
        <LabeledSlider
          label="Wrap width"
          valueLabel={`${Math.round((layer?.width ?? MEME_TEXT_BOUNDS.defaultWrapWidth) * 100)}%`}
          disabled={!layer || disabled}
          value={sliderValue(layer?.width ?? MEME_TEXT_BOUNDS.defaultWrapWidth, MEME_TEXT_BOUNDS.minWrapWidth, MEME_TEXT_BOUNDS.maxWrapWidth)}
          onChange={(value) => updateLayer((current) => ({ ...current, width: normalizeMemeTextWrapWidth(valueFromSlider(value, MEME_TEXT_BOUNDS.minWrapWidth, MEME_TEXT_BOUNDS.maxWrapWidth)) }))}
        />
        <View style={styles.buttonWrap}>
          {(['left', 'center', 'right'] as const).map((align) => (
            <ControlButton
              key={align}
              label={align}
              hint={`Align text ${align}`}
              selected={layer?.style.align === align}
              disabled={!layer || disabled}
              onPress={() => updateLayer((current) => ({ ...current, style: { ...current.style, align } }))}
            />
          ))}
        </View>
      </Section>


      {durationUs > 0 && (
        <Section title="Video range">
          <LabeledSlider
            label="Start"
            valueLabel={`${(activeStartUs / 1_000_000).toFixed(1)}s`}
            disabled={!layer || disabled}
            value={activeStartValue}
            onChange={(value) => {
              if (!layer) return;
              flushText();
              const startUs = Math.min(Math.round(value * durationUs), Math.max(0, activeEndUs - 1));
              onApplyAction({ type: 'set-layer-active-range', id: layer.id, active: { startUs, endUs: activeEndUs } });
            }}
          />
          <LabeledSlider
            label="End"
            valueLabel={`${(activeEndUs / 1_000_000).toFixed(1)}s`}
            disabled={!layer || disabled}
            value={activeEndValue}
            onChange={(value) => {
              if (!layer) return;
              flushText();
              const endUs = Math.max(Math.round(value * durationUs), activeStartUs + 1);
              onApplyAction({ type: 'set-layer-active-range', id: layer.id, active: { startUs: activeStartUs, endUs } });
            }}
          />
        </Section>
      )}
      <Section title="Color and backing">
        <ColorSwatches
          label="Fill"
          selected={layer?.style.color ?? colors.text}
          disabled={!layer || disabled}
          onSelect={(color) => updateLayer((current) => ({ ...current, style: { ...current.style, color } }))}
        />
        <ColorSwatches
          label="Outline"
          selected={layer?.style.outlineColor ?? colors.bg}
          disabled={!layer || disabled}
          onSelect={(outlineColor) => updateLayer((current) => ({ ...current, style: { ...current.style, outlineColor } }))}
        />
        <LabeledSlider
          label="Outline amount"
          valueLabel={`${Math.round((layer?.style.outlineScale ?? 0) * 100)}%`}
          disabled={!layer || disabled}
          value={layer?.style.outlineScale ?? 0}
          onChange={(outlineScale) => updateLayer((current) => ({ ...current, style: { ...current.style, outlineScale } }))}
        />
        <View style={styles.buttonWrap}>
          <ControlButton
            label="Backing"
            hint="Toggle the text backing shape"
            selected={!!layer?.style.backgroundColor}
            disabled={!layer || disabled}
            onPress={() => updateLayer((current) => {
              const defaults = getMemeTextPresetDefaults(current.style.preset).style.backgroundColor ?? colors.bg;
              return { ...current, style: { ...current.style, backgroundColor: current.style.backgroundColor ? null : defaults } };
            })}
          />
          <ControlButton
            label="Uppercase"
            hint="Display this layer in uppercase without changing stored text"
            selected={!!layer?.style.uppercase}
            disabled={!layer || disabled}
            onPress={() => updateLayer((current) => ({ ...current, style: { ...current.style, uppercase: !current.style.uppercase } }))}
          />
        </View>
        <ColorSwatches
          label="Backing color"
          selected={layer?.style.backgroundColor ?? colors.bg}
          disabled={!layer || disabled || !layer?.style.backgroundColor}
          onSelect={(backgroundColor) => updateLayer((current) => ({ ...current, style: { ...current.style, backgroundColor } }))}
        />
        <LabeledSlider
          label="Opacity"
          valueLabel={`${Math.round((layer?.style.opacity ?? 1) * 100)}%`}
          disabled={!layer || disabled}
          value={layer?.style.opacity ?? 1}
          onChange={(opacity) => updateLayer((current) => ({ ...current, style: { ...current.style, opacity } }))}
        />
      </Section>
      <Section title="Layer actions">
        <View style={styles.buttonWrap}>
          <ControlButton label="Move up" hint="Move text layer visually forward" disabled={!layer || disabled || selectedIndex >= project.layers.length - 1} onPress={() => { flushText(); if (layer) onMoveLayer(layer.id, selectedIndex + 1); }} />
          <ControlButton label="Move down" hint="Move text layer visually backward" disabled={!layer || disabled || selectedIndex <= 0} onPress={() => { flushText(); if (layer) onMoveLayer(layer.id, selectedIndex - 1); }} />
          <ControlButton label="Duplicate" hint="Duplicate text layer with a new deterministic ID" disabled={!layer || disabled || project.layers.length >= PROJECT_LIMITS.maxLayers} onPress={() => { flushText(); if (layer) onDuplicateLayer(layer.id); }} />
          <ControlButton label="Delete" hint="Delete selected text layer" danger disabled={!layer || disabled} onPress={() => { flushText(); if (layer) onDeleteLayer(layer.id); }} />
        </View>
      </Section>
    </ScrollView>
  );
});

const styles = StyleSheet.create({
  root: { gap: space.md, padding: space.md },
  headerRow: { minHeight: 44, flexDirection: 'row', alignItems: 'center', gap: space.sm },
  headerCopy: { flex: 1, minWidth: 0, gap: 2 },
  title: { ...type.title, color: colors.text },
  meta: { ...type.caption, color: colors.muted },
  section: { gap: space.sm },
  sectionTitle: { ...type.micro, color: colors.textDim },
  buttonWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  controlButton: {
    minHeight: 44,
    minWidth: 72,
    flexGrow: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: space.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  controlButtonSelected: { borderColor: colors.volt, backgroundColor: colors.voltDim },
  controlButtonDanger: { borderColor: colors.danger, backgroundColor: colors.dangerDim },
  controlButtonText: { ...type.caption, color: colors.textDim, fontWeight: '800', textTransform: 'capitalize' },
  controlButtonTextSelected: { color: colors.volt },
  controlButtonTextDanger: { color: colors.danger },
  input: {
    minHeight: 96,
    padding: space.md,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.borderLight,
    backgroundColor: colors.surface2,
    color: colors.text,
    fontSize: 16,
    lineHeight: 21,
  },
  inputDisabled: { color: colors.muted },
  pendingText: { ...type.caption, color: colors.accent },
  sliderRow: { minHeight: 64, gap: space.sm, justifyContent: 'center' },
  sliderLabels: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  sliderLabel: { ...type.label, color: colors.text },
  sliderValue: { ...type.caption, color: colors.muted, fontVariant: ['tabular-nums'] },
  inlineLabel: { ...type.caption, color: colors.textDim },
  swatchGroup: { gap: space.xs },
  swatches: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  swatch: {
    minWidth: 44,
    minHeight: 44,
    borderRadius: radius.md,
    borderWidth: 2,
    borderColor: colors.border,
  },
  swatchSelected: { borderColor: colors.volt },
  disabledBlock: { opacity: 0.5 },
});
