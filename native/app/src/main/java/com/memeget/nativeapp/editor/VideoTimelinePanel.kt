package com.memeget.nativeapp.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memeget.nativeapp.editor.TimelineEdits.Edge
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val RULER_HEIGHT = 14.dp
private val STRIP_HEIGHT = 52.dp
private val LANE_HEIGHT = 12.dp
private val LANE_GAP = 3.dp
private val HANDLE_WIDTH = 12.dp
private val HANDLE_REACH = 22.dp
/** Zoomed all the way in, one frame is this wide. */
private val MAX_FRAME_WIDTH = 56.dp
private const val FALLBACK_FRAME_US = 33_333L

private data class TrimHandle(val index: Int, val edge: Edge)

/**
 * The video timeline: the whole source as a filmstrip under a fixed playhead line. Drag to scrub, pinch to zoom
 * down to single frames, drag a kept range's yellow edge to trim it (the canvas shows the frame under the finger),
 * tap to jump. Below the strip: the selected layer's timing and where the music plays.
 */
@Composable
internal fun VideoTimelinePanel(
    project: MemeEditProject,
    playheadUs: Long,
    frameIndex: FrameTimelineIndex?,
    selectedLayer: MemeEditLayer?,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onTrimPeek: (Long?) -> Unit,
    onSetRanges: (List<TimeRangeUs>) -> Unit,
    onUpdateLayer: (MemeEditLayer) -> Unit,
    onAddCard: (text: String, backgroundColor: String, durationUs: Long) -> Unit,
    onRemoveCard: (Int) -> Unit,
    onMusicAtPlayhead: () -> Unit,
    onOpenAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val durationUs = TimelineEdits.durationUs(project)
    val ranges = TimelineEdits.ranges(project)
    val segments = remember(project.video, durationUs) { TimelineEdits.segments(project) }
    val music = project.video?.music
    val musicPlan = remember(music, segments) { music?.let { MusicPlan.of(it, TimelineEdits.outputDurationUs(segments)) } }
    val rangeIndex = TimelineEdits.rangeIndexAt(ranges, playheadUs)
    val playheadOutputUs = ProjectCore.mapSourceTimeToOutputTime(playheadUs, segments, project.video?.speed ?: 1f)
    var showCardDialog by remember { mutableStateOf(false) }

    fun snap(us: Long): Long = frameIndex?.let { it.getTimestampUs(it.findFrameIndex(us)) } ?: us
    fun stepFrame(forward: Boolean) {
        val next = if (forward) {
            frameIndex?.nextFrameTimeUs(playheadUs) ?: (playheadUs + FALLBACK_FRAME_US)
        } else {
            frameIndex?.previousFrameTimeUs(playheadUs) ?: (playheadUs - FALLBACK_FRAME_US)
        }
        onSeek(next.coerceIn(0L, durationUs))
    }

    if (showCardDialog) {
        TitleCardDialog(
            onDismiss = { showCardDialog = false },
            onAdd = { text, color, cardUs ->
                showCardDialog = false
                onAddCard(text, color, cardUs)
            }
        )
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatTimecode(playheadUs), color = StudioColors.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val frameLabel = frameIndex?.let {
                val vfr = if (it.isVariableFrameRate) " VFR" else ""
                "f ${it.findFrameIndex(playheadUs) + 1}/${it.frameCount}$vfr"
            }
            Text(
                listOfNotNull(frameLabel, if (rangeIndex == null) "cut" else null).joinToString(" · "),
                color = if (rangeIndex == null) StudioColors.Danger else StudioColors.Muted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            RepeatChip("◀ 1f", enabled = playheadUs > 0L) { stepFrame(forward = false) }
            RepeatChip("1f ▶", enabled = playheadUs < durationUs) { stepFrame(forward = true) }
        }
        Spacer(Modifier.height(6.dp))

        TimelineStrip(
            project = project,
            ranges = ranges,
            segments = segments,
            musicPlan = musicPlan,
            selectedLayer = selectedLayer,
            playheadUs = playheadUs,
            durationUs = durationUs,
            frameIndex = frameIndex,
            snap = ::snap,
            onSeek = onSeek,
            onScrubbing = onScrubbing,
            onTrimPeek = onTrimPeek,
            onSetRanges = onSetRanges,
            onTapMusicLane = onOpenAudio
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (rangeIndex != null) {
                StudioChip("✂ Split", TimelineEdits.splitAt(ranges, playheadUs)?.let { { onSetRanges(it) } })
                StudioChip("🗑 Delete clip", TimelineEdits.deleteAt(ranges, playheadUs)?.let { split ->
                    {
                        onSetRanges(split)
                        // Land on the frame that now follows the deleted clip, or the end of what is left.
                        val next = split.firstOrNull { it.startUs >= playheadUs }?.startUs ?: (split.last().endUs - 1)
                        onSeek(snap(next))
                    }
                }, color = StudioColors.Danger)
            } else {
                StudioChip("↩ Restore cut", { onSetRanges(restoreCut(ranges, playheadUs, durationUs)) })
            }
            StudioChip("+ Title card", if (rangeIndex != null) ({ showCardDialog = true }) else null)
            if (selectedLayer != null) {
                StudioChip("⇤ Starts here", { onUpdateLayer(TimelineEdits.setLayerTiming(selectedLayer, Edge.START, playheadUs, durationUs)) }, color = StudioColors.Layer)
                StudioChip("⇥ Ends here", { onUpdateLayer(TimelineEdits.setLayerTiming(selectedLayer, Edge.END, playheadUs, durationUs)) }, color = StudioColors.Layer)
                if (selectedLayer.active != null) {
                    StudioChip("↔ Whole video", { onUpdateLayer(TimelineEdits.withActive(selectedLayer, null)) }, color = StudioColors.Layer)
                }
            }
            if (music != null) {
                StudioChip("♪ Music starts here", if (playheadOutputUs != null) onMusicAtPlayhead else null, color = StudioColors.Music)
            } else {
                StudioChip("♪ Add music", onOpenAudio, color = StudioColors.Music)
            }
            project.video?.insertedCards.orEmpty().forEachIndexed { i, card ->
                StudioChip("Card ${formatTimecode(card.atUs)} ✕", { onRemoveCard(i) })
            }
        }
    }
}

