import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { useShareIntent, type ShareIntentFile } from 'expo-share-intent';

import { useEmbeddings } from '../embeddings';
import { indexSavedFiles, saveSharedFiles } from '../indexer';
import { emitLibraryChanged } from '../events';
import { extractUrl, resolveSharedLink } from '../linkResolver';
import { colors, radius, shadow, TABBAR_CLEARANCE } from '../theme';
import { deleteCache, type SafFile } from '../saf';
import { importMemesFromZip } from '../zipImport';

// A shared .zip archive: matched by a zip content type, or an octet-stream/
// unknown type whose filename still ends in .zip (file managers vary). We pull
// every compatible meme out of it and then discard the archive.
const isZipShare = (f: ShareIntentFile): boolean =>
  /^application\/(zip|x-zip-compressed|x-zip)$/i.test(f.mimeType ?? '') ||
  /\.zip$/i.test(f.fileName ?? '');

// Share-intent file paths arrive as bare filesystem paths on some providers;
// FileSystem reads/deletes need a scheme.
const toUri = (p: string): string =>
  p.startsWith('file://') || p.startsWith('content://') ? p : `file://${p}`;

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

  // Phase 1: accept the share the moment it arrives. No model wait. Two kinds
  // of share land here:
  //   • image/video files       → copied straight into the linked folder.
  //   • a link (X / Tenor / any  → resolved to its underlying media, downloaded,
  //     social post URL)           then saved the same way. The download is the
  //     ONE time the app touches the network, and only because you handed it a
  //     URL — nothing is uploaded.
  useEffect(() => {
    const allFiles = shareIntent?.files ?? [];
    const files = allFiles.filter((f: ShareIntentFile) => /^(image|video)\//.test(f.mimeType));
    // A shared archive is imported in bulk: every compatible meme inside it is
    // extracted, then the archive is thrown away.
    const zipFiles = allFiles.filter(isZipShare);
    // Only treat the share as a link if no media file / archive came with it.
    const sharedUrl =
      files.length === 0 && zipFiles.length === 0
        ? extractUrl(shareIntent?.webUrl, shareIntent?.text)
        : null;
    if (!hasShareIntent || (files.length === 0 && zipFiles.length === 0 && !sharedUrl)) return;
    if (savingRef.current) return;

    savingRef.current = true;

    // Hand freshly saved files to the background indexer, refresh the library,
    // and report the outcome. Shared by the file and link paths.
    const acceptSaved = (res: { saved: SafFile[]; errors: number; folderName: string }) => {
      if (res.saved.length > 0) {
        queueRef.current.push(...res.saved);
        setTick((t) => t + 1);
      }
      emitLibraryChanged();
      const errNote = res.errors > 0 ? ` (${res.errors} failed)` : '';
      setStatus({
        kind: 'done',
        msg: `✓ Saved ${res.saved.length} to “${res.folderName}” — indexing in background${errNote}`,
      });
    };

    (async () => {
      try {
        if (sharedUrl) {
          // Link path: fetch the page/endpoint → download the media → save it.
          setStatus({ kind: 'importing', msg: 'Reading link…' });
          const media = await resolveSharedLink(sharedUrl, {
            onProgress: (p) =>
              setStatus({
                kind: 'importing',
                msg: p.stage === 'downloading' ? 'Downloading meme…' : 'Reading link…',
              }),
          });
          try {
            const res = await saveSharedFiles([
              { path: media.path, fileName: media.fileName, mimeType: media.mimeType },
            ]);
            acceptSaved(res);
          } finally {
            // The download was a throwaway cache copy; saveSharedFiles already
            // wrote a permanent copy into the linked folder.
            await deleteCache(media.path);
          }
        } else if (zipFiles.length > 0) {
          // Zip path: unpack every compatible meme into the linked folder,
          // skipping duplicates, then discard the shared archive. Extraction
          // saves pending rows and hands them to the same background indexer as
          // any other share; the copies in the folder are what persist.
          setStatus({ kind: 'importing', msg: 'Reading the shared zip…' });
          let imported = 0;
          let duplicates = 0;
          let unsupported = 0;
          let errors = 0;
          let folderName = '';
          const savedAll: SafFile[] = [];
          for (const zf of zipFiles) {
            const zipUri = toUri(zf.path);
            try {
              const res = await importMemesFromZip(zipUri, {
                zipName: zf.fileName,
                onProgress: (done, total, phase) =>
                  setStatus({
                    kind: 'importing',
                    msg:
                      phase === 'reading'
                        ? 'Reading the shared zip…'
                        : `Importing ${done}/${total || '…'} from zip…`,
                  }),
              });
              imported += res.imported;
              duplicates += res.duplicates;
              unsupported += res.unsupported;
              errors += res.errors;
              folderName = res.folderName;
              savedAll.push(...res.saved);
            } finally {
              // Discard the shared archive — the memes now live in the folder.
              await deleteCache(zipUri);
            }
          }
          if (savedAll.length > 0) {
            queueRef.current.push(...savedAll);
            setTick((t) => t + 1);
          }
          emitLibraryChanged();
          const extras: string[] = [];
          if (duplicates > 0) extras.push(`${duplicates} dup${duplicates === 1 ? '' : 's'} skipped`);
          if (unsupported > 0) extras.push(`${unsupported} unsupported`);
          if (errors > 0) extras.push(`${errors} failed`);
          const note = extras.length ? ` (${extras.join(' · ')})` : '';
          const where = folderName ? ` to “${folderName}”` : '';
          setStatus({
            kind: 'done',
            msg: `✓ Imported ${imported} meme${imported === 1 ? '' : 's'} from zip${where} — indexing in background${note}`,
          });
        } else {
          // File path: copy each shared image/video into the linked folder.
          const total = files.length;
          setStatus({ kind: 'importing', msg: total > 1 ? `Saving 0/${total} memes…` : 'Saving meme…' });
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
          acceptSaved(res);
        }
      } catch (e) {
        setStatus({ kind: 'error', msg: String((e as Error)?.message ?? e) });
      } finally {
        // Reset right away so the share intent clears and the user can move on.
        // NOTE: we deliberately do NOT clear savingRef here. resetShareIntent()
        // clears hasShareIntent asynchronously (a native round-trip), so for a
        // moment the effect can still observe the old intent. If we reopened the
        // gate now, that stale intent would re-fire the effect and import the
        // same share a second time — which is exactly the "2 copies" bug on the
        // slow link path, where the wide network window makes the re-fire easy
        // to hit. The gate is reopened only once hasShareIntent actually flips
        // false (the effect below), so the same delivery can't be handled twice.
        resetShareIntent();
        setTimeout(() => setStatus(null), 3600);
      }
    })();
  }, [hasShareIntent, shareIntent, resetShareIntent]);

  // Reopen the import gate only when the share intent has genuinely cleared, not
  // the instant the async import finishes. This closes the race where a stale,
  // not-yet-reset intent re-triggers the import (see the note in the finally).
  useEffect(() => {
    if (!hasShareIntent) savingRef.current = false;
  }, [hasShareIntent]);

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
