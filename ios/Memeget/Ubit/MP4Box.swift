import Foundation

// MARK: - Binary Writer / Reader Helpers

public struct BinaryBuffer {
    public private(set) var data: Data

    public init(capacity: Int = 4096) {
        self.data = Data()
        self.data.reserveCapacity(capacity)
    }

    public var count: Int { data.count }

    public mutating func u8(_ v: UInt8) { data.append(v) }
    public mutating func u16(_ v: UInt16) {
        var be = v.bigEndian
        withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
    }
    public mutating func u24(_ v: UInt32) {
        data.append(UInt8((v >> 16) & 0xff))
        data.append(UInt8((v >> 8) & 0xff))
        data.append(UInt8(v & 0xff))
    }
    public mutating func u32(_ v: UInt32) {
        var be = v.bigEndian
        withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
    }
    public mutating func i32(_ v: Int32) { u32(UInt32(bitPattern: v)) }
    public mutating func u64(_ v: UInt64) {
        var be = v.bigEndian
        withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
    }
    public mutating func fourCC(_ s: String) {
        let utf = Array(s.utf8)
        for i in 0..<4 {
            data.append(i < utf.count ? utf[i] : 0x20)
        }
    }
    public mutating func bytes(_ d: Data) { data.append(d) }
    public mutating func zeros(_ n: Int) {
        if n > 0 { data.append(Data(repeating: 0, count: n)) }
    }

    public mutating func box(_ type: String, _ build: (inout BinaryBuffer) -> Void) {
        let start = data.count
        u32(0)
        fourCC(type)
        build(&self)
        let sz = UInt32(data.count - start)
        var be = sz.bigEndian
        withUnsafeBytes(of: &be) { ptr in
            data.replaceSubrange(start..<(start + 4), with: ptr)
        }
    }

    public mutating func boxRaw(_ rawFourCC: [UInt8], _ build: (inout BinaryBuffer) -> Void) {
        let start = data.count
        u32(0)
        for i in 0..<4 { data.append(i < rawFourCC.count ? rawFourCC[i] : 0x20) }
        build(&self)
        let sz = UInt32(data.count - start)
        var be = sz.bigEndian
        withUnsafeBytes(of: &be) { ptr in
            data.replaceSubrange(start..<(start + 4), with: ptr)
        }
    }

    public mutating func fullBox(_ type: String, version: UInt8, flags: UInt32, _ build: (inout BinaryBuffer) -> Void) {
        box(type) { b in
            b.u8(version)
            b.u24(flags)
            build(&b)
        }
    }
}

extension Data {
    @inline(__always) func beU16(_ off: Int) -> UInt16 {
        guard off + 2 <= count else { return 0 }
        return (UInt16(self[startIndex + off]) << 8) | UInt16(self[startIndex + off + 1])
    }
    @inline(__always) func beU32(_ off: Int) -> UInt32 {
        guard off + 4 <= count else { return 0 }
        let s = startIndex + off
        return (UInt32(self[s]) << 24) | (UInt32(self[s+1]) << 16) | (UInt32(self[s+2]) << 8) | UInt32(self[s+3])
    }
    @inline(__always) func beI32(_ off: Int) -> Int32 {
        Int32(bitPattern: beU32(off))
    }
    @inline(__always) func beU64(_ off: Int) -> UInt64 {
        guard off + 8 <= count else { return 0 }
        return (UInt64(beU32(off)) << 32) | UInt64(beU32(off + 4))
    }
    @inline(__always) func fourCC(_ off: Int) -> String {
        guard off + 4 <= count else { return "" }
        let s = startIndex + off
        return String(bytes: self[s..<(s + 4)], encoding: .isoLatin1) ?? ""
    }
}

// MARK: - MP4 Data Structures

public struct MP4BoxRef: Sendable {
    public var type: String
    public var headerOffset: Int
    public var payloadOffset: Int
    public var endOffset: Int
    public var payloadSize: Int { max(0, endOffset - payloadOffset) }
}

public struct MP4Sample: Sendable {
    public var sourceIndex: Int     // index into source FileHandle / Data array
    public var offset: UInt64       // byte offset inside source file, or inline payload index
    public var size: UInt32
    public var duration: UInt32
    public var ctsOffset: Int32
    public var isSync: Bool
    public var inlineData: Data?    // used for synthesized text/subtitle/TS samples

    public init(sourceIndex: Int = 0, offset: UInt64 = 0, size: UInt32, duration: UInt32, ctsOffset: Int32 = 0, isSync: Bool = true, inlineData: Data? = nil) {
        self.sourceIndex = sourceIndex
        self.offset = offset
        self.size = size
        self.duration = duration
        self.ctsOffset = ctsOffset
        self.isSync = isSync
        self.inlineData = inlineData
    }
}

public enum MP4TrackKind: String, Sendable {
    case video = "vide"
    case audio = "soun"
    case subtitle = "sbtl"
    case text = "text"
}

public struct MP4Track: Sendable {
    public var trackID: UInt32
    public var kind: MP4TrackKind
    public var timescale: UInt32
    public var width: UInt16
    public var height: UInt16
    public var languageISO639_2: String // 3-char e.g. "eng", "und"
    public var stsdPayload: Data       // full payload of `stsd` (starting at version/flags + entry_count + sampleEntry)
    public var samples: [MP4Sample]
    public var sourceURL: URL?

