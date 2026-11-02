import Foundation
import CommonCrypto

public struct HLSVals {
    public struct Segment: Sendable {
        public var index: Int
        public var url: String
        public var duration: Double
        public var byteRange: ByteRange?
        public var keyURL: String?
        public var iv: Data?
        public var mediaSequence: UInt64
    }

    public struct MediaPlaylist: Sendable {
        public var initURL: String?
        public var initByteRange: ByteRange?
        public var segments: [Segment]
        public var isLive: Bool
        public var targetDuration: Double
    }
}

public enum HLSEngine {
    public static func parseAttributes(_ raw: String) -> [String: String] {
        var out: [String: String] = [:]
        var i = raw.startIndex
        while i < raw.endIndex {
            while i < raw.endIndex && (raw[i] == "," || raw[i].isWhitespace) {
                i = raw.index(after: i)
            }
            guard i < raw.endIndex, let eq = raw[i...].firstIndex(of: "=") else { break }
            let key = String(raw[i..<eq]).trimmingCharacters(in: .whitespaces)
            var vStart = raw.index(after: eq)
            if vStart < raw.endIndex && raw[vStart] == "\"" {
                vStart = raw.index(after: vStart)
                var val = ""
                while vStart < raw.endIndex && raw[vStart] != "\"" {
                    val.append(raw[vStart])
                    vStart = raw.index(after: vStart)
                }
                if vStart < raw.endIndex && raw[vStart] == "\"" {
                    vStart = raw.index(after: vStart)
                }
                out[key] = val
                i = vStart
            } else {
                let comma = raw[vStart...].firstIndex(of: ",") ?? raw.endIndex
                out[key] = String(raw[vStart..<comma]).trimmingCharacters(in: .whitespaces)
                i = comma
            }
        }
        return out
    }

    private static func resolve(_ rel: String, base: URL) -> String {
        if rel.hasPrefix("http://") || rel.hasPrefix("https://") { return rel }
        return URL(string: rel, relativeTo: base)?.absoluteURL.absoluteString ?? rel
    }

    private static func parseByteRange(_ s: String, prevEnd: inout Int64) -> ByteRange? {
        let parts = s.split(separator: "@")
        guard let len = Int64(parts[0]) else { return nil }
        let start: Int64
        if parts.count == 2, let off = Int64(parts[1]) {
            start = off
        } else {
            start = prevEnd
        }
        prevEnd = start + len
        return ByteRange(start: start, end: start + len - 1)
    }

