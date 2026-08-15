import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  PanResponder,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type LayoutChangeEvent,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from 'react-native';
import { Image } from 'expo-image';

import { extractVideoFrame, extractVideoFramePlayer } from '../../modules/memeget-bg';
import { tap, warn } from '../haptics';
import type { MemeEditProject, TimeRangeUs } from '../memeEditProjectCore';
import { useConst } from '../reactUtils';
import {
  TIMELINE_MAX_ZOOM,
  TIMELINE_MIN_ZOOM,
  TimelineThumbnailCache,
  applyTrimHandle,
  clampTimelineZoom,
  formatTimelineTimeUs,
  parseTimelineTimeUs,
  insertSplitPointUs,
  pendingThumbnailRequests,
  pixelsToTimeUs,
  removeTimelineSegment,
  splitPointsForRetainedRanges,
  timeUsToPixels,
  timelineContentWidthPx,
  timelineGapBars,
  timelineLayerBar,
  timelineSegments,
  timelineThumbnailTicks,
  timelineReadout,
  type TimelineScale,
  type TimelineTrimEdge,
} from '../memeTimelineCore';
import { colors, radius, space, type } from '../theme';
import { PressableScale } from './ui';

const TILE_WIDTH = 44;
const TILE_HEIGHT = 48;
const SEGMENT_ROW_HEIGHT = 18;
const LAYER_ROW_HEIGHT = 12;
const LAYER_ROW_GAP = 4;
const MAX_LAYER_ROWS = 8;
const MAX_TILES = 48;
// Extractions started per pump. Small so a fling never queues dozens of native
// decodes; the effect re-pumps as each batch lands.
const THUMBNAIL_BATCH = 3;
// One frame of grace after the finger lifts, so a fling's momentum phase is
// recognised before the strip is treated as settled.
const SETTLE_DELAY_MS = 32;
const HANDLE_HIT_WIDTH = 44;
const ACCESSIBILITY_STEP_US = 100_000;

// Module-level so switching tools or reopening the studio reuses frames already
// decoded for this source instead of paying for them twice. Bounded: ~2 screens
// of tiles across a couple of sources.
const THUMBNAIL_CACHE = new TimelineThumbnailCache(128);

interface TrimDrag {
  rangeIndex: number;
  edge: TimelineTrimEdge;
  originPx: number;
  baseRanges: TimeRangeUs[];
}

function ThumbnailTile({ uri, xPx, widthPx }: { uri: string | null | undefined; xPx: number; widthPx: number }) {
  const style = { left: xPx, width: widthPx, height: TILE_HEIGHT };
  if (!uri) return <View style={[styles.tile, styles.tilePlaceholder, style]} />;
  return (
    <Image
      style={[styles.tile, style]}
      source={{ uri }}
      contentFit="cover"
      cachePolicy="memory"
      transition={0}
    />
  );
}

