package com.memeget.nativeapp.storage

import java.io.File
import java.io.RandomAccessFile

/**
 * Makes MP4s readable by Android's platform extractor without re-encoding. X's "GIF" videos
 * ("Twitter-vork muxer") write the composition-offset box (`ctts`) with flags 1, where the MP4
 * spec requires 0; Android's MPEG4Extractor then rejects the sample table and reports no tracks,
 * so neither MediaExtractor nor MediaMetadataRetriever can read a frame. Zeroing those three
 * flag bytes is the whole repair. Only ever applied to private working copies, never originals.
 */
internal object Mp4Repair {
    /** Boxes on the path from the file root to `ctts`. */
    private val PATH_TO_CTTS = setOf("moov", "trak", "mdia", "minf", "stbl")

    /** Zeroes non-zero `ctts` flags in [file] in place; returns how many boxes changed. */
    fun zeroCttsFlags(file: File): Int = RandomAccessFile(file, "rw").use { raf ->
        var changed = 0
        fun walk(start: Long, end: Long) {
            var offset = start
            while (offset + 8 <= end) {
                raf.seek(offset)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
                var header = 8
                when (size) {
                    0L -> size = end - offset
                    1L -> {
                        if (offset + 16 > end) return
                        size = raf.readLong()
                        header = 16
                    }
                }
                if (size < header || size > end - offset) return // malformed: leave the rest alone
                if (type == "ctts" && size >= header + 4) {
                    raf.seek(offset + header)
                    if (raf.readInt() and 0xFFFFFF != 0) {
                        raf.seek(offset + header + 1)
                        raf.write(ByteArray(3))
                        changed++
                    }
                } else if (type in PATH_TO_CTTS) {
                    walk(offset + header, offset + size)
                }
                offset += size
            }
        }
        walk(0, raf.length())
        changed
    }
}
