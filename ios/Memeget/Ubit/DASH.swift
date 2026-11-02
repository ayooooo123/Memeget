import Foundation

public struct DASHRepresentation: Sendable {
    public var id: String
    public var mimeType: String
    public var codecs: String?
    public var width: Int?
    public var height: Int?
    public var fps: Double?
    public var bandwidth: Int64
    public var baseURL: String?
    public var initURL: String?
    public var segmentURLs: [String]
    public var isLive: Bool
}

private final class MPDXMLParser: NSObject, XMLParserDelegate {
    let manifestBase: URL
    var isDynamic = false
    var mediaDuration: Double?
    var representations: [DASHRepresentation] = []

    private var adaptMime = ""
    private var adaptCodecs: String?
    private var adaptBaseURL: URL?
    private var adaptTemplate: [String: String]?
    private var adaptTimeline: [(t: Int64?, d: Int64, r: Int)] = []

    private var inRep = false
    private var repAttrs: [String: String] = [:]
    private var repBaseURLText = ""
    private var inBaseURL = false
    private var mpdbaseURL: URL?
    private var periodBaseURL: URL?
    private var repTemplate: [String: String]?
    private var repTimeline: [(t: Int64?, d: Int64, r: Int)] = []
    private var repInitSourceURL: String?
    private var stack: [String] = []

    init(baseURL: URL) {
        self.manifestBase = baseURL
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        let name = elementName.components(separatedBy: ":").last ?? elementName
        stack.append(name)

        switch name {
        case "MPD":
            isDynamic = (attributeDict["type"] == "dynamic")
            if let dur = attributeDict["mediaPresentationDuration"] {
                mediaDuration = Self.parseISODuration(dur)
            }
        case "AdaptationSet":
            adaptMime = attributeDict["mimeType"] ?? attributeDict["contentType"] ?? ""
            adaptCodecs = attributeDict["codecs"]
            adaptBaseURL = nil
            adaptTemplate = nil
            adaptTimeline = []
        case "Representation":
            inRep = true
            repAttrs = attributeDict
            repBaseURLText = ""
            repTemplate = nil
            repTimeline = []
            repInitSourceURL = nil
        case "BaseURL":
            inBaseURL = true
            repBaseURLText = ""
        case "SegmentTemplate":
            if inRep { repTemplate = attributeDict }
            else { adaptTemplate = attributeDict }
        case "Initialization":
            if let src = attributeDict["sourceURL"] {
                repInitSourceURL = src
            }
        case "S":
            let t = attributeDict["t"].flatMap(Int64.init)
            let d = attributeDict["d"].flatMap(Int64.init) ?? 0
            let r = attributeDict["r"].flatMap(Int.init) ?? 0
            if inRep { repTimeline.append((t, d, r)) }
            else { adaptTimeline.append((t, d, r)) }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inBaseURL { repBaseURLText += string }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let name = elementName.components(separatedBy: ":").last ?? elementName
        _ = stack.popLast()

        if name == "BaseURL" {
            inBaseURL = false
            let trimmed = repBaseURLText.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                let parentScope = stack.last ?? ""
                if parentScope == "MPD" {
                    mpdbaseURL = URL(string: trimmed, relativeTo: manifestBase)?.absoluteURL
                } else if parentScope == "Period" {
                    periodBaseURL = URL(string: trimmed, relativeTo: mpdbaseURL ?? manifestBase)?.absoluteURL
                } else if parentScope == "AdaptationSet" {
                    adaptBaseURL = URL(string: trimmed, relativeTo: periodBaseURL ?? mpdbaseURL ?? manifestBase)?.absoluteURL
                }
            }
        } else if name == "Representation" {
            inRep = false
            finalizeRepresentation()
        }
    }

