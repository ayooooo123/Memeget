package com.memeget.nativeapp.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

internal object StudioColors {
    val Accent = Color(0xFFD8FF4A)
    val Panel = Color(0xFF161B22)
    val Chip = Color(0xFF21262D)
    val Muted = Color(0xFF8B949E)
    val Disabled = Color(0xFF484F58)
    val Danger = Color(0xFFFF5252)
    val Music = Color(0xFF4AD8FF)
    val Layer = Color(0xFFB58CFF)
}

/** A strip of time with [centerUs] under its fixed middle line, [pxPerUs] wide per microsecond. */
internal class StripScale(val centerUs: Long, val pxPerUs: Double, val widthPx: Float) {
    fun x(us: Long): Float = (widthPx / 2.0 + (us - centerUs) * pxPerUs).toFloat()
    fun us(x: Float): Long = (centerUs + (x - widthPx / 2.0) / pxPerUs).roundToLong()
}

/** "1:02.35": minutes, seconds and hundredths, the precision the strip edits at. */
internal fun formatTimecode(us: Long): String {
    val centis = (us.coerceAtLeast(0L) + 5_000L) / 10_000L
    return "%d:%02d.%02d".format(centis / 6_000, (centis / 100) % 60, centis % 100)
}

/**
 * Drag to move the strip under its middle line (one finger), pinch to zoom, press on a handle to drag it, tap
 * to jump. The strip itself does not move: callers own the time under the line and the zoom.
 *
 * @param grabAt the handle a press at a point lands on, or null to scrub.
 */
internal fun Modifier.scrubGestures(
    pxPerUs: () -> Double,
    grabAt: (Offset) -> Any?,
    onScrubStart: () -> Unit,
    onScrubBy: (deltaUs: Long) -> Unit,
    onZoom: (factor: Float) -> Unit,
    onHandleMove: (handle: Any, x: Float) -> Unit,
    onTap: (Offset) -> Unit,
    onEnd: (handle: Any?) -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val handle = grabAt(down.position)
        var travel = 0f
        var started = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val pan = event.calculatePan()
            travel += abs(pan.x)
            if (!started && (travel > viewConfiguration.touchSlop || pressed.size > 1)) {
                started = true
                if (handle == null) onScrubStart()
            }
            if (!started) continue
            if (handle != null) {
                onHandleMove(handle, pressed.first().position.x)
            } else {
                if (pressed.size > 1) event.calculateZoom().takeIf { it != 1f }?.let(onZoom)
                if (pan.x != 0f) onScrubBy((-pan.x / pxPerUs()).roundToLong())
            }
            event.changes.forEach { it.consume() }
        }
        if (started) onEnd(handle) else onTap(down.position)
    }
}

/** Time labels and ticks along the top [heightPx] of a strip, spaced at least 64 dp apart. */
internal fun DrawScope.drawRuler(scale: StripScale, fromUs: Long, toUs: Long, heightPx: Float, measurer: TextMeasurer) {
    val minGapUs = 64.dp.toPx() / scale.pxPerUs
    val stepUs = RULER_STEPS_US.firstOrNull { it >= minGapUs } ?: RULER_STEPS_US.last()
    val labelStyle = TextStyle(color = StudioColors.Muted, fontSize = 9.sp)
    val first = (scale.us(0f).coerceAtLeast(fromUs) / stepUs) * stepUs
    var t = first
    while (t <= toUs) {
        val x = scale.x(t)
        if (x > size.width) break
        if (x >= 0f) {
            drawLine(StudioColors.Muted, Offset(x, heightPx * 0.6f), Offset(x, heightPx), strokeWidth = 1.dp.toPx())
            val label = measurer.measure(rulerLabel(t, stepUs), labelStyle)
            val labelX = x + 2.dp.toPx()
            if (labelX + label.size.width <= size.width) drawText(label, topLeft = Offset(labelX, 0f))
        }
        t += stepUs
    }
}

private val RULER_STEPS_US = longArrayOf(
    10_000, 20_000, 50_000, 100_000, 200_000, 500_000,
    1_000_000, 2_000_000, 5_000_000, 10_000_000, 30_000_000, 60_000_000
)

/** "2s", "1.5s", "0.25s" under a minute (as many decimals as the step needs), "1:05" past it. */
private fun rulerLabel(us: Long, stepUs: Long): String {
    if (us >= 60_000_000L) return "%d:%02d".format(us / 60_000_000L, (us / 1_000_000L) % 60)
    val decimals = when {
        stepUs % 1_000_000L == 0L -> 0
        stepUs % 100_000L == 0L -> 1
        else -> 2
    }
    return "%.${decimals}fs".format(us / 1_000_000.0)
}

/** A small pill button; [onClick] null shows it dimmed and inert. */
@Composable
internal fun StudioChip(label: String, onClick: (() -> Unit)?, selected: Boolean = false, color: Color = Color.White) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) StudioColors.Accent else StudioColors.Chip)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = when {
                selected -> Color.Black
                onClick == null -> StudioColors.Disabled
                else -> color
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** A chip that repeats [onStep] while held: once on press, then steadily after a short wait. */
@Composable
internal fun RepeatChip(label: String, enabled: Boolean, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(StudioColors.Chip)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    coroutineScope {
                        step()
                        val repeat = launch {
                            delay(400)
                            while (true) {
                                step()
                                delay(70)
                            }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                    }
                })
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (enabled) Color.White else StudioColors.Disabled, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
