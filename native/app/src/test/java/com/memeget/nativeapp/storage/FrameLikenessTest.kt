package com.memeget.nativeapp.storage

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Frames go through the same path as in the app: the model's input JPEGs, decoded and compared. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameLikenessTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun frame(name: String, quality: Int = 90, draw: Canvas.() -> Unit): String {
        val bitmap = Bitmap.createBitmap(448, 336, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).draw()
        val file = temporary.newFile("$name.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bitmap.recycle()
        return Uri.fromFile(file).toString()
    }

    /** A still picture: a light block on a dark background. */
    private fun Canvas.still() {
        drawColor(Color.rgb(30, 30, 40))
        drawRect(112f, 84f, 336f, 252f, Paint().apply { color = Color.rgb(200, 180, 120) })
    }

    @Test
    fun aStillPictureEncodedAgainIsCaptionedOnce() {
        val first = frame("first", quality = 90) { still() }
        val again = frame("again", quality = 70) { still() }

        assertEquals(listOf(first), FrameLikeness.distinct(listOf(first, again)))
    }

    @Test
    fun framesOfDifferentColorsWithTheSameBrightnessAreBothCaptioned() {
        // Pure red and this green have the same brightness (luma 76): a brightness-only comparison calls them one.
        val red = frame("red") { drawColor(Color.rgb(255, 0, 0)) }
        val green = frame("green") { drawColor(Color.rgb(0, 130, 0)) }

        assertEquals(listOf(red, green), FrameLikeness.distinct(listOf(red, green)))
    }

    @Test
    fun aNewSubtitleOnTheSamePictureIsANewFrame() {
        val plain = frame("plain") { still() }
        val subtitled = frame("subtitled") {
            still()
            drawText("WHEN THE MEME HITS", 60f, 316f, Paint().apply { color = Color.WHITE; textSize = 28f })
        }

        assertEquals(listOf(plain, subtitled), FrameLikeness.distinct(listOf(plain, subtitled)))
    }
}
