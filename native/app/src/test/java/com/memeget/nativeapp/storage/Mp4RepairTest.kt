package com.memeget.nativeapp.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class Mp4RepairTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun box(type: String, vararg children: ByteArray, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArrayOutputStream().apply { write(payload); children.forEach(::write) }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply { writeInt(8 + body.size); writeBytes(type); write(body) }
        }.toByteArray()
    }

    /** A box using the 64-bit size form (size field 1, then the real size). */
    private fun largeBox(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply { writeInt(1); writeBytes(type); writeLong(16L + payload.size); write(payload) }
    }.toByteArray()

    private fun ctts(versionAndFlags: Int) = box("ctts", payload = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply { writeInt(versionAndFlags); writeInt(1); writeInt(4); writeInt(15) }
    }.toByteArray())

    /** ftyp, a large-size free box, moov/trak/mdia/minf/stbl holding [ctts], then mdat. */
    private fun mp4(cttsBox: ByteArray): ByteArray {
        val stbl = box("stbl", box("stts", payload = ByteArray(8)), cttsBox)
        val moov = box("moov", box("mvhd", payload = ByteArray(100)), box("trak", box("mdia", box("minf", stbl))))
        return box("ftyp", payload = "iso5".toByteArray() + ByteArray(4)) + largeBox("free", ByteArray(8)) + moov +
            box("mdat", payload = ByteArray(32) { it.toByte() })
    }

    private fun write(bytes: ByteArray) = temporary.newFile().apply { writeBytes(bytes) }

    @Test fun nonZeroCttsFlagsAreClearedAndNothingElseChanges() {
        val original = mp4(ctts(0x00000001))
        val file = write(original)

        assertEquals(1, Mp4Repair.zeroCttsFlags(file))

        val repaired = file.readBytes()
        val expected = mp4(ctts(0x00000000))
        assertArrayEquals(expected, repaired)
        assertEquals("second pass finds nothing to change", 0, Mp4Repair.zeroCttsFlags(file))
    }

    @Test fun versionOneOffsetsKeepTheirVersion() {
        val file = write(mp4(ctts(0x01000001)))
        assertEquals(1, Mp4Repair.zeroCttsFlags(file))
        assertArrayEquals(mp4(ctts(0x01000000)), file.readBytes())
    }

    @Test fun validAndTruncatedFilesAreLeftAlone() {
        val valid = mp4(ctts(0))
        val validFile = write(valid)
        assertEquals(0, Mp4Repair.zeroCttsFlags(validFile))
        assertArrayEquals(valid, validFile.readBytes())

        val truncated = valid.copyOf(valid.size - 40)
        val truncatedFile = write(truncated)
        assertEquals(0, Mp4Repair.zeroCttsFlags(truncatedFile))
        assertArrayEquals(truncated, truncatedFile.readBytes())

        val garbage = ByteArray(64) { 0x7f }
        val garbageFile = write(garbage)
        assertEquals(0, Mp4Repair.zeroCttsFlags(garbageFile))
        assertArrayEquals(garbage, garbageFile.readBytes())
    }
}
