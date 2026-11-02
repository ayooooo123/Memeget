import Foundation

/// Pure-Swift 188-byte MPEG-TS (PAT / PMT / PES) demuxer for H.264 Annex-B + ADTS AAC -> MP4Track!
/// Enables zero-FFmpeg remuxing of `.ts` HLS streams directly into fast-start `.mp4` / `.m4a`.
public enum TSDemuxer {

    private struct RawAccessUnit {
        var pts90k: Int64
        var dts90k: Int64
        var payload: Data
    }

    public static func demuxToTracks(tsFileURL: URL) throws -> [MP4Track] {
        let data = try Data(contentsOf: tsFileURL, options: .mappedIfSafe)
        guard data.count >= 188 else { return [] }

        var startOff = 0
        while startOff + 376 < data.count {
            if data[data.startIndex + startOff] == 0x47 && data[data.startIndex + startOff + 188] == 0x47 {
                break
            }
            startOff += 1
        }

        var pmtPID: UInt16 = 0xffff
        var videoPID: UInt16 = 0xffff
        var audioPID: UInt16 = 0xffff

        var vPES = Data()
        var aPES = Data()
        var vUnits: [RawAccessUnit] = []
        var aFrames: [(pts90k: Int64, frame: Data)] = []

        func flushVideoPES() {
            guard !vPES.isEmpty else { return }
            if let (pts, dts, es) = parsePES(vPES) {
                vUnits.append(RawAccessUnit(pts90k: pts, dts90k: dts, payload: es))
            }
            vPES.removeAll(keepingCapacity: true)
        }

        func flushAudioPES() {
            guard !aPES.isEmpty else { return }
            if let (pts, _, es) = parsePES(aPES) {
                extractADTSFrames(es, basePTS: pts, into: &aFrames)
            }
            aPES.removeAll(keepingCapacity: true)
        }

        var pos = startOff
        while pos + 188 <= data.count {
            if data[data.startIndex + pos] != 0x47 {
                pos += 1
                continue
            }
            let b1 = data[data.startIndex + pos + 1]
            let b2 = data[data.startIndex + pos + 2]
            let b3 = data[data.startIndex + pos + 3]
            let pusi = (b1 & 0x40) != 0
            let pid = (UInt16(b1 & 0x1f) << 8) | UInt16(b2)
            let afc = (b3 >> 4) & 0x03

            var payloadOff = pos + 4
            if afc == 2 { pos += 188; continue }
            if afc == 3 {
                let afLen = Int(data[data.startIndex + payloadOff])
                payloadOff += 1 + afLen
            }
            let pktEnd = pos + 188
            if payloadOff >= pktEnd { pos += 188; continue }
            let slice = data[(data.startIndex + payloadOff)..<(data.startIndex + pktEnd)]

            if pid == 0 {
                var p = slice.startIndex
                if pusi && p < slice.endIndex { p += 1 + Int(slice[p]) }
                if p + 12 <= slice.endIndex {
                    let secLen = Int(((UInt16(slice[p + 1]) & 0x0f) << 8) | UInt16(slice[p + 2]))
                    let entriesEnd = min(slice.endIndex, p + 3 + secLen - 4)
                    var ep = p + 8
                    while ep + 4 <= entriesEnd {
                        let progNum = (UInt16(slice[ep]) << 8) | UInt16(slice[ep + 1])
                        let pPid = (UInt16(slice[ep + 2] & 0x1f) << 8) | UInt16(slice[ep + 3])
                        if progNum != 0 { pmtPID = pPid; break }
                        ep += 4
                    }
                }
            } else if pid == pmtPID {
                var p = slice.startIndex
                if pusi && p < slice.endIndex { p += 1 + Int(slice[p]) }
                if p + 12 <= slice.endIndex {
                    let secLen = Int(((UInt16(slice[p + 1]) & 0x0f) << 8) | UInt16(slice[p + 2]))
                    let progInfoLen = Int(((UInt16(slice[p + 10]) & 0x0f) << 8) | UInt16(slice[p + 11]))
                    let entriesEnd = min(slice.endIndex, p + 3 + secLen - 4)
                    var ep = p + 12 + progInfoLen
                    while ep + 5 <= entriesEnd {
                        let stype = slice[ep]
                        let ePid = (UInt16(slice[ep + 1] & 0x1f) << 8) | UInt16(slice[ep + 2])
                        let esInfoLen = Int(((UInt16(slice[ep + 3]) & 0x0f) << 8) | UInt16(slice[ep + 4]))
                        if stype == 0x1b && videoPID == 0xffff { videoPID = ePid }
                        else if stype == 0x0f && audioPID == 0xffff { audioPID = ePid }
                        ep += 5 + esInfoLen
                    }
                }
            } else if pid == videoPID {
                if pusi { flushVideoPES() }
                vPES.append(contentsOf: slice)
            } else if pid == audioPID {
                if pusi { flushAudioPES() }
                aPES.append(contentsOf: slice)
            }

            pos += 188
        }
        flushVideoPES()
        flushAudioPES()

        var outTracks: [MP4Track] = []
        if let vTrack = buildH264Track(from: vUnits) {
            outTracks.append(vTrack)
        }
        if let aTrack = buildAACTrack(from: aFrames) {
            outTracks.append(aTrack)
        }
        return outTracks
    }

