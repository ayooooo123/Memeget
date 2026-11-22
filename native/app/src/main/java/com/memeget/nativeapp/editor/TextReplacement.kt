package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import com.memeget.nativeapp.InferenceEngine
import expo.modules.memegetbg.MemeTextLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Keep explicit breaks. When a paragraph replaces several lines, distribute its words over their measured widths. */
private fun fitLines(text: String, lines: List<Rect>): String {
    if (lines.size < 2 || '\n' in text) return text
    val paragraph = text.trim()
    val spaces = Regex("\\s+").findAll(paragraph).toList()
    if (spaces.size < lines.size - 1) return text
    val width = lines.sumOf { it.width() }
    var start = 0
    var widthSoFar = 0
    return buildString {
        for (line in 0 until lines.lastIndex) {
            widthSoFar += lines[line].width()
            val target = paragraph.length * widthSoFar.toFloat() / width
            val last = spaces.size - (lines.lastIndex - line)
            val split = (start..last).minBy { abs(spaces[it].range.first - target) }
            append(paragraph, if (start == 0) 0 else spaces[start - 1].range.last + 1, spaces[split].range.first)
            append('\n')
            start = split + 1
        }
        append(paragraph, spaces[start - 1].range.last + 1, paragraph.length)
    }
}

internal object TextReplacement {
    private val fontPresets = arrayOf("plain", "subtitle", "impact")

    /** A run is an unbroken row of ink. Its average width measures strokes without counting gaps between letters. */
    private fun strokeWidth(paint: Paint, text: String, bounds: Rect, path: Path, clip: Region, glyphs: Region, span: Rect): Float {
        path.rewind()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        clip.set(bounds.left - 1, bounds.top - 1, bounds.right + 1, bounds.bottom + 1)
        glyphs.setPath(path, clip)
        val runs = RegionIterator(glyphs)
        var area = 0
        var rows = 0
        while (runs.next(span)) {
            rows += span.height()
            area += span.width() * span.height()
        }
        return area.toFloat() / rows.coerceAtLeast(1) / bounds.height()
    }