    public static func extractFormats(
        client: HTTPClient,
        playlistURL: String,
        headers: [String: String]? = nil,
        idPrefix: String = "hls"
    ) async throws -> [MediaFormat] {
        let (text, finalURL) = try await client.fetchText(url: playlistURL, headers: headers)
        if !text.contains("#EXT-X-STREAM-INF") {
            let isLive = !text.contains("#EXT-X-ENDLIST") && text.contains("#EXTINF:")
            return [MediaFormat(
                id: idPrefix,
                url: finalURL.absoluteString,
                ext: "mp4",
                proto: .hls,
                vcodec: "avc1",
                acodec: "mp4a",
                headers: headers,
                isLive: isLive
            )]
        }

        var audioGroups: [String: String] = [:]
        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        for line in lines where line.hasPrefix("#EXT-X-MEDIA:") {
            let attrs = parseAttributes(String(line.dropFirst("#EXT-X-MEDIA:".count)))
            if attrs["TYPE"] == "AUDIO", let gid = attrs["GROUP-ID"], let uri = attrs["URI"] {
                let resolved = resolve(uri, base: finalURL)
                if audioGroups[gid] == nil || attrs["DEFAULT"] == "YES" {
                    audioGroups[gid] = resolved
                }
            }
        }

        var formats: [MediaFormat] = []
        var idx = 0
        while idx < lines.count {
            let line = lines[idx]
            if line.hasPrefix("#EXT-X-STREAM-INF:") {
                let attrs = parseAttributes(String(line.dropFirst("#EXT-X-STREAM-INF:".count)))
                var uriLine = ""
                var nextIdx = idx + 1
                while nextIdx < lines.count {
                    if !lines[nextIdx].isEmpty && !lines[nextIdx].hasPrefix("#") {
                        uriLine = lines[nextIdx]
                        break
                    }
                    nextIdx += 1
                }
                if !uriLine.isEmpty {
                    let fullURI = resolve(uriLine, base: finalURL)
                    let bw = Int64(attrs["AVERAGE-BANDWIDTH"] ?? attrs["BANDWIDTH"] ?? "0")
                    var width: Int?
                    var height: Int?
                    if let res = attrs["RESOLUTION"] {
                        let wh = res.lowercased().split(separator: "x")
                        if wh.count == 2 {
                            width = Int(wh[0])
                            height = Int(wh[1])
                        }
                    }
                    let fps = attrs["FRAME-RATE"].flatMap(Double.init)
                    var vcodec: String? = "avc1"
                    var acodec: String? = "mp4a"
                    if let codecs = attrs["CODECS"] {
                        let cparts = codecs.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                        vcodec = cparts.first { !$0.lowercased().hasPrefix("mp4a") && !$0.lowercased().hasPrefix("opus") && !$0.lowercased().hasPrefix("ac-3") }
                        acodec = cparts.first { $0.lowercased().hasPrefix("mp4a") || $0.lowercased().hasPrefix("opus") || $0.lowercased().hasPrefix("ac-3") }
                    }
                    let audioURI = attrs["AUDIO"].flatMap { audioGroups[$0] }
                    if audioURI != nil && acodec == nil { acodec = "mp4a" }
                    let fid = height != nil ? "\(idPrefix)-\(height!)p" : "\(idPrefix)-\(formats.count)"
                    formats.append(MediaFormat(
                        id: fid,
                        url: fullURI,
                        ext: "mp4",
                        proto: .hls,
                        vcodec: vcodec,
                        acodec: acodec,
                        width: width,
                        height: height,
                        fps: fps,
                        bitrate: bw,
                        qualityLabel: attrs["VIDEO"],
                        headers: headers,
                        audioTrackURL: audioURI
                    ))
                    idx = nextIdx
                }
            }
            idx += 1
        }

        if let (_, audioURI) = audioGroups.first {
            formats.append(MediaFormat(
                id: "\(idPrefix)-audio",
                url: audioURI,
                ext: "m4a",
                proto: .hls,
                vcodec: "none",
                acodec: "mp4a",
                qualityLabel: "Audio",
                headers: headers
            ))
        }
        return formats
    }

    public static func parseMediaPlaylist(_ text: String, baseURL: URL) -> HLSVals.MediaPlaylist {
        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        var initURL: String?
        var initByteRange: ByteRange?
        var segments: [HLSVals.Segment] = []
        var curDur: Double = 0
        var curRange: ByteRange?
        var prevRangeEnd: Int64 = 0
        var curKeyURL: String?
        var curIV: Data?
        var mediaSeq: UInt64 = 0
        var targetDur: Double = 4.0

        for line in lines {
            if line.isEmpty { continue }
            if line.hasPrefix("#EXT-X-TARGETDURATION:") {
                targetDur = Double(line.dropFirst("#EXT-X-TARGETDURATION:".count)) ?? 4.0
            } else if line.hasPrefix("#EXT-X-MEDIA-SEQUENCE:") {
                mediaSeq = UInt64(line.dropFirst("#EXT-X-MEDIA-SEQUENCE:".count)) ?? 0
            } else if line.hasPrefix("#EXT-X-MAP:") {
                let attrs = parseAttributes(String(line.dropFirst("#EXT-X-MAP:".count)))
                if let u = attrs["URI"] { initURL = resolve(u, base: baseURL) }
                if let br = attrs["BYTERANGE"] {
                    var zero: Int64 = 0
                    initByteRange = parseByteRange(br, prevEnd: &zero)
                }
            } else if line.hasPrefix("#EXT-X-KEY:") {
                let attrs = parseAttributes(String(line.dropFirst("#EXT-X-KEY:".count)))
                if attrs["METHOD"] == "AES-128", let u = attrs["URI"] {
                    curKeyURL = resolve(u, base: baseURL)
                    if let ivHex = attrs["IV"] {
                        let clean = ivHex.hasPrefix("0x") || ivHex.hasPrefix("0X") ? String(ivHex.dropFirst(2)) : ivHex
                        curIV = hexDecode(clean)
                    } else {
                        curIV = nil
                    }
                } else if attrs["METHOD"] == "NONE" {
                    curKeyURL = nil
                    curIV = nil
                }
            } else if line.hasPrefix("#EXT-X-BYTERANGE:") {
                curRange = parseByteRange(String(line.dropFirst("#EXT-X-BYTERANGE:".count)), prevEnd: &prevRangeEnd)
            } else if line.hasPrefix("#EXTINF:") {
                let body = line.dropFirst("#EXTINF:".count)
                let comma = body.firstIndex(of: ",") ?? body.endIndex
                curDur = Double(body[..<comma]) ?? 0
            } else if !line.hasPrefix("#") {
                let segSeq = mediaSeq + UInt64(segments.count)
                segments.append(HLSVals.Segment(
                    index: segments.count,
                    url: resolve(line, base: baseURL),
                    duration: curDur,
                    byteRange: curRange,
                    keyURL: curKeyURL,
                    iv: curIV,
                    mediaSequence: segSeq
                ))
                curDur = 0
                curRange = nil
            }
        }
        let isLive = !text.contains("#EXT-X-ENDLIST")
        return HLSVals.MediaPlaylist(
            initURL: initURL,
            initByteRange: initByteRange,
            segments: segments,
            isLive: isLive,
            targetDuration: targetDur
        )
    }

