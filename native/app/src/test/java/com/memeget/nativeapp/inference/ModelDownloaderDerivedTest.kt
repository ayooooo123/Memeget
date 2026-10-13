package com.memeget.nativeapp.inference

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ModelDownloaderDerivedTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val converted = "converted projector".toByteArray()
    private val corrupted = "corrupted projector".toByteArray()
    private val source = ModelArtifact("source", "source.gguf", "https://example.invalid/source.gguf", "0".repeat(64), 1L)
    private val artifact = DerivedModelArtifact("derived", "derived.gguf", source, sha256(converted), converted.size.toLong())

    @Test
    fun verifiedOutputIsKeptAndReusedWithoutProducingAgain() = runTest {
        val downloader = ModelDownloader()
        val dir = folder.newFolder()
        var produced = 0

        val first = downloader.ensureDerived(artifact, dir) { output -> produced++; output.writeBytes(converted) }
        val second = downloader.ensureDerived(artifact, dir) { produced++; error("a verified output must be reused") }

        assertEquals(1, produced)
        assertEquals(first, second)
        assertArrayEquals(converted, second.readBytes())
    }

    @Test
    fun outputWithTheWrongDigestIsRejectedAndLeavesNoFiles() = runTest {
        val dir = folder.newFolder()

        try {
            ModelDownloader().ensureDerived(artifact, dir) { output -> output.writeBytes(corrupted) }
            fail("A same-size output with a different digest must not be accepted")
        } catch (expected: IOException) {
        }

        assertFalse(File(dir, "derived.gguf").exists())
        assertFalse(File(dir, "derived.gguf.part").exists())
    }

    @Test
    fun corruptedExistingOutputIsProducedAgain() = runTest {
        val dir = folder.newFolder()
        File(dir, "derived.gguf").writeBytes(corrupted)
        var produced = 0

        val result = ModelDownloader().ensureDerived(artifact, dir) { output -> produced++; output.writeBytes(converted) }

        assertEquals(1, produced)
        assertArrayEquals(converted, result.readBytes())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
