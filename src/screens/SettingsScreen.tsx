import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Linking, Pressable, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';

import { showToast } from '../components/Toast';
import { Button, Chip, ProgressBar, Slider, StatusDot } from '../components/ui';
import { useAudio } from '../audio';
import { useEmbeddings } from '../embeddings';
import { useVision, intensityLabel, memesPerHour } from '../vision';
import {
  clearIndex,
  countAudioFailed,
  countMemes,
  countMemesDescribed,
  countMemesNeedingAudio,
  countMemesNeedingVision,
  countMemesTranscribed,
  deleteExemplarsByLabel,
  deleteExemplarsByPack,
  getExemplars,
  getFolders,
  getImportedPacks,
  getIndexErrors,
  getTaughtLabelStats,
  importExemplars,
  removeFolder,
  resetAudioFailures,
  resetVisionState,
  type ImportedPack,
  type IndexError,
  type TaughtLabelStat,
} from '../db';
import { emitLibraryChanged } from '../events';
import { success, warn } from '../haptics';
import { getVisionTelemetry, retagAll, type VisionTelemetry } from '../indexer';
import { MEME_LABELS } from '../memeLabels';
import { buildPack, parsePack, serializePack } from '../teachingPack';
import { colors, radius, space, TABBAR_CLEARANCE } from '../theme';
import type { LinkedFolder } from '../types';

