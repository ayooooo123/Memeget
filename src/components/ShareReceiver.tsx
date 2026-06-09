import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { useShareIntent, type ShareIntentFile } from 'expo-share-intent';

import { useEmbeddings } from '../embeddings';
import { importSharedFile } from '../indexer';
import { emitLibraryChanged } from '../events';
import { colors } from '../theme';

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
    setStatus({ kind: 'importing', msg: `Adding ${files.length} meme${files.length > 1 ? 's' : ''}…` });
    (async () => {
      let added = 0;
      let folderName = '';
      try {
        for (const f of files) {
          const res = await importSharedFile(emb, {
            path: f.path,
            fileName: f.fileName,
            mimeType: f.mimeType,
          });
          folderName = res.folderName;
          added++;
        }
        emitLibraryChanged();
        setStatus({ kind: 'done', msg: `Added ${added} to “${folderName}”` });
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
    status.kind === 'error' ? colors.danger : status.kind === 'done' ? colors.accent2 : colors.accent;
  return (
    <View style={[styles.banner, { borderColor: tone }]} pointerEvents="none">
      {status.kind === 'importing' && <ActivityIndicator color={tone} />}
      <Text style={[styles.text, { color: tone }]} numberOfLines={2}>
        {status.kind === 'done' ? '✓ ' : status.kind === 'error' ? '⚠ ' : ''}
        {status.msg}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    position: 'absolute',
    left: 12,
    right: 12,
    bottom: 76,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  text: { flex: 1, fontSize: 13, fontWeight: '700' },
});
