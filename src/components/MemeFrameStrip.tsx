import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
  type LayoutChangeEvent,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from 'react-native';
import { Image } from 'expo-image';

import { extractVideoFrame, extractVideoFramePlayer } from '../../modules/memeget-bg';
import { tap, warn } from '../haptics';
import type { MemeEditProject } from '../memeEditProjectCore';
import {
  FRAME_CELL_WIDTH_PX,
  FRAME_DECODE_BATCH,
  centerScrollXPx,
  createFrameCache,
  formatFrameTimeUs,
  frameStripCells,
  frameStripIntervalUs,
  frameStripReadout,
  frameStripStride,
  frameStripTickWidthPx,
  frameStripTicks,
  frameWindowAt,
  frameWindowTicks,
  pendingFrameRequests,
  sourceFrameIntervalUs,
  stepFrameUs,
  type FrameCell,
  type FrameTick,
} from '../memeFrameStripCore';
import {
  TIMELINE_MAX_ZOOM,
  TIMELINE_MIN_ZOOM,
  TimelineThumbnailCache,
  clampTimelineZoom,
  timeUsToPixels,
  timelineContentWidthPx,
  type TimelineScale,
} from '../memeTimelineCore';
import { colors, radius, space, type } from '../theme';
import { PressableScale } from './ui';

const CELL_HEIGHT = 48;
const OVERSCAN_CELLS = 2;

// Module-level so leaving and re-entering the tool reuses frames already
// decoded for this source. Bounded by MAX_FRAME_CACHE_ENTRIES, so a ten-minute
// clip costs the same as a ten-second one.
const FRAME_CACHE = createFrameCache();

// Decodes that have started but not yet written a result. A native decode
// cannot be aborted, so a superseded batch still lands in the cache — this is
// what stops the next batch from paying for the same frame a second time.
const FRAME_DECODES_IN_FLIGHT = new Set<string>();

// `frames` lays one cell out per source frame at a fixed pitch, so every frame
// is reachable at a cost that depends only on the viewport. `overview` keeps the
// timeline's time->pixel mapping so a long clip can be traversed at all; it is
// honest about sampling every Nth frame rather than pretending otherwise.
type StripMode = 'frames' | 'overview';

function cellAccessibilityLabel(cell: FrameCell, stride: number): string {
  const where = `Frame ${cell.index}, ${formatFrameTimeUs(cell.timeUs)}`;
  const span = stride > 1 ? `, first of ${stride} frames` : '';
  if (cell.state === 'undecodable') return `${where}${span}, could not be decoded`;
  if (cell.state === 'pending') return `${where}${span}, loading`;
  return `${where}${span}`;
}

