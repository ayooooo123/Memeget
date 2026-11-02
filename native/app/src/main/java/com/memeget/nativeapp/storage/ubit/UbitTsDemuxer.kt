package com.memeget.nativeapp.storage.ubit

import java.io.ByteArrayOutputStream
import java.io.File

// Pure-Kotlin 188-byte MPEG-TS (PAT / PMT / PES) demuxer from uBit:
// turns Annex-B H.264 + ADTS AAC .ts HLS segments into crisp MP4Tracks with zero external FFmpeg.

internal object UbitTsDemuxer {

    private class RawAu(val pts90k: Long, val dts90k: Long, val es: ByteArray)

    fun demux(tsFile: File): List<UbitMp4Track> {
        val d = tsFile.readBytes()
        val sz = d.size
        if (sz < 188) return emptyList()

        var pos = 0
        while (pos + 376 < sz && !(d[pos] == 0x47.toByte() && d[pos + 188] == 0x47.toByte())) {
            pos++
        }

        var pmtPid = 0xffff
        var vPid = 0xffff
        var aPid = 0xffff

        val vPes = ByteArrayOutputStream(65536)
        val aPes = ByteArrayOutputStream(16384)
        val vUnits = ArrayList<RawAu>()
        val aAdts = ArrayList<ByteArray>()

        fun flushVideo() {
            if (vPes.size() == 0) return
            val pes = vPes.toByteArray()
            vPes.reset()
            val parsed = parsePes(pes) ?: return
            vUnits.add(RawAu(parsed.first, parsed.second, pes.copyOfRange(parsed.third, pes.size)))
        }

        fun flushAudio() {
            if (aPes.size() == 0) return
            val pes = aPes.toByteArray()
            aPes.reset()
            val parsed = parsePes(pes) ?: return
            var p = parsed.third
            while (p + 7 <= pes.size) {
                if ((pes[p].toInt() and 0xff) == 0xff && (pes[p + 1].toInt() and 0xf0) == 0xf0) {
                    val flen = (((pes[p + 3].toInt() and 0x03) shl 11) or
                        ((pes[p + 4].toInt() and 0xff) shl 3) or
                        ((pes[p + 5].toInt() and 0xff) ushr 5))
                    if (flen > 7 && p + flen <= pes.size) {
                        aAdts.add(pes.copyOfRange(p, p + flen))
                        p += flen
                        continue
                    }
                }
                p++
            }
        }

        while (pos + 188 <= sz) {
            if (d[pos] != 0x47.toByte()) {
                pos++
                continue
            }
            val b1 = d[pos + 1].toInt() and 0xff
            val b2 = d[pos + 2].toInt() and 0xff
            val b3 = d[pos + 3].toInt() and 0xff
            val pusi = (b1 and 0x40) != 0
            val pid = ((b1 and 0x1f) shl 8) or b2
            val afc = (b3 ushr 4) and 0x03

            if (afc == 2) {
                pos += 188
                continue
            }
            val poff = pos + 4 + if (afc == 3) 1 + (d[pos + 4].toInt() and 0xff) else 0
            val pktEnd = pos + 188
            if (poff >= pktEnd) {
                pos += 188
                continue
            }

            when (pid) {
                0 -> {
                    val p = poff + if (pusi) 1 + (d[poff].toInt() and 0xff) else 0
                    if (p + 12 <= pktEnd) {
                        val slen = ((d[p + 1].toInt() and 0x0f) shl 8) or (d[p + 2].toInt() and 0xff)
                        val end = minOf(pktEnd, p + 3 + slen - 4)
                        var ep = p + 8
                        while (ep + 4 <= end) {
                            if (UbitMp4Muxer.rdU16(d, ep) != 0) {
                                pmtPid = UbitMp4Muxer.rdU16(d, ep + 2) and 0x1fff
                                break
                            }
                            ep += 4
                        }
                    }
                }
                pmtPid -> {
                    val p = poff + if (pusi) 1 + (d[poff].toInt() and 0xff) else 0
                    if (p + 12 <= pktEnd) {
                        val slen = ((d[p + 1].toInt() and 0x0f) shl 8) or (d[p + 2].toInt() and 0xff)
                        val pil = ((d[p + 10].toInt() and 0x0f) shl 8) or (d[p + 11].toInt() and 0xff)
                        val end = minOf(pktEnd, p + 3 + slen - 4)
                        var ep = p + 12 + pil
                        while (ep + 5 <= end) {
                            val st = d[ep].toInt() and 0xff
                            val epid = UbitMp4Muxer.rdU16(d, ep + 1) and 0x1fff
                            val esl = UbitMp4Muxer.rdU16(d, ep + 3) and 0x0fff
                            if (st == 0x1b && vPid == 0xffff) vPid = epid
                            else if (st == 0x0f && aPid == 0xffff) aPid = epid
                            ep += 5 + esl
                        }
                    }
                }
                vPid -> {
                    if (pusi) flushVideo()
                    vPes.write(d, poff, pktEnd - poff)
                }
                aPid -> {
                    if (pusi) flushAudio()
                    aPes.write(d, poff, pktEnd - poff)
                }
            }
            pos += 188
        }
        flushVideo()
        flushAudio()

        val out = ArrayList<UbitMp4Track>(2)
        buildH264Track(vUnits)?.let { out.add(it) }
        buildAacTrack(aAdts)?.let { out.add(it) }
        return out
    }

