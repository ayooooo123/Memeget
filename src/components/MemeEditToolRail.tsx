import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { memeEditToolsForSource, type MemeEditToolId } from '../memeEditCanvasCore';
import type { MediaEditKind } from '../memeEditProjectCore';

import { colors, radius, space, type } from '../theme';
import { PressableScale } from './ui';

export type MemeEditTool = MemeEditToolId;

interface ToolSpec {
  id: MemeEditTool;
  label: string;
  hint: string;
  mark: string;
}

const TOOLS: readonly ToolSpec[] = [
  { id: 'layers', label: 'Layers', hint: 'Show layer order and layer actions', mark: 'L' },
  { id: 'text', label: 'Text', hint: 'Add and style meme text layers', mark: 'A' },
  { id: 'transform', label: 'Transform', hint: 'Crop, rotate, or flip this image', mark: 'T' },
  { id: 'replace-text', label: 'Replace text', hint: 'Detect text or draw a box, then Cover, Pixelate, or Replace', mark: 'R' },
  { id: 'timeline', label: 'Timeline', hint: 'Scrub, trim, split, and see when each layer is on screen', mark: 'C' },
  { id: 'motion', label: 'Motion', hint: 'Set when a layer is on screen and keyframe how it moves', mark: 'K' },
  { id: 'audio', label: 'Audio', hint: 'Mute, set the volume, and choose the playback speed', mark: 'V' },
];

export const MemeEditToolRail = React.memo(function MemeEditToolRail({
  activeTool,
  onSelectTool,
  disabled,
  sourceKind,
}: {
  activeTool: MemeEditTool;
  onSelectTool: (tool: MemeEditTool) => void;
  disabled?: boolean;
  sourceKind: MediaEditKind;
}) {
  const availableTools = memeEditToolsForSource(sourceKind);
  return (
    <View style={styles.rail} accessibilityRole="toolbar" accessibilityLabel="Editing tools">
      {TOOLS.filter((tool) => availableTools.includes(tool.id)).map((tool) => {
        const selected = activeTool === tool.id;
        return (
          <PressableScale
            key={tool.id}
            scaleTo={0.94}
            disabled={disabled}
            onPress={() => onSelectTool(tool.id)}
            style={[styles.tool, selected && styles.toolSelected]}
            accessibilityRole="button"
            accessibilityLabel={tool.label}
            accessibilityHint={tool.hint}
            accessibilityState={{ selected, disabled: !!disabled }}
          >
            <View style={[styles.mark, selected && styles.markSelected]}>
              <Text style={[styles.markText, selected && styles.markTextSelected]}>{tool.mark}</Text>
            </View>
            <Text style={[styles.label, selected && styles.labelSelected]}>{tool.label}</Text>
          </PressableScale>
        );
      })}
    </View>
  );
});

const styles = StyleSheet.create({
  rail: {
    minHeight: 64,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    paddingHorizontal: space.md,
    paddingVertical: space.sm,
    backgroundColor: colors.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  tool: {
    minHeight: 44,
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  toolSelected: {
    borderColor: colors.volt,
    backgroundColor: colors.voltDim,
  },
  mark: {
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: colors.borderLight,
    backgroundColor: colors.surface3,
  },
  markSelected: {
    borderColor: colors.volt,
    backgroundColor: colors.volt,
  },
  markText: {
    color: colors.textDim,
    fontSize: 12,
    fontWeight: '800',
  },
  markTextSelected: {
    color: colors.onVolt,
  },
  label: {
    ...type.label,
    color: colors.textDim,
  },
  labelSelected: {
    color: colors.volt,
  },
});
