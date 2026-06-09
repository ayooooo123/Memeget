import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { MemeGrid } from '../components/MemeGrid';
import { useEmbeddings } from '../embeddings';
import {
  addFolder,
  countMemes,
  countMemesWithLabel,
  getFolders,
  getRecentMemes,
  searchByVector,
} from '../db';
import { runIndex, retagAll, type IndexProgress } from '../indexer';
import { onLibraryChanged } from '../events';
import { pickFolder } from '../saf';
import { colors } from '../theme';
import type { LinkedFolder, MemeRecord, SearchHit } from '../types';

const PAGE = 90;

export function LibraryScreen() {
  const emb = useEmbeddings();
  const [folders, setFolders] = useState<LinkedFolder[]>([]);
  const [recent, setRecent] = useState<MemeRecord[]>([]);
  const [count, setCount] = useState(0);
  const [indexing, setIndexing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [progress, setProgress] = useState<IndexProgress | null>(null);

  // Search-as-filter on the same page: empty query => browse recents,
  // non-empty => semantic results replace the grid.
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchHit[] | null>(null);
  const [searching, setSearching] = useState(false);

  const cancelRef = useRef(false);
  const hasMoreRef = useRef(true);
  const loadingMoreRef = useRef(false);

  const refresh = useCallback(async () => {
    setFolders(await getFolders());
    const first = await getRecentMemes(PAGE, 0);
    setRecent(first);
    hasMoreRef.current = first.length === PAGE;
    setCount(await countMemes());
  }, []);

  useEffect(() => {
    refresh();
    // Refresh when a meme is shared into the app from elsewhere.
    return onLibraryChanged(refresh);
  }, [refresh]);

  const loadMore = useCallback(async () => {
    if (results !== null) return; // pagination only while browsing
    if (loadingMoreRef.current || !hasMoreRef.current) return;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    try {
      const next = await getRecentMemes(PAGE, recent.length);
      hasMoreRef.current = next.length === PAGE;
      setRecent((cur) => [...cur, ...next]);
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
      setSearching(true);
      try {
        const vec = await emb.embedText(q);
        setResults(await searchByVector(vec, q, 80));
      } finally {
        setSearching(false);
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
      return;
    }
    const id = setTimeout(() => runSearchRef.current(query), 350);
    return () => clearTimeout(id);
  }, [query]);

  const onLink = useCallback(async () => {
    try {
      const picked = await pickFolder();
      if (!picked) return;
      await addFolder(picked.uri, picked.name);
      await refresh();
      Alert.alert('Folder linked', `"${picked.name}" added. Tap Index to process it.`);
    } catch (e) {
      Alert.alert('Could not link folder', String(e));
    }
  }, [refresh]);

  const onIndex = useCallback(async () => {
    if (!emb.ready) {
      Alert.alert('Model still loading', 'The on-device CLIP model is still preparing. Try again shortly.');
      return;
    }
    if (folders.length === 0) {
      Alert.alert('No folders', 'Link a folder first.');
      return;
    }
    cancelRef.current = false;
    setIndexing(true);
    try {
      const res = await runIndex(emb, {
        onProgress: setProgress,
        shouldCancel: () => cancelRef.current,
      });
      await refresh();
      Alert.alert('Indexing complete', `Added ${res.added}, skipped ${res.skipped}, errors ${res.errors}.`);
    } catch (e) {
      Alert.alert('Indexing failed', String(e));
    } finally {
      setIndexing(false);
      setProgress(null);
    }
  }, [emb, folders.length, refresh]);

  const header = (
    <View style={styles.header}>
      <View style={styles.searchRow}>
        <TextInput
          style={styles.search}
          value={query}
          onChangeText={setQuery}
          placeholder="Search your memes…"
          placeholderTextColor={colors.muted}
          returnKeyType="search"
          autoCapitalize="none"
          onSubmitEditing={() => runSearch(query)}
        />
        {query.length > 0 && (
          <Pressable style={styles.clearBtn} onPress={() => setQuery('')} hitSlop={8}>
            <Text style={styles.clearIcon}>✕</Text>
          </Pressable>
        )}
      </View>

      {results !== null ? (
        <Text style={styles.muted}>
          {searching ? 'Searching on-device…' : `${results.length} result${results.length === 1 ? '' : 's'} for “${query.trim()}”`}
          {!emb.ready && ' · model still loading'}
        </Text>
      ) : (
        <>
          <ModelStatus ready={emb.ready} progress={emb.progress} error={emb.error} />
          <View style={styles.row}>
            <Pressable style={[styles.btn, styles.btnGhost]} onPress={onLink}>
              <Text style={styles.btnGhostText}>+ Link folder</Text>
            </Pressable>
            <Pressable
              style={[styles.btn, styles.btnPrimary, indexing && styles.btnDisabled]}
              onPress={indexing ? () => (cancelRef.current = true) : onIndex}
            >
              <Text style={styles.btnPrimaryText}>{indexing ? 'Stop' : 'Index now'}</Text>
            </Pressable>
          </View>

          {folders.length > 0 && (
            <Text style={styles.muted}>
              {folders.length} folder{folders.length > 1 ? 's' : ''} linked · {count} indexed
            </Text>
          )}

          {indexing && progress && (
            <View style={styles.progress}>
              <ActivityIndicator color={colors.accent} />
              <Text style={styles.muted} numberOfLines={1}>
                {progress.processed}/{progress.total} · +{progress.added} · {progress.current}
              </Text>
            </View>
          )}

          {recent.length === 0 && !indexing && (
            <View style={styles.empty}>
              <Text style={styles.emptyTitle}>No memes indexed yet</Text>
              <Text style={styles.muted}>
                Link a folder of memes, then tap “Index now”. Everything is processed on your phone — no
                network, no uploads.
              </Text>
            </View>
          )}
        </>
      )}
    </View>
  );

  const onTaught = useCallback(
    async (label: string) => {
      if (emb.ready) await retagAll(emb);
      await refresh();
      if (query.trim()) await runSearch(query);
      return countMemesWithLabel(label);
    },
    [emb, refresh, query, runSearch]
  );

  return (
    <MemeGrid
      items={results ?? recent}
      header={header}
      onTaught={onTaught}
      onEndReached={loadMore}
      loadingMore={loadingMore}
    />
  );
}

function ModelStatus({ ready, progress, error }: { ready: boolean; progress: number; error: string | null }) {
  if (error) return <Text style={[styles.status, { color: colors.danger }]}>Model error: {error}</Text>;
  if (ready) return <Text style={[styles.status, { color: colors.accent2 }]}>● CLIP model ready (on-device)</Text>;
  const pct = Math.round((progress || 0) * 100);
  return <Text style={styles.status}>Preparing on-device model… {pct > 0 ? `${pct}%` : ''}</Text>;
}

const styles = StyleSheet.create({
  header: { padding: 12, gap: 10 },
  searchRow: { flexDirection: 'row', alignItems: 'center' },
  search: {
    flex: 1,
    backgroundColor: colors.surface2,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 11,
    color: colors.text,
    fontSize: 15,
  },
  clearBtn: {
    position: 'absolute',
    right: 8,
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: colors.surface,
    alignItems: 'center',
    justifyContent: 'center',
  },
  clearIcon: { color: colors.muted, fontSize: 13, fontWeight: '700' },
  status: { color: colors.muted, fontSize: 12, fontWeight: '600' },
  row: { flexDirection: 'row', gap: 10 },
  btn: { flex: 1, paddingVertical: 12, borderRadius: 12, alignItems: 'center' },
  btnPrimary: { backgroundColor: colors.accent },
  btnPrimaryText: { color: '#0b0d12', fontWeight: '800' },
  btnGhost: { borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface },
  btnGhostText: { color: colors.text, fontWeight: '700' },
  btnDisabled: { opacity: 0.6 },
  muted: { color: colors.muted, fontSize: 12 },
  progress: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  empty: { paddingVertical: 32, gap: 6 },
  emptyTitle: { color: colors.text, fontWeight: '700', fontSize: 15 },
});
