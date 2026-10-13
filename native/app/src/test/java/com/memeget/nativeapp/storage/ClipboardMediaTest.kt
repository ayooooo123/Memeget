package com.memeget.nativeapp.storage

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.memeget.nativeapp.LibraryFiles
import com.memeget.nativeapp.SqliteLibraryStore
import expo.modules.memegetbg.MemeClipFileProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClipboardMediaTest {
    @Before
    fun attachProviderForThisApplication() {
        val context = RuntimeEnvironment.getApplication()
        val info = requireNotNull(context.packageManager.resolveContentProvider(
            "${context.packageName}.memegetclip", PackageManager.GET_META_DATA,
        ))
        // Android attaches providers at process startup; each Robolectric test has a new data directory.
        MemeClipFileProvider().attachInfo(context, info)
    }

    @Test
    fun copiedImageRemainsReadableAfterTheSourceIsDeleted() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "clipboard-source.png")
        val image = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, 0xFFCC0000.toInt())
            setPixel(1, 0, 0xFF0066CC.toInt())
        }
        source.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        val files = LibraryFiles(context, SqliteLibraryStore(context))
        files.copyToClipboard(Uri.fromFile(source).toString(), "proof.png")
        assertTrue(source.delete())

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals(1, clip.itemCount)
        assertTrue(clip.description.hasMimeType("image/png"))
        val copiedUri = requireNotNull(clip.getItemAt(0).uri)
        assertEquals("content", copiedUri.scheme)
        assertEquals("image/png", context.contentResolver.getType(copiedUri))
        val pasted = requireNotNull(context.contentResolver.openInputStream(copiedUri)!!.use(BitmapFactory::decodeStream))
        assertEquals(2, pasted.width)
        assertEquals(1, pasted.height)
        assertEquals(0xFFCC0000.toInt(), pasted.getPixel(0, 0))
        assertEquals(0xFF0066CC.toInt(), pasted.getPixel(1, 0))
        pasted.recycle()
    }

    @Test
    fun failedCopyKeepsThePreviousMediaReadable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "previous-clipboard-source.png")
        val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, 0xFF008844.toInt())
        }
        source.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        val files = LibraryFiles(context, SqliteLibraryStore(context))
        files.copyToClipboard(Uri.fromFile(source).toString(), source.name)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previousUri = requireNotNull(clipboard.primaryClip?.getItemAt(0)?.uri)
        val missingSource = File(context.cacheDir, "missing-clipboard-source.png")
        try {
            files.copyToClipboard(Uri.fromFile(missingSource).toString(), missingSource.name)
            fail("A missing source must not replace the clipboard")
        } catch (_: java.io.FileNotFoundException) {
            // The previously published image remains available to the paste recipient.
        }
        assertEquals(previousUri, clipboard.primaryClip?.getItemAt(0)?.uri)
        val pasted = requireNotNull(context.contentResolver.openInputStream(previousUri)!!.use(BitmapFactory::decodeStream))
        assertEquals(0xFF008844.toInt(), pasted.getPixel(0, 0))
        pasted.recycle()
    }
}
