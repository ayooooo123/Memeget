import React, { useEffect, useRef, useState } from 'react';
import { Image } from 'expo-image';
import {
  ActivityIndicator,
  Alert,
  Animated,
  Dimensions,
  FlatList,
  Keyboard,
  Modal,
  PanResponder,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import * as Clipboard from 'expo-clipboard';
import * as Sharing from 'expo-sharing';
import { useVideoPlayer, VideoView } from 'expo-video';

import { addExemplar, deleteMeme, getLabels, getMemeEmbedding } from '../db';
import { EXEMPLAR_PROB_THRESHOLD, headProb } from '../embeddings';
import { buildExemplarHeads, type ExemplarModel } from '../indexer';
import { success, tap, warn } from '../haptics';
import { deleteFile, materialize, readImageBase64, readVideoFrameBase64 } from '../saf';
import { colors, radius, space, TABBAR_CLEARANCE } from '../theme';
import type { MemeRecord, SearchHit } from '../types';

import { showToast } from './Toast';
import { Chip, PressableScale } from './ui';

const GAP = 3;
const COLS = 3;

type Item = MemeRecord | SearchHit;

// Track the on-screen keyboard height so the teach sheet (a bottom-anchored
// Modal) can lift itself clear of the keyboard. KeyboardAvoidingView is
// unreliable inside an Android Modal — it shares no window with the soft input —
// so we pad the sheet container by the measured height instead. iOS fires the
// `Will` events (smoother, in sync with the slide animation); Android only the
// `Did` events.
function useKeyboardHeight(): number {
  const [height, setHeight] = useState(0);
  useEffect(() => {
    const showEvt = Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow';
    const hideEvt = Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide';
    const show = Keyboard.addListener(showEvt, (e) => setHeight(e.endCoordinates.height));
    const hide = Keyboard.addListener(hideEvt, () => setHeight(0));
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);
  return height;
}

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
    <PressableScale scaleTo={0.94} onPress={() => onPress(item)} style={{ width: size, height: size }}>
      <Image
        source={{ uri: item.uri }}
        style={styles.thumb}
        contentFit="cover"
        transition={150}
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
    </PressableScale>
  );
});

