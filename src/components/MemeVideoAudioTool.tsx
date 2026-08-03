import React, { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';

import { outputDurationUs, type MemeEditProject } from '../memeEditProjectCore';
import {
  VIDEO_SPEEDS,
  formatOutputDuration,
  formatVolumePercent,
  nextVideoSpeed,
  sliderValueForVolume,
  speedPreviewSupport,
  videoAudioPreview,
  volumeForSliderValue,
  type VideoAudioChange,
} from '../memeVideoAudioCore';
import { tap } from '../haptics';
import { colors, radius, space, type } from '../theme';
import { PressableScale, Slider } from './ui';

export interface MemeVideoAudioToolProps {
  project: MemeEditProject;
  disabled?: boolean;
  /** Commits one history transaction. */
  onChange: (change: VideoAudioChange) => void;
  /**
   * Live values for the preview player while a drag is in flight. Null clears
   * the draft and hands the player back to the committed project state.
   */
  onPreviewAudio: (audio: { muted: boolean; volume: number } | null) => void;
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

export const MemeVideoAudioTool = React.memo(function MemeVideoAudioTool({
  project,
  disabled,
  onChange,
  onPreviewAudio,
}: MemeVideoAudioToolProps) {
  const video = project.video;
  const committedVolume = video?.audio.volume ?? 1;
  const muted = video?.audio.muted ?? false;
  const speed = video?.speed ?? 1;
  const [draftVolume, setDraftVolume] = useState(committedVolume);
  // Any committed change — including an undo that rewinds one — retires the
  // drag draft, and so does leaving the tool. A draft that outlived its
  // gesture would hold the player on a value the project no longer carries.
  useEffect(() => {
    setDraftVolume(committedVolume);
    onPreviewAudio(null);
  }, [committedVolume, muted, onPreviewAudio]);
  useEffect(() => () => onPreviewAudio(null), [onPreviewAudio]);

  // The drag drives the player directly, so what you hear is what the slider
  // says; only the released value becomes an undo entry.
  const dragVolume = useCallback(
    (sliderValue: number) => {
      const volume = volumeForSliderValue(sliderValue);
      setDraftVolume(volume);
      onPreviewAudio({ muted, volume });
    },
    [muted, onPreviewAudio]
  );
  const commitVolume = useCallback(
    (sliderValue: number) => {
      const volume = volumeForSliderValue(sliderValue);
      setDraftVolume(volume);
      onPreviewAudio(null);
      onChange({ volume });
    },
    [onChange, onPreviewAudio]
  );
  const toggleMute = useCallback(() => {
    tap();
    onPreviewAudio(null);
    onChange({ muted: !muted });
  }, [muted, onChange, onPreviewAudio]);
  const selectSpeed = useCallback(
    (next: number) => {
      tap();
      onChange({ speed: next });
    },
    [onChange]
  );

  if (!video) {
    return (
      <View style={styles.root}>
        <Text style={styles.empty}>Audio and speed apply to video sources only.</Text>
      </View>
    );
  }

  const preview = videoAudioPreview({ muted, volume: draftVolume });
  const support = speedPreviewSupport(speed);
  const retainedUs = video.retainedRanges.reduce((total, range) => total + (range.endUs - range.startUs), 0);
  const outputUs = outputDurationUs(video.retainedRanges, speed);
  const volumeLabel = formatVolumePercent(draftVolume);

  return (
    <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.root}>
      <Section title="Audio">
        <PressableScale
          scaleTo={0.95}
          disabled={disabled}
          onPress={toggleMute}
          style={[styles.toggle, muted && styles.toggleOn, disabled && styles.disabledBlock]}
          accessibilityRole="switch"
          accessibilityLabel="Mute audio"
          accessibilityHint={muted ? 'Turn the source audio back on' : 'Silence the source audio in the preview and the export'}
          accessibilityState={{ checked: muted, disabled: !!disabled }}
        >
          {/* The glyph, not the fill colour, is what says which way this sits. */}
          <View style={[styles.toggleMark, muted && styles.toggleMarkOn]}>
            <Text style={[styles.toggleMarkText, muted && styles.toggleMarkTextOn]}>{muted ? '\u2715' : '\u266A'}</Text>
          </View>
          <View style={styles.toggleCopy}>
            <Text style={styles.toggleTitle}>{muted ? 'Audio muted' : 'Audio kept'}</Text>
            <Text style={styles.toggleMeta}>
              {muted ? 'Silent in the preview and the export' : 'Source audio plays and exports'}
            </Text>
          </View>
        </PressableScale>

        <View
          style={[styles.sliderRow, (disabled || muted) && styles.disabledBlock]}
          accessibilityLabel={`Volume, ${volumeLabel}`}
        >
          <View style={styles.sliderLabels}>
            <Text style={styles.sliderLabel}>Volume</Text>
            <Text style={styles.sliderValue}>{volumeLabel}</Text>
          </View>
          <Slider
            value={sliderValueForVolume(draftVolume)}
            onChange={disabled ? () => {} : dragVolume}
            onComplete={disabled ? undefined : commitVolume}
            accessibilityLabel="Volume"
            accessibilityDisabled={disabled}
          />
        </View>
        {preview.gainBeyondPreview && (
          <Text style={styles.note}>
            {`Above 100% is applied on export only \u2014 the preview plays at 100%.`}
          </Text>
        )}
        {muted && <Text style={styles.note}>Volume is kept for when you unmute.</Text>}
      </Section>

      <Section title="Speed">
        <View
          style={styles.buttonWrap}
          accessibilityRole="radiogroup"
          accessibilityLabel="Playback speed"
          accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
          onAccessibilityAction={(event) => {
            if (disabled) return;
            selectSpeed(nextVideoSpeed(speed, event.nativeEvent.actionName === 'decrement' ? -1 : 1));
          }}
        >
          {VIDEO_SPEEDS.map((option) => {
            const selected = option === speed;
            return (
              <PressableScale
                key={option}
                scaleTo={0.93}
                disabled={disabled}
                onPress={() => selectSpeed(option)}
                style={[styles.speed, selected && styles.speedSelected, disabled && styles.disabledBlock]}
                accessibilityRole="radio"
                accessibilityLabel={`${option} times speed`}
                accessibilityHint={
                  speedPreviewSupport(option).mode === 'preview'
                    ? 'The preview plays at this speed'
                    : 'Applied on export only'
                }
                accessibilityState={{ selected, disabled: !!disabled }}
              >
                {/* A check mark, so the selection survives without colour. */}
                <Text style={[styles.speedCheck, selected && styles.speedCheckSelected]}>
                  {selected ? '\u2713' : ' '}
                </Text>
                <Text style={[styles.speedText, selected && styles.speedTextSelected]}>{`${option}\u00d7`}</Text>
              </PressableScale>
            );
          })}
        </View>
        <Text style={styles.note}>
          {support.mode === 'preview'
            ? `The preview plays at ${speed}\u00d7, the same rate the export renders.`
            : support.reason}
        </Text>
      </Section>

      <Section title="Output">
        <View style={styles.outputRow}>
          <Text style={styles.outputLabel}>Duration</Text>
          <Text style={styles.outputValue} accessibilityLabel={`Output duration ${formatOutputDuration(outputUs)}`}>
            {formatOutputDuration(outputUs)}
          </Text>
        </View>
        <Text style={styles.outputMeta}>
          {`${formatOutputDuration(retainedUs)} kept across ${video.retainedRanges.length} range${
            video.retainedRanges.length === 1 ? '' : 's'
          } at ${speed}\u00d7`}
        </Text>
      </Section>
    </ScrollView>
  );
});

const styles = StyleSheet.create({
  root: { gap: space.md, padding: space.md },
  empty: { ...type.body, color: colors.muted },
  section: { gap: space.sm },
  sectionTitle: { ...type.micro, color: colors.textDim },
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
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: colors.borderLight,
    backgroundColor: colors.surface3,
  },
  toggleMarkOn: { borderColor: colors.volt, backgroundColor: colors.volt },
  toggleMarkText: { color: colors.textDim, fontSize: 15, fontWeight: '800' },
  toggleMarkTextOn: { color: colors.onVolt },
  toggleCopy: { flex: 1, minWidth: 0, gap: 2 },
  toggleTitle: { ...type.label, color: colors.text },
  toggleMeta: { ...type.caption, color: colors.muted },
  sliderRow: { minHeight: 64, gap: space.sm, justifyContent: 'center' },
  sliderLabels: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  sliderLabel: { ...type.label, color: colors.text },
  sliderValue: { ...type.caption, color: colors.muted, fontVariant: ['tabular-nums'] },
  note: { ...type.caption, color: colors.accent, lineHeight: 17 },
  buttonWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  speed: {
    minHeight: 44,
    minWidth: 72,
    flexGrow: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.xs,
    paddingHorizontal: space.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  speedSelected: { borderColor: colors.volt, borderWidth: 2, backgroundColor: colors.voltDim },
  speedCheck: { ...type.caption, color: 'transparent', fontWeight: '800', width: 12 },
  speedCheckSelected: { color: colors.volt },
  speedText: { ...type.caption, color: colors.textDim, fontWeight: '800', fontVariant: ['tabular-nums'] },
  speedTextSelected: { color: colors.volt },
  outputRow: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', gap: space.sm },
  outputLabel: { ...type.label, color: colors.text },
  outputValue: { ...type.display, fontSize: 24, color: colors.volt, fontVariant: ['tabular-nums'] },
  outputMeta: { ...type.caption, color: colors.muted, fontVariant: ['tabular-nums'] },
  disabledBlock: { opacity: 0.5 },
});
