package com.memeget.nativeapp.storage

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ZipImporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun anOversizedManifestIsReportedAndTheRestOfTheArchiveStillImports() {
        val zip = temporary.newFile("collection.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((path, bytes) in listOf(
                "media/a.png" to ByteArray(10) { 1 },
                "manifest.json" to ByteArray(4096) { '7'.code.toByte() },
                "media/b.png" to ByteArray(10) { 2 },
            )) {
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        val cache = temporary.newFolder("cache")
        ZipInputStream(zip.inputStream()).use { zis ->
            assertEquals("media/a.png", ZipImporter.extractNextEntry(zis, cache, manifestLimit = 1024)?.entryPath)
            assertThrows(ZipImporter.UnreadableManifest::class.java) { ZipImporter.extractNextEntry(zis, cache, manifestLimit = 1024) }
            assertEquals("media/b.png", ZipImporter.extractNextEntry(zis, cache, manifestLimit = 1024)?.entryPath)
            assertNull(ZipImporter.extractNextEntry(zis, cache, manifestLimit = 1024))
        }
    }
}