export const MemeTimeline = React.memo(function MemeTimeline({
  project,
  playheadUs,
  selectedLayerId,
  disabled = false,
  onScrubPlayhead,
  onScrubEnd,
  onCommitRetainedRanges,
  onSelectLayer,
}: {
  project: MemeEditProject;
  /** Source-time playhead the studio owns; the strip scrolls to follow it. */
  playheadUs: number;
  selectedLayerId: string | null;
  disabled?: boolean;
  /** Fires at full gesture rate. The studio throttles what reaches the player. */
  onScrubPlayhead: (sourceTimeUs: number) => void;
  onScrubEnd: (sourceTimeUs: number) => void;
  /** Called ONCE per completed trim gesture; the studio turns it into one undo entry. */
  onCommitRetainedRanges: (ranges: TimeRangeUs[]) => void;
  onSelectLayer: (id: string) => void;
}) {
  const durationUs = project.source.durationUs ?? 0;
  const sourceUri = project.transient.materializedSourceUri ?? project.source.uri;
  const committedRanges = project.video?.retainedRanges ?? [];

  const [viewportWidthPx, setViewportWidthPx] = useState(0);
  const [zoom, setZoom] = useState(TIMELINE_MIN_ZOOM);
  const [scrollBucketPx, setScrollBucketPx] = useState(0);
  const [trimRanges, setTrimRanges] = useState<TimeRangeUs[] | null>(null);
  const [splitPoints, setSplitPoints] = useState<number[]>([]);
  const [selectedSegment, setSelectedSegment] = useState<number | null>(null);
  const [scrubbing, setScrubbing] = useState(false);
  const [thumbnailRevision, setThumbnailRevision] = useState(0);

  const scrollRef = useRef<ScrollView | null>(null);
  const scrubbingRef = useRef(false);
  const trimDragRef = useRef<TrimDrag | null>(null);
  const trimPreviewRef = useRef<TimeRangeUs[] | null>(null);
  const playheadRef = useRef(playheadUs);
  const settleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const thumbnailGenerationRef = useRef(0);
  playheadRef.current = playheadUs;

  const scale = useMemo<TimelineScale>(
    () => ({ durationUs, viewportWidthPx, zoom }),
    [durationUs, viewportWidthPx, zoom]
  );
  const contentWidthPx = timelineContentWidthPx(scale);
  // Half a viewport of padding at each end so the first and last frame can both
  // reach the fixed centre playhead.
  const edgePadPx = viewportWidthPx / 2;

  // A trim in progress previews against a snapshot, so the committed project is
  // only touched once, on release.
  const ranges = trimRanges ?? committedRanges;
  // Handles track the live preview so the grabbed edge follows the finger — but
  // only while the preview has the same shape. A drag that closes a gap merges
  // two ranges into one; swapping the handle set mid-gesture would unmount the
  // responder under the finger and abandon the trim, so that case pins to the
  // committed edges until release.
  const handleRanges =
    trimRanges !== null && trimRanges.length === committedRanges.length ? trimRanges : committedRanges;
  const visibleSplitPoints = useMemo(
    () => splitPointsForRetainedRanges(splitPoints, ranges),
    [ranges, splitPoints]
  );
  const segments = useMemo(() => timelineSegments(ranges, visibleSplitPoints), [ranges, visibleSplitPoints]);
  const gapBars = useMemo(() => timelineGapBars(ranges, scale), [ranges, scale]);
  const readout = timelineReadout(project, playheadUs);

  const ticks = useMemo(
    () =>
      timelineThumbnailTicks(scale, {
        tileWidthPx: TILE_WIDTH,
        maxTiles: MAX_TILES,
        scrollXPx: scrollBucketPx,
        windowWidthPx: viewportWidthPx,
        overscanPx: TILE_WIDTH * 2,
      }),
    [scale, scrollBucketPx, viewportWidthPx]
  );

  // Thumbnail pump. Each run supersedes the previous generation, so a scroll or
  // a zoom abandons the stale batch at its next await instead of queueing behind
  // it. Frames already awaited are still cached — the work is never wasted — but
  // nothing touches state once the generation is stale or the strip unmounted.
  // A native decode cannot be aborted mid-flight; this is the real ceiling.
  useEffect(() => {
    if (project.source.kind !== 'video' || durationUs <= 0) return;
    const pending = pendingThumbnailRequests(ticks, THUMBNAIL_CACHE, sourceUri, THUMBNAIL_BATCH);
    if (pending.length === 0) return;
    const generation = thumbnailGenerationRef.current + 1;
    thumbnailGenerationRef.current = generation;
    let cancelled = false;
    void (async () => {
      for (const timeUs of pending) {
        if (cancelled || thumbnailGenerationRef.current !== generation) return;
        const seconds = timeUs / 1_000_000;
        let frameUri: string | null = null;
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
        // Remembered even when null: an undecodable timestamp must not be retried
        // on every render.
        THUMBNAIL_CACHE.set(sourceUri, timeUs, frameUri);
      }
      if (cancelled || thumbnailGenerationRef.current !== generation) return;
      setThumbnailRevision((revision) => revision + 1);
    })();
    return () => {
      cancelled = true;
    };
  }, [durationUs, project.source.kind, sourceUri, thumbnailRevision, ticks]);

  useEffect(() => () => {
    // Unmount: retire every generation so no in-flight batch continues or
    // reports back into a dead tree, and drop the pending settle.
    thumbnailGenerationRef.current += 1;
    clearTimeout(settleTimerRef.current ?? undefined);
  }, []);

  // Follow the studio's playhead whenever the user is not driving the strip.
  useEffect(() => {
    if (scrubbingRef.current || trimDragRef.current !== null || viewportWidthPx <= 0) return;
    scrollRef.current?.scrollTo({ x: timeUsToPixels(playheadUs, scale), animated: false });
  }, [playheadUs, scale, viewportWidthPx]);

  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const next = event.nativeEvent.layout.width;
    setViewportWidthPx((current) => (Math.abs(current - next) < 0.5 ? current : next));
  }, []);

  const onScroll = useCallback(
    (event: NativeSyntheticEvent<NativeScrollEvent>) => {
      const xPx = event.nativeEvent.contentOffset.x;
      const bucket = Math.round(xPx / TILE_WIDTH) * TILE_WIDTH;
      setScrollBucketPx((current) => (current === bucket ? current : bucket));
      if (scrubbingRef.current) onScrubPlayhead(pixelsToTimeUs(xPx, scale));
    },
    [onScrubPlayhead, scale]
  );
  // A fling keeps scrubbing after the finger lifts: the strip is still moving,
  // so the studio must not yank it back to the old playhead. `onScrollEndDrag`
  // may fire before RN decides there is momentum, so settling is deferred one
  // frame and cancelled if momentum starts.
  const onScrollBeginDrag = useCallback(() => {
    clearTimeout(settleTimerRef.current ?? undefined);
    settleTimerRef.current = null;
    scrubbingRef.current = true;
  }, []);
  const settleScrub = useCallback(
    (offsetXPx: number) => {
      if (!scrubbingRef.current) return;
      scrubbingRef.current = false;
      onScrubEnd(pixelsToTimeUs(offsetXPx, scale));
    },
    [onScrubEnd, scale]
  );
  const onScrollEndDrag = useCallback(
    (event: NativeSyntheticEvent<NativeScrollEvent>) => {
      const offsetXPx = event.nativeEvent.contentOffset.x;
      clearTimeout(settleTimerRef.current ?? undefined);
      settleTimerRef.current = setTimeout(() => {
        settleTimerRef.current = null;
        settleScrub(offsetXPx);
      }, SETTLE_DELAY_MS);
    },
    [settleScrub]
  );
  const onMomentumScrollBegin = useCallback(() => {
    clearTimeout(settleTimerRef.current ?? undefined);
    settleTimerRef.current = null;
  }, []);
  const onMomentumScrollEnd = useCallback(
    (event: NativeSyntheticEvent<NativeScrollEvent>) => settleScrub(event.nativeEvent.contentOffset.x),
    [settleScrub]
  );

  const beginTrim = useCallback(
    (rangeIndex: number, edge: TimelineTrimEdge) => {
      const range = committedRanges[rangeIndex];
      if (!range) return;
      trimDragRef.current = {
        rangeIndex,
        edge,
        originPx: timeUsToPixels(edge === 'start' ? range.startUs : range.endUs, scale),
        baseRanges: committedRanges.map((entry) => ({ ...entry })),
      };
      trimPreviewRef.current = null;
      setScrubbing(true);
      tap();
    },
    [committedRanges, scale]
  );
  // Every move re-derives from the snapshot taken on grant, never from the
  // previous preview: a drag that momentarily collapses two ranges must not
  // renumber the handle out from under the finger.
  const moveTrim = useCallback(
    (dx: number) => {
      const drag = trimDragRef.current;
      if (!drag) return;
      const proposedUs = pixelsToTimeUs(drag.originPx + dx, scale);
      trimPreviewRef.current = applyTrimHandle(
        drag.baseRanges,
        drag.rangeIndex,
        drag.edge,
        proposedUs,
        durationUs
      );
      setTrimRanges(trimPreviewRef.current);
      onScrubPlayhead(proposedUs);
    },
    [durationUs, onScrubPlayhead, scale]
  );
  // Exactly one call to onCommitRetainedRanges per gesture, so the studio turns
  // the whole drag into a single undo entry.
  const endTrim = useCallback(
    (commit: boolean) => {
      const drag = trimDragRef.current;
      const preview = trimPreviewRef.current;
      trimDragRef.current = null;
      trimPreviewRef.current = null;
      setScrubbing(false);
      setTrimRanges(null);
      if (!drag) return;
      onScrubEnd(playheadRef.current);
      if (!commit || !preview) return;
      // Segment indices are positional; a trim can renumber them.
      setSelectedSegment(null);
      onCommitRetainedRanges(preview);
    },
    [onCommitRetainedRanges, onScrubEnd]
  );

  const nudgeTrim = useCallback(
    (rangeIndex: number, edge: TimelineTrimEdge, deltaUs: number) => {
      const range = committedRanges[rangeIndex];
      if (!range) return;
      const currentUs = edge === 'start' ? range.startUs : range.endUs;
      onCommitRetainedRanges(
        applyTrimHandle(committedRanges, rangeIndex, edge, currentUs + deltaUs, durationUs)
      );
    },
    [committedRanges, durationUs, onCommitRetainedRanges]
  );

  // Which clip the typed timecodes address: the selected clip's range when one
  // is selected, otherwise the range under the playhead (falling back to the
  // nearest earlier range, so a playhead parked in a removed gap still edits
  // something predictable).
  const activeRangeIndex = useMemo(() => {
    if (selectedSegment !== null) {
      const segment = segments[selectedSegment];
      if (segment) return segment.rangeIndex;
    }
    let best = 0;
    for (let index = 0; index < committedRanges.length; index += 1) {
      const range = committedRanges[index];
      if (playheadUs >= range.startUs && playheadUs <= range.endUs) return index;
      if (range.startUs <= playheadUs) best = index;
    }
    return best;
  }, [committedRanges, playheadUs, segments, selectedSegment]);
  const activeRange = committedRanges[activeRangeIndex];

  // Null means "show the committed value"; a string is what the user is
  // mid-typing. Cleared on commit or blur so the field snaps back to the
  // clamped truth rather than keeping an unapplied number on screen.
  const [draftStartText, setDraftStartText] = useState<string | null>(null);
  const [draftEndText, setDraftEndText] = useState<string | null>(null);

  // The fields address whatever range is active NOW. Scrubbing or selecting a
  // different clip must not leave half-typed text from the old one on screen,
  // where a blur would commit it against the new range. Bounds are in the key
  // too, so an undo or a drag that moves this range also drops the draft.
  useEffect(() => {
    setDraftStartText(null);
    setDraftEndText(null);
  }, [activeRangeIndex, activeRange?.startUs, activeRange?.endUs]);

  const commitTypedEdge = useCallback(
    (edge: TimelineTrimEdge, text: string) => {
      const setDraft = edge === 'start' ? setDraftStartText : setDraftEndText;
      setDraft(null);
      const range = committedRanges[activeRangeIndex];
      const proposedUs = parseTimelineTimeUs(text, durationUs);
      if (proposedUs === null || range === undefined) {
        warn();
        return;
      }
      // Blur fires even when nothing was typed; an unchanged edge must not cost
      // an undo entry.
      const currentUs = edge === 'start' ? range.startUs : range.endUs;
      if (proposedUs === currentUs) return;
      const next = applyTrimHandle(committedRanges, activeRangeIndex, edge, proposedUs, durationUs);
      tap();
      // A typed edge can merge or renumber ranges exactly like a drag can.
      setSelectedSegment(null);
      onCommitRetainedRanges(next);
      onScrubEnd(proposedUs);
    },
    [activeRangeIndex, committedRanges, durationUs, onCommitRetainedRanges, onScrubEnd]
  );

  const splitAtPlayhead = useCallback(() => {
    const next = insertSplitPointUs(splitPoints, playheadUs, ranges);
    if (next === null) {
      warn();
      return;
    }
    tap();
    setSplitPoints(next);
    setSelectedSegment(null);
  }, [playheadUs, ranges, splitPoints]);

  const removeSelectedSegment = useCallback(() => {
    if (selectedSegment === null) return;
    const result = removeTimelineSegment(committedRanges, splitPoints, selectedSegment, durationUs);
    if (result === null) {
      warn();
      return;
    }
    setSplitPoints(result.splitPoints);
    setSelectedSegment(null);
    onCommitRetainedRanges(result.retainedRanges);
  }, [committedRanges, durationUs, onCommitRetainedRanges, selectedSegment, splitPoints]);

  if (project.source.kind !== 'video' || durationUs <= 0) {
    return (
      <View style={styles.empty} accessibilityRole="text">
        <Text style={styles.emptyTitle}>No video timeline</Text>
        <Text style={styles.emptyText}>This source is a still image, so there is nothing to trim or scrub.</Text>
      </View>
    );
  }

  const layerRows = project.layers.slice().reverse();
  const shownLayerRows = layerRows.slice(0, MAX_LAYER_ROWS);
  const stackHeight =
    TILE_HEIGHT + SEGMENT_ROW_HEIGHT + space.xs * 2 + shownLayerRows.length * (LAYER_ROW_HEIGHT + LAYER_ROW_GAP);

  return (
    <View style={styles.root}>
      <View style={styles.readoutRow}>
        <View
          style={styles.readoutBlock}
          accessibilityRole="adjustable"
          accessibilityLabel="Playhead"
          accessibilityValue={{ text: `${readout.currentLabel} of ${readout.durationLabel}` }}
          accessibilityActions={[
            { name: 'increment', label: 'Forward one second' },
            { name: 'decrement', label: 'Back one second' },
          ]}
          onAccessibilityAction={(event) => {
            if (disabled) return;
            const delta = event.nativeEvent.actionName === 'decrement' ? -1_000_000 : 1_000_000;
            const next = Math.max(0, Math.min(durationUs, playheadUs + delta));
            onScrubPlayhead(next);
            onScrubEnd(next);
          }}
        >
          <Text style={styles.readoutCurrent}>{readout.currentLabel}</Text>
          <Text style={styles.readoutDuration}>{` / ${readout.durationLabel}`}</Text>
        </View>
        <View style={styles.zoomRow}>
          <PressableScale
            scaleTo={0.9}
            style={styles.mini}
            disabled={disabled || zoom <= TIMELINE_MIN_ZOOM}
            onPress={() => setZoom((current) => clampTimelineZoom(current / 2))}
            accessibilityRole="button"
            accessibilityLabel="Zoom out timeline"
            accessibilityHint="Show more of the clip in the strip"
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
            accessibilityLabel="Zoom in timeline"
            accessibilityHint="Show finer detail in the strip"
            accessibilityState={{ disabled: disabled || zoom >= TIMELINE_MAX_ZOOM }}
          >
            <Text style={styles.miniText}>+</Text>
          </PressableScale>
        </View>
      </View>

      <View style={styles.stripFrame} onLayout={onLayout}>
        <ScrollView
          ref={scrollRef}
          horizontal
          scrollEnabled={!disabled && !scrubbing}
          showsHorizontalScrollIndicator={false}
          scrollEventThrottle={16}
          onScroll={onScroll}
          onScrollBeginDrag={onScrollBeginDrag}
          onScrollEndDrag={onScrollEndDrag}
          onMomentumScrollBegin={onMomentumScrollBegin}
          onMomentumScrollEnd={onMomentumScrollEnd}
          contentContainerStyle={{ width: contentWidthPx + edgePadPx * 2, paddingHorizontal: edgePadPx }}
        >
          <View style={[styles.stack, { width: contentWidthPx, height: stackHeight }]}>
            <View style={[styles.filmstrip, { width: contentWidthPx, height: TILE_HEIGHT }]}>
              {ticks.map((tick) => (
                <ThumbnailTile
                  key={tick.timeUs}
                  uri={THUMBNAIL_CACHE.get(sourceUri, tick.timeUs)}
                  xPx={tick.xPx}
                  widthPx={tick.widthPx}
                />
              ))}
              {gapBars.map((gap) => (
                <View
                  key={`gap-${gap.startUs}`}
                  style={[styles.gapOverlay, { left: gap.xPx, width: gap.widthPx, height: TILE_HEIGHT }]}
                  pointerEvents="none"
                />
              ))}
            </View>

            <View style={[styles.segmentRow, { width: contentWidthPx, height: SEGMENT_ROW_HEIGHT }]}>
              {gapBars.map((gap) => (
                <View
                  key={`gapbar-${gap.startUs}`}
                  style={[styles.gapBar, { left: gap.xPx, width: gap.widthPx }]}
                  pointerEvents="none"
                />
              ))}
              {segments.map((segment, index) => {
                const xPx = timeUsToPixels(segment.startUs, scale);
                const widthPx = Math.max(2, timeUsToPixels(segment.endUs, scale) - xPx);
                const selected = selectedSegment === index;
                return (
                  <PressableScale
                    key={`segment-${segment.startUs}-${segment.endUs}`}
                    scaleTo={0.98}
                    style={[styles.segmentBar, selected && styles.segmentBarSelected, { left: xPx, width: widthPx }]}
                    disabled={disabled}
                    onPress={() => setSelectedSegment(selected ? null : index)}
                    accessibilityRole="button"
                    accessibilityLabel={`Clip ${index + 1} of ${segments.length}, ${formatTimelineTimeUs(segment.startUs)} to ${formatTimelineTimeUs(segment.endUs)}`}
                    accessibilityHint="Select this clip so it can be removed"
                    accessibilityState={{ selected, disabled }}
                  />
                );
              })}
              {visibleSplitPoints.map((point) => (
                <View
                  key={`split-${point}`}
                  style={[styles.splitMark, { left: timeUsToPixels(point, scale) - 1 }]}
                  pointerEvents="none"
                />
              ))}
            </View>

            {shownLayerRows.map((layer, index) => {
              const bar = timelineLayerBar(layer, scale);
              const selected = layer.id === selectedLayerId;
              return (
                <PressableScale
                  key={layer.id}
                  scaleTo={0.98}
                  disabled={disabled}
                  onPress={() => onSelectLayer(layer.id)}
                  style={[
                    styles.layerBar,
                    selected && styles.layerBarSelected,
                    layer.active === null && styles.layerBarUntimed,
                    {
                      left: bar.xPx,
                      width: bar.widthPx,
                      top: TILE_HEIGHT + SEGMENT_ROW_HEIGHT + space.xs * 2 + index * (LAYER_ROW_HEIGHT + LAYER_ROW_GAP),
                    },
                  ]}
                  accessibilityRole="button"
                  accessibilityLabel={`${layer.kind} layer, ${layer.active === null ? 'whole clip' : `${formatTimelineTimeUs(layer.active.startUs)} to ${formatTimelineTimeUs(layer.active.endUs)}`}`}
                  accessibilityHint="Select this layer and move the playhead into its range"
                  accessibilityState={{ selected, disabled }}
                />
              );
            })}

            {handleRanges.map((range, index) => (
              <React.Fragment key={`handles-${index}`}>
                <TrimHandle
                  edge="start"
                  disabled={disabled}
                  leftPx={timeUsToPixels(range.startUs, scale) - HANDLE_HIT_WIDTH / 2}
                  height={TILE_HEIGHT + SEGMENT_ROW_HEIGHT}
                  label={`Clip ${index + 1} start, ${formatTimelineTimeUs(range.startUs)}`}
                  onBegin={() => beginTrim(index, 'start')}
                  onMove={moveTrim}
                  onEnd={endTrim}
                  onNudge={(delta) => nudgeTrim(index, 'start', delta)}
                />
                <TrimHandle
                  edge="end"
                  disabled={disabled}
                  leftPx={timeUsToPixels(range.endUs, scale) - HANDLE_HIT_WIDTH / 2}
                  height={TILE_HEIGHT + SEGMENT_ROW_HEIGHT}
                  label={`Clip ${index + 1} end, ${formatTimelineTimeUs(range.endUs)}`}
                  onBegin={() => beginTrim(index, 'end')}
                  onMove={moveTrim}
                  onEnd={endTrim}
                  onNudge={(delta) => nudgeTrim(index, 'end', delta)}
                />
              </React.Fragment>
            ))}
          </View>
        </ScrollView>
        <View style={styles.playhead} pointerEvents="none" />
      </View>

      {layerRows.length > shownLayerRows.length && (
        <Text style={styles.overflowNote}>{`+${layerRows.length - shownLayerRows.length} more layers not shown`}</Text>
      )}

      {activeRange !== undefined && (
        <View style={styles.timecodeRow}>
          <Text style={styles.timecodeLabel}>{`Clip ${activeRangeIndex + 1}`}</Text>
          <TimecodeField
            value={draftStartText ?? formatTimelineTimeUs(activeRange.startUs)}
            dirty={draftStartText !== null}
            disabled={disabled}
            label={`Clip ${activeRangeIndex + 1} start time`}
            onChangeText={setDraftStartText}
            onCommit={(text) => commitTypedEdge('start', text)}
          />
          <Text style={styles.timecodeSeparator}>→</Text>
          <TimecodeField
            value={draftEndText ?? formatTimelineTimeUs(activeRange.endUs)}
            dirty={draftEndText !== null}
            disabled={disabled}
            label={`Clip ${activeRangeIndex + 1} end time`}
            onChangeText={setDraftEndText}
            onCommit={(text) => commitTypedEdge('end', text)}
          />
        </View>
      )}

      <View style={styles.actionRow}>
        <PressableScale
          scaleTo={0.96}
          style={styles.action}
          disabled={disabled}
          onPress={splitAtPlayhead}
          accessibilityRole="button"
          accessibilityLabel="Split at playhead"
          accessibilityHint="Cut the clip under the playhead into two selectable clips"
          accessibilityState={{ disabled }}
        >
          <Text style={styles.actionText}>Split</Text>
        </PressableScale>
        <PressableScale
          scaleTo={0.96}
          style={[styles.action, styles.actionDanger]}
          disabled={disabled || selectedSegment === null || segments.length <= 1}
          onPress={removeSelectedSegment}
          accessibilityRole="button"
          accessibilityLabel="Remove selected clip"
          accessibilityHint="Drop the selected clip from the export"
          accessibilityState={{ disabled: disabled || selectedSegment === null || segments.length <= 1 }}
        >
          <Text style={[styles.actionText, styles.actionTextDanger]}>Remove clip</Text>
        </PressableScale>
      </View>
    </View>
  );
});

