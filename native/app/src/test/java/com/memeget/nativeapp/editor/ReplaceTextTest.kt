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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.memeget.nativeapp.InferenceEngine
import com.memeget.nativeapp.LibraryFiles
import com.memeget.nativeapp.Meme
import com.memeget.nativeapp.NativeInferenceEngine
import com.memeget.nativeapp.SqliteLibraryStore
import com.memeget.nativeapp.TextRegion
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
        val text = label as MemeEditLayer.Text
        assertEquals("Hi", text.text)
        assertEquals("#FFFF00", text.style.color)
        assertNull(text.style.backgroundColor)
    }

    /** The first detected box is used immediately, without tapping the region list. */
    @Test fun replaceKeepsTheFillAndOutlineOfTheDetectedRegion() {
        openStudio(twoWordImage(), listOf(REGION_A, REGION_B))
        autoDetect()
        assertReplacementStyle(replace("Hello"), hex(FILL_GREEN), hex(OUTLINE_RED))
    }

    /** A tap on a different region must use that region's colours, not the colours of the first box. */
    @Test fun replaceUsesTheStyleOfTheRegionTheUserTaps() {
        openStudio(twoWordImage(), listOf(REGION_A, REGION_B))
        autoDetect()
        ui.onNodeWithText("NO").performScrollTo().performClick()
        assertReplacementStyle(replace("Ack"), hex(FILL_BLUE), hex(OUTLINE_YELLOW))
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
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE) { BACKGROUND }
        drawWord(pixels, A_LEFT, FILL_GREEN, OUTLINE_RED, shadow = true)
        openStudio(png("shadow.png", IMAGE_SIZE, IMAGE_SIZE, pixels), listOf(REGION_A))
        autoDetect()
        val text = replace("Hello").layers.last() as MemeEditLayer.Text
        val shadow = text.style.toJson().optJSONObject("shadow")
        assertNotNull("The replacement lost its offset shadow", shadow)
        assertEquals(hex(OUTLINE_RED), shadow!!.getString("color"))
        val em = text.fontSize * IMAGE_SIZE
        assertEquals(4f, shadow.getDouble("xEm").toFloat() * em, 1f)
        assertEquals(3f, shadow.getDouble("yEm").toFloat() * em, 1f)
        assertEquals(0f, text.style.outlineScale, 0f)
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    private fun assertReplacementStyle(project: MemeEditProject, fill: String, outline: String) {
        assertEquals("Replace must add one repair and one text layer", 2, project.layers.size)
        val cover = project.layers[0] as MemeEditLayer.Cover
        val text = project.layers[1] as MemeEditLayer.Text
        assertEquals("Fill colour read from the selected letters", fill, text.style.color)
        assertEquals("Outline colour read from the selected letters", outline, text.style.outlineColor)
        assertTrue("The source outline must become a drawn outline", text.style.outlineScale > 0f)
        val sourceHeight = project.source.height.toFloat()
        val scale = sourceHeight / IMAGE_SIZE.toFloat()
        // The renderer centres its stroke on the edge, then paints the fill over the inside half.
        val strokePx = text.fontSize * sourceHeight * text.style.outlineScale
        val expectedPx = 2f * OUTLINE_WIDTH * scale
        assertTrue(
            "Outline width ${strokePx}px is not physically plausible for a ${OUTLINE_WIDTH}px source outline at ${scale}x",
            strokePx in expectedPx - 2f..expectedPx + 2f
        )
        assertNull("The replaced text must not sit on a background colour", text.style.backgroundColor)
        assertEquals("The repair must remove the old letters, not paint a solid backing over them", "remove", cover.mode)
    }

    private fun openStudio(source: File, regions: List<TextRegion> = emptyList()) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        uri = Uri.fromFile(source).toString()
        val meme = Meme(1, uri, source.name, "image")
        session = "session_${meme.id}_${UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8))}"
        val files = LibraryFiles(activity, SqliteLibraryStore(activity))
        engine = NativeInferenceEngine(activity)
        val fixedOcr = object : InferenceEngine by engine {
            override suspend fun recognize(uri: String) = regions
        }
        activity.setContent { RemixStudio(meme, fixedOcr, files, { closed = true }, {}) }
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
        ui.onNodeWithContentDescription("Close").performClick()
        ui.onNodeWithText("Keep Draft & Exit").performClick()
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
    private fun twoWordImage(): File {
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE) { BACKGROUND }
        drawWord(pixels, left = A_LEFT, fill = FILL_GREEN, outline = OUTLINE_RED)
        drawWord(pixels, left = B_LEFT, fill = FILL_BLUE, outline = OUTLINE_YELLOW)
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
