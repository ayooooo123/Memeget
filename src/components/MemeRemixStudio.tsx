import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  AppState,
  KeyboardAvoidingView,
  Keyboard,
  Modal,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
  type AccessibilityActionEvent,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  MemeEditAutosaveController,
  MemeEditDraftStore,
  MemeEditSourceSessionController,
  flushAutosaveBeforeSourceRelease,
  requestSourceSessionClose,
  shouldStartDefaultAfterDraftRestore,
  createExpoMemeEditDraftIo,
  createExpoMemeEditSourcePreparationIo,
  type MemeEditDraftIdentity,
} from '../memeEditDraftStore';
import {
  remapImageProject,
  remapNormalizedRect,
  type TextRegionCandidate,
} from '../memeImageEditCore';
import {
  applyProjectAction,
  beginProjectTransaction,
  commitProjectTransaction,
  PROJECT_LIMITS,
  createProjectHistory,
  type BaseTransform,
  type MemeEditLayer,
  type MemeEditProject,
  type MemeEditProjectAction,
  type ProjectHistory,
  type TransformKeyframe,
  type TimeRangeUs,
} from '../memeEditProjectCore';
import { beforeAfterAccessibilityNextState, beforeAfterPointerNextState, canDuplicateLayer, commitGestureTransaction, memeRemixExportControlState, memeRemixHeaderLayout, studioSidePaneHeight, nextDuplicateLayerId, projectHistoryCommandAvailability, runProjectHistoryCommand, selectedLayerIdAfterDelete } from '../memeEditCanvasCore';
import { videoAudioActions, type VideoAudioChange } from '../memeVideoAudioCore';
import {
  createSeekThrottle,
  flushSeekThrottle,
  nextSeekThrottleState,
  reconcileLayersForRetainedRanges,
  resolvePlayheadUs,
  seekTargetForLayerSelection,
} from '../memeTimelineCore';
import { tap, warn } from '../haptics';
import { colors, radius, space, type } from '../theme';
import type { MemeRecord } from '../types';
import { PressableScale } from './ui';
import { MemeEditCanvas, type VideoSeekRequest } from './MemeEditCanvas';
import { MemeEditToolRail, type MemeEditTool } from './MemeEditToolRail';
import { MemeFrameStrip } from './MemeFrameStrip';
import { MemeLayerList } from './MemeLayerList';
import { MemeTextInspector } from './MemeTextInspector';
import { MemeSubjectTool } from './MemeSubjectTool';
import { MemeTextReplaceTool } from './MemeTextReplaceTool';
import { MemeTimeline } from './MemeTimeline';
import { MemeTransformInspector } from './MemeTransformInspector';
import { MemeVideoAudioTool } from './MemeVideoAudioTool';
import { MemeVideoMotionTool } from './MemeVideoMotionTool';

type StudioItem = Pick<MemeRecord, 'id' | 'kind' | 'name' | 'uri' | 'modifiedAt'>;

// Floor between scrub seeks. ExoPlayer coalesces badly under a finger moving at
// 60Hz; ~8 seeks a second keeps the preview responsive without queueing them.
const SCRUB_SEEK_INTERVAL_MS = 120;

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
  glyph,
  showLabel = true,
  hint,
  onPress,
  disabled,
  primary,
  danger,
  selected,
  onPressIn,
  onPressOut,
  accessibilityActions,
  onAccessibilityAction,
}: {
  label: string;
  /** Shown instead of the word when space is tight. */
  glyph?: string;
  showLabel?: boolean;
  hint: string;
  onPress?: () => void;
  disabled?: boolean;
  primary?: boolean;
  danger?: boolean;
  selected?: boolean;
  onPressIn?: () => void;
  onPressOut?: () => void;
  accessibilityActions?: { name: string; label?: string }[];
  onAccessibilityAction?: (event: AccessibilityActionEvent) => void;
}) {
  // Only ever collapse to a glyph, never to a truncated word: "Out" for Export
  // is not shorter, it is unreadable. accessibilityLabel keeps the real word in
  // every case, so a screen reader is unaffected by the visual abbreviation.
  const iconOnly = !showLabel && !!glyph;
  return (
    <PressableScale
      scaleTo={0.94}
      disabled={disabled}
      onPress={onPress}
      onPressIn={onPressIn}
      onPressOut={onPressOut}
      style={[
        styles.headerButton,
        iconOnly && styles.headerButtonIcon,
        primary && styles.headerPrimary,
        danger && styles.headerDanger,
        selected && styles.headerSelected,
      ]}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: !!disabled, selected: !!selected }}
      accessibilityActions={accessibilityActions}
      onAccessibilityAction={onAccessibilityAction}
    >
      <Text
        style={[
          styles.headerButtonText,
          iconOnly && styles.headerButtonGlyph,
          primary && styles.headerPrimaryText,
          danger && styles.headerDangerText,
          selected && styles.headerSelectedText,
        ]}
        numberOfLines={1}
      >
        {iconOnly ? glyph : label}
      </Text>
    </PressableScale>
  );
}