// Typed trim entry. Commits on submit AND on blur, because a phone keyboard is
// as often dismissed as it is submitted; the parent then clears the draft so the
// field re-renders the clamped, committed value. An untouched field never
// commits: its displayed value is rounded to centiseconds and would otherwise
// nudge a finer edge on every blur. Submit also latches the text it sent, so the
// blur that follows dismissal cannot commit it a second time.
function TimecodeField({
  value,
  dirty,
  label,
  disabled,
  onChangeText,
  onCommit,
}: {
  value: string;
  dirty: boolean;
  label: string;
  disabled: boolean;
  onChangeText: (text: string) => void;
  onCommit: (text: string) => void;
}) {
  const sentRef = useRef<string | null>(null);
  const commit = (text: string) => {
    if (sentRef.current === text) return;
    sentRef.current = text;
    onCommit(text);
  };
  return (
    <TextInput
      style={[styles.timecodeInput, disabled && styles.timecodeInputDisabled]}
      value={value}
      editable={!disabled}
      keyboardType="numbers-and-punctuation"
      returnKeyType="done"
      selectTextOnFocus
      autoCorrect={false}
      placeholder="0:00.00"
      placeholderTextColor={colors.muted}
      onFocus={() => {
        sentRef.current = null;
      }}
      onChangeText={onChangeText}
      onSubmitEditing={(event) => commit(event.nativeEvent.text)}
      onBlur={() => {
        if (dirty) commit(value);
      }}
      accessibilityLabel={label}
      accessibilityHint="Type a timecode like 1:02.50 to trim this clip exactly"
      accessibilityState={{ disabled }}
    />
  );
}

