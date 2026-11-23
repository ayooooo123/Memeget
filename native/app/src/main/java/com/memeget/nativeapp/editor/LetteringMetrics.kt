package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import expo.modules.memegetbg.MemeTextLayout
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Compare visible stems and gaps with the actual Android font that will draw the replacement. */
internal object LetteringMetrics {
    data class Row(val width: Int, val height: Int, val stem: Float, val bar: Float?, val gap: Float?,
                   val bodies: Int, val meanBody: Float, val bodyError: Float)

    /** A run with both edges in the same place on nearby scanlines is a stem or crossbar, not a diagonal. */
    private fun stroke(alpha: ByteArray, stride: Int, ink: Rect, horizontal: Boolean, widths: IntArray): Float? {
        val inner = if (horizontal) ink.height() else ink.width()
        val outer = if (horizontal) ink.width() else ink.height()
        val step = if (horizontal) stride else 1
        val next = if (horizontal) 1 else stride
        val origin = ink.top * stride + ink.left
        val radius = maxOf(1, ink.height() / 12)
        val margin = if (horizontal) radius else ink.height() / 6
        fun filled(i: Int) = (alpha[i].toInt() and 255) >= 220
        widths.fill(0)
        var count = 0
        for (row in margin until outer - margin) {
            val base = origin + row * next
            var x = 0
            while (x < inner) {
                if (!filled(base + x * step)) { x++; continue }
                val start = x++
                while (x < inner && filled(base + x * step)) x++
                val width = x - start
                if (width >= widths.size) continue
                val end = x
                fun sameEdges(other: Int) = filled(other + start * step) && filled(other + (end - 1) * step) &&
                    (start == 0 || !filled(other + (start - 1) * step)) && (end == inner || !filled(other + end * step))
                if (sameEdges(base - radius * next) && sameEdges(base + radius * next)) { widths[width]++; count++ }
            }
        }
        if (count < maxOf(4, ink.height() / 4)) return null
        var rank = (count + 1) / 2; var median = 0
        while (rank > 0) { median++; rank -= widths[median] }
        // Average nearby widths so one pixel of raster rounding does not choose the weight of the whole font.
        val tolerance = maxOf(1, median / 4)
        var sum = 0; var samples = 0
        for (i in maxOf(1, median - tolerance)..minOf(widths.lastIndex, median + tolerance)) {
            sum += i * widths[i]; samples += widths[i]
        }
        return sum.toFloat() / samples
    }

