import CryptoKit
import Foundation

// High-level multi-platform video & meme link downloader orchestrating uBit extraction,
// parallel Range/HLS/DASH downloads, and zero-FFmpeg fast-start MP4 muxing for Memeget iOS.

public struct UbitDownloadedItem: Sendable {
    public let fileURL: URL
    public let fileName: String
    public let kind: String // "video" or "image"
    public let sha256: String
    public let title: String
    public let site: String
    public let author: String?
}

public enum UbitFormatSelector {
    public static func select(
        from formats: [MediaFormat],
        maxHeight: Int = 1080,
        preferCompatible: Bool = true
    ) throws -> (video: MediaFormat?, audio: MediaFormat?) {
        guard !formats.isEmpty else {
            throw ExtractorError.notFound("No playable media streams found at this link.")
        }

        let videoCandidates = formats.filter { $0.hasVideo }
        guard !videoCandidates.isEmpty else {
            let bestAudio = pickBestAudio(from: formats, preferAAC: preferCompatible) ?? formats[0]
            return (nil, bestAudio)
        }

        let withinCeiling = videoCandidates.filter { f in
            guard maxHeight > 0, let h = f.height, h > 0 else { return true }
            return h <= maxHeight
        }
        let pool = withinCeiling.isEmpty ? videoCandidates : withinCeiling

        let ranked = pool.sorted { lhs, rhs in
            if preferCompatible && lhs.isNativeMP4 != rhs.isNativeMP4 {
                return lhs.isNativeMP4
            }
            let lh = lhs.height ?? 0
            let rh = rhs.height ?? 0
            if lh != rh { return lh > rh }
            let lfps = Int((lhs.fps ?? 30).rounded())
            let rfps = Int((rhs.fps ?? 30).rounded())
            if lfps != rfps { return lfps > rfps }
            if preferCompatible && lhs.isH264 != rhs.isH264 {
                return lhs.isH264
            }
            let lbr = lhs.bitrate ?? lhs.filesize ?? 0
            let rbr = rhs.bitrate ?? rhs.filesize ?? 0
            if lbr != rbr { return lbr > rbr }
            return lhs.hasAudio && !rhs.hasAudio
        }

        let chosenVideo = ranked[0]
        if chosenVideo.hasAudio {
            return (chosenVideo, nil)
        }
        let wantAAC = preferCompatible || chosenVideo.ext == "mp4" || chosenVideo.isH264
        let chosenAudio = pickBestAudio(from: formats, preferAAC: wantAAC)
        return (chosenVideo, chosenAudio)
    }

    public static func pickBestAudio(from formats: [MediaFormat], preferAAC: Bool) -> MediaFormat? {
        let audioOnly = formats.filter { $0.hasAudio && !$0.hasVideo }
        guard !audioOnly.isEmpty else { return nil }
        return audioOnly.sorted { lhs, rhs in
            if preferAAC && lhs.isAAC != rhs.isAAC { return lhs.isAAC }
            return (lhs.bitrate ?? lhs.filesize ?? 0) > (rhs.bitrate ?? rhs.filesize ?? 0)
        }.first
    }
}

public enum UbitLinkDownloader {

    private static let shortlinkHosts: Set<String> = [
        "t.co", "vm.tiktok.com", "vt.tiktok.com", "redd.it",
        "dai.ly", "b23.tv", "pin.it", "xhslink.com", "fb.watch", "on.soundcloud.com"
    ]

    public static func extractURL(from candidates: String?...) -> String? {
        guard let re = try? NSRegularExpression(pattern: "(https?://[^\\s\"'<>]+)", options: [.caseInsensitive]) else {
            return nil
        }
        for raw in candidates {
            guard let raw, !raw.isEmpty else { continue }
            if let m = re.firstMatch(in: raw, range: NSRange(raw.startIndex..., in: raw)),
               let r = Range(m.range(at: 1), in: raw) {
                var candidate = String(raw[r])
                while let last = candidate.last, [")", "]", ".", ",", "!", ";"].contains(String(last)) {
                    candidate.removeLast()
                }
                if let u = URL(string: candidate),
                   let scheme = u.scheme?.lowercased(),
                   (scheme == "http" || scheme == "https"),
                   u.host != nil {
                    return u.absoluteString
                }
            }
        }
        return nil
    }

