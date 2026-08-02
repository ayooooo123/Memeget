import React, { useCallback } from 'react';
import { Alert, FlatList, StyleSheet, Text, View } from 'react-native';

import { colors, radius, space, type } from '../theme';
import type { MemeEditLayer, MemeEditProject } from '../memeEditProjectCore';
import { PressableScale } from './ui';

function layerTitle(layer: MemeEditLayer): string {
  if (layer.kind === 'text') return layer.text.trim() || 'Text layer';
  if (layer.kind === 'cover') return `${layer.mode === 'pixelate' ? 'Pixelate' : 'Cover'} region`;
  if (layer.kind === 'subject') return layer.subjectIndex == null ? 'Subject cutout' : `Subject ${layer.subjectIndex + 1}`;
  return layer.assetKind === 'video' ? 'Video overlay' : 'Image overlay';
}

function layerMeta(layer: MemeEditLayer): string {
  if (layer.kind === 'text') return `Text · width ${(layer.width * 100).toFixed(0)}%`;
  if (layer.kind === 'cover') return `Correction · ${(layer.rect.width * 100).toFixed(0)} × ${(layer.rect.height * 100).toFixed(0)}%`;
  if (layer.kind === 'subject') return layer.maskTrackId ? `Mask ${layer.maskTrackId}` : 'Mask unavailable';
  return `${layer.assetKind} · ${layer.fit}`;
}

const LayerRow = React.memo(function LayerRow({
  layer,
  index,
  total,
  selected,
  disabled,
  onSelect,
  onMoveUp,
  onMoveDown,
  onDuplicate,
  onDelete,
}: {
  layer: MemeEditLayer;
  index: number;
  total: number;
  selected: boolean;
  disabled: boolean;
  onSelect: (id: string) => void;
  onMoveUp: (id: string) => void;
  onMoveDown: (id: string) => void;
  onDuplicate: (id: string) => void;
  onDelete: (id: string) => void;
}) {
  const canMoveUp = index < total - 1;
  const canMoveDown = index > 0;
  return (
    <PressableScale
      scaleTo={0.98}
      onPress={() => {
        if (!disabled) onSelect(layer.id);
      }}
      style={[styles.row, selected && styles.rowSelected]}
      accessibilityRole="button"
      accessibilityLabel={`${layerTitle(layer)}, layer ${index + 1} of ${total}`}
      accessibilityHint="Select this layer for direct manipulation"
      accessibilityState={{ selected, disabled }}
      accessibilityActions={[
        { name: 'activate', label: 'Select layer' },
        ...(canMoveUp ? [{ name: 'increment', label: 'Move layer up' }] : []),
        ...(canMoveDown ? [{ name: 'decrement', label: 'Move layer down' }] : []),
      ]}
      onAccessibilityAction={(event) => {
        if (disabled) return;
        if (event.nativeEvent.actionName === 'increment') onMoveUp(layer.id);
        else if (event.nativeEvent.actionName === 'decrement') onMoveDown(layer.id);
        else onSelect(layer.id);
      }}
    >
      <View style={styles.rowMain}>
        <View style={[styles.indexBadge, selected && styles.indexBadgeSelected]}>
          <Text style={[styles.indexText, selected && styles.indexTextSelected]}>{total - index}</Text>
        </View>
        <View style={styles.copy}>
          <Text style={styles.title} numberOfLines={1}>{layerTitle(layer)}</Text>
          <Text style={styles.meta} numberOfLines={1}>{layerMeta(layer)}</Text>
        </View>
      </View>
      <View style={styles.actions}>
        <MiniButton label="Up" onPress={() => onMoveUp(layer.id)} disabled={disabled || !canMoveUp} hint="Move this layer visually forward" />
        <MiniButton label="Down" onPress={() => onMoveDown(layer.id)} disabled={disabled || !canMoveDown} hint="Move this layer visually backward" />
        <MiniButton label="Dup" onPress={() => onDuplicate(layer.id)} disabled={disabled} hint="Duplicate this layer" />
        <MiniButton label="Del" danger onPress={() => onDelete(layer.id)} disabled={disabled} hint="Delete this layer" />
      </View>
    </PressableScale>
  );
});

function MiniButton({
  label,
  hint,
  onPress,
  disabled,
  danger,
}: {
  label: string;
  hint: string;
  onPress: () => void;
  disabled?: boolean;
  danger?: boolean;
}) {
  return (
    <PressableScale
      scaleTo={0.9}
      onPress={onPress}
      disabled={disabled}
      style={[styles.mini, danger && styles.miniDanger]}
      accessibilityRole="button"
      accessibilityLabel={label === 'Del' ? 'Delete layer' : label === 'Dup' ? 'Duplicate layer' : `Move layer ${label.toLowerCase()}`}
      accessibilityHint={hint}
      accessibilityState={{ disabled: !!disabled }}
    >
      <Text style={[styles.miniText, danger && styles.miniTextDanger]}>{label}</Text>
    </PressableScale>
  );
}

