package com.memeget.nativeapp.storage

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Random
import java.util.zip.CRC32
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
        val source = ZipImporter.CountingInputStream(zip.inputStream())
        val budget = ZipImporter.ArchiveBudget(source)
        ZipInputStream(source).use { zis ->
            assertEquals("media/a.png", ZipImporter.extractNextEntry(zis, cache, manifestLimit = 1024, budget = budget)?.entryPath)
            assertThrows(ZipImporter.UnreadableManifest::class.java) { ZipImporter.extractNextEntry(zis, cache, manifestLimit = 1024, budget = budget) }
            assertEquals("media/b.png", ZipImporter.extractNextEntry(zis, cache, manifestLimit = 1024, budget = budget)?.entryPath)
            assertNull(ZipImporter.extractNextEntry(zis, cache, manifestLimit = 1024, budget = budget))
        }
    }

    /** Archive size alone never stops an import: media that barely deflates may inflate far past the allowance. */
    @Test fun anArchiveOfIncompressibleMediaLargerThanTheAllowanceImportsWhole() {
        val random = Random(7)
        val zip = temporary.newFile("large.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (k in 0 until 8) {
                out.putNextEntry(ZipEntry("media/$k.jpg"))
                out.write(ByteArray(64 * 1024).also(random::nextBytes))
                out.closeEntry()
                out.putNextEntry(ZipEntry("notes/$k.bin"))
                out.write(ByteArray(64 * 1024).also(random::nextBytes))
                out.closeEntry()
            }
        }
        val cache = temporary.newFolder("cache")
        val source = ZipImporter.CountingInputStream(zip.inputStream())
        val budget = ZipImporter.ArchiveBudget(source, maxInflationRatio = 2, inflationAllowance = 64 * 1024L)
        val extracted = ZipInputStream(source).use { zis ->
            generateSequence { ZipImporter.extractNextEntry(zis, cache, budget = budget) }.toList()
        }
        assertEquals((0 until 8).map { "media/$it.jpg" }, extracted.map { it.entryPath })
        assertEquals(16 * 64 * 1024L, budget.totalInflatedBytes)
    }

    @Test fun anHonestStoredPrefixDoesNotPayForASkippedBombAfterIt() {
        val zip = temporary.newFile("prefix-bomb.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            val honest = ByteArray(1024 * 1024).also(Random(3)::nextBytes)
            out.putNextEntry(ZipEntry("media/honest.jpg").apply {
                method = ZipEntry.STORED
                size = honest.size.toLong()
                crc = CRC32().apply { update(honest) }.value
            })
            out.write(honest)
            out.closeEntry()
            out.putNextEntry(ZipEntry("notes/bomb.bin"))
            out.write(ByteArray(1024 * 1024))
            out.closeEntry()
        }
        val cache = temporary.newFolder("cache")
        val source = ZipImporter.CountingInputStream(zip.inputStream())
        val budget = ZipImporter.ArchiveBudget(source, maxInflationRatio = 2, inflationAllowance = 64 * 1024L)
        ZipInputStream(source).use { zis ->
            assertEquals("media/honest.jpg", ZipImporter.extractNextEntry(zis, cache, budget = budget)?.entryPath)
            assertThrows(ZipImporter.ArchiveTooLarge::class.java) { ZipImporter.extractNextEntry(zis, cache, budget = budget) }
        }
        assertTrue("The bomb stopped near the allowance", budget.totalInflatedBytes < 1024 * 1024 + 128 * 1024L)
    }

    @Test fun anArchiveUnpackingPastTheCeilingStopsEvenAtAnHonestRatio() {
        val random = Random(11)
        val zip = temporary.newFile("ceiling.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (k in 0 until 4) {
                out.putNextEntry(ZipEntry("media/$k.jpg"))
                out.write(ByteArray(256 * 1024).also(random::nextBytes))
                out.closeEntry()
            }
        }
        val cache = temporary.newFolder("cache")
        val source = ZipImporter.CountingInputStream(zip.inputStream())
        val budget = ZipImporter.ArchiveBudget(source, maxTotalInflated = 600 * 1024L)
        ZipInputStream(source).use { zis ->
            assertEquals("media/0.jpg", ZipImporter.extractNextEntry(zis, cache, budget = budget)?.entryPath)
            assertEquals("media/1.jpg", ZipImporter.extractNextEntry(zis, cache, budget = budget)?.entryPath)
            assertThrows(ZipImporter.ArchiveTooLarge::class.java) { ZipImporter.extractNextEntry(zis, cache, budget = budget) }
        }
    }

    /** A write failure ends the import; skipping the entry would inflate its rest outside the budget. */
    @Test fun anUnwritableCacheFailsTheImportWithoutInflatingTheEntry() {
        val zip = temporary.newFile("flat.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("media/flat.png"))
            out.write(ByteArray(32 * 1024 * 1024))
            out.closeEntry()
        }
        val notADirectory = temporary.newFile("cache")
        val source = ZipImporter.CountingInputStream(zip.inputStream())
        val budget = ZipImporter.ArchiveBudget(source)
        ZipInputStream(source).use { zis ->
            assertThrows(IOException::class.java) { ZipImporter.extractNextEntry(zis, notADirectory, budget = budget) }
        }
        assertEquals(0L, budget.totalInflatedBytes)
        assertTrue("The entry's compressed data was left unread", source.count < zip.length())
    }
}