function useKeyboardHeight(): number {
  const [keyboardHeight, setKeyboardHeight] = useState(0);
  useEffect(() => {
    const showEvent = Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow';
    const hideEvent = Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide';
    const show = Keyboard.addListener(showEvent, (event) => setKeyboardHeight(event.endCoordinates.height));
    const hide = Keyboard.addListener(hideEvent, () => setKeyboardHeight(0));
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);
  return keyboardHeight;
}

export function MemeRemixStudio({
  item,
  visible,
  exportBusy,
  onClose,
  onExport,
}: {
  item: StudioItem | null;
  visible: boolean;
  exportBusy?: boolean;
  onClose: () => void;
  onExport?: (project: MemeEditProject) => Promise<void> | void;
}) {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const compact = width < 430 || height < 720;
  const keyboardHeight = useKeyboardHeight();
  const draftStore = useMemo(() => new MemeEditDraftStore(createExpoMemeEditDraftIo()), []);
  const sourceIo = useMemo(() => createExpoMemeEditSourcePreparationIo(), []);
  const [state, setState] = useState<LoadState>({ kind: 'closed' });
  const [retryNonce, setRetryNonce] = useState(0);
  const [activeTool, setActiveTool] = useState<MemeEditTool>('layers');
  // Collapsing the tool panel is the one lever that meaningfully grows the
  // canvas on a phone, so it is a first-class piece of editor state rather than
  // a transient animation flag.
  const [panelCollapsed, setPanelCollapsed] = useState(false);
  const [cleanupPending, setCleanupPending] = useState(false);
  const [selectedLayerId, setSelectedLayerId] = useState<string | null>(null);
  const [before, setBefore] = useState(false);
  const [inlineError, setInlineError] = useState('');
  const [externalTextRevision, setExternalTextRevision] = useState(0);
  const [discarding, setDiscarding] = useState(false);
  const [previewBase, setPreviewBase] = useState<BaseTransform | null>(null);
  const [textRegions, setTextRegions] = useState<TextRegionCandidate[]>([]);
  const [selectedTextRegion, setSelectedTextRegion] = useState<TextRegionCandidate | null>(null);
  const [manualTextRegionMode, setManualTextRegionMode] = useState(false);
  const [previewAudio, setPreviewAudio] = useState<{ muted: boolean; volume: number } | null>(null);
  const [playbackUs, setPlaybackUs] = useState(0);
  const [scrubUs, setScrubUs] = useState<number | null>(null);
  const [seekRequest, setSeekRequest] = useState<VideoSeekRequest | null>(null);
  // Container frame rate from the media probe. Only the frame strip needs it,
  // and only to size its cells; null makes it assume 30fps.
  const [sourceFrameRate, setSourceFrameRate] = useState<number | null>(null);
  const autosaveRef = useRef<MemeEditAutosaveController | null>(null);
  const sourceControllerRef = useRef<MemeEditSourceSessionController | null>(null);
  const closedRef = useRef(true);
  const pendingTextFlushRef = useRef<(() => MemeEditProject | null) | null>(null);
  const pendingTextProjectRef = useRef<MemeEditProject | null>(null);
  const seekThrottleRef = useRef(createSeekThrottle());
  const seekNonceRef = useRef(0);

  const ready = state.kind === 'ready' ? state : null;
  const project = ready?.history.present ?? null;
  // The playhead follows the finger at full gesture rate; only the seeks that
  // reach ExoPlayer are throttled.
  const playheadUs = resolvePlayheadUs(scrubUs, playbackUs);

  const registerPendingTextFlush = useCallback((flush: () => MemeEditProject | null) => {
    pendingTextFlushRef.current = flush;
    return () => {
      if (pendingTextFlushRef.current === flush) pendingTextFlushRef.current = null;
    };
  }, []);
  const flushPendingTextSnapshot = useCallback(() => {
    const snapshot = pendingTextFlushRef.current?.() ?? null;
    if (snapshot) pendingTextProjectRef.current = snapshot;
    return snapshot;
  }, []);
  const flushPendingTextForHistory = useCallback(() => {
    pendingTextFlushRef.current?.();
  }, []);
  const announceExternalTextRevision = useCallback(() => {
    setExternalTextRevision((revision) => revision + 1);
  }, []);
  const flushExactPendingTextAutosave = useCallback(async () => {
    const snapshot = pendingTextProjectRef.current;
    const autosave = autosaveRef.current;
    if (!snapshot || !autosave) return;
    autosave.schedule(snapshot);
    await autosave.flush();
    pendingTextProjectRef.current = null;
  }, []);
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
      setCleanupPending(false);
      flushPendingTextSnapshot();
      setInlineError('');
      setDiscarding(false);
      setSelectedLayerId(null);
      setPreviewBase(null);
      setTextRegions([]);
      setSelectedTextRegion(null);
      setManualTextRegionMode(false);
      setActiveTool('layers');
      setPlaybackUs(0);
      setScrubUs(null);
      setSeekRequest(null);
      setSourceFrameRate(null);
      seekThrottleRef.current = createSeekThrottle();
      setState({ kind: 'closed' });
      void (async () => {
        try {
          await flushExactPendingTextAutosave();
          await closeSessionAssets();
        } catch (error) {
          if (!closedRef.current) setInlineError(`Could not close edit session: ${String(error)}`);
        }
      })();
      return;
    }

    closedRef.current = false;
    let cancelled = false;
    setState({ kind: 'loading', message: 'Preparing source…' });
    setPreviewBase(null);
    setTextRegions([]);
    setSelectedTextRegion(null);
    setManualTextRegionMode(false);
    setActiveTool('layers');
    setPlaybackUs(0);
    setScrubUs(null);
    setSeekRequest(null);
    setSourceFrameRate(null);
    seekThrottleRef.current = createSeekThrottle();
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
        if (!cancelled && !closedRef.current) setSourceFrameRate(prepared.probe?.frameRate ?? null);
        const restored = await draftStore.restore(prepared.identity);
        if (cancelled || closedRef.current) return;
        if (restored.status === 'restored') {
          chooseDraft(prepared.identity, prepared.project, restored.project, restored.savedAtMs, prepared.materializedSourceUri);
        } else if (shouldStartDefaultAfterDraftRestore(restored)) {
          beginReady(prepared.identity, prepared.project);
        } else {
          safeSetState({ kind: 'error', message: 'Could not read the saved edit draft. Retry to restore it, or discard it from storage before starting over.' });
        }
      })
      .catch((error) => safeSetState({ kind: 'error', message: `Could not prepare source: ${String(error)}` }));

    return () => {
      cancelled = true;
      closedRef.current = true;
      void closeSessionAssets().catch(() => {});
    };
  }, [closeSessionAssets, draftStore, flushExactPendingTextAutosave, flushPendingTextSnapshot, item, retryNonce, sourceIo, visible]);

  useEffect(() => {
    if (!project || !autosaveRef.current) return;
    autosaveRef.current.schedule(project);
  }, [project]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (next) => {
      if (next === 'background' || next === 'inactive') {
        flushPendingTextSnapshot();
        void flushExactPendingTextAutosave().then(() => autosaveRef.current?.flush()).catch((error) => {
          if (!closedRef.current) setInlineError(`Draft flush failed: ${String(error)}`);
        });
      }
    });
    return () => sub.remove();
  }, [flushExactPendingTextAutosave, flushPendingTextSnapshot]);

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
  // One motion command — a handle drag, a keyframe, an easing switch — is one
  // history entry. A refused command arrives as an empty list and commits
  // nothing, so a rejected press leaves no undo step behind.
  const commitMotionActions = useCallback((actions: MemeEditProjectAction[]) => {
    if (!discarding) setHistory((history) => commitGestureTransaction(history, actions));
  }, [discarding, setHistory]);
  const requestSeek = useCallback((timeUs: number) => {
    seekNonceRef.current += 1;
    setSeekRequest({ timeUs, nonce: seekNonceRef.current });
  }, []);
  // Parks the preview on a frame: the playhead, the decoder, and the scrub
  // throttle all land on the same instant.
  const parkPlayheadAt = useCallback((timeUs: number) => {
    setScrubUs(null);
    setPlaybackUs(timeUs);
    seekThrottleRef.current = createSeekThrottle();
    requestSeek(timeUs);
  }, [requestSeek]);
  // Called at full gesture rate by the timeline. The throttle decides how much
  // of that the decoder actually sees.
  const scrubPlayhead = useCallback((sourceTimeUs: number) => {
    setScrubUs(sourceTimeUs);
    const step = nextSeekThrottleState(seekThrottleRef.current, sourceTimeUs, Date.now(), SCRUB_SEEK_INTERVAL_MS);
    seekThrottleRef.current = step.state;
    if (step.seekUs !== null) requestSeek(step.seekUs);
  }, [requestSeek]);
  const endScrub = useCallback((sourceTimeUs: number) => {
    const step = flushSeekThrottle(seekThrottleRef.current, Date.now());
    seekThrottleRef.current = step.state;
    if (step.seekUs !== null) requestSeek(step.seekUs);
    setPlaybackUs(sourceTimeUs);
    setScrubUs(null);
  }, [requestSeek]);
  // Selecting a layer parks the playhead where that layer is actually visible,
  // so direct manipulation never happens against a frame the layer is absent from.
  const selectLayer = useCallback((id: string | null) => {
    setSelectedLayerId(id);
    if (!project || project.source.kind !== 'video' || id === null) return;
    const target = seekTargetForLayerSelection(project.layers.find((layer) => layer.id === id) ?? null, playheadUs);
    if (target === null) return;
    parkPlayheadAt(target);
  }, [parkPlayheadAt, playheadUs, project]);
  // One trim gesture, one undo entry: the retained-range change and every layer
  // it invalidates go into a single transaction.
  const commitRetainedRanges = useCallback((retainedRanges: TimeRangeUs[]) => {
    if (!project || discarding) return;
    const plan = reconcileLayersForRetainedRanges(project, retainedRanges);
    if (plan.actions.length === 0) return;
    setHistory((history) => commitGestureTransaction(history, plan.actions));
    setSelectedLayerId((current) => (current !== null && plan.removedLayerIds.includes(current) ? null : current));
    tap();
  }, [discarding, project, setHistory]);

  const previewImageBase = useCallback((base: BaseTransform) => {
    if (project?.source.kind === 'image' && !discarding) setPreviewBase(base);
  }, [discarding, project?.source.kind]);
  const commitImageBase = useCallback((base: BaseTransform) => {
    if (!project || project.source.kind !== 'image' || discarding) return;
    const remapped = remapImageProject(project, base);
    setHistory((history) => commitGestureTransaction(history, [{
      type: 'set-image-geometry',
      base: remapped.base,
      layers: remapped.layers,
      maskTracks: remapped.maskTracks,
    }]));
    setTextRegions((current) => current.flatMap((region) => {
      const rect = remapNormalizedRect(region.rect, project.base, base);
      return rect ? [{ ...region, rect }] : [];
    }));
    setSelectedTextRegion((current) => {
      if (!current) return null;
      const rect = remapNormalizedRect(current.rect, project.base, base);
      return rect ? { ...current, rect } : null;
    });
    setPreviewBase(null);
  }, [discarding, project, setHistory]);
  const addRegionLayers = useCallback((layers: MemeEditLayer[]) => {
    if (discarding || layers.length === 0) return;
    setHistory((history) => commitGestureTransaction(history, [{ type: 'add-layers', layers }]));
  }, [discarding, setHistory]);

  // One completed gesture, one undo entry. The in-flight value never reaches
  // history; it only drives the preview player so what you hear is honest.
  const commitVideoAudio = useCallback((change: VideoAudioChange) => {
    if (discarding) return;
    setHistory((history) => commitGestureTransaction(history, videoAudioActions(history.present, change)));
  }, [discarding, setHistory]);

  const beginTextTransaction = useCallback(() => {
    if (!discarding) setHistory(beginProjectTransaction);
  }, [discarding, setHistory]);
  const commitTextTransaction = useCallback(() => {
    if (!discarding) setHistory(commitProjectTransaction);
  }, [discarding, setHistory]);
  const clearTransientImageTools = useCallback(() => {
    setPreviewBase(null);
    setTextRegions([]);
    setSelectedTextRegion(null);
    setManualTextRegionMode(false);
  }, []);
  const undo = useCallback(() => {
    if (discarding) return;
    tap();
    runProjectHistoryCommand('undo', flushPendingTextForHistory, setHistory, announceExternalTextRevision);
    clearTransientImageTools();
  }, [announceExternalTextRevision, clearTransientImageTools, discarding, flushPendingTextForHistory, setHistory]);
  const redo = useCallback(() => {
    if (discarding) return;
    tap();
    runProjectHistoryCommand('redo', flushPendingTextForHistory, setHistory, announceExternalTextRevision);
    clearTransientImageTools();
  }, [announceExternalTextRevision, clearTransientImageTools, discarding, flushPendingTextForHistory, setHistory]);
  const moveLayer = useCallback((id: string, toIndex: number) => applyAction({ type: 'move-layer', id, toIndex }), [applyAction]);
  const duplicateLayer = useCallback((id: string) => {
    if (!project) return;
    if (!canDuplicateLayer(project.layers.length, PROJECT_LIMITS.maxLayers)) {
      setInlineError(`Project already has the ${PROJECT_LIMITS.maxLayers} layer maximum.`);
      return;
    }
    const prefix = `studio-${item?.id ?? 'session'}`;
    const newId = nextDuplicateLayerId(prefix, project.layers.map((layer) => layer.id));
    applyAction({ type: 'duplicate-layer', id, newId });
    setSelectedLayerId(newId);
  }, [applyAction, item?.id, project]);
  const deleteLayer = useCallback((id: string) => {
    const orderedIds = project?.layers.map((layer) => layer.id) ?? [];
    applyAction({ type: 'remove-layer', id });
    setSelectedLayerId((current) => selectedLayerIdAfterDelete(orderedIds, id, current));
    warn();
  }, [applyAction, project?.layers]);

  const cancel = useCallback(() => {
    flushPendingTextSnapshot();
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
        await flushExactPendingTextAutosave();
        await closeSessionAssets();
        onClose();
      } catch (error) {
        setInlineError(`Could not close edit session: ${String(error)}`);
      }
    })();
  }, [closeSessionAssets, discarding, flushExactPendingTextAutosave, flushPendingTextSnapshot, onClose, state.kind]);

  const discard = useCallback(() => {
    Alert.alert('Discard draft and close?', 'This removes the saved edit draft for this source. The original file is not changed.', [
      { text: 'Keep editing', style: 'cancel' },
      {
        text: 'Discard',
        style: 'destructive',
        onPress: () => {
          void (async () => {
            let draftDiscarded = false;
            let keepDiscarding = false;
            try {
              setDiscarding(true);
              await autosaveRef.current?.discard();
              draftDiscarded = true;
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
              if (draftDiscarded) {
                keepDiscarding = true;
                setCleanupPending(true);
                setDiscarding(true);
                setInlineError(`Draft discarded. Retry cleanup to close the editor: ${String(error)}`);
              } else {
                setInlineError(`Could not discard draft: ${String(error)}`);
              }
            } finally {
              if (!closedRef.current && !keepDiscarding) setDiscarding(false);
            }
          })();
        },
      },
    ]);
  }, [closeSessionAssets, discarding, onClose, ready]);

  const retryCleanupClose = useCallback(() => {
    void (async () => {
      try {
        setDiscarding(true);
        await closeSessionAssets();
        setCleanupPending(false);
        onClose();
      } catch (error) {
        setInlineError(`Still could not close the editor: ${String(error)}`);
      }
    })();
  }, [closeSessionAssets, onClose]);

  const exportProject = useCallback(() => {
    if (!project || !onExport || !ready || exportBusy || discarding) return;
    const exactProject = flushPendingTextSnapshot() ?? project;
    if (exactProject !== project && autosaveRef.current) autosaveRef.current.schedule(exactProject);
    Promise.resolve(onExport(exactProject)).catch((error) => {
      setInlineError(String(error));
    });
  }, [discarding, exportBusy, flushPendingTextSnapshot, onExport, project, ready]);
  const headerLayout = memeRemixHeaderLayout(width);
  const sidePaneHeight = studioSidePaneHeight(height, panelCollapsed);
  const panelTitle =
    activeTool === 'layers'
      ? 'Layer tray'
      : activeTool === 'text'
        ? 'Text'
        : activeTool === 'transform'
          ? 'Image transform'
          : activeTool === 'timeline'
            ? 'Timeline'
            : activeTool === 'frames'
              ? 'Frames'
              : activeTool === 'motion'
                ? 'Motion'
                : activeTool === 'audio'
                  ? 'Audio & speed'
                  : activeTool === 'subject'
                    ? 'Cut out subject'
                    : 'Replace text';

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
  const historyCommands = ready ? projectHistoryCommandAvailability(ready.history) : { canUndo: false, canRedo: false };
  const { canUndo, canRedo } = historyCommands;
  const disabled = !ready || !!exportBusy || discarding;
  const showBefore = useCallback(() => setBefore((value) => beforeAfterPointerNextState(value, 'press-in')), []);
  const hideBefore = useCallback(() => setBefore((value) => beforeAfterPointerNextState(value, 'press-out')), []);
  const toggleBeforeForAccessibility = useCallback((event: AccessibilityActionEvent) => {
    if (event.nativeEvent.actionName === 'activate') {
      setBefore((value) => beforeAfterAccessibilityNextState(value, 'activate'));
    }
  }, []);
  const exportControl = memeRemixExportControlState(headerLayout, { ready: !!ready, exportBusy: !!exportBusy, discarding, hasExport: !!onExport });
  const canvasProject = useMemo(
    () => project && previewBase && project.source.kind === 'image'
      ? remapImageProject(project, previewBase)
      : project,
    [previewBase, project]
  );

  return (
    <Modal visible={visible} animationType="slide" statusBarTranslucent onRequestClose={cancel}>
      <KeyboardAvoidingView style={styles.root} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <View style={[styles.topBar, headerLayout.mode === 'compact-two-row' && styles.topBarCompact, { paddingTop: insets.top + space.sm }]}>
          {headerLayout.mode === 'compact-two-row' ? (
            <>
              <View style={styles.topBarRow}>
                <HeaderButton label="Cancel" hint="Close and keep a recoverable draft" onPress={cancel} disabled={discarding} />
                <View style={styles.titleBlock}>
                  <Text style={styles.title} numberOfLines={1}>{item?.name ?? 'Meme remix'}</Text>
                  <Text style={styles.status} numberOfLines={1}>{headerStatus}</Text>
                </View>
              </View>
              <View style={[styles.topBarRow, styles.commandRow]}>
                <HeaderButton label="Before" glyph="◑" showLabel={headerLayout.showCommandLabels} hint="Hold to hide all edit layers without resetting video playback. Screen reader activate toggles before and after." disabled={!ready} selected={before} onPressIn={showBefore} onPressOut={hideBefore} accessibilityActions={[{ name: 'activate', label: before ? 'Show edited layers' : 'Show original media' }]} onAccessibilityAction={toggleBeforeForAccessibility} />
                <HeaderButton label="Undo" glyph="↶" showLabel={headerLayout.showCommandLabels} hint="Undo the last edit transaction" onPress={undo} disabled={!canUndo || disabled} />
                <HeaderButton label="Redo" glyph="↷" showLabel={headerLayout.showCommandLabels} hint="Redo the next edit transaction" onPress={redo} disabled={!canRedo || disabled} />
                <View style={styles.commandSpacer} />
                <HeaderButton label={exportControl.label} hint="Render this project at full resolution and save it as a new meme" onPress={exportProject} disabled={exportControl.disabled} primary={!!onExport} />
              </View>
            </>
          ) : (
            <View style={styles.topBarRow}>
              <HeaderButton label="Cancel" hint="Close and keep a recoverable draft" onPress={cancel} disabled={discarding} />
              <View style={styles.titleBlock}>
                <Text style={styles.title} numberOfLines={1}>{item?.name ?? 'Meme remix'}</Text>
                <Text style={styles.status} numberOfLines={1}>{headerStatus}</Text>
              </View>
              <HeaderButton label="Before" hint="Hold to hide all edit layers without resetting video playback. Screen reader activate toggles before and after." disabled={!ready} selected={before} onPressIn={showBefore} onPressOut={hideBefore} accessibilityActions={[{ name: 'activate', label: before ? 'Show edited layers' : 'Show original media' }]} onAccessibilityAction={toggleBeforeForAccessibility} />
              <HeaderButton label="Undo" hint="Undo the last edit transaction" onPress={undo} disabled={!canUndo || disabled} />
              <HeaderButton label="Redo" hint="Redo the next edit transaction" onPress={redo} disabled={!canRedo || disabled} />
              <HeaderButton label={exportControl.label} hint="Render this project at full resolution and save it as a new meme" onPress={exportProject} disabled={exportControl.disabled} primary={!!onExport} />
            </View>
          )}
        </View>

        {state.kind === 'ready' && project ? (
          <View style={[styles.workspace, compact && styles.workspaceCompact]}>
            <View style={styles.previewPane}>
              <MemeEditCanvas
                project={canvasProject ?? project}
                selectedLayerId={selectedLayerId}
                before={before}
                onSelectLayer={selectLayer}
                onCommitLayerKeyframes={commitLayerKeyframes}
                disabled={disabled}
                textRegions={activeTool === 'replace-text' ? textRegions : []}
                selectedTextRegion={activeTool === 'replace-text' ? selectedTextRegion : null}
                manualTextRegionMode={activeTool === 'replace-text' && manualTextRegionMode}
                onSelectTextRegion={setSelectedTextRegion}
                onChangeSelectedTextRegion={setSelectedTextRegion}
                onManualTextRegionComplete={() => setManualTextRegionMode(false)}
                previewAudio={previewAudio}
                seekRequest={seekRequest}
                // Deliberately NOT the frames tool: the preview loops at 30fps and a
                // frame decode costs ~1-3s, so feeding it live playback time makes the
                // strip chase a playhead it can never catch. It parks on the frame you
                // chose instead.
                onPlaybackTimeUs={activeTool === 'timeline' || activeTool === 'motion' ? setPlaybackUs : undefined}
              />
              <View style={styles.readout} pointerEvents="none">
                <Text style={styles.readoutText}>{selectedLayerSummary(project, selectedLayerId)}</Text>
              </View>
            </View>
            <View style={[styles.sidePane, compact && { height: sidePaneHeight, width: '100%' }]}>
              <PressableScale
                scaleTo={0.98}
                style={styles.sideHead}
                onPress={compact ? () => setPanelCollapsed((v) => !v) : undefined}
                disabled={!compact}
                accessibilityRole="button"
                accessibilityLabel={panelTitle}
                accessibilityHint={panelCollapsed ? 'Expand the tool panel' : 'Collapse the tool panel to give the canvas more room'}
                accessibilityState={{ expanded: !panelCollapsed }}
              >
                <Text style={styles.sideTitle}>{panelTitle}</Text>
                <View style={styles.sideHeadRight}>
                  <Text style={styles.sideMeta}>{project.layers.length} layer{project.layers.length === 1 ? '' : 's'}</Text>
                  {compact ? <Text style={styles.sideChevron}>{panelCollapsed ? '▴' : '▾'}</Text> : null}
                </View>
              </PressableScale>
              {activeTool === 'layers' ? (
                <MemeLayerList
                  project={project}
                  selectedLayerId={selectedLayerId}
                  onSelectLayer={selectLayer}
                  onMoveLayer={moveLayer}
                  onDuplicateLayer={duplicateLayer}
                  onDeleteLayer={deleteLayer}
                  disabled={disabled}
                />
              ) : activeTool === 'text' ? (
                <MemeTextInspector
                  project={project}
                  selectedLayerId={selectedLayerId}
                  externalTextRevision={externalTextRevision}
                  idPrefix={`studio-${item?.id ?? 'session'}-text`}
                  disabled={disabled}
                  bottomInset={Platform.OS === 'android' ? keyboardHeight : 0}
                  onApplyAction={applyAction}
                  onSelectLayer={selectLayer}
                  onDuplicateLayer={duplicateLayer}
                  onDeleteLayer={deleteLayer}
                  onMoveLayer={moveLayer}
                  onRegisterPendingTextFlush={registerPendingTextFlush}
                  onBeginTextTransaction={beginTextTransaction}
                  onCommitTextTransaction={commitTextTransaction}
                />
              ) : activeTool === 'transform' ? (
                // The panel has a bounded height, so anything taller than it
                // must scroll or it is simply invisible. This one silently
                // clipped its crop-ratio chips and the discard button.
                <ScrollView
                  style={styles.transformPanel}
                  contentContainerStyle={styles.transformPanelContent}
                  keyboardShouldPersistTaps="handled"
                >
                  <MemeTransformInspector
                    project={project}
                    base={previewBase ?? project.base}
                    disabled={disabled}
                    onPreviewBase={previewImageBase}
                    onCommitBase={commitImageBase}
                  />
                  <HeaderButton label={discarding ? 'Discarding…' : 'Discard draft'} hint="Delete this source's saved edit draft" onPress={discard} disabled={discarding} danger />
                </ScrollView>
              ) : activeTool === 'timeline' ? (
                <MemeTimeline
                  project={project}
                  playheadUs={playheadUs}
                  selectedLayerId={selectedLayerId}
                  disabled={disabled}
                  onScrubPlayhead={scrubPlayhead}
                  onScrubEnd={endScrub}
                  onCommitRetainedRanges={commitRetainedRanges}
                  onSelectLayer={selectLayer}
                />
              ) : activeTool === 'frames' ? (
                <MemeFrameStrip
                  project={project}
                  frameRate={sourceFrameRate}
                  playheadUs={playheadUs}
                  disabled={disabled}
                  onSeekToFrame={parkPlayheadAt}
                />
              ) : activeTool === 'motion' ? (
                <MemeVideoMotionTool
                  project={project}
                  selectedLayerId={selectedLayerId}
                  playheadUs={playheadUs}
                  disabled={disabled}
                  onCommitActions={commitMotionActions}
                  onSeek={parkPlayheadAt}
                  onSelectLayer={selectLayer}
                />
              ) : activeTool === 'audio' ? (
                <MemeVideoAudioTool
                  project={project}
                  disabled={disabled}
                  onChange={commitVideoAudio}
                  onPreviewAudio={setPreviewAudio}
                />
              ) : activeTool === 'subject' ? (
                <MemeSubjectTool
                  project={project}
                  sourceUri={project.transient.materializedSourceUri ?? project.source.uri}
                  idPrefix={`subject-${project.source.kind}`}
                  disabled={disabled}
                  onApplyActions={commitMotionActions}
                  onSelectLayer={selectLayer}
                />
              ) : (
                <MemeTextReplaceTool
                  project={project}
                  sourceUri={project.transient.materializedSourceUri ?? project.source.uri}
                  idPrefix={`studio-${item?.id ?? 'session'}-replace`}
                  regions={textRegions}
                  selectedRegion={selectedTextRegion}
                  manualMode={manualTextRegionMode}
                  disabled={disabled}
                  onRegionsChange={setTextRegions}
                  onSelectedRegionChange={setSelectedTextRegion}
                  onManualModeChange={(enabled) => {
                    setManualTextRegionMode(enabled);
                    if (enabled) setSelectedTextRegion(null);
                  }}
                  onAddLayers={addRegionLayers}
                  onSelectLayer={selectLayer}
                />
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

        {!!inlineError && (
          <View style={[styles.errorBar, { bottom: insets.bottom + 74 }]} accessibilityRole="alert">
            <Text style={styles.errorText}>{inlineError}</Text>
            {cleanupPending && <HeaderButton label="Retry close" hint="Retry source cleanup and close the editor" onPress={retryCleanupClose} primary />}
          </View>
        )}

        <MemeEditToolRail activeTool={activeTool} onSelectTool={setActiveTool} sourceKind={project?.source.kind ?? 'image'} disabled={state.kind !== 'ready' || discarding} />
        <View style={{ height: Math.max(insets.bottom, space.sm) + (Platform.OS === 'android' ? keyboardHeight : 0) }} />
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  topBar: {
    minHeight: 74,
    gap: space.xs,
    paddingHorizontal: space.md,
    paddingBottom: space.sm,
    backgroundColor: colors.surface,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  topBarCompact: {
    minHeight: 118,
  },
  topBarRow: {
    minHeight: 44,
    width: '100%',
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
  },
  commandRow: { justifyContent: 'flex-start', gap: space.xs },
  // Pushes Export to the trailing edge and puts real distance between it and
  // Undo/Redo. It is the only destructive-ish, forward-moving action in the
  // row; sitting flush against Redo it gets hit by accident.
  commandSpacer: { flex: 1 },
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
  // Square when it is a glyph: a 58dp-wide pill around a single arrow reads as
  // an empty button.
  headerButtonIcon: { minWidth: 44, paddingHorizontal: 0 },
  headerPrimary: { backgroundColor: colors.volt, borderColor: colors.volt },
  headerDanger: { backgroundColor: colors.dangerDim, borderColor: colors.danger },
  headerSelected: { borderColor: colors.volt },
  headerButtonText: { ...type.caption, color: colors.textDim, fontWeight: '800' },
  // Glyphs need more size than words to read at the same weight.
  headerButtonGlyph: { fontSize: 18, lineHeight: 22, fontWeight: '600' },
  headerPrimaryText: { color: colors.onVolt },
  headerDangerText: { color: colors.danger },
  headerSelectedText: { color: colors.volt },
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
  sideHeadRight: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  // Points the way the panel will MOVE, not the way it currently is:
  // a collapsed panel offers to go up, an expanded one offers to go down.
  sideChevron: { ...type.caption, color: colors.muted, fontSize: 14 },
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
  transformPanel: { flex: 1 },
  transformPanelContent: { padding: space.md, gap: space.md },
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
