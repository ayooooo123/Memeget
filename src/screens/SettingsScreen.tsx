import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import { useEmbeddings } from '../embeddings';
import { clearIndex, countMemes, getFolders, removeFolder } from '../db';
import { MEME_LABELS } from '../memeLabels';
import { colors } from '../theme';
import type { LinkedFolder } from '../types';

export function SettingsScreen() {
  const emb = useEmbeddings();
  const [folders, setFolders] = useState<LinkedFolder[]>([]);
  const [count, setCount] = useState(0);

  const refresh = useCallback(async () => {
    setFolders(await getFolders());
    setCount(await countMemes());
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const onClear = useCallback(() => {
    Alert.alert('Clear index?', 'Removes all processed memes from the local database. Your actual files are untouched.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Clear',
        style: 'destructive',
        onPress: async () => {
          await clearIndex();
          await refresh();
        },
      },
    ]);
  }, [refresh]);

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Section title="On-device model">
        <Row label="CLIP (image + text)" value={emb.ready ? 'Ready' : emb.error ? 'Error' : 'Loading'} />
        <Text style={styles.note}>
          Runs fully on your device via ExecuTorch. The model binary downloads once on first launch,
          then everything — indexing and search — happens offline with no network calls.
        </Text>
      </Section>

      <Section title="Index">
        <Row label="Indexed memes" value={String(count)} />
        <Row label="Known meme formats" value={String(MEME_LABELS.length)} />
        <Pressable style={styles.danger} onPress={onClear}>
          <Text style={styles.dangerText}>Clear index</Text>
        </Pressable>
      </Section>

      <Section title={`Linked folders (${folders.length})`}>
        {folders.length === 0 ? (
          <Text style={styles.note}>None yet. Link folders from the Library tab.</Text>
        ) : (
          folders.map((f) => (
            <View key={f.uri} style={styles.folderRow}>
              <Text style={styles.folderName} numberOfLines={1}>
                {f.name}
              </Text>
              <Pressable
                onPress={async () => {
                  await removeFolder(f.uri);
                  refresh();
                }}
              >
                <Text style={styles.unlink}>Unlink</Text>
              </Pressable>
            </View>
          ))
        )}
      </Section>

      <Section title="Privacy">
        <Text style={styles.note}>
          Memeget never uploads your memes. Folder access is granted per-folder through Android’s
          Storage Access Framework, and the search index lives only in this app’s local database.
        </Text>
        <Pressable onPress={() => Linking.openSettings()}>
          <Text style={styles.link}>Open app settings</Text>
        </Pressable>
      </Section>

      <Text style={styles.version}>Memeget 0.1 · on-device meme indexing</Text>
    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      <View style={styles.card}>{children}</View>
    </View>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { padding: 12, gap: 18, paddingBottom: 40 },
  section: { gap: 8 },
  sectionTitle: { color: colors.muted, fontSize: 12, textTransform: 'uppercase', fontWeight: '700', marginLeft: 4 },
  card: { backgroundColor: colors.surface, borderRadius: 14, padding: 14, gap: 10 },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  rowLabel: { color: colors.text, fontSize: 14 },
  rowValue: { color: colors.accent, fontSize: 14, fontWeight: '700' },
  note: { color: colors.muted, fontSize: 12, lineHeight: 17 },
  link: { color: colors.accent, fontSize: 13, fontWeight: '600' },
  danger: { borderWidth: 1, borderColor: colors.danger, borderRadius: 10, paddingVertical: 10, alignItems: 'center' },
  dangerText: { color: colors.danger, fontWeight: '700' },
  folderRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 12 },
  folderName: { color: colors.text, flex: 1, fontSize: 13 },
  unlink: { color: colors.danger, fontSize: 13, fontWeight: '600' },
  version: { color: colors.muted, fontSize: 11, textAlign: 'center', marginTop: 8 },
});
