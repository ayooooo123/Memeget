import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { colors, radius, space, type } from '../theme';
import { PressableScale } from './ui';

export type MemeEditTool = 'layers' | 'transform';

interface ToolSpec {
  id: MemeEditTool;
  label: string;
  hint: string;
  mark: string;
}

const TOOLS: readonly ToolSpec[] = [
  { id: 'layers', label: 'Layers', hint: 'Show layer order and layer actions', mark: 'L' },
  { id: 'transform', label: 'Transform', hint: 'Move, resize, and rotate the selected layer', mark: 'T' },
];

export const MemeEditToolRail = React.memo(function MemeEditToolRail({
  activeTool,
  onSelectTool,
  disabled,
}: {
  activeTool: MemeEditTool;
  onSelectTool: (tool: MemeEditTool) => void;
  disabled?: boolean;
}) {
  return (
    <View style={styles.rail} accessibilityRole="toolbar" accessibilityLabel="Editing tools">
      {TOOLS.map((tool) => {
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