    public init(
        trackID: UInt32,
        kind: MP4TrackKind,
        timescale: UInt32,
        width: UInt16 = 0,
        height: UInt16 = 0,
        languageISO639_2: String = "und",
        stsdPayload: Data,
        samples: [MP4Sample],
        sourceURL: URL? = nil
    ) {
        self.trackID = trackID
        self.kind = kind
        self.timescale = max(1, timescale)
        self.width = width
        self.height = height
        self.languageISO639_2 = languageISO639_2
        self.stsdPayload = stsdPayload
        self.samples = samples
        self.sourceURL = sourceURL
    }

    public var totalTicks: UInt64 {
        samples.reduce(0) { $0 + UInt64($1.duration) }
    }

    public var durationSeconds: Double {
        Double(totalTicks) / Double(max(1, timescale))
    }
}

public struct MP4Metadata: Sendable {
    public var title: String?
    public var artist: String?
    public var date: String?
    public var webpageURL: String?
    public var description: String?
    public var coverJPEG: Data?
    public var chapters: [Chapter]

    public init(
        title: String? = nil,
        artist: String? = nil,
        date: String? = nil,
        webpageURL: String? = nil,
        description: String? = nil,
        coverJPEG: Data? = nil,
        chapters: [Chapter] = []
    ) {
        self.title = title
        self.artist = artist
        self.date = date
        self.webpageURL = webpageURL
        self.description = description
        self.coverJPEG = coverJPEG
        self.chapters = chapters
    }
}

public struct SidxSegment: Sendable {
    public var byteRange: ByteRange
    public var startTimeSec: Double
    public var durationSec: Double
}

// MARK: - ISO-BMFF Parser, Planner, Trimmer & Fast-Start Writer

public enum MP4Muxer {

    public static func listBoxes(in data: Data, from start: Int, to end: Int) -> [MP4BoxRef] {
        var out: [MP4BoxRef] = []
        var pos = start
        let limit = min(end, data.count)
        while pos + 8 <= limit {
            let sz32 = data.beU32(pos)
            let btype = data.fourCC(pos + 4)
            var hdrLen = 8
            var boxEnd: Int
            if sz32 == 1 {
                guard pos + 16 <= limit else { break }
                let sz64 = data.beU64(pos + 8)
                hdrLen = 16
                boxEnd = pos + Int(clamping: sz64)
            } else if sz32 == 0 {
                boxEnd = limit
            } else {
                boxEnd = pos + Int(sz32)
            }
            if boxEnd < pos + hdrLen || boxEnd > limit { break }
            out.append(MP4BoxRef(type: btype, headerOffset: pos, payloadOffset: pos + hdrLen, endOffset: boxEnd))
            pos = boxEnd
        }
        return out
    }

    /// Parses `sidx` (Segment Index box) from the header bytes of a DASH/YouTube stream
    /// so `--section start-end` downloads ONLY the required byte ranges!
    public static func parseSidx(in headerData: Data) -> (initEndOffset: Int64, segments: [SidxSegment])? {
        let boxes = listBoxes(in: headerData, from: 0, to: headerData.count)
        guard let sidx = boxes.first(where: { $0.type == "sidx" }) else { return nil }
        let p = sidx.payloadOffset
        guard p + 12 <= sidx.endOffset else { return nil }
        let version = headerData[headerData.startIndex + p]
        let timescale = headerData.beU32(p + 8)
        guard timescale > 0 else { return nil }

        var off = p + 12
        let earliestPTS: UInt64
        let firstOffset: UInt64
        if version == 0 {
            guard off + 8 <= sidx.endOffset else { return nil }
            earliestPTS = UInt64(headerData.beU32(off))
            firstOffset = UInt64(headerData.beU32(off + 4))
            off += 8
        } else {
            guard off + 16 <= sidx.endOffset else { return nil }
            earliestPTS = headerData.beU64(off)
            firstOffset = headerData.beU64(off + 8)
            off += 16
        }
        guard off + 4 <= sidx.endOffset else { return nil }
        let refCount = Int(headerData.beU16(off + 2))
        off += 4

        var curByte = Int64(sidx.endOffset) + Int64(firstOffset)
        var curTicks = earliestPTS
        var segs: [SidxSegment] = []

        for _ in 0..<refCount {
            guard off + 12 <= sidx.endOffset else { break }
            let sizeWord = headerData.beU32(off)
            let refSize = Int64(sizeWord & 0x7fffffff)
            let subsegDur = UInt64(headerData.beU32(off + 4))
            off += 12
            if refSize > 0 {
                let startSec = Double(curTicks) / Double(timescale)
                let durSec = Double(subsegDur) / Double(timescale)
                segs.append(SidxSegment(
                    byteRange: ByteRange(start: curByte, end: curByte + refSize - 1),
                    startTimeSec: startSec,
                    durationSec: durSec
                ))
                curByte += refSize
                curTicks += subsegDur
            }
        }
        guard !segs.isEmpty else { return nil }
        return (Int64(sidx.endOffset - 1), segs)
    }

