import React, { useState } from 'react';
import { Image } from 'expo-image';
import {
  Dimensions,
  FlatList,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { colors } from '../theme';
import type { MemeRecord, SearchHit } from '../types';

const GAP = 2;
const COLS = 3;

type Item = MemeRecord | SearchHit;

export function MemeGrid({ items, header }: { items: Item[]; header?: React.ReactElement }) {
  const [selected, setSelected] = useState<Item | null>(null);
  const size = (Dimensions.get('window').width - GAP * (COLS + 1)) / COLS;

  return (
    <>
      <FlatList
        data={items}
        keyExtractor={(it) => String(it.id)}
        numColumns={COLS}
        ListHeaderComponent={header}
        columnWrapperStyle={{ gap: GAP, paddingHorizontal: GAP }}
        contentContainerStyle={{ gap: GAP, paddingBottom: 24 }}
        renderItem={({ item }) => (
          <Pressable onPress={() => setSelected(item)} style={{ width: size, height: size }}>
            <Image
              source={{ uri: item.uri }}
              style={styles.thumb}
              contentFit="cover"
              transition={120}
            />
            {item.kind === 'video' && (
              <View style={styles.play}>
                <Text style={styles.playIcon}>▶</Text>
              </View>
            )}
          </Pressable>
        )}
      />

      <Modal visible={!!selected} transparent animationType="fade" onRequestClose={() => setSelected(null)}>
        <Pressable style={styles.backdrop} onPress={() => setSelected(null)}>
          {selected && (
            <View style={styles.sheet}>
              <Image source={{ uri: selected.uri }} style={styles.preview} contentFit="contain" />
              <ScrollView style={styles.meta} contentContainerStyle={{ padding: 14, gap: 10 }}>
                <Text style={styles.name}>{selected.name}</Text>
                {'score' in selected && (
                  <Text style={styles.muted}>match {(selected.score * 100).toFixed(0)}%</Text>
                )}
                {selected.tags.length > 0 && (
                  <View style={styles.chipRow}>
                    {selected.tags.map((t) => (
                      <View key={t.label} style={styles.chip}>
                        <Text style={styles.chipText}>{t.label}</Text>
                      </View>
                    ))}
                  </View>
                )}
                {!!selected.ocrText && (
                  <View>
                    <Text style={styles.sectionLabel}>Text in meme</Text>
                    <Text style={styles.ocr}>{selected.ocrText}</Text>
                  </View>
                )}
              </ScrollView>
            </View>
          )}
        </Pressable>
      </Modal>
    </>
  );
}

const styles = StyleSheet.create({
  thumb: { width: '100%', height: '100%', backgroundColor: colors.surface2, borderRadius: 4 },
  play: {
    position: 'absolute',
    right: 4,
    bottom: 4,
    backgroundColor: 'rgba(0,0,0,0.6)',
    borderRadius: 10,
    paddingHorizontal: 5,
    paddingVertical: 1,
  },
  playIcon: { color: '#fff', fontSize: 10 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.85)', justifyContent: 'center', padding: 16 },
  sheet: { backgroundColor: colors.surface, borderRadius: 16, overflow: 'hidden', maxHeight: '88%' },
  preview: { width: '100%', height: 320, backgroundColor: '#000' },
  meta: { maxHeight: 240 },
  name: { color: colors.text, fontWeight: '700', fontSize: 14 },
  muted: { color: colors.muted, fontSize: 12 },
  sectionLabel: { color: colors.muted, fontSize: 11, textTransform: 'uppercase', marginBottom: 4 },
  ocr: { color: colors.text, fontSize: 13, lineHeight: 18 },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  chip: { backgroundColor: colors.chip, borderRadius: 12, paddingHorizontal: 10, paddingVertical: 4 },
  chipText: { color: colors.accent, fontSize: 12, fontWeight: '600' },
});