export const MemeLayerList = React.memo(function MemeLayerList({
  project,
  selectedLayerId,
  onSelectLayer,
  onMoveLayer,
  onDuplicateLayer,
  onDeleteLayer,
  disabled = false,
}: {
  project: MemeEditProject;
  selectedLayerId: string | null;
  onSelectLayer: (id: string | null) => void;
  onMoveLayer: (id: string, toIndex: number) => void;
  onDuplicateLayer: (id: string) => void;
  onDeleteLayer: (id: string) => void;
  disabled?: boolean;
}) {
  const ordered = project.layers.slice().reverse();
  const total = project.layers.length;
  const moveUp = useCallback((id: string) => {
    if (disabled) return;
    const index = project.layers.findIndex((layer) => layer.id === id);
    if (index >= 0) onMoveLayer(id, Math.min(project.layers.length - 1, index + 1));
  }, [project.layers, onMoveLayer]);
  const moveDown = useCallback((id: string) => {
    if (disabled) return;
    const index = project.layers.findIndex((layer) => layer.id === id);
    if (index >= 0) onMoveLayer(id, Math.max(0, index - 1));
  }, [project.layers, onMoveLayer]);
  const deleteLayer = useCallback((id: string) => {
    if (disabled) return;
    const layer = project.layers.find((candidate) => candidate.id === id);
    Alert.alert('Delete layer?', layer ? layerTitle(layer) : 'This cannot be undone.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => onDeleteLayer(id) },
    ]);
  }, [project.layers, onDeleteLayer]);
  const renderItem = useCallback(({ item, index }: { item: MemeEditLayer; index: number }) => (
    <LayerRow
      layer={item}
      index={total - index - 1}
      total={total}
      selected={item.id === selectedLayerId}
      disabled={disabled}
      onSelect={onSelectLayer}
      onMoveUp={moveUp}
      onMoveDown={moveDown}
      onDuplicate={onDuplicateLayer}
      onDelete={deleteLayer}
    />
  ), [deleteLayer, disabled, moveDown, moveUp, onDuplicateLayer, onSelectLayer, selectedLayerId, total]);

  if (project.layers.length === 0) {
    return (
      <View style={styles.empty} accessibilityRole="text">
        <Text style={styles.emptyTitle}>No editable layers yet</Text>
        <Text style={styles.emptyText}>This shell edits layers restored from drafts or later tools. The source stays unchanged.</Text>
      </View>
    );
  }

  return (
    <FlatList
      data={ordered}
      keyExtractor={(layer) => layer.id}
      renderItem={renderItem}
      contentContainerStyle={styles.list}
      keyboardShouldPersistTaps="handled"
    />
  );
});

const styles = StyleSheet.create({
  list: { gap: space.sm, padding: space.md },
  empty: {
    gap: space.xs,
    padding: space.lg,
    backgroundColor: colors.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  emptyTitle: { ...type.title, color: colors.text },
  emptyText: { ...type.caption, color: colors.muted, lineHeight: 17 },
  row: {
    gap: space.sm,
    padding: space.md,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  rowSelected: {
    borderColor: colors.volt,
    backgroundColor: colors.voltDim,
    borderLeftWidth: 4,
  },
  rowMain: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  indexBadge: {
    width: 30,
    height: 30,
    borderRadius: 15,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surface3,
    borderWidth: 1,
    borderColor: colors.borderLight,
  },
  indexBadgeSelected: { backgroundColor: colors.volt, borderColor: colors.volt },
  indexText: { color: colors.textDim, fontWeight: '800', fontVariant: ['tabular-nums'] },
  indexTextSelected: { color: colors.onVolt },
  copy: { flex: 1, minWidth: 0 },
  title: { ...type.label, color: colors.text },
  meta: { ...type.caption, color: colors.muted, marginTop: 2 },
  actions: { flexDirection: 'row', gap: space.xs, flexWrap: 'wrap' },
  mini: {
    minWidth: 52,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.borderLight,
    backgroundColor: colors.surface3,
  },
  miniDanger: { borderColor: colors.danger, backgroundColor: colors.dangerDim },
  miniText: { color: colors.textDim, fontSize: 12, fontWeight: '800' },
  miniTextDanger: { color: colors.danger },
});
