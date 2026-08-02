import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  AppState,
  KeyboardAvoidingView,
  Modal,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  MemeEditAutosaveController,
  MemeEditDraftStore,
  MemeEditSourceSessionController,
  flushAutosaveBeforeSourceRelease,
  requestSourceSessionClose,
  createExpoMemeEditDraftIo,
  createExpoMemeEditSourcePreparationIo,
  type MemeEditDraftIdentity,
} from '../memeEditDraftStore';
import {
  applyProjectAction,
  createProjectHistory,
  redoProjectHistory,
  undoProjectHistory,
  type MemeEditProject,
  type MemeEditProjectAction,
  type ProjectHistory,
  type TransformKeyframe,
} from '../memeEditProjectCore';
import { commitGestureTransaction, nextDuplicateLayerId } from '../memeEditCanvasCore';
import { tap, warn } from '../haptics';
import { colors, radius, space, type } from '../theme';
import type { MemeRecord } from '../types';
import { PressableScale } from './ui';
import { MemeEditCanvas } from './MemeEditCanvas';
import { MemeEditToolRail, type MemeEditTool } from './MemeEditToolRail';
import { MemeLayerList } from './MemeLayerList';

type StudioItem = Pick<MemeRecord, 'id' | 'kind' | 'name' | 'uri' | 'modifiedAt'>;

type LoadState =
  | { kind: 'closed' }
  | { kind: 'loading'; message: string }
  | { kind: 'prompting'; message: string }
  | { kind: 'ready'; identity: MemeEditDraftIdentity; history: ProjectHistory }
  | { kind: 'error'; message: string };


function selectedLayerSummary(project: MemeEditProject, selectedLayerId: string | null): string {
  const layer = selectedLayerId ? project.layers.find((candidate) => candidate.id === selectedLayerId) : null;
  if (!layer) return 'No layer selected';
  if (layer.kind === 'text') {
    const keyframe = layer.keyframes[0];
    return `Text · ${keyframe ? `${(keyframe.scale * 100).toFixed(0)}% · ${keyframe.rotationDegrees.toFixed(0)}°` : 'no keyframe'}`;
  }
  if (layer.kind === 'cover') return `${layer.mode} cover · ${(layer.rect.width * 100).toFixed(0)}% wide`;
  if (layer.kind === 'subject') return 'Subject layer · mask required';
  return `${layer.assetKind} overlay · ${layer.fit}`;
}

function HeaderButton({
  label,
  hint,
  onPress,
  disabled,
  primary,
  danger,
  onPressIn,
  onPressOut,
}: {
  label: string;
  hint: string;
  onPress?: () => void;
  disabled?: boolean;
  primary?: boolean;
  danger?: boolean;
  onPressIn?: () => void;
  onPressOut?: () => void;
}) {
  return (
    <PressableScale
      scaleTo={0.94}
      disabled={disabled}
      onPress={onPress}
      onPressIn={onPressIn}
      onPressOut={onPressOut}
      style={[styles.headerButton, primary && styles.headerPrimary, danger && styles.headerDanger]}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: !!disabled }}
    >
      <Text style={[styles.headerButtonText, primary && styles.headerPrimaryText, danger && styles.headerDangerText]}>{label}</Text>
    </PressableScale>
  );
}

