import React, { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';

import { formatTimelineTimeUs } from '../memeTimelineCore';
import {
  clampMotionHandleUs,
  copyKeyframeForward,
  deleteKeyframeAt,
  evaluateLayerMotionAt,
  keyframeIndexAt,
  motionActiveRange,
  motionHandleActions,
  motionLayerFor,
  motionRefusalMessage,
  nextKeyframeUs,
  previousKeyframeUs,
  setKeyframeEasing,
  setKeyframeHere,
  type MotionCommand,
  type MotionHandle,
} from '../memeVideoMotionCore';
import type { KeyframedLayer, MemeEditProject, MemeEditProjectAction } from '../memeEditProjectCore';
import { tap, warn } from '../haptics';
import { colors, radius, space, type } from '../theme';
import { PressableScale, Slider } from './ui';

export interface MemeVideoMotionToolProps {
  project: MemeEditProject;
  selectedLayerId: string | null;
  /** Source time the preview is parked on. Commands read and write at this instant. */
  playheadUs: number;
  disabled?: boolean;
  /** Commits one history transaction. An empty list is a refusal and commits nothing. */
  onCommitActions: (actions: MemeEditProjectAction[]) => void;
  onSeek: (timeUs: number) => void;
  onSelectLayer: (id: string | null) => void;
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

function Command({
  label,
  hint,
  disabled,
  selected,
  onPress,
}: {
  label: string;
  hint: string;
  disabled?: boolean;
  selected?: boolean;
  onPress: () => void;
}) {
  return (
    <PressableScale
      scaleTo={0.95}
      disabled={disabled}
      onPress={onPress}
      style={[styles.command, selected && styles.commandOn, disabled && styles.disabledBlock]}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: !!disabled, selected: !!selected }}
    >
      <Text style={[styles.commandText, selected && styles.commandTextOn]}>{label}</Text>
    </PressableScale>
  );
}

function layerLabel(layer: KeyframedLayer): string {
  if (layer.kind === 'text') return layer.text.trim().slice(0, 18) || 'Text layer';
  if (layer.kind === 'subject') return 'Subject';
  return layer.assetKind === 'video' ? 'Video overlay' : 'Image overlay';
}

