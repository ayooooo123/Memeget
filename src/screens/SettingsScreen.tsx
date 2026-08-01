import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Linking, Pressable, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system/legacy';
import Constants from 'expo-constants';
import { File, FileMode } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { manipulateAsync, SaveFormat } from 'expo-image-manipulator';
import { saveToDownloads } from '../../modules/memeget-bg';

import { showToast } from '../components/Toast';
import { Button, ProgressBar, Slider, StatusDot } from '../components/ui';
import { useAudio } from '../audio';
import { AUDIO_MODEL_INFO } from '../audioCore';
import { useEmbeddings } from '../embeddings';
import { useVision, intensityLabel, memesPerHour } from '../vision';
import {
  clearIndex,
  countAudioFailed,
  countMemes,
  countStaleExemplars,
  countMemesNeedingAudio,
  countMemesTranscribed,
  deleteExemplarsByLabel,
  deleteExemplarsByPack,
  getExemplars,
  exportDescribedTags,
  getCollectionRecords,
  type CollectionRecord,
  getFolders,
  getImportedPacks,
  getIndexErrors,
  getIndexModelMismatch,
  getPosterStats,
  getTaughtLabelStats,
  getVisionStats,
  importExemplars,
  migrateStaleExemplars,
  removeFolder,
  resetAllAudio,
  resetAudioFailures,
  resetFailedThumbs,
  resetVisionFailures,
  type ImportedPack,
  type IndexError,
  type TaughtLabelStat,
  type VisionStats,
} from '../db';
import { emitLibraryChanged, onLibraryChanged } from '../events';
import { success, warn } from '../haptics';
import { acquireKeepAlive, reportKeepAliveProgress } from '../keepAlive';
import {
  backfillVideoThumbs,
  clearThumbSkips,
  getVisionTelemetry,
  indexSavedFiles,
  retagAll,
  type VisionTelemetry,
} from '../indexer';
import { importMemesFromZip, type ZipImportPhase } from '../zipImport';
import { MEME_LABELS } from '../memeLabels';
import { formatBuildLabel } from '../memeActionsCore';
import { buildPack, parsePack, serializePack } from '../teachingPack';
import { writeCollectionZip } from '../collectionExport';
import { restoreAllSidecars, syncAllSidecars } from '../sidecarSync';
import { colors, radius, space, TABBAR_CLEARANCE } from '../theme';
import type { LinkedFolder } from '../types';

// Deliver a finished export: drop it straight into the public Downloads folder
// (native MediaStore copy — streams in native code, so a large zip never hits
// JS memory) and toast where it landed. Falls back to the share sheet only when
// the native module isn't built in.
async function deliverExport(path: string, fileName: string, mimeType: string): Promise<void> {
  const dest = await saveToDownloads(path, fileName, mimeType).catch(() => null);
  if (dest) {
    showToast(`Saved to ${dest}`, 'info');
    return;
  }
  if (await Sharing.isAvailableAsync()) {
    await Sharing.shareAsync(path, { mimeType, dialogTitle: 'Export' });
  } else {
    showToast(`Saved to ${path}`, 'info');
  }
}

const EMPTY_VISION_STATS: VisionStats = {
  total: 0,
  described: 0,
  queued: 0,
  indexing: 0,
  failed: 0,
  unsupported: 0,
};