    private static func unwrapShortlinkIfNeeded(
        _ rawURL: String,
        client: HTTPClient
    ) async -> String {
        guard let initial = URL(string: rawURL),
              let initialHost = initial.host?.lowercased(),
              shortlinkHosts.contains(initialHost) else {
            return rawURL
        }
        if let (_, resp) = try? await client.fetchData(url: rawURL, method: "HEAD", retries: 1),
           let finalU = resp.url, finalU.absoluteString != rawURL {
            return finalU.absoluteString
        }
        if let (_, resp) = try? await client.fetchData(url: rawURL, method: "GET", retries: 1),
           let finalU = resp.url, finalU.absoluteString != rawURL {
            return finalU.absoluteString
        }
        return rawURL
    }

    public static func download(
        rawInput: String,
        outputDirectory: URL,
        sessionConfiguration: URLSessionConfiguration? = nil,
        onStatus: (@Sendable (String) -> Void)? = nil
    ) async throws -> UbitDownloadedItem {
        guard let extracted = extractURL(from: rawInput) else {
            throw ExtractorError.unsupported("No valid http/https link found.")
        }
        try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)

        let cookies = CookieVault()
        let client = HTTPClient(cookies: cookies, sessionConfiguration: sessionConfiguration)
        let unwrapped = await unwrapShortlinkIfNeeded(extracted, client: client)
        onStatus?("Extracting stream…")
        var info = try await ExtractorRegistry.extract(url: unwrapped, client: client)

        if info.isDRM && info.formats.isEmpty {
            throw ExtractorError.drmProtected
        }

        // Still-image meme fallback (e.g. Imgflip blank template, X photo post, Bluesky image post, OG meme page)
        if info.formats.isEmpty, let imgURL = info.imageFallbackURL, !imgURL.isEmpty {
            onStatus?("Downloading image…")
            return try await downloadImageFallback(info: info, imgURL: imgURL, outputDirectory: outputDirectory, client: client)
        }

        if info.isLive || (!info.formats.isEmpty && info.formats.allSatisfy(\.isLive)) {
            throw ExtractorError.unsupported("Live streams can't be saved as memes; share a clip or finished video link instead.")
        }

        let nonLive = info.formats.filter { !$0.isLive }
        var selected = try UbitFormatSelector.select(from: nonLive.isEmpty ? info.formats : nonLive, maxHeight: 1080, preferCompatible: true)

