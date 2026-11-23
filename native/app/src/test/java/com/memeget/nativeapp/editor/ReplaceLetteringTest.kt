package com.memeget.nativeapp.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.memeget.nativeapp.InferenceEngine
import com.memeget.nativeapp.NativeInferenceEngine
import expo.modules.memegetbg.MemeTextLayout
import expo.modules.memegetbg.MemeImageRenderer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplaceLetteringTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private data class Caption(val picture: MemeEditProject, val selection: NormalizedRect, val rows: List<Rect>)

    private fun caption(preset: String, vararg words: String, gap: Int? = null,
                        w: Int = 640, h: Int = 400, em: Float = 64f): Caption {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val spec = TextLayoutBridge.getPresetSpec(preset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = em
            typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight)
        }
        val file = File.createTempFile("caption-", ".png", context.cacheDir)
        val rows = mutableListOf<Rect>()
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            for ((i, word) in words.withIndex()) {
                val bounds = Rect().apply { paint.getTextBounds(word, 0, word.length, this) }
                val baseline = if (gap == null || rows.isEmpty()) (em * 1.4f).toInt() + i * (em.toInt() + 2)
                    else rows.last().bottom + gap - bounds.top
                canvas.drawText(word, 40f, baseline.toFloat(), paint)
                bounds.offset(40, baseline)
                rows += bounds
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val box = Rect(rows.first()).apply { rows.drop(1).forEach { union(it) }; inset(-4, -4) }
        val source = MemeEditSource(Uri.fromFile(file).toString(), file.name, MediaEditKind.IMAGE, w, h)
        return Caption(ProjectCore.createDefaultImageProject(source), NormalizedRect(box.left.toFloat()/w, box.top.toFloat()/h,
            box.width().toFloat()/w, box.height().toFloat()/h), rows)
    }

    private fun replace(source: Caption, old: String?, new: String): Pair<MemeEditLayer.Cover, MemeEditLayer.Text> = runBlocking {
        val native = NativeInferenceEngine(context)
        val offline = object : InferenceEngine by native { override suspend fun installedInpaintModel(): File? = null }
        try { TextReplacement.layers(context, source.picture, source.selection, new, old, offline) }
        finally { native.close() }
    }

    @Test fun manualMatchesAntonAndItsCapHeightWithoutAnOcrTranscript() {
        val source = caption("impact", "TYPED")
        val text = replace(source, null, "MIX").second
        assertEquals("Manual selection must recognize heavy condensed lettering", "impact", text.style.preset)
        assertEquals("Size must reproduce the selected glyph height", 64f, text.fontSize * source.picture.source.height, 3f)
    }

    private fun externalCaption(resource: String, selection: Rect): Caption {
        val file = File.createTempFile("lettering-", ".png", context.cacheDir)
        checkNotNull(javaClass.getResourceAsStream(resource)).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val picture = MemeEditSource(Uri.fromFile(file).toString(), file.name, MediaEditKind.IMAGE, 720, 450)
        return Caption(ProjectCore.createDefaultImageProject(picture), NormalizedRect(
            selection.left / 720f, selection.top / 450f, selection.width() / 720f, selection.height() / 450f), emptyList())
    }

    @Test fun manualMatchesOutlinedAntonFromAnIndependentRenderer() {
        // Both external PNGs were drawn with FreeType, not with the Android typefaces used by Replace.
        val source = externalCaption("/outlined-anton.png", Rect(130, 179, 590, 271))
        val text = replace(source, null, "NEW TYPE").second
        assertEquals("Manual selection of the outlined caption must preserve its condensed face", "impact", text.style.preset)
        assertEquals("Fit the source cap height instead of the outline height", 72f, text.fontSize * 450, 4f)
    }

    @Test fun manualKeepsIndependentBoldSansDistinctFromCondensedLettering() {
        // Select LOW ONE, one clear row below KEEP TOP; both are bold sans at 64 px.
        val source = externalCaption("/outlined-bold-sans.png", Rect(144, 244, 576, 302))
        val text = replace(source, null, "NEW TYPE").second
        assertEquals("A wide bold caption must not become Anton", "subtitle", text.style.preset)
        assertEquals(64f, text.fontSize * 450, 4f)
    }

    @Test fun narrowLettersDoNotTurnWideBoldCaptionsIntoACondensedFont() {
        // Three independently drawn captions in the same face, with different proportions of narrow I's.
        for (bounds in listOf(Rect(96, 58, 498, 115), Rect(96, 189, 328, 244), Rect(96, 313, 252, 370))) {
            val source = externalCaption("/narrow-letter-rows.png", bounds)
            assertEquals("Keep the wide bold face at $bounds", "subtitle", replace(source, null, "NEW").second.style.preset)
        }
    }

    @Test fun manualKeepsTheRenderedWidthAndStrokeOfTheSharedWord() = checkSharedWord(null)

    @Test fun detectedTextKeepsTheRenderedWidthAndStrokeOfTheSharedWord() = checkSharedWord("OLD TYPE")

    @Test fun manualKeepsTheOriginalWhiteLetteringOnAGradient() =
        checkSharedWord(null, "/gradient-caption.png", Rect(144, 194, 576, 263), Color.rgb(250, 249, 244))

    @Test fun detectedTextKeepsTheOriginalGoldLetteringOnTexture() =
        checkSharedWord("OLD TYPE", "/texture-caption.png", Rect(201, 198, 519, 252), Color.rgb(255, 235, 145))

    private fun checkSharedWord(transcript: String?, resource: String = "/tracked-caption.png",
                                bounds: Rect = Rect(160, 185, 560, 267), fill: Int = Color.rgb(20, 20, 22)) {
        val source = externalCaption(resource, bounds)
        val initial = replace(source, transcript, "NEW TYPE").second
        val text = MemeEditLayer.fromJson(initial.toJson()) as MemeEditLayer.Text
        val original = BitmapFactory.decodeFile(Uri.parse(source.picture.source.uri).path!!)
        val edited = source.picture.copy(layers = listOf(text), background = BackgroundSpec(mode = "solid", color = "#FF00FF"))
        val rendered = MemeImageRenderer.renderToBitmap(context, RenderPlanBuilder.buildImageRenderPlan(edited).toString())
        try {
            val old = sharedWordDimensions(original, fill)
            val new = sharedWordDimensions(rendered, fill)
            fun relative(after: Int, before: Int) = (after.toFloat() / new.height) / (before.toFloat() / old.height)
            assertEquals("The cap height changed: $old -> $new", old.height.toFloat(), new.height.toFloat(), old.height * 0.10f)
            assertEquals("The shared word TYPE changed width: $old -> $new", 1f, relative(new.width, old.width), 0.10f)
            assertEquals("The T stem changed weight: $old -> $new", 1f, relative(new.stem, old.stem), 0.10f)
            assertEquals("The T crossbar changed weight: $old -> $new", 1f, relative(new.crossbar, old.crossbar), 0.10f)
        } finally { original.recycle(); rendered.recycle() }
    }

    private data class Word(val width: Int, val stem: Int, val height: Int, val crossbar: Int)

    private fun sharedWordDimensions(image: Bitmap, fill: Int): Word {
        val w = image.width; val h = image.height
        val pixels = IntArray(w * h).also { image.getPixels(it, 0, w, 0, 0, w, h) }
        fun ink(x: Int, y: Int): Boolean {
            val pixel = pixels[y * w + x]
            return Color.alpha(pixel) > 200 && kotlin.math.abs(Color.red(pixel) - Color.red(fill)) < 40 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(fill)) < 40 && kotlin.math.abs(Color.blue(pixel) - Color.blue(fill)) < 40
        }
        val columns = (0 until w).filter { x -> (0 until h).any { y -> ink(x, y) } }
        val space = (1 until columns.size).maxBy { columns[it] - columns[it - 1] }
        val t = columns[space]
        val mid = (0 until h).filter { y -> (t..columns.last()).any { x -> ink(x, y) } }.let { (it.first() + it.last()) / 2 }
        val left = (t..columns.last()).first { ink(it, mid) }
        val right = (left..columns.last()).first { !ink(it, mid) }
        val stemX = (left + right - 1) / 2
        val height = (0 until h).count { ink(stemX, it) }
        check(height > 10 && columns[space] - columns[space - 1] > 5)
        val armX = (t + left) / 2
        val crossbar = (0 until h).count { ink(armX, it) }
        check(crossbar in 1 until height / 2)
        return Word(columns.last() + 1 - t, right - left, height, crossbar)
    }

    @Test fun manualDistinguishesRegularAndBoldWideLettering() {
        for (preset in listOf("plain", "subtitle")) {
            val text = replace(caption(preset, "IMPACT"), null, "NEW TYPE").second
            assertEquals("A wider $preset source must not be mistaken for condensed lettering", preset, text.style.preset)
        }
    }

    @Test fun detectedRegularTextDoesNotBecomeBold() {
        val source = caption("plain", "TYPED")
        val text = replace(source, "TYPED", "MIX").second
        assertEquals("A regular source must not be replaced with a bold font", "plain", text.style.preset)
        assertEquals(64f, text.fontSize * source.picture.source.height, 3f)
    }

    @Test fun updatingOneGroupedOcrLinePreservesEveryLetterInTheUnchangedLine() {
        val source = caption("subtitle", "OLD", "KEEP TYPE")
        val (repair, text) = replace(source, "OLD\nKEEP TYPE", "NEW\nKEEP TYPE")
        assertEquals("Unchanged OCR text must stay in the original picture", "NEW", text.text)
        val (mask, w, h) = LogoMask.loadOriented(context, checkNotNull(repair.maskUri), BaseTransform.Default)
        fun masked(x: Int, y: Int): Boolean {
            val mx = (((x+0.5f)/source.picture.source.width-repair.rect.x)/repair.rect.width*w).toInt()
            val my = (((y+0.5f)/source.picture.source.height-repair.rect.y)/repair.rect.height*h).toInt()
            return mx in 0 until w && my in 0 until h && mask[my*w+mx].toInt()!=0
        }
        fun count(r: Rect) = (r.top until r.bottom).sumOf { y -> (r.left until r.right).count { x -> masked(x,y) } }
        assertTrue("Selected line must be repaired", count(source.rows[0]) > 100)
        assertEquals("The lower line must not be in the repair mask", 0, count(source.rows[1]))
        assertTrue("Replacement belongs on the top line", text.keyframes.single().center.y*source.picture.source.height < source.rows[1].top)
        assertEquals("Keep the alignment of the original two-line caption", "left", text.style.align)
        val left = (text.keyframes.single().center.x - text.width / 2f) * source.picture.source.width
        assertEquals("The edit must start at the selected word, not at the edge of the longer row", source.rows[0].left.toFloat(), left, 2f)
    }

    @Test fun ambiguousOcrBoxDoesNotEraseTwoLinesWhenTheUserEntersOne() {
        val source = caption("subtitle", "OLD", "KEEP TYPE")
        val err = assertThrows(IOException::class.java) { replace(source, "OLD KEEP TYPE", "NEW") }
        assertTrue("Explain how to target only the intended line", err.message.orEmpty().contains("Manual Box"))
    }

    @Test fun aBlankReplacementCannotCreateAnEraseLayer() {
        val source = caption("plain", "TYPED")
        val err = assertThrows(IOException::class.java) { replace(source, null, " \t") }
        assertTrue(err.message.orEmpty().contains("Enter replacement text"))
    }

    @Test fun closeFullHeightLinesAreNotTreatedAsOneWord() {
        val source = caption("plain", "TYPE", "TYPE", gap = 1)
        val err = assertThrows(IOException::class.java) { replace(source, "TYPE TYPE", "NEW") }
        assertTrue(err.message.orEmpty().contains("Manual Box"))
    }

    @Test fun smallAndLargeReplacementsKeepTheirVisibleSizeAfterFlippingThePicture() {
        for ((source, em, limit) in listOf(
            Triple(caption("plain", "HH", w = 360, h = 1440, em = 25f), 25f, ProjectLimits.MIN_FONT_SIZE),
            Triple(caption("plain", "HH", w = 600, h = 600, em = 230f), 230f, ProjectLimits.MAX_FONT_SIZE)
        )) {
            val picture = source.picture.source
            val text = replace(source, "HH", "HI").second
            assertEquals("The size must be within the slider limits before the flip", limit, text.fontSize, 0.0001f)
            val expected = source.rows.single().height()
            val height = paintedHeight(source, text)
            assertEquals("The visible $em px caption must fit the source outside the slider range",
                expected.toFloat(), height.toFloat(), maxOf(2f, expected * 0.06f))
            val flipped = ProjectCore.remapProjectLayers(listOf(text), BaseTransform.Default,
                BaseTransform(flipX = true), picture.width, picture.height).single() as MemeEditLayer.Text
            assertEquals("A flip must not change the apparent letter size", height, paintedHeight(source, flipped))
        }
    }

    private fun paintedHeight(source: Caption, text: MemeEditLayer.Text): Int {
        val project = source.picture.copy(layers = listOf(text), background = BackgroundSpec(mode = "solid", color = "#FFFFFF"))
        val image = MemeImageRenderer.renderToBitmap(context, RenderPlanBuilder.buildImageRenderPlan(project).toString())
        try {
            val row = IntArray(image.width)
            var top = image.height; var bottom = 0
            for (y in 0 until image.height) {
                image.getPixels(row, 0, image.width, 0, y, image.width, 1)
                if (row.any { Color.red(it) < 64 }) { top = minOf(top, y); bottom = y + 1 }
            }
            check(bottom > top) { "No replacement letters rendered" }
            return bottom - top
        } finally { image.recycle() }
    }

    @Test fun aSmallCaptionCanStillUseTheWholeLineAfterAFlip() {
        val old = "READ ALL OF THESE SMALL WORDS ON THIS LINE"
        val source = caption("plain", old, w = 1080, h = 1920, em = 40f)
        check(source.rows.single().width() in 730..1035)
        val text = replace(source, old, old.replace("THIS", "THAT")).second
        val flipped = ProjectCore.remapProjectLayers(listOf(text), BaseTransform.Default,
            BaseTransform(flipX = true), 1080, 1920).single() as MemeEditLayer.Text
        for (layer in listOf(text, flipped)) {
            assertEquals("A long line below the minimum slider size must not wrap",
                source.rows.single().height().toFloat(), paintedHeight(source, layer).toFloat(), 3f)
        }
    }

    @Test fun dottedLowercaseSentenceKeepsOneLineAndItsOriginalSize() {
        val source = caption("plain", "omg i win", w = 800, h = 600, em = 100f)
        val text = replace(source, "omg i win", "you lose").second
        assertEquals("A dot above i is not a line break", "you lose", text.text)
        assertEquals("A single line must not become right-aligned", "center", text.style.align)
        assertEquals(100f, text.fontSize * 600, 4f)
    }

    @Test fun dotsAboveTheSelectedLettersAreNotASecondLine() {
        val source = caption("plain", "jij")
        val text = replace(source, "jij", "mix").second
        assertEquals("mix", text.text)
        assertEquals("Measured height must include the dots", 64f, text.fontSize * source.picture.source.height, 4f)
    }
}