/** Ranges with the cut under [us] put back: the gap between two ranges, or a trimmed start or end. */
private fun restoreCut(ranges: List<TimeRangeUs>, us: Long, durationUs: Long): List<TimeRangeUs> {
    val after = ranges.indexOfFirst { it.startUs > us }
    return when {
        after == 0 -> TimelineEdits.trimEdge(ranges, 0, Edge.START, 0L, durationUs)
        after > 0 -> TimelineEdits.joinWithNext(ranges, after - 1)
        else -> TimelineEdits.trimEdge(ranges, ranges.lastIndex, Edge.END, durationUs, durationUs)
    }
}

@Composable
private fun TimelineStrip(
    project: MemeEditProject,
    ranges: List<TimeRangeUs>,
    segments: List<ProjectCore.CompositionSegment>,
    musicPlan: MusicPlan?,
    selectedLayer: MemeEditLayer?,
    playheadUs: Long,
    durationUs: Long,
    frameIndex: FrameTimelineIndex?,
    snap: (Long) -> Long,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onTrimPeek: (Long?) -> Unit,
    onSetRanges: (List<TimeRangeUs>) -> Unit,
    onTapMusicLane: () -> Unit
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val stripHeightPx = with(density) { STRIP_HEIGHT.roundToPx() }
    val sourceUri = project.materializedSourceUri ?: project.source.uri
    val filmstrip by rememberFilmstrip(sourceUri, durationUs, stripHeightPx)
    val speed = project.video?.speed ?: 1f

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat()
        val fitPxPerUs = widthPx.toDouble() / durationUs.coerceAtLeast(1L)
        val frameUs = frameIndex?.let { if (it.frameCount > 1) it.durationUs / it.frameCount else null } ?: FALLBACK_FRAME_US
        val maxPxPerUs = with(density) { MAX_FRAME_WIDTH.toPx() } / frameUs.coerceAtLeast(1L).toDouble()
        var pxPerUs by remember(durationUs, widthPx) { mutableDoubleStateOf(fitPxPerUs) }
        var draft by remember { mutableStateOf<List<TimeRangeUs>?>(null) }
        val shownRanges = draft ?: ranges
        val scale = StripScale(playheadUs, pxPerUs, widthPx)

        // Gestures outlive recompositions; they read the newest values through these.
        val latest by rememberUpdatedState(TimelineGestureState(scale, ranges, durationUs, snap, onSeek, onScrubbing, onTrimPeek, onSetRanges, onTapMusicLane, frameIndex))
        var scrubUs by remember { mutableStateOf(0L) }

        val rulerPx = with(density) { RULER_HEIGHT.toPx() }
        val stripTop = rulerPx
        val stripBottom = stripTop + stripHeightPx
        val laneGap = with(density) { LANE_GAP.toPx() }
        val laneHeight = with(density) { LANE_HEIGHT.toPx() }
        val layerLaneTop = stripBottom + laneGap
        val musicLaneTop = layerLaneTop + laneHeight + laneGap
        val totalHeight = with(density) { (musicLaneTop + laneHeight).toDp() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
                .scrubGestures(
                    pxPerUs = { latest.scale.pxPerUs },
                    grabAt = { p -> if (p.y in stripTop..stripBottom) handleAt(latest.scale, latest.ranges, p.x, density) else null },
                    onScrubStart = {
                        scrubUs = latest.scale.centerUs
                        latest.onScrubbing(true)
                    },
                    onScrubBy = { delta ->
                        scrubUs = (scrubUs + delta).coerceIn(0L, latest.durationUs)
                        latest.onSeek(scrubUs)
                    },
                    onZoom = { factor -> pxPerUs = (pxPerUs * factor).coerceIn(fitPxPerUs * 0.5, max(maxPxPerUs, fitPxPerUs)) },
                    onHandleMove = { handle, x ->
                        handle as TrimHandle
                        val toUs = latest.snap(latest.scale.us(x).coerceIn(0L, latest.durationUs))
                        val moved = TimelineEdits.trimEdge(latest.ranges, handle.index, handle.edge, toUs, latest.durationUs)
                        draft = moved
                        val edge = moved[handle.index]
                        latest.onTrimPeek(
                            if (handle.edge == Edge.START) edge.startUs
                            else latest.frameIndex?.previousFrameTimeUs(edge.endUs) ?: (edge.endUs - 1)
                        )
                    },
                    onTap = { p ->
                        if (p.y > musicLaneTop) latest.onTapMusicLane()
                        else latest.onSeek(latest.snap(latest.scale.us(p.x).coerceIn(0L, latest.durationUs)))
                    },
                    onEnd = { handle ->
                        if (handle is TrimHandle) {
                            val moved = draft
                            draft = null
                            latest.onTrimPeek(null)
                            if (moved != null) {
                                latest.onSetRanges(moved)
                                val edge = moved[handle.index]
                                latest.onSeek(
                                    if (handle.edge == Edge.START) edge.startUs
                                    else latest.frameIndex?.previousFrameTimeUs(edge.endUs) ?: (edge.endUs - 1)
                                )
                            }
                        } else {
                            latest.onSeek(latest.snap(scrubUs))
                            latest.onScrubbing(false)
                        }
                    }
                )
        ) {
            drawRuler(scale, 0L, durationUs, rulerPx, measurer)
            clipRect(top = stripTop, bottom = stripBottom) {
                drawFilmstrip(filmstrip, scale, durationUs, stripTop, stripHeightPx.toFloat(), project.source)
                drawCuts(shownRanges, scale, durationUs, stripTop, stripBottom)
                drawRangeFrames(shownRanges, scale, stripTop, stripBottom, density.density)
            }
            drawCards(segments, scale, speed, stripTop, stripBottom)
            selectedLayer?.let { drawLayerLane(it, scale, durationUs, layerLaneTop, laneHeight, measurer) }
            musicPlan?.let { drawMusicLane(it, project.video?.music?.name, segments, speed, scale, musicLaneTop, laneHeight, measurer) }
            drawPlayhead(widthPx / 2f, rulerPx, size.height)
        }
    }
}