    /// Reads all video and audio tracks from either a standard MP4/M4A or a fragmented fMP4 file.
    public static func readTracks(from fileURL: URL, sourceIndex: Int = 0) throws -> [MP4Track] {
        let data = try Data(contentsOf: fileURL, options: .mappedIfSafe)
        let topBoxes = listBoxes(in: data, from: 0, to: data.count)
        guard let moov = topBoxes.first(where: { $0.type == "moov" }) else {
            throw NSError(domain: "MP4Muxer", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing moov box in \(fileURL.lastPathComponent)"])
        }

        let moovChildren = listBoxes(in: data, from: moov.payloadOffset, to: moov.endOffset)

        // Default sample durations/sizes from mvex -> trex (for fragmented MP4)
        var trexDefaults: [UInt32: (dur: UInt32, size: UInt32, flags: UInt32)] = [:]
        if let mvex = moovChildren.first(where: { $0.type == "mvex" }) {
            for b in listBoxes(in: data, from: mvex.payloadOffset, to: mvex.endOffset) where b.type == "trex" {
                let p = b.payloadOffset
                if p + 24 <= b.endOffset {
                    let tid = data.beU32(p + 4)
                    let defDur = data.beU32(p + 12)
                    let defSize = data.beU32(p + 16)
                    let defFlags = data.beU32(p + 20)
                    trexDefaults[tid] = (defDur, defSize, defFlags)
                }
            }
        }

        var tracksByID: [UInt32: MP4Track] = [:]
        var orderedIDs: [UInt32] = []

        for trak in moovChildren where trak.type == "trak" {
            if let parsed = parseTrak(data: data, trak: trak, sourceURL: fileURL, sourceIndex: sourceIndex) {
                tracksByID[parsed.trackID] = parsed
                orderedIDs.append(parsed.trackID)
            }
        }

        // Parse any fragmented MP4 `moof` boxes!
        for box in topBoxes where box.type == "moof" {
            parseMoof(data: data, moof: box, trexDefaults: trexDefaults, sourceIndex: sourceIndex, tracks: &tracksByID)
        }

        return orderedIDs.compactMap { tracksByID[$0] }.filter { !$0.samples.isEmpty }
    }

    private static func parseTrak(data: Data, trak: MP4BoxRef, sourceURL: URL, sourceIndex: Int) -> MP4Track? {
        let children = listBoxes(in: data, from: trak.payloadOffset, to: trak.endOffset)
        var trackID: UInt32 = 1
        var width: UInt16 = 0
        var height: UInt16 = 0

        if let tkhd = children.first(where: { $0.type == "tkhd" }) {
            let p = tkhd.payloadOffset
            let ver = data[data.startIndex + p]
            if ver == 0 && p + 84 <= tkhd.endOffset {
                trackID = data.beU32(p + 12)
                width = UInt16(data.beU32(p + 76) >> 16)
                height = UInt16(data.beU32(p + 80) >> 16)
            } else if ver == 1 && p + 96 <= tkhd.endOffset {
                trackID = data.beU32(p + 20)
                width = UInt16(data.beU32(p + 88) >> 16)
                height = UInt16(data.beU32(p + 92) >> 16)
            }
        }

        guard let mdia = children.first(where: { $0.type == "mdia" }) else { return nil }
        let mdiaChildren = listBoxes(in: data, from: mdia.payloadOffset, to: mdia.endOffset)

        var timescale: UInt32 = 1000
        var lang = "und"
        if let mdhd = mdiaChildren.first(where: { $0.type == "mdhd" }) {
            let p = mdhd.payloadOffset
            let ver = data[data.startIndex + p]
            let langWord: UInt16
            if ver == 0 && p + 24 <= mdhd.endOffset {
                timescale = data.beU32(p + 12)
                langWord = data.beU16(p + 20)
            } else if ver == 1 && p + 36 <= mdhd.endOffset {
                timescale = data.beU32(p + 20)
                langWord = data.beU16(p + 32)
            } else {
                langWord = 0
            }
            if langWord > 0 {
                let c1 = UInt8(((langWord >> 10) & 0x1f) + 0x60)
                let c2 = UInt8(((langWord >> 5) & 0x1f) + 0x60)
                let c3 = UInt8((langWord & 0x1f) + 0x60)
                if (97...122).contains(c1) && (97...122).contains(c2) && (97...122).contains(c3) {
                    lang = String(bytes: [c1, c2, c3], encoding: .ascii) ?? "und"
                }
            }
        }

        var handlerType = ""
        if let hdlr = mdiaChildren.first(where: { $0.type == "hdlr" }), hdlr.payloadOffset + 12 <= hdlr.endOffset {
            handlerType = data.fourCC(hdlr.payloadOffset + 8)
        }
        let kind: MP4TrackKind
        switch handlerType {
        case "vide": kind = .video
        case "soun": kind = .audio
        case "sbtl", "subt": kind = .subtitle
        case "text": kind = .text
        default: return nil
        }

        guard let minf = mdiaChildren.first(where: { $0.type == "minf" }),
              let stbl = listBoxes(in: data, from: minf.payloadOffset, to: minf.endOffset).first(where: { $0.type == "stbl" }) else {
            return nil
        }
        let stblBoxes = listBoxes(in: data, from: stbl.payloadOffset, to: stbl.endOffset)
        guard let stsd = stblBoxes.first(where: { $0.type == "stsd" }) else { return nil }
        let stsdPayload = Data(data[(data.startIndex + stsd.payloadOffset)..<(data.startIndex + stsd.endOffset)])

        let samples = parseStblSamples(data: data, stblBoxes: stblBoxes, sourceIndex: sourceIndex, defaultSync: kind != .video)
        return MP4Track(
            trackID: trackID,
            kind: kind,
            timescale: timescale,
            width: width,
            height: height,
            languageISO639_2: lang,
            stsdPayload: stsdPayload,
            samples: samples,
            sourceURL: sourceURL
        )
    }

    private static func parseStblSamples(data: Data, stblBoxes: [MP4BoxRef], sourceIndex: Int, defaultSync: Bool) -> [MP4Sample] {
        var sampleSizes: [UInt32] = []
        if let stsz = stblBoxes.first(where: { $0.type == "stsz" }), stsz.payloadOffset + 12 <= stsz.endOffset {
            let p = stsz.payloadOffset
            let fixedSize = data.beU32(p + 4)
            let count = Int(data.beU32(p + 8))
            if count > 0 {
                if fixedSize != 0 {
                    sampleSizes = [UInt32](repeating: fixedSize, count: count)
                } else {
                    sampleSizes.reserveCapacity(count)
                    var off = p + 12
                    for _ in 0..<count where off + 4 <= stsz.endOffset {
                        sampleSizes.append(data.beU32(off))
                        off += 4
                    }
                }
            }
        }
        guard !sampleSizes.isEmpty else { return [] }
        let sampleCount = sampleSizes.count

        var chunkOffsets: [UInt64] = []
        if let stco = stblBoxes.first(where: { $0.type == "stco" }), stco.payloadOffset + 8 <= stco.endOffset {
            let p = stco.payloadOffset
            let n = Int(data.beU32(p + 4))
            var off = p + 8
            chunkOffsets.reserveCapacity(n)
            for _ in 0..<n where off + 4 <= stco.endOffset {
                chunkOffsets.append(UInt64(data.beU32(off)))
                off += 4
            }
        } else if let co64 = stblBoxes.first(where: { $0.type == "co64" }), co64.payloadOffset + 8 <= co64.endOffset {
            let p = co64.payloadOffset
            let n = Int(data.beU32(p + 4))
            var off = p + 8
            chunkOffsets.reserveCapacity(n)
            for _ in 0..<n where off + 8 <= co64.endOffset {
                chunkOffsets.append(data.beU64(off))
                off += 8
            }
        }

        var sampleOffsets = [UInt64](repeating: 0, count: sampleCount)
        if let stsc = stblBoxes.first(where: { $0.type == "stsc" }), stsc.payloadOffset + 8 <= stsc.endOffset, !chunkOffsets.isEmpty {
            let p = stsc.payloadOffset
            let n = Int(data.beU32(p + 4))
            var runs: [(firstChunk: Int, samplesPerChunk: Int)] = []
            var off = p + 8
            for _ in 0..<n where off + 12 <= stsc.endOffset {
                runs.append((max(1, Int(data.beU32(off))), Int(data.beU32(off + 4))))
                off += 12
            }
            var sIdx = 0
            for rIdx in 0..<runs.count {
                let firstChunk0 = runs[rIdx].firstChunk - 1
                let endChunk0 = (rIdx + 1 < runs.count) ? min(chunkOffsets.count, runs[rIdx + 1].firstChunk - 1) : chunkOffsets.count
                let spc = runs[rIdx].samplesPerChunk
                if firstChunk0 >= endChunk0 || spc <= 0 { continue }
                for c in firstChunk0..<endChunk0 {
                    var byteCursor = chunkOffsets[c]
                    for _ in 0..<spc {
                        guard sIdx < sampleCount else { break }
                        sampleOffsets[sIdx] = byteCursor
                        byteCursor += UInt64(sampleSizes[sIdx])
                        sIdx += 1
                    }
                }
            }
        }

        var durations = [UInt32](repeating: 1024, count: sampleCount)
        if let stts = stblBoxes.first(where: { $0.type == "stts" }), stts.payloadOffset + 8 <= stts.endOffset {
            let p = stts.payloadOffset
            let n = Int(data.beU32(p + 4))
            var off = p + 8
            var sIdx = 0
            for _ in 0..<n where off + 8 <= stts.endOffset {
                let cnt = Int(data.beU32(off))
                let delta = data.beU32(off + 4)
                off += 8
                for _ in 0..<cnt where sIdx < sampleCount {
                    durations[sIdx] = delta
                    sIdx += 1
                }
            }
        }

        var ctsOffsets = [Int32](repeating: 0, count: sampleCount)
        if let ctts = stblBoxes.first(where: { $0.type == "ctts" }), ctts.payloadOffset + 8 <= ctts.endOffset {
            let p = ctts.payloadOffset
            let n = Int(data.beU32(p + 4))
            var off = p + 8
            var sIdx = 0
            for _ in 0..<n where off + 8 <= ctts.endOffset {
                let cnt = Int(data.beU32(off))
                let cOff = data.beI32(off + 4)
                off += 8
                for _ in 0..<cnt where sIdx < sampleCount {
                    ctsOffsets[sIdx] = cOff
                    sIdx += 1
                }
            }
        }

        var syncSet: Set<Int>?
        if let stss = stblBoxes.first(where: { $0.type == "stss" }), stss.payloadOffset + 8 <= stss.endOffset {
            let p = stss.payloadOffset
            let n = Int(data.beU32(p + 4))
            var s = Set<Int>(minimumCapacity: n)
            var off = p + 8
            for _ in 0..<n where off + 4 <= stss.endOffset {
                s.insert(Int(data.beU32(off)) - 1)
                off += 4
            }
            syncSet = s
        }

        var out: [MP4Sample] = []
        out.reserveCapacity(sampleCount)
        for i in 0..<sampleCount {
            let sync = syncSet.map { $0.contains(i) } ?? defaultSync
            out.append(MP4Sample(
                sourceIndex: sourceIndex,
                offset: sampleOffsets[i],
                size: sampleSizes[i],
                duration: durations[i],
                ctsOffset: ctsOffsets[i],
                isSync: sync
            ))
        }
        return out
    }

    private static func parseMoof(
        data: Data,
        moof: MP4BoxRef,
        trexDefaults: [UInt32: (dur: UInt32, size: UInt32, flags: UInt32)],
        sourceIndex: Int,
        tracks: inout [UInt32: MP4Track]
    ) {
        let moofStart = UInt64(moof.headerOffset)
        for traf in listBoxes(in: data, from: moof.payloadOffset, to: moof.endOffset) where traf.type == "traf" {
            let trafBoxes = listBoxes(in: data, from: traf.payloadOffset, to: traf.endOffset)
            guard let tfhd = trafBoxes.first(where: { $0.type == "tfhd" }), tfhd.payloadOffset + 8 <= tfhd.endOffset else { continue }
            let hp = tfhd.payloadOffset
            let tfFlags = data.beU32(hp) & 0x00ffffff
            let trackID = data.beU32(hp + 4)
            guard var track = tracks[trackID] ?? (tracks.count == 1 ? tracks.values.first : nil) else { continue }

            let trex = trexDefaults[trackID] ?? (1024, 0, 0)
            var hOff = hp + 8
            var baseDataOffset: UInt64 = moofStart
            if (tfFlags & 0x000001) != 0, hOff + 8 <= tfhd.endOffset {
                baseDataOffset = data.beU64(hOff)
                hOff += 8
            }
            if (tfFlags & 0x000002) != 0 { hOff += 4 }
            var defDur = trex.dur
            if (tfFlags & 0x000008) != 0, hOff + 4 <= tfhd.endOffset {
                defDur = data.beU32(hOff)
                hOff += 4
            }
            var defSize = trex.size
            if (tfFlags & 0x000010) != 0, hOff + 4 <= tfhd.endOffset {
                defSize = data.beU32(hOff)
                hOff += 4
            }
            var defFlags = trex.flags
            if (tfFlags & 0x000020) != 0, hOff + 4 <= tfhd.endOffset {
                defFlags = data.beU32(hOff)
                hOff += 4
            }

            var runningDataOffset = baseDataOffset
            for trun in trafBoxes where trun.type == "trun" && trun.payloadOffset + 8 <= trun.endOffset {
                let tp = trun.payloadOffset
                let trFlags = data.beU32(tp) & 0x00ffffff
                let count = Int(data.beU32(tp + 4))
                var tOff = tp + 8
                if (trFlags & 0x000001) != 0, tOff + 4 <= trun.endOffset {
                    let rel = Int64(data.beI32(tOff))
                    runningDataOffset = UInt64(Int64(baseDataOffset) + rel)
                    tOff += 4
                }
                var firstSampleFlags: UInt32?
                if (trFlags & 0x000004) != 0, tOff + 4 <= trun.endOffset {
                    firstSampleFlags = data.beU32(tOff)
                    tOff += 4
                }

                for i in 0..<count {
                    var sDur = defDur
                    var sSize = defSize
                    var sFlags = (i == 0 && firstSampleFlags != nil) ? firstSampleFlags! : defFlags
                    var sCTS: Int32 = 0

                    if (trFlags & 0x000100) != 0, tOff + 4 <= trun.endOffset {
                        sDur = data.beU32(tOff); tOff += 4
                    }
                    if (trFlags & 0x000200) != 0, tOff + 4 <= trun.endOffset {
                        sSize = data.beU32(tOff); tOff += 4
                    }
                    if (trFlags & 0x000400) != 0, tOff + 4 <= trun.endOffset {
                        sFlags = data.beU32(tOff); tOff += 4
                    }
                    if (trFlags & 0x000800) != 0, tOff + 4 <= trun.endOffset {
                        sCTS = data.beI32(tOff); tOff += 4
                    }
                    let isNonSync = (sFlags & 0x00010000) != 0
                    let isSync = track.kind != .video ? true : !isNonSync
                    track.samples.append(MP4Sample(
                        sourceIndex: sourceIndex,
                        offset: runningDataOffset,
                        size: sSize,
                        duration: sDur,
                        ctsOffset: sCTS,
                        isSync: isSync
                    ))
                    runningDataOffset += UInt64(sSize)
                }
            }
            tracks[track.trackID] = track
        }
    }

    public static func trimTracks(_ tracks: [MP4Track], section: SectionRange, baseStartTimeSec: Double = 0) -> [MP4Track] {
        let targetStart = max(0, (section.start ?? 0) - baseStartTimeSec)
        let targetEnd = section.end.map { max(targetStart, $0 - baseStartTimeSec) }

        var effectiveStart = targetStart
        if let vTrack = tracks.first(where: { $0.kind == .video }) {
            var accTicks: UInt64 = 0
            var lastSyncSec: Double = 0
            for s in vTrack.samples {
                let sec = Double(accTicks) / Double(vTrack.timescale)
                if s.isSync && sec <= targetStart + 0.001 {
                    lastSyncSec = sec
                }
                if sec > targetStart { break }
                accTicks += UInt64(s.duration)
            }
            effectiveStart = lastSyncSec
        }

        return tracks.map { track in
            var kept: [MP4Sample] = []
            var accTicks: UInt64 = 0
            for s in track.samples {
                let sStart = Double(accTicks) / Double(track.timescale)
                accTicks += UInt64(s.duration)
                let sEnd = Double(accTicks) / Double(track.timescale)
                if sEnd <= effectiveStart { continue }
                if let e = targetEnd, sStart >= e { break }
                kept.append(s)
            }
            var copy = track
            copy.samples = kept.isEmpty ? Array(track.samples.prefix(1)) : kept
            return copy
        }
    }

    public static func makeTimedTextTrack(
        cues: [(start: Double, end: Double, text: String)],
        language: String,
        isChapterTrack: Bool = false,
        totalDuration: Double
    ) -> MP4Track? {
        guard !cues.isEmpty else { return nil }
        let timescale: UInt32 = 1000
        let sorted = cues.sorted { $0.start < $1.start }

        var samples: [MP4Sample] = []
        var cursorTicks: UInt64 = 0

        func makeTextSamplePayload(_ str: String) -> Data {
            let utf = Data(str.utf8)
            var b = BinaryBuffer(capacity: 2 + utf.count)
            b.u16(UInt16(clamping: utf.count))
            b.bytes(utf)
            return b.data
        }

        for cue in sorted {
            let startTicks = UInt64(max(0, cue.start) * Double(timescale))
            let endTicks = max(startTicks + 100, UInt64(max(cue.start + 0.1, cue.end) * Double(timescale)))
            if startTicks > cursorTicks {
                let gap = UInt32(clamping: startTicks - cursorTicks)
                let empty = makeTextSamplePayload("")
                samples.append(MP4Sample(size: UInt32(empty.count), duration: gap, inlineData: empty))
                cursorTicks = startTicks
            }
            let dur = UInt32(clamping: endTicks > cursorTicks ? endTicks - cursorTicks : 100)
            let payload = makeTextSamplePayload(cue.text)
            samples.append(MP4Sample(size: UInt32(payload.count), duration: dur, inlineData: payload))
            cursorTicks += UInt64(dur)
        }

        let totalTicks = UInt64(max(0, totalDuration) * Double(timescale))
        if totalTicks > cursorTicks {
            let empty = makeTextSamplePayload("")
            samples.append(MP4Sample(size: UInt32(empty.count), duration: UInt32(clamping: totalTicks - cursorTicks), inlineData: empty))
        }

        var stsd = BinaryBuffer()
        stsd.u8(0); stsd.u24(0)
        stsd.u32(1)
        stsd.box("tx3g") { e in
            e.zeros(6)
            e.u16(1)
            e.u32(0)
            e.u8(1)
            e.u8(0xff)
            e.u32(0x000000ff)
            e.zeros(8)
            e.zeros(8)
            e.u32(0xffffffff)
            e.box("ftab") { f in
                f.u16(1)
                f.u16(1)
                let fn = Data("Sans-Serif".utf8)
                f.u8(UInt8(fn.count))
                f.bytes(fn)
            }
        }

        return MP4Track(
            trackID: 99,
            kind: isChapterTrack ? .text : .subtitle,
            timescale: timescale,
            languageISO639_2: "eng",
            stsdPayload: stsd.data,
            samples: samples
        )
    }

    public static func writeMP4(
        tracks inputTracks: [MP4Track],
        sourceFiles: [URL],
        destination: URL,
        isAudioOnlyM4A: Bool = false,
        metadata: MP4Metadata = MP4Metadata()
    ) throws {
        guard !inputTracks.isEmpty else {
            throw NSError(domain: "MP4Muxer", code: 2, userInfo: [NSLocalizedDescriptionKey: "No media tracks to mux"])
        }

        var tracks = inputTracks
        let maxDuration = tracks.map(\.durationSeconds).max() ?? 0

        var chapterTrackID: UInt32?
        if !metadata.chapters.isEmpty {
            let cues = metadata.chapters.map { (start: $0.startTime, end: $0.endTime, text: $0.title) }
            if var chapTrack = makeTimedTextTrack(cues: cues, language: "eng", isChapterTrack: true, totalDuration: maxDuration) {
                chapTrack.trackID = UInt32(tracks.count + 1)
                chapterTrackID = chapTrack.trackID
                tracks.append(chapTrack)
            }
        }

        for i in 0..<tracks.count {
            tracks[i].trackID = UInt32(i + 1)
            if tracks[i].kind == .text && chapterTrackID != nil {
                chapterTrackID = tracks[i].trackID
            }
        }

        var relativeOffsets: [[UInt64]] = []
        var mdatPayloadBytes: UInt64 = 0
        for t in tracks {
            var offs: [UInt64] = []
            offs.reserveCapacity(t.samples.count)
            for s in t.samples {
                offs.append(mdatPayloadBytes)
                mdatPayloadBytes += UInt64(s.size)
            }
            relativeOffsets.append(offs)
        }

        let mdatHeaderLen: UInt64 = (mdatPayloadBytes + 8 > UInt64(UInt32.max)) ? 16 : 8

        var ftyp = BinaryBuffer(capacity: 32)
        ftyp.box("ftyp") { b in
            if isAudioOnlyM4A {
                b.fourCC("M4A ")
                b.u32(0)
                b.fourCC("M4A ")
                b.fourCC("mp42")
                b.fourCC("isom")
            } else {
                b.fourCC("isom")
                b.u32(0x200)
                b.fourCC("isom")
                b.fourCC("iso2")
                b.fourCC("avc1")
                b.fourCC("mp41")
            }
        }

        let dummyMoov = buildMoov(tracks: tracks, chunkBaseOffset: 0, relativeOffsets: relativeOffsets, metadata: metadata, chapterTrackID: chapterTrackID)
        let mdatPayloadStart = UInt64(ftyp.count + dummyMoov.count) + mdatHeaderLen
        let finalMoov = buildMoov(tracks: tracks, chunkBaseOffset: mdatPayloadStart, relativeOffsets: relativeOffsets, metadata: metadata, chapterTrackID: chapterTrackID)

        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        let partURL = destination.appendingPathExtension("mux.part")
        FileManager.default.createFile(atPath: partURL.path, contents: nil)
        let outFH = try FileHandle(forWritingTo: partURL)
        defer { try? outFH.close() }

        try outFH.write(contentsOf: ftyp.data)
        try outFH.write(contentsOf: finalMoov)

        var mdatHdr = BinaryBuffer(capacity: 16)
        if mdatHeaderLen == 16 {
            mdatHdr.u32(1)
            mdatHdr.fourCC("mdat")
            mdatHdr.u64(mdatPayloadBytes + 16)
        } else {
            mdatHdr.u32(UInt32(mdatPayloadBytes + 8))
            mdatHdr.fourCC("mdat")
        }
        try outFH.write(contentsOf: mdatHdr.data)

        let sourceHandles = try sourceFiles.map { try FileHandle(forReadingFrom: $0) }
        defer { for h in sourceHandles { try? h.close() } }

        var writeChunk = Data()
        writeChunk.reserveCapacity(262_144)

        for t in tracks {
            for s in t.samples {
                if let inline = s.inlineData {
                    writeChunk.append(inline)
                } else if s.sourceIndex < sourceHandles.count, s.size > 0 {
                    let fh = sourceHandles[s.sourceIndex]
                    try fh.seek(toOffset: s.offset)
                    if let d = try fh.read(upToCount: Int(s.size)) {
                        writeChunk.append(d)
                    }
                }
                if writeChunk.count >= 262_144 {
                    try outFH.write(contentsOf: writeChunk)
                    writeChunk.removeAll(keepingCapacity: true)
                }
            }
        }
        if !writeChunk.isEmpty {
            try outFH.write(contentsOf: writeChunk)
        }
        try outFH.close()

        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: partURL, to: destination)
    }