export function MemeRemixStudio({
  item,
  visible,
  exportBusy,
  exportError,
  onClose,
  onExport,
}: {
  item: StudioItem | null;
  visible: boolean;
  exportBusy?: boolean;
  exportError?: string;
  onClose: () => void;
  onExport?: (project: MemeEditProject) => Promise<void> | void;
}) {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const compact = width < 430 || height < 720;
  const draftStore = useMemo(() => new MemeEditDraftStore(createExpoMemeEditDraftIo()), []);
  const sourceIo = useMemo(() => createExpoMemeEditSourcePreparationIo(), []);
  const [state, setState] = useState<LoadState>({ kind: 'closed' });
  const [retryNonce, setRetryNonce] = useState(0);
  const [activeTool, setActiveTool] = useState<MemeEditTool>('layers');
  const [selectedLayerId, setSelectedLayerId] = useState<string | null>(null);
  const [before, setBefore] = useState(false);
  const [inlineError, setInlineError] = useState('');
  const [discarding, setDiscarding] = useState(false);
  const autosaveRef = useRef<MemeEditAutosaveController | null>(null);
  const sourceControllerRef = useRef<MemeEditSourceSessionController | null>(null);
  const closedRef = useRef(true);

  const ready = state.kind === 'ready' ? state : null;
  const project = ready?.history.present ?? null;

  const closeSessionAssets = useCallback(async () => {
    const autosave = autosaveRef.current;
    const controller = sourceControllerRef.current;
    let cleanupError: unknown = null;
    const outcome = await flushAutosaveBeforeSourceRelease(
      autosave,
      async () => {
        if (controller) await controller.cancel();
      },
      (error) => {
        cleanupError = error;
      }
    );
    if (cleanupError) throw cleanupError;
    if (outcome === 'released' && autosaveRef.current === autosave) autosaveRef.current = null;
    if (outcome === 'released' && sourceControllerRef.current === controller) sourceControllerRef.current = null;
  }, []);

  useEffect(() => {
    if (!visible || !item) {
      closedRef.current = true;
      setBefore(false);
      setInlineError('');
      setDiscarding(false);
      setSelectedLayerId(null);
      setState({ kind: 'closed' });
      void closeSessionAssets().catch((error) => {
        if (!closedRef.current) setInlineError(`Could not close edit session: ${String(error)}`);
      });
      return;
    }

    closedRef.current = false;
    let cancelled = false;
    setState({ kind: 'loading', message: 'Preparing source…' });
    setInlineError('');

    const safeSetState = (next: LoadState) => {
      if (!cancelled && !closedRef.current) setState(next);
    };
    const beginReady = (identity: MemeEditDraftIdentity, initialProject: MemeEditProject) => {
      if (cancelled || closedRef.current) return;
      const autosave = new MemeEditAutosaveController(draftStore, identity, {
        onError: (error) => {
          if (!closedRef.current) setInlineError(`Draft autosave failed: ${String(error)}`);
        },
      });
      autosaveRef.current = autosave;
      const history = createProjectHistory(initialProject);
      autosave.schedule(initialProject);
      setSelectedLayerId(initialProject.layers[initialProject.layers.length - 1]?.id ?? null);
      safeSetState({ kind: 'ready', identity, history });
    };
    const chooseDraft = (
      identity: MemeEditDraftIdentity,
      defaultProject: MemeEditProject,
      restoredProject: MemeEditProject,
      savedAtMs: number,
      materializedSourceUri: string
    ) => {
      safeSetState({ kind: 'prompting', message: 'A matching draft is available.' });
      Alert.alert('Restore edit draft?', `Saved ${new Date(savedAtMs).toLocaleString()}.`, [
        {
          text: 'Discard draft',
          style: 'destructive',
          onPress: () => {
            draftStore.discard(identity)
              .then(() => beginReady(identity, defaultProject))
              .catch((error) => safeSetState({ kind: 'error', message: `Could not discard draft: ${String(error)}` }));
          },
        },
        {
          text: 'Restore',
          onPress: () => beginReady(identity, {
            ...restoredProject,
            transient: { ...restoredProject.transient, materializedSourceUri },
          }),
        },
      ]);
    };

    const sourceController = new MemeEditSourceSessionController(sourceIo, {
      sessionId: `meme-remix/${item.id}`,
      uri: item.uri,
      name: item.name,
      indexedKind: item.kind,
      modifiedTimeMs: item.modifiedAt ?? null,
    });
    sourceControllerRef.current = sourceController;
    sourceController.prepare()
      .then(async (prepared) => {
        const restored = await draftStore.restore(prepared.identity);
        if (cancelled || closedRef.current) return;
        if (restored.status === 'restored') {
          chooseDraft(prepared.identity, prepared.project, restored.project, restored.savedAtMs, prepared.materializedSourceUri);
        } else {
          beginReady(prepared.identity, prepared.project);
        }
      })
      .catch((error) => safeSetState({ kind: 'error', message: `Could not prepare source: ${String(error)}` }));

    return () => {
      cancelled = true;
      void closeSessionAssets().catch(() => {});
    };
  }, [closeSessionAssets, draftStore, item, retryNonce, sourceIo, visible]);

  useEffect(() => {
    if (!project || !autosaveRef.current) return;
    autosaveRef.current.schedule(project);
  }, [project]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (next) => {
      if (next === 'background' || next === 'inactive') {
        void autosaveRef.current?.flush().catch((error) => {
          if (!closedRef.current) setInlineError(`Draft flush failed: ${String(error)}`);
        });
      }
    });
    return () => sub.remove();
  }, []);

  const setHistory = useCallback((updater: (history: ProjectHistory) => ProjectHistory) => {
    setState((current) => {
      if (current.kind !== 'ready') return current;
      return { ...current, history: updater(current.history) };
    });
  }, []);

  const applyAction = useCallback((action: MemeEditProjectAction) => {
    if (!discarding) setHistory((history) => applyProjectAction(history, action));
  }, [discarding, setHistory]);

  const commitLayerKeyframes = useCallback((layerId: string, keyframes: TransformKeyframe[]) => {
    if (!discarding) setHistory((history) => commitGestureTransaction(history, [{ type: 'set-layer-keyframes', id: layerId, keyframes }]));
  }, [discarding, setHistory]);

  const undo = useCallback(() => {
    if (discarding) return;
    tap();
    setHistory(undoProjectHistory);
  }, [discarding, setHistory]);
  const redo = useCallback(() => {
    if (discarding) return;
    tap();
    setHistory(redoProjectHistory);
  }, [discarding, setHistory]);
  const moveLayer = useCallback((id: string, toIndex: number) => applyAction({ type: 'move-layer', id, toIndex }), [applyAction]);
  const duplicateLayer = useCallback((id: string) => {
    if (!project) return;
    const prefix = `studio-${item?.id ?? 'session'}`;
    const newId = nextDuplicateLayerId(prefix, project.layers.map((layer) => layer.id));
    applyAction({ type: 'duplicate-layer', id, newId });
    setSelectedLayerId(newId);
  }, [applyAction, item?.id, project]);
  const deleteLayer = useCallback((id: string) => {
    applyAction({ type: 'remove-layer', id });
    setSelectedLayerId((current) => current === id ? null : current);
    warn();
  }, [applyAction]);

  const cancel = useCallback(() => {
    if (discarding) return;
    if (state.kind !== 'ready') {
      closedRef.current = true;
      const controller = sourceControllerRef.current;
      if (controller) {
        requestSourceSessionClose(
          controller,
          onClose,
          (error) => {
            if (!closedRef.current) setInlineError(`Could not close edit session: ${String(error)}`);
          }
        );
      } else {
        onClose();
      }
      return;
    }
    void (async () => {
      try {
        await closeSessionAssets();
        onClose();
      } catch (error) {
        setInlineError(`Could not close edit session: ${String(error)}`);
      }
    })();
  }, [closeSessionAssets, discarding, onClose, state.kind]);

  const discard = useCallback(() => {
    if (!ready || discarding) return;
    Alert.alert('Discard draft and close?', 'This removes the saved edit draft for this source. The original file is not changed.', [
      { text: 'Keep editing', style: 'cancel' },
      {
        text: 'Discard',
        style: 'destructive',
        onPress: () => {
          void (async () => {
            try {
              setDiscarding(true);
              await autosaveRef.current?.discard();
              await closeSessionAssets();
              onClose();
            } catch (error) {
              if (closedRef.current) {
                try {
                  await closeSessionAssets();
                } catch {
                  // The studio is already closed; contain cleanup failures without
                  // posting state updates into an unmounted tree.
                }
                return;
              }
              setInlineError(`Could not discard draft: ${String(error)}`);
            } finally {
              if (!closedRef.current) setDiscarding(false);
            }
          })();
        },
      },
    ]);
  }, [closeSessionAssets, discarding, onClose, ready]);

  const exportProject = useCallback(() => {
    if (!project) return;
    setInlineError('');
    if (!onExport) {
      setInlineError('Structured export is unavailable in this branch. No file was rendered.');
      return;
    }
    Promise.resolve(onExport(project)).catch((error) => {
      setInlineError(String(error));
    });
  }, [onExport, project]);

  const status = project
    ? `${project.source.kind.toUpperCase()} · ${project.source.width}×${project.source.height}${project.source.durationUs ? ` · ${(project.source.durationUs / 1_000_000).toFixed(1)}s` : ''}`
    : '';
  const headerStatus =
    state.kind === 'ready'
      ? status
      : state.kind === 'error'
        ? 'Source error'
        : state.kind === 'loading' || state.kind === 'prompting'
          ? state.message
          : 'Closed';
  const centerMessage =
    state.kind === 'error'
      ? state.message
      : state.kind === 'loading' || state.kind === 'prompting'
        ? state.message
        : 'Closed';
  const canUndo = !!ready && ready.history.past.length > 0;
  const canRedo = !!ready && ready.history.future.length > 0;
  const disabled = !ready || !!exportBusy || discarding;

  return (
    <Modal visible={visible} animationType="slide" statusBarTranslucent onRequestClose={cancel}>
      <KeyboardAvoidingView style={styles.root} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <View style={[styles.topBar, { paddingTop: insets.top + space.sm }]}> 
          <HeaderButton label="Cancel" hint="Close and keep a recoverable draft" onPress={cancel} disabled={discarding} />
          <View style={styles.titleBlock}>
            <Text style={styles.title} numberOfLines={1}>{item?.name ?? 'Meme remix'}</Text>
            <Text style={styles.status} numberOfLines={1}>{headerStatus}</Text>
          </View>
          <HeaderButton label="Before" hint="Hold to hide all edit layers without resetting video playback" disabled={!ready} onPressIn={() => setBefore(true)} onPressOut={() => setBefore(false)} />
          <HeaderButton label="Undo" hint="Undo the last edit transaction" onPress={undo} disabled={!canUndo || disabled} />
          <HeaderButton label="Redo" hint="Redo the next edit transaction" onPress={redo} disabled={!canRedo || disabled} />
          <HeaderButton label={onExport ? 'Export' : 'Export unavailable'} hint="Hand the structured project to the export pipeline" onPress={exportProject} disabled={!ready || !!exportBusy} primary={!!onExport} />
        </View>

        {state.kind === 'ready' && project ? (
          <View style={[styles.workspace, compact && styles.workspaceCompact]}>
            <View style={styles.previewPane}>
              <MemeEditCanvas
                project={project}
                selectedLayerId={selectedLayerId}
                before={before}
                onSelectLayer={setSelectedLayerId}
                onCommitLayerKeyframes={commitLayerKeyframes}
                disabled={disabled}
              />
              <View style={styles.readout} pointerEvents="none">
                <Text style={styles.readoutText}>{selectedLayerSummary(project, selectedLayerId)}</Text>
              </View>
            </View>
            <View style={[styles.sidePane, compact && styles.sidePaneCompact]}>
              <View style={styles.sideHead}>
                <Text style={styles.sideTitle}>{activeTool === 'layers' ? 'Layer tray' : 'Transform'}</Text>
                <Text style={styles.sideMeta}>{project.layers.length} layer{project.layers.length === 1 ? '' : 's'}</Text>
              </View>
              {activeTool === 'layers' ? (
                <MemeLayerList
                  project={project}
                  selectedLayerId={selectedLayerId}
                  onSelectLayer={setSelectedLayerId}
                  onMoveLayer={moveLayer}
                  onDuplicateLayer={duplicateLayer}
                  onDeleteLayer={deleteLayer}
                  disabled={disabled}
                />
              ) : (
                <ScrollView contentContainerStyle={styles.transformPanel}>
                  <Text style={styles.transformTitle}>Direct transform</Text>
                  <Text style={styles.transformCopy}>Drag the selected layer on the media. Use the round handle to rotate and the corner handle to resize. Letterbox space is inert.</Text>
                  <Text style={styles.transformMetric}>{selectedLayerSummary(project, selectedLayerId)}</Text>
                  <HeaderButton label={discarding ? 'Discarding…' : 'Discard draft'} hint="Delete this source's saved edit draft" onPress={discard} disabled={discarding} danger />
                </ScrollView>
              )}
            </View>
          </View>
        ) : (
          <View style={styles.centerState}>
            {state.kind === 'loading' || state.kind === 'prompting' ? <ActivityIndicator color={colors.volt} /> : null}
            <Text style={styles.centerTitle}>{state.kind === 'error' ? 'Could not open studio' : 'Opening studio'}</Text>
            <Text style={styles.centerCopy}>{centerMessage}</Text>
            {state.kind === 'error' && (
              <HeaderButton label="Retry" hint="Try preparing this source again" onPress={() => setRetryNonce((value) => value + 1)} primary />
            )}
          </View>
        )}

        {!!(inlineError || exportError) && (
          <View style={[styles.errorBar, { bottom: insets.bottom + 74 }]} accessibilityRole="alert">
            <Text style={styles.errorText}>{inlineError || exportError}</Text>
          </View>
        )}

        <MemeEditToolRail activeTool={activeTool} onSelectTool={setActiveTool} disabled={state.kind !== 'ready' || discarding} />
        <View style={{ height: Math.max(insets.bottom, space.sm) }} />
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  topBar: {
    minHeight: 74,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    paddingHorizontal: space.md,
    paddingBottom: space.sm,
    backgroundColor: colors.surface,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  titleBlock: { flex: 1, minWidth: 0, gap: 2, paddingHorizontal: space.xs },
  title: { ...type.title, color: colors.text },
  status: { ...type.caption, color: colors.muted, fontVariant: ['tabular-nums'] },
  headerButton: {
    minHeight: 44,
    minWidth: 58,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: space.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface2,
  },
  headerPrimary: { backgroundColor: colors.volt, borderColor: colors.volt },
  headerDanger: { backgroundColor: colors.dangerDim, borderColor: colors.danger },
  headerButtonText: { ...type.caption, color: colors.textDim, fontWeight: '800' },
  headerPrimaryText: { color: colors.onVolt },
  headerDangerText: { color: colors.danger },
  workspace: { flex: 1, flexDirection: 'row', gap: space.sm, padding: space.sm },
  workspaceCompact: { flexDirection: 'column' },
  previewPane: {
    flex: 1,
    overflow: 'hidden',
    borderRadius: radius.xl,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.bg,
  },
  sidePane: {
    width: 320,
    overflow: 'hidden',
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
  },
  sidePaneCompact: { width: '100%', maxHeight: 240 },
  sideHead: {
    minHeight: 52,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: space.md,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  sideTitle: { ...type.title, color: colors.text },
  sideMeta: { ...type.caption, color: colors.muted, fontVariant: ['tabular-nums'] },
  readout: {
    position: 'absolute',
    left: space.md,
    bottom: space.md,
    paddingHorizontal: space.md,
    paddingVertical: space.sm,
    borderRadius: radius.pill,
    backgroundColor: colors.overlay,
    borderWidth: 1,
    borderColor: colors.borderLight,
  },
  readoutText: { ...type.caption, color: colors.textDim, fontVariant: ['tabular-nums'] },
  transformPanel: { gap: space.md, padding: space.md },
  transformTitle: { ...type.title, color: colors.text },
  transformCopy: { ...type.body, color: colors.textDim, lineHeight: 21 },
  transformMetric: {
    ...type.label,
    color: colors.volt,
    fontVariant: ['tabular-nums'],
    padding: space.md,
    borderRadius: radius.md,
    backgroundColor: colors.voltDim,
    borderWidth: 1,
    borderColor: colors.volt,
  },
  centerState: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: space.md, padding: space.xl },
  centerTitle: { ...type.title, color: colors.text, textAlign: 'center' },
  centerCopy: { ...type.body, color: colors.textDim, textAlign: 'center', lineHeight: 22 },
  errorBar: {
    position: 'absolute',
    left: space.md,
    right: space.md,
    padding: space.md,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.danger,
    backgroundColor: colors.dangerDim,
  },
  errorText: { ...type.caption, color: colors.danger, fontWeight: '700' },
});
