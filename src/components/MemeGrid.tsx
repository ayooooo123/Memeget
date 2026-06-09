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

import * as Clipboard from 'expo-clipboard';
import * as Sharing from 'expo-sharing';

import { addExemplar, getLabels, getMemeEmbedding } from '../db';
import { EXEMPLAR_PROB_THRESHOLD, headProb } from '../embeddings';
import { buildExemplarHeads, type ExemplarModel } from '../indexer';
import { materialize, readImageBase64 } from '../saf';
import { colors } from '../theme';
import type { MemeRecord, SearchHit } from '../types';

const GAP = 2;
const COLS = 3;

type Item = MemeRecord | SearchHit;

// Memoized so modal/teach-sheet state changes in MemeGrid don't re-render every
// visible thumbnail (which kept the tag-edit tap feeling sluggish on big grids).
const GridCell = React.memo(function GridCell({
  item,
  size,
  onPress,
}: {
  item: Item;
  size: number;
  onPress: (it: Item) => void;
}) {
  return (
    <Pressable onPress={() => onPress(item)} style={{ width: size, height: size }}>
      <Image
        source={{ uri: item.uri }}
        style={styles.thumb}
        contentFit="cover"
        transition={120}
        // Reuse the view and release the previous bitmap when a cell is
        // recycled (e.g. when retagAll hands the list a fresh array).
        recyclingKey={String(item.id)}
        cachePolicy="disk"
        allowDownscaling
      />
      {item.kind === 'video' && (
        <View style={styles.play}>
          <Text style={styles.playIcon}>▶</Text>
        </View>
      )}
    </Pressable>
  );
});

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
  const [positive, setPositive] = useState(true);
  const [labels, setLabels] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [matchInfo, setMatchInfo] = useState<{ label: string; score: number }[] | null>(null);
  const [matchBusy, setMatchBusy] = useState(false);
  // Cache the trained heads so we don't retrain on every modal open; cleared
  // after teaching so the next open reflects the new example.
  const modelRef = useRef<ExemplarModel | null>(null);
  const size = (Dimensions.get('window').width - GAP * (COLS + 1)) / COLS;

  // The taught-label confidence readout is a debug aid that trains a logistic
  // head per label over a 500-vector background — hundreds of ms+ of synchronous
  // JS. Running it on every meme open froze the UI (and any tag tap queued
  // behind it), so it's now opt-in: reset on open, compute only when tapped.
  useEffect(() => {
    setMatchInfo(null);
  }, [selected]);

  const computeMatchInfo = async () => {
    if (!selected || matchBusy) return;
    setMatchBusy(true);
    try {
      if (!modelRef.current) modelRef.current = await buildExemplarHeads();
      const model = modelRef.current;
      const emb = await getMemeEmbedding(selected.id);
      if (!emb || model.heads.length === 0) {
        setMatchInfo([]);
        return;
      }
      const centered = model.mean ? Array.from(emb, (v, i) => v - model.mean![i]) : Array.from(emb);
      const scored = model.heads
        .map((h) => ({ label: h.label, score: headProb(h, centered) }))
        .sort((a, b) => b.score - a.score)
        .slice(0, 6);
      setMatchInfo(scored);
    } finally {
      setMatchBusy(false);
    }
  };

  const flash = (msg: string) => {
    setNotice(msg);
    setTimeout(() => setNotice(null), 1600);
  };

  // Share the original file to any other app (Discord, Telegram, Photos…) — the
  // fastest way to get a meme onto another platform on mobile.
  const onShare = async () => {
    if (!selected || busy) return;
    setBusy(true);
    try {
      if (!(await Sharing.isAvailableAsync())) {
        Alert.alert('Sharing unavailable', 'This device has no share targets.');
        return;
      }
      const path = await materialize(selected.uri, selected.name);
      await Sharing.shareAsync(path);
    } catch (e) {
      Alert.alert('Could not share', String(e));
    } finally {
      setBusy(false);
    }
  };

  const onCopyImage = async () => {
    if (!selected || busy) return;
    if (selected.kind === 'video') {
      flash('Can’t copy a video as an image — use Share');
      return;
    }
    setBusy(true);
    try {
      const base64 = await readImageBase64(selected.uri, selected.name);
      await Clipboard.setImageAsync(base64);
      flash('Meme copied — paste it anywhere');
    } catch (e) {
      Alert.alert('Could not copy image', String(e));
    } finally {
      setBusy(false);
    }
  };

  const onCopyText = async () => {
    if (!selected?.ocrText) return;
    await Clipboard.setStringAsync(selected.ocrText);
    flash('Text copied');
  };

  const openTeach = (asPositive: boolean, preset?: string) => {
    setLabelInput(preset ?? '');
    setAssocInput('');
    setPositive(asPositive);
    setTeaching(true);
    getLabels()
      .then(setLabels)
      .catch(() => setLabels([]));
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
      // Associations are world-knowledge terms for a positive label; they make
      // no sense for a "this is NOT a <label>" correction.
      const associations = positive
        ? assocInput.split(',').map((s) => s.trim()).filter(Boolean)
        : [];
      await addExemplar({
        label,
        category: 'character',
        vector: Array.from(emb),
        associations,
        sourceUri: selected.uri,
        positive,
      });
      setTeaching(false);
      modelRef.current = null; // new example → retrain heads on next open
      const matched = onTaught ? await onTaught(label) : undefined;
      const count =
        typeof matched === 'number'
          ? ` ${matched} meme${matched === 1 ? '' : 's'} now tagged "${label}".`
          : ' Run "Re-tag library" in Settings to apply it.';
      Alert.alert(
        positive ? 'Taught!' : 'Correction saved',
        positive
          ? `Memeget will recognize "${label}" by example.${count}` +
              (typeof matched === 'number' && matched <= 1
                ? ' Teach a few more (different poses/backgrounds) to catch the rest.'
                : '')
          : `Marked as NOT "${label}". The model learns from the correction —` +
              ` similar images are less likely to be tagged "${label}".${count}`
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
        // Memory guards: keep only a few screens of image cells mounted so a
        // large library can't pin hundreds of decoded bitmaps at once (was
        // OOM-ing when a teach → retagAll re-render spiked on top of the grid).
        removeClippedSubviews
        initialNumToRender={15}
        maxToRenderPerBatch={9}
        windowSize={5}
        updateCellsBatchingPeriod={60}
        ListFooterComponent={
          loadingMore ? <ActivityIndicator color={colors.accent} style={{ paddingVertical: 16 }} /> : null
        }
        renderItem={({ item }) => <GridCell item={item} size={size} onPress={setSelected} />}
      />

      <Modal
        visible={!!selected}
        transparent
        animationType="fade"
        statusBarTranslucent
        onRequestClose={() => setSelected(null)}
      >
        <View style={styles.modalRoot}>
          {/* Backdrop sits BEHIND the sheet: only taps that land outside the
              sheet reach it, so scrolling/teaching/selecting never dismisses. */}
          <Pressable style={StyleSheet.absoluteFill} onPress={() => setSelected(null)} />
          {selected && (
            <View style={styles.sheet}>
              <View style={styles.sheetHeader}>
                <Text style={styles.sheetTitle} numberOfLines={1} selectable>
                  {selected.name}
                </Text>
                <Pressable
                  onPress={() => setSelected(null)}
                  hitSlop={12}
                  style={styles.closeBtn}
                  accessibilityLabel="Close"
                >
                  <Text style={styles.closeIcon}>✕</Text>
                </Pressable>
              </View>
              <Image
                source={{ uri: selected.uri }}
                style={styles.preview}
                contentFit="contain"
                recyclingKey={String(selected.id)}
                allowDownscaling
              />
              <View style={styles.actionBar}>
                <Pressable style={styles.actionBtn} onPress={onShare} disabled={busy}>
                  <Text style={styles.actionText}>{busy ? 'Preparing…' : '⤴  Share'}</Text>
                </Pressable>
                {selected.kind !== 'video' && (
                  <Pressable style={styles.actionBtn} onPress={onCopyImage} disabled={busy}>
                    <Text style={styles.actionText}>⧉  Copy image</Text>
                  </Pressable>
                )}
                {!!selected.ocrText && (
                  <Pressable style={styles.actionBtn} onPress={onCopyText}>
                    <Text style={styles.actionText}>🆎  Copy text</Text>
                  </Pressable>
                )}
              </View>
              {notice && <Text style={styles.notice}>{notice}</Text>}
              <ScrollView
                style={styles.meta}
                contentContainerStyle={{ padding: 14, gap: 10 }}
                keyboardShouldPersistTaps="handled"
              >
                {'score' in selected && (
                  <Text style={styles.muted}>match {Math.min(100, selected.score * 100).toFixed(0)}%</Text>
                )}
                {selected.tags.length > 0 && (
                  <View>
                    <Text style={styles.sectionLabel}>Tags · tap one to correct it</Text>
                    <View style={styles.chipRow}>
                      {selected.tags.map((t) => (
                        <Pressable
                          key={t.label}
                          onPress={() => openTeach(false, t.label)}
                          style={[styles.chip, t.source === 'exemplar' && styles.chipTaught]}
                        >
                          <Text style={styles.chipText}>
                            {t.source === 'exemplar' ? '★ ' : ''}
                            {t.label}
                          </Text>
                        </Pressable>
                      ))}
                    </View>
                  </View>
                )}
                {!!selected.ocrText && (
                  <View>
                    <Text style={styles.sectionLabel}>Text in meme · long-press to copy</Text>
                    <Text style={styles.ocr} selectable>
                      {selected.ocrText}
                    </Text>
                  </View>
                )}
                {matchInfo === null ? (
                  <Pressable onPress={computeMatchInfo} disabled={matchBusy}>
                    <Text style={styles.debugLink}>
                      {matchBusy ? 'Scoring…' : 'Show taught-label confidence (debug)'}
                    </Text>
                  </Pressable>
                ) : matchInfo.length > 0 ? (
                  <View>
                    <Text style={styles.sectionLabel}>Taught-label confidence (debug)</Text>
                    {matchInfo.map((m) => (
                      <Text key={m.label} style={styles.muted}>
                        {m.label}: {(m.score * 100).toFixed(0)}%{' '}
                        {m.score >= EXEMPLAR_PROB_THRESHOLD ? '✓ match' : ''}
                      </Text>
                    ))}
                  </View>
                ) : (
                  <Text style={styles.muted}>No taught labels yet.</Text>
                )}
                <View style={styles.teachRow}>
                  <Pressable style={[styles.teachBtn, { flex: 1 }]} onPress={() => openTeach(true)}>
                    <Text style={styles.teachBtnText}>＋ This IS a…</Text>
                  </Pressable>
                  <Pressable
                    style={[styles.teachBtn, styles.teachBtnNeg, { flex: 1 }]}
                    onPress={() => openTeach(false)}
                  >
                    <Text style={styles.teachBtnNegText}>✗ This is NOT a…</Text>
                  </Pressable>
                </View>
              </ScrollView>
            </View>
          )}
        </View>
      </Modal>

      <Modal visible={teaching} transparent animationType="fade" onRequestClose={() => setTeaching(false)}>
        <Pressable style={styles.backdrop} onPress={() => setTeaching(false)}>
          <Pressable style={styles.teachSheet} onPress={() => {}}>
            <Text style={styles.name}>Label this meme</Text>
            <View style={styles.segRow}>
              <Pressable
                style={[styles.seg, positive && styles.segActive]}
                onPress={() => setPositive(true)}
              >
                <Text style={[styles.segText, positive && styles.segTextActive]}>✓ This IS a…</Text>
              </Pressable>
              <Pressable
                style={[styles.seg, !positive && styles.segActiveNeg]}
                onPress={() => setPositive(false)}
              >
                <Text style={[styles.segText, !positive && styles.segTextActive]}>✗ NOT a…</Text>
              </Pressable>
            </View>
            <Text style={styles.muted}>
              {positive
                ? 'Memeget learns this label by visual example — on-device, no retraining.'
                : 'Memeget learns this is NOT that label and pulls similar images away from it.'}
            </Text>
            <TextInput
              style={styles.input}
              value={labelInput}
              onChangeText={setLabelInput}
              placeholder={positive ? 'Label, e.g. Milady' : 'Not this label, e.g. Milady'}
              placeholderTextColor={colors.muted}
              autoFocus
            />
            {labels.length > 0 && (
              <View style={styles.suggestRow}>
                {labels.map((l) => (
                  <Pressable
                    key={l}
                    style={[styles.suggestChip, labelInput.trim() === l && styles.suggestChipActive]}
                    onPress={() => setLabelInput(l)}
                  >
                    <Text style={styles.suggestChipText}>{l}</Text>
                  </Pressable>
                ))}
              </View>
            )}
            {positive && (
              <TextInput
                style={styles.input}
                value={assocInput}
                onChangeText={setAssocInput}
                placeholder="Related terms (optional, comma-separated): remilia, nft, ethereum"
                placeholderTextColor={colors.muted}
              />
            )}
            <View style={styles.teachRow}>
              <Pressable style={[styles.teachAction, styles.teachCancel]} onPress={() => setTeaching(false)}>
                <Text style={styles.teachCancelText}>Cancel</Text>
              </Pressable>
              <Pressable
                style={[
                  styles.teachAction,
                  positive ? styles.teachSave : styles.teachSaveNeg,
                  (!labelInput.trim() || saving) && styles.disabled,
                ]}
                onPress={saveExemplar}
                disabled={!labelInput.trim() || saving}
              >
                <Text style={positive ? styles.teachSaveText : styles.teachSaveNegText}>
                  {saving ? 'Saving…' : positive ? 'Teach' : 'Mark as NOT this'}
                </Text>
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
  modalRoot: { flex: 1, backgroundColor: 'rgba(0,0,0,0.85)', justifyContent: 'center', padding: 16 },
  sheet: { backgroundColor: colors.surface, borderRadius: 16, overflow: 'hidden', maxHeight: '90%' },
  sheetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
    gap: 10,
  },
  sheetTitle: { color: colors.text, fontWeight: '700', fontSize: 14, flex: 1 },
  closeBtn: {
    width: 30,
    height: 30,
    borderRadius: 15,
    backgroundColor: colors.surface2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeIcon: { color: colors.text, fontSize: 14, fontWeight: '800' },
  preview: { width: '100%', height: 320, backgroundColor: '#000' },
  actionBar: {
    flexDirection: 'row',
    gap: 8,
    paddingHorizontal: 12,
    paddingTop: 12,
    paddingBottom: 4,
  },
  actionBtn: {
    flex: 1,
    backgroundColor: colors.surface2,
    borderRadius: 10,
    paddingVertical: 10,
    alignItems: 'center',
  },
  actionText: { color: colors.accent2, fontWeight: '700', fontSize: 13 },
  notice: { color: colors.accent2, fontSize: 12, textAlign: 'center', paddingTop: 4 },
  meta: { maxHeight: 260 },
  name: { color: colors.text, fontWeight: '700', fontSize: 14 },
  muted: { color: colors.muted, fontSize: 12 },
  sectionLabel: { color: colors.muted, fontSize: 11, textTransform: 'uppercase', marginBottom: 4 },
  debugLink: { color: colors.muted, fontSize: 12, textDecorationLine: 'underline' },
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
  teachBtnNeg: { borderColor: colors.danger },
  teachBtnNegText: { color: colors.danger, fontWeight: '700', fontSize: 13 },
  teachSheet: { backgroundColor: colors.surface, borderRadius: 16, padding: 18, gap: 12 },
  segRow: { flexDirection: 'row', backgroundColor: colors.surface2, borderRadius: 10, padding: 3, gap: 3 },
  seg: { flex: 1, paddingVertical: 9, borderRadius: 8, alignItems: 'center' },
  segActive: { backgroundColor: colors.accent2 },
  segActiveNeg: { backgroundColor: colors.danger },
  segText: { color: colors.muted, fontWeight: '700', fontSize: 13 },
  segTextActive: { color: '#0b0d12' },
  suggestRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  suggestChip: {
    backgroundColor: colors.chip,
    borderRadius: 14,
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  suggestChipActive: { borderColor: colors.accent },
  suggestChipText: { color: colors.accent, fontSize: 13, fontWeight: '600' },
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
  teachSaveNeg: { backgroundColor: colors.danger },
  teachSaveNegText: { color: '#fff', fontWeight: '800' },
  disabled: { opacity: 0.5 },
});
