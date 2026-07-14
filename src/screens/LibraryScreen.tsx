import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import { MemeGrid } from '../components/MemeGrid';
import { showToast } from '../components/Toast';
import { Button, Chip, ProgressBar, StatusDot } from '../components/ui';
import { useEmbeddings } from '../embeddings';
import {
  addFolder,
  countMemes,
  countMemesWithLabel,
  getFolders,
  getLabels,
  getLibraryTagLabels,
  getRecentMemes,
  searchByVector,
} from '../db';
import { noteInteractive, runIndex, retagAll, type IndexProgress } from '../indexer';
import { emitLibraryChanged, onLibraryChanged } from '../events';
import { success, tap, thud } from '../haptics';
import { pickFolder } from '../saf';
import { colors, radius, space, type } from '../theme';
import type { LinkedFolder, MediaKind, MemeRecord, SearchHit } from '../types';

const PAGE = 90;

// Two records render-identically when every field the grid/viewer reads matches.
function sameTags(a: MemeRecord['tags'], b: MemeRecord['tags']): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i].label !== b[i].label || a[i].source !== b[i].source) return false;
  }
  return true;
}
function sameRecord(a: MemeRecord, b: MemeRecord): boolean {
  return (
    a.id === b.id &&
    a.uri === b.uri &&
    a.name === b.name &&
    a.kind === b.kind &&
    a.pending === b.pending &&
    // The poster backfill updates ONLY this field — omitting it here kept the
    // stale object alive, so freshly extracted posters never appeared until an
    // app restart even though the DB said they were done.
    a.thumbUri === b.thumbUri &&
    a.visionState === b.visionState &&
    a.caption === b.caption &&
    a.transcript === b.transcript &&
    a.ocrText === b.ocrText &&
    a.extraTerms === b.extraTerms &&
    a.indexedAt === b.indexedAt &&
    sameTags(a.tags, b.tags)
  );
}

// Re-fetching the browse list (e.g. after every shared/indexed meme) used to
// hand React a brand-new array of brand-new objects, so every memoized grid cell
// re-rendered and the list visibly hitched. This reuses the previous object for
// any row whose rendered fields are unchanged, so only genuinely new/changed
// cells re-render — and if nothing changed at all, the SAME array is returned so
// React bails out of the update entirely.
function mergeRecords(prev: MemeRecord[], next: MemeRecord[]): MemeRecord[] {
  if (prev.length === 0) return next;
  const byId = new Map(prev.map((m) => [m.id, m]));
  let changed = next.length !== prev.length;
  const merged = next.map((r, i) => {
    const old = byId.get(r.id);
    if (old && sameRecord(old, r)) {
      if (old !== prev[i]) changed = true; // same data, but its position moved
      return old;
    }
    changed = true;
    return r;
  });
  return changed ? merged : prev;
}