    private static func buildMoov(
        tracks: [MP4Track],
        chunkBaseOffset: UInt64,
        relativeOffsets: [[UInt64]],
        metadata: MP4Metadata,
        chapterTrackID: UInt32?
    ) -> Data {
        let movieTimescale: UInt32 = 1000
        let maxDurSec = tracks.map(\.durationSeconds).max() ?? 0
        let movieDurTicks = UInt64(maxDurSec * Double(movieTimescale))

        var moov = BinaryBuffer(capacity: 65_536)
        moov.box("moov") { m in
            m.fullBox("mvhd", version: 0, flags: 0) { b in
                b.u32(0); b.u32(0)
                b.u32(movieTimescale)
                b.u32(UInt32(clamping: movieDurTicks))
                b.u32(0x00010000)
                b.u16(0x0100)
                b.zeros(10)
                for v: UInt32 in [0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000] {
                    b.u32(v)
                }
                b.zeros(24)
                b.u32(UInt32(tracks.count + 1))
            }

            for (idx, t) in tracks.enumerated() {
                writeTrak(&m, track: t, movieTimescale: movieTimescale, chunkBaseOffset: chunkBaseOffset, relativeOffsets: relativeOffsets[idx], chapterTrackID: chapterTrackID)
            }

            writeUdta(&m, metadata: metadata)
        }
        return moov.data
    }