export const MemeFrameStrip = React.memo(function MemeFrameStrip({
  project,
  frameRate,
  playheadUs,
  disabled = false,
  onSeekToFrame,
}: {
  project: MemeEditProject;
  /** Container frame rate from the media probe. Null falls back to 30fps. */
  frameRate: number | null;
  /** Source-time playhead the studio owns. */
  playheadUs: number;
  disabled?: boolean;
  /** Seek to an EXACT frame boundary. Never called with an interpolated instant. */
  onSeekToFrame: (sourceTimeUs: number) => void;
}) {
  const durationUs = project.source.durationUs ?? 0;
  const sourceUri = project.transient.materializedSourceUri ?? project.source.uri;
  const frameIntervalUs = sourceFrameIntervalUs(frameRate);

  const [mode, setMode] = useState<StripMode>('frames');
  const [zoom, setZoom] = useState(TIMELINE_MIN_ZOOM);
  const [viewportWidthPx, setViewportWidthPx] = useState(0);
  const [scrollBucketPx, setScrollBucketPx] = useState(0);
  const [cacheRevision, setCacheRevision] = useState(0);

  const scrollRef = useRef<ScrollView | null>(null);
  const draggingRef = useRef(false);
  const decodeGenerationRef = useRef(0);
  const mountedRef = useRef(true);

  const framesMode = mode === 'frames';
  const readout = frameStripReadout(playheadUs, frameIntervalUs, durationUs);

  const scale = useMemo<TimelineScale>(
    () => ({ durationUs, viewportWidthPx, zoom }),
    [durationUs, viewportWidthPx, zoom]
  );
  const overviewIntervalUs = frameStripIntervalUs(scale, frameIntervalUs);
  const stride = framesMode ? 1 : frameStripStride(scale, frameIntervalUs);

  const frameWindow = useMemo(
    () =>
      frameWindowAt(
        scrollBucketPx,
        viewportWidthPx,
        durationUs,
        frameIntervalUs,
        FRAME_CELL_WIDTH_PX,
        OVERSCAN_CELLS
      ),
    [durationUs, frameIntervalUs, scrollBucketPx, viewportWidthPx]
  );

  const contentWidthPx = framesMode ? frameWindow.contentWidthPx : timelineContentWidthPx(scale);
  const cellWidthPx = framesMode
    ? FRAME_CELL_WIDTH_PX
    : frameStripTickWidthPx(scale, overviewIntervalUs);

  const ticks = useMemo<FrameTick[]>(
    () =>
      framesMode
        ? frameWindowTicks(frameWindow, frameIntervalUs, FRAME_CELL_WIDTH_PX)
        : frameStripTicks(scale, frameIntervalUs, {
            scrollXPx: scrollBucketPx,
            windowWidthPx: viewportWidthPx,
            overscanPx: FRAME_CELL_WIDTH_PX * OVERSCAN_CELLS,
          }),
    [frameIntervalUs, frameWindow, framesMode, scale, scrollBucketPx, viewportWidthPx]
  );

  const cells = useMemo(
    () =>
      frameStripCells(ticks, {
        cache: FRAME_CACHE,
        sourceUri,
        frameIntervalUs,
        cellSpanUs: framesMode ? frameIntervalUs : overviewIntervalUs,
        cellWidthPx,
        currentTimeUs: playheadUs,
        contentWidthPx,
      }),
    // `cacheRevision` is not read here; it is what makes a landed decode repaint.
    [
      cacheRevision,
      cellWidthPx,
      contentWidthPx,
      frameIntervalUs,
      framesMode,
      overviewIntervalUs,
      playheadUs,
      sourceUri,
      ticks,
    ]
  );

  // Retire in-flight decodes whose layout is gone. Declared BEFORE the pump so a
  // mode, zoom, or source change bumps the generation first and the pump's own
  // run then supersedes it as usual. Without this, a mode switch whose new cells
  // are all cached leaves the previous batch decoding for a layout nobody sees.
  useEffect(() => {
    decodeGenerationRef.current += 1;
  }, [mode, sourceUri, zoom]);

  // Decode pump. Each run supersedes the previous generation, so a fling
  // abandons the stale batch at its next await instead of queueing behind it.
  // Frames already awaited are still cached — the work is never wasted — but
  // nothing touches state once the generation is stale or the strip unmounted.
  // A native decode cannot be aborted mid-flight; that is the real ceiling.
  useEffect(() => {
    if (project.source.kind !== 'video' || durationUs <= 0) return;
    const pending = pendingFrameRequests(
      ticks,
      FRAME_CACHE,
      sourceUri,
      FRAME_DECODE_BATCH,
      FRAME_DECODES_IN_FLIGHT
    );
    if (pending.length === 0) return;
    const generation = decodeGenerationRef.current + 1;
    decodeGenerationRef.current = generation;
    let cancelled = false;
    void (async () => {
      let decoded = 0;
      for (const timeUs of pending) {
        if (cancelled || decodeGenerationRef.current !== generation) break;
        const key = TimelineThumbnailCache.keyFor(sourceUri, timeUs);
        if (FRAME_DECODES_IN_FLIGHT.has(key)) continue;
        FRAME_DECODES_IN_FLIGHT.add(key);
        const seconds = timeUs / 1_000_000;
        let frameUri: string | null = null;
        try {
          try {
            frameUri = await extractVideoFrame(sourceUri, seconds);
          } catch {
            frameUri = null;
          }
          if (frameUri === null) {
            try {
              frameUri = await extractVideoFramePlayer(sourceUri, seconds);
            } catch {
              frameUri = null;
            }
          }
          // Remembered even when null: an undecodable timestamp must not be
          // retried on every scroll, and its cell must be able to say so
          // instead of looking like a decode still in flight.
          FRAME_CACHE.set(sourceUri, timeUs, frameUri);
          decoded += 1;
        } finally {
          FRAME_DECODES_IN_FLIGHT.delete(key);
        }
      }
      // Repaint whenever anything landed, even if this batch was superseded.
      // The generation decides whether to keep DECODING; suppressing the paint
      // too would strand a decoded frame as a permanently 'loading' cell, since
      // the next batch now skips timestamps this one already resolved.
      if (decoded > 0 && mountedRef.current) setCacheRevision((revision) => revision + 1);
    })();
    return () => {
      cancelled = true;
    };
  }, [cacheRevision, durationUs, project.source.kind, sourceUri, ticks]);

  useEffect(
    () => () => {
      // Unmount: retire every generation so no in-flight batch reports back
      // into a dead tree.
      mountedRef.current = false;
      decodeGenerationRef.current += 1;
    },
    []
  );

  // Keep the current frame centred whenever the user is not driving the strip.
  const followTargetPx = framesMode
    ? readout.index * FRAME_CELL_WIDTH_PX + FRAME_CELL_WIDTH_PX / 2
    : timeUsToPixels(readout.exactUs, scale) + cellWidthPx / 2;
  useEffect(() => {
    if (draggingRef.current || viewportWidthPx <= 0 || contentWidthPx <= 0) return;
    scrollRef.current?.scrollTo({
      x: centerScrollXPx(followTargetPx, viewportWidthPx, contentWidthPx),
      animated: false,
    });
  }, [contentWidthPx, followTargetPx, viewportWidthPx]);

  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const next = event.nativeEvent.layout.width;
    setViewportWidthPx((current) => (Math.abs(current - next) < 0.5 ? current : next));
  }, []);

  const onScroll = useCallback((event: NativeSyntheticEvent<NativeScrollEvent>) => {
    const bucket =
      Math.round(event.nativeEvent.contentOffset.x / FRAME_CELL_WIDTH_PX) * FRAME_CELL_WIDTH_PX;
    setScrollBucketPx((current) => (current === bucket ? current : bucket));
  }, []);
  // Scrolling this strip never moves the playhead — a cell has to be chosen —
  // so the drag flag only suppresses the follow effect from fighting the finger.
  const onScrollBeginDrag = useCallback(() => {
    draggingRef.current = true;
  }, []);
  const onScrollSettled = useCallback(() => {
    draggingRef.current = false;
  }, []);

  // The whole point of the module: a cell seeks to the EXACT frame it drew.
  // A zero-frame step snaps and clamps to a frame the strip actually renders,
  // which is one frame short of what snapToFrameUs alone would allow.
  const selectFrame = useCallback(
    (timeUs: number) => {
      tap();
      onSeekToFrame(stepFrameUs(timeUs, frameIntervalUs, durationUs, 0));
    },
    [durationUs, frameIntervalUs, onSeekToFrame]
  );

  const step = useCallback(
    (deltaFrames: number) => {
      const next = stepFrameUs(playheadUs, frameIntervalUs, durationUs, deltaFrames);
      if (next === readout.exactUs) {
        warn();
        return;
      }
      tap();
      onSeekToFrame(next);
    },
    [durationUs, frameIntervalUs, onSeekToFrame, playheadUs, readout.exactUs]
  );

  const switchMode = useCallback((next: StripMode) => {
    tap();
    setMode(next);
    // Force a re-measure of the window against the new layout; the follow effect
    // then recentres on the same frame, so the mode switch never loses the spot.
    setScrollBucketPx(0);
  }, []);

  if (project.source.kind !== 'video' || durationUs <= 0) {
    return (
      <View style={styles.empty} accessibilityRole="text">
        <Text style={styles.emptyTitle}>No frames to step through</Text>
        <Text style={styles.emptyText}>
          This source is a still image, so there is only ever one frame.
        </Text>
      </View>
    );
  }

  if (readout.totalFrames === 0) {
    return (
      <View style={styles.empty} accessibilityRole="text">
        <Text style={styles.emptyTitle}>No full frames</Text>
        <Text style={styles.emptyText}>
          {`This clip is shorter than a single frame at ${Math.round(1_000_000 / frameIntervalUs)} fps, so there is nothing to step through.`}
        </Text>
      </View>
    );
  }

  const atFirst = readout.index <= 0;
  const atLast = readout.index >= readout.totalFrames - 1;
  // The compact side pane caps this panel at 240dp, and a clipped control is a
  // control below the 44dp floor. So the sampling note rides on the readout's
  // second line rather than costing a row of its own.
  const rateLabel = `${Math.round(1_000_000 / frameIntervalUs)} fps`;
  const strideNote = framesMode ? rateLabel : `every ${stride} frames`;
  const readoutValue = `Frame ${readout.index} of ${readout.totalFrames}, ${readout.timeLabel}, ${
    framesMode ? `${rateLabel}, every frame reachable` : `showing every ${stride} frames`
  }`;

  return (
    <View style={styles.root}>
      <View style={styles.headRow}>
        <View
          style={styles.readoutBlock}
          accessible
          accessibilityRole="adjustable"
          accessibilityLabel="Selected frame"
          accessibilityValue={{ text: readoutValue }}
          accessibilityActions={[
            { name: 'increment', label: 'Next frame' },
            { name: 'decrement', label: 'Previous frame' },
          ]}
          accessibilityState={{ disabled }}
          onAccessibilityAction={(event) => {
            if (disabled) return;
            step(event.nativeEvent.actionName === 'decrement' ? -1 : 1);
          }}
        >
          <Text style={styles.readoutFrame} numberOfLines={1}>
            {`Frame ${readout.index} / ${readout.totalFrames - 1}`}
          </Text>
          <Text style={styles.readoutTime} numberOfLines={1}>
            {`${readout.timeLabel} · ${strideNote}`}
          </Text>
        </View>
        <View style={styles.modeRow}>
          {(['overview', 'frames'] as const).map((candidate) => (
            <PressableScale
              key={candidate}
              scaleTo={0.94}
              style={[styles.mode, mode === candidate && styles.modeSelected]}
              disabled={disabled}
              onPress={() => switchMode(candidate)}
              accessibilityRole="button"
              accessibilityLabel={candidate === 'frames' ? 'Frame by frame' : 'Whole clip overview'}
              accessibilityHint={
                candidate === 'frames'
                  ? 'Lay out one cell per source frame so every frame can be reached'
                  : 'Show the whole clip at once to travel to a region quickly'
              }
              accessibilityState={{ selected: mode === candidate, disabled }}
            >
              <Text style={[styles.modeText, mode === candidate && styles.modeTextSelected]}>
                {candidate === 'frames' ? 'Frames' : 'Overview'}
              </Text>
            </PressableScale>
          ))}
        </View>
      </View>

      <View style={styles.stripFrame} onLayout={onLayout}>
        <ScrollView
          ref={scrollRef}
          horizontal
          scrollEnabled={!disabled}
          showsHorizontalScrollIndicator={false}
          scrollEventThrottle={16}
          onScroll={onScroll}
          onScrollBeginDrag={onScrollBeginDrag}
          onScrollEndDrag={onScrollSettled}
          onMomentumScrollEnd={onScrollSettled}
          contentContainerStyle={{ width: contentWidthPx }}
        >
          <View style={[styles.track, { width: contentWidthPx, height: CELL_HEIGHT }]}>
            {cells.map((cell) => (
              <PressableScale
                key={cell.timeUs}
                scaleTo={0.92}
                disabled={disabled}
                onPress={() => selectFrame(cell.timeUs)}
                style={[
                  styles.cell,
                  cell.state === 'pending' && styles.cellPending,
                  cell.state === 'undecodable' && styles.cellFailed,
                  cell.current && styles.cellCurrent,
                  { left: cell.xPx, width: cell.widthPx, height: CELL_HEIGHT },
                ]}
                accessibilityRole="button"
                accessibilityLabel={cellAccessibilityLabel(cell, stride)}
                accessibilityHint="Seek the preview to this exact frame"
                accessibilityState={{ selected: cell.current, disabled }}
              >
                {cell.state === 'ready' && cell.uri !== null ? (
                  <Image
                    style={styles.cellImage}
                    source={{ uri: cell.uri }}
                    contentFit="cover"
                    cachePolicy="memory"
                    transition={0}
                  />
                ) : null}
                {cell.state === 'undecodable' ? (
                  // A frame that will never decode must not look like one that
                  // is still loading, or the strip is lying about pending work.
                  <Text style={styles.cellFailedMark}>✕</Text>
                ) : null}
              </PressableScale>
            ))}
          </View>
        </ScrollView>
      </View>

      <View style={styles.actionRow}>
        <PressableScale
          scaleTo={0.96}
          style={styles.action}
          disabled={disabled || atFirst}
          onPress={() => step(-1)}
          accessibilityRole="button"
          accessibilityLabel="Previous frame"
          accessibilityHint="Seek back exactly one source frame"
          accessibilityState={{ disabled: disabled || atFirst }}
        >
          <Text style={styles.actionText}>◀ Frame</Text>
        </PressableScale>
        {!framesMode && (
          <>
            <PressableScale
              scaleTo={0.9}
              style={styles.mini}
              disabled={disabled || zoom <= TIMELINE_MIN_ZOOM}
              onPress={() => setZoom((current) => clampTimelineZoom(current / 2))}
              accessibilityRole="button"
              accessibilityLabel="Zoom out overview"
              accessibilityHint="Show more of the clip at once"
              accessibilityState={{ disabled: disabled || zoom <= TIMELINE_MIN_ZOOM }}
            >
              <Text style={styles.miniText}>−</Text>
            </PressableScale>
            <Text style={styles.zoomLabel}>{`${zoom}×`}</Text>
            <PressableScale
              scaleTo={0.9}
              style={styles.mini}
              disabled={disabled || zoom >= TIMELINE_MAX_ZOOM}
              onPress={() => setZoom((current) => clampTimelineZoom(current * 2))}
              accessibilityRole="button"
              accessibilityLabel="Zoom in overview"
              accessibilityHint="Sample the clip more finely"
              accessibilityState={{ disabled: disabled || zoom >= TIMELINE_MAX_ZOOM }}
            >
              <Text style={styles.miniText}>+</Text>
            </PressableScale>
          </>
        )}
        <PressableScale
          scaleTo={0.96}
          style={styles.action}
          disabled={disabled || atLast}
          onPress={() => step(1)}
          accessibilityRole="button"
          accessibilityLabel="Next frame"
          accessibilityHint="Seek forward exactly one source frame"
          accessibilityState={{ disabled: disabled || atLast }}
        >
          <Text style={styles.actionText}>Frame ▶</Text>
        </PressableScale>
      </View>

    </View>
  );
});