export function MemeGrid({
  items,
  header,
  onTaught,
  onEndReached,
  loadingMore,
  onDeleted,
  onSearchLabel,
  emptyState,
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
  // Called after a meme is deleted so the parent can drop it from its list.
  onDeleted?: (id: number) => void;
  // Tap a tag in the viewer to jump to a search for it. Optional.
  onSearchLabel?: (label: string) => void;
  // Rendered when items is empty (e.g. a "no results" state).
  emptyState?: React.ReactElement | null;
}) {
  const [selected, setSelected] = useState<Item | null>(null);
  const [teaching, setTeaching] = useState(false);
  const [labelInput, setLabelInput] = useState('');
  const [assocInput, setAssocInput] = useState('');
  const [positive, setPositive] = useState(true);
  const [labels, setLabels] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [busy, setBusy] = useState(false);
  const [matchInfo, setMatchInfo] = useState<{ label: string; score: number }[] | null>(null);
  const [matchBusy, setMatchBusy] = useState(false);
  // Cache the trained heads so we don't retrain on every modal open; cleared
  // after teaching so the next open reflects the new example.
  const modelRef = useRef<ExemplarModel | null>(null);
  const size = (Dimensions.get('window').width - GAP * (COLS + 1)) / COLS;
  const kbHeight = useKeyboardHeight();

  const openViewer = (it: Item) => {
    tap();
    setSelected(it);
  };

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

  // Share the original file to any other app (Discord, Telegram, Photos…) — the
  // fastest way to get a meme onto another platform on mobile.
  const onShare = async () => {
    if (!selected || busy) return;
    setBusy(true);
    try {
      if (!(await Sharing.isAvailableAsync())) {
        showToast('This device has no share targets', 'error');
        return;
      }
      const path = await materialize(selected.uri, selected.name);
      await Sharing.shareAsync(path);
    } catch (e) {
      showToast(`Could not share: ${String(e)}`, 'error');
    } finally {
      setBusy(false);
    }
  };

  const onCopy = async () => {
    if (!selected || busy) return;
    const isVideo = selected.kind === 'video';
    setBusy(true);
    try {
      // Images copy as-is; videos copy a representative still frame, since the
      // system clipboard can't hold a video file.
      const base64 = isVideo
        ? await readVideoFrameBase64(selected.uri, selected.name)
        : await readImageBase64(selected.uri, selected.name);
      await Clipboard.setImageAsync(base64);
      success();
      showToast(isVideo ? 'Frame copied — paste it anywhere' : 'Meme copied — paste it anywhere', 'success');
    } catch (e) {
      showToast(`Could not copy: ${String(e)}`, 'error');
    } finally {
      setBusy(false);
    }
  };

  const onCopyText = async () => {
    if (!selected?.ocrText) return;
    await Clipboard.setStringAsync(selected.ocrText);
    success();
    showToast('Text copied', 'success');
  };

  const onDelete = () => {
    const item = selected;
    if (!item || busy) return;
    warn();
    Alert.alert(
      'Delete meme?',
      `This removes “${item.name}” from your library and deletes the file from its folder. This can’t be undone.`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: async () => {
            setBusy(true);
            try {
              await deleteMeme(item.id);
              await deleteFile(item.uri).catch(() => {}); // best-effort; DB row is gone regardless
              setSelected(null);
              onDeleted?.(item.id);
              showToast('Meme deleted', 'info');
            } catch (e) {
              showToast(`Could not delete: ${String(e)}`, 'error');
            } finally {
              setBusy(false);
            }
          },
        },
      ]
    );
  };

  const openTeach = (asPositive: boolean, preset?: string) => {
    tap();
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
        showToast('Could not teach: no stored embedding for this item', 'error');
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
      success();
      if (typeof matched === 'number') {
        showToast(
          positive
            ? `Taught “${label}” — ${matched} meme${matched === 1 ? '' : 's'} now tagged` +
                (matched <= 1 ? '. Teach a few more examples to catch the rest' : '')
            : `Got it — NOT “${label}”. ${matched} meme${matched === 1 ? '' : 's'} still carry the tag`,
          'success'
        );
      } else {
        showToast(
          positive ? `Taught “${label}” — re-tag in Settings to apply it` : `Correction for “${label}” saved`,
          'success'
        );
      }
    } catch (e) {
      showToast(`Could not teach: ${String(e)}`, 'error');
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
        ListEmptyComponent={emptyState ?? null}
        columnWrapperStyle={{ gap: GAP, paddingHorizontal: GAP }}
        contentContainerStyle={{ gap: GAP, paddingBottom: TABBAR_CLEARANCE + 24 }}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
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
          loadingMore ? <ActivityIndicator color={colors.volt} style={{ paddingVertical: 16 }} /> : null
        }
        renderItem={({ item }) => <GridCell item={item} size={size} onPress={openViewer} />}
      />

      <ViewerSheet
        item={selected}
        busy={busy}
        matchInfo={matchInfo}
        matchBusy={matchBusy}
        onClose={() => setSelected(null)}
        onShare={onShare}
        onCopy={onCopy}
        onCopyText={onCopyText}
        onDelete={onDelete}
        onTeach={openTeach}
        onShowConfidence={computeMatchInfo}
        onSearchLabel={
          onSearchLabel
            ? (label) => {
                setSelected(null);
                onSearchLabel(label);
              }
            : undefined
        }
      />

      <Modal
        visible={teaching}
        transparent
        animationType="slide"
        statusBarTranslucent
        onRequestClose={() => setTeaching(false)}
      >
        <View style={[styles.teachRoot, { paddingBottom: kbHeight }]}>
          <Pressable style={StyleSheet.absoluteFill} onPress={() => setTeaching(false)} />
          <View style={styles.teachSheet}>
            <View style={styles.grabber} />
            <Text style={styles.sheetHeading}>Teach Memeget</Text>
            <View style={styles.segRow}>
              <Pressable
                style={[styles.seg, positive && styles.segActive]}
                onPress={() => setPositive(true)}
              >
                <Text style={[styles.segText, positive && styles.segTextOn]}>✓ This IS a…</Text>
              </Pressable>
              <Pressable
                style={[styles.seg, !positive && styles.segActiveNeg]}
                onPress={() => setPositive(false)}
              >
                <Text style={[styles.segText, !positive && styles.segTextOnNeg]}>✗ NOT a…</Text>
              </Pressable>
            </View>
            <Text style={styles.teachHint}>
              {positive
                ? 'Learns this label by visual example — on-device, instantly.'
                : 'Learns this is NOT that label and pulls similar images away from it.'}
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
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.suggestRow}
                keyboardShouldPersistTaps="handled"
              >
                {labels.map((l) => (
                  <Chip key={l} label={l} active={labelInput.trim() === l} onPress={() => setLabelInput(l)} />
                ))}
              </ScrollView>
            )}
            {positive && (
              <TextInput
                style={styles.input}
                value={assocInput}
                onChangeText={setAssocInput}
                placeholder="Related search terms, comma-separated (optional)"
                placeholderTextColor={colors.muted}
              />
            )}
            <View style={styles.teachActions}>
              <PressableScale style={[styles.teachAction, styles.teachCancel]} onPress={() => setTeaching(false)}>
                <Text style={styles.teachCancelText}>Cancel</Text>
              </PressableScale>
              <PressableScale
                style={[styles.teachAction, positive ? styles.teachSave : styles.teachSaveNeg]}
                onPress={saveExemplar}
                disabled={!labelInput.trim() || saving}
              >
                <Text style={positive ? styles.teachSaveText : styles.teachSaveNegText}>
                  {saving ? 'Saving…' : positive ? 'Teach' : 'Mark as NOT this'}
                </Text>
              </PressableScale>
            </View>
          </View>
        </View>
      </Modal>
    </>
  );
}

