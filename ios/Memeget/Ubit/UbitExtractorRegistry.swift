import Foundation

public protocol SiteExtractor: Sendable {
    var name: String { get }
    func matches(url: URL) -> Bool
    func extract(url: URL, client: HTTPClient) async throws -> MediaInfo
}

public enum ExtractorError: LocalizedError {
    case unsupported(String)
    case notFound(String)
    case drmProtected
    case loginRequired(String)

    public var errorDescription: String? {
        switch self {
        case .unsupported(let m): return m
        case .notFound(let m): return m
        case .drmProtected: return "This media is protected by DRM and can't be downloaded."
        case .loginRequired(let site): return "\(site) asked to sign in before opening this media."
        }
    }
}

public enum ExtractorUtils {
    public static func hostMatches(_ url: URL, domains: [String]) -> Bool {
        guard let h = url.host?.lowercased() else { return false }
        return domains.contains { h == $0 || h.hasSuffix("." + $0) }
    }

    public static func firstMatch(_ pattern: String, in text: String, group: Int = 1, options: NSRegularExpression.Options = []) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern, options: options),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              m.numberOfRanges > group,
              m.range(at: group).location != NSNotFound,
              let r = Range(m.range(at: group), in: text) else {
            return nil
        }
        return String(text[r])
    }

    public static func allMatches(_ pattern: String, in text: String, group: Int = 1, options: NSRegularExpression.Options = []) -> [String] {
        guard let re = try? NSRegularExpression(pattern: pattern, options: options) else { return [] }
        return re.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { m in
            guard m.numberOfRanges > group, m.range(at: group).location != NSNotFound, let r = Range(m.range(at: group), in: text) else { return nil }
            return String(text[r])
        }
    }

    public static func extractJSONAfter(_ marker: String, in text: String) -> Any? {
        guard let range = text.range(of: marker) else { return nil }
        var idx = range.upperBound
        while idx < text.endIndex && (text[idx].isWhitespace || text[idx] == "=") {
            idx = text.index(after: idx)
        }
        guard idx < text.endIndex, (text[idx] == "{" || text[idx] == "[") else { return nil }
        let openCh = text[idx]
        let closeCh: Character = (openCh == "{") ? "}" : "]"

        var depth = 0
        var inStr = false
        var quoteCh: Character = "\""
        var escaped = false
        let start = idx

        while idx < text.endIndex {
            let c = text[idx]
            if inStr {
                if escaped { escaped = false }
                else if c == "\\" { escaped = true }
                else if c == quoteCh { inStr = false }
            } else {
                if c == "\"" || c == "'" {
                    inStr = true
                    quoteCh = c
                } else if c == openCh {
                    depth += 1
                } else if c == closeCh {
                    depth -= 1
                    if depth == 0 {
                        let end = text.index(after: idx)
                        let sub = String(text[start..<end]).replacingOccurrences(of: ":undefined", with: ":null")
                        if let data = sub.data(using: .utf8) {
                            return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
                        }
                        return nil
                    }
                }
            }
            idx = text.index(after: idx)
        }
        return nil
    }

    public static func metaContent(_ prop: String, in html: String) -> String? {
        let escaped = NSRegularExpression.escapedPattern(for: prop)
        let patterns = [
            "<meta[^>]+(?:property|name|itemprop)=[\"']\(escaped)[\"'][^>]+content=[\"']([^\"']+)[\"']",
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name|itemprop)=[\"']\(escaped)[\"']",
        ]
        for p in patterns {
            if let val = firstMatch(p, in: html, options: [.caseInsensitive]) {
                return SponsorAndSubtitles.decodeHTMLEntities(val)
            }
        }
        return nil
    }

    public static func pageTitle(in html: String) -> String? {
        if let og = metaContent("og:title", in: html) ?? metaContent("twitter:title", in: html), !og.isEmpty {
            return og
        }
        if let t = firstMatch("<title[^>]*>([\\s\\S]*?)</title>", in: html, options: [.caseInsensitive]) {
            return SponsorAndSubtitles.decodeHTMLEntities(t.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }

    public static func unescapeJSString(_ raw: String) -> String {
        var s = raw
            .replacingOccurrences(of: "\\/", with: "/")
            .replacingOccurrences(of: "\\u0026", with: "&")
            .replacingOccurrences(of: "\\u002F", with: "/")
            .replacingOccurrences(of: "\\u003d", with: "=")
            .replacingOccurrences(of: "\\u003D", with: "=")
            .replacingOccurrences(of: "\\u0025", with: "%")
            .replacingOccurrences(of: "&amp;", with: "&")
        if let data = "\"\(s.replacingOccurrences(of: "\"", with: "\\\""))\"".data(using: .utf8),
           let decoded = try? JSONDecoder().decode(String.self, from: data) {
            s = decoded
        }
        return s
    }

    public static func formatUnixDate(_ ts: TimeInterval) -> String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.timeZone = TimeZone(secondsFromGMT: 0)
        df.dateFormat = "yyyy-MM-dd"
        return df.string(from: Date(timeIntervalSince1970: ts))
    }
}

