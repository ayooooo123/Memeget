package com.memeget.nativeapp.storage

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/** The image model and OCR read only JPEG/PNG through OpenCV; every frame reaches them as the original app's JPEG. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelInputJpegTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = RuntimeEnvironment.getApplication()

    private fun encoded(format: Bitmap.CompressFormat, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 40, 40)) }
        return temporary.newFile().apply { outputStream().use { bitmap.compress(format, 100, it) } }
    }

    /** Width and height of [file] when it is a JPEG; fails otherwise. */
    private fun jpegSize(file: File): Pair<Int, Int> {
        val bytes = file.readBytes()
        assertEquals("JPEG start of image", listOf(0xFF, 0xD8), listOf(bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    private fun modelInput(source: File): File =
        temporary.newFile("input-${source.name}.jpg").also { MediaIoUtils.writeModelInputJpeg(context, Uri.fromFile(source), it) }

    @Test fun webpAndGifBecomeAJpeg768Wide() {
        assertEquals(768 to 512, jpegSize(modelInput(encoded(Bitmap.CompressFormat.WEBP_LOSSLESS, 480, 320))))

        // A 1x1 GIF89a: the format X and messaging apps save animated memes in.
        val gif = temporary.newFile("one.gif").apply {
            writeBytes("47494638396101000100800000000000ffffff21f90401000000002c00000000010001000002024401003b"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        assertEquals(768 to 768, jpegSize(modelInput(gif)))
    }

    @Test fun aThinStripIsCappedInsteadOfUpscaledWithoutLimit() {
        val (width, height) = jpegSize(modelInput(encoded(Bitmap.CompressFormat.PNG, 8, 4000)))
        assertEquals(8192, height)
        assertEquals(16, width)
    }

    @Test fun unreadableBytesFailWithAReason() {
        val garbage = temporary.newFile("broken.webp").apply { writeBytes(ByteArray(64) { 0x5A }) }
        val failure = assertThrows(IOException::class.java) { modelInput(garbage) }
        assertEquals("Unsupported or damaged image", failure.message)
    }
}