// Full-width bottom sheet viewer with a drag-to-dismiss handle.
function ViewerSheet({
  item,
  busy,
  matchInfo,
  matchBusy,
  onClose,
  onShare,
  onCopy,
  onCopyText,
  onDelete,
  onTeach,
  onShowConfidence,
  onSearchLabel,
}: {
  item: Item | null;
  busy: boolean;
  matchInfo: { label: string; score: number }[] | null;
  matchBusy: boolean;
  onClose: () => void;
  onShare: () => void;
  onCopy: () => void;
  onCopyText: () => void;
  onDelete: () => void;
  onTeach: (positive: boolean, preset?: string) => void;
  onShowConfidence: () => void;
  onSearchLabel?: (label: string) => void;
}) {
  const drag = useRef(new Animated.Value(0)).current;

  // Drag-to-dismiss on the grab area only, so scrolling the metadata or
  // pinch-looking at the image never accidentally closes the sheet.
  const pan = useRef(
    PanResponder.create({
      onMoveShouldSetPanResponder: (_e, g) => g.dy > 6 && Math.abs(g.dy) > Math.abs(g.dx),
      onPanResponderMove: (_e, g) => drag.setValue(Math.max(0, g.dy)),
      onPanResponderRelease: (_e, g) => {
        if (g.dy > 110 || g.vy > 1.2) {
          Animated.timing(drag, { toValue: 600, duration: 160, useNativeDriver: true }).start(() => {
            drag.setValue(0);
            onClose();
          });
        } else {
          Animated.spring(drag, { toValue: 0, useNativeDriver: true, speed: 30, bounciness: 4 }).start();
        }
      },
    })
  ).current;

  useEffect(() => {
    if (item) drag.setValue(0);
  }, [item, drag]);

  const imgHeight = Math.round(Dimensions.get('window').height * 0.42);

  return (
    <Modal
      visible={!!item}
      transparent
      animationType="slide"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      <View style={styles.viewerRoot}>
        {/* Backdrop sits BEHIND the sheet: only taps that land outside the
            sheet reach it, so scrolling/teaching/selecting never dismisses. */}
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} />
        {item && (
          <Animated.View style={[styles.sheet, { transform: [{ translateY: drag }] }]}>
            <View {...pan.panHandlers} style={styles.sheetHeader}>
              <View style={styles.grabber} />
              <View style={styles.sheetTitleRow}>
                <Text style={styles.sheetTitle} numberOfLines={1} selectable>
                  {item.name}
                </Text>
                <Pressable onPress={onClose} hitSlop={12} style={styles.closeBtn} accessibilityLabel="Close">
                  <Text style={styles.closeIcon}>✕</Text>
                </Pressable>
              </View>
            </View>

            {item.kind === 'video' ? (
              <VideoPreview key={item.id} uri={item.uri} height={imgHeight} />
            ) : (
              <Image
                source={{ uri: item.uri }}
                style={[styles.preview, { height: imgHeight }]}
                contentFit="contain"
                recyclingKey={String(item.id)}
                allowDownscaling
              />
            )}

            <View style={styles.actionBar}>
              <ActionButton glyph="⤴" label={busy ? '…' : 'Share'} onPress={onShare} disabled={busy} />
              <ActionButton glyph="⧉" label="Copy" onPress={onCopy} disabled={busy} />
              {!!item.ocrText && <ActionButton glyph="🆎" label="Text" onPress={onCopyText} />}
              <ActionButton glyph="🗑" label="Delete" danger onPress={onDelete} disabled={busy} />
            </View>

            <ScrollView
              style={styles.meta}
              contentContainerStyle={styles.metaContent}
              keyboardShouldPersistTaps="handled"
            >
              {'score' in item && (
                <Text style={styles.matchScore}>
                  match {Math.min(100, item.score * 100).toFixed(0)}%
                </Text>
              )}

              {item.tags.length > 0 && (
                <View style={styles.block}>
                  <Text style={styles.sectionLabel}>
                    {onSearchLabel ? 'Tags · tap to search · hold to correct' : 'Tags · tap to correct'}
                  </Text>
                  <View style={styles.tagWrap}>
                    {item.tags.map((t) => (
                      <Chip
                        key={t.label}
                        label={t.label}
                        taught={t.source === 'exemplar'}
                        onPress={
                          onSearchLabel ? () => onSearchLabel(t.label) : () => onTeach(false, t.label)
                        }
                        onLongPress={() => onTeach(false, t.label)}
                      />
                    ))}
                  </View>
                </View>
              )}

              {!!item.ocrText && (
                <View style={styles.block}>
                  <Text style={styles.sectionLabel}>Text in meme · long-press to copy</Text>
                  <Text style={styles.ocr} selectable>
                    {item.ocrText}
                  </Text>
                </View>
              )}

              <View style={styles.teachRow}>
                <PressableScale style={styles.teachBtn} onPress={() => onTeach(true)}>
                  <Text style={styles.teachBtnText}>＋ This IS a…</Text>
                </PressableScale>
                <PressableScale style={[styles.teachBtn, styles.teachBtnNeg]} onPress={() => onTeach(false)}>
                  <Text style={styles.teachBtnNegText}>✗ This is NOT a…</Text>
                </PressableScale>
              </View>

              {matchInfo === null ? (
                <Pressable onPress={onShowConfidence} disabled={matchBusy}>
                  <Text style={styles.debugLink}>
                    {matchBusy ? 'Scoring…' : 'Show taught-label confidence (debug)'}
                  </Text>
                </Pressable>
              ) : matchInfo.length > 0 ? (
                <View style={styles.block}>
                  <Text style={styles.sectionLabel}>Taught-label confidence (debug)</Text>
                  {matchInfo.map((m) => (
                    <Text key={m.label} style={styles.mutedSmall}>
                      {m.label}: {(m.score * 100).toFixed(0)}%{' '}
                      {m.score >= EXEMPLAR_PROB_THRESHOLD ? '✓ match' : ''}
                    </Text>
                  ))}
                </View>
              ) : (
                <Text style={styles.mutedSmall}>No taught labels yet.</Text>
              )}
            </ScrollView>
          </Animated.View>
        )}
      </View>
    </Modal>
  );
}