    suspend fun layers(context: Context, project: MemeEditProject, rect: NormalizedRect, replacement: String,
                       original: String?, engine: InferenceEngine): Pair<MemeEditLayer.Cover, MemeEditLayer.Text> = withContext(Dispatchers.IO) {
        val picture = VideoBackgroundEffect.decodeOrientedBitmap(context, project.materializedSourceUri ?: project.source.uri, 4096)
        try {
            val source = ProjectCore.remapRectUnclipped(rect, project.base, BaseTransform())
            val w = picture.width; val h = picture.height
            val selection = Rect(floor(source.x * w).toInt(), floor(source.y * h).toInt(),
                ceil((source.x + source.width) * w).toInt(), ceil((source.y + source.height) * h).toInt())
            if (!selection.intersect(0, 0, w, h)) throw IOException("Select letters inside the picture")
            val pad = (minOf(selection.width(), selection.height()) / 4).coerceIn(6, 64)
            val crop = Rect(selection).apply { inset(-pad, -pad); intersect(0, 0, w, h) }
            val scale = minOf(1f, 1024f / maxOf(crop.width(), crop.height()))
            val mw = (crop.width() * scale).roundToInt().coerceAtLeast(1)
            val mh = (crop.height() * scale).roundToInt().coerceAtLeast(1)
            val pixels = IntArray(mw * mh)
            if (scale == 1f) picture.getPixels(pixels, 0, mw, crop.left, crop.top, mw, mh) else {
                val small = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(small).drawBitmap(picture, crop, Rect(0, 0, mw, mh), Paint(Paint.FILTER_BITMAP_FLAG))
                    small.getPixels(pixels, 0, mw, 0, 0, mw, mh)
                } finally { small.recycle() }
            }
            val target = Rect(((selection.left - crop.left) * scale).roundToInt(), ((selection.top - crop.top) * scale).roundToInt(),
                ((selection.right - crop.left) * scale).roundToInt(), ((selection.bottom - crop.top) * scale).roundToInt())
            val letters = LetterMask.find(pixels, mw, mh, target) ?: throw IOException("Could not separate the letters. Select the text more closely.")
            val area = NormalizedRect(crop.left.toFloat() / w, crop.top.toFloat() / h, crop.width().toFloat() / w, crop.height().toFloat() / h)
            val work = LogoTracker.sourceRectToWorking(area, project.base)
            val text = textLayer(context, letters, mw, mh, work, project.base, rect, original, replacement)
            val built = LogoMask.Built(work, area, letters.hole, letters.ink, mw, mh, LogoMask.save(context, letters.hole, mw, mh))
            val model = try { engine.installedInpaintModel()?.let(MiganInpainter::open) }
                catch (stop: CancellationException) { throw stop }
                catch (_: Exception) { null }
            val patch = model.use { it?.let { fill -> LogoFill.image(context, picture, area, letters.hole, mw, mh, fill) } }
            built.stillLayer(patch) to text
        } finally { picture.recycle() }
    }

    /** Measure the actual ink, not the height of a loose selection. Multiline starts/ends tell us the alignment. */
    private fun textLayer(context: Context, letters: LetterMask, mw: Int, mh: Int, work: NormalizedRect, base: BaseTransform,
                          selection: NormalizedRect, old: String?, replacement: String): MemeEditLayer.Text {
        val (ink, w, h) = LogoMask.orient(letters.ink, mw, mh, base)
        val lines = mutableListOf<Rect>()
        var count = 0
        var edges = 0
        for (y in 0 until h) {
            var left = w; var right = 0
            for (x in 0 until w) if (ink[y * w + x] != 0.toByte()) {
                left = minOf(x, left); right = x + 1; count++
                if (x == 0 || ink[y * w + x - 1] == 0.toByte()) edges++
            }
            if (left >= right) continue
            val last = lines.lastOrNull()
            if (last == null || y - last.bottom > 2) lines += Rect(left, y, right, y + 1)
            else last.union(left, y, right, y + 1)
        }
        val bounds = Rect(lines.first()).apply { for (i in 1 until lines.size) union(lines[i]) }
        val line = lines.maxBy { it.width() }
        val transcript = old?.lines().orEmpty()
        val originalLine = if (transcript.size == lines.size) transcript[lines.indexOf(line)] else transcript.maxByOrNull { it.length }.orEmpty()
        val weight = count.toFloat() / edges.coerceAtLeast(1) / line.height()
        val strokePreset = if (weight >= 0.12f) "subtitle" else "plain"
        var preset = strokePreset
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 100f }
        val measured = Rect()
        var capHeight = 72
        if (originalLine.isNotBlank()) {
            val aspect = line.width().toFloat() / line.height()
            val path = Path()
            val clip = Region()
            val glyphs = Region()
            val span = Rect()
            var best = Float.MAX_VALUE
            for (key in fontPresets) {
                val spec = TextLayoutBridge.getPresetSpec(key)
                paint.typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight)
                paint.getTextBounds(originalLine, 0, originalLine.length, measured)
                if (measured.height() == 0) continue
                val stroke = strokeWidth(paint, originalLine, measured, path, clip, glyphs, span)
                if (stroke == 0f) continue
                val spacedWidth = measured.width() + 100f * spec.letterSpacingEm * (originalLine.length - 1)
                val difference = abs(spacedWidth / measured.height() / aspect - 1f) + abs(stroke / weight - 1f)
                if (difference < best || (difference == best && key == strokePreset)) {
                    best = difference
                    preset = key
                    capHeight = measured.height()
                }
            }
        }
        val em = line.height() * 100f / capHeight
        val fontSize = em * work.height / h
        fun spread(value: (Rect) -> Int) = lines.maxOf(value) - lines.minOf(value)
        val align = when {
            lines.size < 2 -> "center"
            spread { it.left } < spread { it.centerX() } && spread { it.left } < spread { it.right } -> "left"
            spread { it.right } < spread { it.centerX() } -> "right"
            else -> "center"
        }
        val words = fitLines(replacement, lines)
        val uppercase = old != null && ProjectCore.looksAllCaps(old)
        val spec = TextLayoutBridge.getPresetSpec(preset)
        paint.typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight)
        paint.textSize = em
        val display = if (uppercase) words.uppercase() else words
        // A tight OCR box fits the OLD words. Keep a longer replacement on its line if the canvas has room.
        val needed = display.lineSequence().maxOf {
            paint.measureText(it) + em * spec.letterSpacingEm * (it.length - 1).coerceAtLeast(0) + 2f
        } * work.width / w
        val width = maxOf(selection.width, needed).coerceIn(ProjectLimits.MIN_TEXT_WRAP_WIDTH, ProjectLimits.MAX_TEXT_WRAP_WIDTH)
        val cx = when (align) {
            "left" -> work.x + bounds.left * work.width / w + width / 2
            "right" -> work.x + bounds.right * work.width / w - width / 2
            else -> work.x + bounds.exactCenterX() * work.width / w
        }.coerceIn(width / 2f, 1f - width / 2f)
        fun hex(rgb: Int) = "#%06X".format(rgb and 0xffffff)
        val shadow = letters.shadowOffset?.let {
            val (x, y) = when (base.rotation) {
                QuarterRotation.R0 -> it.x to it.y
                QuarterRotation.R90 -> -it.y to it.x
                QuarterRotation.R180 -> -it.x to -it.y
                QuarterRotation.R270 -> it.y to -it.x
            }
            TextShadow(hex(checkNotNull(letters.edgeColor)),
                (if (base.flipX) -x else x) / em, (if (base.flipY) -y else y) / em)
        }
        val style = TextStyle(preset = preset, color = hex(letters.color), outlineColor = hex(letters.edgeColor ?: letters.color),
            outlineScale = 2f * letters.outlinePx / em, backgroundColor = null, align = align,
            uppercase = uppercase, shadow = shadow)
        return MemeEditLayer.Text("text_${System.nanoTime()}", words, width, fontSize, style,
            keyframes = listOf(TransformKeyframe(center = NormalizedPoint(cx, work.y + bounds.exactCenterY() * work.height / h))))
    }
}