private class TimelineGestureState(
    val scale: StripScale,
    val ranges: List<TimeRangeUs>,
    val durationUs: Long,
    val snap: (Long) -> Long,
    val onSeek: (Long) -> Unit,
    val onScrubbing: (Boolean) -> Unit,
    val onTrimPeek: (Long?) -> Unit,
    val onSetRanges: (List<TimeRangeUs>) -> Unit,
    val onTapMusicLane: () -> Unit,
    val frameIndex: FrameTimelineIndex?
)

/** The trim handle under [x]: the inner [HANDLE_WIDTH] of a kept range's edge, reachable a little either side. */
private fun handleAt(scale: StripScale, ranges: List<TimeRangeUs>, x: Float, density: androidx.compose.ui.unit.Density): TrimHandle? {
    val reach = with(density) { HANDLE_REACH.toPx() }
    val outside = with(density) { 6.dp.toPx() }
    val minWidth = with(density) { (HANDLE_WIDTH * 3).toPx() }
    var best: TrimHandle? = null
    var bestDistance = Float.MAX_VALUE
    ranges.forEachIndexed { i, r ->
        val start = scale.x(r.startUs)
        val end = scale.x(r.endUs)
        if (end - start < minWidth) return@forEachIndexed
        // Each handle reaches inward, so where two ranges touch the finger's side picks the range.
        if (x in (start - outside)..(start + reach) && abs(x - start) < bestDistance) {
            best = TrimHandle(i, Edge.START)
            bestDistance = abs(x - start)
        }
        if (x in (end - reach)..(end + outside) && abs(x - end) < bestDistance) {
            best = TrimHandle(i, Edge.END)
            bestDistance = abs(x - end)
        }
    }
    return best
}