    private static func hexDecode(_ hex: String) -> Data? {
        var padded = hex
        if padded.count % 2 != 0 { padded = "0" + padded }
        var data = Data(capacity: padded.count / 2)
        var idx = padded.startIndex
        while idx < padded.endIndex {
            let next = padded.index(idx, offsetBy: 2)
            guard let b = UInt8(padded[idx..<next], radix: 16) else { return nil }
            data.append(b)
            idx = next
        }
        return data
    }

    public static func decryptAES128(data: Data, key: Data, iv: Data?, sequence: UInt64) throws -> Data {
        guard key.count == kCCKeySizeAES128 else { return data }
        var ivBytes: [UInt8]
        if let iv, iv.count == 16 {
            ivBytes = [UInt8](iv)
        } else {
            ivBytes = [UInt8](repeating: 0, count: 16)
            var seqBE = sequence.bigEndian
            withUnsafeBytes(of: &seqBE) { ptr in
                for i in 0..<8 { ivBytes[8 + i] = ptr[i] }
            }
        }
        let outCap = data.count + kCCBlockSizeAES128
        var out = Data(count: outCap)
        var outLen: size_t = 0
        let status = out.withUnsafeMutableBytes { outPtr in
            data.withUnsafeBytes { inPtr in
                key.withUnsafeBytes { keyPtr in
                    CCCrypt(
                        CCOperation(kCCDecrypt),
                        CCAlgorithm(kCCAlgorithmAES),
                        CCOptions(kCCOptionPKCS7Padding),
                        keyPtr.baseAddress,
                        kCCKeySizeAES128,
                        ivBytes,
                        inPtr.baseAddress,
                        data.count,
                        outPtr.baseAddress,
                        outCap,
                        &outLen
                    )
                }
            }
        }
        guard status == kCCSuccess else {
            throw NSError(domain: "HLSEngine", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "AES-128 segment decryption failed"])
        }
        out.count = outLen
        return out
    }

    public static func downloadStream(
        client: HTTPClient,
        playlistURL: String,
        destination: URL,
        headers: [String: String]? = nil,
        section: SectionRange? = nil,
        connections: Int = 6,
        onProgress: (@Sendable (_ downloaded: Int64, _ estTotal: Int64, _ fraction: Double, _ recordedSec: Double) -> Void)? = nil
    ) async throws -> (isFMP4: Bool, duration: Double) {
        let (text, finalURL) = try await client.fetchText(url: playlistURL, headers: headers)
        let pl = parseMediaPlaylist(text, baseURL: finalURL)

        if pl.isLive && section == nil {
            throw ExtractorError.unsupported("Live streams can't be saved as memes; share a clip or finished video link instead.")
        }

        var selected = pl.segments
        if let sec = section {
            var acc: Double = 0
            var filtered: [HLSVals.Segment] = []
            for seg in pl.segments {
                let segStart = acc
                let segEnd = acc + seg.duration
                acc = segEnd
                if let s = sec.start, segEnd < s { continue }
                if let e = sec.end, segStart > e { break }
                filtered.append(seg)
            }
            if !filtered.isEmpty { selected = filtered }
        }

        let segWorkDir = destination.deletingLastPathComponent().appendingPathComponent(".\(destination.lastPathComponent).segs", isDirectory: true)
        try FileManager.default.createDirectory(at: segWorkDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: segWorkDir) }

        var initData: Data?
        if let initU = pl.initURL {
            if let br = pl.initByteRange {
                initData = try await client.fetchRangeBytes(url: initU, range: br, headers: headers)
            } else {
                initData = try await client.fetchData(url: initU, headers: headers).0
            }
        }

        actor KeyStore {
            var cache: [String: Data] = [:]
            func get(_ url: String, client: HTTPClient, headers: [String: String]?) async throws -> Data {
                if let d = cache[url] { return d }
                let (d, _) = try await client.fetchData(url: url, headers: headers)
                cache[url] = d
                return d
            }
        }

        actor ProgressTracker {
            var completedCount = 0
            var totalBytes: Int64 = 0
            let totalSegs: Int
            let maxBytes: Int64
            init(totalSegs: Int, maxBytes: Int64) {
                self.totalSegs = max(1, totalSegs)
                self.maxBytes = maxBytes
            }
            func add(bytes: Int64) throws -> (Int, Int64, Double) {
                completedCount += 1
                totalBytes += bytes
                if totalBytes > maxBytes { throw HTTPError.sizeExceeded(maxBytes) }
                let frac = Double(completedCount) / Double(totalSegs)
                let est = frac > 0.01 ? Int64(Double(totalBytes) / frac) : 0
                return (completedCount, est, frac)
            }
            func currentBytes() -> Int64 { totalBytes }
        }

        let keyStore = KeyStore()
        let tracker = ProgressTracker(totalSegs: selected.count, maxBytes: client.maxDownloadBytes)
        let concurrency = max(1, min(8, connections))

        actor IndexCursor {
            var next = 0
            let count: Int
            init(_ count: Int) { self.count = count }
            func take() -> Int? {
                guard next < count else { return nil }
                defer { next += 1 }
                return next
            }
        }
        let cursor = IndexCursor(selected.count)

        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<concurrency {
                group.addTask {
                    while let pos = await cursor.take() {
                        try Task.checkCancellation()
                        let seg = selected[pos]
                        let segFile = segWorkDir.appendingPathComponent(String(format: "%06d.seg", pos))
                        var raw: Data
                        if let br = seg.byteRange {
                            raw = try await client.fetchRangeBytes(url: seg.url, range: br, headers: headers)
                        } else {
                            raw = try await client.fetchData(url: seg.url, headers: headers).0
                        }
                        if let kurl = seg.keyURL {
                            let key = try await keyStore.get(kurl, client: client, headers: headers)
                            raw = try decryptAES128(data: raw, key: key, iv: seg.iv, sequence: seg.mediaSequence)
                        }
                        try raw.write(to: segFile, options: .atomic)
                        let (_, est, frac) = try await tracker.add(bytes: Int64(raw.count))
                        let cur = await tracker.currentBytes()
                        onProgress?(cur, est, frac, 0)
                    }
                }
            }
            try await group.waitForAll()
        }

        let partOut = destination.appendingPathExtension("part")
        FileManager.default.createFile(atPath: partOut.path, contents: nil)
        let fh = try FileHandle(forWritingTo: partOut)
        var firstBytes = Data()
        if let initData {
            try fh.write(contentsOf: initData)
            firstBytes = initData.prefix(16)
        }
        var totalDur: Double = 0
        for pos in 0..<selected.count {
            totalDur += selected[pos].duration
            let segFile = segWorkDir.appendingPathComponent(String(format: "%06d.seg", pos))
            let d = try Data(contentsOf: segFile)
            if firstBytes.isEmpty { firstBytes = d.prefix(16) }
            try fh.write(contentsOf: d)
        }
        try fh.close()
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: partOut, to: destination)

        let isFMP4 = initData != nil || (firstBytes.count >= 8 && String(decoding: firstBytes[4..<8], as: UTF8.self) == "ftyp") || (firstBytes.first != 0x47)
        return (isFMP4, totalDur)
    }
}
