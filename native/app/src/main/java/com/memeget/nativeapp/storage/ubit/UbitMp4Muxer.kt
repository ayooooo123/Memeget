package com.memeget.nativeapp.storage.ubit

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

// Zero-dependency ISO-BMFF reader and fast-start MP4/M4A muxer from uBit.
// Enforces uBit's two-pass co64 invariant so moov-before-mdat sample offsets never drift by a single byte.

internal class UbitBinBuf(initialCapacity: Int = 4096) {
    private var buf = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size.coerceAtLeast(64)
        while (cap < size + extra) cap = cap * 2
        buf = buf.copyOf(cap)
    }

    fun u8(v: Int) {
        ensure(1)
        buf[size++] = (v and 0xff).toByte()
    }

    fun u16(v: Int) {
        ensure(2)
        buf[size++] = ((v ushr 8) and 0xff).toByte()
        buf[size++] = (v and 0xff).toByte()
    }

    fun u24(v: Int) {
        ensure(3)
        buf[size++] = ((v ushr 16) and 0xff).toByte()
        buf[size++] = ((v ushr 8) and 0xff).toByte()
        buf[size++] = (v and 0xff).toByte()
    }

    fun u32(v: Long) {
        ensure(4)
        buf[size++] = ((v ushr 24) and 0xff).toByte()
        buf[size++] = ((v ushr 16) and 0xff).toByte()
        buf[size++] = ((v ushr 8) and 0xff).toByte()
        buf[size++] = (v and 0xff).toByte()
    }

    fun i32(v: Int) = u32(v.toLong() and 0xffffffffL)

    fun u64(v: Long) {
        u32((v ushr 32) and 0xffffffffL)
        u32(v and 0xffffffffL)
    }

    fun fourCc(s: String) {
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until 4) {
            u8(if (i < bytes.size) bytes[i].toInt() and 0xff else 0x20)
        }
    }

    fun raw4(b0: Int, b1: Int, b2: Int, b3: Int) {
        u8(b0); u8(b1); u8(b2); u8(b3)
    }

    fun bytes(src: ByteArray, off: Int = 0, len: Int = src.size - off) {
        if (len <= 0) return
        ensure(len)
        System.arraycopy(src, off, buf, size, len)
        size += len
    }

    fun strBytes(s: String) = bytes(s.toByteArray(Charsets.UTF_8))

    fun zeros(count: Int) {
        if (count <= 0) return
        ensure(count)
        buf.fill(0, size, size + count)
        size += count
    }

    inline fun box(type: String, build: (UbitBinBuf) -> Unit) {
        val start = size
        u32(0L)
        fourCc(type)
        build(this)
        patchU32(start, (size - start).toLong())
    }

    inline fun boxRaw4(b0: Int, b1: Int, b2: Int, b3: Int, build: (UbitBinBuf) -> Unit) {
        val start = size
        u32(0L)
        raw4(b0, b1, b2, b3)
        build(this)
        patchU32(start, (size - start).toLong())
    }

    inline fun fullBox(type: String, version: Int, flags: Int, build: (UbitBinBuf) -> Unit) {
        box(type) { b ->
            b.u8(version)
            b.u24(flags)
            build(b)
        }
    }

    fun patchU32(offset: Int, v: Long) {
        buf[offset] = ((v ushr 24) and 0xff).toByte()
        buf[offset + 1] = ((v ushr 16) and 0xff).toByte()
        buf[offset + 2] = ((v ushr 8) and 0xff).toByte()
        buf[offset + 3] = (v and 0xff).toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

internal data class UbitBoxRef(
    val type: String,
    val hdrOff: Int,
    val payOff: Int,
    val endOff: Int,
)

internal class UbitMp4Sample(
    val srcIdx: Int = 0,
    val offset: Long = 0L,
    val size: Int,
    val duration: Long,
    val ctsOffset: Int = 0,
    val isSync: Boolean = true,
    val inlineData: ByteArray? = null,
)

internal class UbitMp4Track(
    var trackId: Int = 1,
    val kind: String, // "vide", "soun"
    val timescale: Long,
    val width: Int = 0,
    val height: Int = 0,
    val stsdPayload: ByteArray,
    val samples: MutableList<UbitMp4Sample> = ArrayList(),
) {
    val totalTicks: Long
        get() {
            var sum = 0L
            for (s in samples) sum += s.duration
            return sum
        }

    val durationSec: Double
        get() = totalTicks.toDouble() / timescale.coerceAtLeast(1L).toDouble()
}

internal data class UbitMp4Metadata(
    val title: String? = null,
    val artist: String? = null,
    val date: String? = null,
    val webpageUrl: String? = null,
    val description: String? = null,
)

internal object UbitMp4Muxer {

    fun rdU16(d: ByteArray, off: Int): Int =
        ((d[off].toInt() and 0xff) shl 8) or (d[off + 1].toInt() and 0xff)

    fun rdU32(d: ByteArray, off: Int): Long =
        ((d[off].toLong() and 0xffL) shl 24) or
            ((d[off + 1].toLong() and 0xffL) shl 16) or
            ((d[off + 2].toLong() and 0xffL) shl 8) or
            (d[off + 3].toLong() and 0xffL)

    fun rdI32(d: ByteArray, off: Int): Int = rdU32(d, off).toInt()

    fun rdU64(d: ByteArray, off: Int): Long =
        (rdU32(d, off) shl 32) or rdU32(d, off + 4)

    fun rd4cc(d: ByteArray, off: Int): String =
        String(d, off, 4, Charsets.ISO_8859_1)

    fun listBoxes(d: ByteArray, start: Int, end: Int): List<UbitBoxRef> {
        val out = ArrayList<UbitBoxRef>()
        var pos = start
        while (pos + 8 <= end) {
            val sz32 = rdU32(d, pos)
            val tp = rd4cc(d, pos + 4)
            val hlen: Int
            val bend: Long
            when (sz32) {
                1L -> {
                    if (pos + 16 > end) break
                    hlen = 16
                    bend = pos.toLong() + rdU64(d, pos + 8)
                }
                0L -> {
                    hlen = 8
                    bend = end.toLong()
                }
                else -> {
                    hlen = 8
                    bend = pos.toLong() + sz32
                }
            }
            if (bend < pos + hlen || bend > end.toLong()) break
            out.add(UbitBoxRef(tp, pos, pos + hlen, bend.toInt()))
            pos = bend.toInt()
        }
        return out
    }

    private data class TrexDefaults(val dur: Long = 1024L, val size: Int = 0, val flags: Long = 0L)

    // Parses only the ISO-BMFF header / moof structure — sample payloads remain on disk and stream via RandomAccessFile during writeMp4!
    // Wait: for fragmented fMP4 (HLS/DASH) moof boxes can sit interleaved with mdat throughout the file, so we walk top-level boxes via RandomAccessFile!
    fun readTracks(file: File, srcIdx: Int = 0): List<UbitMp4Track> {
        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            if (fileLen < 8L) throw IOException("Empty MP4 source: ${file.name}")

            data class DiskBox(val type: String, val hdrOff: Long, val payOff: Long, val endOff: Long)

            val topBoxes = ArrayList<DiskBox>()
            var pos = 0L
            val hdr = ByteArray(16)
            while (pos + 8L <= fileLen) {
                raf.seek(pos)
                raf.readFully(hdr, 0, 8)
                val sz32 = rdU32(hdr, 0)
                val tp = rd4cc(hdr, 4)
                val hlen: Long
                val bend: Long
                when (sz32) {
                    1L -> {
                        if (pos + 16L > fileLen) break
                        raf.readFully(hdr, 8, 8)
                        hlen = 16L
                        bend = pos + rdU64(hdr, 8)
                    }
                    0L -> {
                        hlen = 8L
                        bend = fileLen
                    }
                    else -> {
                        hlen = 8L
                        bend = pos + sz32
                    }
                }
                if (bend < pos + hlen || bend > fileLen) break
                topBoxes.add(DiskBox(tp, pos, pos + hlen, bend))
                pos = bend
            }

            val moovDisk = topBoxes.firstOrNull { it.type == "moov" }
                ?: throw IOException("Missing moov box in ${file.name}")
            val moovLen = (moovDisk.endOff - moovDisk.payOff).toInt()
            if (moovLen <= 0 || moovLen > 128 * 1024 * 1024) {
                throw IOException("Invalid moov box size ($moovLen) in ${file.name}")
            }
            val moovBytes = ByteArray(moovLen)
            raf.seek(moovDisk.payOff)
            raf.readFully(moovBytes)

            val mchildren = listBoxes(moovBytes, 0, moovBytes.size)
            val trexMap = HashMap<Int, TrexDefaults>()
            for (b in mchildren) {
                if (b.type == "mvex") {
                    for (tb in listBoxes(moovBytes, b.payOff, b.endOff)) {
                        if (tb.type == "trex" && tb.payOff + 24 <= tb.endOff) {
                            val tid = rdU32(moovBytes, tb.payOff + 4).toInt()
                            trexMap[tid] = TrexDefaults(
                                dur = rdU32(moovBytes, tb.payOff + 12),
                                size = rdU32(moovBytes, tb.payOff + 16).toInt(),
                                flags = rdU32(moovBytes, tb.payOff + 20),
                            )
                        }
                    }
                }
            }

            val byId = LinkedHashMap<Int, UbitMp4Track>()
            for (trak in mchildren) {
                if (trak.type != "trak") continue
                var trackId = 1
                var width = 0
                var height = 0
                var timescale = 1000L
                var kind = ""
                var stblRef: UbitBoxRef? = null

                for (tb in listBoxes(moovBytes, trak.payOff, trak.endOff)) {
                    when (tb.type) {
                        "tkhd" -> {
                            val ver = moovBytes[tb.payOff].toInt() and 0xff
                            if (ver == 0 && tb.payOff + 84 <= tb.endOff) {
                                trackId = rdU32(moovBytes, tb.payOff + 12).toInt()
                                width = (rdU32(moovBytes, tb.payOff + 76) ushr 16).toInt() and 0xffff
                                height = (rdU32(moovBytes, tb.payOff + 80) ushr 16).toInt() and 0xffff
                            } else if (ver == 1 && tb.payOff + 96 <= tb.endOff) {
                                trackId = rdU32(moovBytes, tb.payOff + 20).toInt()
                                width = (rdU32(moovBytes, tb.payOff + 88) ushr 16).toInt() and 0xffff
                                height = (rdU32(moovBytes, tb.payOff + 92) ushr 16).toInt() and 0xffff
                            }
                        }
                        "mdia" -> {
                            for (mb in listBoxes(moovBytes, tb.payOff, tb.endOff)) {
                                when (mb.type) {
                                    "mdhd" -> {
                                        val ver = moovBytes[mb.payOff].toInt() and 0xff
                                        timescale = if (ver == 0 && mb.payOff + 16 <= mb.endOff) {
                                            rdU32(moovBytes, mb.payOff + 12)
                                        } else if (mb.payOff + 24 <= mb.endOff) {
                                            rdU32(moovBytes, mb.payOff + 20)
                                        } else {
                                            1000L
                                        }
                                    }
                                    "hdlr" -> if (mb.payOff + 12 <= mb.endOff) {
                                        kind = rd4cc(moovBytes, mb.payOff + 8)
                                    }
                                    "minf" -> {
                                        for (mfb in listBoxes(moovBytes, mb.payOff, mb.endOff)) {
                                            if (mfb.type == "stbl") stblRef = mfb
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if ((kind == "vide" || kind == "soun") && stblRef != null) {
                    val parsed = parseStbl(
                        d = moovBytes,
                        boxes = listBoxes(moovBytes, stblRef!!.payOff, stblRef!!.endOff),
                        srcIdx = srcIdx,
                        trackId = trackId,
                        kind = kind,
                        timescale = timescale.coerceAtLeast(1L),
                        width = width,
                        height = height,
                    )
                    if (parsed != null && parsed.stsdPayload.isNotEmpty()) {
                        byId[trackId] = parsed
                    }
                }
            }

            // Walk fragmented moof boxes (DASH / fMP4 HLS)
            for (b in topBoxes) {
                if (b.type != "moof") continue
                val moofPayLen = (b.endOff - b.payOff).toInt()
                if (moofPayLen <= 0 || moofPayLen > 16 * 1024 * 1024) continue
                val moofPay = ByteArray(moofPayLen)
                raf.seek(b.payOff)
                raf.readFully(moofPay)

                val moofStart = b.hdrOff
                for (traf in listBoxes(moofPay, 0, moofPay.size)) {
                    if (traf.type != "traf") continue
                    val tboxes = listBoxes(moofPay, traf.payOff, traf.endOff)
                    val tfhd = tboxes.firstOrNull { it.type == "tfhd" } ?: continue
                    if (tfhd.payOff + 8 > tfhd.endOff) continue
                    val tflags = rdU32(moofPay, tfhd.payOff).toInt() and 0xffffff
                    val tid = rdU32(moofPay, tfhd.payOff + 4).toInt()
                    val track = byId[tid] ?: byId.values.singleOrNull() ?: continue
                    val tx = trexMap[tid] ?: TrexDefaults()

                    var hoff = tfhd.payOff + 8
                    var baseOff = moofStart
                    if ((tflags and 0x01) != 0 && hoff + 8 <= tfhd.endOff) {
                        baseOff = rdU64(moofPay, hoff); hoff += 8
                    }
                    if ((tflags and 0x02) != 0) hoff += 4
                    var defDur = tx.dur
                    var defSz = tx.size
                    var defFl = tx.flags
                    if ((tflags and 0x08) != 0 && hoff + 4 <= tfhd.endOff) {
                        defDur = rdU32(moofPay, hoff); hoff += 4
                    }
                    if ((tflags and 0x10) != 0 && hoff + 4 <= tfhd.endOff) {
                        defSz = rdU32(moofPay, hoff).toInt(); hoff += 4
                    }
                    if ((tflags and 0x20) != 0 && hoff + 4 <= tfhd.endOff) {
                        defFl = rdU32(moofPay, hoff); hoff += 4
                    }

                    var runOff = baseOff
                    for (trun in tboxes) {
                        if (trun.type != "trun" || trun.payOff + 8 > trun.endOff) continue
                        val rflags = rdU32(moofPay, trun.payOff).toInt() and 0xffffff
                        val cnt = rdU32(moofPay, trun.payOff + 4).toInt()
                        var roff = trun.payOff + 8
                        if ((rflags and 0x01) != 0 && roff + 4 <= trun.endOff) {
                            runOff = baseOff + rdI32(moofPay, roff).toLong()
                            roff += 4
                        }
                        var hasFirstFl = false
                        var firstFl = 0L
                        if ((rflags and 0x04) != 0 && roff + 4 <= trun.endOff) {
                            firstFl = rdU32(moofPay, roff)
                            hasFirstFl = true
                            roff += 4
                        }
                        for (i in 0 until cnt) {
                            var sdur = defDur
                            var ssz = defSz
                            var sfl = if (i == 0 && hasFirstFl) firstFl else defFl
                            var scts = 0
                            if ((rflags and 0x100) != 0 && roff + 4 <= trun.endOff) {
                                sdur = rdU32(moofPay, roff); roff += 4
                            }
                            if ((rflags and 0x200) != 0 && roff + 4 <= trun.endOff) {
                                ssz = rdU32(moofPay, roff).toInt(); roff += 4
                            }
                            if ((rflags and 0x400) != 0 && roff + 4 <= trun.endOff) {
                                sfl = rdU32(moofPay, roff); roff += 4
                            }
                            if ((rflags and 0x800) != 0 && roff + 4 <= trun.endOff) {
                                scts = rdI32(moofPay, roff); roff += 4
                            }
                            val sync = if (track.kind != "vide") true else (sfl and 0x00010000L) == 0L
                            if (ssz > 0) {
                                track.samples.add(UbitMp4Sample(srcIdx, runOff, ssz, sdur, scts, sync, null))
                                runOff += ssz.toLong()
                            }
                        }
                    }
                }
            }

            return byId.values.filter { it.samples.isNotEmpty() }
        }
    }

    private fun parseStbl(
        d: ByteArray,
        boxes: List<UbitBoxRef>,
        srcIdx: Int,
        trackId: Int,
        kind: String,
        timescale: Long,
        width: Int,
        height: Int,
    ): UbitMp4Track? {
        var stsdPayload: ByteArray? = null
        var sizes = IntArray(0)
        val chunkOffs = ArrayList<Long>()
        data class StscRun(val firstChunk: Int, val spc: Int)
        val stscRuns = ArrayList<StscRun>()
        var sttsBox: UbitBoxRef? = null
        var cttsBox: UbitBoxRef? = null
        var stssBox: UbitBoxRef? = null

        for (b in boxes) {
            when (b.type) {
                "stsd" -> stsdPayload = d.copyOfRange(b.payOff, b.endOff)
                "stsz" -> if (b.payOff + 12 <= b.endOff) {
                    val fixed = rdU32(d, b.payOff + 4).toInt()
                    val cnt = rdU32(d, b.payOff + 8).toInt()
                    if (cnt > 0) {
                        if (fixed != 0) {
                            sizes = IntArray(cnt) { fixed }
                        } else {
                            val maxCnt = minOf(cnt, (b.endOff - (b.payOff + 12)) / 4)
                            sizes = IntArray(maxCnt) { i -> rdU32(d, b.payOff + 12 + i * 4).toInt() }
                        }
                    }
                }
                "stco" -> if (b.payOff + 8 <= b.endOff) {
                    val cnt = rdU32(d, b.payOff + 4).toInt()
                    val maxCnt = minOf(cnt, (b.endOff - (b.payOff + 8)) / 4)
                    for (i in 0 until maxCnt) chunkOffs.add(rdU32(d, b.payOff + 8 + i * 4))
                }
                "co64" -> if (b.payOff + 8 <= b.endOff) {
                    val cnt = rdU32(d, b.payOff + 4).toInt()
                    val maxCnt = minOf(cnt, (b.endOff - (b.payOff + 8)) / 8)
                    for (i in 0 until maxCnt) chunkOffs.add(rdU64(d, b.payOff + 8 + i * 8))
                }
                "stsc" -> if (b.payOff + 8 <= b.endOff) {
                    val cnt = rdU32(d, b.payOff + 4).toInt()
                    val maxCnt = minOf(cnt, (b.endOff - (b.payOff + 8)) / 12)
                    for (i in 0 until maxCnt) {
                        val fc = rdU32(d, b.payOff + 8 + i * 12).toInt().coerceAtLeast(1)
                        val spc = rdU32(d, b.payOff + 12 + i * 12).toInt()
                        stscRuns.add(StscRun(fc, spc))
                    }
                }
                "stts" -> sttsBox = b
                "ctts" -> cttsBox = b
                "stss" -> stssBox = b
            }
        }

        val stsd = stsdPayload ?: return null
        val track = UbitMp4Track(trackId, kind, timescale, width, height, stsd)
        val n = sizes.size
        if (n == 0) return track

        val offs = LongArray(n)
        var sidx = 0
        for (r in stscRuns.indices) {
            if (chunkOffs.isEmpty()) break
            val c0 = stscRuns[r].firstChunk - 1
            val c1 = if (r + 1 < stscRuns.size) minOf(chunkOffs.size, stscRuns[r + 1].firstChunk - 1) else chunkOffs.size
            for (c in c0 until c1) {
                var cur = chunkOffs[c]
                var k = 0
                while (k < stscRuns[r].spc && sidx < n) {
                    offs[sidx] = cur
                    cur += sizes[sidx].toLong()
                    sidx++
                    k++
                }
            }
        }

        val durs = LongArray(n) { 1024L }
        sttsBox?.let { b ->
            if (b.payOff + 8 <= b.endOff) {
                val cnt = rdU32(d, b.payOff + 4).toInt()
                val maxCnt = minOf(cnt, (b.endOff - (b.payOff + 8)) / 8)
                var si = 0
                for (i in 0 until maxCnt) {
                    val rc = rdU32(d, b.payOff + 8 + i * 8).toInt()
                    val rd = rdU32(d, b.payOff + 12 + i * 8)
                    var j = 0
                    while (j < rc && si < n) {
                        durs[si++] = rd
                        j++
                    }
                }
            }
        }

        val cts = IntArray(n)
        cttsBox?.let { b ->
            if (b.payOff + 8 <= b.endOff) {
                val cnt = rdU32(d, b.payOff + 4).toInt()
                val maxCnt = minOf(cnt, (b.endOff - (b.payOff + 8)) / 8)
                var si = 0
                for (i in 0 until maxCnt) {
                    val rc = rdU32(d, b.payOff + 8 + i * 8).toInt()
                    val co = rdI32(d, b.payOff + 12 + i * 8)
                    var j = 0
                    while (j < rc && si < n) {
                        cts[si++] = co
                        j++
                    }
                }
            }
        }

        val syncs = HashSet<Int>()
        var hasStss = false
        stssBox?.let { b ->
            if (b.payOff + 8 <= b.endOff) {
                hasStss = true
                val cnt = rdU32(d, b.payOff + 4).toInt()
                val maxCnt = minOf(cnt, (b.endOff - (b.payOff + 8)) / 4)
                for (i in 0 until maxCnt) {
                    val v = rdU32(d, b.payOff + 8 + i * 4).toInt()
                    if (v > 0) syncs.add(v - 1)
                }
            }
        }

        for (i in 0 until n) {
            val sy = if (hasStss) syncs.contains(i) else (kind != "vide")
            track.samples.add(UbitMp4Sample(srcIdx, offs[i], sizes[i], durs[i], cts[i], sy, null))
        }
        return track
    }

    fun writeMp4(
        tracks: List<UbitMp4Track>,
        sourceFiles: List<File>,
        destination: File,
        isAudioOnlyM4a: Boolean = false,
        metadata: UbitMp4Metadata = UbitMp4Metadata(),
    ) {
        if (tracks.isEmpty()) throw IOException("No playable tracks to mux")
        for (i in tracks.indices) tracks[i].trackId = i + 1

        val relOffs = Array(tracks.size) { idx -> LongArray(tracks[idx].samples.size) }
        var mdatBytes = 0L
        for (i in tracks.indices) {
            val samples = tracks[i].samples
            for (si in samples.indices) {
                relOffs[i][si] = mdatBytes
                mdatBytes += samples[si].size.toLong()
            }
        }
        val mdatHdrLen = if (mdatBytes + 8L > 0xffffffffL) 16L else 8L

        val ftyp = UbitBinBuf(64).apply {
            box("ftyp") { b ->
                if (isAudioOnlyM4a) {
                    b.fourCc("M4A "); b.u32(0L); b.fourCc("M4A "); b.fourCc("mp42"); b.fourCc("isom")
                } else {
                    b.fourCc("isom"); b.u32(0x200L); b.fourCc("isom"); b.fourCc("iso2"); b.fourCc("avc1"); b.fourCc("mp41")
                }
            }
        }.toByteArray()

        // Two-pass co64 invariant: pass 1 measures exact moov byte size, pass 2 stamps final chunk offsets.
        val dummyMoov = buildMoov(tracks, 0L, relOffs, metadata)
        val mdatPayStart = ftyp.size.toLong() + dummyMoov.size.toLong() + mdatHdrLen
        val finalMoov = buildMoov(tracks, mdatPayStart, relOffs, metadata)

        destination.parentFile?.mkdirs()
        val partFile = File(destination.parentFile, "${destination.name}.mux.part")
        val openedRafs = ArrayList<RandomAccessFile?>(sourceFiles.size)
        try {
            for (sf in sourceFiles) {
                openedRafs.add(if (sf.exists()) RandomAccessFile(sf, "r") else null)
            }
            BufferedOutputStream(FileOutputStream(partFile), 65536).use { out ->
                out.write(ftyp)
                out.write(finalMoov)

                val mh = UbitBinBuf(16)
                if (mdatHdrLen == 16L) {
                    mh.u32(1L); mh.fourCc("mdat"); mh.u64(mdatBytes + 16L)
                } else {
                    mh.u32(mdatBytes + 8L); mh.fourCc("mdat")
                }
                out.write(mh.toByteArray())

                var copyBuf = ByteArray(65536)
                for (t in tracks) {
                    for (s in t.samples) {
                        val inline = s.inlineData
                        if (inline != null) {
                            out.write(inline)
                        } else if (s.srcIdx in openedRafs.indices && s.size > 0) {
                            val raf = openedRafs[s.srcIdx] ?: continue
                            if (copyBuf.size < s.size) copyBuf = ByteArray(maxOf(s.size, copyBuf.size * 2))
                            raf.seek(s.offset)
                            raf.readFully(copyBuf, 0, s.size)
                            out.write(copyBuf, 0, s.size)
                        }
                    }
                }
                out.flush()
            }
            if (destination.exists()) destination.delete()
            if (!partFile.renameTo(destination)) {
                partFile.copyTo(destination, overwrite = true)
                partFile.delete()
            }
        } catch (e: Throwable) {
            partFile.delete()
            throw e
        } finally {
            for (raf in openedRafs) runCatching { raf?.close() }
        }
    }

    private fun buildMoov(
        tracks: List<UbitMp4Track>,
        baseOff: Long,
        relOffs: Array<LongArray>,
        meta: UbitMp4Metadata,
    ): ByteArray {
        val maxDur = tracks.maxOfOrNull { it.durationSec } ?: 0.0
        val movieTs = 1000L
        val movieDur = (maxDur * movieTs).toLong()

        val moov = UbitBinBuf(32768)
        moov.box("moov") { m ->
            m.fullBox("mvhd", 0, 0) { b ->
                b.u32(0L); b.u32(0L); b.u32(movieTs); b.u32(movieDur)
                b.u32(0x00010000L); b.u16(0x0100); b.zeros(10)
                for (v in longArrayOf(0x00010000L, 0L, 0L, 0L, 0x00010000L, 0L, 0L, 0L, 0x40000000L)) b.u32(v)
                b.zeros(24)
                b.u32((tracks.size + 1).toLong())
            }

            for (idx in tracks.indices) {
                val t = tracks[idx]
                m.box("trak") { tr ->
                    tr.fullBox("tkhd", 0, 3) { b ->
                        b.u32(0L); b.u32(0L); b.u32(t.trackId.toLong()); b.u32(0L)
                        b.u32((t.durationSec * movieTs).toLong())
                        b.zeros(8); b.u16(0)
                        b.u16(if (t.kind == "soun") 1 else 0)
                        b.u16(if (t.kind == "soun") 0x0100 else 0)
                        b.u16(0)
                        for (v in longArrayOf(0x00010000L, 0L, 0L, 0L, 0x00010000L, 0L, 0L, 0L, 0x40000000L)) b.u32(v)
                        b.u32((t.width.toLong() and 0xffffL) shl 16)
                        b.u32((t.height.toLong() and 0xffffL) shl 16)
                    }

                    tr.box("mdia") { md ->
                        md.fullBox("mdhd", 0, 0) { b ->
                            b.u32(0L); b.u32(0L); b.u32(t.timescale); b.u32(t.totalTicks)
                            b.u16(0x55c4); b.u16(0)
                        }
                        md.fullBox("hdlr", 0, 0) { b ->
                            b.u32(0L); b.fourCc(t.kind); b.zeros(12)
                            b.strBytes("uBit Media Handler"); b.u8(0)
                        }
                        md.box("minf") { mf ->
                            when (t.kind) {
                                "vide" -> mf.fullBox("vmhd", 0, 1) { b -> b.zeros(8) }
                                "soun" -> mf.fullBox("smhd", 0, 0) { b -> b.zeros(4) }
                                else -> mf.fullBox("nmhd", 0, 0) {}
                            }
                            mf.box("dinf") { di ->
                                di.fullBox("dref", 0, 0) { dr ->
                                    dr.u32(1L); dr.fullBox("url ", 0, 1) {}
                                }
                            }
                            mf.box("stbl") { sb ->
                                sb.box("stsd") { b -> b.bytes(t.stsdPayload) }

                                val sttsCounts = IntArray(t.samples.size)
                                val sttsDurs = LongArray(t.samples.size)
                                var sttsLen = 0

                                var anyCts = false
                                var negCts = false
                                val cttsCounts = IntArray(t.samples.size)
                                val cttsOffs = IntArray(t.samples.size)
                                var cttsLen = 0

                                val syncs = ArrayList<Int>()
                                for (si in t.samples.indices) {
                                    val s = t.samples[si]
                                    if (sttsLen > 0 && sttsDurs[sttsLen - 1] == s.duration) {
                                        sttsCounts[sttsLen - 1]++
                                    } else {
                                        sttsCounts[sttsLen] = 1
                                        sttsDurs[sttsLen] = s.duration
                                        sttsLen++
                                    }
                                    if (s.ctsOffset != 0) anyCts = true
                                    if (s.ctsOffset < 0) negCts = true
                                    if (cttsLen > 0 && cttsOffs[cttsLen - 1] == s.ctsOffset) {
                                        cttsCounts[cttsLen - 1]++
                                    } else {
                                        cttsCounts[cttsLen] = 1
                                        cttsOffs[cttsLen] = s.ctsOffset
                                        cttsLen++
                                    }
                                    if (t.kind == "vide" && s.isSync) syncs.add(si + 1)
                                }

                                sb.fullBox("stts", 0, 0) { b ->
                                    b.u32(sttsLen.toLong())
                                    for (i in 0 until sttsLen) {
                                        b.u32(sttsCounts[i].toLong())
                                        b.u32(sttsDurs[i])
                                    }
                                }
                                if (anyCts) {
                                    // Always version 0 flags 0 (unless negative CTS requires version 1, still flags=0 so Android MediaExtractor loves it!)
                                    sb.fullBox("ctts", if (negCts) 1 else 0, 0) { b ->
                                        b.u32(cttsLen.toLong())
                                        for (i in 0 until cttsLen) {
                                            b.u32(cttsCounts[i].toLong())
                                            b.i32(cttsOffs[i])
                                        }
                                    }
                                }
                                if (t.kind == "vide") {
                                    sb.fullBox("stss", 0, 0) { b ->
                                        b.u32(syncs.size.toLong())
                                        for (si in syncs) b.u32(si.toLong())
                                    }
                                }

                                val csz = 64
                                val nchunks = (t.samples.size + csz - 1) / csz
                                val rem = t.samples.size % csz
                                sb.fullBox("stsc", 0, 0) { b ->
                                    when {
                                        nchunks <= 1 -> {
                                            b.u32(1L); b.u32(1L); b.u32(t.samples.size.toLong()); b.u32(1L)
                                        }
                                        rem == 0 -> {
                                            b.u32(1L); b.u32(1L); b.u32(csz.toLong()); b.u32(1L)
                                        }
                                        else -> {
                                            b.u32(2L)
                                            b.u32(1L); b.u32(csz.toLong()); b.u32(1L)
                                            b.u32(nchunks.toLong()); b.u32(rem.toLong()); b.u32(1L)
                                        }
                                    }
                                }
                                sb.fullBox("stsz", 0, 0) { b ->
                                    b.u32(0L); b.u32(t.samples.size.toLong())
                                    for (s in t.samples) b.u32(s.size.toLong())
                                }
                                sb.fullBox("co64", 0, 0) { b ->
                                    b.u32(nchunks.toLong())
                                    var ci = 0
                                    while (ci < relOffs[idx].size) {
                                        b.u64(baseOff + relOffs[idx][ci])
                                        ci += csz
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val hasMeta = !meta.title.isNullOrBlank() || !meta.artist.isNullOrBlank() ||
                !meta.date.isNullOrBlank() || !meta.webpageUrl.isNullOrBlank()
            if (hasMeta) {
                m.box("udta") { u ->
                    u.fullBox("meta", 0, 0) { mb ->
                        mb.fullBox("hdlr", 0, 0) { h ->
                            h.u32(0L); h.fourCc("mdir"); h.fourCc("appl"); h.zeros(8); h.u8(0)
                        }
                        mb.box("ilst") { il ->
                            fun writeTag(b0: Int, b1: Int, b2: Int, b3: Int, value: String?) {
                                if (value.isNullOrBlank()) return
                                il.boxRaw4(b0, b1, b2, b3) { it ->
                                    it.box("data") { dt ->
                                        dt.u32(1L); dt.u32(0L); dt.strBytes(value)
                                    }
                                }
                            }
                            writeTag(0xA9, 'n'.code, 'a'.code, 'm'.code, meta.title)
                            writeTag(0xA9, 'A'.code, 'R'.code, 'T'.code, meta.artist)
                            writeTag(0xA9, 'd'.code, 'a'.code, 'y'.code, meta.date)
                            writeTag(0xA9, 'c'.code, 'm'.code, 't'.code, meta.webpageUrl)
                            meta.description?.take(250)?.takeIf { it.isNotBlank() }?.let { desc ->
                                writeTag('d'.code, 'e'.code, 's'.code, 'c'.code, desc)
                            }
                        }
                    }
                }
            }
        }
        return moov.toByteArray()
    }
}
