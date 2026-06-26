import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { useShareIntent, type ShareIntentFile } from 'expo-share-intent';

import { useEmbeddings } from '../embeddings';
import { indexSavedFiles, saveSharedFiles } from '../indexer';
import { emitLibraryChanged } from '../events';
import { colors, radius, shadow, TABBAR_CLEARANCE } from '../theme';
import type { SafFile } from '../saf';

type Status =
  | { kind: 'importing'; msg: string }
  | { kind: 'done'; msg: string }
  | { kind: 'error'; msg: string };

// Listens for images/videos shared into the app from other apps (Android
// ACTION_SEND) and adds them to the library in two phases so the user can
// leave the app the instant they share:
//
//   1. Save — copy the file into the linked folder. Fast (a file copy, no
//      model), runs immediately even while the CLIP model is still loading, and
//      makes the file permanent. Once this shows "Saved", the user is free to go.
//   2. Index — embed/OCR/tag in the background. Decoupled from the share event,
//      so backgrounding the app (which resets the share intent) never blocks it.
//      If it never gets to run, the saved file is still in the folder and the
//      next folder scan indexes it — nothing is lost.
//
// Lives inside EmbeddingsProvider so the index phase can reuse the loaded model.
export function ShareReceiver() {
  const emb = useEmbeddings();
  const { hasShareIntent, shareIntent, resetShareIntent } = useShareIntent({ resetOnBackground: true });
  const [status, setStatus] = useState<Status | null>(null);
  const savingRef = useRef(false);

  // Files saved to the folder but not yet indexed. A ref (the queue) plus a tick
  // (to wake the drain effect) so a new share while indexing just appends.
  const queueRef = useRef<SafFile[]>([]);
  const indexingRef = useRef(false);
  const [tick, setTick] = useState(0);

  // Phase 1: save the shared files the moment they arrive. No model wait.
  useEffect(() => {
    const files = (shareIntent?.files ?? []).filter((f: ShareIntentFile) =>
      /^(image|video)\//.test(f.mimeType)
    );
    if (!hasShareIntent || files.length === 0) return;
    if (savingRef.current) return;

    savingRef.current = true;
    const total = files.length;
    setStatus({ kind: 'importing', msg: total > 1 ? `Saving 0/${total} memes…` : 'Saving meme…' });
    (async () => {
      try {
        const res = await saveSharedFiles(
          files.map((f: ShareIntentFile) => ({
            path: f.path,
            fileName: f.fileName,
            mimeType: f.mimeType,
          })),
          {
            onProgress: (done, t) => {
              if (t > 1 && done < t) setStatus({ kind: 'importing', msg: `Saving ${done}/${t} memes…` });
            },
          }
        );
        // Hand the saved files to the background indexer and refresh the library.
        if (res.saved.length > 0) {
          queueRef.current.push(...res.saved);
          setTick((t) => t + 1);
        }
        emitLibraryChanged();
        const errNote = res.errors > 0 ? ` (${res.errors} failed)` : '';
        const n = res.saved.length;
        setStatus({
          kind: 'done',
          msg: `✓ Saved ${n} to “${res.folderName}” — indexing in background${errNote}`,
        });
      } catch (e) {
        setStatus({ kind: 'error', msg: String((e as Error)?.message ?? e) });
      } finally {
        // Reset right away so the share intent clears and the user can move on.
        resetShareIntent();
        savingRef.current = false;
        setTimeout(() => setStatus(null), 3200);
      }
    })();
  }, [hasShareIntent, shareIntent, resetShareIntent]);

  // Phase 2: drain the queue once the model is ready. Serialized via indexingRef
  // so overlapping shares don't build knowledge twice; a share that lands mid-run
  // just appends and gets picked up by the same loop.
  const drain = useCallback(async () => {
    if (indexingRef.current || !emb.ready || queueRef.current.length === 0) return;
    indexingRef.current = true;
    try {
      while (queueRef.current.length > 0) {
        const batch = queueRef.current.splice(0, queueRef.current.length);
        await indexSavedFiles(emb, batch);
        emitLibraryChanged();
      }
    } catch {
      // Best-effort: anything missed stays in the folder for the next scan.
    } finally {
      indexingRef.current = false;
    }
  }, [emb]);

  useEffect(() => {
    drain();
  }, [tick, emb.ready, drain]);

  if (!status) return null;
  const tone =
    status.kind === 'error' ? colors.danger : status.kind === 'done' ? colors.good : colors.volt;
  return (
    <View style={styles.banner} pointerEvents="none">
      <View style={[styles.bar, { backgroundColor: tone }]} />
      {status.kind === 'importing' && <ActivityIndicator color={tone} size="small" />}
      <Text style={[styles.text, { color: status.kind === 'error' ? colors.danger : colors.text }]} numberOfLines={2}>
        {status.kind === 'done' ? '✓ ' : status.kind === 'error' ? '⚠ ' : ''}
        {status.msg}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    position: 'absolute',
    left: 16,
    right: 16,
    bottom: TABBAR_CLEARANCE + 16,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    backgroundColor: colors.surface2,
    borderWidth: 1,
    borderColor: colors.borderLight,
    borderRadius: radius.md,
    paddingRight: 14,
    overflow: 'hidden',
    ...shadow.float,
  },
  bar: { width: 3, alignSelf: 'stretch' },
  text: { flex: 1, fontSize: 13, fontWeight: '600', paddingVertical: 12, paddingLeft: 11 },
});
