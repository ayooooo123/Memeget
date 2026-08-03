import * as DocumentPicker from 'expo-document-picker';
import React, { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';

import {
  addSubjectSegmentationProgressListener,
  cancelSubjectSegmentation,
  releaseSubjectCutouts,
  segmentImageSubjects,
  subjectSegmentationAvailable,
  subjectSegmentationModuleInstalled,
  sweepSubjectCutouts,
} from '../../modules/memeget-bg';
import {
  CUTOUT_BACKGROUND_MODES,
  DEFAULT_CUTOUT_BACKGROUND_COLOR,
  DEFAULT_CUTOUT_STICKER,
  abandonedCutoutRequestIds,
  buildCutoutApplication,
  cutoutCancellable,
  cutoutResultFromNative,
  cutoutSelectionOptions,
  cutoutStatusLabel,
  drainCutoutOrphans,
  initialCutoutState,
  isCutoutBusy,
  memeCutoutReducer,
  normalizeCutoutSticker,
  planSubjectSegmentation,
  selectedCutoutRef,
  type CutoutBackgroundChoice,
  type CutoutBackgroundMode,
  type CutoutSelection,
  type CutoutStickerChoice,
} from '../memeCutoutCore';
import type { MemeEditProjectAction, MemeEditProject } from '../memeEditProjectCore';
import { colors, radius, space, type } from '../theme';
import { PressableScale, Slider } from './ui';

const SOLID_COLORS = ['#000000', '#FFFFFF', '#B8FF2C', '#FF4E42', '#20242A', '#E9E5DC'] as const;

export const MemeSubjectTool = React.memo(function MemeSubjectTool({
  project,
  sourceUri,
  idPrefix,
  disabled,
  onApplyActions,
  onSelectLayer,
}: {
  project: MemeEditProject;
  /** The FULL source: segmentation runs on the uncropped, EXIF-oriented image. */
  sourceUri: string;
  idPrefix: string;
  disabled?: boolean;
  onApplyActions: (actions: MemeEditProjectAction[]) => void;
  onSelectLayer: (id: string | null) => void;
}) {
  const [state, dispatch] = useReducer(memeCutoutReducer, undefined, initialCutoutState);
  const [selection, setSelection] = useState<CutoutSelection>({ kind: 'all' });
  const [background, setBackground] = useState<CutoutBackgroundChoice>({
    mode: 'transparent',
    color: DEFAULT_CUTOUT_BACKGROUND_COLOR,
    assetUri: null,
  });
  const [sticker, setSticker] = useState<CutoutStickerChoice>({ ...DEFAULT_CUTOUT_STICKER });
  const [applyError, setApplyError] = useState('');
  const [moduleInstalled, setModuleInstalled] = useState<boolean | null>(null);
  const requestCounter = useRef(0);
  // The state the cleanup effect reads on unmount. A ref, because that effect
  // must not re-run on every state change — it would delete live cutouts.
  const latest = useRef(state);
  latest.current = state;

  const plan = useMemo(
    () => planSubjectSegmentation({ width: project.source.width, height: project.source.height }),
    [project.source.height, project.source.width]
  );

  useEffect(() => {
    if (!subjectSegmentationAvailable) return;
    let cancelled = false;
    subjectSegmentationModuleInstalled().then(
      (installed) => {
        if (!cancelled) setModuleInstalled(installed);
      },
      () => {
        if (!cancelled) setModuleInstalled(false);
      }
    );
    // Old cutouts from a session that crashed are nobody's now.
    sweepSubjectCutouts().catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  // Progress arrives on a native event, keyed by request id so a late event from
  // a superseded run cannot move the current run's bar.
  useEffect(() => {
    const subscription = addSubjectSegmentationProgressListener((payload) => {
      const phase = latest.current.phase;
      // Narrow once: `request` and `runId` only exist together, on the three
      // in-flight phases. Reading either off a bare `idle` is a type error and,
      // worse, would match a stale event against nothing.
      const active =
        phase.kind === 'preparing' || phase.kind === 'downloading' || phase.kind === 'segmenting'
          ? phase
          : null;
      if (!active || payload.requestId !== active.request.requestId) return;
      const { runId } = active;
      if (payload.phase === 'segmenting') {
        dispatch({ type: 'segmentStarted', runId });
        return;
      }
      dispatch({
        type: 'downloadProgress',
        runId,
        bytesDownloaded: payload.bytesDownloaded ?? 0,
        totalBytes: payload.totalBytes ?? 0,
      });
    });
    return () => subscription.remove();
  }, []);

  // Orphaned cutout files, deleted as they appear. Failures are the native
  // sweep's problem; the list is cleared either way so it cannot grow forever.
  useEffect(() => {
    if (state.orphans.length === 0) return;
    let cancelled = false;
    drainCutoutOrphans(state, releaseSubjectCutouts).then(() => {
      if (!cancelled) dispatch({ type: 'orphansDrained' });
    });
    return () => {
      cancelled = true;
    };
  }, [state]);

  // Leaving the tool with an unapplied cutout — or mid-run — must not leave a
  // full-resolution PNG in the cache.
  useEffect(() => () => {
    const pending = latest.current;
    const active =
      pending.phase.kind === 'preparing' ||
      pending.phase.kind === 'downloading' ||
      pending.phase.kind === 'segmenting'
        ? pending.phase.request.requestId
        : null;
    if (active) cancelSubjectSegmentation(active);
    for (const requestId of abandonedCutoutRequestIds(pending)) {
      releaseSubjectCutouts(requestId).catch(() => {});
    }
  }, []);

  const findSubjects = useCallback(async () => {
    if (disabled || !subjectSegmentationAvailable) return;
    setApplyError('');
    requestCounter.current += 1;
    const requestId = `${idPrefix}-${Date.now()}-${requestCounter.current}`.replace(
      /[^a-zA-Z0-9_-]/g,
      '-'
    );
    const request = { sourceUri, requestId, plan };
    dispatch({ type: 'start', request });
    const runId = latest.current.nextRunId;
    try {
      const native = await segmentImageSubjects(sourceUri, requestId);
      if (!native) {
        dispatch({ type: 'failed', runId, code: 'E_CUTOUT_FAILED', detail: 'native module absent' });
        return;
      }
      const result = cutoutResultFromNative(native, sourceUri);
      dispatch({ type: 'succeeded', runId, result });
      setSelection({ kind: 'all' });
      // The model is on the device now, whatever it was before.
      setModuleInstalled(true);
    } catch (error) {
      const code =
        error && typeof error === 'object' && 'code' in error
          ? String((error as { code?: unknown }).code)
          : 'E_CUTOUT_FAILED';
      const detail = error instanceof Error ? error.message : String(error);
      dispatch({ type: 'failed', runId, code, detail });
    }
  }, [disabled, idPrefix, plan, sourceUri]);

  const cancel = useCallback(() => {
    const phase = latest.current.phase;
    if (
      phase.kind === 'preparing' ||
      phase.kind === 'downloading' ||
      phase.kind === 'segmenting'
    ) {
      cancelSubjectSegmentation(phase.request.requestId);
      dispatch({ type: 'cancel' });
    }
  }, []);

  const pickReplacement = useCallback(async () => {
    const picked = await DocumentPicker.getDocumentAsync({
      type: ['image/*'],
      copyToCacheDirectory: true,
      multiple: false,
    });
    const asset = picked.assets?.[0];
    if (picked.canceled || !asset) return;
    setBackground((current) => ({ ...current, mode: 'image', assetUri: asset.uri }));
    setApplyError('');
  }, []);

  const chooseBackgroundMode = useCallback(
    (mode: CutoutBackgroundMode) => {
      setApplyError('');
      setBackground((current) => ({ ...current, mode }));
      if (mode === 'image' && !background.assetUri) void pickReplacement();
    },
    [background.assetUri, pickReplacement]
  );

  const apply = useCallback(() => {
    const phase = latest.current.phase;
    if (phase.kind !== 'ready') return;
    const outcome = buildCutoutApplication({
      project,
      result: phase.result,
      selection,
      background,
      sticker,
      idPrefix,
    });
    if (!outcome.ok) {
      setApplyError(outcome.message);
      return;
    }
    setApplyError('');
    const application = outcome.application;
    // One undo entry: the mask track, its materialized cutout, the layers and
    // the background are a single edit as far as the user is concerned.
    onApplyActions([
      ...application.maskTracks.map((track) => ({ type: 'add-mask-track' as const, track })),
      ...application.maskTracks.map((track) => ({
        type: 'set-mask-track-uri' as const,
        trackId: track.id,
        uri: application.transientMaskTracks[track.id],
      })),
      { type: 'add-layers', layers: application.layers },
      { type: 'set-background', background: application.background },
    ]);
    // The project owns these files now; the tool must stop treating them as its.
    dispatch({ type: 'applied' });
    onSelectLayer(application.layers[application.layers.length - 1].id);
  }, [background, idPrefix, onApplyActions, onSelectLayer, project, selection, sticker]);

  const phase = state.phase;
  const busy = isCutoutBusy(state);
  const status = cutoutStatusLabel(state);
  const options = phase.kind === 'ready' ? cutoutSelectionOptions(phase.result) : [];
  const chosen = phase.kind === 'ready' ? selectedCutoutRef(phase.result, selection) : null;
  const downloadFraction = phase.kind === 'downloading' ? phase.progress.fraction : null;

  return (
    <ScrollView contentContainerStyle={styles.root} keyboardShouldPersistTaps="handled">
      <Text style={styles.title}>Cut out subject</Text>
      <Text style={styles.copy}>
        Find the subjects in this photo on this device, keep one or all of them, and choose what
        goes behind. Nothing leaves the phone.
      </Text>

      {!subjectSegmentationAvailable ? (
        <View style={styles.notice} accessibilityRole="text">
          <Text style={styles.noticeText}>
            Subject cutouts are not built into this app version. Replace text can still cover a
            region.
          </Text>
        </View>
      ) : (
        <>
          {moduleInstalled === false && !busy && (
            <View style={styles.notice} accessibilityRole="text">
              <Text style={styles.noticeText}>
                The cutout model has not been downloaded yet. The first cutout downloads it once
                (a few MB), then works offline.
              </Text>
            </View>
          )}
          {plan.belowRecommendedResolution && (
            <View style={styles.notice} accessibilityRole="text">
              <Text style={styles.noticeText}>
                This image is {project.source.width}×{project.source.height}. Cutouts get rough
                below 512 px on the short edge.
              </Text>
            </View>
          )}

          <View style={styles.topActions}>
            <PressableScale
              onPress={findSubjects}
              disabled={disabled || busy}
              style={styles.primaryAction}
              accessibilityRole="button"
              accessibilityLabel={phase.kind === 'ready' ? 'Find subjects again' : 'Find subjects'}
              accessibilityHint="Segment the subjects of this photo on this device"
              accessibilityState={{ busy, disabled: !!disabled || busy }}
            >
              {busy ? (
                <ActivityIndicator color={colors.onVolt} />
              ) : (
                <Text style={styles.primaryActionText}>
                  {phase.kind === 'ready' ? 'Find again' : 'Find subjects'}
                </Text>
              )}
            </PressableScale>
            {busy && (
              <PressableScale
                onPress={cancel}
                disabled={!cutoutCancellable(state)}
                style={styles.secondaryAction}
                accessibilityRole="button"
                accessibilityLabel="Cancel the cutout"
                accessibilityHint="Stop the model download or the search for subjects"
                accessibilityState={{ disabled: !cutoutCancellable(state) }}
              >
                <Text style={styles.secondaryActionText}>Cancel</Text>
              </PressableScale>
            )}
          </View>

          {!!status && (
            <Text
              style={[styles.metric, phase.kind === 'failed' && styles.error]}
              accessibilityRole={phase.kind === 'failed' ? 'alert' : 'text'}
            >
              {status}
            </Text>
          )}
          {downloadFraction !== null && (
            <View
              style={styles.progressTrack}
              accessibilityRole="progressbar"
              accessibilityLabel="Cutout model download"
              accessibilityValue={{ now: Math.round(downloadFraction * 100), min: 0, max: 100 }}
            >
              <View style={[styles.progressFill, { width: `${Math.round(downloadFraction * 100)}%` }]} />
            </View>
          )}
          {phase.kind === 'failed' && (
            <Text style={styles.remedy} accessibilityRole="text">
              {phase.failure.remedy}
            </Text>
          )}
          {phase.kind === 'failed' && phase.failure.retryable && (
            <PressableScale
              onPress={findSubjects}
              disabled={disabled}
              style={styles.secondaryAction}
              accessibilityRole="button"
              accessibilityLabel="Try the cutout again"
              accessibilityHint="Run subject segmentation again"
            >
              <Text style={styles.secondaryActionText}>Try again</Text>
            </PressableScale>
          )}
          {phase.kind === 'empty' && (
            <Text style={styles.remedy}>
              Nothing in this photo reads as a subject. Try a photo where the subject stands out,
              or use Replace text to cover a region.
            </Text>
          )}

          {phase.kind === 'ready' && (
            <>
              <Text style={styles.fieldLabel}>Keep</Text>
              <View
                style={styles.optionRow}
                accessibilityRole="radiogroup"
                accessibilityLabel="Which subject to keep"
              >
                {options.map((option) => {
                  const active =
                    option.selection.kind === selection.kind &&
                    (option.selection.kind === 'all' ||
                      (selection.kind === 'subject' && option.selection.index === selection.index));
                  return (
                    <PressableScale
                      key={option.label}
                      onPress={() => setSelection(option.selection)}
                      disabled={disabled}
                      style={[styles.option, active && styles.optionSelected]}
                      accessibilityRole="radio"
                      accessibilityLabel={`${option.label}, ${Math.round(option.coverage * 100)} percent of the image`}
                      accessibilityState={{ checked: active, disabled: !!disabled }}
                    >
                      <Text style={[styles.optionText, active && styles.optionTextSelected]}>
                        {option.label}
                      </Text>
                      <Text style={styles.optionMeta}>{Math.round(option.coverage * 100)}%</Text>
                    </PressableScale>
                  );
                })}
              </View>
              {phase.result.droppedSubjects > 0 && (
                <Text style={styles.metric}>
                  {phase.result.droppedSubjects} more subject
                  {phase.result.droppedSubjects === 1 ? '' : 's'} found than a meme can hold; the
                  largest are listed.
                </Text>
              )}

              <Text style={styles.fieldLabel}>Background</Text>
              <View
                style={styles.optionRow}
                accessibilityRole="radiogroup"
                accessibilityLabel="What goes behind the subject"
              >
                {CUTOUT_BACKGROUND_MODES.map((entry) => {
                  const active = background.mode === entry.mode;
                  return (
                    <PressableScale
                      key={entry.mode}
                      onPress={() => chooseBackgroundMode(entry.mode)}
                      disabled={disabled}
                      style={[styles.option, active && styles.optionSelected]}
                      accessibilityRole="radio"
                      accessibilityLabel={entry.label}
                      accessibilityHint={entry.hint}
                      accessibilityState={{ checked: active, disabled: !!disabled }}
                    >
                      <Text style={[styles.optionText, active && styles.optionTextSelected]}>
                        {entry.label}
                      </Text>
                    </PressableScale>
                  );
                })}
              </View>

              {background.mode === 'solid' && (
                <View style={styles.palette} accessibilityRole="radiogroup" accessibilityLabel="Background colour">
                  {SOLID_COLORS.map((color) => (
                    <PressableScale
                      key={color}
                      onPress={() => setBackground((current) => ({ ...current, color }))}
                      disabled={disabled}
                      style={[
                        styles.swatch,
                        { backgroundColor: color },
                        background.color === color && styles.swatchSelected,
                      ]}
                      accessibilityRole="radio"
                      accessibilityLabel={`Use ${color} background`}
                      accessibilityState={{ checked: background.color === color, disabled: !!disabled }}
                    >
                      <Text style={[styles.swatchMark, color === '#000000' && styles.swatchMarkLight]}>
                        {background.color === color ? 'On' : ''}
                      </Text>
                    </PressableScale>
                  ))}
                </View>
              )}

              {background.mode === 'blurred-source' && (
                <View style={styles.sliderRow}>
                  <Text style={styles.metric}>
                    Blur {Math.round((background.blurScale ?? 0.6) * 100)}%
                  </Text>
                  <Slider
                    value={background.blurScale ?? 0.6}
                    onChange={(value) => setBackground((current) => ({ ...current, blurScale: value }))}
                    accessibilityLabel="Background blur strength"
                    accessibilityDisabled={disabled}
                  />
                </View>
              )}

              {background.mode === 'image' && (
                <PressableScale
                  onPress={pickReplacement}
                  disabled={disabled}
                  style={styles.secondaryAction}
                  accessibilityRole="button"
                  accessibilityLabel={background.assetUri ? 'Change the replacement image' : 'Choose a replacement image'}
                  accessibilityHint="Pick a picture to put behind the subject"
                >
                  <Text style={styles.secondaryActionText}>
                    {background.assetUri ? 'Change image' : 'Choose image'}
                  </Text>
                </PressableScale>
              )}

              <Text style={styles.fieldLabel}>Sticker</Text>
              <View style={styles.optionRow}>
                {(
                  [
                    ['outline', 'Outline'],
                    ['shadow', 'Shadow'],
                    ['duplicate', 'Duplicate'],
                  ] as const
                ).map(([key, label]) => {
                  const active = sticker[key];
                  return (
                    <PressableScale
                      key={key}
                      onPress={() =>
                        setSticker((current) =>
                          normalizeCutoutSticker({ ...current, [key]: !current[key] })
                        )
                      }
                      disabled={disabled}
                      style={[styles.option, active && styles.optionSelected]}
                      accessibilityRole="switch"
                      accessibilityLabel={`${label} effect`}
                      accessibilityState={{ checked: active, disabled: !!disabled }}
                    >
                      <Text style={[styles.optionText, active && styles.optionTextSelected]}>
                        {label}
                      </Text>
                      <Text style={styles.optionMeta}>{active ? 'on' : 'off'}</Text>
                    </PressableScale>
                  );
                })}
              </View>
              {sticker.outline && (
                <View style={styles.sliderRow}>
                  <Text style={styles.metric}>Outline {Math.round(sticker.outlineScale * 100)}%</Text>
                  <Slider
                    value={sticker.outlineScale}
                    onChange={(value) =>
                      setSticker((current) => normalizeCutoutSticker({ ...current, outlineScale: value }))
                    }
                    accessibilityLabel="Outline thickness"
                    accessibilityDisabled={disabled}
                  />
                </View>
              )}
              {sticker.shadow && (
                <View style={styles.sliderRow}>
                  <Text style={styles.metric}>Shadow {Math.round(sticker.shadowScale * 100)}%</Text>
                  <Slider
                    value={sticker.shadowScale}
                    onChange={(value) =>
                      setSticker((current) => normalizeCutoutSticker({ ...current, shadowScale: value }))
                    }
                    accessibilityLabel="Shadow strength"
                    accessibilityDisabled={disabled}
                  />
                </View>
              )}

              {!!chosen && (
                <Text style={styles.metric}>
                  {Math.round(chosen.bounds.width * 100)}% × {Math.round(chosen.bounds.height * 100)}%
                  of the image, {chosen.widthPx}×{chosen.heightPx} px.
                </Text>
              )}
              {!!applyError && (
                <Text style={styles.error} accessibilityRole="alert">
                  {applyError}
                </Text>
              )}
              <PressableScale
                onPress={apply}
                disabled={disabled || !chosen}
                style={styles.primaryAction}
                accessibilityRole="button"
                accessibilityLabel="Add this cutout"
                accessibilityHint="Add the chosen subject and background to the meme"
                accessibilityState={{ disabled: !!disabled || !chosen }}
              >
                <Text style={styles.primaryActionText}>Add cutout</Text>
              </PressableScale>
            </>
          )}
        </>
      )}
    </ScrollView>
  );
});

const styles = StyleSheet.create({
  root: { padding: space.md, gap: space.sm },
  title: { ...type.title, color: colors.text },
  copy: { ...type.body, color: colors.muted },
  notice: {
    backgroundColor: colors.surface2,
    borderRadius: radius.md,
    padding: space.sm,
  },
  noticeText: { ...type.caption, color: colors.muted },
  topActions: { flexDirection: 'row', gap: space.sm, alignItems: 'center' },
  primaryAction: {
    minHeight: 48,
    flexGrow: 1,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.md,
    backgroundColor: colors.volt,
    paddingHorizontal: space.md,
  },
  primaryActionText: { ...type.title, color: colors.onVolt },
  secondaryAction: {
    minHeight: 48,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: space.md,
  },
  secondaryActionText: { ...type.title, color: colors.text },
  metric: { ...type.caption, color: colors.muted },
  remedy: { ...type.caption, color: colors.text },
  error: { ...type.caption, color: colors.danger },
  fieldLabel: { ...type.label, color: colors.text, marginTop: space.xs },
  optionRow: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  option: {
    minHeight: 44,
    justifyContent: 'center',
    paddingHorizontal: space.sm,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.border,
  },
  optionSelected: { borderColor: colors.volt, backgroundColor: colors.surface2 },
  optionText: { ...type.caption, color: colors.text },
  optionTextSelected: { color: colors.volt },
  optionMeta: { ...type.micro, color: colors.faint },
  palette: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  swatch: {
    width: 44,
    height: 44,
    borderRadius: radius.sm,
    borderWidth: 2,
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  swatchSelected: { borderColor: colors.volt },
  swatchMark: { ...type.micro, color: colors.text },
  swatchMarkLight: { color: colors.bg },
  sliderRow: { gap: space.xs },
  progressTrack: {
    height: 6,
    borderRadius: 3,
    backgroundColor: colors.surface2,
    overflow: 'hidden',
  },
  progressFill: { height: 6, backgroundColor: colors.volt },
});
