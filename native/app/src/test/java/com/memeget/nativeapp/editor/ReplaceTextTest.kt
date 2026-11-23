package com.memeget.nativeapp.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.memeget.nativeapp.InferenceEngine
import com.memeget.nativeapp.LibraryFiles
import com.memeget.nativeapp.Meme
import com.memeget.nativeapp.NativeInferenceEngine
import com.memeget.nativeapp.SqliteLibraryStore
import com.memeget.nativeapp.TextRegion
import expo.modules.memegetbg.MemeImageRenderer
import expo.modules.memegetbg.MemeTextLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** The host drives the real editor and reads its saved project. Only the OCR boxes are supplied by the test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w600dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplaceTextTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var engine: NativeInferenceEngine
    private lateinit var uri: String
    private lateinit var session: String
    private var closed = false
    private val activity get() = controller.get()

    @After fun close() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        if (::engine.isInitialized) engine.close()
    }

    @Test fun movingSizeOverridesTheInferredSizeOfAReplacement() {
        val normal = MemeEditLayer.Text(id = "caption", text = "HH", style = TextStyle(
            preset = "plain", color = "#FFFFFF", outlineScale = 0f, uppercase = false))
        val source = MemeEditSource("file://caption.png", "caption.png", MediaEditKind.IMAGE, IMAGE_SIZE, IMAGE_SIZE)
        val project = ProjectCore.createDefaultImageProject(source)
        val selected = mutableStateOf(normal)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.setContent { TextInspectorPanel(selected.value, { selected.value = it }, {}, {}, {}) }

        fun renderedHeight(layer: MemeEditLayer.Text): Int {
            val bitmap = renderTextOnly(project, layer)
            try {
                val row = IntArray(bitmap.width)
                return (0 until bitmap.height).count { y ->
                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    row.any { samePigment(it, Color.WHITE) }
                }
            } finally { bitmap.recycle() }
        }
        val requested = 0.10f
        val expected = renderedHeight(normal.copy(fontSize = requested))
        assertTrue("The reference must contain visible letters", expected > 20)
        for ((baseSize, fit) in listOf(ProjectLimits.MIN_FONT_SIZE to 0.5f, ProjectLimits.MAX_FONT_SIZE to 1.7f)) {
            // Restore a fitted caption as the editor does when reopening a draft.
            val style = TextStyle.fromJson(normal.style.toJson().put("fittedSizeScale", fit.toDouble()))
            ui.runOnIdle { selected.value = normal.copy(fontSize = baseSize, style = style) }
            val sizeSlider = ui.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(baseSize, ProjectLimits.MIN_FONT_SIZE..ProjectLimits.MAX_FONT_SIZE)))
            sizeSlider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { set -> set(requested) }
            ui.onNodeWithText("10%").assertExists()
            val edited = ui.runOnIdle { selected.value }
            assertEquals("An explicit 10% size must render at 10%, not at the retained $fit scale",
                expected, renderedHeight(edited))
        }
    }

    @Test fun replaceCannotLeaveOnlyTheRepairAtTheLayerLimit() {
        val source = MemeEditSource("file://source.png", "source.png", MediaEditKind.IMAGE, 100, 100)
        val repair = MemeEditLayer.Cover(id = "repair", rect = NormalizedRect.Full, mode = "remove")
        val text = MemeEditLayer.Text(id = "text", text = "Replacement")
        val existing = List(ProjectLimits.MAX_LAYERS - 1) { text.copy(id = "existing_$it") }
        val initial = ProjectCore.createDefaultImageProject(source).copy(layers = existing)
        val add = ProjectAction.AddLayers(listOf(repair, text))
        val history = createProjectHistory(initial)
        val rejected = applyProjectAction(history, add)
        assertFalse("Replace must not leave only the repair at the layer limit", repair in rejected.present.layers)
        assertEquals(existing, rejected.present.layers)
        assertFalse(canUndo(rejected))
        val available = createProjectHistory(initial.copy(layers = existing.dropLast(1)))
        val applied = applyProjectAction(available, add)
        assertEquals(listOf(repair, text), applied.present.layers.takeLast(2))
        assertEquals(available.present, undoProjectHistory(applied).present)
    }

    @Test fun replaceRepairsLettersButNotTheGapBetweenThem() {
        openStudio(twoLetterImage())
        ui.onNodeWithText("Manual Box").performClick()
        val (repair, label) = replace("Hi").layers
        val cover = repair as MemeEditLayer.Cover
        assertEquals("Replace must not paint the selection solid", "remove", cover.mode)
        val (mask, w, h) = LogoMask.loadOriented(activity, checkNotNull(cover.maskUri), BaseTransform.Default)
        fun masked(x: Int, y: Int): Boolean {
            val mx = (((x + 0.5f) / 200 - cover.rect.x) / cover.rect.width * w).toInt()
            val my = (((y + 0.5f) / 100 - cover.rect.y) / cover.rect.height * h).toInt()
            return mx in 0 until w && my in 0 until h && mask[my * w + mx] != 0.toByte()
        }
        assertTrue(masked(60, 27))
        assertTrue(masked(140, 27))
        assertFalse(masked(100, 27))
        assertTrue("The repair mask must be an actual letter shape, not a filled rectangle", mask.any { it == 0.toByte() })
        assertFalse("The repair must protect the background beside the letters", masked(50, 25))
        val text = label as MemeEditLayer.Text
        assertEquals("Hi", text.text)
        assertEquals("#FFFF00", text.style.color)
        assertNull(text.style.backgroundColor)
        assertEquals("The smooth background beside the yellow letters has no outline", 0f, text.style.outlineScale, 0f)
        assertNull("A gradient is not an offset shadow", text.style.shadow)
        assertFalse("Keep the gradient three pixels to the left of the first letter", masked(55, 27))
    }

    /** The first detected box is used immediately, without tapping the region list. */
    @Test fun replaceKeepsTheFillAndOutlineOfTheDetectedRegion() {
        openStudio(twoWordImage(), listOf(REGION_A, REGION_B))
        autoDetect()
        assertReplacementStyle(replace("Hello"), hex(FILL_GREEN), hex(OUTLINE_RED), A_LEFT)
    }

    /** A tap on a different region must use that region's colours, not the colours of the first box. */
    @Test fun replaceUsesTheStyleOfTheRegionTheUserTaps() {
        openStudio(twoWordImage(), listOf(REGION_A, REGION_B))
        autoDetect()
        ui.onNodeWithText("NO").performScrollTo().performClick()
        assertReplacementStyle(replace("Ack"), hex(FILL_BLUE), hex(OUTLINE_YELLOW), B_LEFT)
    }

    /** Empty and whitespace-only submissions in Manual Box must be inert: no repair, no invisible text layer. */
    @Test fun replaceIgnoresAnEmptyManualReplacement() = assertBlankManualReplacement("")

    @Test fun replaceIgnoresAWhitespaceManualReplacement() = assertBlankManualReplacement("   ")

    /** The editor refuses a replace that needs two layers when only one slot is free. */
    @Test fun replaceRefusesWhenTwoFreeSlotsAreNotAvailable() {
        val source = twoLetterImage()
        val lookedForModel = AtomicBoolean()
        openStudio(source, beforeInpaint = { lookedForModel.set(true) }, seedProject = { uri ->
            val seeded = ProjectCore.createDefaultImageProject(
                MemeEditSource(uri, source.name, MediaEditKind.IMAGE, 200, 100)
            )
            seeded.copy(layers = List(ProjectLimits.MAX_LAYERS - 1) {
                MemeEditLayer.Text(id = "seed_$it", text = "seed")
            })
        })
        ui.onNodeWithText("Manual Box").performClick()
        ui.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Hi")
        ui.onNodeWithText("Replace").performScrollTo().performClick()
        waitForReplaceButton()
        assertFalse("Out of layer slots: the editor must not start the repair", lookedForModel.get())
        ui.onNodeWithText("two free layers", substring = true).assertExists()
        val project = closeAndRestore()
        assertEquals(
            "A refused replace must not add a repair or a text layer",
            ProjectLimits.MAX_LAYERS - 1,
            project.layers.size
        )
        assertTrue("The refused replace must leave the existing text layers untouched", project.layers.all { it is MemeEditLayer.Text })
    }

    /**
     * A different edit landing while the repair is still being built must win. The latch holds the fill-model
     * lookup, a real Cover tap adds a layer, then the lookup is released.
     */
    @Test fun replaceRefusesToApplyToAChangedPicture() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        openStudio(twoLetterImage(), beforeInpaint = {
            entered.countDown()
            assertTrue("The controlled fill-model latch timed out", release.await(30, TimeUnit.SECONDS))
        })
        ui.onNodeWithText("Manual Box").performClick()
        val assets = File(activity.filesDir, "editor_assets")
        val existing = assets.listFiles().orEmpty().toSet()
        ui.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Hi")
        ui.onNodeWithText("Replace").performScrollTo().performClick()
        lateinit var unappliedMask: File
        try {
            ui.waitUntil(15_000) { entered.count == 0L }
            unappliedMask = assets.listFiles().orEmpty().single { it.name.startsWith("mask_") && it !in existing }
            ui.onNodeWithText("Cover").performScrollTo().performClick()
        } finally {
            release.countDown()
        }
        waitForReplaceButton()
        val offeredReselection = ui.onAllNodes(hasText("Select the text again", substring = true)).fetchSemanticsNodes().isNotEmpty()
        val project = closeAndRestore()
        assertEquals("A stale replacement must not add its repair or text", 1, project.layers.size)
        assertEquals("Only the edit made while replacing must remain", "solid", (project.layers[0] as MemeEditLayer.Cover).mode)
        assertTrue("A stale edit needs a new selection", offeredReselection)
        assertFalse("Do not keep the discarded repair in app storage", unappliedMask.exists())
    }

    @Test fun replaceKeepsBoldStrokesWhenWordWidthsMatch() {
        val context = RuntimeEnvironment.getApplication()
        val original = "TYPE"
        val bounds = Rect()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 80f; color = Color.BLACK }
        fun aspect(weight: Int): Float {
            paint.typeface = MemeTextLayout.weightedTypeface(context, "NotoSans", weight)
            paint.getTextBounds(original, 0, original.length, bounds)
            return bounds.width().toFloat() / bounds.height()
        }
        // A bold source with regular proportions: width and height alone cannot tell the weights apart.
        paint.textScaleX = aspect(400) / aspect(700)
        paint.getTextBounds(original, 0, original.length, bounds)
        val w = 400
        val h = 160
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val source = File.createTempFile("bold_text", ".png", context.cacheDir)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText(original, 32f, 110f, paint)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        bounds.offset(32, 110)
        bounds.inset(-3, -3)
        val region = TextRegion(original, 1f, bounds.left.toFloat() / w, bounds.top.toFloat() / h,
            bounds.width().toFloat() / w, bounds.height().toFloat() / h)
        openStudio(source, listOf(region))
        autoDetect()
        val text = replace("NEW").layers.last() as MemeEditLayer.Text
        assertEquals("The replacement must keep bold strokes", 700, TextLayoutBridge.getPresetSpec(text.style.preset).fontWeight)
    }

    @Test fun replaceKeepsAnOffsetShadowInsteadOfDrawingAnOutline() {
        openStudio(twoWordImage(shadow = true), listOf(REGION_A))
        autoDetect()
        assertOffsetShadow(replace("Hello"), OUTLINE_RED, A_LEFT)
    }

    /** A tap on the second region must read that region's shadow, not the first box's shadow. */
    @Test fun replaceUsesTheOffsetShadowOfTheRegionTheUserTaps() {
        openStudio(twoWordImage(shadow = true), listOf(REGION_A, REGION_B))
        autoDetect()
        ui.onNodeWithText("NO").performScrollTo().performClick()
        assertOffsetShadow(replace("Ack"), OUTLINE_YELLOW, B_LEFT)
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    private fun assertBlankManualReplacement(value: String) {
        openStudio(twoLetterImage())
        ui.onNodeWithText("Manual Box").performClick()
        ui.onNodeWithText("Cover").performScrollTo().performClick()
        ui.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(value)
        val enabled = ui.onAllNodes(hasText("Replace") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        ui.onNodeWithText("Replace").performScrollTo().performTouchInput { click() }
        waitForReplaceButton()
        val project = closeAndRestore()
        assertEquals("A blank replacement must not add a repair or a text layer", 1, project.layers.size)
        assertFalse("Replace must be disabled for blank input", enabled)
        assertEquals("The edit made before the blank submission must survive", "solid",
            (project.layers[0] as MemeEditLayer.Cover).mode)
    }

    private fun waitForReplaceButton() {
        ui.waitUntil(15_000) { ui.onAllNodes(hasText("Replace")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun renderedFontSize(project: MemeEditProject): Float {
        val spec = RenderPlanBuilder.buildImageRenderPlan(project).getJSONArray("layers").getJSONObject(1).getJSONObject("spec")
        return (spec.getJSONObject("canvas").getDouble("fontSizeDip") * spec.getJSONObject("transform").getDouble("scale")).toFloat()
    }

    private fun assertReplacementStyle(project: MemeEditProject, fill: String, outline: String, wordLeft: Int) {
        assertEquals("Replace must add one repair and one text layer", 2, project.layers.size)
        val cover = project.layers[0] as MemeEditLayer.Cover
        val text = project.layers[1] as MemeEditLayer.Text
        assertEquals("Fill colour read from the selected letters", fill, text.style.color)
        assertEquals("Outline colour read from the selected letters", outline, text.style.outlineColor)
        assertTrue("The source outline must become a drawn outline", text.style.outlineScale > 0f)
        val sourceHeight = project.source.height.toFloat()
        val scale = sourceHeight / IMAGE_SIZE.toFloat()
        // The renderer centres its stroke on the edge, then paints the fill over the inside half.
        val strokePx = renderedFontSize(project) * text.style.outlineScale
        val expectedPx = 2f * OUTLINE_WIDTH * scale
        assertTrue(
            "Outline width ${strokePx}px is not physically plausible for a ${OUTLINE_WIDTH}px source outline at ${scale}x",
            strokePx in expectedPx - 2f..expectedPx + 2f
        )
        assertNull("The replaced text must not sit on a background colour", text.style.backgroundColor)
        assertEquals("The repair must remove the old letters, not paint a solid backing over them", "remove", cover.mode)
        assertCoverMask(project, wordLeft, shadowed = false)
        assertTextCenteredOverWord(project, wordLeft)
    }

    /** The editor must turn the source shadow into a drawn shadow at the same offset, never into an outline. */
    private fun assertOffsetShadow(project: MemeEditProject, edge: Int, wordLeft: Int) {
        assertEquals("Replace must add one repair and one text layer", 2, project.layers.size)
        val text = project.layers[1] as MemeEditLayer.Text
        val shadow = checkNotNull(text.style.shadow) { "The replacement lost its offset shadow" }
        assertEquals("Fill colour read from the selected letters",
            hex(if (wordLeft == B_LEFT) FILL_BLUE else FILL_GREEN), text.style.color)
        assertEquals("Shadow colour read from the selected letters", hex(edge), shadow.color)
        val em = renderedFontSize(project)
        assertEquals(4f, shadow.xEm * em, 1f)
        assertEquals(3f, shadow.yEm * em, 1f)
        assertEquals("A shadowed source must not become an outline", 0f, text.style.outlineScale, 0f)
        assertCoverMask(project, wordLeft, shadowed = true)
        assertTextCenteredOverWord(project, wordLeft)
        assertDrawnOffsetShadow(project)
    }

    /**
     * The saved repair mask must exist, cover the letter ink (and shadow), and leave the surrounding picture alone.
     * The coordinates are source pixels of the generated 600 px test picture, mapped through the layer's rect.
     */
    private fun assertCoverMask(project: MemeEditProject, wordLeft: Int, shadowed: Boolean) {
        val cover = project.layers[0] as MemeEditLayer.Cover
        assertEquals("The repair must remove the old letters", "remove", cover.mode)
        val maskUri = cover.maskUri
        assertNotNull("The repair must carry a real mask", maskUri)
        val (mask, w, h) = LogoMask.loadOriented(activity, maskUri!!, project.base)
        var painted = 0
        for (value in mask) if (value != 0.toByte()) painted++
        assertTrue("The repair mask must not be empty", painted > 0)
        assertTrue("The repair mask must be a letter shape, not a filled rectangle", painted < w * h)
        fun masked(x: Int, y: Int): Boolean {
            val mx = (((x + 0.5f) / IMAGE_SIZE - cover.rect.x) / cover.rect.width * w).toInt()
            val my = (((y + 0.5f) / IMAGE_SIZE - cover.rect.y) / cover.rect.height * h).toInt()
            return mx in 0 until w && my in 0 until h && mask[my * w + mx] != 0.toByte()
        }
        for (stem in listOf(0, STEM + GAP)) {
            assertTrue("The repair must remove the letter at x=${wordLeft + stem}",
                masked(wordLeft + stem + STEM / 2, WORD_TOP + WORD_HEIGHT / 2))
        }
        assertTrue("The repair must remove the letter's crossbar",
            masked(wordLeft + STEM + GAP / 2, WORD_TOP + WORD_HEIGHT / 2))
        assertFalse("The repair must protect the background above the crossbar",
            masked(wordLeft + STEM + GAP / 2, WORD_TOP + 4))
        assertFalse("The repair must protect the background beside the word",
            masked(wordLeft - 10, WORD_TOP + WORD_HEIGHT / 2))
        if (shadowed) {
            assertTrue("The repair must remove the source shadow right of the letters",
                masked(wordLeft + 2 * STEM + GAP + 2, WORD_TOP + WORD_HEIGHT / 2))
            assertTrue("The repair must remove the source shadow below the letters",
                masked(wordLeft + STEM / 2, WORD_TOP + WORD_HEIGHT - 1 + 3))
        } else {
            assertTrue("The repair must reach the outside of the source outline",
                masked(wordLeft - OUTLINE_WIDTH, WORD_TOP + WORD_HEIGHT / 2))
        }
    }

    /** A tap on the second box must move the replacement, not merely recolour the first box's replacement. */
    private fun assertTextCenteredOverWord(project: MemeEditProject, wordLeft: Int) {
        val center = (project.layers[1] as MemeEditLayer.Text).keyframes.first().center
        val expectedX = (wordLeft + (2 * STEM + GAP) / 2f) / IMAGE_SIZE
        val expectedY = (WORD_TOP + WORD_HEIGHT / 2f) / IMAGE_SIZE
        // The wrap box of a longer replacement may be clamped near the edge; a short one lands on the ink centre.
        val tolerance = if (wordLeft == B_LEFT) 0.05f else 0.2f
        assertTrue(
            "The replacement must sit on the ${if (wordLeft == B_LEFT) "tapped" else "first"} word " +
                "(centre x=${center.x}, expected $expectedX)",
            abs(center.x - expectedX) < tolerance
        )
        assertTrue("The replacement must sit on the word's line (centre y=${center.y})", abs(center.y - expectedY) < 0.05f)
    }

    /**
     * The real image renderer, not the editor preview: same replacement text with and without its shadow on a flat
     * background. A visible shadow must be the glyph shifted by the saved offset; the no-shadow copy must not show it.
     * The repair cover is left out because the host has no ARM image-repair library.
     */
    private fun assertDrawnOffsetShadow(project: MemeEditProject) {
        val text = project.layers[1] as MemeEditLayer.Text
        val shadow = checkNotNull(text.style.shadow) { "The replacement lost its offset shadow" }
        val fillColor = Color.parseColor(text.style.color)
        val shadowColor = Color.parseColor(shadow.color)
        val withShadow = renderTextOnly(project, text)
        val withoutShadow = renderTextOnly(project, text.copy(style = text.style.copy(shadow = null)))
        try {
            val size = IMAGE_SIZE * IMAGE_SIZE
            val painted = IntArray(size)
            val plain = IntArray(size)
            withShadow.getPixels(painted, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
            withoutShadow.getPixels(plain, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
            val em = renderedFontSize(project)
            val dx = (shadow.xEm * em).roundToInt()
            val dy = (shadow.yEm * em).roundToInt()
            var shadowPixels = 0
            var supported = 0
            var fillPixels = 0
            var plainFillPixels = 0
            var background = 0
            for (y in 0 until IMAGE_SIZE) for (x in 0 until IMAGE_SIZE) {
                val i = y * IMAGE_SIZE + x
                val pixel = painted[i]
                if (samePigment(pixel, shadowColor)) {
                    shadowPixels++
                    if (hasPigmentNear(plain, x - dx, y - dy, fillColor)) supported++
                    assertFalse("A shadow must not be drawn when the style has none", samePigment(plain[i], shadowColor))
                }
                if (samePigment(pixel, fillColor)) fillPixels++
                if (samePigment(plain[i], fillColor)) plainFillPixels++
                if (pixel == BACKGROUND) background++
            }
            assertTrue("The replacement's offset shadow was not drawn", shadowPixels > 100)
            assertTrue("The drawn shadow is not the source shadow offset by ($dx,$dy)", supported >= shadowPixels * 7 / 10)
            assertTrue("The same replacement text must be drawn with and without the shadow", fillPixels > 100)
            assertTrue(
                "Turning the shadow off must not change the glyphs ($fillPixels vs $plainFillPixels)",
                abs(fillPixels - plainFillPixels) <= plainFillPixels / 20 + 2
            )
            assertTrue("The picture's background must stay behind the text", background > size * 8 / 10)
            assertEquals("The background corner must survive the text layer", BACKGROUND, painted[5 * IMAGE_SIZE + 5])
            assertEquals("The background corner must survive the text layer", BACKGROUND, plain[5 * IMAGE_SIZE + 5])
        } finally {
            withShadow.recycle()
            withoutShadow.recycle()
        }
    }

    private fun renderTextOnly(project: MemeEditProject, text: MemeEditLayer.Text): Bitmap {
        val flat = project.copy(
            layers = listOf(text),
            background = BackgroundSpec(mode = "solid", color = hex(BACKGROUND)),
            materializedSourceUri = null
        )
        return MemeImageRenderer.renderToBitmap(
            RuntimeEnvironment.getApplication(),
            RenderPlanBuilder.buildImageRenderPlan(flat).toString()
        )
    }

    private fun samePigment(pixel: Int, target: Int): Boolean =
        Color.alpha(pixel) > 200 &&
            abs(Color.red(pixel) - Color.red(target)) <= 40 &&
            abs(Color.green(pixel) - Color.green(target)) <= 40 &&
            abs(Color.blue(pixel) - Color.blue(target)) <= 40

    private fun hasPigmentNear(pixels: IntArray, x: Int, y: Int, target: Int): Boolean {
        for (dy in -2..2) for (dx in -2..2) {
            val px = x + dx
            val py = y + dy
            if (px in 0 until IMAGE_SIZE && py in 0 until IMAGE_SIZE &&
                samePigment(pixels[py * IMAGE_SIZE + px], target)) return true
        }
        return false
    }

    private fun openStudio(source: File, regions: List<TextRegion> = emptyList(),
                           beforeInpaint: suspend () -> Unit = {},
                           seedProject: ((String) -> MemeEditProject)? = null) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        uri = Uri.fromFile(source).toString()
        val meme = Meme(1, uri, source.name, "image")
        session = "session_${meme.id}_${UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8))}"
        if (seedProject != null) {
            check(runBlocking { DraftJournalStore.saveDraft(activity, session, seedProject(uri)) })
        }
        val files = LibraryFiles(activity, SqliteLibraryStore(activity))
        engine = NativeInferenceEngine(activity)
        val fixedOcr = object : InferenceEngine by engine {
            override suspend fun recognize(uri: String) = regions
            override suspend fun installedInpaintModel(): File? {
                beforeInpaint()
                return engine.installedInpaintModel()
            }
        }
        activity.setContent { RemixStudio(meme, fixedOcr, files, { closed = true }, {}) }
        if (seedProject != null) {
            // The seeded draft is offered through the real restore prompt.
            ui.waitUntil(10_000) { ui.onAllNodes(hasText("Restore")).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Restore").performClick()
        }
        ui.waitUntil(10_000) { ui.onAllNodes(hasText("BEFORE")).fetchSemanticsNodes().isNotEmpty() }
        // Keep showing the original. The host does not have the Android ARM image-repair library.
        ui.onNodeWithText("BEFORE").performClick()
        ui.onNodeWithText("▨ Replace").performScrollTo().performClick()
    }

    private fun autoDetect() {
        ui.onNodeWithText("Auto Detect Text").performClick()
    }

    private fun replace(value: String): MemeEditProject {
        ui.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(value)
        ui.onNodeWithText("Replace").performScrollTo().performClick()
        ui.waitUntil(15_000) {
            ui.onAllNodes(hasContentDescription("Undo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        return closeAndRestore()
    }

    /** Closes through the editor's own exit path, then reads the saved project. */
    private fun closeAndRestore(): MemeEditProject {
        ui.onNodeWithContentDescription("Close").performClick()
        ui.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            closed || ui.onAllNodes(hasText("Keep Draft & Exit")).fetchSemanticsNodes().isNotEmpty()
        }
        if (!closed) ui.onNodeWithText("Keep Draft & Exit").performClick()
        ui.waitUntil(10_000) {
            // The journal writes on IO, then posts the close callback to Android's main looper.
            shadowOf(Looper.getMainLooper()).idle()
            closed
        }
        val saved = CoroutineScope(Dispatchers.IO).async { DraftJournalStore.restoreDraft(activity, session, uri).project }
        ui.waitUntil(10_000) { saved.isCompleted }
        return checkNotNull(runBlocking { saved.await() })
    }

    private fun twoLetterImage(): File {
        val width = 200
        val height = 100
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            if (i / width in 22..32 && (x in 58..63 || x in 138..143)) Color.YELLOW else Color.rgb(x, 50, 100)
        }
        return png("replace_letters_${UUID.randomUUID()}.png", width, height, pixels)
    }

    /** Two H-shaped words of exactly known fill and outline colours, far enough apart to be separate selections. */
    private fun twoWordImage(shadow: Boolean = false): File {
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE) { BACKGROUND }
        drawWord(pixels, left = A_LEFT, fill = FILL_GREEN, outline = OUTLINE_RED, shadow = shadow)
        drawWord(pixels, left = B_LEFT, fill = FILL_BLUE, outline = OUTLINE_YELLOW, shadow = shadow)
        return png("replace_style_${UUID.randomUUID()}.png", IMAGE_SIZE, IMAGE_SIZE, pixels)
    }

    private fun drawWord(pixels: IntArray, left: Int, fill: Int, outline: Int, shadow: Boolean = false) {
        val shapes = listOf(
            intArrayOf(left, WORD_TOP, left + STEM, WORD_TOP + WORD_HEIGHT),
            intArrayOf(left + STEM + GAP, WORD_TOP, left + STEM + GAP + STEM, WORD_TOP + WORD_HEIGHT),
            intArrayOf(left + STEM, WORD_TOP + (WORD_HEIGHT - BAR) / 2, left + STEM + GAP,
                WORD_TOP + (WORD_HEIGHT + BAR) / 2)
        )
        for (shape in shapes) {
            val dx = if (shadow) 4 else -OUTLINE_WIDTH
            val dy = if (shadow) 3 else -OUTLINE_WIDTH
            for (y in shape[1] + dy until shape[3] + if (shadow) dy else OUTLINE_WIDTH) {
                for (x in shape[0] + dx until shape[2] + if (shadow) dx else OUTLINE_WIDTH) {
                    pixels[y * IMAGE_SIZE + x] = outline
                }
            }
        }
        for (shape in shapes) {
            for (y in shape[1] until shape[3]) {
                for (x in shape[0] until shape[2]) pixels[y * IMAGE_SIZE + x] = fill
            }
        }
    }

    private fun png(name: String, width: Int, height: Int, pixels: IntArray): File {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, name)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private companion object {
        const val IMAGE_SIZE = 600
        const val OUTLINE_WIDTH = 4
        const val WORD_TOP = 120
        const val WORD_HEIGHT = 72
        const val STEM = 16
        const val GAP = 64
        const val BAR = 16
        const val A_LEFT = 32
        const val B_LEFT = 400

        val BACKGROUND = Color.rgb(32, 32, 32)
        val FILL_GREEN = Color.rgb(0, 255, 0)
        val OUTLINE_RED = Color.rgb(255, 0, 0)
        val FILL_BLUE = Color.rgb(0, 0, 255)
        val OUTLINE_YELLOW = Color.rgb(255, 255, 0)

        val REGION_A = TextRegion("HI", 0.9f, A_LEFT / IMAGE_SIZE.toFloat(), WORD_TOP / IMAGE_SIZE.toFloat(),
            (2 * STEM + GAP) / IMAGE_SIZE.toFloat(), WORD_HEIGHT / IMAGE_SIZE.toFloat())
        val REGION_B = TextRegion("NO", 0.9f, B_LEFT / IMAGE_SIZE.toFloat(), WORD_TOP / IMAGE_SIZE.toFloat(),
            (2 * STEM + GAP) / IMAGE_SIZE.toFloat(), WORD_HEIGHT / IMAGE_SIZE.toFloat())
    }
}