private fun DrawScope.drawFilmstrip(strip: Filmstrip, scale: StripScale, durationUs: Long, top: Float, heightPx: Float, source: MemeEditSource) {
    val aspect = if (source.height > 0) source.width.toFloat() / source.height else 1f
    val tileWidth = max(heightPx * aspect, 16.dp.toPx())
    val tileUs = (tileWidth / scale.pxPerUs).toLong().coerceAtLeast(1L)
    val startX = scale.x(0L)
    val endX = scale.x(durationUs)
    drawRect(Color(0xFF0D1117), Offset(startX, top), Size(endX - startX, heightPx))
    val firstTile = ((scale.us(0f).coerceAtLeast(0L)) / tileUs).coerceAtLeast(0L)
    var tile = firstTile
    while (true) {
        val tileStartUs = tile * tileUs
        if (tileStartUs >= durationUs) break
        val x = scale.x(tileStartUs)
        if (x > size.width) break
        val frame = strip.nearest(tileStartUs + tileUs / 2)
        if (frame != null) {
            // Crop the frame to the tile from its middle; the last tile stops at the clip's end.
            val drawWidth = minOf(tileWidth, endX - x)
            val srcWidth = (frame.width * drawWidth / tileWidth).roundToInt().coerceIn(1, frame.width)
            // Whole pixels from floor(x) to ceil(x + width), so neighbouring tiles meet without a seam.
            val left = kotlin.math.floor(x).toInt()
            val right = kotlin.math.ceil(x + drawWidth).toInt()
            drawImage(
                image = frame,
                srcOffset = IntOffset((frame.width - srcWidth) / 2, 0),
                srcSize = IntSize(srcWidth, frame.height),
                dstOffset = IntOffset(left, top.roundToInt()),
                dstSize = IntSize((right - left).coerceAtLeast(1), heightPx.roundToInt())
            )
        }
        tile++
    }
}