    private fun parsePes(pes: ByteArray): Triple<Long, Long, Int>? {
        if (pes.size < 9 || pes[0] != 0.toByte() || pes[1] != 0.toByte() || pes[2] != 1.toByte()) return null
        val f2 = pes[7].toInt() and 0xff
        val esOff = 9 + (pes[8].toInt() and 0xff)
        if (esOff > pes.size) return null

        fun rpts(o: Int): Long {
            val b0 = (pes[o].toLong() ushr 1) and 0x07L
            val b1 = pes[o + 1].toLong() and 0xffL
            val b2 = (pes[o + 2].toLong() ushr 1) and 0x7fL
            val b3 = pes[o + 3].toLong() and 0xffL
            val b4 = (pes[o + 4].toLong() ushr 1) and 0x7fL
            return (b0 shl 30) or (b1 shl 22) or (b2 shl 15) or (b3 shl 7) or b4
        }

        var pts = 0L
        var dts = 0L
        if ((f2 and 0x80) != 0 && pes.size >= 14) {
            pts = rpts(9)
            dts = pts
            if ((f2 and 0x40) != 0 && pes.size >= 19) dts = rpts(14)
        }
        return Triple(pts, dts, esOff)
    }

    private fun buildH264Track(units: List<RawAu>): UbitMp4Track? {
        if (units.isEmpty()) return null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val samples = ArrayList<UbitMp4Sample>(units.size)

        for (idx in units.indices) {
            val u = units[idx].es
            val sampleBuf = UbitBinBuf(u.size + 16)
            var idr = false
            var vcl = false
            var i = 0
            var ns = -1

            fun emitNal(a: Int, b: Int) {
                if (b <= a) return
                val nt = u[a].toInt() and 0x1f
                if (nt == 7) { sps = u.copyOfRange(a, b); return }
                if (nt == 8) { pps = u.copyOfRange(a, b); return }
                if (nt == 9) return
                if (nt == 5) { idr = true; vcl = true }
                else if (nt in 1..4) vcl = true
                sampleBuf.u32((b - a).toLong())
                sampleBuf.bytes(u, a, b - a)
            }

            while (i + 3 <= u.size) {
                var scl = 0
                if (u[i] == 0.toByte() && u[i + 1] == 0.toByte()) {
                    if (u[i + 2] == 1.toByte()) scl = 3
                    else if (i + 4 <= u.size && u[i + 2] == 0.toByte() && u[i + 3] == 1.toByte()) scl = 4
                }
                if (scl > 0) {
                    if (ns >= 0) emitNal(ns, i)
                    ns = i + scl
                    i += scl
                } else {
                    i++
                }
            }
            if (ns >= 0 && ns < u.size) emitNal(ns, u.size)

            if (vcl && sampleBuf.size > 0) {
                val nextDts = if (idx + 1 < units.size) units[idx + 1].dts90k else (units[idx].dts90k + 3000L)
                val dur = (nextDts - units[idx].dts90k).coerceAtLeast(1L)
                val cts = (units[idx].pts90k - units[idx].dts90k).toInt()
                val rawSample = sampleBuf.toByteArray()
                samples.add(UbitMp4Sample(0, 0L, rawSample.size, dur, cts, idr, rawSample))
            }
        }

        val spsBytes = sps ?: return null
        val ppsBytes = pps ?: return null
        if (spsBytes.size < 4 || ppsBytes.isEmpty() || samples.isEmpty()) return null
        val (w, h) = parseSpsDimensions(spsBytes)

        val stsd = UbitBinBuf(256).apply {
            u8(0); u24(0); u32(1L)
            box("avc1") { b ->
                b.zeros(6); b.u16(1); b.zeros(16); b.u16(w); b.u16(h)
                b.u32(0x00480000L); b.u32(0x00480000L); b.u32(0L); b.u16(1); b.zeros(32); b.u16(0x0018); b.u16(0xffff)
                b.box("avcC") { c ->
                    c.u8(1)
                    c.u8(spsBytes[1].toInt() and 0xff)
                    c.u8(spsBytes[2].toInt() and 0xff)
                    c.u8(spsBytes[3].toInt() and 0xff)
                    c.u8(0xff)
                    c.u8(0xe1)
                    c.u16(spsBytes.size); c.bytes(spsBytes)
                    c.u8(1); c.u16(ppsBytes.size); c.bytes(ppsBytes)
                }
            }
        }.toByteArray()

        return UbitMp4Track(1, "vide", 90000L, w, h, stsd, samples)
    }