export function LibraryScreen() {
  const emb = useEmbeddings();
  // Mirror the embeddings api into a ref so the stable callbacks below
  // (onIndex/onTaught) don't take a new identity every time the CLIP model's
  // download/load progress ticks. On the first launch after an app update the
  // model reloads and `emb` re-memoizes many times a second; without this, that
  // churn rebuilt the grid header (and re-ran the whole list) mid-scroll, which
  // showed up as scroll jank / glitching while browsing the collection.
  const embRef = useRef(emb);
  embRef.current = emb;
  const [folders, setFolders] = useState<LinkedFolder[]>([]);
  const [recent, setRecent] = useState<MemeRecord[]>([]);
  const [count, setCount] = useState(0);
  const [taughtLabels, setTaughtLabels] = useState<string[]>([]);
  const [libraryTags, setLibraryTags] = useState<string[]>([]);
  const [indexing, setIndexing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [progress, setProgress] = useState<IndexProgress | null>(null);

  // Search-as-filter on the same page: empty query => browse recents,
  // non-empty => semantic results replace the grid.
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchHit[] | null>(null);
  const [searching, setSearching] = useState(false);
  // Bumped each time a search kicks off so the grid scrolls back to the top —
  // otherwise a search run while scrolled deep into the library leaves the user
  // stranded at the bottom instead of looking at the fresh results.
  const [scrollToTopSignal, setScrollToTopSignal] = useState(0);

  // Media-type narrowing applied to both browse and search. 'all' = no filter.
  const [kind, setKind] = useState<MediaKind | 'all'>('all');
  // Mirrored into a ref so refresh()/loadMore()/runSearch() (kept stable) read
  // the current filter without being re-created on every kind change.
  const kindRef = useRef<MediaKind | 'all'>('all');
  kindRef.current = kind;
  const kindArg = () => (kindRef.current === 'all' ? undefined : kindRef.current);

  const cancelRef = useRef(false);
  const hasMoreRef = useRef(true);
  const loadingMoreRef = useRef(false);
  // Latest query text, mirrored into a ref so an in-flight runSearch can tell —
  // once it finally resolves — whether the box still holds the text it searched
  // for. Used both here and by the kind effect below.
  const queryRef = useRef('');
  queryRef.current = query;
  // How many recents are currently loaded, mirrored into a ref so refresh()
  // (stable, no deps) can re-fetch the same span without losing the user's
  // scroll position — important because background indexing of a freshly shared
  // meme fires refresh repeatedly.
  const loadedCountRef = useRef(PAGE);

  const refresh = useCallback(async () => {
    setFolders(await getFolders());
    const k = kindRef.current === 'all' ? undefined : kindRef.current;
    const span = Math.max(PAGE, loadedCountRef.current);
    const rows = await getRecentMemes(span, 0, k);
    setRecent((prev) => mergeRecords(prev, rows));
    loadedCountRef.current = rows.length;
    // Only assume there's more to page in when we filled a clean page boundary.
    hasMoreRef.current = rows.length === span && rows.length % PAGE === 0;
    setCount(await countMemes());
    setTaughtLabels(await getLabels().catch(() => []));
    setLibraryTags(await getLibraryTagLabels().catch(() => []));
  }, []);

  // Coalesce library-changed bursts. Background indexing/sharing can fire
  // onLibraryChanged many times in quick succession (once per meme); without
  // this, each one kicked off a full re-fetch + re-render while you were trying
  // to scroll. Debouncing collapses a burst into a single refresh.
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const scheduleRefresh = useCallback(() => {
    if (refreshTimer.current) clearTimeout(refreshTimer.current);
    refreshTimer.current = setTimeout(() => {
      refreshTimer.current = null;
      refresh();
    }, 300);
  }, [refresh]);

  useEffect(() => {
    refresh();
    // Refresh (debounced) when memes are shared/indexed into the app.
    const unsub = onLibraryChanged(scheduleRefresh);
    return () => {
      unsub();
      if (refreshTimer.current) clearTimeout(refreshTimer.current);
    };
  }, [refresh, scheduleRefresh]);

  const loadMore = useCallback(async () => {
    if (results !== null) return; // pagination only while browsing
    if (loadingMoreRef.current || !hasMoreRef.current) return;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    try {
      const next = await getRecentMemes(PAGE, recent.length, kindArg());
      hasMoreRef.current = next.length === PAGE;
      setRecent((cur) => {
        const merged = [...cur, ...next];
        loadedCountRef.current = merged.length;
        return merged;
      });
    } finally {
      loadingMoreRef.current = false;
      setLoadingMore(false);
    }
  }, [recent.length, results]);

  // Run a search for the given text. Empty text drops back to browse mode.
  const runSearch = useCallback(
    async (text: string) => {
      const q = text.trim();
      if (!q) {
        setResults(null);
        setSearching(false);
        return;
      }
      if (!emb.ready) return;
      // Tell the idle loops (DINO backfill, paced describes) to stand down —
      // they were starving the text embed this search needs.
      noteInteractive();
      setSearching(true);
      setScrollToTopSignal((n) => n + 1);
      // The scan aborts itself the moment a newer keystroke supersedes this
      // query (returns null), so stale full scans don't pile up on the JS
      // thread behind the latest one.
      const stale = () => queryRef.current.trim() !== q;
      try {
        // The text embed competes for CPU with whatever generation is already
        // in flight. If it takes noticeably long, serve lexical-only results
        // (OCR/tags/captions/filenames) immediately, then upgrade to the full
        // hybrid ranking when the vector lands.
        const vecPromise = emb.embedText(q);
        const TIMED_OUT = Symbol('embed-timeout');
        const first = await Promise.race([
          vecPromise,
          new Promise<typeof TIMED_OUT>((r) => setTimeout(() => r(TIMED_OUT), 1_200)),
        ]);
        if (first === TIMED_OUT) {
          const quick = await searchByVector(null, q, 80, kindArg(), stale);
          if (quick !== null && !stale()) setResults(quick);
        }
        const vec = await vecPromise;
        if (stale()) return;
        const hits = await searchByVector(vec, q, 80, kindArg(), stale);
        // Embedding + brute-force search are async and on-device, so they can
        // resolve long after the box was cleared or retyped. If the current
        // query no longer matches what we searched for, drop these results —
        // otherwise we'd clobber browse mode back into "N results for ''", or
        // let a slow earlier search overwrite a newer one.
        if (hits === null || stale()) return;
        setResults(hits);
      } catch {
        // Embed failed (model unloading, OOM) — keep whatever lexical results
        // are already showing rather than blanking the grid.
      } finally {
        // Only clear the spinner if this is still the active search; a superseded
        // run bailing out shouldn't yank the indicator from the live one.
        if (queryRef.current.trim() === q) setSearching(false);
      }
    },
    [emb]
  );

  // Keep the latest runSearch in a ref so the debounce effect below depends
  // ONLY on the query text. If it depended on runSearch (a new function each
  // render), every search would re-render → re-arm the timer → search again,
  // flickering "Searching…" ↔ "N results" forever.
  const runSearchRef = useRef(runSearch);
  runSearchRef.current = runSearch;

  // Debounce so the grid narrows as you type without a search per keystroke.
  useEffect(() => {
    if (!query.trim()) {
      setResults(null);
      // A search in flight when the box is cleared bails without clearing its
      // own spinner (it's no longer the active query), so reset it here.
      setSearching(false);
      return;
    }
    const id = setTimeout(() => runSearchRef.current(query), 350);
    return () => clearTimeout(id);
  }, [query]);

  const searchLabel = useCallback((label: string) => {
    tap();
    // Toggle: tapping the active chip clears the filter.
    setQuery((cur) => (cur.trim() === label ? '' : label));
  }, []);

  // Tapping a media chip toggles that filter (tap again to clear back to 'all').
  const toggleKind = useCallback((k: MediaKind) => {
    tap();
    setKind((cur) => (cur === k ? 'all' : k));
  }, []);

  // When the media filter changes, re-fetch the browse page and re-run any
  // active search through the new filter. Skips the initial mount (the refresh
  // effect above already loads the unfiltered library once).
  const didMountKind = useRef(false);
  useEffect(() => {
    if (!didMountKind.current) {
      didMountKind.current = true;
      return;
    }
    loadedCountRef.current = PAGE; // drop back to a single page for the new filter
    refresh();
    const q = queryRef.current.trim();
    if (q) runSearchRef.current(q);
  }, [kind, refresh]);

  const onLink = useCallback(async () => {
    try {
      const picked = await pickFolder();
      if (!picked) return;
      await addFolder(picked.uri, picked.name);
      await refresh();
      success();
      showToast(`Linked “${picked.name}” — tap Index to scan it`, 'success');
    } catch (e) {
      showToast(`Could not link folder: ${String(e)}`, 'error');
    }
  }, [refresh]);

  const onIndex = useCallback(async () => {
    if (indexing) return;
    const embApi = embRef.current;
    if (!embApi.ready) {
      showToast('The on-device model is still preparing — try again shortly', 'info');
      return;
    }
    if (folders.length === 0) {
      showToast('Link a folder first', 'info');
      return;
    }
    thud();
    cancelRef.current = false;
    setIndexing(true);
    try {
      const res = await runIndex(embApi, {
        onProgress: setProgress,
        shouldCancel: () => cancelRef.current,
      });
      // Old-space taught examples were re-based during the index; apply them.
      // Keep the progress card honest (this pass used to run in silence after
      // the bar filled, which read as a hang) and let Stop cancel it.
      if (res.migratedExemplars > 0 && embApi.ready) {
        setProgress({
          processed: 1,
          total: 1,
          added: res.added,
          current: 'applying migrated tags to the library…',
        });
        await retagAll(embApi, { shouldCancel: () => cancelRef.current });
      }
      await refresh();
      // Tell the other providers (notably the demand-loaded VLM, which summons
      // itself when pending describe work appears) that the library changed.
      emitLibraryChanged();
      success();
      const errNote = res.errors > 0 ? ` · ${res.errors} failed` : '';
      const migNote =
        res.migratedExemplars > 0 ? ` · ${res.migratedExemplars} taught examples migrated` : '';
      showToast(`Indexed ${res.added} new · ${res.skipped} already known${errNote}${migNote}`, 'success');
    } catch (e) {
      showToast(`Indexing failed: ${String(e)}`, 'error');
    } finally {
      setIndexing(false);
      setProgress(null);
    }
  }, [folders.length, indexing, refresh]);

  const isSearch = results !== null;
  const hasLibrary = count > 0 || recent.length > 0;

  // Quick-filter chips: the labels the user has taught (starred) first, then the
  // known meme tags the indexer actually applied across the library — so people
  // can narrow to a recognized format/character by tapping instead of typing.
  const tagChips = useMemo(() => {
    const taught = new Set(taughtLabels);
    return [
      ...taughtLabels.map((label) => ({ label, taught: true })),
      ...libraryTags.filter((l) => !taught.has(l)).map((label) => ({ label, taught: false })),
    ];
  }, [taughtLabels, libraryTags]);

  // Scrolling part of the page (lives inside the grid as its header). Memoized
  // so an unrelated re-render (notably the CLIP model's load-progress ticks on a
  // post-update launch) doesn't hand the FlatList a brand-new header element to
  // reconcile mid-scroll — `emb.ready` is the only model field it reads, and
  // that flips just once.
  const header = useMemo(
    () => (
    <View style={styles.listHeader}>
      {isSearch ? (
        <View style={styles.resultRow}>
          {searching ? (
            <>
              <ActivityIndicator size="small" color={colors.volt} />
              <Text style={styles.resultText}>Searching on-device…</Text>
            </>
          ) : (
            <Text style={styles.resultText}>
              <Text style={styles.resultCount}>{results.length}</Text>
              {` result${results.length === 1 ? '' : 's'} for “${query.trim()}”`}
            </Text>
          )}
        </View>
      ) : (
        <>
          {indexing ? (
            <View style={styles.progressCard}>
              <View style={styles.progressTopRow}>
                <Text style={styles.progressTitle}>
                  {progress
                    ? `Indexing ${progress.processed}/${progress.total || '…'}` +
                      (progress.added > 0 ? `  ·  ${progress.added} new` : '')
                    : 'Preparing to index…'}
                </Text>
                <Pressable onPress={() => (cancelRef.current = true)} hitSlop={10}>
                  <Text style={styles.stopText}>Stop</Text>
                </Pressable>
              </View>
              <ProgressBar value={progress?.total ? progress.processed / progress.total : 0} />
              {!!progress?.current && (
                <Text style={styles.progressFile} numberOfLines={1}>
                  {progress.current}
                </Text>
              )}
            </View>
          ) : hasLibrary ? (
            <View style={styles.toolbar}>
              <Text style={styles.toolbarInfo}>
                {count} meme{count === 1 ? '' : 's'}
                {folders.length > 0 ? ` · ${folders.length} folder${folders.length > 1 ? 's' : ''}` : ''}
              </Text>
              <View style={styles.toolbarBtns}>
                <Button small variant="secondary" label="Link" icon="＋" onPress={onLink} />
                <Button small variant="primary" label="Index" icon="⟳" onPress={onIndex} />
              </View>
            </View>
          ) : (
            <Onboarding
              hasFolder={folders.length > 0}
              modelReady={emb.ready}
              onLink={onLink}
              onIndex={onIndex}
            />
          )}
        </>
      )}
    </View>
    ),
    [isSearch, searching, results, query, indexing, progress, hasLibrary, count, folders, emb.ready, onLink, onIndex]
  );

  const onTaught = useCallback(
    async (label: string) => {
      const embApi = embRef.current;
      if (embApi.ready) await retagAll(embApi);
      await refresh();
      const q = queryRef.current.trim();
      if (q) await runSearchRef.current(q);
      return countMemesWithLabel(label);
    },
    [refresh]
  );

  const onDeleted = useCallback((id: number) => {
    setRecent((cur) => {
      const next = cur.filter((m) => m.id !== id);
      loadedCountRef.current = next.length;
      return next;
    });
    setResults((cur) => (cur ? cur.filter((m) => m.id !== id) : cur));
    setCount((c) => Math.max(0, c - 1));
  }, []);

  // Memoized for the same reason as `header`: keep the element reference stable
  // across model-load re-renders so it never busts MemeGrid's memoization.
  const emptyState = useMemo(
    () =>
      isSearch && !searching && results.length === 0 ? (
        <View style={styles.noResults}>
          <Text style={styles.noResultsGlyph}>¯\_(ツ)_/¯</Text>
          <Text style={styles.noResultsTitle}>Nothing matched</Text>
          <Text style={styles.noResultsHint}>
            Try fewer words, a vibe (“sad frog”), or text you remember from the meme.
          </Text>
        </View>
      ) : !isSearch && hasLibrary && recent.length === 0 && kind !== 'all' ? (
        <View style={styles.noResults}>
          <Text style={styles.noResultsGlyph}>{kind === 'video' ? '▶' : '▦'}</Text>
          <Text style={styles.noResultsTitle}>No {kind === 'video' ? 'videos' : 'images'} here</Text>
          <Text style={styles.noResultsHint}>
            Tap “{kind === 'video' ? '▶ Videos' : '▦ Images'}” again to show everything.
          </Text>
        </View>
      ) : null,
    [isSearch, searching, results, hasLibrary, recent.length, kind]
  );

  return (
    <View style={styles.root}>
      {/* Fixed chrome: brand, search, quick filters. */}
      <View style={styles.topArea}>
        <View style={styles.brandRow}>
          <Text style={styles.brand}>
            Memeget<Text style={styles.brandDot}>.</Text>
          </Text>
          <ModelBadge ready={emb.ready} progress={emb.progress} error={emb.error} />
        </View>

        <View style={styles.searchRow}>
          <Text style={styles.searchGlyph}>⌕</Text>
          <TextInput
            style={styles.search}
            value={query}
            onChangeText={setQuery}
            placeholder={hasLibrary ? `Search ${count} memes…` : 'Search your memes…'}
            placeholderTextColor={colors.muted}
            returnKeyType="search"
            autoCapitalize="none"
            autoCorrect={false}
            onSubmitEditing={() => runSearch(query)}
          />
          {query.length > 0 && (
            <Pressable style={styles.clearBtn} onPress={() => setQuery('')} hitSlop={8}>
              <Text style={styles.clearIcon}>✕</Text>
            </Pressable>
          )}
        </View>

        {hasLibrary && (
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.chipRow}
            keyboardShouldPersistTaps="handled"
          >
            <Chip label="▦ Images" active={kind === 'image'} onPress={() => toggleKind('image')} />
            <Chip label="▶ Videos" active={kind === 'video'} onPress={() => toggleKind('video')} />
            {tagChips.length > 0 && <View style={styles.chipDivider} />}
            {tagChips.map((c) => (
              <Chip
                key={c.label}
                label={c.label}
                taught={c.taught}
                active={query.trim() === c.label}
                onPress={() => searchLabel(c.label)}
              />
            ))}
          </ScrollView>
        )}
      </View>

      <MemeGrid
        items={results ?? recent}
        header={header}
        onTaught={onTaught}
        onEndReached={loadMore}
        loadingMore={loadingMore}
        onDeleted={onDeleted}
        onSearchLabel={searchLabel}
        scrollToTopSignal={scrollToTopSignal}
        emptyState={emptyState}
      />
    </View>
  );
}