export const MemeVideoMotionTool = React.memo(function MemeVideoMotionTool({
  project,
  selectedLayerId,
  playheadUs,
  disabled,
  onCommitActions,
  onSeek,
  onSelectLayer,
}: MemeVideoMotionToolProps) {
  const durationUs = project.source.durationUs ?? 0;
  const layer = motionLayerFor(project, selectedLayerId);
  const [refusal, setRefusal] = useState('');
  const [draftHandle, setDraftHandle] = useState<{ handle: MotionHandle; timeUs: number } | null>(null);
  // Any committed change — including an undo — retires a drag draft, and so does
  // switching layers. A draft that outlived its gesture would label the handle
  // with a time the project does not carry.
  useEffect(() => {
    setDraftHandle(null);
    setRefusal('');
  }, [layer?.active?.endUs, layer?.active?.startUs, layer?.id]);

  const runCommand = useCallback(
    (command: MotionCommand) => {
      if (command.refusal !== 'accepted') {
        warn();
        setRefusal(motionRefusalMessage(command.refusal));
        return;
      }
      setRefusal('');
      tap();
      onCommitActions(command.actions);
    },
    [onCommitActions]
  );
  const dragHandle = useCallback(
    (handle: MotionHandle, sliderValue: number) => {
      if (!layer) return;
      setDraftHandle({ handle, timeUs: clampMotionHandleUs(layer, handle, sliderValue * durationUs, durationUs) });
    },
    [durationUs, layer]
  );
  const commitHandle = useCallback(
    (handle: MotionHandle, sliderValue: number) => {
      setDraftHandle(null);
      if (!layer) return;
      const actions = motionHandleActions(project, layer.id, handle, sliderValue * durationUs);
      if (actions.length === 0) return;
      setRefusal('');
      tap();
      onCommitActions(actions);
    },
    [durationUs, layer, onCommitActions, project]
  );
  const seekTo = useCallback(
    (timeUs: number | null) => {
      if (timeUs === null) {
        warn();
        setRefusal('No keyframe that way.');
        return;
      }
      setRefusal('');
      tap();
      onSeek(timeUs);
    },
    [onSeek]
  );

  if (project.video === null || durationUs <= 0) {
    return (
      <View style={styles.empty}>
        <Text style={styles.emptyTitle}>Motion needs a video</Text>
        <Text style={styles.emptyCopy}>An image has no timeline, so its layers cannot be keyframed.</Text>
      </View>
    );
  }

  const movable = project.layers.flatMap((candidate) =>
    candidate.kind === 'cover' ? [] : [candidate]
  );
  const range = layer ? motionActiveRange(layer, durationUs) : null;
  const atKeyframeIndex = layer ? keyframeIndexAt(layer.keyframes, playheadUs) : -1;
  const previousUs = layer ? previousKeyframeUs(layer.keyframes, playheadUs) : null;
  const nextUs = layer ? nextKeyframeUs(layer.keyframes, playheadUs) : null;
  const evaluated = layer ? evaluateLayerMotionAt(layer, playheadUs) : null;
  const atKeyframe = layer && atKeyframeIndex >= 0 ? layer.keyframes[atKeyframeIndex] : null;
  const startUs = draftHandle?.handle === 'start' ? draftHandle.timeUs : range?.startUs ?? 0;
  const endUs = draftHandle?.handle === 'end' ? draftHandle.timeUs : range?.endUs ?? durationUs;
  const blocked = !!disabled || !layer;

  return (
    <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.root}>
      <Section title="Moving layer">
        <View style={styles.wrap} accessibilityRole="radiogroup" accessibilityLabel="Layer to keyframe">
          {movable.length === 0 ? (
            <Text style={styles.note}>Add a text, subject, or overlay layer to keyframe it.</Text>
          ) : (
            movable.map((candidate) => (
              <Command
                key={candidate.id}
                label={layerLabel(candidate)}
                hint="Keyframe this layer"
                selected={candidate.id === layer?.id}
                disabled={!!disabled}
                onPress={() => onSelectLayer(candidate.id)}
              />
            ))
          )}
        </View>
      </Section>

      <Section title="On screen from">
        <View style={[styles.sliderRow, blocked && styles.disabledBlock]}>
          <View style={styles.sliderLabels}>
            <Text style={styles.sliderLabel}>Start</Text>
            <Text style={styles.sliderValue}>{formatTimelineTimeUs(startUs)}</Text>
          </View>
          <Slider
            value={durationUs > 0 ? startUs / durationUs : 0}
            onChange={blocked ? () => {} : (value) => dragHandle('start', value)}
            onComplete={blocked ? undefined : (value) => commitHandle('start', value)}
            accessibilityLabel="Layer start"
            accessibilityDisabled={blocked}
          />
        </View>
        <View style={[styles.sliderRow, blocked && styles.disabledBlock]}>
          <View style={styles.sliderLabels}>
            <Text style={styles.sliderLabel}>End</Text>
            <Text style={styles.sliderValue}>{formatTimelineTimeUs(endUs)}</Text>
          </View>
          <Slider
            value={durationUs > 0 ? endUs / durationUs : 0}
            onChange={blocked ? () => {} : (value) => dragHandle('end', value)}
            onComplete={blocked ? undefined : (value) => commitHandle('end', value)}
            accessibilityLabel="Layer end"
            accessibilityDisabled={blocked}
          />
        </View>
        <Text style={styles.note}>
          {'Shortening the range moves any keyframe outside it onto the new edge.'}
        </Text>
      </Section>

      <Section title="Keyframes">
        <Text style={styles.readout} accessibilityRole="summary">
          {layer
            ? `${layer.keyframes.length} keyframe${layer.keyframes.length === 1 ? '' : 's'} \u00b7 ${
                atKeyframe ? 'on a keyframe' : evaluated ? 'between keyframes' : 'off screen here'
              } at ${formatTimelineTimeUs(playheadUs)}`
            : 'Select a layer to see its keyframes.'}
        </Text>
        {evaluated && (
          <Text style={styles.readout}>
            {`x ${evaluated.center.x.toFixed(3)} \u00b7 y ${evaluated.center.y.toFixed(3)} \u00b7 ${evaluated.scale.toFixed(2)}\u00d7 \u00b7 ${evaluated.rotationDegrees.toFixed(1)}\u00b0 \u00b7 ${Math.round(evaluated.opacity * 100)}%`}
          </Text>
        )}
        <View style={styles.wrap}>
          <Command
            label={"\u25c0 Previous"}
            hint="Move the playhead to the previous keyframe"
            disabled={blocked}
            onPress={() => seekTo(previousUs)}
          />
          <Command
            label={"Next \u25b6"}
            hint="Move the playhead to the next keyframe"
            disabled={blocked}
            onPress={() => seekTo(nextUs)}
          />
        </View>
        <View style={styles.wrap}>
          <Command
            label="Set keyframe here"
            hint="Pin the value currently on screen at the playhead"
            disabled={blocked}
            onPress={() => runCommand(setKeyframeHere(project, selectedLayerId, playheadUs))}
          />
          <Command
            label="Delete keyframe"
            hint="Remove the keyframe under the playhead"
            disabled={blocked}
            onPress={() => runCommand(deleteKeyframeAt(project, selectedLayerId, playheadUs))}
          />
          <Command
            label="Copy forward"
            hint="Hold this value until the next keyframe, or until the layer leaves the screen"
            disabled={blocked}
            onPress={() =>
              runCommand(
                copyKeyframeForward(
                  project,
                  selectedLayerId,
                  playheadUs,
                  nextUs ?? range?.endUs ?? playheadUs
                )
              )
            }
          />
        </View>
        <View style={styles.wrap} accessibilityRole="radiogroup" accessibilityLabel="Keyframe easing">
          <Command
            label="Ease linear"
            hint="Interpolate from this keyframe to the next one"
            selected={atKeyframe?.easing === 'linear'}
            disabled={blocked || !atKeyframe}
            onPress={() => runCommand(setKeyframeEasing(project, selectedLayerId, playheadUs, 'linear'))}
          />
          <Command
            label="Hold"
            hint="Freeze this value until the next keyframe"
            selected={atKeyframe?.easing === 'hold'}
            disabled={blocked || !atKeyframe}
            onPress={() => runCommand(setKeyframeEasing(project, selectedLayerId, playheadUs, 'hold'))}
          />
        </View>
        {!!refusal && (
          <Text style={styles.refusal} accessibilityRole="alert">
            {refusal}
          </Text>
        )}
      </Section>
    </ScrollView>
  );
});

const styles = StyleSheet.create({
  root: { padding: space.md, gap: space.md },
  section: { gap: space.sm },
  sectionTitle: { ...type.micro, color: colors.textDim },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  command: {
    minHeight: 44,
    minWidth: 44,
    paddingHorizontal: space.md,
    justifyContent: 'center',
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  commandOn: { borderColor: colors.volt, borderWidth: 2, backgroundColor: colors.voltDim },
  commandText: { ...type.caption, color: colors.textDim, fontWeight: '800' },
  commandTextOn: { color: colors.volt },
  sliderRow: { minHeight: 64, gap: space.sm, justifyContent: 'center' },
  sliderLabels: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  sliderLabel: { ...type.label, color: colors.text },
  sliderValue: { ...type.caption, color: colors.muted, fontVariant: ['tabular-nums'] },
  readout: { ...type.caption, color: colors.muted, fontVariant: ['tabular-nums'] },
  note: { ...type.caption, color: colors.accent, lineHeight: 17 },
  refusal: { ...type.caption, color: colors.danger },
  disabledBlock: { opacity: 0.5 },
  empty: { padding: space.md, gap: space.xs },
  emptyTitle: { ...type.label, color: colors.text },
  emptyCopy: { ...type.caption, color: colors.muted },
});