private fun DrawScope.drawCuts(ranges: List<TimeRangeUs>, scale: StripScale, durationUs: Long, top: Float, bottom: Float) {
    var cursor = 0L
    val dim = Color(0xD90A0B0E)
    fun cut(from: Long, to: Long) {
        if (to <= from) return
        val a = scale.x(from).coerceAtLeast(0f)
        val b = scale.x(to).coerceAtMost(size.width)
        if (b <= a) return
        drawRect(dim, Offset(a, top), Size(b - a, bottom - top))
        // Hatching says "removed" even where the dimmed frames are dark anyway.
        val h = bottom - top
        clipRect(left = a, top = top, right = b, bottom = bottom) {
            var hx = a - h
            while (hx < b) {
                drawLine(Color(0x66FF5252), Offset(hx, bottom), Offset(hx + h, top), 1.dp.toPx())
                hx += 10.dp.toPx()
            }
        }
    }
    for (r in ranges) {
        cut(cursor, r.startUs)
        cursor = r.endUs
    }
    cut(cursor, durationUs)
}

private fun DrawScope.drawRangeFrames(ranges: List<TimeRangeUs>, scale: StripScale, top: Float, bottom: Float, density: Float) {
    val stroke = 2f * density
    val handle = HANDLE_WIDTH.value * density
    for (r in ranges) {
        val a = scale.x(r.startUs)
        val b = scale.x(r.endUs)
        if (b < 0f || a > size.width) continue
        drawRoundRect(
            StudioColors.Accent,
            Offset(a + stroke / 2, top + stroke / 2),
            Size(b - a - stroke, bottom - top - stroke),
            CornerRadius(4f * density),
            style = Stroke(stroke)
        )
        if (b - a >= handle * 3) {
            for (x in listOf(a, b - handle)) {
                drawRoundRect(StudioColors.Accent, Offset(x, top), Size(handle, bottom - top), CornerRadius(3f * density))
                val mid = x + handle / 2
                drawLine(Color.Black, Offset(mid, top + (bottom - top) * 0.35f), Offset(mid, bottom - (bottom - top) * 0.35f), 1.5f * density)
            }
        }
    }
}

/** A white tick where each title card plays, at the source time it interrupts. */
private fun DrawScope.drawCards(segments: List<ProjectCore.CompositionSegment>, scale: StripScale, speed: Float, top: Float, bottom: Float) {
    var lastSourceUs = 0L
    for (seg in segments) {
        when (seg) {
            is ProjectCore.CompositionSegment.Source -> lastSourceUs = seg.sourceEndUs
            is ProjectCore.CompositionSegment.Card -> {
                val x = scale.x(lastSourceUs.takeIf { it > 0L } ?: firstSourceStart(segments))
                val w = 3.dp.toPx()
                drawRect(Color.White, Offset(x - w / 2, top - 2.dp.toPx()), Size(w, bottom - top + 4.dp.toPx()))
            }
        }
    }
}

private fun firstSourceStart(segments: List<ProjectCore.CompositionSegment>): Long =
    (segments.firstOrNull { it is ProjectCore.CompositionSegment.Source } as? ProjectCore.CompositionSegment.Source)?.sourceStartUs ?: 0L

private fun DrawScope.drawLayerLane(
    layer: MemeEditLayer,
    scale: StripScale,
    durationUs: Long,
    top: Float,
    height: Float,
    measurer: androidx.compose.ui.text.TextMeasurer
) {
    val active = layer.active ?: TimeRangeUs(0L, durationUs)
    val a = scale.x(active.startUs).coerceAtLeast(0f)
    val b = scale.x(active.endUs).coerceAtMost(size.width)
    if (b <= a) return
    drawRoundRect(StudioColors.Layer, Offset(a, top), Size(b - a, height), CornerRadius(height / 2))
    val label = when (layer) {
        is MemeEditLayer.Text -> layer.text.lineSequence().firstOrNull().orEmpty()
        is MemeEditLayer.Cover -> "cover"
        is MemeEditLayer.Subject -> "cutout"
        is MemeEditLayer.Media -> "sticker"
        is MemeEditLayer.Draw -> "drawing"
    }
    drawLaneLabel(label, a, b, top, height, measurer)
}

