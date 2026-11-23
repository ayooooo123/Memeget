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
        if (replacement.isBlank()) throw IOException("Enter replacement text before replacing the letters.")
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
            fun maskRect(box: NormalizedRect) = Rect(
                floor((box.x * w - crop.left) * scale).toInt(), floor((box.y * h - crop.top) * scale).toInt(),
                ceil(((box.x + box.width) * w - crop.left) * scale).toInt(),
                ceil(((box.y + box.height) * h - crop.top) * scale).toInt()
            ).apply { if (!intersect(0, 0, mw, mh)) throw IOException("Select letters inside the picture") }
            fun find(target: Rect) = LetterMask.find(pixels, mw, mh, target)
                ?: throw IOException("Could not separate the letters. Select the text more closely.")
            fun orient(mask: ByteArray) = if (project.base.rotation == QuarterRotation.R0 && !project.base.flipX && !project.base.flipY)
                Triple(mask, mw, mh) else LogoMask.orient(mask, mw, mh, project.base)
            var letters = find(maskRect(source))
            var ink = orient(letters.ink)
            var lines = inkLines(ink.first, ink.second, ink.third)
            val align = alignment(lines)
            val area = NormalizedRect(crop.left.toFloat() / w, crop.top.toFloat() / h, crop.width().toFloat() / w, crop.height().toFloat() / h)
            val work = LogoTracker.sourceRectToWorking(area, project.base)
            var box = rect
            var old = original
            var words = replacement
            if (original != null && (lines.size > 1 || '\n' in original)) {
                val before = original.lines()
                val after = replacement.lines()
                val changed = changedLines(before, after, lines.size)
                val first = changed.first
                val last = changed.last
                if (first != 0 || last != before.lastIndex) {
                    // Keep the selected lines' horizontal margins and split vertically in the gap.
                    // A short word must not inherit the width of a longer line that will stay in the picture.
                    val chosen = first..last
                    val left = rect.x + (chosen.minOf { lines[it].left } - lines.minOf { it.left }) * work.width / ink.second
                    val right = rect.x + rect.width - (lines.maxOf { it.right } - chosen.maxOf { lines[it].right }) * work.width / ink.second
                    fun between(a: Int, b: Int) = work.y + (lines[a].bottom + lines[b].top) * 0.5f * work.height / ink.third
                    val top = if (first == 0) rect.y else between(first - 1, first)
                    val bottom = if (last == lines.lastIndex) rect.y + rect.height else between(last, last + 1)
                    box = NormalizedRect(left, top, right - left, bottom - top)
                    letters = find(maskRect(ProjectCore.remapRectUnclipped(box, project.base, BaseTransform())))
                    ink = orient(letters.ink)
                    lines = inkLines(ink.first, ink.second, ink.third)
                    if (lines.size != last - first + 1) ambiguousLines()
                    old = before.subList(first, last + 1).joinToString("\n")
                    words = after.subList(first, last + 1).joinToString("\n")
                }
            }
            val text = textLayer(context, letters, ink, lines, work, project.base, box, old, words, align)
            val built = LogoMask.Built(work, area, letters.hole, letters.ink, mw, mh, LogoMask.save(context, letters.hole, mw, mh))
            val model = try { engine.installedInpaintModel()?.let(MiganInpainter::open) }
                catch (stop: CancellationException) { throw stop }
                catch (_: Exception) { null }
            val patch = model.use { it?.let { fill -> LogoFill.image(context, picture, area, letters.hole, mw, mh, fill) } }
            built.stillLayer(patch) to text
        } finally { picture.recycle() }
    }

    private fun inkLines(ink: ByteArray, w: Int, h: Int): List<Rect> {
        val lines = mutableListOf<Rect>()
        for (y in 0 until h) {
            var left = w; var right = 0
            for (x in 0 until w) if (ink[y * w + x] != 0.toByte()) {
                left = minOf(x, left); right = x + 1
            }
            if (left >= right) continue
            val last = lines.lastOrNull()
            if (last == null || y > last.bottom) lines += Rect(left, y, right, y + 1)
            else last.union(left, y, right, y + 1)
        }
        // A dot or accent may sit above its letter. Keep it with the nearest full-height band,
        // but do not merge two full lines just because only one clear row separates them.
        var i = 0
        while (i < lines.lastIndex) {
            val a = lines[i]
            val b = lines[i + 1]
            val larger = maxOf(a.height(), b.height())
            if (minOf(a.height(), b.height()) * 3 <= larger && b.top - a.bottom <= larger / 2) {
                a.union(b)
                lines.removeAt(i + 1)
                if (i > 0) i--
            } else i++
        }
        if (lines.isEmpty()) throw IOException("Could not separate the letters. Select the text more closely.")
        return lines
    }

    private fun ambiguousLines(): Nothing = throw IOException("Use Manual Box to select the line you want to replace.")

    private fun changedLines(before: List<String>, after: List<String>, visible: Int): IntRange {
        if (before.size != visible || after.size != visible) ambiguousLines()
        val first = before.indices.firstOrNull { before[it] != after[it] } ?: return before.indices
        val last = before.indices.last { before[it] != after[it] }
        val changed = first..last
        // One editable text layer cannot span two edits on opposite sides of an untouched line.
        if (changed.any { before[it] == after[it] }) ambiguousLines()
        return changed
    }

    private fun alignment(lines: List<Rect>): String {
        if (lines.size == 1) return "center"
        fun spread(value: (Rect) -> Int) = lines.maxOf(value) - lines.minOf(value)
        val left = spread { it.left }; val middle = spread { it.centerX() }; val right = spread { it.right }
        return when {
            left < middle && left < right -> "left"
            right < middle -> "right"
            else -> "center"
        }
    }

    private fun medianWidth(widths: IntArray, count: Int): Float {
        widths.sort(0, count)
        return (widths[(count - 1) / 2] + widths[count / 2]) * 0.5f
    }

    /** Only distinct letter widths give useful evidence of condensation. A row of I's or joined script does not. */
    private fun glyphAspect(columns: BooleanArray, height: Int): Float? {
        val widths = IntArray((columns.size + 1) / 2)
        var glyphs = 0; var width = 0
        var narrowest = Int.MAX_VALUE; var widest = 0
        for (x in 0..columns.size) {
            if (x < columns.size && columns[x]) { width++; continue }
            if (width == 0) continue
            widths[glyphs++] = width
            narrowest = minOf(narrowest, width); widest = maxOf(widest, width)
            width = 0
        }
        if (glyphs < 3 || widest - narrowest < maxOf(2, height / 10) || widest > height * 2) return null
        // Occasional I's and narrow punctuation should not make a wide font look condensed.
        return medianWidth(widths, glyphs) / height
    }

    /** Fit the actual ink, not the size of a loose selection. Manual selections have no transcription. */
    private fun textLayer(context: Context, letters: LetterMask, oriented: Triple<ByteArray, Int, Int>,
                          lines: List<Rect>, work: NormalizedRect, base: BaseTransform,
                          selection: NormalizedRect, old: String?, replacement: String, align: String): MemeEditLayer.Text {
        val (ink, w, h) = oriented
        val bounds = Rect(lines.first()).apply { for (i in 1 until lines.size) union(lines[i]) }
        val line = lines.maxBy { it.width() }
        val originalLine = old?.lineSequence()?.elementAt(lines.indexOf(line))?.takeIf { it.isNotBlank() }
        val occupiedColumns = if (originalLine == null) BooleanArray(line.width()) else null
        var count = 0
        var runs = 0
        for (y in line.top until line.bottom) for (x in line.left until line.right) {
            if (ink[y * w + x] == 0.toByte()) continue
            count++
            if (occupiedColumns != null) occupiedColumns[x - line.left] = true
            if (x == line.left || ink[y * w + x - 1] == 0.toByte()) runs++
        }
        val weight = count.toFloat() / runs / line.height()
        val aspect = line.width().toFloat() / line.height()
        // An unknown word has no meaningful total width. Separate letter bodies can still
        // distinguish condensed and wide faces whose strokes happen to have the same thickness.
        val manualAspect = occupiedColumns?.let { glyphAspect(it, line.height()) }
        val shape = LetteringMetrics.measure(ink, w, line)
        val sample = originalLine ?: "HAMBURGEFONTS"
        val widths = if (manualAspect != null) IntArray(sample.length) else null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 100f }
        val measured = Rect()
        val path = Path()
        val clip = Region()
        val glyphs = Region()
        val span = Rect()
        var best = Float.MAX_VALUE
        var preset = "plain"
        var capHeight = 0
        for (key in fontPresets) {
            val spec = TextLayoutBridge.getPresetSpec(key)
            paint.typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight)
            paint.getTextBounds(sample, 0, sample.length, measured)
            if (measured.height() == 0) continue
            val referenceStem = if (originalLine == null && shape != null) LetteringMetrics.manualStem(key, paint, sample) else null
            var difference = if (referenceStem != null && shape != null) {
                abs(referenceStem * shape.height / shape.stem - 1f)
            } else {
                val stroke = strokeWidth(paint, sample, measured, path, clip, glyphs, span)
                if (stroke == 0f) continue
                abs(stroke / weight - 1f)
            }
            if (originalLine != null) {
                val spacedWidth = measured.width() + 100f * spec.letterSpacingEm * (sample.length - 1)
                difference += abs(spacedWidth / measured.height() / aspect - 1f)
            } else if (manualAspect != null && widths != null) {
                for (i in sample.indices) {
                    paint.getTextBounds(sample, i, i + 1, span)
                    widths[i] = span.width()
                }
                difference += abs(medianWidth(widths, sample.length) / measured.height() / manualAspect - 1f)
            }
            if (difference < best) {
                best = difference
                preset = key
                capHeight = measured.height()
            }
        }
        if (capHeight == 0) throw IOException("Could not fit a font to the selected letters.")
        val nominalEm = line.height() * 100f / capHeight
        val words = fitLines(replacement, lines)
        val uppercase = old != null && ProjectCore.looksAllCaps(old)
        val spec = TextLayoutBridge.getPresetSpec(preset)
        paint.typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight)
        val lettering = if (originalLine != null || manualAspect != null)
            LetteringMetrics.fit(context, shape, paint, nominalEm, sample, originalLine != null, spec) else null
        val em = if (lettering != null) paint.textSize else nominalEm
        val fittedSize = em * work.height / h
        val fontSize = fittedSize.coerceIn(ProjectLimits.MIN_FONT_SIZE, ProjectLimits.MAX_FONT_SIZE)
        // A small caption on a tall image can still extend all the way across the picture.
        // Keep the slider in range and scale only the font, never its wrapping box.
        val sizeScale = fittedSize / fontSize
        paint.textScaleX = lettering?.widthScale ?: 1f
        paint.textSize = em
        val display = if (uppercase) words.uppercase() else words
        // A tight OCR box fits the OLD words. Keep a longer replacement on its line if the canvas has room.
        val needed = display.lineSequence().maxOf {
            paint.measureText(it) + em * paint.textScaleX * (lettering?.spacingEm ?: spec.letterSpacingEm) *
                (it.length - 1).coerceAtLeast(0) + 2f
        } * work.width / w
        val width = maxOf(selection.width, needed).coerceIn(ProjectLimits.MIN_TEXT_WRAP_WIDTH, ProjectLimits.MAX_TEXT_WRAP_WIDTH)
        val halfWidth = width / 2
        val cx = when (align) {
            "left" -> work.x + bounds.left * work.width / w + halfWidth
            "right" -> work.x + bounds.right * work.width / w - halfWidth
            else -> work.x + bounds.exactCenterX() * work.width / w
        }.coerceIn(halfWidth, 1f - halfWidth)
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
            uppercase = uppercase, shadow = shadow, lettering = lettering, fittedSizeScale = sizeScale)
        return MemeEditLayer.Text("text_${System.nanoTime()}", words, width, fontSize, style,
            keyframes = listOf(TransformKeyframe(center = NormalizedPoint(cx, work.y + bounds.exactCenterY() * work.height / h))))
    }
}
