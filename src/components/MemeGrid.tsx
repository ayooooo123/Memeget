import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Image } from 'expo-image';
import {
  ActivityIndicator,
  Alert,
  Animated,
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
  useWindowDimensions,
  View,
} from 'react-native';

import * as Clipboard from 'expo-clipboard';
import * as Sharing from 'expo-sharing';
import { useVideoPlayer, VideoView } from 'expo-video';

import { addExemplar, deleteMeme, getLabels, getMemeEmbedding, getSimilarMemes } from '../db';
import { scoreExemplar } from '../learnCore';
import { buildExemplarHeads, noteInteractive, type ExemplarModel } from '../indexer';
import { success, tap, warn } from '../haptics';
import { copyFileToClipboard } from '../../modules/memeget-bg';
import { deleteFile, materialize, readImageBase64, readVideoFrameBase64, videoMimeFor } from '../saf';
import { colors, radius, shadow, space, TABBAR_CLEARANCE } from '../theme';
import { useConst } from '../reactUtils';
import type { MemeRecord, SearchHit } from '../types';

import { showToast } from './Toast';
import { Chip, PressableScale } from './ui';

const GAP = 3;
const COLS = 3;

// How far (px) the grid must be scrolled before the back-to-top button shows —
// roughly a couple of screens, where flick-scrolling back up gets tedious.
const SHOW_TOP_AFTER = 1400;

// Similarity floor for the "also these?" confirm step after a teach — below
// this, CLIP cosine is background noise and the sheet would pad itself with
// unrelated memes.
const CONFIRM_MIN_COSINE = 0.6;

type Item = MemeRecord | SearchHit;

// What an <Image> thumbnail should load for an item: videos use their persisted
// poster jpeg when one exists — the image view can't decode a frame from every
// video codec, and "mp4 gif" style files rendered blank straight off their
// content:// uri. Falls back to the original uri (images; videos not yet
// backfilled, where a decodable codec still shows a frame like before).
function thumbSource(item: Pick<Item, 'kind' | 'uri' | 'thumbUri'>): string {
  return item.kind === 'video' && item.thumbUri ? item.thumbUri : item.uri;
}

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
        source={{ uri: thumbSource(item) }}
        style={styles.thumb}
        contentFit="cover"
        transition={150}
        // Reuse the view and release the previous bitmap when a cell is
        // recycled (e.g. when retagAll hands the list a fresh array).
        recyclingKey={String(item.id)}
        // Memory-only cache (NOT "disk"). The originals already live as local
        // content:// files in the user's linked folder, so a disk cache just
        // duplicates the entire library into the app's cache dir — it ballooned
        // cache to library size, and once Android purged that cache the
        // thumbnails got stranded in a perpetual loading state. Disk-only also
        // meant no in-memory bitmaps, so every recycled cell re-decoded a
        // full-res image off disk while scrolling, saturating the decode thread
        // (the "feed won't load while scrolling" jank). The in-memory LRU keeps
        // the active window smooth; off-screen cells decode again from the local
        // file — cheap because allowDownscaling decodes straight to thumb size.
        cachePolicy="memory"
        allowDownscaling
      />
      {item.kind === 'video' && (
        <View style={styles.play}>
          <Text style={styles.playIcon}>▶</Text>
        </View>
      )}
      {'pending' in item && item.pending && (
        <View style={styles.pending}>
          <ActivityIndicator color="#fff" size="small" />
        </View>
      )}
    </PressableScale>
  );
});