// In-viewer player for video memes. expo-video drives Android's ExoPlayer,
// which streams the SAF content:// uri directly (same uri we store + display),
// so there's no need to materialize a temp file just to watch it. The player is
// created per-uri and released automatically when the sheet unmounts; keying the
// element by item.id guarantees a fresh player when you swipe to another video.
function VideoPreview({ uri, height }: { uri: string; height: number }) {
  const player = useVideoPlayer(uri, (p) => {
    p.loop = true;
    p.play();
  });

  return (
    <VideoView
      style={[styles.preview, { height }]}
      player={player}
      contentFit="contain"
      nativeControls
      fullscreenOptions={{ enable: true }}
      allowsPictureInPicture={false}
    />
  );
}

function ActionButton({
  glyph,
  label,
  onPress,
  disabled,
  danger,
}: {
  glyph: string;
  label: string;
  onPress: () => void;
  disabled?: boolean;
  danger?: boolean;
}) {
  return (
    <PressableScale scaleTo={0.9} style={styles.action} onPress={onPress} disabled={disabled}>
      <View style={[styles.actionCircle, danger && styles.actionCircleDanger]}>
        <Text style={[styles.actionGlyph, danger && styles.actionGlyphDanger]}>{glyph}</Text>
      </View>
      <Text style={[styles.actionLabel, danger && styles.actionLabelDanger]}>{label}</Text>
    </PressableScale>
  );
}