export function SettingsScreen({ active = true }: { active?: boolean }) {
  const emb = useEmbeddings();
  const vision = useVision();
  const audio = useAudio();
  const [folders, setFolders] = useState<LinkedFolder[]>([]);
  const [count, setCount] = useState(0);
  const [taughtStats, setTaughtStats] = useState<TaughtLabelStat[]>([]);
  const [importedPacks, setImportedPacks] = useState<ImportedPack[]>([]);
  const [errors, setErrors] = useState<IndexError[]>([]);
  const [showErrors, setShowErrors] = useState(false);
  const [retagging, setRetagging] = useState<{ done: number; total: number } | null>(null);
  const [transferBusy, setTransferBusy] = useState(false);
  const [described, setDescribed] = useState(0);
  const [pending, setPending] = useState(0);
  const [enriching, setEnriching] = useState<{ done: number; total: number } | null>(null);
  const [tele, setTele] = useState<VisionTelemetry>({ described: 0, deduped: 0, failed: 0, avgMs: 0 });
  const enrichCancel = useRef(false);
  const [audioPending, setAudioPending] = useState(0);
  const [audioStats, setAudioStats] = useState({ analyzed: 0, withSpeech: 0 });
  const [audioFailed, setAudioFailed] = useState(0);
  const [transcribing, setTranscribing] = useState<{ done: number; total: number } | null>(null);
  const transcribeCancel = useRef(false);

  const refresh = useCallback(async () => {
    setFolders(await getFolders());
    setCount(await countMemes());
    setTaughtStats(await getTaughtLabelStats().catch(() => []));
    setImportedPacks(await getImportedPacks().catch(() => []));
    setErrors(await getIndexErrors());
    setDescribed(await countMemesDescribed());
    setPending(await countMemesNeedingVision());
    setTele(getVisionTelemetry());
    setAudioPending(await countMemesNeedingAudio().catch(() => 0));
    setAudioStats(await countMemesTranscribed().catch(() => ({ analyzed: 0, withSpeech: 0 })));
    setAudioFailed(await countAudioFailed().catch(() => 0));
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
      showToast(`Re-tag done — ${res.updated} meme${res.updated === 1 ? '' : 's'} changed`, 'success');
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
      const stamp = new Date().toISOString().slice(0, 10); // YYYY-MM-DD
      const labelCount = new Set(exemplars.map((e) => e.label)).size;
      const pack = buildPack(exemplars, Date.now(), {
        name: `Teaching pack · ${labelCount} tag${labelCount === 1 ? '' : 's'} · ${stamp}`,
      });
      const path = `${FileSystem.cacheDirectory}memeget-teachings-${stamp}.json`;
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

  // Run an import in the chosen mode, then offer to re-tag so it takes effect.
  // Called from the mode Alert below, after the picker's busy lock has already
  // been released — so it manages its own busy state and error reporting.
  const runImport = useCallback(
    async (
      exemplars: Parameters<typeof importExemplars>[0],
      packName: string,
      mode: 'merge' | 'replace'
    ) => {
      setTransferBusy(true);
      try {
        const { added, skipped, removed } = await importExemplars(exemplars, {
          pack: packName,
          mode,
        });
        await refresh();
        success();
        const replacedNote = mode === 'replace' && removed > 0 ? `Replaced ${removed} · ` : '';
        const summary =
          added > 0
            ? `${replacedNote}Imported ${added} example${added === 1 ? '' : 's'}` +
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
    },
    [refresh, emb.ready, onRetag]
  );

  // Pick a pack file, then ask whether to merge it into your tags or replace
  // everything with it. The actual insert + re-tag happens in runImport.
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
      // Prefer the pack's own name; fall back to the picked file's name.
      const packName =
        pack.name ||
        (asset.name || 'Imported pack')
          .replace(/\.json$/i, '')
          .replace(/[_-]+/g, ' ')
          .trim()
          .slice(0, 60) ||
        'Imported pack';

      // The picker dialog has dismissed by now; the busy lock is released in the
      // finally below, so each Alert branch kicks off its own busy import.
      Alert.alert(
        packName,
        `${pack.count} example${pack.count === 1 ? '' : 's'} ready. Merge into your taught tags, or replace everything with this pack?`,
        [
          { text: 'Cancel', style: 'cancel' },
          {
            text: 'Replace all',
            style: 'destructive',
            onPress: () => runImport(pack.exemplars, packName, 'replace'),
          },
          { text: 'Merge', onPress: () => runImport(pack.exemplars, packName, 'merge') },
        ]
      );
    } catch (e) {
      showToast(`Import failed: ${String(e)}`, 'error');
    } finally {
      setTransferBusy(false);
    }
  }, [transferBusy, runImport]);

  const onRemovePack = useCallback(
    (pack: ImportedPack) => {
      warn();
      Alert.alert(
        `Remove “${pack.pack}”?`,
        `Deletes the ${pack.examples} imported example${pack.examples === 1 ? '' : 's'} from this pack. Your own teaching is untouched; re-tag afterward to drop its labels from memes.`,
        [
          { text: 'Cancel', style: 'cancel' },
          {
            text: 'Remove',
            style: 'destructive',
            onPress: async () => {
              await deleteExemplarsByPack(pack.pack);
              await refresh();
              showToast(`Removed “${pack.pack}” — re-tag to apply`, 'info');
            },
          },
        ]
      );
    },
    [refresh]
  );

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

  const onRunEnrich = useCallback(async () => {
    if (!vision.ready) {
      showToast('Vision model still loading — try again shortly', 'info');
      return;
    }
    enrichCancel.current = false;
    setEnriching({ done: 0, total: 0 });
    try {
      // Routed through the provider's mutex so it can never collide with the
      // background trickle (one accelerator, one generation at a time).
      const res = await vision.runEnrichment({
        onProgress: (p) => setEnriching({ done: p.done, total: p.total }),
        shouldCancel: () => enrichCancel.current,
      });
      if (res === 'busy') {
        showToast('Already describing in the background — try again in a moment', 'info');
        return;
      }
      success();
      emitLibraryChanged(); // captions/tags changed under the Library's feet
      const dupNote = res.deduped > 0 ? ` · ${res.deduped} dup${res.deduped === 1 ? '' : 's'} skipped` : '';
      const failNote = res.failed > 0 ? ` · ${res.failed} failed` : '';
      showToast(
        `Described ${res.described} meme${res.described === 1 ? '' : 's'}${dupNote}${failNote}`,
        'success'
      );
    } catch (e) {
      showToast(`Describe failed: ${String(e)}`, 'error');
    } finally {
      setEnriching(null);
      refresh();
    }
  }, [vision, refresh]);

  const onRunTranscribe = useCallback(async () => {
    if (!audio.ready) {
      showToast('Speech model still loading — try again shortly', 'info');
      return;
    }
    transcribeCancel.current = false;
    setTranscribing({ done: 0, total: 0 });
    try {
      const res = await audio.runTranscription({
        onProgress: (p) => setTranscribing({ done: p.done, total: p.total }),
        shouldCancel: () => transcribeCancel.current,
      });
      if (res === 'busy') {
        showToast('Already transcribing — try again in a moment', 'info');
        return;
      }
      success();
      const silentNote = res.silent > 0 ? ` · ${res.silent} without speech` : '';
      const failNote = res.failed > 0 ? ` · ${res.failed} failed` : '';
      showToast(
        `Transcribed ${res.transcribed} video${res.transcribed === 1 ? '' : 's'}${silentNote}${failNote}`,
        'success'
      );
    } catch (e) {
      showToast(`Transcription failed: ${String(e)}`, 'error');
    } finally {
      setTranscribing(null);
      refresh();
    }
  }, [audio, refresh]);

  const onRetryAudioFailures = useCallback(async () => {
    const n = await resetAudioFailures();
    await refresh();
    showToast(`Re-queued ${n} failed video${n === 1 ? '' : 's'} for transcription`, 'info');
  }, [refresh]);

  const onSwitchQuality = useCallback(
    (q: 'fast' | 'max') => {
      if (q === vision.quality) return;
      vision.setQuality(q);
      // Re-queue everything so the sharper/faster model re-describes it.
      resetVisionState()
        .then(refresh)
        .catch(() => {});
      showToast(
        `Switched to ${q === 'max' ? 'Best · Gemma E2B' : 'Fast · LFM 450M'} — re-run Describe to apply`,
        'info'
      );
    },
    [vision, refresh]
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

  const visionTone = vision.error ? 'bad' : vision.ready ? 'good' : 'busy';
  const visionLabel = vision.error
    ? 'Error'
    : !vision.enabled
      ? 'Off'
      : vision.ready
        ? 'Ready'
        : `Loading ${Math.round((vision.progress || 0) * 100)}%`;
  const describedTotal = described + pending;

  const audioTone = audio.error ? 'bad' : audio.ready ? 'good' : 'busy';
  const audioLabel = audio.error
    ? 'Error'
    : !audio.enabled
      ? 'Off'
      : audio.ready
        ? 'Ready'
        : `Loading ${Math.round((audio.progress || 0) * 100)}%`;
  const audioTotal = audioStats.analyzed + audioPending;

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.title}>Settings</Text>

      <Section glyph="✦" title="On-device model" tint={colors.volt}>
        <Row label={`${emb.primaryLabel} (image + text)`}>
          <StatusDot tone={modelTone} label={modelLabel} />
        </Row>
        {emb.visualModel.available && (
          <Row label={`${emb.visualModel.label} (visual similarity)`}>
            <StatusDot
              tone={emb.visualError ? 'bad' : emb.visualReady ? 'good' : 'busy'}
              label={
                emb.visualError
                  ? 'Error'
                  : emb.visualReady
                    ? 'Ready'
                    : `Loading ${Math.round((emb.visualProgress || 0) * 100)}%`
              }
            />
          </Row>
        )}
        {!emb.ready && !emb.error && <ProgressBar value={emb.progress || 0} />}
        {!!emb.error && <Text style={styles.errText}>{emb.error}</Text>}
        {!!emb.visualError && <Text style={styles.errText}>{emb.visualError}</Text>}
        <Text style={styles.note}>
          Runs fully on your device via ExecuTorch. The model binary downloads once on first launch,
          then everything — indexing and search — happens offline with no network calls.
        </Text>
      </Section>

      <Section glyph="👁" title="AI descriptions" tint={colors.volt}>
        <Row label="Gemma 4 (vision-language)">
          <StatusDot tone={visionTone} label={visionLabel} />
        </Row>
        {vision.enabled && !vision.ready && !vision.error && (
          <ProgressBar value={vision.progress || 0} />
        )}
        {!!vision.error && <Text style={styles.errText}>{vision.error}</Text>}
        <Text style={styles.note}>
          Reads each meme on-device and writes a one-line caption, the text inside, and rich
          open-vocabulary tags — so you can search by what’s actually happening, not just keywords.
          Runs through the same ExecuTorch engine as CLIP; nothing ever leaves your phone.
        </Text>

        <Button
          small
          variant={vision.enabled ? 'dangerGhost' : 'primary'}
          label={vision.enabled ? 'Turn off AI descriptions' : 'Enable AI descriptions'}
          onPress={() => {
            const turningOn = !vision.enabled;
            vision.setEnabled(turningOn);
            if (turningOn) showToast('Downloading the vision model — first time only', 'info');
          }}
        />

        {vision.enabled && (
          <>
            <Text style={[styles.note, { marginTop: 2 }]}>Model</Text>
            <View style={styles.qualityRow}>
              <Chip
                label="Best · Gemma E2B"
                active={vision.quality === 'max'}
                onPress={() => onSwitchQuality('max')}
              />
              <Chip
                label="Fast · LFM 450M"
                active={vision.quality === 'fast'}
                onPress={() => onSwitchQuality('fast')}
              />
            </View>

            <Row label="Described" value={`${described} / ${describedTotal}`} />
            {tele.avgMs > 0 && (
              <Text style={styles.faintSmall}>
                ≈ {(tele.avgMs / 1000).toFixed(1)}s per meme on this device
                {tele.deduped > 0 ? ` · ${tele.deduped} skipped as duplicates` : ''}
              </Text>
            )}
            {enriching ? (
              <View style={{ gap: 8 }}>
                <View style={styles.enrichTopRow}>
                  <Text style={styles.note}>
                    Describing {enriching.done}/{enriching.total || '…'}
                  </Text>
                  <Pressable onPress={() => (enrichCancel.current = true)} hitSlop={10}>
                    <Text style={styles.stopText}>Stop</Text>
                  </Pressable>
                </View>
                <ProgressBar value={enriching.total ? enriching.done / enriching.total : 0} />
              </View>
            ) : pending > 0 ? (
              <Button
                small
                label={`Describe ${pending} meme${pending === 1 ? '' : 's'}`}
                onPress={onRunEnrich}
                disabled={!vision.ready}
              />
            ) : (
              <Text style={styles.note}>
                {described > 0 ? 'Every meme has been described ✓' : 'Index some memes first, then describe them.'}
              </Text>
            )}

            <View style={styles.bgDivider} />

            <View style={styles.bgHeadRow}>
              <Text style={styles.rowLabel}>Background processing</Text>
              <Switch
                value={vision.backgroundEnabled}
                onValueChange={vision.setBackgroundEnabled}
                trackColor={{ true: colors.volt, false: colors.surface3 }}
                thumbColor="#fff"
              />
            </View>
            <Text style={styles.note}>
              Quietly trickles through your library — no need to sit on the Describe button. Drag
              toward Extreme to go faster (and warmer); Conservative sips. Runs while the app is open,
              and (with a native build) on a schedule when it’s closed.
            </Text>

            {vision.backgroundEnabled && (
              <>
                <View style={styles.enrichTopRow}>
                  <Text style={styles.rowLabel}>{intensityLabel(vision.backgroundIntensity)}</Text>
                  <Text style={styles.rowValue}>
                    {memesPerHour(vision.backgroundIntensity) === Infinity
                      ? 'max speed'
                      : `~${memesPerHour(vision.backgroundIntensity)} / hr`}
                  </Text>
                </View>
                <Slider
                  value={vision.backgroundIntensity}
                  onChange={vision.setBackgroundIntensity}
                />
                <View style={styles.scaleRow}>
                  <Text style={styles.faintSmall}>Conservative</Text>
                  <Text style={styles.faintSmall}>Extreme</Text>
                </View>
                {vision.pausedReason && (
                  <Text style={styles.faintSmall}>Paused — {vision.pausedReason}</Text>
                )}
                {vision.running && !enriching && !vision.pausedReason && (
                  <Text style={styles.faintSmall}>Working in the background… {described} described so far.</Text>
                )}

                <View style={styles.bgDivider} />
                <ThrottleRow
                  label="Only while charging"
                  value={vision.throttles.onlyWhileCharging}
                  onChange={(v) => vision.setThrottle('onlyWhileCharging', v)}
                />
                <ThrottleRow
                  label="Pause when device is warm"
                  value={vision.throttles.pauseWhenHot}
                  onChange={(v) => vision.setThrottle('pauseWhenHot', v)}
                />
                <ThrottleRow
                  label="Pause on low battery"
                  value={vision.throttles.pauseOnLowBattery}
                  onChange={(v) => vision.setThrottle('pauseOnLowBattery', v)}
                />
                {!vision.nativeBackgroundAvailable && (
                  <Text style={styles.note}>
                    Charging/thermal awareness and true keep-running-in-background need a native
                    build (expo prebuild). Until then this runs only while the app is open and the
                    throttles above have no signal to act on.
                  </Text>
                )}
              </>
            )}
          </>
        )}
      </Section>

      <Section glyph="🎙" title="Audio analysis" tint={colors.volt}>
        <Row label="Whisper (speech-to-text)">
          <StatusDot tone={audioTone} label={audioLabel} />
        </Row>
        {audio.enabled && !audio.ready && !audio.error && (
          <ProgressBar value={audio.progress || 0} />
        )}
        {!!audio.error && <Text style={styles.errText}>{audio.error}</Text>}
        <Text style={styles.note}>
          Listens to your video memes on-device and writes down what’s said, so you can find a clip
          by the line you remember hearing. Same ExecuTorch engine as everything else — audio never
          leaves your phone.
        </Text>

        <Button
          small
          variant={audio.enabled ? 'dangerGhost' : 'primary'}
          label={audio.enabled ? 'Turn off audio analysis' : 'Enable audio analysis'}
          onPress={() => {
            const turningOn = !audio.enabled;
            audio.setEnabled(turningOn);
            if (turningOn) showToast('Downloading the speech model — first time only', 'info');
          }}
        />

        {audio.enabled && !audio.nativeAvailable && (
          <Text style={styles.note}>
            Decoding a video’s audio track needs a native build (expo prebuild). This dev build
            can’t transcribe until then.
          </Text>
        )}

        {audio.enabled && audio.nativeAvailable && (
          <>
            <Row label="Analyzed" value={`${audioStats.analyzed} / ${audioTotal}`} />
            {audioStats.analyzed > 0 && (
              <Text style={styles.faintSmall}>
                {audioStats.withSpeech} with speech · {audioStats.analyzed - audioStats.withSpeech}{' '}
                silent or music-only
              </Text>
            )}
            {transcribing ? (
              <View style={{ gap: 8 }}>
                <View style={styles.enrichTopRow}>
                  <Text style={styles.note}>
                    Transcribing {transcribing.done}/{transcribing.total || '…'}
                  </Text>
                  <Pressable onPress={() => (transcribeCancel.current = true)} hitSlop={10}>
                    <Text style={styles.stopText}>Stop</Text>
                  </Pressable>
                </View>
                <ProgressBar value={transcribing.total ? transcribing.done / transcribing.total : 0} />
              </View>
            ) : audioPending > 0 ? (
              <Button
                small
                label={`Transcribe ${audioPending} video${audioPending === 1 ? '' : 's'}`}
                onPress={onRunTranscribe}
                disabled={!audio.ready}
              />
            ) : (
              <Text style={styles.note}>
                {audioStats.analyzed > 0
                  ? 'Every video has been analyzed ✓'
                  : 'Index some videos first, then transcribe them.'}
              </Text>
            )}
            {audioFailed > 0 && !transcribing && (
              <Button
                small
                variant="secondary"
                label={`Retry ${audioFailed} failed`}
                onPress={onRetryAudioFailures}
              />
            )}
          </>
        )}
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
                  <View style={styles.taughtTitleRow}>
                    <Text style={styles.taughtLabel} numberOfLines={1}>
                      {t.label}
                    </Text>
                    <View style={[styles.srcChip, t.fromPack ? styles.srcPack : styles.srcSelf]}>
                      <Text style={[styles.srcChipText, { color: t.fromPack ? colors.accent : colors.muted }]}>
                        {t.fromSelf && t.fromPack ? 'you + pack' : t.fromPack ? 'pack' : 'you'}
                      </Text>
                    </View>
                  </View>
                  <Text style={styles.taughtMeta}>
                    {t.tagged} meme{t.tagged === 1 ? '' : 's'} tagged · {t.positives} example
                    {t.positives === 1 ? '' : 's'}
                    {t.negatives > 0 ? ` · ${t.negatives} correction${t.negatives === 1 ? '' : 's'}` : ''}
                    {t.fromPack && t.packs.length > 0 ? ` · from ${t.packs.join(', ')}` : ''}
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
          Share your taught tags as a pack, or import someone else’s to inherit their meme knowledge.
          On import you choose to <Text style={styles.noteStrong}>merge</Text> it into your tags or{' '}
          <Text style={styles.noteStrong}>replace</Text> everything with it — then re-tag to apply.
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

        {importedPacks.length > 0 && (
          <>
            <View style={styles.divider} />
            <Text style={styles.note}>Imported packs</Text>
            {importedPacks.map((p) => (
              <View key={p.pack} style={styles.packRow}>
                <View style={styles.taughtMain}>
                  <Text style={styles.taughtLabel} numberOfLines={1}>
                    {p.pack}
                  </Text>
                  <Text style={styles.taughtMeta}>
                    {p.labels} tag{p.labels === 1 ? '' : 's'} · {p.examples} example
                    {p.examples === 1 ? '' : 's'}
                  </Text>
                </View>
                <Pressable hitSlop={8} onPress={() => onRemovePack(p)}>
                  <Text style={styles.taughtForget}>✕</Text>
                </Pressable>
              </View>
            ))}
          </>
        )}
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

function ThrottleRow({
  label,
  value,
  onChange,
}: {
  label: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <View style={styles.bgHeadRow}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Switch
        value={value}
        onValueChange={onChange}
        trackColor={{ true: colors.volt, false: colors.surface3 }}
        thumbColor="#fff"
      />
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
  qualityRow: { flexDirection: 'row', gap: 8 },
  enrichTopRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  stopText: { color: colors.danger, fontSize: 13, fontWeight: '700' },
  bgDivider: { height: 1, backgroundColor: colors.border, marginVertical: 4 },
  bgHeadRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  scaleRow: { flexDirection: 'row', justifyContent: 'space-between' },
  faintSmall: { color: colors.faint, fontSize: 11, fontWeight: '600' },
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
  taughtTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  taughtLabel: { color: colors.text, fontSize: 14, fontWeight: '700', flexShrink: 1 },
  taughtMeta: { color: colors.muted, fontSize: 11 },
  taughtForget: { color: colors.faint, fontSize: 16, fontWeight: '700', paddingHorizontal: 4 },
  srcChip: {
    paddingHorizontal: 7,
    paddingVertical: 1,
    borderRadius: radius.sm,
    borderWidth: 1,
  },
  srcSelf: { borderColor: colors.border, backgroundColor: 'transparent' },
  srcPack: { borderColor: colors.accent, backgroundColor: colors.surface },
  srcChipText: { fontSize: 9, fontWeight: '800', letterSpacing: 0.3, textTransform: 'uppercase' },
  packRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 7,
  },
  divider: { height: StyleSheet.hairlineWidth, backgroundColor: colors.border, marginVertical: 2 },
  noteStrong: { color: colors.text, fontWeight: '700' },
  transferRow: { flexDirection: 'row', gap: space.sm },
  transferBtn: { flex: 1 },
  version: { color: colors.faint, fontSize: 11, textAlign: 'center', marginTop: 4 },
});