    private fun parseSpsDimensions(sps: ByteArray): Pair<Int, Int> {
        val rbsp = ByteArrayOutputStream(sps.size)
        var i = 1
        while (i < sps.size) {
            if (i + 2 < sps.size && sps[i] == 0.toByte() && sps[i + 1] == 0.toByte() && sps[i + 2] == 3.toByte()) {
                rbsp.write(0); rbsp.write(0)
                i += 3
            } else {
                rbsp.write(sps[i].toInt() and 0xff)
                i++
            }
        }
        val b = rbsp.toByteArray()
        if (b.size < 4) return 1920 to 1080
        var bitPos = 24

        fun readBit(): Int {
            if (bitPos / 8 >= b.size) return 0
            val v = ((b[bitPos / 8].toInt() and 0xff) ushr (7 - (bitPos % 8))) and 1
            bitPos++
            return v
        }
        fun readBits(n: Int): Int {
            var v = 0
            for (k in 0 until n) v = (v shl 1) or readBit()
            return v
        }
        fun readUe(): Int {
            var zeros = 0
            while (readBit() == 0 && zeros < 30) zeros++
            return ((1 shl zeros) - 1) + readBits(zeros)
        }
        fun readSe(): Int {
            val v = readUe()
            val mag = (v + 1) / 2
            return if (v % 2 == 0) -mag else mag
        }

        readUe() // seq_parameter_set_id
        val profile = b[0].toInt() and 0xff
        var chromaFormat = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chromaFormat = readUe()
            if (chromaFormat == 3) readBit()
            readUe(); readUe(); readBit()
            if (readBit() == 1) {
                val cnt = if (chromaFormat != 3) 8 else 12
                for (idx in 0 until cnt) {
                    if (readBit() == 1) {
                        val sz = if (idx < 6) 16 else 64
                        var lastScale = 8
                        var nextScale = 8
                        for (s in 0 until sz) {
                            if (nextScale != 0) nextScale = (lastScale + readSe() + 256) and 0xff
                            lastScale = if (nextScale == 0) lastScale else nextScale
                        }
                    }
                }
            }
        }
        readUe() // log2_max_frame_num_minus4
        when (readUe()) {
            0 -> readUe()
            1 -> {
                readBit(); readSe(); readSe()
                val n = minOf(255, readUe())
                for (k in 0 until n) readSe()
            }
        }
        readUe()
        readBit()

        val picWidthMbs = readUe() + 1
        val picHeightMapUnits = readUe() + 1
        val frameMbsOnly = readBit()
        if (frameMbsOnly == 0) readBit()
        readBit()

        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (readBit() == 1) {
            cropL = readUe(); cropR = readUe(); cropT = readUe(); cropB = readUe()
        }
        val cropUnitX = if (chromaFormat == 0 || chromaFormat == 3) 1 else 2
        val cropUnitY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
        val w = (picWidthMbs * 16 - cropUnitX * (cropL + cropR)).coerceIn(16, 8192)
        val h = ((2 - frameMbsOnly) * picHeightMapUnits * 16 - cropUnitY * (cropT + cropB)).coerceIn(16, 8192)
        return w to h
    }

    private fun buildAacTrack(adtsFrames: List<ByteArray>): UbitMp4Track? {
        if (adtsFrames.isEmpty()) return null
        val srates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val f0 = adtsFrames[0]
        val prof = (((f0[2].toInt() and 0xff) ushr 6) and 0x03) + 1
        val sri = ((f0[2].toInt() and 0xff) ushr 2) and 0x0f
        val ch = (((f0[2].toInt() and 0x01) shl 2) or (((f0[3].toInt() and 0xff) ushr 6) and 0x03))
        val sr = if (sri in srates.indices) srates[sri] else 44100

        val samples = ArrayList<UbitMp4Sample>(adtsFrames.size)
        for (fr in adtsFrames) {
            val hl = if ((fr[1].toInt() and 0x01) != 0) 7 else 9
            if (fr.size > hl) {
                val raw = fr.copyOfRange(hl, fr.size)
                samples.add(UbitMp4Sample(0, 0L, raw.size, 1024L, 0, true, raw))
            }
        }
        if (samples.isEmpty()) return null

        val asc0 = (prof shl 3) or ((sri ushr 1) and 0x07)
        val asc1 = ((sri and 0x01) shl 7) or (ch shl 3)

        val stsd = UbitBinBuf(128).apply {
            u8(0); u24(0); u32(1L)
            box("mp4a") { b ->
                b.zeros(6); b.u16(1); b.zeros(8); b.u16(if (ch > 0) ch else 2); b.u16(16); b.u32(0L)
                b.u32((sr.toLong() and 0xffffL) shl 16)
                b.fullBox("esds", 0, 0) { e ->
                    e.u8(3); e.u8(25); e.u16(1); e.u8(0)
                    e.u8(4); e.u8(17); e.u8(0x40); e.u8(0x15); e.u24(0); e.u32(128000L); e.u32(128000L)
                    e.u8(5); e.u8(2); e.u8(asc0); e.u8(asc1)
                    e.u8(6); e.u8(1); e.u8(2)
                }
            }
        }.toByteArray()

        return UbitMp4Track(2, "soun", sr.toLong(), 0, 0, stsd, samples)
    }
}
