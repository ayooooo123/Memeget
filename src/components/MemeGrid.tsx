import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Image } from 'expo-image';
import {
  ActivityIndicator,
  Alert,
  Animated,
  FlatList,
  Keyboard,
  Modal,
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
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  addExemplar,
  bulkUpdateMemeTags,
  deleteMeme,
  getLabels,
  getLibraryTagLabels,
  getMemeEmbedding,
  getSimilarMemes,
  propagateTagToSimilarMemes,
  requeueMemeThumb,
  requeueMemeVision,
} from '../db';
import { termsWithLabel } from '../tagPropagation';
import { emitLibraryChanged } from '../events';
import { guessFacet } from '../facetCoverage';
import { scoreExemplar } from '../learnCore';
import { buildExemplarHeads, noteInteractive, type ExemplarModel } from '../indexer';
import { noteCodecInteractive } from '../interactive';
import { success, tap, thud, warn } from '../haptics';
import { copyFileToClipboard } from '../../modules/memeget-bg';
import { deleteFile, materialize, readImageBase64, readVideoFrameBase64, videoMimeFor } from '../saf';
import { colors, radius, shadow, space, TABBAR_CLEARANCE } from '../theme';
import { useConst } from '../reactUtils';
import type { MemeRecord, SearchHit, Tag } from '../types';

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

// What an <Image> thumbnail should load for an item: the persisted poster
// jpeg when one exists — the image view can't decode a frame from every video
// codec, and "mp4 gif" style files (including mp4 bytes wearing a .gif name,
// which land as kind 'image') rendered blank straight off their content://
// uri. Falls back to the original uri for everything without a poster.
function thumbSource(item: Pick<Item, 'kind' | 'uri' | 'thumbUri'>): string {
  return item.thumbUri || item.uri;
}