    private static func parsePES(_ pes: Data) -> (pts: Int64, dts: Int64, es: Data)? {
        guard pes.count >= 9,
              pes[0] == 0x00, pes[1] == 0x00, pes[2] == 0x01 else { return nil }
        let flags2 = pes[7]
        let hdrDataLen = Int(pes[8])
        let esStart = 9 + hdrDataLen
        guard esStart <= pes.count else { return nil }

        var pts: Int64 = 0
        var dts: Int64 = 0
        if (flags2 & 0x80) != 0, pes.count >= 14 {
            pts = readPTS(pes, at: 9)
            dts = pts
            if (flags2 & 0x40) != 0, pes.count >= 19 {
                dts = readPTS(pes, at: 14)
            }
        }
        return (pts, dts, Data(pes[esStart...]))
    }

    private static func readPTS(_ d: Data, at off: Int) -> Int64 {
        let p0 = Int64((d[off] >> 1) & 0x07) << 30
        let p1 = Int64(d[off + 1]) << 22
        let p2 = Int64((d[off + 2] >> 1) & 0x7f) << 15
        let p3 = Int64(d[off + 3]) << 7
        let p4 = Int64((d[off + 4] >> 1) & 0x7f)
        return p0 | p1 | p2 | p3 | p4
    }

    private static func splitAnnexBNALUs(_ es: Data) -> [Data] {
        var nalus: [Data] = []
        let bytes = [UInt8](es)
        let n = bytes.count
        var i = 0
        var naluStart = -1

        while i + 3 <= n {
            var scLen = 0
            if bytes[i] == 0 && bytes[i + 1] == 0 {
                if bytes[i + 2] == 1 { scLen = 3 }
                else if i + 4 <= n && bytes[i + 2] == 0 && bytes[i + 3] == 1 { scLen = 4 }
            }
            if scLen > 0 {
                if naluStart >= 0 && i > naluStart {
                    nalus.append(Data(bytes[naluStart..<i]))
                }
                naluStart = i + scLen
                i += scLen
            } else {
                i += 1
            }
        }
        if naluStart >= 0 && naluStart < n {
            nalus.append(Data(bytes[naluStart..<n]))
        }
        return nalus
    }