    private static func writeTrak(
        _ m: inout BinaryBuffer,
        track: MP4Track,
        movieTimescale: UInt32,
        chunkBaseOffset: UInt64,
        relativeOffsets: [UInt64],
        chapterTrackID: UInt32?
    ) {
        let trackDurMovie = UInt32(clamping: UInt64(track.durationSeconds * Double(movieTimescale)))
        let isChapter = (track.kind == .text && chapterTrackID == track.trackID)
        let tkhdFlags: UInt32 = isChapter ? 0 : 0x000003

        m.box("trak") { tr in
            tr.fullBox("tkhd", version: 0, flags: tkhdFlags) { b in
                b.u32(0); b.u32(0)
                b.u32(track.trackID)
                b.u32(0)
                b.u32(trackDurMovie)
                b.zeros(8)
                b.u16(0)
                b.u16(track.kind == .audio ? 1 : (track.kind == .subtitle ? 2 : 0))
                b.u16(track.kind == .audio ? 0x0100 : 0)
                b.u16(0)
                for v: UInt32 in [0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000] { b.u32(v) }
                b.u32(UInt32(track.width) << 16)
                b.u32(UInt32(track.height) << 16)
            }

            if let chapID = chapterTrackID, chapID != track.trackID, (track.kind == .video || track.kind == .audio) {
                tr.box("tref") { tref in
                    tref.box("chap") { c in c.u32(chapID) }
                }
            }

            tr.box("mdia") { md in
                md.fullBox("mdhd", version: 0, flags: 0) { b in
                    b.u32(0); b.u32(0)
                    b.u32(track.timescale)
                    b.u32(UInt32(clamping: track.totalTicks))
                    let chars = Array(track.languageISO639_2.lowercased().utf8)
                    let l1 = UInt16(chars.count > 0 && chars[0] >= 0x60 ? chars[0] - 0x60 : 21)
                    let l2 = UInt16(chars.count > 1 && chars[1] >= 0x60 ? chars[1] - 0x60 : 14)
                    let l3 = UInt16(chars.count > 2 && chars[2] >= 0x60 ? chars[2] - 0x60 : 4)
                    b.u16(((l1 & 0x1f) << 10) | ((l2 & 0x1f) << 5) | (l3 & 0x1f))
                    b.u16(0)
                }

                md.fullBox("hdlr", version: 0, flags: 0) { b in
                    b.u32(0)
                    b.fourCC(track.kind.rawValue)
                    b.zeros(12)
                    b.bytes(Data("uBit Media Handler\0".utf8))
                }

                md.box("minf") { mf in
                    switch track.kind {
                    case .video:
                        mf.fullBox("vmhd", version: 0, flags: 1) { b in b.zeros(8) }
                    case .audio:
                        mf.fullBox("smhd", version: 0, flags: 0) { b in b.zeros(4) }
                    case .subtitle, .text:
                        mf.fullBox("nmhd", version: 0, flags: 0) { _ in }
                    }

                    mf.box("dinf") { di in
                        di.fullBox("dref", version: 0, flags: 0) { dr in
                            dr.u32(1)
                            dr.fullBox("url ", version: 0, flags: 1) { _ in }
                        }
                    }

                    mf.box("stbl") { sb in
                        sb.box("stsd") { b in b.bytes(track.stsdPayload) }

                        var sttsRuns: [(UInt32, UInt32)] = []
                        for s in track.samples {
                            if let last = sttsRuns.last, last.1 == s.duration {
                                sttsRuns[sttsRuns.count - 1].0 += 1
                            } else {
                                sttsRuns.append((1, s.duration))
                            }
                        }
                        sb.fullBox("stts", version: 0, flags: 0) { b in
                            b.u32(UInt32(sttsRuns.count))
                            for (cnt, dur) in sttsRuns { b.u32(cnt); b.u32(dur) }
                        }

                        if track.samples.contains(where: { $0.ctsOffset != 0 }) {
                            let hasNeg = track.samples.contains { $0.ctsOffset < 0 }
                            var cttsRuns: [(UInt32, Int32)] = []
                            for s in track.samples {
                                if let last = cttsRuns.last, last.1 == s.ctsOffset {
                                    cttsRuns[cttsRuns.count - 1].0 += 1
                                } else {
                                    cttsRuns.append((1, s.ctsOffset))
                                }
                            }
                            sb.fullBox("ctts", version: hasNeg ? 1 : 0, flags: 0) { b in
                                b.u32(UInt32(cttsRuns.count))
                                for (cnt, off) in cttsRuns { b.u32(cnt); b.i32(off) }
                            }
                        }

                        if track.kind == .video {
                            var syncIndices: [UInt32] = []
                            for (i, s) in track.samples.enumerated() where s.isSync {
                                syncIndices.append(UInt32(i + 1))
                            }
                            sb.fullBox("stss", version: 0, flags: 0) { b in
                                b.u32(UInt32(syncIndices.count))
                                for idx in syncIndices { b.u32(idx) }
                            }
                        }

                        let chunkSize = 64
                        let numChunks = (track.samples.count + chunkSize - 1) / max(1, chunkSize)
                        let rem = track.samples.count % chunkSize

                        sb.fullBox("stsc", version: 0, flags: 0) { b in
                            if numChunks <= 1 {
                                b.u32(1)
                                b.u32(1); b.u32(UInt32(track.samples.count)); b.u32(1)
                            } else if rem == 0 {
                                b.u32(1)
                                b.u32(1); b.u32(UInt32(chunkSize)); b.u32(1)
                            } else {
                                b.u32(2)
                                b.u32(1); b.u32(UInt32(chunkSize)); b.u32(1)
                                b.u32(UInt32(numChunks)); b.u32(UInt32(rem)); b.u32(1)
                            }
                        }

                        sb.fullBox("stsz", version: 0, flags: 0) { b in
                            b.u32(0)
                            b.u32(UInt32(track.samples.count))
                            for s in track.samples { b.u32(s.size) }
                        }

                        sb.fullBox("co64", version: 0, flags: 0) { b in
                            b.u32(UInt32(numChunks))
                            var idx = 0
                            while idx < relativeOffsets.count {
                                b.u64(chunkBaseOffset + relativeOffsets[idx])
                                idx += chunkSize
                            }
                        }
                    }
                }
            }
        }
    }