// Each handle owns its gesture. Claiming the responder on touch START is what
// stops the enclosing horizontal ScrollView from panning instead; the parent
// additionally flips `scrollEnabled` off for the duration of the drag.
// Callbacks live in refs so the responder is created exactly once per handle.
function TrimHandle({
  edge,
  leftPx,
  height,
  label,
  disabled,
  onBegin,
  onMove,
  onEnd,
  onNudge,
}: {
  edge: TimelineTrimEdge;
  leftPx: number;
  height: number;
  label: string;
  disabled: boolean;
  onBegin: () => void;
  onMove: (dx: number) => void;
  onEnd: (commit: boolean) => void;
  onNudge: (deltaUs: number) => void;
}) {
  const handlers = useRef({ disabled, onBegin, onMove, onEnd });
  handlers.current = { disabled, onBegin, onMove, onEnd };
  const pan = useConst(() =>
    PanResponder.create({
      onStartShouldSetPanResponder: () => !handlers.current.disabled,
      onMoveShouldSetPanResponder: () => !handlers.current.disabled,
      onPanResponderTerminationRequest: () => false,
      onPanResponderGrant: () => handlers.current.onBegin(),
      onPanResponderMove: (_event, gesture) => handlers.current.onMove(gesture.dx),
      onPanResponderRelease: () => handlers.current.onEnd(true),
      onPanResponderTerminate: () => handlers.current.onEnd(false),
    })
  );
  return (
    <View
      style={[styles.handleHit, { left: leftPx, height }]}
      {...pan.panHandlers}
      accessible
      accessibilityRole="adjustable"
      accessibilityLabel={label}
      accessibilityHint="Drag to trim, or use increment and decrement to nudge by a tenth of a second"
      accessibilityState={{ disabled }}
      accessibilityActions={[
        { name: 'increment', label: 'Later' },
        { name: 'decrement', label: 'Earlier' },
      ]}
      onAccessibilityAction={(event) => {
        if (disabled) return;
        onNudge(event.nativeEvent.actionName === 'decrement' ? -ACCESSIBILITY_STEP_US : ACCESSIBILITY_STEP_US);
      }}
    >
      <View style={[styles.handle, edge === 'start' ? styles.handleStart : styles.handleEnd]} />
    </View>
  );
}

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
  readoutRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  readoutBlock: { flexDirection: 'row', alignItems: 'baseline', minHeight: 44, paddingRight: space.sm },
  readoutCurrent: { ...type.title, color: colors.text, fontVariant: ['tabular-nums'] },
  readoutDuration: { ...type.label, color: colors.muted, fontVariant: ['tabular-nums'] },
  zoomRow: { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  zoomLabel: { ...type.caption, color: colors.textDim, fontVariant: ['tabular-nums'], minWidth: 26, textAlign: 'center' },
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
  stripFrame: {
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
    overflow: 'hidden',
  },
  stack: { position: 'relative' },
  filmstrip: { position: 'relative', backgroundColor: colors.surface2 },
  tile: { position: 'absolute', top: 0 },
  tilePlaceholder: { backgroundColor: colors.surface3 },
  // Removed stretches read as removed: the frames stay visible but knocked back
  // behind a scrim, so it is obvious what the export will drop.
  gapOverlay: { position: 'absolute', top: 0, backgroundColor: colors.overlay },
  segmentRow: { position: 'relative', marginTop: space.xs },
  segmentBar: {
    position: 'absolute',
    top: 3,
    height: 12,
    borderRadius: radius.sm,
    backgroundColor: colors.volt,
    opacity: 0.85,
  },
  segmentBarSelected: { opacity: 1, borderWidth: 2, borderColor: colors.text },
  gapBar: {
    position: 'absolute',
    top: 7,
    height: 4,
    borderRadius: 2,
    backgroundColor: colors.danger,
    opacity: 0.5,
  },
  splitMark: { position: 'absolute', top: 0, width: 2, height: SEGMENT_ROW_HEIGHT, backgroundColor: colors.text },
  layerBar: {
    position: 'absolute',
    height: LAYER_ROW_HEIGHT,
    borderRadius: 3,
    backgroundColor: colors.accent,
    opacity: 0.55,
  },
  layerBarUntimed: { backgroundColor: colors.muted },
  layerBarSelected: { opacity: 1, borderWidth: 1, borderColor: colors.text },
  timecodeRow: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  timecodeLabel: { ...type.label, color: colors.textDim, minWidth: 52 },
  timecodeSeparator: { ...type.label, color: colors.muted },
  timecodeInput: {
    flex: 1,
    minHeight: 44,
    paddingHorizontal: space.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.borderLight,
    backgroundColor: colors.surface3,
    color: colors.text,
    fontVariant: ['tabular-nums'],
    textAlign: 'center',
  },
  timecodeInputDisabled: { opacity: 0.5 },
  handleHit: { position: 'absolute', top: 0, width: HANDLE_HIT_WIDTH, alignItems: 'center', justifyContent: 'center' },
  handle: { width: 4, height: '100%', backgroundColor: colors.volt, borderRadius: 2 },
  handleStart: { borderTopLeftRadius: radius.sm, borderBottomLeftRadius: radius.sm },
  handleEnd: { borderTopRightRadius: radius.sm, borderBottomRightRadius: radius.sm },
  playhead: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: '50%',
    width: 2,
    marginLeft: -1,
    backgroundColor: colors.text,
  },
  overflowNote: { ...type.caption, color: colors.muted },
  actionRow: { flexDirection: 'row', gap: space.sm },
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
  actionDanger: { borderColor: colors.danger, backgroundColor: colors.dangerDim },
  actionText: { ...type.label, color: colors.textDim },
  actionTextDanger: { color: colors.danger },
});