    private static func buildH264Track(from units: [RawAccessUnit]) -> MP4Track? {
        guard !units.isEmpty else { return nil }
        var sps: Data?
        var pps: Data?
        var mp4Samples: [MP4Sample] = []

        for (idx, u) in units.enumerated() {
            let nalus = splitAnnexBNALUs(u.payload)
            var avccSample = BinaryBuffer(capacity: u.payload.count)
            var isIDR = false
            var hasVCL = false

            for nalu in nalus where !nalu.isEmpty {
                let nalType = nalu[nalu.startIndex] & 0x1f
                if nalType == 7 { sps = nalu; continue }
                if nalType == 8 { pps = nalu; continue }
                if nalType == 9 { continue }
                if nalType == 5 { isIDR = true; hasVCL = true }
                else if (1...4).contains(nalType) { hasVCL = true }

                avccSample.u32(UInt32(nalu.count))
                avccSample.bytes(nalu)
            }
            guard hasVCL, avccSample.count > 0 else { continue }

            let nextDTS = (idx + 1 < units.count) ? units[idx + 1].dts90k : (u.dts90k + 3000)
            let dur = UInt32(clamping: max(1, nextDTS - u.dts90k))
            let cts = Int32(clamping: u.pts90k - u.dts90k)
            mp4Samples.append(MP4Sample(
                size: UInt32(avccSample.count),
                duration: dur,
                ctsOffset: cts,
                isSync: isIDR,
                inlineData: avccSample.data
            ))
        }

        guard let sps, sps.count >= 4, let pps, !mp4Samples.isEmpty else { return nil }
        let (width, height) = parseH264SPSDimensions(sps)

        var stsd = BinaryBuffer()
        stsd.u8(0); stsd.u24(0)
        stsd.u32(1)
        stsd.box("avc1") { b in
            b.zeros(6)
            b.u16(1)
            b.zeros(16)
            b.u16(width > 0 ? width : 1920)
            b.u16(height > 0 ? height : 1080)
            b.u32(0x00480000)
            b.u32(0x00480000)
            b.u32(0)
            b.u16(1)
            b.zeros(32)
            b.u16(0x0018)
            b.u16(0xffff)
            b.box("avcC") { c in
                c.u8(1)
                c.u8(sps[sps.startIndex + 1])
                c.u8(sps[sps.startIndex + 2])
                c.u8(sps[sps.startIndex + 3])
                c.u8(0xff)
                c.u8(0xe1)
                c.u16(UInt16(sps.count))
                c.bytes(sps)
                c.u8(1)
                c.u16(UInt16(pps.count))
                c.bytes(pps)
            }
        }

        return MP4Track(
            trackID: 1,
            kind: .video,
            timescale: 90_000,
            width: width > 0 ? width : 1920,
            height: height > 0 ? height : 1080,
            stsdPayload: stsd.data,
            samples: mp4Samples
        )
    }

    private static func parseH264SPSDimensions(_ sps: Data) -> (UInt16, UInt16) {
        var rbsp: [UInt8] = []
        let src = [UInt8](sps.dropFirst())
        var i = 0
        while i < src.count {
            if i + 2 < src.count && src[i] == 0 && src[i + 1] == 0 && src[i + 2] == 3 {
                rbsp.append(0); rbsp.append(0)
                i += 3
            } else {
                rbsp.append(src[i])
                i += 1
            }
        }
        guard rbsp.count >= 4 else { return (1920, 1080) }
        var bitPos = 24

        func readBit() -> UInt32 {
            guard bitPos / 8 < rbsp.count else { return 0 }
            let v = (rbsp[bitPos / 8] >> (7 - (bitPos % 8))) & 1
            bitPos += 1
            return UInt32(v)
        }
        func readBits(_ n: Int) -> UInt32 {
            var v: UInt32 = 0
            for _ in 0..<n { v = (v << 1) | readBit() }
            return v
        }
        func readUE() -> UInt32 {
            var zeros = 0
            while readBit() == 0 && zeros < 31 { zeros += 1 }
            return ((1 << zeros) - 1) + readBits(zeros)
        }
        func readSE() -> Int32 {
            let v = readUE()
            let mag = Int32((v + 1) / 2)
            return (v % 2 == 0) ? -mag : mag
        }

        _ = readUE()
        let profile = rbsp[0]
        var chromaFormat: UInt32 = 1
        if [100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135].contains(profile) {
            chromaFormat = readUE()
            if chromaFormat == 3 { _ = readBit() }
            _ = readUE()
            _ = readUE()
            _ = readBit()
            if readBit() == 1 {
                let cnt = chromaFormat != 3 ? 8 : 12
                for idx in 0..<cnt {
                    if readBit() == 1 {
                        let sz = idx < 6 ? 16 : 64
                        var lastScale = 8, nextScale = 8
                        for _ in 0..<sz {
                            if nextScale != 0 {
                                nextScale = (lastScale + Int(readSE()) + 256) % 256
                            }
                            lastScale = nextScale == 0 ? lastScale : nextScale
                        }
                    }
                }
            }
        }
        _ = readUE()
        let pocType = readUE()
        if pocType == 0 { _ = readUE() }
        else if pocType == 1 {
            _ = readBit(); _ = readSE(); _ = readSE()
            let n = readUE()
            for _ in 0..<min(255, n) { _ = readSE() }
        }
        _ = readUE()
        _ = readBit()

        let picWidthMbs = readUE() + 1
        let picHeightMapUnits = readUE() + 1
        let frameMbsOnly = readBit()
        if frameMbsOnly == 0 { _ = readBit() }
        _ = readBit()

        var cropLeft: UInt32 = 0, cropRight: UInt32 = 0, cropTop: UInt32 = 0, cropBottom: UInt32 = 0
        if readBit() == 1 {
            cropLeft = readUE()
            cropRight = readUE()
            cropTop = readUE()
            cropBottom = readUE()
        }
        let cropUnitX: UInt32 = (chromaFormat == 0) ? 1 : (chromaFormat == 3 ? 1 : 2)
        let cropUnitY: UInt32 = ((chromaFormat == 1 ? 2 : 1)) * (2 - frameMbsOnly)
        let w = picWidthMbs * 16 - cropUnitX * (cropLeft + cropRight)
        let h = (2 - frameMbsOnly) * picHeightMapUnits * 16 - cropUnitY * (cropTop + cropBottom)
        return (UInt16(clamping: w), UInt16(clamping: h))
    }