// Compact model state pill in the brand row; details live in Settings.
function ModelBadge({ ready, progress, error }: { ready: boolean; progress: number; error: string | null }) {
  if (error) return <StatusDot tone="bad" label="model error" />;
  if (ready) return <StatusDot tone="good" label="on-device" />;
  const pct = Math.round((progress || 0) * 100);
  return <StatusDot tone="busy" label={pct > 0 ? `model ${pct}%` : 'model loading'} />;
}

// First-run guidance: a 1-2-3 card that walks straight into the two actions.
function Onboarding({
  hasFolder,
  modelReady,
  onLink,
  onIndex,
}: {
  hasFolder: boolean;
  modelReady: boolean;
  onLink: () => void;
  onIndex: () => void;
}) {
  return (
    <View style={styles.onboard}>
      <Text style={styles.onboardEmoji}>🐸</Text>
      <Text style={styles.onboardTitle}>Your stash, searchable</Text>
      <Text style={styles.onboardBody}>
        Memeget indexes a folder of memes entirely on your phone — no uploads, no account — then finds
        any of them by vibe, character, or the text inside.
      </Text>
      <View style={styles.steps}>
        <Step n="1" done={hasFolder} text="Link the folder where your memes live" />
        <Step n="2" done={false} text="Index it (one-time scan, all on-device)" />
        <Step n="3" done={false} text="Search “sad frog”, “galaxy brain”, anything" />
      </View>
      {hasFolder ? (
        <Button label={modelReady ? 'Index my memes' : 'Index (model still loading…)'} onPress={onIndex} />
      ) : (
        <Button label="Link a folder" icon="＋" onPress={onLink} />
      )}
      {hasFolder && <Button variant="ghost" small label="＋ Link another folder" onPress={onLink} />}
    </View>
  );
}