const styles = StyleSheet.create({
  root: { gap: space.sm, padding: space.md },
  empty: {
    gap: space.xs,
    padding: space.lg,
    backgroundColor: colors.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  emptyTitle: { ...type.title, color: colors.text },
  emptyText: { ...type.caption, color: colors.muted, lineHeight: 17 },
  headRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  readoutBlock: { flex: 1, minHeight: 44, justifyContent: 'center', paddingRight: space.sm },
  readoutFrame: { ...type.title, color: colors.text, fontVariant: ['tabular-nums'] },
  readoutTime: { ...type.caption, color: colors.textDim, fontVariant: ['tabular-nums'] },
  modeRow: { flexDirection: 'row', gap: space.xs },
  mode: {
    minHeight: 44,
    paddingHorizontal: space.sm,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.borderLight,
    backgroundColor: colors.surface3,
  },
  modeSelected: { borderColor: colors.volt, backgroundColor: colors.voltDim },
  modeText: { ...type.label, color: colors.textDim },
  modeTextSelected: { color: colors.volt },
  stripFrame: {
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
    overflow: 'hidden',
  },
  track: { position: 'relative', backgroundColor: colors.surface2 },
  cell: { position: 'absolute', top: 0, overflow: 'hidden', alignItems: 'center', justifyContent: 'center' },
  cellImage: { width: '100%', height: '100%' },
  cellPending: { backgroundColor: colors.surface3 },
  // Undecodable reads as a distinct failure, never as an empty slot: same
  // footprint, danger tint, and an explicit mark.
  cellFailed: { backgroundColor: colors.dangerDim, borderWidth: 1, borderColor: colors.danger },
  cellFailedMark: { color: colors.danger, fontSize: 14, fontWeight: '800' },
  cellCurrent: { borderWidth: 2, borderColor: colors.volt },
  actionRow: { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  action: {
    flex: 1,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.borderLight,
    backgroundColor: colors.surface3,
  },
  actionText: { ...type.label, color: colors.textDim, fontVariant: ['tabular-nums'] },
  zoomLabel: {
    ...type.caption,
    color: colors.textDim,
    fontVariant: ['tabular-nums'],
    minWidth: 30,
    textAlign: 'center',
  },
  mini: {
    minWidth: 44,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.borderLight,
    backgroundColor: colors.surface3,
  },
  miniText: { color: colors.textDim, fontSize: 16, fontWeight: '800' },
});