// Ordered source candidates for a grid cell. Real GIFs skip their persisted
// thumb (a static jpeg — it froze them) and load the original bytes so they
// animate right in the grid; when that decode fails (mp4 bytes wearing a .gif
// name) the cell falls back to the extracted poster before giving up on the
// stub. Everything else keeps loading the small persisted thumb.
function cellSources(item: Pick<Item, 'kind' | 'uri' | 'name' | 'thumbUri'>): string[] {
  if (item.kind === 'image' && /\.gif$/i.test(item.name)) {
    return item.thumbUri ? [item.uri, item.thumbUri] : [item.uri];
  }
  return [thumbSource(item)];
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
  selectionMode,
  selected,
  onPress,
  onLongPress,
}: {
  item: Item;
  size: number;
  selectionMode: boolean;
  selected: boolean;
  onPress: (it: Item) => void;
  onLongPress: (it: Item) => void;
}) {
  // Walk the item's source candidates on render failure (an "mp4 gif" whose
  // bytes wear a .gif name and land as kind 'image', a codec expo-image
  // refuses, a stale/missing poster file): a failed source advances to the
  // next, and only a tile with nothing left to try shows the labeled stub
  // instead of a permanent blank square. The index resets whenever the
  // candidate list changes so a poster landing later (patched in by id) is
  // retried, not stuck on the earlier failure.
  const sources = cellSources(item);
  const [srcIdx, setSrcIdx] = useState(0);
  const srcKey = sources.join('\n');
  useEffect(() => setSrcIdx(0), [srcKey]);
  const exhausted = srcIdx >= sources.length;
  const src = sources[Math.min(srcIdx, sources.length - 1)];
  // A video with no poster yet goes straight to the stub (asking the image view
  // to decode the video hits the same retriever that already refused it). Any
  // other tile only shows the stub once every candidate has failed to render.
  const showStub = (item.kind === 'video' && !item.thumbUri) || exhausted;

  return (
    <PressableScale
      scaleTo={0.94}
      onPress={() => onPress(item)}
      onLongPress={() => onLongPress(item)}
      style={{ width: size, height: size }}
    >
      {showStub ? (
        <View style={[styles.thumb, styles.videoStub]}>
          <Text style={styles.videoStubGlyph}>{item.kind === 'video' ? '🎞' : '🖼'}</Text>
          <Text style={styles.videoStubName} numberOfLines={2}>
            {item.name}
          </Text>
        </View>
      ) : (
        <Image
          source={{ uri: src }}
          style={styles.thumb}
          contentFit="cover"
          transition={150}
          // A source that can't be decoded (mp4-as-gif, unsupported codec,
          // missing poster) advances to the next candidate; the stub above is
          // the last resort instead of rendering blank.
          onError={() => setSrcIdx((i) => i + 1)}
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
      )}
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
      {selectionMode && (
        <>
          {selected && <View style={styles.selOverlay} pointerEvents="none" />}
          <View style={[styles.selCircle, selected && styles.selCircleOn]} pointerEvents="none">
            {selected && <Text style={styles.selCheck}>✓</Text>}
          </View>
        </>
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
  onScrollActiveChange,
}: {
  items: Item[];
  header?: React.ReactElement;
  // Called after a new exemplar is saved; should re-tag and return how many
  // memes now carry the label (for feedback). Optional.
  onTaught?: (label: string) => Promise<number | void>;
  // Infinite-scroll hook: called when the user nears the end of the list so
  // the parent can append the next page (browse pages from the DB; search
  // reveals more of its already-ranked list). Optional.
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
  // Called with true when a drag/fling begins and false when scrolling settles,
  // so the parent can hold refreshes and stamp interactivity during a scroll.
  onScrollActiveChange?: (active: boolean) => void;
}) {
  const [selected, setSelected] = useState<Item | null>(null);
  // Multi-select: long-press a cell to enter selection mode, tap to toggle, then
  // apply a bulk action (tag / delete) to the whole set. Kept in the grid (not
  // lifted to the screen) so the bar and cell overlays live next to the list.
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [bulkTagging, setBulkTagging] = useState(false);
  const [bulkLabelInput, setBulkLabelInput] = useState('');
  const [bulkLabels, setBulkLabels] = useState<string[]>([]);
  const [bulkBusy, setBulkBusy] = useState(false);
  // Spread the tag to visual look-alikes after tagging (the toggle in the
  // bulk-tag sheet). On by default; sticks for the session.
  const [bulkSpread, setBulkSpread] = useState(true);
  // Read the latest selection mode from the tap/long-press handlers without
  // giving them a new identity each toggle (which would bust GridCell's memo).
  const selectionModeRef = useRef(false);
  selectionModeRef.current = selectionMode;
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

  // Report scroll activity to the parent, momentum-aware so a drag→fling handoff
  // doesn't flap "settled" for a frame between the finger lifting and momentum
  // starting. Deactivation is deferred a beat; a momentum-begin within that beat
  // cancels it, so we stay "active" continuously from first touch to full stop.
  const scrollSettleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const setScrollActive = useCallback(
    (active: boolean) => {
      if (active) {
        if (scrollSettleTimer.current) {
          clearTimeout(scrollSettleTimer.current);
          scrollSettleTimer.current = null;
        }
        onScrollActiveChange?.(true);
      } else {
        if (scrollSettleTimer.current) clearTimeout(scrollSettleTimer.current);
        scrollSettleTimer.current = setTimeout(() => {
          scrollSettleTimer.current = null;
          onScrollActiveChange?.(false);
        }, 150);
      }
    },
    [onScrollActiveChange]
  );
  const onScrollBeginDrag = useCallback(() => setScrollActive(true), [setScrollActive]);
  const onScrollEndDrag = useCallback(() => setScrollActive(false), [setScrollActive]);
  const onMomentumScrollBegin = useCallback(() => setScrollActive(true), [setScrollActive]);
  const onMomentumScrollEnd = useCallback(() => setScrollActive(false), [setScrollActive]);
  useEffect(
    () => () => {
      if (scrollSettleTimer.current) clearTimeout(scrollSettleTimer.current);
    },
    []
  );

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
    // Opening a video contends for the hardware decoder the poster backfill
    // uses — stamp the short codec window so it briefly yields the decoder.
    if (it.kind === 'video') noteCodecInteractive();
    setSelected(it);
  }, []);

  const toggleSelect = useCallback((it: Item) => {
    tap();
    setSelectedIds((cur) => {
      const next = new Set(cur);
      if (next.has(it.id)) next.delete(it.id);
      else next.add(it.id);
      return next;
    });
  }, []);

  const enterSelection = useCallback((it: Item) => {
    thud();
    setSelectionMode(true);
    setSelectedIds(new Set([it.id]));
  }, []);

  const exitSelection = useCallback(() => {
    setSelectionMode(false);
    setSelectedIds(new Set());
  }, []);

  // Stable across renders (they read selectionMode via the ref), so toggling a
  // cell doesn't re-create the handlers and re-render every thumbnail. A tap
  // opens the viewer normally, or toggles the cell when selecting; a long-press
  // enters selection mode (or toggles once already in it).
  const handlePress = useCallback(
    (it: Item) => {
      if (selectionModeRef.current) toggleSelect(it);
      else openViewer(it);
    },
    [toggleSelect, openViewer]
  );
  const handleLongPress = useCallback(
    (it: Item) => {
      if (selectionModeRef.current) toggleSelect(it);
      else enterSelection(it);
    },
    [toggleSelect, enterSelection]
  );

  // renderItem depends on selectedIds/selectionMode so cells reflect selection.
  // GridCell is memoized on a plain `selected` boolean, so only the cell whose
  // boolean actually changed re-renders on a toggle — the rest short-circuit.
  const keyExtractor = useCallback((it: Item) => String(it.id), []);
  const renderItem = useCallback(
    ({ item }: { item: Item }) => (
      <GridCell
        item={item}
        size={size}
        selectionMode={selectionMode}
        selected={selectedIds.has(item.id)}
        onPress={handlePress}
        onLongPress={handleLongPress}
      />
    ),
    [size, selectionMode, selectedIds, handlePress, handleLongPress]
  );

  const allSelected = items.length > 0 && selectedIds.size >= items.length;
  const toggleSelectAll = useCallback(() => {
    tap();
    setSelectedIds((cur) => (cur.size >= items.length ? new Set() : new Set(items.map((it) => it.id))));
  }, [items]);

  const openBulkTag = () => {
    if (selectedIds.size === 0) return;
    tap();
    setBulkLabelInput('');
    setBulkTagging(true);
    getLibraryTagLabels(40)
      .then(setBulkLabels)
      .catch(() => setBulkLabels([]));
  };

  const applyBulkTag = async () => {
    const label = bulkLabelInput.trim();
    if (!label || selectedIds.size === 0 || bulkBusy) return;
    setBulkBusy(true);
    try {
      const lower = label.toLowerCase();
      const updates = items
        .filter((it) => selectedIds.has(it.id))
        .map((it) => {
          const already = it.tags.some((t) => t.label.toLowerCase() === lower);
          const tags: Tag[] = already
            ? it.tags
            : [...it.tags, { label, category: 'user', score: 1, source: 'manual' as const }];
          return {
            id: it.id,
            tags,
            extraTerms: already ? it.extraTerms : termsWithLabel(it.extraTerms, label),
          };
        });
      await bulkUpdateMemeTags(updates);
      // Optionally spread the tag to the tagged memes' visual look-alikes
      // (DINOv2 space when configured, else CLIP — see tagPropagation.ts).
      // Best-effort: a spread failure never rolls back the tag itself.
      let spread = 0;
      if (bulkSpread) {
        spread = await propagateTagToSimilarMemes(
          updates.map((u) => u.id),
          label
        )
          .then((r) => r.propagated)
          .catch(() => 0);
      }
      emitLibraryChanged();
      setBulkTagging(false);
      exitSelection();
      success();
      const tagged = `Tagged ${updates.length} meme${updates.length === 1 ? '' : 's'} “${label}”`;
      showToast(
        spread > 0 ? `${tagged} · spread to ${spread} look-alike${spread === 1 ? '' : 's'}` : tagged,
        'success'
      );
    } catch (e) {
      showToast(`Could not tag: ${String(e)}`, 'error');
    } finally {
      setBulkBusy(false);
    }
  };

  const bulkDelete = () => {
    if (selectedIds.size === 0 || bulkBusy) return;
    const targets = items.filter((it) => selectedIds.has(it.id));
    if (targets.length === 0) return;
    warn();
    Alert.alert(
      `Delete ${targets.length} meme${targets.length === 1 ? '' : 's'}?`,
      `This removes them from your library and deletes the files from their folders. This can’t be undone.`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: async () => {
            setBulkBusy(true);
            let done = 0;
            try {
              for (const it of targets) {
                await deleteMeme(it.id);
                await deleteFile(it.uri).catch(() => {}); // best-effort; DB row is gone regardless
                onDeleted?.(it.id);
                done += 1;
              }
              success();
              showToast(`Deleted ${done} meme${done === 1 ? '' : 's'}`, 'info');
            } catch (e) {
              showToast(`Deleted ${done} of ${targets.length}; ${String(e)}`, 'error');
            } finally {
              setBulkBusy(false);
              exitSelection();
            }
          },
        },
      ]
    );
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
    if (isVideo) noteCodecInteractive(); // free the poster loop's decoder now
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

  // Re-run the on-device vision model on just this meme: requeue it and nudge
  // the library so the describe loop (or the demand-loaded VLM) picks it up. The
  // caption/tags refresh in place once it finishes; nothing to await here.
  const onRecaption = async () => {
    if (!selected || busy) return;
    setBusy(true);
    try {
      await requeueMemeVision(selected.id);
      emitLibraryChanged();
      success();
      showToast('Re-captioning queued — the description refreshes shortly', 'success');
    } catch (e) {
      showToast(`Could not re-caption: ${String(e)}`, 'error');
    } finally {
      setBusy(false);
    }
  };

  // Regenerate a video's grid poster: clear it and wake the poster loop. Only
  // offered for videos (images are their own thumbnail).
  const onRethumb = async () => {
    if (!selected || busy) return;
    setBusy(true);
    try {
      await requeueMemeThumb(selected.id);
      emitLibraryChanged();
      success();
      showToast('Poster refresh queued — the thumbnail regenerates shortly', 'success');
    } catch (e) {
      showToast(`Could not refresh poster: ${String(e)}`, 'error');
    } finally {
      setBusy(false);
    }
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
        category: guessFacet(label),
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
          category: guessFacet(c.label),
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
        category: guessFacet(label),
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
        onScrollBeginDrag={onScrollBeginDrag}
        onScrollEndDrag={onScrollEndDrag}
        onMomentumScrollBegin={onMomentumScrollBegin}
        onMomentumScrollEnd={onMomentumScrollEnd}
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

      {/* Floating back-to-top button. Hidden while selecting so it doesn't
          collide with the bulk-action bar. pointerEvents flips with visibility
          so a fully faded-out button can never swallow taps meant for the grid. */}
      {!selectionMode && (
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
      )}

      {/* Bulk-action bar — shown while selecting. Cancel · count · select-all on
          the left; the actions (tag, delete) on the right. */}
      {selectionMode && (
        <View style={styles.bulkBar}>
          <PressableScale
            scaleTo={0.9}
            onPress={exitSelection}
            style={styles.bulkClose}
            accessibilityRole="button"
            accessibilityLabel="Cancel selection"
          >
            <Text style={styles.bulkCloseIcon}>✕</Text>
          </PressableScale>
          <Text style={styles.bulkCount}>{selectedIds.size}</Text>
          <PressableScale scaleTo={0.92} onPress={toggleSelectAll} style={styles.bulkAll}>
            <Text style={styles.bulkAllText}>{allSelected ? 'None' : 'All'}</Text>
          </PressableScale>
          <View style={styles.bulkSpacer} />
          <PressableScale
            scaleTo={0.92}
            onPress={openBulkTag}
            style={[styles.bulkAction, styles.bulkTagBtn]}
            disabled={selectedIds.size === 0 || bulkBusy}
          >
            <Text style={styles.bulkTagText}>🏷 Tag</Text>
          </PressableScale>
          <PressableScale
            scaleTo={0.92}
            onPress={bulkDelete}
            style={[styles.bulkAction, styles.bulkDeleteBtn]}
            disabled={selectedIds.size === 0 || bulkBusy}
          >
            <Text style={styles.bulkDeleteText}>🗑 Delete</Text>
          </PressableScale>
        </View>
      )}
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
        onRecaption={onRecaption}
        onRethumb={onRethumb}
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

      {/* Bulk-tag sheet — applies one tag to every selected meme at once. */}
      <Modal
        visible={bulkTagging}
        transparent
        animationType="slide"
        statusBarTranslucent
        onRequestClose={() => setBulkTagging(false)}
      >
        <View style={[styles.teachRoot, { paddingBottom: kbHeight }]}>
          <Pressable style={StyleSheet.absoluteFill} onPress={() => setBulkTagging(false)} />
          <View style={styles.teachSheet}>
            <View style={styles.grabber} />
            <Text style={styles.sheetHeading}>
              Tag {selectedIds.size} meme{selectedIds.size === 1 ? '' : 's'}
            </Text>
            <Text style={styles.teachHint}>
              Adds this tag to every selected meme. It sticks through re-tagging and is searchable.
            </Text>
            <TextInput
              style={styles.input}
              value={bulkLabelInput}
              onChangeText={setBulkLabelInput}
              placeholder="Tag, e.g. reaction"
              placeholderTextColor={colors.muted}
              autoFocus
            />
            {bulkLabels.length > 0 && (
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.suggestRow}
                keyboardShouldPersistTaps="handled"
              >
                {bulkLabels.map((l) => (
                  <Chip key={l} label={l} active={bulkLabelInput.trim() === l} onPress={() => setBulkLabelInput(l)} />
                ))}
              </ScrollView>
            )}
            <Pressable
              style={styles.spreadRow}
              onPress={() => {
                tap();
                setBulkSpread((v) => !v);
              }}
            >
              <Text style={[styles.spreadBox, bulkSpread && styles.spreadBoxOn]}>
                {bulkSpread ? '✓' : ''}
              </Text>
              <View style={styles.spreadCopy}>
                <Text style={styles.spreadTitle}>Spread to look-alikes</Text>
                <Text style={styles.spreadHint}>
                  Also tags memes that look almost identical to the selected ones — other crops and
                  variants of the same template.
                </Text>
              </View>
            </Pressable>
            <View style={styles.teachActions}>
              <PressableScale
                style={[styles.teachAction, styles.teachCancel]}
                onPress={() => setBulkTagging(false)}
              >
                <Text style={styles.teachCancelText}>Cancel</Text>
              </PressableScale>
              <PressableScale
                style={[styles.teachAction, styles.teachSave]}
                onPress={applyBulkTag}
                disabled={!bulkLabelInput.trim() || bulkBusy}
              >
                <Text style={styles.teachSaveText}>{bulkBusy ? 'Tagging…' : 'Add tag'}</Text>
              </PressableScale>
            </View>
          </View>
        </View>
      </Modal>
    </>
  );
});