/** The music where it is heard: each piece of the edited video it overlaps, drawn at its source position. */
private fun DrawScope.drawMusicLane(
    plan: MusicPlan,
    name: String?,
    segments: List<ProjectCore.CompositionSegment>,
    speed: Float,
    scale: StripScale,
    top: Float,
    height: Float,
    measurer: androidx.compose.ui.text.TextMeasurer
) {
    var labelled = false
    for (seg in segments) {
        if (seg !is ProjectCore.CompositionSegment.Source) continue
        val from = maxOf(seg.outputStartUs, plan.leadInUs)
        val to = minOf(seg.outputEndUs, plan.endUs)
        if (to <= from) continue
        val sourceFrom = seg.sourceStartUs + ((from - seg.outputStartUs) * speed).toLong()
        val sourceTo = seg.sourceStartUs + ((to - seg.outputStartUs) * speed).toLong()
        val a = scale.x(sourceFrom).coerceAtLeast(0f)
        val b = scale.x(sourceTo).coerceAtMost(size.width)
        if (b <= a) continue
        drawRoundRect(StudioColors.Music, Offset(a, top), Size(b - a, height), CornerRadius(height / 2))
        if (!labelled) {
            drawLaneLabel("♪ ${name ?: "music"}", a, b, top, height, measurer)
            labelled = true
        }
    }
}

private fun DrawScope.drawLaneLabel(label: String, a: Float, b: Float, top: Float, height: Float, measurer: androidx.compose.ui.text.TextMeasurer) {
    val pad = 4.dp.toPx()
    val room = (b - a - 2 * pad).roundToInt()
    if (room < 24.dp.toPx()) return
    val layout = measurer.measure(
        label,
        TextStyle(color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = room)
    )
    drawText(layout, topLeft = Offset(a + pad, top + (height - layout.size.height) / 2))
}

private fun DrawScope.drawPlayhead(x: Float, top: Float, bottom: Float) {
    val w = 2.dp.toPx()
    drawRect(Color.White, Offset(x - w / 2, top), Size(w, bottom - top))
    val tri = 6.dp.toPx()
    drawPath(
        Path().apply {
            moveTo(x - tri, top - tri)
            lineTo(x + tri, top - tri)
            lineTo(x, top)
            close()
        },
        Color.White
    )
}

@Composable
private fun TitleCardDialog(onDismiss: () -> Unit, onAdd: (text: String, color: String, durationUs: Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(CARD_COLORS.first().second) }
    var seconds by remember { mutableFloatStateOf(1.5f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Title card", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    placeholder = { Text("meanwhile…") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, hex) in CARD_COLORS) StudioChip(label, { color = hex }, selected = color == hex)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = seconds,
                        onValueChange = { seconds = (it * 10).roundToInt() / 10f },
                        valueRange = 0.5f..5f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = StudioColors.Accent, activeTrackColor = StudioColors.Accent)
                    )
                    Box(Modifier.width(44.dp)) { Text("%.1fs".format(seconds), color = StudioColors.Muted, fontSize = 12.sp) }
                }
                Text("Plays at the playhead, sized to the video.", color = StudioColors.Muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(text.ifBlank { "…" }, color, (seconds * 1_000_000L).toLong()) },
                colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Accent)
            ) { Text("Add", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
        containerColor = StudioColors.Panel
    )
}

private val CARD_COLORS = listOf("Black" to "#0A0B0E", "White" to "#FFFFFF", "Red" to "#D7263D", "Yellow" to "#FFD400")