function Step({ n, done, text }: { n: string; done: boolean; text: string }) {
  return (
    <View style={styles.step}>
      <View style={[styles.stepBadge, done && styles.stepBadgeDone]}>
        <Text style={[styles.stepN, done && styles.stepNDone]}>{done ? '✓' : n}</Text>
      </View>
      <Text style={styles.stepText}>{text}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  topArea: { paddingHorizontal: space.lg, paddingTop: space.sm, gap: space.md, paddingBottom: space.sm },
  brandRow: { flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'space-between' },
  brand: { color: colors.text, ...type.display },
  brandDot: { color: colors.volt },

  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surface2,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: 14,
  },
  searchGlyph: { color: colors.muted, fontSize: 18, marginRight: 6, fontWeight: '700' },
  search: { flex: 1, paddingVertical: 12, color: colors.text, fontSize: 15 },
  clearBtn: {
    width: 26,
    height: 26,
    borderRadius: 13,
    backgroundColor: colors.surface3,
    alignItems: 'center',
    justifyContent: 'center',
  },
  clearIcon: { color: colors.textDim, fontSize: 12, fontWeight: '700' },
  chipRow: { gap: space.sm, paddingRight: space.lg, alignItems: 'center' },
  chipDivider: { width: 1, alignSelf: 'stretch', marginVertical: 5, backgroundColor: colors.border },

  listHeader: { paddingHorizontal: space.md, paddingBottom: space.sm, gap: space.md },
  resultRow: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 2 },
  resultText: { color: colors.muted, fontSize: 13, fontWeight: '600' },
  resultCount: { color: colors.volt, fontWeight: '800' },

  toolbar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  toolbarInfo: { color: colors.muted, fontSize: 13, fontWeight: '600', flexShrink: 1 },
  toolbarBtns: { flexDirection: 'row', gap: space.sm },

  progressCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    padding: space.lg,
    gap: 10,
  },
  progressTopRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  progressTitle: { color: colors.text, fontSize: 13, fontWeight: '700' },
  stopText: { color: colors.danger, fontSize: 13, fontWeight: '700' },
  progressFile: { color: colors.faint, fontSize: 11 },

  onboard: {
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    borderWidth: 1,
    borderColor: colors.border,
    padding: space.xl,
    gap: space.md,
    marginTop: space.sm,
  },
  onboardEmoji: { fontSize: 40 },
  onboardTitle: { color: colors.text, fontSize: 20, fontWeight: '800', letterSpacing: -0.3 },
  onboardBody: { color: colors.muted, fontSize: 13, lineHeight: 19 },
  steps: { gap: 10, marginVertical: 4 },
  step: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  stepBadge: {
    width: 22,
    height: 22,
    borderRadius: 11,
    backgroundColor: colors.surface3,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepBadgeDone: { backgroundColor: colors.goodDim },
  stepN: { color: colors.textDim, fontSize: 11, fontWeight: '800' },
  stepNDone: { color: colors.good },
  stepText: { color: colors.textDim, fontSize: 13, flex: 1 },

  noResults: { alignItems: 'center', paddingVertical: 48, paddingHorizontal: 32, gap: 8 },
  noResultsGlyph: { color: colors.faint, fontSize: 22, marginBottom: 6 },
  noResultsTitle: { color: colors.text, fontSize: 16, fontWeight: '700' },
  noResultsHint: { color: colors.muted, fontSize: 13, textAlign: 'center', lineHeight: 19 },
});