// Rough remaining-time hint from the measured per-meme time — deliberately
// coarse ("about 2h 10m left"), because a per-second countdown on a job paced
// by battery and thermals would be a lie.
function formatEta(ms: number): string {
  const secs = Math.round(ms / 1000);
  if (secs < 90) return `${secs}s`;
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${mins}m`;
  return `${Math.floor(mins / 60)}h ${mins % 60}m`;
}

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
  const [visionStats, setVisionStats] = useState<VisionStats>(EMPTY_VISION_STATS);
  const [enriching, setEnriching] = useState<{ done: number; total: number; current: string } | null>(
    null
  );
  const [tele, setTele] = useState<VisionTelemetry>({ described: 0, deduped: 0, failed: 0, avgMs: 0 });
  const enrichCancel = useRef(false);
  const [audioPending, setAudioPending] = useState(0);
  const [audioStats, setAudioStats] = useState({ analyzed: 0, withSpeech: 0 });
  const [audioFailed, setAudioFailed] = useState(0);
  const [transcribing, setTranscribing] = useState<{ done: number; total: number } | null>(null);
  const transcribeCancel = useRef(false);
  const [modelMismatch, setModelMismatch] = useState<{ stored: string; current: string } | null>(
    null
  );
  const [staleExemplars, setStaleExemplars] = useState(0);
  const [migrating, setMigrating] = useState(false);
  const [posterStats, setPosterStats] = useState({ total: 0, done: 0, failed: 0, missing: 0 });
  const [retryingPosters, setRetryingPosters] = useState(false);
  const [zipImport, setZipImport] = useState<{ done: number; total: number; phase: ZipImportPhase } | null>(
    null
  );
  const [exportProgress, setExportProgress] = useState<{ done: number; total: number } | null>(null);
  const [backingUp, setBackingUp] = useState(false);
  const [backupStatus, setBackupStatus] = useState('');

  // Just the describe counters — one grouped query. Runs on a ticker while
  // there's outstanding work, so it must stay far cheaper than `refresh`.
  const refreshVision = useCallback(async () => {
    setVisionStats(await getVisionStats().catch(() => EMPTY_VISION_STATS));
    setTele(getVisionTelemetry());
  }, []);

  const refresh = useCallback(async () => {
    // Independent reads: run them together. A dozen sequential SQLite round
    // trips is what made opening this tab hitch on a big library.
    const [
      nextFolders,
      nextCount,
      mismatch,
      stale,
      taught,
      packs,
      posters,
      errs,
      vStats,
      aPending,
      aStats,
      aFailed,
    ] = await Promise.all([
      getFolders().catch(() => []),
      countMemes().catch(() => 0),
      getIndexModelMismatch().catch(() => null),
      countStaleExemplars().catch(() => 0),
      getTaughtLabelStats().catch(() => []),
      getImportedPacks().catch(() => []),
      getPosterStats().catch(() => ({ total: 0, done: 0, failed: 0, missing: 0 })),
      getIndexErrors().catch(() => []),
      getVisionStats().catch(() => EMPTY_VISION_STATS),
      countMemesNeedingAudio().catch(() => 0),
      countMemesTranscribed().catch(() => ({ analyzed: 0, withSpeech: 0 })),
      countAudioFailed().catch(() => 0),
    ]);
    setFolders(nextFolders);
    setCount(nextCount);
    setModelMismatch(mismatch);
    setStaleExemplars(stale);
    setTaughtStats(taught);
    setImportedPacks(packs);
    setPosterStats(posters);
    setErrors(errs);
    setVisionStats(vStats);
    setTele(getVisionTelemetry());
    setAudioPending(aPending);
    setAudioStats(aStats);
    setAudioFailed(aFailed);
  }, []);

  // Force a sidecar write for every linked folder. The same thing an index pass
  // does at the end — exposed on its own because a user who has just spent an
  // hour teaching tags shouldn't have to run a full index to get that written
  // out where it's safe.
  const onBackupNow = useCallback(async () => {
    if (backingUp) return;
    setBackingUp(true);
    setBackupStatus('');
    const release = acquireKeepAlive('Backing up meme knowledge');
    try {
      const results = await syncAllSidecars();
      const wrote = results.filter((r) => !r.skipped);
      if (wrote.length === 0) {
        setBackupStatus('No folder could be written to — check the folder is still accessible.');
        showToast('Backup failed — no writable folder', 'error');
        return;
      }
      const memes = wrote.reduce((n, r) => n + r.memes, 0);
      const files = wrote.reduce((n, r) => n + r.chunksWritten, 0);
      setBackupStatus(
        `Backed up ${memes} meme${memes === 1 ? '' : 's'} to ${wrote.length} folder${wrote.length === 1 ? '' : 's'} · ${files} file${files === 1 ? '' : 's'} updated`
      );
      success();
    } catch (e) {
      setBackupStatus('');
      showToast(`Backup failed: ${String(e)}`, 'error');
    } finally {
      release();
      setBackingUp(false);
    }
  }, [backingUp]);

  // Pull knowledge back out of the folders. Additive, so this is safe to run at
  // any time — the usual trigger is a fresh install whose folders were linked
  // before the sidecar existed, or a restore the user wants to redo.
  const onRestoreSidecars = useCallback(async () => {
    if (backingUp) return;
    setBackingUp(true);
    setBackupStatus('');
    const release = acquireKeepAlive('Restoring meme knowledge');
    try {
      const results = await restoreAllSidecars();
      const found = results.filter((r) => r.found);
      if (found.length === 0) {
        setBackupStatus('No .memeget backup found in any linked folder yet.');
        showToast('Nothing to restore', 'info');
        return;
      }
      const added = found.reduce((n, r) => n + r.added, 0);
      const enriched = found.reduce((n, r) => n + r.enriched, 0);
      const taught = found.reduce((n, r) => n + r.teachingsAdded, 0);
      const unreadable = found.reduce((n, r) => n + r.unreadable, 0);
      setBackupStatus(
        `Restored ${added} new meme${added === 1 ? '' : 's'}, filled in ${enriched} existing, ${taught} taught example${taught === 1 ? '' : 's'}` +
          (found.some((r) => r.vectorsDropped) ? ' · vectors were from another model — run Index to re-embed' : '') +
          // Loud on purpose: this is the backup failing to be a backup.
          (unreadable > 0 ? ` · ⚠ ${unreadable} record${unreadable === 1 ? '' : 's'} could not be read — back up again to rewrite them` : '')
      );
      await refresh();
      emitLibraryChanged();
      success();
    } catch (e) {
      setBackupStatus('');
      showToast(`Restore failed: ${String(e)}`, 'error');
    } finally {
      release();
      setBackingUp(false);
    }
  }, [backingUp, refresh]);

  // Both tabs stay mounted (so the Library keeps its state), which means this
  // screen must refetch its stats whenever it becomes the visible tab.
  useEffect(() => {
    if (active) refresh();
  }, [active, refresh]);

  // Keep the describe counters live while this tab is open. Re-reading only on
  // tab focus made a model that was actively working look frozen — and the
  // background trickle drops `vision.running` between items, so outstanding
  // WORK (not the running flag) is what decides whether to tick. Gated on a
  // plain boolean so the interval isn't torn down and rebuilt on every count
  // change; it stops the moment the queue drains.
  const visionBusy = visionStats.queued > 0 || visionStats.indexing > 0 || enriching !== null;
  useEffect(() => {
    if (!active) return;
    const unsub = onLibraryChanged(refreshVision);
    if (!visionBusy) return unsub;
    const timer = setInterval(refreshVision, 2000);
    return () => {
      clearInterval(timer);
      unsub();
    };
  }, [active, refreshVision, visionBusy]);

  // Clear the undecodable stamps and run the poster backfill in the foreground
  // with feedback — the idle loop would get there too, but silently, and after
  // several silent failures what the user needs is to SEE it finish (or see
  // the per-file reasons land in the indexing-errors list right below).
  const onRetryPosters = useCallback(async () => {
    if (retryingPosters) return;
    setRetryingPosters(true);
    const release = acquireKeepAlive('Extracting video previews');
    try {
      const n = await resetFailedThumbs();
      clearThumbSkips();
      showToast(`Retrying posters for ${n} videos…`, 'info');
      while (
        (await backfillVideoThumbs({ limit: 24 }).catch(() => ({ fetched: 0, patches: [] })))
          .fetched > 0
      ) {
        setPosterStats(await getPosterStats().catch(() => ({ total: 0, done: 0, failed: 0, missing: 0 })));
      }
      // A user-driven full rebuild (not a background drain) — a single library
      // refresh at the end is fine here; they're watching this screen, not
      // scrolling the grid.
      emitLibraryChanged();
      const stats = await getPosterStats();
      setPosterStats(stats);
      setErrors(await getIndexErrors());
      if (stats.failed > 0) {
        showToast(`${stats.failed} still failed — see “Indexing errors” for why`, 'error');
        setShowErrors(true);
      } else {
        showToast('Video posters rebuilt', 'success');
      }
    } finally {
      release();
      setRetryingPosters(false);
    }
  }, [retryingPosters]);

  // Import every compatible meme out of a .zip: pick the archive, unzip its
  // image/video entries into the linked folder (skipping duplicates), then hand
  // the freshly saved files to the background indexer — the same two-phase path
  // a shared meme takes. Needs a linked folder (importMemesFromZip throws a
  // readable message otherwise).
  const onImportZip = useCallback(async () => {
    if (zipImport) return;
    if (folders.length === 0) {
      showToast('Link a folder first (Library tab) so imported memes have a home', 'info');
      return;
    }
    const res = await DocumentPicker.getDocumentAsync({
      // Android zip pickers report a few different mime types; keep */* so the
      // archive is always selectable, and we validate the bytes on read.
      type: ['application/zip', 'application/x-zip-compressed', 'multipart/x-zip', '*/*'],
      copyToCacheDirectory: true,
    }).catch(() => null);
    if (!res || res.canceled) return;
    const picked = res.assets[0];
    if (!picked) return;

    const release = acquireKeepAlive('Importing memes from zip');
    setZipImport({ done: 0, total: 0, phase: 'reading' });
    try {
      const result = await importMemesFromZip(picked.uri, {
        zipName: picked.name,
        onProgress: (done, total, phase) => setZipImport({ done, total, phase }),
      });
      // Files are on disk as pending rows now — show them, then embed/OCR/tag in
      // the background. If the model isn't ready yet nothing is lost: they stay
      // as normal folder files for the next Index (or the pending-recovery sweep).
      emitLibraryChanged();
      if (result.saved.length > 0 && emb.ready) {
        indexSavedFiles(emb, result.saved)
          .then(() => emitLibraryChanged())
          .catch(() => {});
      }
      await refresh();

      const parts = [`Imported ${result.imported} meme${result.imported === 1 ? '' : 's'}`];
      if (result.duplicates > 0) parts.push(`${result.duplicates} duplicate${result.duplicates === 1 ? '' : 's'} skipped`);
      if (result.unsupported > 0) parts.push(`${result.unsupported} unsupported`);
      if (result.errors > 0) parts.push(`${result.errors} failed`);
      if (result.imported > 0) success();
      showToast(parts.join(' · '), result.imported > 0 ? 'success' : 'info');
    } catch (e) {
      showToast(`Zip import failed: ${String((e as Error)?.message ?? e)}`, 'error');
    } finally {
      release();
      setZipImport(null);
    }
  }, [zipImport, folders.length, emb, refresh]);

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
      await deliverExport(path, `memeget-teachings-${stamp}.json`, 'application/json');
      success();
    } catch (e) {
      showToast(`Export failed: ${String(e)}`, 'error');
    } finally {
      setTransferBusy(false);
    }
  }, [transferBusy]);

  // Export the model's own per-meme tags (source 'vision') as JSON, for the
  // facet-coverage prompt-tuning loop (drop into tools/eval/described.json, run
  // `npm run coverage`). Distinct from the teaching-pack export above: teachings
  // are what YOU taught; this is what the MODEL produced.
  const onExportDescribedTags = useCallback(async () => {
    if (transferBusy) return;
    setTransferBusy(true);
    try {
      const memes = await exportDescribedTags();
      if (memes.length === 0) {
        showToast('Nothing described yet — describe some memes first', 'info');
        return;
      }
      const stamp = new Date().toISOString().slice(0, 10);
      const path = `${FileSystem.cacheDirectory}memeget-described-tags-${stamp}.json`;
      await FileSystem.writeAsStringAsync(path, JSON.stringify(memes));
      await deliverExport(path, `memeget-described-tags-${stamp}.json`, 'application/json');
      success();
    } catch (e) {
      showToast(`Export failed: ${String(e)}`, 'error');
    } finally {
      setTransferBusy(false);
    }
  }, [transferBusy]);

  // Export the whole collection as a ZIP: manifest.json (tags + caption + OCR +
  // embeddings per meme) plus images/<id>.jpg. One artifact carrying everything
  // needed to re-run tagging, score coverage, and rebuild the eval set.
  const onExportCollection = useCallback(async () => {
    if (transferBusy) return;
    setTransferBusy(true);
    const release = acquireKeepAlive('Exporting collection');
    try {
      const records = await getCollectionRecords();
      if (records.length === 0) {
        showToast('Nothing indexed yet', 'info');
        return;
      }
      setExportProgress({ done: 0, total: records.length });
      let lastShownPct = -1;
      const onProgress = (done: number, total: number) => {
        reportKeepAliveProgress(done, total);
        const pct = total ? Math.floor((done / total) * 100) : 0;
        if (pct !== lastShownPct || done === total) {
          lastShownPct = pct;
          setExportProgress({ done, total });
        }
      };
      // Images: downscale to ~640px via the manipulator (handles content:// SAF
      // uris); videos fall back to their stored poster. A failure just drops
      // that one image — metadata is always kept.
      const loadImage = async (r: CollectionRecord): Promise<string | null> => {
        if (r.kind === 'image') {
          try {
            const out = await manipulateAsync(r.uri, [{ resize: { width: 640 } }], {
              compress: 0.8,
              format: SaveFormat.JPEG,
              base64: true,
            });
            if (out.base64) return out.base64;
          } catch {
            // fall through to poster
          }
        }
        if (r.thumbUri) {
          try {
            return await FileSystem.readAsStringAsync(r.thumbUri, {
              encoding: FileSystem.EncodingType.Base64,
            });
          } catch {
            return null;
          }
        }
        return null;
      };
      const stamp = new Date().toISOString().slice(0, 10);
      const path = `${FileSystem.cacheDirectory}memeget-collection-${stamp}.zip`;
      // Stream the archive straight to disk in chunks. Building the whole zip as
      // one base64 string in memory (then decoding it again on write) OOM'd the
      // JS runtime on large libraries — the export would churn ~30s, then die
      // with no file and no error. A file handle + chunked writes keeps peak
      // memory flat.
      const file = new File(path);
      file.create({ overwrite: true });
      const handle = file.open(FileMode.WriteOnly);
      try {
        await writeCollectionZip(records, loadImage, Date.now(), (chunk) => handle.writeBytes(chunk), onProgress);
      } finally {
        handle.close();
      }
      await deliverExport(path, `memeget-collection-${stamp}.zip`, 'application/zip');
      success();
    } catch (e) {
      showToast(`Export failed: ${String(e)}`, 'error');
    } finally {
      release();
      setExportProgress(null);
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
    if (!vision.enabled) return;
    // The model is demand-loaded; this tap may be what summons it.
    // runEnrichment waits for the load internally.
    if (!vision.ready) {
      showToast('Loading the vision model — the first describe after a cold start takes a bit', 'info');
    }
    enrichCancel.current = false;
    setEnriching({ done: 0, total: 0, current: '' });
    try {
      // Routed through the provider's mutex so it can never collide with the
      // background trickle (one accelerator, one generation at a time).
      const res = await vision.runEnrichment({
        onProgress: (p) => setEnriching({ done: p.done, total: p.total, current: p.current }),
        shouldCancel: () => enrichCancel.current,
      });
      if (res === 'busy') {
        showToast('Already describing in the background — try again in a moment', 'info');
        return;
      }
      // Stop is not success. Cancelling during the lock wait yields zero work,
      // and cancelling mid-run yields a partial count — neither deserves the
      // success haptic or a "Described 0 memes" cheer.
      emitLibraryChanged(); // captions/tags changed under the Library's feet
      const dupNote = res.deduped > 0 ? ` · ${res.deduped} dup${res.deduped === 1 ? '' : 's'} skipped` : '';
      const failNote = res.failed > 0 ? ` · ${res.failed} failed` : '';
      const body = `${res.described} meme${res.described === 1 ? '' : 's'}${dupNote}${failNote}`;
      if (enrichCancel.current) {
        showToast(`Stopped — described ${body}`, 'info');
        return;
      }
      success();
      showToast(`Described ${body}`, 'success');
    } catch (e) {
      showToast(`Describe failed: ${String(e)}`, 'error');
    } finally {
      setEnriching(null);
      refresh();
    }
  }, [vision, refresh]);

  // Put the model's own failures back in the queue. Files the indexer could
  // never read are NOT re-queued (see resetVisionFailures) — retrying those
  // would burn a generation slot on every pass, forever.
  const onRetryVisionFailures = useCallback(async () => {
    const n = await resetVisionFailures().catch(() => 0);
    await refreshVision();
    showToast(
      n > 0 ? `Re-queued ${n} meme${n === 1 ? '' : 's'} for describing` : 'Nothing retryable left',
      'info'
    );
  }, [refreshVision]);

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

  // Wipe every video transcript and transcribe the whole library again — for
  // when the current speech model's output is poor and the user wants a clean
  // retry (e.g. after switching models). Guarded: don't clear transcripts unless
  // the model can actually run, or search loses all speech terms until a later
  // pass. Kicks the pass right after re-queueing.
  const onRegenerateAllAudio = useCallback(() => {
    if (!audio.ready) {
      showToast('Speech model still loading — try again shortly', 'info');
      return;
    }
    // A per-meme retry from the Library sets AudioApi.running without touching this
    // screen's `transcribing`, so check the shared flag before wiping the table.
    if (audio.running) {
      showToast('Already transcribing — try again in a moment', 'info');
      return;
    }
    Alert.alert(
      'Regenerate all transcriptions?',
      'Clears every video transcript and transcribes the whole library again on-device. This can take a while.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Regenerate',
          style: 'destructive',
          onPress: async () => {
            const n = await resetAllAudio();
            await refresh();
            showToast(`Re-queued ${n} video${n === 1 ? '' : 's'} — transcribing…`, 'info');
            await onRunTranscribe();
          },
        },
      ]
    );
  }, [audio.ready, audio.running, refresh, onRunTranscribe]);


  // Re-base old-space taught examples onto the current index (see
  // migrateStaleExemplars), then re-tag so the labels actually reappear.
  const onMigrateExemplars = useCallback(async () => {
    if (migrating) return;
    setMigrating(true);
    try {
      const { migrated, unmigratable } = await migrateStaleExemplars();
      if (migrated > 0 && emb.ready) {
        await retagAll(emb);
        emitLibraryChanged(); // tags changed under the Library's feet
      }
      await refresh();
      success();
      const packNote =
        unmigratable > 0
          ? ` · ${unmigratable} pack example${unmigratable === 1 ? '' : 's'} need a re-exported pack`
          : '';
      showToast(
        migrated > 0
          ? `Migrated ${migrated} taught example${migrated === 1 ? '' : 's'}${packNote}`
          : `Nothing migrated — index the library first${packNote}`,
        migrated > 0 ? 'success' : 'info'
      );
    } catch (e) {
      showToast(`Migration failed: ${String(e)}`, 'error');
    } finally {
      setMigrating(false);
    }
  }, [migrating, emb, refresh]);

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

  const visionTone = vision.error ? 'bad' : vision.ready || vision.modelIdle ? 'good' : 'busy';
  const visionLabel = vision.error
    ? 'Error'
    : !vision.enabled
      ? 'Off'
      : vision.ready
        ? 'Ready'
        : vision.modelIdle
          ? 'On demand' // loads only when there's something to describe
          : `Loading ${Math.round((vision.progress || 0) * 100)}%`;
  const describedPct = visionStats.total ? visionStats.described / visionStats.total : 0;
  // Only the buckets that exist — a row of zeroes is noise.
  const visionBreakdown = [
    visionStats.queued > 0 ? `${visionStats.queued} to go` : '',
    visionStats.indexing > 0 ? `${visionStats.indexing} still indexing` : '',
    visionStats.failed > 0 ? `${visionStats.failed} failed` : '',
    visionStats.unsupported > 0 ? `${visionStats.unsupported} unreadable` : '',
  ]
    .filter(Boolean)
    .join(' · ');

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
              tone={emb.visualError ? 'bad' : emb.visualReady || !emb.visualWanted ? 'good' : 'busy'}
              label={
                emb.visualError
                  ? 'Error'
                  : emb.visualReady
                    ? 'Ready'
                    : !emb.visualWanted
                      ? 'On demand' // deliberately unloaded until its backfill has work
                      : `Loading ${Math.round((emb.visualProgress || 0) * 100)}%`
              }
            />
          </Row>
        )}
        {!emb.ready && !emb.error && <ProgressBar value={emb.progress || 0} />}
        {!!emb.error && <Text style={styles.errText}>{emb.error}</Text>}
        {!!emb.visualError && <Text style={styles.errText}>{emb.visualError}</Text>}
        {modelMismatch && (
          <Text style={styles.errText}>
            ⚠ Index/model mismatch: the index was built with {modelMismatch.stored}, but this build
            runs {modelMismatch.current}. Search and taught labels are unreliable until you Clear
            index (below), re-Index, and re-teach or re-import your labels.
          </Text>
        )}
        <Text style={styles.note}>
          Runs fully on your device via ExecuTorch. The model binary downloads once on first launch,
          then everything — indexing and search — happens offline with no network calls.
        </Text>
      </Section>

      <Section glyph="👁" title="AI descriptions" tint={colors.volt}>
        <Row label="Gemma 4 E2B (vision-language)">
          <StatusDot tone={visionTone} label={visionLabel} />
        </Row>
        {vision.enabled && !vision.ready && !vision.modelIdle && !vision.error && (
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
            <Row label="Described" value={`${visionStats.described} / ${visionStats.total}`} />
            <ProgressBar value={describedPct} tint={colors.good} />
            {!!visionBreakdown && <Text style={styles.faintSmall}>{visionBreakdown}</Text>}
            {tele.avgMs > 0 && (
              <Text style={styles.faintSmall}>
                ≈ {(tele.avgMs / 1000).toFixed(1)}s per meme
                {visionStats.queued > 0
                  ? ` · ~${formatEta(tele.avgMs * visionStats.queued)} of model time left`
                  : ''}
                {tele.deduped > 0 ? ` · ${tele.deduped} skipped as duplicates` : ''}
              </Text>
            )}
            {enriching ? (
              <View style={styles.enrichBlock}>
                <View style={styles.enrichTopRow}>
                  <Text style={styles.note}>
                    {enriching.total
                      ? `Describing ${enriching.done}/${enriching.total} · ${Math.round((enriching.done / enriching.total) * 100)}%`
                      : 'Describing…'}
                  </Text>
                  <Pressable onPress={() => (enrichCancel.current = true)} hitSlop={10}>
                    <Text style={styles.stopText}>Stop</Text>
                  </Pressable>
                </View>
                <ProgressBar value={enriching.total ? enriching.done / enriching.total : 0} />
                {!!enriching.current && (
                  <Text style={styles.faintSmall} numberOfLines={1}>
                    {enriching.current}
                  </Text>
                )}
              </View>
            ) : visionStats.queued > 0 ? (
              <Button
                small
                label={
                  vision.ready
                    ? `Describe ${visionStats.queued} meme${visionStats.queued === 1 ? '' : 's'}`
                    : `Describe ${visionStats.queued} meme${visionStats.queued === 1 ? '' : 's'} (loads model)`
                }
                onPress={onRunEnrich}
              />
            ) : visionStats.indexing > 0 ? (
              <Text style={styles.note}>
                {visionStats.indexing} meme{visionStats.indexing === 1 ? '' : 's'} still being indexed
                — describing picks them up automatically.
              </Text>
            ) : (
              <Text style={styles.note}>
                {visionStats.described > 0
                  ? 'Every meme that can be described has been ✓'
                  : 'Index some memes first, then describe them.'}
              </Text>
            )}
            {visionStats.failed > 0 && (
              <Button
                small
                variant="ghost"
                label={`Retry ${visionStats.failed} that failed`}
                onPress={onRetryVisionFailures}
              />
            )}
            {visionStats.unsupported > 0 && (
              <Text style={styles.faintSmall}>
                {visionStats.unsupported} file{visionStats.unsupported === 1 ? '' : 's'} couldn’t be
                read at all — see “Indexing errors” below.
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
                  <Text style={styles.faintSmall}>
                    Working in the background… {visionStats.described} described so far.
                  </Text>
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
        <Row label={`${AUDIO_MODEL_INFO.label} (speech-to-text)`}>
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
            {audioStats.analyzed > 0 && !transcribing && (
              <Button
                small
                variant="secondary"
                label="Regenerate all transcriptions"
                onPress={onRegenerateAllAudio}
                disabled={!audio.ready || audio.running}
              />
            )}
          </>
        )}
      </Section>

      <Section glyph="▦" title="Index" tint={colors.accent}>
        <Row label="Indexed memes" value={String(count)} />
        <Row label="Known meme formats" value={String(MEME_LABELS.length)} />
        {posterStats.total > 0 && (
          <Row
            label="Video posters"
            value={`${posterStats.done}/${posterStats.total}${posterStats.failed ? ` · ${posterStats.failed} failed` : ''}${posterStats.missing ? ` · ${posterStats.missing} queued` : ''}`}
            valueTint={posterStats.failed > 0 ? colors.danger : undefined}
          />
        )}
        {posterStats.failed > 0 && (
          <Button
            small
            variant="secondary"
            label={retryingPosters ? 'Retrying…' : `Retry ${posterStats.failed} failed posters`}
            onPress={onRetryPosters}
            disabled={retryingPosters}
          />
        )}
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
                {/* Poster failures pack three per-path reasons into one row —
                    give them room; they're the only debugging signal we get. */}
                <Text style={styles.errReason} numberOfLines={4}>
                  [{e.stage}] {e.reason}
                </Text>
              </View>
            ))}
          </View>
        )}
        <View style={styles.divider} />
        <Text style={styles.note}>
          Import an archive of memes: pulls every supported image and video out of a{' '}
          <Text style={styles.noteStrong}>.zip</Text> into your linked folder, skips anything already
          there, and indexes the rest in the background.
        </Text>
        {zipImport ? (
          <View style={{ gap: 8 }}>
            <Text style={styles.note}>
              {zipImport.phase === 'reading'
                ? 'Reading the archive…'
                : `Importing ${zipImport.done}/${zipImport.total || '…'}`}
            </Text>
            <ProgressBar value={zipImport.total ? zipImport.done / zipImport.total : 0} />
          </View>
        ) : (
          <Button
            small
            variant="secondary"
            icon="⇩"
            label="Import from a .zip"
            onPress={onImportZip}
            disabled={folders.length === 0}
          />
        )}
        {folders.length === 0 && (
          <Text style={styles.faintSmall}>Link a folder first to import into it.</Text>
        )}

        <View style={styles.divider} />
        <Button variant="dangerGhost" small label="Clear index" onPress={onClear} />
      </Section>

      <Section glyph="★" title="Taught knowledge" tint={colors.good}>
        <Row
          label="Tags you've taught"
          value={String(taughtStats.length)}
          valueTint={colors.good}
        />
        {staleExemplars > 0 && (
          <>
            <Text style={styles.errText}>
              {staleExemplars} taught example{staleExemplars === 1 ? '' : 's'} from a previous
              embedding model {staleExemplars === 1 ? 'is' : 'are'} hidden — they aren't deleted,
              but their vectors don't work in the current model's space. Your own examples can be
              migrated automatically from their source memes (index the library first if you
              haven't); pack-imported ones need a pack made with the current model.
            </Text>
            <Button
              small
              label={migrating ? 'Migrating…' : 'Migrate taught examples'}
              onPress={onMigrateExemplars}
              disabled={migrating || !emb.ready}
            />
          </>
        )}
        {taughtStats.length === 0 ? (
          <Text style={styles.note}>
            Open any meme and use “This IS a…” to teach a new character or format by example (e.g.
            Milady). Re-tagging applies it across everything already indexed — no re-scanning, it
            reuses the embeddings on device.
          </Text>
        ) : (
          <View style={styles.taughtList}>
            {taughtStats.map((t) => (
              <View
                key={t.label}
                style={styles.taughtRow}
                accessible
                accessibilityLabel={`${t.label}, ${t.tagged} memes tagged, ${t.positives} examples${t.negatives ? `, ${t.negatives} corrections` : ''}`}
              >
                <Text style={styles.taughtLabel} numberOfLines={1}>
                  {t.label}
                </Text>
                <Text style={styles.taughtCompactMeta}>
                  {t.tagged}m · {t.positives}+{t.negatives > 0 ? ` · ${t.negatives}−` : ''}
                </Text>
                <View style={[styles.srcChip, t.fromPack ? styles.srcPack : styles.srcSelf]}>
                  <Text style={[styles.srcChipText, { color: t.fromPack ? colors.accent : colors.muted }]}>
                    {t.fromSelf && t.fromPack ? 'you+pack' : t.fromPack ? 'pack' : 'you'}
                  </Text>
                </View>
                <Pressable hitSlop={8} onPress={() => onForget(t.label)} accessibilityLabel={`Forget ${t.label}`}>
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

        <View style={styles.divider} />
        <Text style={styles.note}>
          Export the tags the <Text style={styles.noteStrong}>model</Text> produced for your described
          memes (not your teachings) — used to measure how well it covers each facet.
        </Text>
        <Button
          small
          variant="secondary"
          icon="⇪"
          label="Export described tags"
          onPress={onExportDescribedTags}
          disabled={transferBusy}
          style={styles.transferBtn}
        />

        <View style={styles.divider} />
        <Text style={styles.note}>
          Export the whole collection as a <Text style={styles.noteStrong}>zip</Text> — every meme's
          image plus its tags, caption, and embeddings in one file to share.
        </Text>
        {exportProgress ? (
          <View style={{ gap: 8 }}>
            <Text style={styles.note}>
              {`Exporting ${exportProgress.done}/${exportProgress.total || '…'} — saving to Downloads`}
            </Text>
            <ProgressBar
              value={exportProgress.total ? exportProgress.done / exportProgress.total : 0}
            />
          </View>
        ) : (
          <Button
            small
            variant="secondary"
            icon="🗜"
            label="Export collection (zip)"
            onPress={onExportCollection}
            disabled={transferBusy}
            style={styles.transferBtn}
          />
        )}

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

      <Section glyph="🛟" title="Folder backup" tint={colors.good}>
        <Text style={styles.note}>
          Everything Memeget works out about your memes — tags, descriptions, transcripts,
          embeddings and the examples you’ve taught — is mirrored into a{' '}
          <Text style={styles.noteStrong}>.memeget</Text> folder inside each linked folder. It’s
          written automatically after every index. Reinstall the app, rename the package, or move to
          a new phone: link the same folder again and your knowledge comes back before a single
          model has to run.
        </Text>
        {backupStatus && <Text style={styles.note}>{backupStatus}</Text>}
        <Button
          variant="primary"
          icon="⇧"
          label={backingUp ? 'Backing up…' : 'Back up now'}
          onPress={onBackupNow}
          disabled={backingUp || folders.length === 0}
        />
        <Button
          variant="secondary"
          icon="⇩"
          label="Restore from linked folders"
          onPress={onRestoreSidecars}
          disabled={backingUp || folders.length === 0}
        />
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
        {formatBuildLabel(
          Constants.expoConfig?.version,
          Constants.platform?.android?.versionCode ??
            Constants.platform?.ios?.buildNumber ??
            Constants.expoConfig?.android?.versionCode ??
            Constants.expoConfig?.ios?.buildNumber
        )}
        {' · private, on-device meme search'}
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
  enrichBlock: { gap: 8 },
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
    paddingHorizontal: 10,
    paddingVertical: 2,
  },
  taughtRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    paddingVertical: 6,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  taughtMain: { flex: 1, gap: 2 },
  taughtTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  taughtLabel: { color: colors.text, fontSize: 13, fontWeight: '700', flex: 1 },
  taughtMeta: { color: colors.muted, fontSize: 11 },
  taughtCompactMeta: { color: colors.muted, fontSize: 10, fontVariant: ['tabular-nums'] },
  taughtForget: { color: colors.faint, fontSize: 15, fontWeight: '700', paddingHorizontal: 2 },
  srcChip: {
    paddingHorizontal: 5,
    paddingVertical: 0,
    borderRadius: radius.sm,
    borderWidth: 1,
  },
  srcSelf: { borderColor: colors.border, backgroundColor: 'transparent' },
  srcPack: { borderColor: colors.accent, backgroundColor: colors.surface },
  srcChipText: { fontSize: 8, fontWeight: '800', letterSpacing: 0.2, textTransform: 'uppercase' },
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