        let workDir = FileManager.default.temporaryDirectory.appendingPathComponent("ubit_ios_\(UUID().uuidString.prefix(12))", isDirectory: true)
        try FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: workDir) }

        let vTemp = workDir.appendingPathComponent("video.\(selected.video?.ext ?? "mp4")")
        let aTemp = workDir.appendingPathComponent("audio.\(selected.audio?.ext ?? "m4a")")
        var videoIsTS = false
        var audioIsTS = false

        func fetchRole(_ fmt: MediaFormat, to dest: URL, isVideoRole: Bool) async throws -> Bool {
            var currentFmt = fmt
            var blockedIDs = Set<String>()
            for attempt in 0..<3 {
                do {
                    switch currentFmt.proto {
                    case .hls:
                        let (isFMP4, _) = try await HLSEngine.downloadStream(
                            client: client,
                            playlistURL: currentFmt.url,
                            destination: dest,
                            headers: currentFmt.headers,
                            connections: 6
                        )
                        return !isFMP4 && currentFmt.ext.lowercased() != "mp3"
                    case .dash:
                        try await DASHEngine.downloadRepresentation(
                            client: client,
                            mpdURL: currentFmt.url,
                            formatID: currentFmt.id,
                            destination: dest,
                            headers: currentFmt.headers,
                            connections: 6
                        )
                        return false
                    case .http:
                        try await client.downloadFile(
                            url: currentFmt.url,
                            destination: dest,
                            headers: currentFmt.headers,
                            knownSize: currentFmt.filesize,
                            connections: 6
                        )
                        return false
                    }
                } catch HTTPError.staleLink {
                    guard attempt < 2 else { throw HTTPError.staleLink(403) }
                    if attempt == 1 { blockedIDs.insert(currentFmt.id) }
                    try? FileManager.default.removeItem(at: dest)
                    info = try await ExtractorRegistry.extract(url: unwrapped, client: client)
                    let pool = info.formats.filter { !blockedIDs.contains($0.id) && !$0.isLive }
                    selected = try UbitFormatSelector.select(from: pool.isEmpty ? info.formats : pool, maxHeight: 1080, preferCompatible: true)
                    guard let upd = isVideoRole ? selected.video : (selected.audio ?? selected.video) else {
                        throw HTTPError.staleLink(403)
                    }
                    currentFmt = upd
                }
            }
            return false
        }

        onStatus?("Downloading \(info.site) video…")
        if let vFmt = selected.video {
            videoIsTS = try await fetchRole(vFmt, to: vTemp, isVideoRole: true)
            if selected.audio == nil, let hlsAudioURL = vFmt.audioTrackURL {
                let synthAudio = MediaFormat(id: "hls-audio", url: hlsAudioURL, ext: "m4a", proto: .hls, vcodec: "none", acodec: "mp4a", headers: vFmt.headers)
                audioIsTS = try await fetchRole(synthAudio, to: aTemp, isVideoRole: false)
            }
        }
        if let aFmt = selected.audio {
            audioIsTS = try await fetchRole(aFmt, to: aTemp, isVideoRole: false)
        }

        let hasV = FileManager.default.fileExists(atPath: vTemp.path)
        let hasA = FileManager.default.fileExists(atPath: aTemp.path)
        guard hasV || hasA else {
            throw ExtractorError.notFound("Downloaded media stream was empty.")
        }

        let isAudioOnly = !hasV && hasA
        let isWebM = (selected.video?.ext.lowercased() == "webm") || (isAudioOnly && selected.audio?.ext.lowercased() == "webm")
        let isRawMP3 = isAudioOnly && (selected.audio?.ext.lowercased() == "mp3")
        let finalExt = isRawMP3 ? "mp3" : (isWebM ? "webm" : (isAudioOnly ? "m4a" : "mp4"))

        let stem = buildFileStem(info: info, fallbackURL: unwrapped)
        let finalURL = allocateUniqueURL(in: outputDirectory, stem: stem, ext: finalExt)

        let needMux = !isWebM && !isRawMP3 && (
            (hasV && hasA) ||
            videoIsTS ||
            audioIsTS ||
            selected.video?.proto == .hls ||
            selected.video?.proto == .dash ||
            isFragmentedMP4(vTemp)
        )

        if needMux {
            onStatus?("Muxing MP4…")
            do {
                var allTracks: [MP4Track] = []
                var srcFiles: [URL] = []
                if hasV {
                    let idx = srcFiles.count
                    srcFiles.append(vTemp)
                    var vt = videoIsTS ? try TSDemuxer.demuxToTracks(tsFileURL: vTemp) : try MP4Muxer.readTracks(from: vTemp, sourceIndex: idx)
                    if hasA { vt.removeAll { $0.kind == .audio } }
                    allTracks.append(contentsOf: vt)
                }
                if hasA {
                    let idx = srcFiles.count
                    srcFiles.append(aTemp)
                    var at = audioIsTS ? try TSDemuxer.demuxToTracks(tsFileURL: aTemp) : try MP4Muxer.readTracks(from: aTemp, sourceIndex: idx)
                    at = at.filter { $0.kind == .audio }
                    allTracks.append(contentsOf: at)
                }
                guard !allTracks.isEmpty else {
                    throw ExtractorError.notFound("No valid MP4/MPEG-TS tracks parsed.")
                }
                let meta = MP4Metadata(
                    title: info.title,
                    artist: info.author,
                    date: info.uploadDate,
                    webpageURL: info.webpageURL,
                    description: info.description,
                    chapters: info.chapters
                )
                try MP4Muxer.writeMP4(tracks: allTracks, sourceFiles: srcFiles, destination: finalURL, isAudioOnlyM4A: isAudioOnly, metadata: meta)
            } catch {
                if hasV && !hasA && !videoIsTS {
                    try FileManager.default.copyItem(at: vTemp, to: finalURL)
                } else {
                    throw error
                }
            }
        } else {
            let single = hasV ? vTemp : aTemp
            try FileManager.default.copyItem(at: single, to: finalURL)
        }

        let sha = try sha256Hex(of: finalURL)
        return UbitDownloadedItem(
            fileURL: finalURL,
            fileName: finalURL.lastPathComponent,
            kind: "video",
            sha256: sha,
            title: info.title,
            site: info.site,
            author: info.author
        )
    }

    private static func downloadImageFallback(
        info: MediaInfo,
        imgURL: String,
        outputDirectory: URL,
        client: HTTPClient
    ) async throws -> UbitDownloadedItem {
        let workFile = FileManager.default.temporaryDirectory.appendingPathComponent("ubit_img_\(UUID().uuidString.prefix(12)).bin")
        defer { try? FileManager.default.removeItem(at: workFile) }

        let ctype = (try await client.downloadFile(url: imgURL, destination: workFile, headers: ["Referer": info.webpageURL], connections: 1) ?? "").lowercased()
        let urlExt = URL(string: imgURL)?.pathExtension.lowercased() ?? ""
        let ext: String
        if ctype.contains("png") || urlExt == "png" { ext = "png" }
        else if ctype.contains("webp") || urlExt == "webp" { ext = "webp" }
        else if ctype.contains("gif") || urlExt == "gif" { ext = "gif" }
        else { ext = "jpg" }

        let stem = buildFileStem(info: info, fallbackURL: info.webpageURL)
        let finalURL = allocateUniqueURL(in: outputDirectory, stem: stem, ext: ext)
        try FileManager.default.copyItem(at: workFile, to: finalURL)
        let sha = try sha256Hex(of: finalURL)

        return UbitDownloadedItem(
            fileURL: finalURL,
            fileName: finalURL.lastPathComponent,
            kind: "image",
            sha256: sha,
            title: info.title,
            site: info.site,
            author: info.author
        )
    }

    private static func isFragmentedMP4(_ url: URL) -> Bool {
        guard let fh = try? FileHandle(forReadingFrom: url),
              let head = try? fh.read(upToCount: 4096) else { return false }
        try? fh.close()
        let boxes = MP4Muxer.listBoxes(in: head, from: 0, to: head.count)
        return boxes.contains { $0.type == "moof" || $0.type == "sidx" }
    }

    private static func buildFileStem(info: MediaInfo, fallbackURL: String) -> String {
        if let pref = info.preferredFileStem, !pref.isEmpty {
            return FormatHelpers.sanitizeFilename(pref)
        }
        let siteSlug = info.site.lowercased().filter { $0.isLetter || $0.isNumber }.prefix(14)
        let titleSlug = FormatHelpers.sanitizeFilename(info.title).replacingOccurrences(of: " ", with: "_").prefix(48)
        if !siteSlug.isEmpty && !titleSlug.lowercased().hasPrefix(siteSlug) {
            return "\(siteSlug)_\(titleSlug)"
        }
        return titleSlug.isEmpty ? "shared_\(abs(fallbackURL.hashValue) % 1_000_000)" : String(titleSlug)
    }

    private static func allocateUniqueURL(in dir: URL, stem: String, ext: String) -> URL {
        let cleanStem = stem.hasSuffix(".\(ext)") ? String(stem.dropLast(ext.count + 1)) : stem
        var candidate = dir.appendingPathComponent("\(cleanStem).\(ext)")
        var n = 2
        while FileManager.default.fileExists(atPath: candidate.path) && n < 1000 {
            candidate = dir.appendingPathComponent("\(cleanStem)_\(n).\(ext)")
            n += 1
        }
        return candidate
    }

    public static func sha256Hex(of fileURL: URL) throws -> String {
        let data = try Data(contentsOf: fileURL, options: .mappedIfSafe)
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