// Memoized so a re-render of the owning screen that leaves every prop unchanged
// (notably the CLIP model's load-progress ticks right after an app update, which
// re-render LibraryScreen many times a second) can't re-run the whole grid and
// stutter an in-progress scroll. Relies on the parent keeping `header`,
// `emptyState`, and the callbacks referentially stable across those re-renders.
export const MemeGrid = React.memo(function MemeGrid({
  items,
  header,
  onTaught,
  onEndReached,
  loadingMore,
  onDeleted,
  onSearchLabel,
  emptyState,
  scrollToTopSignal,
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
  // Bumped by the parent each time a new search runs; when it changes we jump
  // the list back to the top so fresh results are visible immediately instead
  // of stranding the user wherever they'd scrolled to while browsing.
  scrollToTopSignal?: number;
}) {
  const [selected, setSelected] = useState<Item | null>(null);
  const [teaching, setTeaching] = useState(false);
  const [labelInput, setLabelInput] = useState('');
  const [assocInput, setAssocInput] = useState('');
  const [positive, setPositive] = useState(true);
  const [labels, setLabels] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  // Step 2 of a positive teach: "these look similar — are they also <label>?".
  // Confirming candidates turns one teach into several exemplars, which is what
  // actually makes a taught label reliable (a single example rarely is).
  const [confirming, setConfirming] = useState<{
    label: string;
    candidates: SearchHit[];
    picked: number[]; // meme ids the user has tapped
  } | null>(null);
  const [confirmSaving, setConfirmSaving] = useState(false);
  const [busy, setBusy] = useState(false);
  const [matchInfo, setMatchInfo] = useState<
    { label: string; score: number; matched: boolean }[] | null
  >(null);
  const [matchBusy, setMatchBusy] = useState(false);
  // Cache the trained heads so we don't retrain on every modal open; cleared
  // after teaching so the next open reflects the new example.
  const modelRef = useRef<ExemplarModel | null>(null);
  // Applying a taught example across the whole library (retrain heads + re-tag
  // everything) is heavy and runs detached from the teach button. Serialize the
  // applies through one promise chain so a quick second teach can't kick off a
  // second retag while the first is still inside its DB transaction.
  // Starts null and is seeded on first use, so render doesn't allocate a
  // throwaway resolved Promise every pass.
  const applyChainRef = useRef<Promise<void> | null>(null);
  // useWindowDimensions re-renders on rotation/resize, so the grid reflows
  // correctly instead of being stuck at the launch width.
  const { width: winWidth } = useWindowDimensions();
  const size = (winWidth - GAP * (COLS + 1)) / COLS;
  const kbHeight = useKeyboardHeight();
  const listRef = useRef<FlatList>(null);

  // Whenever the parent signals a new search, snap back to the top so the
  // results (and the "N results" header) are in view right away. Skipped on the
  // initial mount — the list already starts at the top and an empty list can't
  // be scrolled yet.
  const didMountScroll = useRef(false);
  useEffect(() => {
    if (!didMountScroll.current) {
      didMountScroll.current = true;
      return;
    }
    listRef.current?.scrollToOffset({ offset: 0, animated: true });
  }, [scrollToTopSignal]);

  // Back-to-top button: appears once the user is deep in the grid, fades out
  // near the top. State only flips at the threshold crossing (the ref is the
  // per-scroll-event gate), so scrolling doesn't re-render the grid every frame.
  const [showTopBtn, setShowTopBtn] = useState(false);
  const showTopRef = useRef(false);
  const fabAnim = useConst(() => new Animated.Value(0));
  useEffect(() => {
    Animated.timing(fabAnim, {
      toValue: showTopBtn ? 1 : 0,
      duration: 180,
      useNativeDriver: true,
    }).start();
  }, [showTopBtn, fabAnim]);
  const onScroll = useCallback(
    (e: { nativeEvent: { contentOffset: { y: number } } }) => {
      const show = e.nativeEvent.contentOffset.y > SHOW_TOP_AFTER;
      if (show !== showTopRef.current) {
        showTopRef.current = show;
        setShowTopBtn(show);
      }
    },
    []
  );
  const scrollToTop = useCallback(() => {
    tap();
    listRef.current?.scrollToOffset({ offset: 0, animated: true });
  }, []);

  // Stable across renders so the memoized GridCells aren't all re-rendered
  // every time unrelated grid state changes (opening the viewer, toggling
  // `loadingMore` during pagination, teaching). An unstable onPress here was
  // defeating React.memo and re-flashing every visible thumbnail — the
  // flicker you'd see right as the next page loaded in.
  const openViewer = useCallback((it: Item) => {
    tap();
    // The viewer is interactive foreground work: stand the background loops
    // down (they hold hardware codecs the video preview / frame-copy need).
    noteInteractive();
    setSelected(it);
  }, []);

  // Likewise kept stable so they don't bust GridCell's React.memo on every
  // render. renderItem only depends on the constant cell `size` and openViewer.
  const keyExtractor = useCallback((it: Item) => String(it.id), []);
  const renderItem = useCallback(
    ({ item }: { item: Item }) => <GridCell item={item} size={size} onPress={openViewer} />,
    [size, openViewer]
  );

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
      const raw = Array.from(emb);
      const centered = model.mean ? Array.from(emb, (v, i) => v - model.mean![i]) : raw;
      const scored = model.heads
        .map((h) => {
          const s = scoreExemplar(h, raw, centered);
          return { label: h.label, score: s.prob, matched: s.matched };
        })
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
    noteInteractive();
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
    // Frame extraction needs a hardware decoder; make the background loops
    // yield theirs before we try (retries inside cover the in-flight one).
    noteInteractive();
    setBusy(true);
    try {
      if (isVideo) {
        // Put the actual video file on the clipboard as a content:// uri (the
        // memeget-bg native module — expo-clipboard can only hold images).
        // Apps that accept rich pastes receive the full video; if the module
        // isn't built in or the copy fails, fall through to the still frame.
        const copied = await copyFileToClipboard(
          selected.uri,
          selected.name,
          videoMimeFor(selected.name)
        ).catch(() => false);
        if (copied) {
          success();
          showToast('Video copied — paste it in apps that accept videos', 'success');
          return;
        }
      }
      // Images copy as-is; videos fall back to a representative still frame.
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

  // Backdrop / back button on the teach modal. During the confirm step this is
  // a "skip": the first exemplar is already saved, so the deferred apply must
  // still run — only the extra picks are dropped.
  const closeTeachModal = () => {
    if (confirming) {
      if (!confirmSaving) finishConfirm([]);
    } else {
      setTeaching(false);
    }
  };

  // Label ideas for the teach sheet: the open meme's own tags first (you're
  // usually naming something the indexer already half-recognized — one tap
  // promotes the guess to ground truth), then every previously taught label.
  const suggestions = selected
    ? [...new Set([...selected.tags.map((t) => t.label), ...labels])]
    : labels;

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

  // Apply taught examples across the library off the button: retrain heads and
  // re-tag everything. Chained so concurrent teaches run one retag at a time —
  // the teach buttons are gated only on persisting the exemplar (fast), never on
  // this heavy pass, so they don't sit frozen while the library reclassifies.
  const applyTeach = (label: string, taughtPositive: boolean, examples: number) => {
    modelRef.current = null; // new example(s) → retrain heads on next open
    applyChainRef.current = (applyChainRef.current ?? Promise.resolve())
      .catch(() => {})
      .then(async () => {
        try {
          const matched = onTaught ? await onTaught(label) : undefined;
          if (typeof matched === 'number') {
            showToast(
              taughtPositive
                ? `Taught “${label}” from ${examples} example${examples === 1 ? '' : 's'} — ` +
                    `${matched} meme${matched === 1 ? '' : 's'} now tagged` +
                    (examples === 1 && matched <= 1 ? '. Teach a few more examples to catch the rest' : '')
                : `Got it — NOT “${label}”. ${matched} meme${matched === 1 ? '' : 's'} still carry the tag`,
              'success'
            );
          } else {
            showToast(
              taughtPositive ? `Taught “${label}” — re-tag in Settings to apply it` : `Correction for “${label}” saved`,
              'success'
            );
          }
        } catch (e) {
          showToast(`Saved “${label}”, but applying it failed: ${String(e)}`, 'error');
        }
      });
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
    } catch (e) {
      showToast(`Could not teach: ${String(e)}`, 'error');
      return;
    } finally {
      setSaving(false);
    }

    // Exemplar saved. `positive` is captured now because the sheet (and its
    // state) may be reused before the detached apply runs.
    const taughtPositive = positive;
    setTeaching(false);
    success();

    // For a positive teach, offer step 2: visually similar memes the user can
    // confirm as further examples in one tap each. The apply is deferred until
    // that step closes so the whole round costs a single library re-tag.
    if (taughtPositive) {
      try {
        const candidates = (await getSimilarMemes(selected.id, 12)).filter(
          (s) => s.score >= CONFIRM_MIN_COSINE
        );
        if (candidates.length > 0) {
          setConfirming({ label, candidates, picked: [] });
          return;
        }
      } catch {
        // candidate fetch is best-effort — fall through to a normal apply
      }
    }
    applyTeach(label, taughtPositive, 1);
  };

  const togglePick = (id: number) => {
    tap();
    setConfirming((cur) =>
      cur
        ? {
            ...cur,
            picked: cur.picked.includes(id) ? cur.picked.filter((p) => p !== id) : [...cur.picked, id],
          }
        : cur
    );
  };

  // Close the confirm step: persist the picked memes as extra exemplars, then
  // run the single deferred apply for everything taught this round. `picks` is
  // explicit so Skip/backdrop pass [] while the confirm button passes the
  // current selection (no race against state updates).
  const finishConfirm = async (picks: number[]) => {
    const c = confirming;
    if (!c || confirmSaving) return;
    setConfirmSaving(true);
    let added = 0;
    try {
      for (const id of picks) {
        const hit = c.candidates.find((s) => s.id === id);
        const emb = await getMemeEmbedding(id);
        if (!hit || !emb) continue;
        await addExemplar({
          label: c.label,
          category: 'character',
          vector: Array.from(emb),
          associations: [], // world-knowledge terms were captured with the first example
          sourceUri: hit.uri,
          positive: true,
        });
        added++;
      }
    } catch (e) {
      showToast(`Some examples failed to save: ${String(e)}`, 'error');
    } finally {
      setConfirmSaving(false);
      setConfirming(null);
    }
    if (added > 0) success();
    applyTeach(c.label, true, 1 + added);
  };

  // One-tap positive teach from a tag chip's long-press menu — the fastest way
  // to turn a model guess (CLIP/VLM) into the user's own ground truth.
  const confirmTag = async (label: string) => {
    if (!selected) return;
    try {
      const emb = await getMemeEmbedding(selected.id);
      if (!emb) {
        showToast('Could not teach: no stored embedding for this item', 'error');
        return;
      }
      await addExemplar({
        label,
        category: 'character',
        vector: Array.from(emb),
        associations: [],
        sourceUri: selected.uri,
        positive: true,
      });
      success();
      applyTeach(label, true, 1);
    } catch (e) {
      showToast(`Could not teach: ${String(e)}`, 'error');
    }
  };

  return (
    <>
      <View style={styles.gridWrap}>
      <FlatList
        ref={listRef}
        data={items}
        keyExtractor={keyExtractor}
        numColumns={COLS}
        ListHeaderComponent={header}
        ListEmptyComponent={emptyState ?? null}
        columnWrapperStyle={styles.column}
        contentContainerStyle={styles.listContent}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
        onScroll={onScroll}
        scrollEventThrottle={64}
        onEndReached={onEndReached}
        onEndReachedThreshold={0.6}
        // Memory is already bounded by `windowSize`: FlatList only keeps that
        // many viewports of cells mounted regardless. We deliberately do NOT set
        // `removeClippedSubviews` — on Android it blanks/flickers expo-image
        // cells as they scroll back into view (a known FlatList grid bug) and is
        // redundant with the windowing below. Render a little ahead of the
        // scroll so fast flings don't outrun cell rendering into blank gaps.
        initialNumToRender={18}
        maxToRenderPerBatch={12}
        windowSize={7}
        updateCellsBatchingPeriod={50}
        ListFooterComponent={
          loadingMore ? <ActivityIndicator color={colors.volt} style={{ paddingVertical: 16 }} /> : null
        }
        renderItem={renderItem}
      />

      {/* Floating back-to-top button. pointerEvents flips with visibility so a
          fully faded-out button can never swallow taps meant for the grid. */}
      <Animated.View
        pointerEvents={showTopBtn ? 'auto' : 'none'}
        style={[
          styles.topFabWrap,
          {
            opacity: fabAnim,
            transform: [
              { scale: fabAnim.interpolate({ inputRange: [0, 1], outputRange: [0.8, 1] }) },
            ],
          },
        ]}
      >
        <PressableScale
          scaleTo={0.88}
          onPress={scrollToTop}
          style={styles.topFab}
          accessibilityRole="button"
          accessibilityLabel="Back to top"
        >
          <Text style={styles.topFabIcon}>↑</Text>
        </PressableScale>
      </Animated.View>
      </View>

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
        onConfirmTag={confirmTag}
        onShowConfidence={computeMatchInfo}
        onSearchLabel={
          onSearchLabel
            ? (label) => {
                setSelected(null);
                onSearchLabel(label);
              }
            : undefined
        }
        onSelectItem={(hit) => {
          tap();
          // Strip the similarity score before it becomes the viewed item, so
          // the viewer doesn't show a bogus "match N%" (that readout is for
          // text-search results).
          const { score, ...rec } = hit;
          setSelected(rec as MemeRecord);
        }}
      />

      <Modal
        visible={teaching || confirming !== null}
        transparent
        animationType="slide"
        statusBarTranslucent
        onRequestClose={closeTeachModal}
      >
        <View style={[styles.teachRoot, { paddingBottom: kbHeight }]}>
          <Pressable style={StyleSheet.absoluteFill} onPress={closeTeachModal} />
          {confirming ? (
            <View style={styles.teachSheet}>
              <View style={styles.grabber} />
              <Text style={styles.sheetHeading}>More “{confirming.label}”?</Text>
              <Text style={styles.teachHint}>
                These look similar. Tap the ones that are also “{confirming.label}” — every
                confirmation becomes another example and makes the label sharper.
              </Text>
              <View style={styles.confirmGrid}>
                {confirming.candidates.map((s) => {
                  const picked = confirming.picked.includes(s.id);
                  return (
                    <Pressable key={s.id} onPress={() => togglePick(s.id)} style={styles.confirmCell}>
                      <Image
                        source={{ uri: thumbSource(s) }}
                        style={[styles.confirmThumb, picked && styles.confirmThumbOn]}
                        contentFit="cover"
                        recyclingKey={`conf-${s.id}`}
                        cachePolicy="memory"
                        allowDownscaling
                      />
                      {picked && (
                        <View style={styles.confirmCheck}>
                          <Text style={styles.confirmCheckText}>✓</Text>
                        </View>
                      )}
                    </Pressable>
                  );
                })}
              </View>
              <View style={styles.teachActions}>
                <PressableScale
                  style={[styles.teachAction, styles.teachCancel]}
                  onPress={() => finishConfirm([])}
                  disabled={confirmSaving}
                >
                  <Text style={styles.teachCancelText}>Skip</Text>
                </PressableScale>
                <PressableScale
                  style={[
                    styles.teachAction,
                    styles.teachSave,
                    confirming.picked.length === 0 && styles.teachActionDisabled,
                  ]}
                  onPress={() => finishConfirm(confirming.picked)}
                  disabled={confirmSaving || confirming.picked.length === 0}
                >
                  <Text style={styles.teachSaveText}>
                    {confirmSaving
                      ? 'Saving…'
                      : `Teach ${confirming.picked.length} more`}
                  </Text>
                </PressableScale>
              </View>
            </View>
          ) : (
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
            {suggestions.length > 0 && (
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.suggestRow}
                keyboardShouldPersistTaps="handled"
              >
                {suggestions.map((l) => (
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
          )}
        </View>
      </Modal>
    </>
  );
});

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
  onConfirmTag,
  onShowConfidence,
  onSearchLabel,
  onSelectItem,
}: {
  item: Item | null;
  busy: boolean;
  matchInfo: { label: string; score: number; matched: boolean }[] | null;
  matchBusy: boolean;
  onClose: () => void;
  onShare: () => void;
  onCopy: () => void;
  onCopyText: () => void;
  onDelete: () => void;
  onTeach: (positive: boolean, preset?: string) => void;
  // One-tap "this tag is right" — saves a positive exemplar for the open meme.
  onConfirmTag: (label: string) => void;
  onShowConfidence: () => void;
  onSearchLabel?: (label: string) => void;
  // Tap a "More like this" thumbnail to view that meme in this sheet instead.
  onSelectItem?: (hit: SearchHit) => void;
}) {
  const drag = useConst(() => new Animated.Value(0));

  // Visually similar memes for the open item, ranked by CLIP cosine against its
  // stored embedding. Fetched per item (cleared first so a stale strip never
  // shows against the wrong meme); the stale flag drops a slow fetch that
  // resolves after the user has already jumped to another meme. Pending
  // placeholders have no embedding yet, so they simply show no strip.
  const itemId = item ? item.id : null;
  const itemPending = !!item && 'pending' in item && !!item.pending;
  const [similar, setSimilar] = useState<SearchHit[] | null>(null);
  useEffect(() => {
    setSimilar(null);
    if (itemId == null || itemPending) return;
    let stale = false;
    getSimilarMemes(itemId, 10)
      .then((hits) => {
        if (!stale) setSimilar(hits);
      })
      .catch(() => {
        if (!stale) setSimilar([]);
      });
    return () => {
      stale = true;
    };
  }, [itemId, itemPending]);

  // Drag-to-dismiss on the grab area only, so scrolling the metadata or
  // pinch-looking at the image never accidentally closes the sheet. Lazy so the
  // responder is built once instead of re-created on every render.
  const pan = useConst(() =>
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
  );

  useEffect(() => {
    if (item) drag.setValue(0);
  }, [item, drag]);

  // Long-press verdict on a tag chip: promote the model's guess to a taught
  // positive example, or mark it wrong. Either way this meme stops being a
  // guess and becomes the user's ground truth.
  const askTagVerdict = (label: string) => {
    tap();
    Alert.alert(`“${label}”`, 'Is this tag right for this meme?', [
      { text: 'Cancel', style: 'cancel' },
      { text: '✗ Wrong — not this', style: 'destructive', onPress: () => onTeach(false, label) },
      { text: '✓ Right — teach it', onPress: () => onConfirmTag(label) },
    ]);
  };

  const { height: winHeight } = useWindowDimensions();
  const imgHeight = Math.round(winHeight * 0.42);

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
                // Same reasoning as the grid: it's a local file, so skip the
                // redundant on-disk copy and only hold it in memory while open.
                cachePolicy="memory"
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

              {/* Recency debug: the file date drives library ordering; shown so
                  the stored value can be verified against the real file. */}
              <Text style={styles.mutedSmall}>
                file date: {item.modifiedAt ? new Date(item.modifiedAt).toLocaleString() : 'unknown'}
                {'  ·  indexed: '}
                {new Date(item.indexedAt).toLocaleString()}
              </Text>

              {!!item.caption && (
                <View style={styles.block}>
                  <Text style={styles.sectionLabel}>What this is · on-device AI</Text>
                  <Text style={styles.caption} selectable>
                    {item.caption}
                  </Text>
                </View>
              )}

              {item.tags.length > 0 && (
                <View style={styles.block}>
                  <Text style={styles.sectionLabel}>
                    {onSearchLabel ? 'Tags · tap to search · hold to confirm/fix' : 'Tags · hold to confirm/fix'}
                  </Text>
                  <View style={styles.tagWrap}>
                    {item.tags.map((t) => (
                      <Chip
                        key={t.label}
                        label={t.label}
                        taught={t.source === 'exemplar'}
                        onPress={
                          onSearchLabel ? () => onSearchLabel(t.label) : () => askTagVerdict(t.label)
                        }
                        onLongPress={() => askTagVerdict(t.label)}
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

              {!!item.transcript && (
                <View style={styles.block}>
                  <Text style={styles.sectionLabel}>Speech in video · on-device Whisper</Text>
                  <Text style={styles.ocr} selectable>
                    {item.transcript}
                  </Text>
                </View>
              )}

              {similar !== null && similar.length > 0 && (
                <View style={styles.block}>
                  <Text style={styles.sectionLabel}>More like this · tap to open</Text>
                  <ScrollView
                    horizontal
                    showsHorizontalScrollIndicator={false}
                    contentContainerStyle={styles.similarRow}
                  >
                    {similar.map((s) => (
                      <PressableScale
                        key={s.id}
                        scaleTo={0.92}
                        onPress={() => onSelectItem?.(s)}
                        style={styles.similarCell}
                      >
                        <Image
                          source={{ uri: thumbSource(s) }}
                          style={styles.similarThumb}
                          contentFit="cover"
                          transition={100}
                          recyclingKey={`sim-${s.id}`}
                          // Same reasoning as the grid: originals are local
                          // files, keep decoded thumbs in memory only.
                          cachePolicy="memory"
                          allowDownscaling
                        />
                        {s.kind === 'video' && (
                          <View style={styles.similarPlay}>
                            <Text style={styles.playIcon}>▶</Text>
                          </View>
                        )}
                      </PressableScale>
                    ))}
                  </ScrollView>
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
                      {m.matched ? '✓ match' : ''}
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
  // Hoisted out of render so the FlatList isn't handed fresh style objects on
  // every parent re-render (search keystrokes, library refreshes).
  column: { gap: GAP, paddingHorizontal: GAP },
  // Positioning context for the floating back-to-top button.
  gridWrap: { flex: 1 },
  topFabWrap: {
    position: 'absolute',
    right: space.lg,
    bottom: TABBAR_CLEARANCE + 8,
  },
  topFab: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: colors.surface3,
    borderWidth: 1,
    borderColor: colors.borderLight,
    alignItems: 'center',
    justifyContent: 'center',
    ...shadow.float,
  },
  topFabIcon: { color: colors.volt, fontSize: 18, fontWeight: '800' },
  listContent: { gap: GAP, paddingBottom: TABBAR_CLEARANCE + 24 },
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
  pending: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.35)',
    borderRadius: radius.sm,
  },

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
  similarRow: { gap: 8, paddingRight: 4 },
  similarCell: { width: 76, height: 76 },
  similarThumb: {
    width: '100%',
    height: '100%',
    borderRadius: radius.sm,
    backgroundColor: colors.surface2,
  },
  similarPlay: {
    position: 'absolute',
    right: 4,
    bottom: 4,
    backgroundColor: 'rgba(0,0,0,0.65)',
    borderRadius: radius.pill,
    paddingHorizontal: 5,
    paddingVertical: 1,
  },
  ocr: { color: colors.textDim, fontSize: 13, lineHeight: 19 },
  caption: { color: colors.text, fontSize: 14, lineHeight: 20, fontWeight: '500' },
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
  teachActionDisabled: { opacity: 0.4 },
  confirmGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  confirmCell: { width: 72, height: 72 },
  confirmThumb: {
    width: '100%',
    height: '100%',
    borderRadius: radius.sm,
    backgroundColor: colors.surface2,
  },
  confirmThumbOn: { borderWidth: 2, borderColor: colors.volt },
  confirmCheck: {
    position: 'absolute',
    top: 4,
    right: 4,
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: colors.volt,
    alignItems: 'center',
    justifyContent: 'center',
  },
  confirmCheckText: { color: colors.onVolt, fontSize: 11, fontWeight: '800' },
  teachCancel: { borderWidth: 1, borderColor: colors.border },
  teachCancelText: { color: colors.textDim, fontWeight: '700' },
  teachSave: { backgroundColor: colors.volt },
  teachSaveText: { color: colors.onVolt, fontWeight: '800' },
  teachSaveNeg: { backgroundColor: colors.danger },
  teachSaveNegText: { color: '#fff', fontWeight: '800' },
});
