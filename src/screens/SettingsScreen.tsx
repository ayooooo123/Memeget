import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';

import { showToast } from '../components/Toast';
import { Button, ProgressBar, StatusDot } from '../components/ui';
import { useEmbeddings } from '../embeddings';
import {
  clearIndex,
  countMemes,
  deleteExemplarsByLabel,
  getExemplars,
  getFolders,
  getIndexErrors,
  getTaughtLabelStats,
  importExemplars,
  removeFolder,
  type IndexError,
  type TaughtLabelStat,
} from '../db';
import { emitLibraryChanged } from '../events';
import { success, warn } from '../haptics';
import { retagAll } from '../indexer';
import { MEME_LABELS } from '../memeLabels';
import { buildPack, parsePack, serializePack } from '../teachingPack';
import { colors, radius, space, TABBAR_CLEARANCE } from '../theme';
import type { LinkedFolder } from '../types';

export function SettingsScreen({ active = true }: { active?: boolean }) {
  const emb = useEmbeddings();
  const [folders, setFolders] = useState<LinkedFolder[]>([]);
  const [count, setCount] = useState(0);
  const [taughtStats, setTaughtStats] = useState<TaughtLabelStat[]>([]);
  const [errors, setErrors] = useState<IndexError[]>([]);
  const [showErrors, setShowErrors] = useState(false);
  const [retagging, setRetagging] = useState<{ done: number; total: number } | null>(null);
  const [transferBusy, setTransferBusy] = useState(false);

  const refresh = useCallback(async () => {
    setFolders(await getFolders());
    setCount(await countMemes());
    setTaughtStats(await getTaughtLabelStats().catch(() => []));
    setErrors(await getIndexErrors());
  }, []);

  // Both tabs stay mounted (so the Library keeps its state), which means this
  // screen must refetch its stats whenever it becomes the visible tab.
  useEffect(() => {
    if (active) refresh();
  }, [active, refresh]);

  const onRetag = useCallback(async () => {
    if (!emb.ready) {
      showToast('Model still loading — try again shortly', 'info');
      return;
    }
    setRetagging({ done: 0, total: 0 });
    try {
      const res = await retagAll(emb, {
        onProgress: (done, total) => setRetagging({ done, total }),
      });
      success();
      emitLibraryChanged(); // tags changed under the Library's feet
      showToast(`Re-tagged ${res.updated} memes with current knowledge`, 'success');
    } catch (e) {
      showToast(`Re-tag failed: ${String(e)}`, 'error');
    } finally {
      setRetagging(null);
      refresh();
    }
  }, [emb, refresh]);

  // Write every taught example to a JSON pack and hand it to the share sheet so
  // an archiver can send their meme knowledge to anyone.
  const onExport = useCallback(async () => {
    if (transferBusy) return;
    setTransferBusy(true);
    try {
      const exemplars = await getExemplars();
      if (exemplars.length === 0) {
        showToast('Nothing to export yet — teach a tag first', 'info');
        return;
      }
      const pack = buildPack(exemplars, Date.now());
      const path = `${FileSystem.cacheDirectory}memeget-teachings-${Date.now()}.json`;
      await FileSystem.writeAsStringAsync(path, serializePack(pack));
      if (await Sharing.isAvailableAsync()) {
        await Sharing.shareAsync(path, {
          mimeType: 'application/json',
          dialogTitle: 'Share teaching pack',
          UTI: 'public.json',
        });
      } else {
        showToast(`Saved pack to ${path}`, 'info');
      }
      success();
    } catch (e) {
      showToast(`Export failed: ${String(e)}`, 'error');
    } finally {
      setTransferBusy(false);
    }
  }, [transferBusy]);

  // Pick a pack file, fold its examples into the local DB, then offer to re-tag
  // so the imported knowledge takes effect across the library.
  const onImport = useCallback(async () => {
    if (transferBusy) return;
    setTransferBusy(true);
    try {
      const res = await DocumentPicker.getDocumentAsync({
        type: ['application/json', 'text/plain', '*/*'],
        copyToCacheDirectory: true,
      });
      if (res.canceled) return;
      const asset = res.assets[0];
      if (!asset) return;
      const text = await FileSystem.readAsStringAsync(asset.uri);
      const pack = parsePack(text); // throws a readable message on bad input
      const { added, skipped } = await importExemplars(pack.exemplars);
      await refresh();
      success();
      const summary =
        added > 0
          ? `Imported ${added} example${added === 1 ? '' : 's'}` +
            (skipped ? ` (${skipped} already had)` : '')
          : 'Pack already fully imported — nothing new';
      if (added > 0 && emb.ready) {
        Alert.alert('Teaching imported', `${summary}. Re-tag your library now to apply it?`, [
          { text: 'Later', style: 'cancel', onPress: () => showToast(summary, 'success') },
          { text: 'Re-tag now', onPress: onRetag },
        ]);
      } else {
        showToast(summary, added > 0 ? 'success' : 'info');
      }
    } catch (e) {
      showToast(`Import failed: ${String(e)}`, 'error');
    } finally {
      setTransferBusy(false);
    }
  }, [transferBusy, refresh, emb.ready, onRetag]);

  const onForget = useCallback(
    (label: string) => {
      warn();
      Alert.alert(
        `Forget “${label}”?`,
        'Removes the examples you taught for this tag. Memes already tagged keep the label until the next re-tag.',
        [
          { text: 'Cancel', style: 'cancel' },
          {
            text: 'Forget',
            style: 'destructive',
            onPress: async () => {
              await deleteExemplarsByLabel(label);
              await refresh();
              showToast(`Forgot “${label}” — re-tag to drop it from memes`, 'info');
            },
          },
        ]
      );
    },
    [refresh]
  );

  const onClear = useCallback(() => {
    warn();
    Alert.alert(
      'Clear index?',
      'Removes all processed memes from the local database. Your actual files are untouched.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Clear',
          style: 'destructive',
          onPress: async () => {
            await clearIndex();
            await refresh();
            emitLibraryChanged(); // the Library grid must drop its rows too
            showToast('Index cleared', 'info');
          },
        },
      ]
    );
  }, [refresh]);

  const modelTone = emb.error ? 'bad' : emb.ready ? 'good' : 'busy';
  const modelLabel = emb.error
    ? 'Error'
    : emb.ready
      ? 'Ready'
      : `Loading ${Math.round((emb.progress || 0) * 100)}%`;

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.title}>Settings</Text>

      <Section glyph="✦" title="On-device model" tint={colors.volt}>
        <Row label="CLIP (image + text)">
          <StatusDot tone={modelTone} label={modelLabel} />
        </Row>
        {!emb.ready && !emb.error && <ProgressBar value={emb.progress || 0} />}
        {!!emb.error && <Text style={styles.errText}>{emb.error}</Text>}
        <Text style={styles.note}>
          Runs fully on your device via ExecuTorch. The model binary downloads once on first launch,
          then everything — indexing and search — happens offline with no network calls.
        </Text>
      </Section>

      <Section glyph="▦" title="Index" tint={colors.accent}>
        <Row label="Indexed memes" value={String(count)} />
        <Row label="Known meme formats" value={String(MEME_LABELS.length)} />
        {errors.length > 0 && (
          <Pressable onPress={() => setShowErrors((s) => !s)}>
            <Row label="Indexing errors" value={`${errors.length} ${showErrors ? '▴' : '▾'}`} valueTint={colors.danger} />
          </Pressable>
        )}
        {showErrors && errors.length > 0 && (
          <View style={styles.errBox}>
            {Object.entries(
              errors.reduce<Record<string, number>>((acc, e) => {
                const key = `${e.stage} · ${e.kind}`;
                acc[key] = (acc[key] ?? 0) + 1;
                return acc;
              }, {})
            ).map(([k, n]) => (
              <Row key={k} label={k} value={String(n)} />
            ))}
            {errors.slice(0, 8).map((e, i) => (
              <View key={i} style={styles.errRow}>
                <Text style={styles.errName} numberOfLines={1}>
                  {e.name}
                </Text>
                <Text style={styles.errReason} numberOfLines={2}>
                  [{e.stage}] {e.reason}
                </Text>
              </View>
            ))}
          </View>
        )}
        <Button variant="dangerGhost" small label="Clear index" onPress={onClear} />
      </Section>

      <Section glyph="★" title="Taught knowledge" tint={colors.good}>
        <Row
          label="Tags you've taught"
          value={String(taughtStats.length)}
          valueTint={colors.good}
        />
        {taughtStats.length === 0 ? (
          <Text style={styles.note}>
            Open any meme and use “This IS a…” to teach a new character or format by example (e.g.
            Milady). Re-tagging applies it across everything already indexed — no re-scanning, it
            reuses the embeddings on device.
          </Text>
        ) : (
          <View style={styles.taughtList}>
            {taughtStats.map((t) => (
              <View key={t.label} style={styles.taughtRow}>
                <View style={styles.taughtMain}>
                  <Text style={styles.taughtLabel} numberOfLines={1}>
                    {t.label}
                  </Text>
                  <Text style={styles.taughtMeta}>
                    {t.tagged} meme{t.tagged === 1 ? '' : 's'} tagged · {t.positives} example
                    {t.positives === 1 ? '' : 's'}
                    {t.negatives > 0 ? ` · ${t.negatives} correction${t.negatives === 1 ? '' : 's'}` : ''}
                  </Text>
                </View>
                <Pressable hitSlop={8} onPress={() => onForget(t.label)}>
                  <Text style={styles.taughtForget}>✕</Text>
                </Pressable>
              </View>
            ))}
          </View>
        )}

        {retagging ? (
          <View style={{ gap: 8 }}>
            <Text style={styles.note}>
              Re-tagging {retagging.done}/{retagging.total || '…'}
            </Text>
            <ProgressBar value={retagging.total ? retagging.done / retagging.total : 0} tint={colors.good} />
          </View>
        ) : (
          <Button small label="Re-tag library" onPress={onRetag} />
        )}

        <View style={styles.divider} />
        <Text style={styles.note}>
          Share your taught tags as a pack, or import someone else’s to inherit their meme knowledge
          instantly — the examples merge into yours, then re-tag to apply them.
        </Text>
        <View style={styles.transferRow}>
          <Button
            small
            variant="secondary"
            icon="⇪"
            label="Export"
            onPress={onExport}
            disabled={transferBusy}
            style={styles.transferBtn}
          />
          <Button
            small
            variant="secondary"
            icon="⇩"
            label="Import"
            onPress={onImport}
            disabled={transferBusy}
            style={styles.transferBtn}
          />
        </View>
      </Section>

      <Section glyph="🗂" title={`Linked folders (${folders.length})`} tint={colors.accent}>
        {folders.length === 0 ? (
          <Text style={styles.note}>None yet. Link folders from the Library tab.</Text>
        ) : (
          folders.map((f) => (
            <View key={f.uri} style={styles.folderRow}>
              <Text style={styles.folderName} numberOfLines={1}>
                {f.name}
              </Text>
              <Pressable
                hitSlop={8}
                onPress={async () => {
                  await removeFolder(f.uri);
                  refresh();
                  emitLibraryChanged();
                  showToast(`Unlinked “${f.name}” — already-indexed memes stay searchable`, 'info');
                }}
              >
                <Text style={styles.unlink}>Unlink</Text>
              </Pressable>
            </View>
          ))
        )}
      </Section>

      <Section glyph="🔒" title="Privacy" tint={colors.good}>
        <Text style={styles.note}>
          Memeget never uploads your memes. Folder access is granted per-folder through Android’s
          Storage Access Framework, and the search index lives only in this app’s local database.
        </Text>
        <Pressable onPress={() => Linking.openSettings()}>
          <Text style={styles.link}>Open app settings →</Text>
        </Pressable>
      </Section>

      <Text style={styles.version}>
        Memeget<Text style={{ color: colors.volt }}>.</Text> 0.1 · private, on-device meme search
      </Text>
    </ScrollView>
  );
}

