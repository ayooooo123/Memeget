import React, { useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text } from 'react-native';

import { memeEditToolsForSource, toolRailScrollOffsetPx, TOOL_RAIL_ITEM_WIDTH, TOOL_RAIL_GAP, TOOL_RAIL_PADDING, type MemeEditToolId } from '../memeEditCanvasCore';
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

// The label always shows, so the mark is recognition support rather than the
// only clue. That matters: the previous single letters (L, A, T, R, C, F, K, V)
// carried no meaning on their own AND the row was too cramped to show the words
// beside them, so "Transform" rendered as "Transfo…" with the badge overlapping
// it. A glyph above a full word fixes both.
const TOOLS: readonly ToolSpec[] = [
  { id: 'layers', label: 'Layers', hint: 'Show layer order and layer actions', mark: '▤' },
  { id: 'text', label: 'Text', hint: 'Add and style meme text layers', mark: 'A' },
  { id: 'transform', label: 'Transform', hint: 'Crop, rotate, or flip this image', mark: '⌗' },
  { id: 'replace-text', label: 'Replace', hint: 'Detect text or draw a box, then Cover, Pixelate, or Replace', mark: '▨' },
  { id: 'subject', label: 'Cut out', hint: 'Isolate the subject and replace or remove the background', mark: '✂' },
  { id: 'timeline', label: 'Timeline', hint: 'Scrub, trim, split, and see when each layer is on screen', mark: '⏱' },
  { id: 'frames', label: 'Frames', hint: 'Scroll to any exact source frame and step one frame at a time', mark: '⊞' },
  { id: 'motion', label: 'Motion', hint: 'Set when a layer is on screen and keyframe how it moves', mark: '➚' },
  { id: 'audio', label: 'Audio', hint: 'Mute, set the volume, and choose the playback speed', mark: '♪' },
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
  const tools = TOOLS.filter((tool) => availableTools.includes(tool.id));
  const scroller = useRef<ScrollView>(null);
  const viewport = useRef(0);
  const offset = useRef(0);

  // A video source offers eight tools; at ~384dp only four fit. Without this,
  // selecting a tool programmatically (or reopening on one) leaves the active
  // tool off-screen with nothing to indicate the rail scrolls at all.
  useEffect(() => {
    const index = tools.findIndex((tool) => tool.id === activeTool);
    if (index < 0) return;
    const next = toolRailScrollOffsetPx(index, viewport.current, offset.current);
    // Already visible -> the offset comes back unchanged and the rail holds
    // still, which is the difference between "scrolls when it must" and
    // "twitches whenever you change tool".
    if (next === offset.current) return;
    offset.current = next;
    scroller.current?.scrollTo({ x: next, animated: true });
  }, [activeTool, tools]);

  return (
    <ScrollView
      ref={scroller}
      horizontal
      showsHorizontalScrollIndicator={false}
      style={styles.rail}
      contentContainerStyle={styles.railContent}
      onLayout={(event) => {
        viewport.current = event.nativeEvent.layout.width;
      }}
      scrollEventThrottle={64}
      onScroll={(event) => {
        offset.current = event.nativeEvent.contentOffset.x;
      }}
      accessibilityRole="toolbar"
      accessibilityLabel="Editing tools"
    >
      {tools.map((tool) => {
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
            <Text style={[styles.markText, selected && styles.markTextSelected]}>{tool.mark}</Text>
            <Text style={[styles.label, selected && styles.labelSelected]} numberOfLines={1}>
              {tool.label}
            </Text>
          </PressableScale>
        );
      })}
    </ScrollView>
  );
});

const styles = StyleSheet.create({
  rail: {
    flexGrow: 0,
    backgroundColor: colors.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  railContent: {
    alignItems: 'center',
    gap: TOOL_RAIL_GAP,
    // Must match TOOL_RAIL_PADDING: the scroll-offset math assumes it.
    paddingHorizontal: TOOL_RAIL_PADDING,
    paddingVertical: space.sm,
  },
  tool: {
    // Fixed rather than flexed: a flexed row divided by eight tools is what
    // produced 48dp cells and truncated words.
    width: TOOL_RAIL_ITEM_WIDTH,
    minHeight: 56,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
    paddingVertical: space.xs,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  toolSelected: {
    borderColor: colors.volt,
    backgroundColor: colors.voltDim,
  },
  markText: {
    color: colors.textDim,
    fontSize: 17,
    lineHeight: 21,
    fontWeight: '600',
  },
  markTextSelected: {
    color: colors.volt,
  },
  label: {
    ...type.caption,
    color: colors.textDim,
  },
  labelSelected: {
    color: colors.volt,
  },
});