public enum ExtractorRegistry {
    public static let extractors: [SiteExtractor] = [
        ImgflipExtractor(),
        YouTubeExtractor(),
        VimeoExtractor(),
        SoundCloudExtractor(),
        BilibiliExtractor(),
        TwitchExtractor(),
        KickExtractor(),
        RedditExtractor(),
        TwitterExtractor(),
        BlueskyExtractor(),
        TikTokExtractor(),
        InstagramExtractor(),
        FacebookExtractor(),
        ThreadsExtractor(),
        BandcampExtractor(),
        MixcloudExtractor(),
        DailymotionExtractor(),
        RumbleExtractor(),
        OdyseeExtractor(),
        InternetArchiveExtractor(),
        ImgurExtractor(),
        TumblrExtractor(),
        PinterestExtractor(),
        StreamableExtractor(),
        LoomExtractor(),
        TEDExtractor(),
        VKExtractor(),
        DouyinExtractor(),
        KuaishouExtractor(),
        WeiboExtractor(),
        XiaohongshuExtractor(),
        GitHubMediaExtractor(),
        CoubExtractor(),
        NineGagExtractor(),
        MastodonExtractor(),
        PeerTubeExtractor(),
    ]

    public static func extract(url rawInput: String, client: HTTPClient) async throws -> MediaInfo {
        let trimmed = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parsedURL = URL(string: trimmed), let scheme = parsedURL.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
            throw ExtractorError.unsupported("Invalid web link: \(rawInput)")
        }

        let pathExt = parsedURL.pathExtension.lowercased()
        if pathExt == "m3u8" {
            let fmts = try await HLSEngine.extractFormats(client: client, playlistURL: parsedURL.absoluteString)
            return MediaInfo(
                id: parsedURL.deletingPathExtension().lastPathComponent,
                title: parsedURL.deletingPathExtension().lastPathComponent.isEmpty ? "HLS Stream" : parsedURL.deletingPathExtension().lastPathComponent,
                site: parsedURL.host ?? "HLS",
                webpageURL: parsedURL.absoluteString,
                isLive: fmts.contains(where: \.isLive),
                formats: fmts
            )
        }
        if pathExt == "mpd" {
            let (fmts, _, isDRM) = try await DASHEngine.extractFormats(client: client, mpdURL: parsedURL.absoluteString)
            return MediaInfo(
                id: parsedURL.deletingPathExtension().lastPathComponent,
                title: parsedURL.deletingPathExtension().lastPathComponent.isEmpty ? "DASH Stream" : parsedURL.deletingPathExtension().lastPathComponent,
                site: parsedURL.host ?? "DASH",
                webpageURL: parsedURL.absoluteString,
                isLive: fmts.contains(where: \.isLive),
                isDRM: isDRM,
                formats: fmts
            )
        }
        if ["mp4", "m4v", "mov", "webm", "m4a", "mp3"].contains(pathExt) {
            let isAudio = ["m4a", "mp3"].contains(pathExt)
            let name = parsedURL.deletingPathExtension().lastPathComponent
            return MediaInfo(
                id: name.isEmpty ? "direct" : name,
                title: (name.removingPercentEncoding ?? name).isEmpty ? "Direct Media" : (name.removingPercentEncoding ?? name),
                site: parsedURL.host ?? "Web",
                webpageURL: parsedURL.absoluteString,
                formats: [MediaFormat(
                    id: "direct-\(pathExt)",
                    url: parsedURL.absoluteString,
                    ext: (pathExt == "m4v" || pathExt == "mov") ? "mp4" : pathExt,
                    proto: .http,
                    vcodec: isAudio ? "none" : (pathExt == "webm" ? "vp9" : "avc1"),
                    acodec: pathExt == "webm" ? "opus" : "mp4a"
                )]
            )
        }
        if ["jpg", "jpeg", "png", "webp", "gif", "heic"].contains(pathExt) {
            let name = parsedURL.deletingPathExtension().lastPathComponent
            return MediaInfo(
                id: name.isEmpty ? "image" : name,
                title: name.isEmpty ? "Meme" : name,
                site: parsedURL.host ?? "Web",
                webpageURL: parsedURL.absoluteString,
                imageFallbackURL: parsedURL.absoluteString
            )
        }