function Section({
  glyph,
  title,
  tint,
  children,
}: {
  glyph: string;
  title: string;
  tint: string;
  children: React.ReactNode;
}) {
  return (
    <View style={styles.section}>
      <View style={styles.sectionHead}>
        <View style={[styles.glyphBox, { borderColor: tint }]}>
          <Text style={[styles.glyph, { color: tint }]}>{glyph}</Text>
        </View>
        <Text style={styles.sectionTitle}>{title}</Text>
      </View>
      <View style={styles.card}>{children}</View>
    </View>
  );
}

function Row({
  label,
  value,
  valueTint,
  children,
}: {
  label: string;
  value?: string;
  valueTint?: string;
  children?: React.ReactNode;
}) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      {children ?? <Text style={[styles.rowValue, valueTint ? { color: valueTint } : null]}>{value}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: space.lg,
    gap: space.xl,
    paddingBottom: TABBAR_CLEARANCE + 32,
  },
  title: { color: colors.text, fontSize: 28, fontWeight: '800', letterSpacing: -0.5 },
  section: { gap: space.sm },
  sectionHead: { flexDirection: 'row', alignItems: 'center', gap: 8, marginLeft: 2 },
  glyphBox: {
    width: 22,
    height: 22,
    borderRadius: 7,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  glyph: { fontSize: 11 },
  sectionTitle: { color: colors.textDim, fontSize: 13, fontWeight: '700' },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    padding: space.lg,
    gap: space.md,
  },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 12 },
  rowLabel: { color: colors.text, fontSize: 14, flexShrink: 1 },
  rowValue: { color: colors.accent, fontSize: 14, fontWeight: '700' },
  note: { color: colors.muted, fontSize: 12, lineHeight: 18 },
  link: { color: colors.accent, fontSize: 13, fontWeight: '600' },
  errText: { color: colors.danger, fontSize: 12 },
  errBox: {
    backgroundColor: colors.surface2,
    borderRadius: radius.md,
    padding: space.md,
    gap: 8,
  },
  errRow: { gap: 1 },
  errName: { color: colors.textDim, fontSize: 12, fontWeight: '600' },
  errReason: { color: colors.muted, fontSize: 11, lineHeight: 15 },
  folderRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 12 },
  folderName: { color: colors.text, flex: 1, fontSize: 13 },
  unlink: { color: colors.danger, fontSize: 13, fontWeight: '600' },
  taughtList: {
    backgroundColor: colors.surface2,
    borderRadius: radius.md,
    paddingHorizontal: space.md,
    paddingVertical: 4,
  },
  taughtRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 9,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  taughtMain: { flex: 1, gap: 2 },
  taughtLabel: { color: colors.text, fontSize: 14, fontWeight: '700' },
  taughtMeta: { color: colors.muted, fontSize: 11 },
  taughtForget: { color: colors.faint, fontSize: 16, fontWeight: '700', paddingHorizontal: 4 },
  divider: { height: StyleSheet.hairlineWidth, backgroundColor: colors.border, marginVertical: 2 },
  transferRow: { flexDirection: 'row', gap: space.sm },
  transferBtn: { flex: 1 },
  version: { color: colors.faint, fontSize: 11, textAlign: 'center', marginTop: 4 },
});