// Full-screen viewer: the meme fills the screen with a slim top bar (name ·
// match · close) and a collapsible details panel docked at the bottom. Tapping
// the image (or the panel's grabber) hides the panel for an uncluttered look;
// the ⓘ pill brings it back. The panel carries only the everyday actions —
// share, copy, tag, delete — while maintenance (re-caption, poster refresh,
// label corrections, debug confidence, file dates) lives behind “More”.
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
  onRecaption,
  onRethumb,
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
  // Re-run the vision model / regenerate the poster for just this meme.
  onRecaption: () => void;
  onRethumb: () => void;
  onTeach: (positive: boolean, preset?: string) => void;
  // One-tap "this tag is right" — saves a positive exemplar for the open meme.
  onConfirmTag: (label: string) => void;
  onShowConfidence: () => void;
  onSearchLabel?: (label: string) => void;
  // Tap a "More like this" thumbnail to view that meme in this sheet instead.
  onSelectItem?: (hit: SearchHit) => void;
}) {
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

  // Details panel + More menu visibility. Reset per meme so a panel hidden on
  // the previous meme (or a menu left open) doesn't leak into the next one.
  const [showInfo, setShowInfo] = useState(true);
  const [moreOpen, setMoreOpen] = useState(false);
  useEffect(() => {
    setShowInfo(true);
    setMoreOpen(false);
  }, [itemId]);

  // A full-res image expo-image can't decode (mp4 bytes wearing a .gif name)
  // falls back to the extracted poster instead of a black void. Reset per meme.
  const [mediaFailed, setMediaFailed] = useState(false);
  useEffect(() => setMediaFailed(false), [itemId]);

  // The modal covers the whole screen (statusBarTranslucent), so the top bar
  // and bottom panel pad themselves clear of the system bars.
  const insets = useSafeAreaInsets();

  const closeMoreThen = (fn: () => void) => () => {
    setMoreOpen(false);
    fn();
  };

  return (
    <Modal
      visible={!!item}
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      {item && (
        <View style={styles.viewerRoot}>
          <View style={[styles.viewerTop, { paddingTop: insets.top + 6 }]}>
            <View style={styles.viewerTitleWrap}>
              <Text style={styles.viewerTitle} numberOfLines={1}>
                {item.name}
              </Text>
              {/* Clamped to 0 as well as 100: search ranks the whole
                  collection, so weak-tail items (even slightly negative
                  cosines) are reachable and shouldn't read "-3% match". */}
              {'score' in item && (
                <Text style={styles.matchScore}>
                  {Math.min(100, Math.max(0, item.score * 100)).toFixed(0)}% match
                </Text>
              )}
            </View>
            <Pressable onPress={onClose} hitSlop={12} style={styles.closeBtn} accessibilityLabel="Close">
              <Text style={styles.closeIcon}>✕</Text>
            </Pressable>
          </View>

          {item.kind === 'video' ? (
            <VideoPreview key={item.id} uri={item.uri} />
          ) : (
            // Tap the image to hide/show the details panel — the meme itself is
            // what the viewer is for. (Videos keep the panel toggling on the
            // grabber/pill only; taps there belong to the native controls.)
            <Pressable style={styles.mediaArea} onPress={() => setShowInfo((v) => !v)}>
              <Image
                source={{ uri: mediaFailed && item.thumbUri ? item.thumbUri : item.uri }}
                style={styles.media}
                contentFit="contain"
                onError={() => setMediaFailed(true)}
                recyclingKey={String(item.id)}
                // Same reasoning as the grid: it's a local file, so skip the
                // redundant on-disk copy and only hold it in memory while open.
                cachePolicy="memory"
                allowDownscaling
              />
            </Pressable>
          )}

          {!showInfo && (
            <PressableScale
              scaleTo={0.9}
              onPress={() => setShowInfo(true)}
              style={[styles.infoPill, { bottom: insets.bottom + 18 }]}
              accessibilityRole="button"
              accessibilityLabel="Show details"
            >
              <Text style={styles.infoPillText}>ⓘ Details</Text>
            </PressableScale>
          )}

          {showInfo && (
            <View style={[styles.infoPanel, { paddingBottom: insets.bottom + 4 }]}>
              <Pressable
                onPress={() => setShowInfo(false)}
                hitSlop={10}
                accessibilityRole="button"
                accessibilityLabel="Hide details"
              >
                <View style={styles.grabber} />
              </Pressable>

              {/* Everyday actions only; everything else is behind More. */}
              <View style={styles.actionBar}>
                <ActionButton glyph="⤴" label={busy ? '…' : 'Share'} onPress={onShare} disabled={busy} />
                <ActionButton glyph="⧉" label="Copy" onPress={onCopy} disabled={busy} />
                <ActionButton glyph="🏷" label="Tag" onPress={() => onTeach(true)} disabled={busy} />
                <ActionButton glyph="⋯" label="More" onPress={() => setMoreOpen(true)} />
                <ActionButton glyph="🗑" label="Delete" danger onPress={onDelete} disabled={busy} />
              </View>

              <ScrollView
                style={styles.meta}
                contentContainerStyle={styles.metaContent}
                keyboardShouldPersistTaps="handled"
              >
                {!!item.caption && (
                  <Text style={styles.caption} selectable>
                    {item.caption}
                  </Text>
                )}

                {item.tags.length > 0 && (
                  <View style={styles.block}>
                    <Text style={styles.sectionLabel}>
                      {onSearchLabel ? 'Tags · tap to search · hold to fix' : 'Tags · hold to fix'}
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
                    <Text style={styles.sectionLabel}>Text in meme · hold to copy</Text>
                    <Text style={styles.ocr} selectable onLongPress={onCopyText}>
                      {item.ocrText}
                    </Text>
                  </View>
                )}

                {!!item.transcript && (
                  <View style={styles.block}>
                    <Text style={styles.sectionLabel}>Speech in video</Text>
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

                {/* Filled in when "taught-label confidence" is run from More. */}
                {matchInfo !== null &&
                  (matchInfo.length > 0 ? (
                    <View style={styles.block}>
                      <Text style={styles.sectionLabel}>Taught-label confidence</Text>
                      {matchInfo.map((m) => (
                        <Text key={m.label} style={styles.mutedSmall}>
                          {m.label}: {(m.score * 100).toFixed(0)}%{' '}
                          {m.matched ? '✓ match' : ''}
                        </Text>
                      ))}
                    </View>
                  ) : (
                    <Text style={styles.mutedSmall}>No taught labels yet.</Text>
                  ))}
              </ScrollView>
            </View>
          )}

          {/* “More” menu: the maintenance actions that used to crowd the main
              action bar, plus the file/index dates (a recency-sort debug aid). */}
          {moreOpen && (
            <View style={StyleSheet.absoluteFill}>
              <Pressable style={[StyleSheet.absoluteFill, styles.moreScrim]} onPress={() => setMoreOpen(false)} />
              <View style={[styles.moreSheet, { paddingBottom: insets.bottom + space.md }]}>
                <View style={styles.grabber} />
                {!!item.ocrText && (
                  <MoreRow glyph="🆎" label="Copy the text in this meme" onPress={closeMoreThen(onCopyText)} />
                )}
                <MoreRow
                  glyph="✗"
                  label="Fix a wrong label…"
                  hint="Teach that this meme is NOT some label"
                  onPress={closeMoreThen(() => onTeach(false))}
                />
                <MoreRow
                  glyph="✨"
                  label="Re-run the caption"
                  hint="Fresh on-device description for this meme"
                  onPress={closeMoreThen(onRecaption)}
                  disabled={busy}
                />
                {item.kind === 'video' && (
                  <MoreRow
                    glyph="🖼"
                    label="Regenerate the thumbnail"
                    hint="Extract a new poster frame for the grid"
                    onPress={closeMoreThen(onRethumb)}
                    disabled={busy}
                  />
                )}
                <MoreRow
                  glyph="◎"
                  label={matchBusy ? 'Scoring taught labels…' : 'Show taught-label confidence'}
                  hint="How strongly each taught label matches this meme"
                  onPress={closeMoreThen(() => {
                    setShowInfo(true); // the readout lands in the details panel
                    onShowConfidence();
                  })}
                  disabled={matchBusy}
                />
                <Text style={styles.moreDates}>
                  file date {item.modifiedAt ? new Date(item.modifiedAt).toLocaleString() : 'unknown'}
                  {'  ·  '}indexed {new Date(item.indexedAt).toLocaleString()}
                </Text>
              </View>
            </View>
          )}
        </View>
      )}
    </Modal>
  );
}

function MoreRow({
  glyph,
  label,
  hint,
  onPress,
  disabled,
}: {
  glyph: string;
  label: string;
  hint?: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <PressableScale scaleTo={0.97} style={styles.moreRow} onPress={onPress} disabled={disabled}>
      <Text style={styles.moreGlyph}>{glyph}</Text>
      <View style={styles.moreCopy}>
        <Text style={styles.moreLabel}>{label}</Text>
        {!!hint && <Text style={styles.moreHint}>{hint}</Text>}
      </View>
    </PressableScale>
  );
}

// In-viewer player for video memes. expo-video drives Android's ExoPlayer,
// which streams the SAF content:// uri directly (same uri we store + display),
// so there's no need to materialize a temp file just to watch it. The player is
// created per-uri and released automatically when the sheet unmounts; keying the
// element by item.id guarantees a fresh player when you swipe to another video.
function VideoPreview({ uri }: { uri: string }) {
  const player = useVideoPlayer(uri, (p) => {
    p.loop = true;
    p.play();
  });

  return (
    <VideoView
      style={styles.mediaArea}
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
  videoStub: { alignItems: 'center', justifyContent: 'center', padding: 8, gap: 4 },
  videoStubGlyph: { fontSize: 22, opacity: 0.6 },
  videoStubName: { color: colors.faint, fontSize: 9, textAlign: 'center' },
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
  selOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    borderRadius: radius.sm,
    borderWidth: 2,
    borderColor: colors.volt,
    backgroundColor: 'rgba(0,0,0,0.28)',
  },
  selCircle: {
    position: 'absolute',
    top: 6,
    left: 6,
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 2,
    borderColor: '#fff',
    backgroundColor: 'rgba(0,0,0,0.4)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  selCircleOn: { backgroundColor: colors.volt, borderColor: colors.volt },
  selCheck: { color: colors.onVolt, fontSize: 13, fontWeight: '900' },

  bulkBar: {
    position: 'absolute',
    left: space.md,
    right: space.md,
    bottom: TABBAR_CLEARANCE + 8,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    backgroundColor: colors.surface3,
    borderWidth: 1,
    borderColor: colors.borderLight,
    borderRadius: radius.pill,
    paddingLeft: 10,
    paddingRight: 8,
    paddingVertical: 8,
    ...shadow.float,
  },
  bulkClose: {
    width: 30,
    height: 30,
    borderRadius: 15,
    backgroundColor: colors.surface2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bulkCloseIcon: { color: colors.text, fontSize: 13, fontWeight: '800' },
  bulkCount: { color: colors.text, fontSize: 14, fontWeight: '800', minWidth: 18, textAlign: 'center' },
  bulkAll: { paddingHorizontal: 8, paddingVertical: 6 },
  bulkAllText: { color: colors.volt, fontSize: 13, fontWeight: '700' },
  bulkSpacer: { flex: 1 },
  bulkAction: { paddingHorizontal: 12, paddingVertical: 8, borderRadius: radius.pill },
  bulkTagBtn: { backgroundColor: colors.volt },
  bulkTagText: { color: colors.onVolt, fontSize: 13, fontWeight: '800' },
  bulkDeleteBtn: { backgroundColor: colors.dangerDim, borderWidth: 1, borderColor: colors.danger },
  bulkDeleteText: { color: colors.danger, fontSize: 13, fontWeight: '800' },

  // Full-screen viewer: near-black canvas so the meme is the loudest thing on
  // screen; the chrome (top bar, details panel) sits quietly at the edges.
  viewerRoot: { flex: 1, backgroundColor: '#050608' },
  viewerTop: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: space.lg,
    paddingBottom: 8,
  },
  viewerTitleWrap: { flex: 1, gap: 2 },
  viewerTitle: { color: colors.textDim, fontWeight: '600', fontSize: 13 },
  matchScore: { color: colors.accent, fontSize: 11, fontWeight: '700' },
  grabber: {
    alignSelf: 'center',
    width: 38,
    height: 4,
    borderRadius: 2,
    backgroundColor: colors.borderLight,
    marginBottom: 8,
  },
  closeBtn: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: colors.surface3,
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeIcon: { color: colors.text, fontSize: 13, fontWeight: '800' },
  // The media takes every pixel the chrome doesn't.
  mediaArea: { flex: 1 },
  media: { width: '100%', height: '100%' },

  // Floating pill that restores a hidden details panel.
  infoPill: {
    position: 'absolute',
    alignSelf: 'center',
    backgroundColor: colors.surface3,
    borderWidth: 1,
    borderColor: colors.borderLight,
    borderRadius: radius.pill,
    paddingHorizontal: 16,
    paddingVertical: 9,
    ...shadow.float,
  },
  infoPillText: { color: colors.text, fontSize: 13, fontWeight: '700' },

  // Bottom details panel: actions on top, scrollable metadata under them. Capped
  // so the meme always keeps the upper part of the screen.
  infoPanel: {
    maxHeight: '55%',
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    borderWidth: 1,
    borderBottomWidth: 0,
    borderColor: colors.borderLight,
    paddingTop: 10,
  },
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
  caption: { color: colors.text, fontSize: 15, lineHeight: 21, fontWeight: '500' },
  mutedSmall: { color: colors.muted, fontSize: 12 },

  // The “More” menu sheet inside the viewer.
  moreScrim: { backgroundColor: colors.scrim },
  moreSheet: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    borderWidth: 1,
    borderBottomWidth: 0,
    borderColor: colors.borderLight,
    paddingTop: 10,
    paddingHorizontal: space.sm,
    gap: 2,
  },
  moreRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: space.md,
    paddingVertical: 12,
    borderRadius: radius.md,
  },
  moreGlyph: { color: colors.volt, fontSize: 16, width: 24, textAlign: 'center' },
  moreCopy: { flex: 1, gap: 1 },
  moreLabel: { color: colors.text, fontSize: 14, fontWeight: '600' },
  moreHint: { color: colors.muted, fontSize: 12 },
  moreDates: { color: colors.faint, fontSize: 11, textAlign: 'center', paddingVertical: 10 },

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
  spreadRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 10, paddingVertical: 2 },
  spreadBox: {
    width: 22,
    height: 22,
    borderRadius: radius.sm,
    borderWidth: 1.5,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
    color: colors.bg,
    fontSize: 14,
    fontWeight: '800',
    textAlign: 'center',
    lineHeight: 20,
    marginTop: 1,
  },
  spreadBoxOn: { backgroundColor: colors.volt, borderColor: colors.volt },
  spreadCopy: { flex: 1, gap: 2 },
  spreadTitle: { color: colors.text, fontSize: 13, fontWeight: '700' },
  spreadHint: { color: colors.muted, fontSize: 12, lineHeight: 17 },
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
