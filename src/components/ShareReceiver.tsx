import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { useShareIntent, type ShareIntentFile } from 'expo-share-intent';

import { useEmbeddings } from '../embeddings';
import { importSharedFiles } from '../indexer';
import { emitLibraryChanged } from '../events';
import { colors, radius, shadow, TABBAR_CLEARANCE } from '../theme';

type Status =
  | { kind: 'importing'; msg: string }
  | { kind: 'done'; msg: string }
  | { kind: 'error'; msg: string };

// Listens for images/videos shared into the app from other apps (Android
// ACTION_SEND). On receipt it copies each into the linked folder and indexes
// it, showing a small status banner. Lives inside EmbeddingsProvider so the
// import can reuse the loaded CLIP model.
export function ShareReceiver() {
  const emb = useEmbeddings();
  const { hasShareIntent, shareIntent, resetShareIntent } = useShareIntent({ resetOnBackground: true });
  const [status, setStatus] = useState<Status | null>(null);
  const busyRef = useRef(false);

  useEffect(() => {
    const files = (shareIntent?.files ?? []).filter((f: ShareIntentFile) =>
      /^(image|video)\//.test(f.mimeType)
    );
    if (!hasShareIntent || files.length === 0) return;
    if (busyRef.current) return;

    // Wait for the on-device model before indexing; this re-runs once it's ready.
    if (!emb.ready) {
      setStatus({ kind: 'importing', msg: 'Preparing the on-device model…' });
      return;
    }

    busyRef.current = true;
    const total = files.length;
    setStatus({ kind: 'importing', msg: total > 1 ? `Adding 0/${total} memes…` : 'Adding meme…' });
    (async () => {
      try {
        const res = await importSharedFiles(
          emb,
          files.map((f: ShareIntentFile) => ({
            path: f.path,
            fileName: f.fileName,
            mimeType: f.mimeType,
          })),
          {
            onProgress: (done, t) => {
              if (t > 1 && done < t) setStatus({ kind: 'importing', msg: `Adding ${done}/${t} memes…` });
            },
          }
        );
        emitLibraryChanged();
        const errNote = res.errors > 0 ? ` (${res.errors} failed)` : '';
        setStatus({ kind: 'done', msg: `Added ${res.added} to “${res.folderName}”${errNote}` });
      } catch (e) {
        setStatus({ kind: 'error', msg: String((e as Error)?.message ?? e) });
      } finally {
        resetShareIntent();
        busyRef.current = false;
        setTimeout(() => setStatus(null), 3200);
      }
    })();
  }, [hasShareIntent, shareIntent, emb.ready, emb, resetShareIntent]);

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