    private func finalizeRepresentation() {
        let repID = repAttrs["id"] ?? "rep-\(representations.count)"
        let mime = repAttrs["mimeType"] ?? adaptMime
        let codecs = repAttrs["codecs"] ?? adaptCodecs
        let width = repAttrs["width"].flatMap(Int.init)
        let height = repAttrs["height"].flatMap(Int.init)
        let bw = repAttrs["bandwidth"].flatMap(Int64.init) ?? 0
        var fps: Double?
        if let fr = repAttrs["frameRate"] {
            let parts = fr.split(separator: "/")
            if parts.count == 2, let n = Double(parts[0]), let d = Double(parts[1]), d > 0 { fps = n / d }
            else { fps = Double(fr) }
        }

        let effectiveBase = adaptBaseURL ?? periodBaseURL ?? mpdbaseURL ?? manifestBase
        let repDirectBase = repBaseURLText.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedRepBase = repDirectBase.isEmpty ? effectiveBase : (URL(string: repDirectBase, relativeTo: effectiveBase)?.absoluteURL ?? effectiveBase)

        var initU: String?
        var segs: [String] = []

        if let tpl = repTemplate ?? adaptTemplate {
            let timeline = !repTimeline.isEmpty ? repTimeline : adaptTimeline
            let initTpl = tpl["initialization"] ?? repInitSourceURL
            if let initTpl {
                let filled = Self.fillTemplate(initTpl, repID: repID, bandwidth: bw, number: nil, time: nil)
                initU = URL(string: filled, relativeTo: resolvedRepBase)?.absoluteURL.absoluteString ?? filled
            }
            if let mediaTpl = tpl["media"] {
                let startNum = tpl["startNumber"].flatMap(Int.init) ?? 1
                let timescale = tpl["timescale"].flatMap(Double.init) ?? 1.0
                if !timeline.isEmpty {
                    var curTime: Int64 = 0
                    var num = startNum
                    for entry in timeline {
                        if let t = entry.t { curTime = t }
                        let count = max(0, entry.r + 1)
                        for _ in 0..<count {
                            let filled = Self.fillTemplate(mediaTpl, repID: repID, bandwidth: bw, number: num, time: curTime)
                            let abs = URL(string: filled, relativeTo: resolvedRepBase)?.absoluteURL.absoluteString ?? filled
                            segs.append(abs)
                            curTime += entry.d
                            num += 1
                        }
                    }
                } else if let durAttr = tpl["duration"].flatMap(Double.init), durAttr > 0, let totalDur = mediaDuration {
                    let segDurSec = durAttr / timescale
                    let count = max(1, Int(ceil(totalDur / segDurSec)))
                    for i in 0..<count {
                        let filled = Self.fillTemplate(mediaTpl, repID: repID, bandwidth: bw, number: startNum + i, time: Int64(Double(i) * durAttr))
                        let abs = URL(string: filled, relativeTo: resolvedRepBase)?.absoluteURL.absoluteString ?? filled
                        segs.append(abs)
                    }
                }
            }
        } else if !repDirectBase.isEmpty {
            if let src = repInitSourceURL {
                initU = URL(string: src, relativeTo: resolvedRepBase)?.absoluteURL.absoluteString
            }
        }

        representations.append(DASHRepresentation(
            id: repID,
            mimeType: mime,
            codecs: codecs,
            width: width,
            height: height,
            fps: fps,
            bandwidth: bw,
            baseURL: segs.isEmpty ? resolvedRepBase.absoluteString : nil,
            initURL: initU,
            segmentURLs: segs,
            isLive: isDynamic
        ))
    }

    static func fillTemplate(_ tpl: String, repID: String, bandwidth: Int64, number: Int?, time: Int64?) -> String {
        var s = tpl
            .replacingOccurrences(of: "$$", with: "\u{0001}")
            .replacingOccurrences(of: "$RepresentationID$", with: repID)
            .replacingOccurrences(of: "$Bandwidth$", with: String(bandwidth))
        if let number {
            s = replacePaddedToken(s, token: "Number", value: Int64(number))
        }
        if let time {
            s = replacePaddedToken(s, token: "Time", value: time)
        }
        return s.replacingOccurrences(of: "\u{0001}", with: "$")
    }

    private static func replacePaddedToken(_ input: String, token: String, value: Int64) -> String {
        let pattern = "\\$\(token)(?:%0(\\d+)d)?\\$"
        guard let re = try? NSRegularExpression(pattern: pattern) else { return input }
        var result = input
        let matches = re.matches(in: input, range: NSRange(input.startIndex..., in: input)).reversed()
        for m in matches {
            let fullRange = Range(m.range, in: result)!
            if m.numberOfRanges > 1, let padR = Range(m.range(at: 1), in: result), let width = Int(result[padR]) {
                result.replaceSubrange(fullRange, with: String(format: "%0*lld", width, value))
            } else {
                result.replaceSubrange(fullRange, with: String(value))
            }
        }
        return result
    }

    static func parseISODuration(_ raw: String) -> Double? {
        guard let re = try? NSRegularExpression(pattern: "P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:([0-9.]+)S)?") else { return nil }
        guard let m = re.firstMatch(in: raw, range: NSRange(raw.startIndex..., in: raw)) else { return nil }
        func val(_ idx: Int) -> Double {
            guard m.range(at: idx).location != NSNotFound, let r = Range(m.range(at: idx), in: raw) else { return 0 }
            return Double(raw[r]) ?? 0
        }
        let total = val(1) * 86400 + val(2) * 3600 + val(3) * 60 + val(4)
        return total > 0 ? total : nil
    }
}

public enum DASHEngine {
    public static func parseMPD(data: Data, baseURL: URL) -> [DASHRepresentation] {
        let delegate = MPDXMLParser(baseURL: baseURL)
        let xml = XMLParser(data: data)
        xml.delegate = delegate
        _ = xml.parse()
        return delegate.representations
    }