    private static func writeUdta(_ m: inout BinaryBuffer, metadata: MP4Metadata) {
        let hasTextMeta = metadata.title != nil || metadata.artist != nil || metadata.date != nil || metadata.webpageURL != nil || metadata.coverJPEG != nil
        guard hasTextMeta || !metadata.chapters.isEmpty else { return }

        m.box("udta") { u in
            if !metadata.chapters.isEmpty {
                u.fullBox("chpl", version: 1, flags: 0) { c in
                    c.u8(0)
                    let count = min(255, metadata.chapters.count)
                    c.u32(UInt32(count))
                    for ch in metadata.chapters.prefix(count) {
                        let start100ns = UInt64(max(0, ch.startTime) * 10_000_000)
                        c.u64(start100ns)
                        let utf = Data(ch.title.prefix(120).utf8)
                        c.u8(UInt8(clamping: utf.count))
                        c.bytes(utf)
                    }
                }
            }

            if hasTextMeta {
                u.fullBox("meta", version: 0, flags: 0) { meta in
                    meta.fullBox("hdlr", version: 0, flags: 0) { h in
                        h.u32(0)
                        h.fourCC("mdir")
                        h.fourCC("appl")
                        h.zeros(8)
                        h.u8(0)
                    }
                    meta.box("ilst") { ilst in
                        func writeUTF8Atom(_ atomFourCC: [UInt8], _ val: String?) {
                            guard let val, !val.isEmpty else { return }
                            let utf = Data(val.utf8)
                            ilst.boxRaw(atomFourCC) { item in
                                item.box("data") { d in
                                    d.u32(1)
                                    d.u32(0)
                                    d.bytes(utf)
                                }
                            }
                        }
                        writeUTF8Atom([0xA9, 0x6E, 0x61, 0x6D], metadata.title)
                        writeUTF8Atom([0xA9, 0x41, 0x52, 0x54], metadata.artist)
                        writeUTF8Atom([0xA9, 0x64, 0x61, 0x79], metadata.date)
                        writeUTF8Atom([0xA9, 0x63, 0x6D, 0x74], metadata.webpageURL)
                        writeUTF8Atom([0x64, 0x65, 0x73, 0x63], metadata.description.map { String($0.prefix(250)) })

                        if let jpg = metadata.coverJPEG, !jpg.isEmpty {
                            let isPNG = jpg.starts(with: [0x89, 0x50, 0x4E, 0x47])
                            ilst.box("covr") { item in
                                item.box("data") { d in
                                    d.u32(isPNG ? 14 : 13)
                                    d.u32(0)
                                    d.bytes(jpg)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