    private static let aacSampleRates: [UInt32] = [
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    ]

    private static func extractADTSFrames(_ es: Data, basePTS: Int64, into out: inout [(pts90k: Int64, frame: Data)]) {
        let bytes = [UInt8](es)
        var pos = 0
        while pos + 7 <= bytes.count {
            if bytes[pos] == 0xff && (bytes[pos + 1] & 0xf0) == 0xf0 {
                let noCRC = (bytes[pos + 1] & 0x01) != 0
                let hdrLen = noCRC ? 7 : 9
                let frameLen = (Int(bytes[pos + 3] & 0x03) << 11) | (Int(bytes[pos + 4]) << 3) | Int(bytes[pos + 5] >> 5)
                if frameLen > hdrLen && pos + frameLen <= bytes.count {
                    out.append((basePTS, Data(bytes[pos..<(pos + frameLen)])))
                    pos += frameLen
                    continue
                }
            }
            pos += 1
        }
    }

    private static func buildAACTrack(from adtsFrames: [(pts90k: Int64, frame: Data)]) -> MP4Track? {
        guard let first = adtsFrames.first?.frame, first.count >= 7 else { return nil }
        let b2 = first[first.startIndex + 2]
        let b3 = first[first.startIndex + 3]
        let profileObjectType = ((b2 >> 6) & 0x03) + 1
        let srIdx = Int((b2 >> 2) & 0x0f)
        let chanCfg = ((b2 & 0x01) << 2) | ((b3 >> 6) & 0x03)
        let sampleRate = srIdx < aacSampleRates.count ? aacSampleRates[srIdx] : 44100

        var samples: [MP4Sample] = []
        samples.reserveCapacity(adtsFrames.count)
        for (_, f) in adtsFrames {
            let noCRC = (f[f.startIndex + 1] & 0x01) != 0
            let hdrLen = noCRC ? 7 : 9
            guard f.count > hdrLen else { continue }
            let rawAAC = Data(f.dropFirst(hdrLen))
            samples.append(MP4Sample(size: UInt32(rawAAC.count), duration: 1024, inlineData: rawAAC))
        }
        guard !samples.isEmpty else { return nil }

        let asc0 = (profileObjectType << 3) | UInt8((srIdx >> 1) & 0x07)
        let asc1 = (UInt8(srIdx & 0x01) << 7) | (chanCfg << 3)

        var stsd = BinaryBuffer()
        stsd.u8(0); stsd.u24(0)
        stsd.u32(1)
        stsd.box("mp4a") { b in
            b.zeros(6)
            b.u16(1)
            b.zeros(8)
            b.u16(UInt16(chanCfg == 0 ? 2 : chanCfg))
            b.u16(16)
            b.u32(0)
            b.u32(sampleRate << 16)
            b.fullBox("esds", version: 0, flags: 0) { e in
                e.u8(0x03); e.u8(25)
                e.u16(1); e.u8(0)
                e.u8(0x04); e.u8(17)
                e.u8(0x40)
                e.u8(0x15)
                e.u24(0)
                e.u32(128_000)
                e.u32(128_000)
                e.u8(0x05); e.u8(2)
                e.u8(asc0); e.u8(asc1)
                e.u8(0x06); e.u8(1); e.u8(0x02)
            }
        }

        return MP4Track(
            trackID: 2,
            kind: .audio,
            timescale: sampleRate,
            stsdPayload: stsd.data,
            samples: samples
        )
    }
}