    public static func extractFormats(
        client: HTTPClient,
        mpdURL: String,
        headers: [String: String]? = nil,
        idPrefix: String = "dash"
    ) async throws -> (formats: [MediaFormat], representations: [String: DASHRepresentation], isDRM: Bool) {
        let (data, resp) = try await client.fetchData(url: mpdURL, headers: headers)
        let rawText = String(decoding: data, as: UTF8.self)
        let hasDRM = rawText.contains("cenc:pssh") || rawText.contains("ContentProtection")
        let reps = parseMPD(data: data, baseURL: resp.url ?? URL(string: mpdURL)!)

        var formats: [MediaFormat] = []
        var repMap: [String: DASHRepresentation] = [:]
        for rep in reps {
            let isAudio = rep.mimeType.lowercased().contains("audio") || ((rep.height ?? 0) == 0 && (rep.codecs?.lowercased().contains("mp4a") == true || rep.codecs?.lowercased().contains("opus") == true))
            let ext = rep.mimeType.lowercased().contains("webm") ? "webm" : (isAudio ? "m4a" : "mp4")
            let fid = "\(idPrefix)-\(rep.id)"
            repMap[fid] = rep
            formats.append(MediaFormat(
                id: fid,
                url: rep.baseURL ?? mpdURL,
                ext: ext,
                proto: rep.segmentURLs.isEmpty ? .http : .dash,
                vcodec: isAudio ? "none" : (rep.codecs ?? "avc1"),
                acodec: isAudio ? (rep.codecs ?? "mp4a") : "none",
                width: rep.width,
                height: rep.height,
                fps: rep.fps,
                bitrate: rep.bandwidth,
                headers: headers,
                isLive: rep.isLive
            ))
        }
        return (formats, repMap, hasDRM && formats.isEmpty)
    }

    public static func downloadRepresentation(
        client: HTTPClient,
        mpdURL: String,
        formatID: String,
        destination: URL,
        headers: [String: String]? = nil,
        connections: Int = 6,
        onProgress: (@Sendable (Int64, Int64, Double) -> Void)? = nil
    ) async throws {
        let (_, repMap, _) = try await extractFormats(client: client, mpdURL: mpdURL, headers: headers)
        let rep = repMap[formatID] ?? repMap.values.first { "dash-\($0.id)" == formatID || $0.id == formatID }
        guard let rep else {
            throw HTTPError.badURL(mpdURL)
        }
        if rep.isLive {
            throw ExtractorError.unsupported("Live streams can't be saved as memes; share a clip or finished video link instead.")
        }
        if rep.segmentURLs.isEmpty, let direct = rep.baseURL {
            try await client.downloadFile(url: direct, destination: destination, headers: headers, connections: connections) { done, total in
                onProgress?(done, total, total > 0 ? Double(done) / Double(total) : 0)
            }
            return
        }

        let workDir = destination.deletingLastPathComponent().appendingPathComponent(".\(destination.lastPathComponent).dsegs", isDirectory: true)
        try FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: workDir) }

        var initData: Data?
        if let initU = rep.initURL {
            initData = try await client.fetchData(url: initU, headers: headers).0
        }

        actor SegProgress {
            var done = 0
            var bytes: Int64 = 0
            let total: Int
            let maxBytes: Int64
            init(_ total: Int, maxBytes: Int64) { self.total = max(1, total); self.maxBytes = maxBytes }
            func record(_ n: Int64) throws -> (Int64, Int64, Double) {
                done += 1
                bytes += n
                if bytes > maxBytes { throw HTTPError.sizeExceeded(maxBytes) }
                let frac = Double(done) / Double(total)
                let est = frac > 0.01 ? Int64(Double(bytes) / frac) : 0
                return (bytes, est, frac)
            }
        }
        actor Cursor {
            var i = 0
            let n: Int
            init(_ n: Int) { self.n = n }
            func next() -> Int? { guard i < n else { return nil }; defer { i += 1 }; return i }
        }

        let segs = rep.segmentURLs
        let prog = SegProgress(segs.count, maxBytes: client.maxDownloadBytes)
        let cur = Cursor(segs.count)
        let workers = max(1, min(8, connections))

        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<workers {
                group.addTask {
                    while let idx = await cur.next() {
                        try Task.checkCancellation()
                        let f = workDir.appendingPathComponent(String(format: "%06d.m4s", idx))
                        let (d, _) = try await client.fetchData(url: segs[idx], headers: headers)
                        try d.write(to: f, options: .atomic)
                        let (b, est, frac) = try await prog.record(Int64(d.count))
                        onProgress?(b, est, frac)
                    }
                }
            }
            try await group.waitForAll()
        }

        let partOut = destination.appendingPathExtension("part")
        FileManager.default.createFile(atPath: partOut.path, contents: nil)
        let fh = try FileHandle(forWritingTo: partOut)
        if let initData { try fh.write(contentsOf: initData) }
        for idx in 0..<segs.count {
            let f = workDir.appendingPathComponent(String(format: "%06d.m4s", idx))
            try fh.write(contentsOf: try Data(contentsOf: f))
        }
        try fh.close()
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: partOut, to: destination)
    }
}