const styles = StyleSheet.create({
  thumb: { width: '100%', height: '100%', backgroundColor: colors.surface2, borderRadius: radius.sm },
  play: {
    position: 'absolute',
    right: 6,
    bottom: 6,
    backgroundColor: 'rgba(0,0,0,0.65)',
    borderRadius: radius.pill,
    paddingHorizontal: 7,
    paddingVertical: 2,
  },
  playIcon: { color: '#fff', fontSize: 10 },

  viewerRoot: { flex: 1, backgroundColor: colors.overlay, justifyContent: 'flex-end' },
  sheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    overflow: 'hidden',
    maxHeight: '94%',
    borderWidth: 1,
    borderBottomWidth: 0,
    borderColor: colors.borderLight,
  },
  sheetHeader: { paddingTop: 8, paddingBottom: 4 },
  grabber: {
    alignSelf: 'center',
    width: 38,
    height: 4,
    borderRadius: 2,
    backgroundColor: colors.borderLight,
    marginBottom: 8,
  },
  sheetTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: space.lg,
    gap: 10,
  },
  sheetTitle: { color: colors.textDim, fontWeight: '600', fontSize: 13, flex: 1 },
  closeBtn: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: colors.surface3,
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeIcon: { color: colors.text, fontSize: 13, fontWeight: '800' },
  preview: { width: '100%', backgroundColor: '#000' },

  actionBar: {
    flexDirection: 'row',
    justifyContent: 'space-evenly',
    paddingVertical: space.md,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  action: { alignItems: 'center', gap: 5, minWidth: 64 },
  actionCircle: {
    width: 46,
    height: 46,
    borderRadius: 23,
    backgroundColor: colors.surface2,
    borderWidth: 1,
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  actionCircleDanger: { backgroundColor: colors.dangerDim, borderColor: colors.danger },
  actionGlyph: { color: colors.volt, fontSize: 18 },
  actionGlyphDanger: { color: colors.danger, fontSize: 16 },
  actionLabel: { color: colors.muted, fontSize: 11, fontWeight: '600' },
  actionLabelDanger: { color: colors.danger },

  meta: { flexGrow: 0 },
  metaContent: { padding: space.lg, gap: space.md, paddingBottom: space.xl },
  matchScore: { color: colors.accent, fontSize: 12, fontWeight: '700' },
  block: { gap: 8 },
  sectionLabel: {
    color: colors.faint,
    fontSize: 11,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  tagWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  ocr: { color: colors.textDim, fontSize: 13, lineHeight: 19 },
  mutedSmall: { color: colors.muted, fontSize: 12 },
  debugLink: { color: colors.faint, fontSize: 12, textDecorationLine: 'underline' },

  teachRow: { flexDirection: 'row', gap: space.sm },
  teachBtn: {
    flex: 1,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
    borderRadius: radius.md,
    paddingVertical: 11,
    alignItems: 'center',
  },
  teachBtnText: { color: colors.good, fontWeight: '700', fontSize: 13 },
  teachBtnNeg: { borderColor: colors.danger, backgroundColor: 'transparent' },
  teachBtnNegText: { color: colors.danger, fontWeight: '700', fontSize: 13 },

  teachRoot: { flex: 1, backgroundColor: colors.scrim, justifyContent: 'flex-end' },
  teachSheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    borderWidth: 1,
    borderBottomWidth: 0,
    borderColor: colors.borderLight,
    padding: space.lg,
    paddingTop: 10,
    gap: space.md,
  },
  sheetHeading: { color: colors.text, fontWeight: '800', fontSize: 17, letterSpacing: -0.3 },
  segRow: {
    flexDirection: 'row',
    backgroundColor: colors.surface2,
    borderRadius: radius.md,
    padding: 3,
    gap: 3,
  },
  seg: { flex: 1, paddingVertical: 10, borderRadius: radius.md - 3, alignItems: 'center' },
  segActive: { backgroundColor: colors.volt },
  segActiveNeg: { backgroundColor: colors.danger },
  segText: { color: colors.muted, fontWeight: '700', fontSize: 13 },
  segTextOn: { color: colors.onVolt },
  segTextOnNeg: { color: '#fff' },
  teachHint: { color: colors.muted, fontSize: 12, lineHeight: 17 },
  suggestRow: { gap: 8, paddingVertical: 2 },
  input: {
    backgroundColor: colors.surface2,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingHorizontal: 14,
    paddingVertical: 11,
    color: colors.text,
    fontSize: 14,
  },
  teachActions: { flexDirection: 'row', gap: space.sm, marginTop: 2, marginBottom: 6 },
  teachAction: { flex: 1, paddingVertical: 13, borderRadius: radius.md, alignItems: 'center' },
  teachCancel: { borderWidth: 1, borderColor: colors.border },
  teachCancelText: { color: colors.textDim, fontWeight: '700' },
  teachSave: { backgroundColor: colors.volt },
  teachSaveText: { color: colors.onVolt, fontWeight: '800' },
  teachSaveNeg: { backgroundColor: colors.danger },
  teachSaveNegText: { color: '#fff', fontWeight: '800' },
});
