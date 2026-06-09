import React, { useEffect, useRef, useState } from 'react';
import { Image } from 'expo-image';
import {
  ActivityIndicator,
  Alert,
  Dimensions,
  FlatList,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import { addExemplar, getMemeEmbedding } from '../db';
import { EXEMPLAR_PROB_THRESHOLD, headProb } from '../embeddings';
import { buildExemplarHeads, type ExemplarModel } from '../indexer';
import { colors } from '../theme';
import type { MemeRecord, SearchHit } from '../types';

const GAP = 2;
const COLS = 3;

type Item = MemeRecord | SearchHit;

export function MemeGrid({
  items,
  header,
  onTaught,
  onEndReached,
  loadingMore,
}: {
  items: Item[];
  header?: React.ReactElement;
  // Called after a new exemplar is saved; should re-tag and return how many
  // memes now carry the label (for feedback). Optional.
  onTaught?: (label: string) => Promise<number | void>;
  // Infinite-scroll hook: called when the user nears the end of the list so
  // the parent can append the next page. Optional (search passes a fixed set).
  onEndReached?: () => void;
  loadingMore?: boolean;
}) {
  const [selected, setSelected] = useState<Item | null>(null);
  const [teaching, setTeaching] = useState(false);
  const [labelInput, setLabelInput] = useState('');
  const [assocInput, setAssocInput] = useState('');
  const [saving, setSaving] = useState(false);
  const [matchInfo, setMatchInfo] = useState<{ label: string; score: number }[] | null>(null);
  // Cache the trained heads so we don't retrain on every modal open; cleared
  // after teaching so the next open reflects the new example.
  const modelRef = useRef<ExemplarModel | null>(null);
  const size = (Dimensions.get('window').width - GAP * (COLS + 1)) / COLS;

  // Live diagnostic: when a meme opens, run it through every taught label's
  // trained head and show the calibrated probability — so a real Milady reads
  // ~0.95 and an unrelated meme ~0.03, confirming the model actually learned.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!selected) {
        setMatchInfo(null);
        return;
      }
      setMatchInfo(null);
      if (!modelRef.current) modelRef.current = await buildExemplarHeads();
      const model = modelRef.current;
      const emb = await getMemeEmbedding(selected.id);
      if (cancelled) return;
      if (!emb || model.heads.length === 0) {
        setMatchInfo([]);
        return;
      }
      const centered = model.mean
        ? Array.from(emb, (v, i) => v - model.mean![i])
        : Array.from(emb);
      const scored = model.heads
        .map((h) => ({ label: h.label, score: headProb(h, centered) }))
        .sort((a, b) => b.score - a.score)
        .slice(0, 6);
      setMatchInfo(scored);
    })();
    return () => {
      cancelled = true;
    };
  }, [selected]);

  const openTeach = () => {
    setLabelInput('');
    setAssocInput('');
    setTeaching(true);
  };

  const saveExemplar = async () => {
    const label = labelInput.trim();
    if (!selected || !label) return;
    setSaving(true);
    try {
      const emb = await getMemeEmbedding(selected.id);
      if (!emb) {
        Alert.alert('Could not teach', 'No stored embedding for this item.');
        return;
      }
      const associations = assocInput
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
      await addExemplar({
        label,
        category: 'character',
        vector: Array.from(emb),
        associations,
        sourceUri: selected.uri,
      });
      setTeaching(false);
      modelRef.current = null; // new example → retrain heads on next open
      const matched = onTaught ? await onTaught(label) : undefined;
      Alert.alert(
        'Taught!',
        typeof matched === 'number'
          ? `Applied across your library: ${matched} meme${matched === 1 ? '' : 's'} now tagged "${label}".` +
              (matched <= 1
                ? ' Teach a few more examples (different poses/backgrounds) to catch the rest.'
                : '')
          : `Memeget will now recognize "${label}" by example. Run "Re-tag library" in Settings to apply it.`
      );
    } catch (e) {
      Alert.alert('Could not teach', String(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <FlatList
        data={items}
        keyExtractor={(it) => String(it.id)}
        numColumns={COLS}
        ListHeaderComponent={header}
        columnWrapperStyle={{ gap: GAP, paddingHorizontal: GAP }}
        contentContainerStyle={{ gap: GAP, paddingBottom: 24 }}
        onEndReached={onEndReached}
        onEndReachedThreshold={0.6}
        ListFooterComponent={
          loadingMore ? <ActivityIndicator color={colors.accent} style={{ paddingVertical: 16 }} /> : null
        }
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
                  <Text style={styles.muted}>match {Math.min(100, selected.score * 100).toFixed(0)}%</Text>
                )}
                {selected.tags.length > 0 && (
                  <View style={styles.chipRow}>
                    {selected.tags.map((t) => (
                      <View
                        key={t.label}
                        style={[styles.chip, t.source === 'exemplar' && styles.chipTaught]}
                      >
                        <Text style={styles.chipText}>
                          {t.source === 'exemplar' ? '★ ' : ''}
                          {t.label}
                        </Text>
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
                {matchInfo && matchInfo.length > 0 && (
                  <View>
                    <Text style={styles.sectionLabel}>Taught-label confidence (debug)</Text>
                    {matchInfo.map((m) => (
                      <Text key={m.label} style={styles.muted}>
                        {m.label}: {(m.score * 100).toFixed(0)}%{' '}
                        {m.score >= EXEMPLAR_PROB_THRESHOLD ? '✓ match' : ''}
                      </Text>
                    ))}
                  </View>
                )}
                <Pressable style={styles.teachBtn} onPress={openTeach}>
                  <Text style={styles.teachBtnText}>＋ Teach a label from this image</Text>
                </Pressable>
              </ScrollView>
            </View>
          )}
        </Pressable>
      </Modal>

      <Modal visible={teaching} transparent animationType="fade" onRequestClose={() => setTeaching(false)}>
        <Pressable style={styles.backdrop} onPress={() => setTeaching(false)}>
          <Pressable style={styles.teachSheet} onPress={() => {}}>
            <Text style={styles.name}>Teach a label</Text>
            <Text style={styles.muted}>
              Name what this is (e.g. “Milady”). Memeget learns it by visual example — no model
              retraining, fully on-device.
            </Text>
            <TextInput
              style={styles.input}
              value={labelInput}
              onChangeText={setLabelInput}
              placeholder="Label, e.g. Milady"
              placeholderTextColor={colors.muted}
              autoFocus
            />
            <TextInput
              style={styles.input}
              value={assocInput}
              onChangeText={setAssocInput}
              placeholder="Related terms (optional, comma-separated): remilia, nft, ethereum"
              placeholderTextColor={colors.muted}
            />
            <View style={styles.teachRow}>
              <Pressable style={[styles.teachAction, styles.teachCancel]} onPress={() => setTeaching(false)}>
                <Text style={styles.teachCancelText}>Cancel</Text>
              </Pressable>
              <Pressable
                style={[styles.teachAction, styles.teachSave, (!labelInput.trim() || saving) && styles.disabled]}
                onPress={saveExemplar}
                disabled={!labelInput.trim() || saving}
              >
                <Text style={styles.teachSaveText}>{saving ? 'Saving…' : 'Teach'}</Text>
              </Pressable>
            </View>
          </Pressable>
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
  chipTaught: { backgroundColor: '#1f3a2c', borderWidth: 1, borderColor: colors.accent2 },
  chipText: { color: colors.accent, fontSize: 12, fontWeight: '600' },
  teachBtn: {
    marginTop: 4,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 10,
    paddingVertical: 10,
    alignItems: 'center',
  },
  teachBtnText: { color: colors.accent2, fontWeight: '700', fontSize: 13 },
  teachSheet: { backgroundColor: colors.surface, borderRadius: 16, padding: 18, gap: 12 },
  input: {
    backgroundColor: colors.surface2,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    color: colors.text,
    fontSize: 14,
  },
  teachRow: { flexDirection: 'row', gap: 10, marginTop: 4 },
  teachAction: { flex: 1, paddingVertical: 11, borderRadius: 10, alignItems: 'center' },
  teachCancel: { borderWidth: 1, borderColor: colors.border },
  teachCancelText: { color: colors.text, fontWeight: '700' },
  teachSave: { backgroundColor: colors.accent2 },
  teachSaveText: { color: '#0b0d12', fontWeight: '800' },
  disabled: { opacity: 0.5 },
});