        for ext in extractors where ext.matches(url: parsedURL) {
            do {
                let info = try await ext.extract(url: parsedURL, client: client)
                if !info.formats.isEmpty || info.imageFallbackURL != nil || info.isDRM {
                    return info
                }
            } catch let e as ExtractorError {
                throw e
            } catch {
                if let fallback = try? await GenericExtractor.extract(url: parsedURL, client: client),
                   !fallback.formats.isEmpty || fallback.imageFallbackURL != nil {
                    return fallback
                }
                throw error
            }
        }

        return try await GenericExtractor.extract(url: parsedURL, client: client)
    }
}

public enum GenericExtractor {
    public static func extract(url: URL, client: HTTPClient) async throws -> MediaInfo {
        let (data, resp) = try await client.fetchData(url: url.absoluteString)
        let finalURL = resp.url ?? url
        let ctype = (resp.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()

        if ctype.hasPrefix("video/") || ctype.hasPrefix("audio/") {
            let isAudio = ctype.hasPrefix("audio/")
            let ext = isAudio ? "m4a" : (ctype.contains("webm") ? "webm" : "mp4")
            return MediaInfo(
                id: finalURL.lastPathComponent.isEmpty ? "media" : finalURL.lastPathComponent,
                title: finalURL.lastPathComponent.isEmpty ? (finalURL.host ?? "Media") : finalURL.lastPathComponent,
                site: finalURL.host ?? "Web",
                webpageURL: finalURL.absoluteString,
                formats: [MediaFormat(id: "direct", url: finalURL.absoluteString, ext: ext, proto: .http, vcodec: isAudio ? "none" : "avc1", acodec: "mp4a", filesize: Int64(data.count))]
            )
        }
        if ctype.hasPrefix("image/") {
            return MediaInfo(
                id: finalURL.lastPathComponent.isEmpty ? "image" : finalURL.lastPathComponent,
                title: finalURL.lastPathComponent.isEmpty ? "Meme" : finalURL.lastPathComponent,
                site: finalURL.host ?? "Web",
                webpageURL: finalURL.absoluteString,
                imageFallbackURL: finalURL.absoluteString
            )
        }

        let html = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        let title = ExtractorUtils.pageTitle(in: html) ?? finalURL.lastPathComponent
        let thumb = ExtractorUtils.metaContent("og:image:secure_url", in: html)
            ?? ExtractorUtils.metaContent("og:image", in: html)
            ?? ExtractorUtils.metaContent("twitter:image", in: html)
        let author = ExtractorUtils.metaContent("author", in: html) ?? ExtractorUtils.metaContent("og:site_name", in: html)
        let headers = ["Referer": finalURL.absoluteString]

        if let ytEmbed = ExtractorUtils.firstMatch("(?:youtube\\.com/embed/|youtube-nocookie\\.com/embed/)([A-Za-z0-9_-]{11})", in: html) {
            return try await YouTubeExtractor().extract(url: URL(string: "https://www.youtube.com/watch?v=\(ytEmbed)")!, client: client)
        }

        var formats: [MediaFormat] = []
        var seenURLs = Set<String>()

        func addCandidate(_ raw: String) async {
            let clean = ExtractorUtils.unescapeJSString(raw).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !clean.isEmpty, !clean.hasPrefix("blob:") else { return }
            guard let resolved = URL(string: clean, relativeTo: finalURL)?.absoluteURL.absoluteString else { return }
            guard seenURLs.insert(resolved).inserted else { return }
            let low = resolved.lowercased()
            if low.contains(".m3u8") {
                if let hls = try? await HLSEngine.extractFormats(client: client, playlistURL: resolved, headers: headers, idPrefix: "hls-\(formats.count)") {
                    formats.append(contentsOf: hls)
                }
            } else if low.contains(".mpd") {
                if let (dash, _, _) = try? await DASHEngine.extractFormats(client: client, mpdURL: resolved, headers: headers, idPrefix: "dash-\(formats.count)") {
                    formats.append(contentsOf: dash)
                }
            } else {
                let isAudio = low.contains(".mp3") || low.contains(".m4a")
                let ext = low.contains(".webm") ? "webm" : (low.contains(".mp3") ? "mp3" : (isAudio ? "m4a" : "mp4"))
                formats.append(MediaFormat(
                    id: "web-\(formats.count)",
                    url: resolved,
                    ext: ext,
                    proto: .http,
                    vcodec: isAudio ? "none" : "avc1",
                    acodec: "mp4a",
                    headers: headers
                ))
            }
        }

        for metaName in ["og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream"] {
            if let v = ExtractorUtils.metaContent(metaName, in: html) {
                let vType = (ExtractorUtils.metaContent("og:video:type", in: html) ?? "").lowercased()
                if v.contains(".mp4") || v.contains(".m3u8") || v.contains(".webm") || vType.hasPrefix("video/") || vType.contains("mpegurl") {
                    await addCandidate(v)
                }
            }
        }

        for ldJSON in ExtractorUtils.allMatches("<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>", in: html, options: [.caseInsensitive]) {
            if let d = ldJSON.data(using: .utf8), let obj = try? JSONSerialization.jsonObject(with: d) {
                for u in collectJSONLDMediaURLs(obj) {
                    await addCandidate(u)
                }
            }
        }

        for src in ExtractorUtils.allMatches("<(?:video|source)[^>]+src=[\"']([^\"']+)[\"']", in: html, options: [.caseInsensitive]) {
            await addCandidate(src)
        }

        for m3u8 in ExtractorUtils.allMatches("(https?:\\\\?/\\\\?/[^\"'\\s<>]+\\.m3u8(?:\\?[^\"'\\s<>]*)?)", in: html) {
            await addCandidate(m3u8)
        }
        if formats.isEmpty {
            for mp4 in ExtractorUtils.allMatches("(https?:\\\\?/\\\\?/[^\"'\\s<>]+\\.(?:mp4|m4v|webm)(?:\\?[^\"'\\s<>]*)?)", in: html) {
                await addCandidate(mp4)
            }
        }

        let resolvedImgFallback = thumb.flatMap { URL(string: $0, relativeTo: finalURL)?.absoluteURL.absoluteString }
        guard !formats.isEmpty || resolvedImgFallback != nil else {
            throw ExtractorError.notFound("No downloadable video or image found at \(finalURL.host ?? finalURL.absoluteString).")
        }

        return MediaInfo(
            id: String(abs(finalURL.absoluteString.hashValue)),
            title: title,
            site: finalURL.host?.replacingOccurrences(of: "www.", with: "") ?? "Web",
            webpageURL: finalURL.absoluteString,
            author: author,
            thumbnail: resolvedImgFallback,
            isLive: formats.contains(where: \.isLive),
            formats: formats,
            imageFallbackURL: formats.isEmpty ? resolvedImgFallback : nil
        )
    }

    private static func collectJSONLDMediaURLs(_ node: Any) -> [String] {
        var out: [String] = []
        if let dict = node as? [String: Any] {
            for k in ["contentUrl", "streamUrl"] {
                if let u = dict[k] as? String { out.append(u) }
            }
            for (_, v) in dict { out.append(contentsOf: collectJSONLDMediaURLs(v)) }
        } else if let arr = node as? [Any] {
            for item in arr { out.append(contentsOf: collectJSONLDMediaURLs(item)) }
        }
        return out
    }
}