    fun measure(alpha: ByteArray, stride: Int, bounds: Rect): Row? {
        fun filled(x: Int, y: Int) = (alpha[y * stride + x].toInt() and 255) >= 220
        val occupied = BooleanArray(bounds.width())
        var top = bounds.bottom; var bottom = bounds.top
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            if (!filled(x, y)) continue
            occupied[x - bounds.left] = true
            if (y < top) top = y
            bottom = y + 1
        }
        val height = bottom - top
        if (height < 10) return null
        val first = occupied.indexOfFirst { it }; val last = occupied.indexOfLast { it }
        val gaps = IntArray(last - first + 1)
        var n = 0; var gap = 0; var body = 0; var bodies = 0; var sum = 0; var squares = 0
        for (x in first..last + 1) {
            if (x <= last && occupied[x]) {
                body++
                if (gap > 0) { gaps[n++] = gap; gap = 0 }
            } else {
                gap++
                if (body > 0) { sum += body; squares += body * body; bodies++; body = 0 }
            }
        }
        val mean = sum.toFloat() / bodies
        val error = if (bodies > 1) sqrt(maxOf(0f, squares.toFloat() / bodies - mean * mean) / (bodies - 1)) else 0f
        // The median rejects the wider break between words. Do not guess tracking from a single gap.
        val spacing = if (n >= 3) {
            gaps.sort(0, n)
            (gaps[(n - 1) / 2] + gaps[n / 2]) * 0.5f
        } else null
        val ink = Rect(bounds.left + first, top, bounds.left + last + 1, bottom)
        val widths = IntArray(height / 3 + 1)
        val stem = stroke(alpha, stride, ink, false, widths) ?: return null
        return Row(ink.width(), height, stem, stroke(alpha, stride, ink, true, widths), spacing, bodies, mean, error)
    }

    private fun referenceRow(paint: Paint, sample: String): Row? {
        val bounds = Rect()
        paint.getTextBounds(sample, 0, sample.length, bounds)
        // Long transcripts and tiny or joined glyphs provide no reliable sample at this resolution.
        if (bounds.width() !in 2..2046 || bounds.height() !in 2..190) return null
        val image = Bitmap.createBitmap(bounds.width() + 2, bounds.height() + 2, Bitmap.Config.ALPHA_8)
        return try {
            paint.color = Color.WHITE
            Canvas(image).drawText(sample, (1 - bounds.left).toFloat(), (1 - bounds.top).toFloat(), paint)
            val bytes = ByteArray(image.rowBytes * image.height)
            image.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
            measure(bytes, image.rowBytes, Rect(0, 0, image.width, image.height))
        } finally { image.recycle() }
    }
    // The Manual Box comparison uses the same sample, size and three bundled presets for every picture.
    private val manualStems = HashMap<String, Float>()

    @Synchronized
    fun manualStem(preset: String, paint: Paint, sample: String): Float? {
        manualStems[preset]?.let { return it }
        val row = referenceRow(paint, sample) ?: return null
        return (row.stem / row.height).also { manualStems[preset] = it }
    }

    /** On success, [paint] has the actual typeface and font size needed to reproduce the source ink height. */
    fun fit(context: Context, original: Row?, paint: Paint, em: Float,
            sample: String, transcribed: Boolean, spec: TextPresetSpec): TextLettering? {
        if (sample.length < 3 || original == null) return null
        val referenceEm = em.coerceIn(48f, 120f)
        paint.textSize = referenceEm
        var reference = referenceRow(paint, sample) ?: return null
        var weight: Int? = null
        val bar = original.bar
        if (spec.fontFamily != "Anton" && bar != null && reference.bar != null) {
            val target = bar / original.height
            fun normalized(row: Row?) = row?.bar?.let { it / row.height }
            var error = abs(checkNotNull(normalized(reference)) - target)
            // Horizontal stretching changes stems, not crossbars. Fit the crossbars first.
            if (error * original.height > 0.45f) {
                fun instance(w: Int): Row? {
                    paint.typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight, w)
                    return referenceRow(paint, sample)
                }
                fun consider(w: Int, row: Row?) {
                    val actual = normalized(row) ?: return
                    val difference = abs(actual - target)
                    if (difference < error) { weight = w; reference = checkNotNull(row); error = difference }
                }
                val light = instance(400); val heavy = instance(850)
                val low = normalized(light); val high = normalized(heavy)
                if (low != null && high != null && high > low) {
                    consider(400, light); consider(850, heavy)
                    val fitted = ((400f + 450f * (target - low) / (high - low)) / 25).roundToInt().coerceIn(4, 36) * 25
                    if (fitted != 400 && fitted != 850) consider(fitted, instance(fitted))
                }
                paint.typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight, weight)
            }
        }
        val ratio = reference.height.toFloat() / original.height
        // Different faces can have the same letter width but different stem widths.
        val body = original.meanBody * ratio
        val bodyScale = when {
            transcribed && original.bodies == reference.bodies && original.bodies >= 3 -> body / reference.meanBody
            !transcribed && original.bodies >= 3 && reference.bodies >= 3 -> {
                // Different words can have different mean widths in the same font. Keep that font's
                // width unless the difference exceeds the sampling error of both sets of letters.
                val error = original.bodyError * ratio
                if (abs(body - reference.meanBody) > 2f * sqrt(error * error + reference.bodyError * reference.bodyError))
                    body / reference.meanBody else 1f
            }
            else -> 1f
        }.coerceIn(0.7f, 1.45f)
        val stemAtBodyWidth = reference.stem * bodyScale / ratio
        val widthScale = if (abs(stemAtBodyWidth - original.stem) <= 0.5f) bodyScale else {
            // One horizontal scale cannot match two different proportions exactly. The geometric
            // mean balances the two proportional errors rather than matching only one dimension.
            sqrt(bodyScale * original.stem * ratio / reference.stem).coerceIn(0.7f, 1.45f)
        }
        val spacing = when {
            transcribed -> (original.width * ratio / widthScale - reference.width) / (sample.length - 1) / referenceEm
            original.gap != null && reference.gap != null -> {
                // Keep the advance of a typical letter when a narrower stem also narrows the body.
                val advance = reference.meanBody * bodyScale + original.gap * ratio
                (advance / widthScale - reference.meanBody - checkNotNull(reference.gap)) / referenceEm
            }
            else -> spec.letterSpacingEm
        }
        paint.textSize = referenceEm / ratio
        return TextLettering(widthScale, spacing.coerceIn(-0.10f, 0.22f), weight)
    }
}
