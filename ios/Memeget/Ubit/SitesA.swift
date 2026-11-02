import Foundation

// MARK: - Vimeo

public struct VimeoExtractor: SiteExtractor {
    public var name: String { "Vimeo" }
    public init() {}

    public func matches(url: URL) -> Bool {
        ExtractorUtils.hostMatches(url, domains: ["vimeo.com"])
    }

    public func extract(url: URL, client: HTTPClient) async throws -> MediaInfo {
        let path = url.path
        guard let videoID = ExtractorUtils.firstMatch("(?:video/|vimeo\\.com/)(?:channels/[^/]+/|groups/[^/]+/videos/)?(\\d+)", in: url.absoluteString) else {
            throw ExtractorError.notFound("This Vimeo page isn't a video.")
        }

        let hashParam = ExtractorUtils.firstMatch("/\(videoID)/([0-9a-fA-F]{6,})", in: path)
            ?? URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "h" })?.value
        var cfgURL = "https://player.vimeo.com/video/\(videoID)/config"
        if let h = hashParam { cfgURL += "?h=\(h)" }

        let headers = ["Referer": "https://vimeo.com/"]
        guard let cfg = try await client.fetchJSON(url: cfgURL, headers: headers) as? [String: Any] else {
            throw ExtractorError.notFound("Could not read Vimeo player config.")
        }

        let videoObj = (cfg["video"] as? [String: Any]) ?? [:]
        let title = (videoObj["title"] as? String) ?? "Vimeo \(videoID)"
        let owner = ((videoObj["owner"] as? [String: Any])?["name"] as? String)
        let duration = (videoObj["duration"] as? Double) ?? (videoObj["duration"] as? Int).map(Double.init)
        let thumbs = (videoObj["thumbs"] as? [String: Any])
        let thumb = (thumbs?["base"] as? String) ?? (thumbs?["1280"] as? String) ?? (thumbs?["640"] as? String)

        var formats: [MediaFormat] = []
        var sawOnlyDRM = false

        if let files = (cfg["request"] as? [String: Any])?["files"] as? [String: Any] {
            if let prog = files["progressive"] as? [[String: Any]] {
                for p in prog {
                    guard let purl = p["url"] as? String else { continue }
                    let h = p["height"] as? Int
                    let w = p["width"] as? Int
                    let fps = (p["fps"] as? Double) ?? (p["fps"] as? Int).map(Double.init)
                    let q = (p["quality"] as? String) ?? h.map { "\($0)p" }
                    formats.append(MediaFormat(
                        id: "http-\(q ?? "\(formats.count)")",
                        url: purl,
                        ext: "mp4",
                        proto: .http,
                        vcodec: "avc1",
                        acodec: "mp4a",
                        width: w,
                        height: h,
                        fps: fps,
                        qualityLabel: q,
                        headers: headers
                    ))
                }
            }
            if let hls = files["hls"] as? [String: Any], let cdns = hls["cdns"] as? [String: Any] {
                for (_, cdnVal) in cdns {
                    guard let cdn = cdnVal as? [String: Any],
                          let m3u8 = (cdn["url"] as? String) ?? (cdn["avc_url"] as? String) else { continue }
                    if m3u8.contains("/drm/") {
                        sawOnlyDRM = true
                        continue
                    }
                    if let hlsFmts = try? await HLSEngine.extractFormats(client: client, playlistURL: m3u8, headers: headers, idPrefix: "hls") {
                        formats.append(contentsOf: hlsFmts)
                        break
                    }
                }
            }
        }

        if formats.isEmpty && sawOnlyDRM {
            return MediaInfo(id: videoID, title: title, site: "Vimeo", webpageURL: url.absoluteString, author: owner, duration: duration, thumbnail: thumb, isDRM: true)
        }
        guard !formats.isEmpty else {
            throw ExtractorError.notFound("No non-DRM streams available for Vimeo \(videoID).")
        }
        return MediaInfo(id: videoID, title: title, site: "Vimeo", webpageURL: url.absoluteString, author: owner, duration: duration, thumbnail: thumb, formats: formats, preferredFileStem: "vimeo_\(videoID)")
    }
}

// MARK: - SoundCloud

public struct SoundCloudExtractor: SiteExtractor {
    public var name: String { "SoundCloud" }
    public init() {}

    public func matches(url: URL) -> Bool {
        ExtractorUtils.hostMatches(url, domains: ["soundcloud.com", "on.soundcloud.com"])
    }

    private func discoverClientID(client: HTTPClient, pageHTML: String) async throws -> String {
        if let inline = ExtractorUtils.firstMatch("client_id[\"']?\\s*[:=]\\s*[\"']([a-zA-Z0-9]{32})[\"']", in: pageHTML) {
            return inline
        }
        let scripts = ExtractorUtils.allMatches("<script[^>]+src=[\"'](https://a-v2\\.sndcdn\\.com/assets/[^\"']+\\.js)[\"']", in: pageHTML)
        for jsURL in scripts.suffix(4).reversed() {
            if let (js, _) = try? await client.fetchText(url: jsURL, includeCookies: false),
               let cid = ExtractorUtils.firstMatch("client_id\\s*:\\s*\"([a-zA-Z0-9]{32})\"", in: js) {
                return cid
            }
        }
        throw ExtractorError.notFound("Could not discover SoundCloud client_id.")
    }

    public func extract(url: URL, client: HTTPClient) async throws -> MediaInfo {
        let (html, finalURL) = try await client.fetchText(url: url.absoluteString)
        let clientID = try await discoverClientID(client: client, pageHTML: html)
        let encoded = finalURL.absoluteString.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? finalURL.absoluteString
        guard let resolved = try await client.fetchJSON(url: "https://api-v2.soundcloud.com/resolve?url=\(encoded)&client_id=\(clientID)") as? [String: Any] else {
            throw ExtractorError.notFound("SoundCloud could not resolve this link.")
        }
        let id = String((resolved["id"] as? Int) ?? abs(finalURL.absoluteString.hashValue))
        let title = (resolved["title"] as? String) ?? "SoundCloud Track"
        let author = ((resolved["user"] as? [String: Any])?["username"] as? String)
        let trackAuth = resolved["track_authorization"] as? String

        var formats: [MediaFormat] = []
        if let transcodings = ((resolved["media"] as? [String: Any])?["transcodings"] as? [[String: Any]]) {
            for tc in transcodings {
                guard let apiStreamURL = tc["url"] as? String else { continue }
                let fmtDict = tc["format"] as? [String: Any]
                let protoStr = (fmtDict?["protocol"] as? String) ?? ""
                let mime = (fmtDict?["mime_type"] as? String) ?? ""
                if protoStr.contains("encrypted") || mime.contains("opus") { continue }
                var lookup = "\(apiStreamURL)?client_id=\(clientID)"
                if let ta = trackAuth { lookup += "&track_authorization=\(ta)" }
                guard let linkObj = try? await client.fetchJSON(url: lookup) as? [String: Any],
                      let actualURL = linkObj["url"] as? String else { continue }
                let isHLS = protoStr.contains("hls") || actualURL.contains(".m3u8")
                formats.append(MediaFormat(id: "\(protoStr)-mp3", url: actualURL, ext: "mp3", proto: isHLS ? .hls : .http, vcodec: "none", acodec: "mp3", bitrate: 128_000))
            }
        }
        guard !formats.isEmpty else { throw ExtractorError.notFound("This SoundCloud track has no downloadable stream.") }
        return MediaInfo(id: id, title: title, site: "SoundCloud", webpageURL: finalURL.absoluteString, author: author, formats: formats)
    }
}

// MARK: - Bilibili

public struct BilibiliExtractor: SiteExtractor {
    public var name: String { "Bilibili" }
    public init() {}

    public func matches(url: URL) -> Bool {
        ExtractorUtils.hostMatches(url, domains: ["bilibili.com", "b23.tv", "bili2233.cn"])
    }

    public func extract(url: URL, client: HTTPClient) async throws -> MediaInfo {
        let (_, finalURL) = try await client.fetchText(url: url.absoluteString)
        let bvid = ExtractorUtils.firstMatch("(BV[0-9A-Za-z]{10})", in: finalURL.path)
        let aid = ExtractorUtils.firstMatch("/av(\\d+)", in: finalURL.path)
        guard bvid != nil || aid != nil else {
            return try await GenericExtractor.extract(url: finalURL, client: client)
        }

        let query = bvid != nil ? "bvid=\(bvid!)" : "aid=\(aid!)"
        let headers = ["Referer": "https://www.bilibili.com/"]
        guard let viewResp = try await client.fetchJSON(url: "https://api.bilibili.com/x/web-interface/view?\(query)", headers: headers) as? [String: Any],
              let vdata = viewResp["data"] as? [String: Any] else {
            throw ExtractorError.notFound("Could not fetch Bilibili video metadata.")
        }

        let realBvid = (vdata["bvid"] as? String) ?? bvid ?? "av\(aid!)"
        let title = (vdata["title"] as? String) ?? realBvid
        let author = ((vdata["owner"] as? [String: Any])?["name"] as? String)
        let cid = (vdata["cid"] as? Int) ?? 0
        let duration = (vdata["duration"] as? Double) ?? (vdata["duration"] as? Int).map(Double.init)

        let playURL = "https://api.bilibili.com/x/player/playurl?\(query)&cid=\(cid)&qn=80&fnval=4048&fourk=1"
        guard let playResp = try await client.fetchJSON(url: playURL, headers: headers) as? [String: Any],
              let pdata = playResp["data"] as? [String: Any] else {
            throw ExtractorError.notFound("Could not fetch Bilibili stream URLs.")
        }

        var formats: [MediaFormat] = []
        if let dash = pdata["dash"] as? [String: Any] {
            for v in (dash["video"] as? [[String: Any]]) ?? [] {
                guard let u = (v["baseUrl"] as? String) ?? (v["base_url"] as? String) else { continue }
                let codecs = (v["codecs"] as? String) ?? "avc1"
                formats.append(MediaFormat(
                    id: "v-\(v["id"] as? Int ?? 0)-\(codecs.prefix(4))",
                    url: u,
                    ext: "mp4",
                    proto: .http,
                    vcodec: codecs,
                    acodec: "none",
                    width: v["width"] as? Int,
                    height: v["height"] as? Int,
                    bitrate: (v["bandwidth"] as? Int64) ?? (v["bandwidth"] as? Int).map(Int64.init),
                    headers: headers
                ))
            }
            for a in (dash["audio"] as? [[String: Any]]) ?? [] {
                guard let u = (a["baseUrl"] as? String) ?? (a["base_url"] as? String) else { continue }
                formats.append(MediaFormat(
                    id: "a-\(a["id"] as? Int ?? 30280)",
                    url: u,
                    ext: "m4a",
                    proto: .http,
                    vcodec: "none",
                    acodec: (a["codecs"] as? String) ?? "mp4a.40.2",
                    bitrate: (a["bandwidth"] as? Int64) ?? (a["bandwidth"] as? Int).map(Int64.init),
                    headers: headers
                ))
            }
        }
        return MediaInfo(id: realBvid, title: title, site: "Bilibili", webpageURL: finalURL.absoluteString, author: author, duration: duration, formats: formats, preferredFileStem: "bilibili_\(realBvid)")
    }
}

// MARK: - Twitch (Clips + VODs via GQL)

public struct TwitchExtractor: SiteExtractor {
    public var name: String { "Twitch" }
    private let gqlClientID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    public init() {}

    public func matches(url: URL) -> Bool {
        ExtractorUtils.hostMatches(url, domains: ["twitch.tv"])
    }

    public func extract(url: URL, client: HTTPClient) async throws -> MediaInfo {
        let host = url.host?.lowercased() ?? ""
        let path = url.path

        if host == "clips.twitch.tv" || path.contains("/clip/") {
            let slug = path.split(separator: "/").last.map(String.init) ?? ""
            let gql: [[String: Any]] = [[
                "operationName": "VideoAccessToken_Clip",
                "variables": ["slug": slug],
                "extensions": ["persistedQuery": ["version": 1, "sha256Hash": "36b89d2507fce29e5ca551df756d27c1cfe079e2609642b4390aa4c35796eb11"]]
            ]]
            let resp = try await client.fetchJSON(
                url: "https://gql.twitch.tv/gql",
                method: "POST",
                headers: ["Client-ID": gqlClientID, "Content-Type": "application/json"],
                body: try JSONSerialization.data(withJSONObject: gql)
            )
            guard let arr = resp as? [[String: Any]],
                  let clip = (arr.first?["data"] as? [String: Any])?["clip"] as? [String: Any],
                  let pb = clip["playbackAccessToken"] as? [String: Any],
                  let sig = pb["signature"] as? String,
                  let tok = pb["value"] as? String else {
                throw ExtractorError.notFound("Twitch clip not found.")
            }
            let encTok = tok.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? tok
            var formats: [MediaFormat] = []
            for q in (clip["videoQualities"] as? [[String: Any]]) ?? [] {
                guard let src = q["sourceURL"] as? String else { continue }
                let qual = (q["quality"] as? String) ?? "720"
                formats.append(MediaFormat(
                    id: "clip-\(qual)p",
                    url: "\(src)?sig=\(sig)&token=\(encTok)",
                    ext: "mp4",
                    proto: .http,
                    vcodec: "avc1",
                    acodec: "mp4a",
                    height: Int(qual),
                    fps: q["frameRate"] as? Double
                ))
            }
            let title = (clip["title"] as? String) ?? slug
            let broadcaster = ((clip["broadcaster"] as? [String: Any])?["displayName"] as? String)
            return MediaInfo(id: slug, title: title, site: "Twitch", webpageURL: url.absoluteString, author: broadcaster, duration: clip["durationSeconds"] as? Double, thumbnail: clip["thumbnailURL"] as? String, formats: formats, preferredFileStem: "twitch_\(slug)")
        }

        if let vodID = ExtractorUtils.firstMatch("/videos/(\\d+)", in: path) {
            let query = """
            {"query":"{ video(id: \\"\(vodID)\\") { title lengthSeconds previewThumbnailURL(width:1280,height:720) owner { displayName } } playbackAccessToken( params: { platform: \\"web\\", playerBackend: \\"mediaplayer\\", playerType: \\"site\\" }, vodID: \\"\(vodID)\\" ) { value signature } }"}
            """
            let resp = try await client.fetchJSON(
                url: "https://gql.twitch.tv/gql",
                method: "POST",
                headers: ["Client-ID": gqlClientID, "Content-Type": "application/json"],
                body: Data(query.utf8)
            ) as? [String: Any]
            let dataObj = (resp?["data"] as? [String: Any]) ?? [:]
            guard let pb = dataObj["playbackAccessToken"] as? [String: Any],
                  let sig = pb["signature"] as? String,
                  let tok = pb["value"] as? String else {
                throw ExtractorError.notFound("Twitch VOD \(vodID) unavailable.")
            }
            let encTok = tok.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? tok
            let m3u8 = "https://usher.ttvnw.net/vod/\(vodID).m3u8?nauth=\(encTok)&nauthsig=\(sig)&allow_source=true&allow_audio_only=true"
            let fmts = try await HLSEngine.extractFormats(client: client, playlistURL: m3u8)
            let vid = (dataObj["video"] as? [String: Any]) ?? [:]
            return MediaInfo(
                id: vodID,
                title: (vid["title"] as? String) ?? "Twitch VOD \(vodID)",
                site: "Twitch",
                webpageURL: url.absoluteString,
                author: (vid["owner"] as? [String: Any])?["displayName"] as? String,
                duration: (vid["lengthSeconds"] as? Int).map(Double.init),
                thumbnail: vid["previewThumbnailURL"] as? String,
                formats: fmts,
                preferredFileStem: "twitch_\(vodID)"
            )
        }

        throw ExtractorError.unsupported("Live streams can't be saved as memes; share a Twitch clip or VOD link instead.")
    }
}

// MARK: - Kick (Videos & Clips)

public struct KickExtractor: SiteExtractor {
    public var name: String { "Kick" }
    public init() {}

    public func matches(url: URL) -> Bool {
        ExtractorUtils.hostMatches(url, domains: ["kick.com"])
    }

    public func extract(url: URL, client: HTTPClient) async throws -> MediaInfo {
        let path = url.path
        if let vidUUID = ExtractorUtils.firstMatch("/videos?/([0-9a-fA-F-]{36})", in: path) {
            guard let obj = try await client.fetchJSON(url: "https://kick.com/api/v1/video/\(vidUUID)") as? [String: Any],
                  let src = obj["source"] as? String else {
                throw ExtractorError.notFound("Kick video unavailable.")
            }
            let fmts = try await HLSEngine.extractFormats(client: client, playlistURL: src)
            let ls = obj["livestream"] as? [String: Any]
            return MediaInfo(id: vidUUID, title: (ls?["session_title"] as? String) ?? "Kick Video", site: "Kick", webpageURL: url.absoluteString, formats: fmts, preferredFileStem: "kick_\(vidUUID.prefix(8))")
        }
        if let clipID = ExtractorUtils.firstMatch("clip=([A-Za-z0-9_]+)", in: url.absoluteString) ?? ExtractorUtils.firstMatch("/clips/([A-Za-z0-9_]+)", in: path) {
            guard let resp = try await client.fetchJSON(url: "https://kick.com/api/v2/clips/\(clipID)") as? [String: Any],
                  let clip = resp["clip"] as? [String: Any],
                  let curl = clip["clip_url"] as? String else {
                throw ExtractorError.notFound("Kick clip unavailable.")
            }
            let fmts = curl.contains(".m3u8")
                ? (try await HLSEngine.extractFormats(client: client, playlistURL: curl))
                : [MediaFormat(id: "mp4", url: curl, ext: "mp4", proto: .http, vcodec: "avc1", acodec: "mp4a")]
            return MediaInfo(id: clipID, title: (clip["title"] as? String) ?? "Kick Clip", site: "Kick", webpageURL: url.absoluteString, thumbnail: clip["thumbnail_url"] as? String, formats: fmts, preferredFileStem: "kick_\(clipID)")
        }
        throw ExtractorError.unsupported("Live streams can't be saved as memes; share a Kick clip or video link instead.")
    }
}

// MARK: - Reddit (Posts, v.redd.it HLS + DASH + Fallback MP4, Crossposts, Images)

public struct RedditExtractor: SiteExtractor {
    public var name: String { "Reddit" }
    public init() {}

    public func matches(url: URL) -> Bool {
        ExtractorUtils.hostMatches(url, domains: ["reddit.com", "redd.it", "v.redd.it"])
    }

    public func extract(url: URL, client: HTTPClient) async throws -> MediaInfo {
        let host = url.host?.lowercased() ?? ""
        if host == "v.redd.it" {
            let vid = url.path.split(separator: "/").first.map(String.init) ?? "reddit"
            let hlsURL = "https://v.redd.it/\(vid)/HLSPlaylist.m3u8"
            if let fmts = try? await HLSEngine.extractFormats(client: client, playlistURL: hlsURL), !fmts.isEmpty {
                return MediaInfo(id: vid, title: "Reddit Video \(vid)", site: "Reddit", webpageURL: url.absoluteString, formats: fmts, preferredFileStem: "reddit_\(vid)")
            }
        }

        let (_, resolvedURL) = try await client.fetchText(url: url.absoluteString)
        var cleanPath = resolvedURL.path
        if cleanPath.hasSuffix("/") { cleanPath.removeLast() }
        let jsonEndpoint = "https://www.reddit.com\(cleanPath).json?raw_json=1"

        if let arr = try? await client.fetchJSON(url: jsonEndpoint, headers: ["Accept": "application/json"]) as? [Any],
           let firstListing = arr.first as? [String: Any],
           let children = ((firstListing["data"] as? [String: Any])?["children"] as? [[String: Any]]),
           var post = children.first?["data"] as? [String: Any] {
            if let cross = (post["crosspost_parent_list"] as? [[String: Any]])?.first {
                post = cross
            }
            let id = (post["id"] as? String) ?? "reddit"
            let title = (post["title"] as? String) ?? "Reddit Post"
            let author = post["author"] as? String
            let thumb = (post["thumbnail"] as? String).flatMap { $0.hasPrefix("http") ? $0 : nil }

            let rv = ((post["secure_media"] as? [String: Any])?["reddit_video"] as? [String: Any])
                ?? ((post["media"] as? [String: Any])?["reddit_video"] as? [String: Any])
                ?? (((post["preview"] as? [String: Any])?["reddit_video_preview"] as? [String: Any]))

            var formats: [MediaFormat] = []
            if let rv {
                if let hls = rv["hls_url"] as? String,
                   let hlsFmts = try? await HLSEngine.extractFormats(client: client, playlistURL: hls) {
                    formats.append(contentsOf: hlsFmts)
                }
                if let dash = rv["dash_url"] as? String,
                   let (dashFmts, _, _) = try? await DASHEngine.extractFormats(client: client, mpdURL: dash) {
                    formats.append(contentsOf: dashFmts)
                }
                if let fb = rv["fallback_url"] as? String {
                    formats.append(MediaFormat(id: "fallback", url: fb, ext: "mp4", proto: .http, vcodec: "avc1", acodec: "none", height: rv["height"] as? Int))
                }
                let dur = (rv["duration"] as? Double) ?? (rv["duration"] as? Int).map(Double.init)
                if !formats.isEmpty {
                    return MediaInfo(id: id, title: title, site: "Reddit", webpageURL: resolvedURL.absoluteString, author: author, duration: dur, thumbnail: thumb, formats: formats, preferredFileStem: "reddit_\(id)")
                }
            }
            if let dest = post["url_overridden_by_dest"] as? String, dest != resolvedURL.absoluteString {
                let lower = dest.lowercased().components(separatedBy: "?").first ?? ""
                if ["jpg", "jpeg", "png", "webp", "gif"].contains(where: { lower.hasSuffix(".\($0)") }) {
                    return MediaInfo(id: id, title: title, site: "Reddit", webpageURL: resolvedURL.absoluteString, author: author, imageFallbackURL: dest, preferredFileStem: "reddit_\(id)")
                }
                return try await ExtractorRegistry.extract(url: dest, client: client)
            }
        }

        let embedURL = "https://embed.reddit.com\(cleanPath)"
        let (embedHTML, _) = try await client.fetchText(url: embedURL)
        if let hlsMatch = ExtractorUtils.firstMatch("(https://v\\.redd\\.it/[^\"'\\s]+HLSPlaylist\\.m3u8[^\"'\\s]*)", in: embedHTML) {
            let cleanHLS = ExtractorUtils.unescapeJSString(hlsMatch)
            let fmts = try await HLSEngine.extractFormats(client: client, playlistURL: cleanHLS)
            let title = ExtractorUtils.pageTitle(in: embedHTML) ?? "Reddit Video"
            return MediaInfo(id: cleanPath, title: title, site: "Reddit", webpageURL: resolvedURL.absoluteString, formats: fmts)
        }
        throw ExtractorError.notFound("This Reddit post has no downloadable video or image.")
    }
}

// MARK: - X / Twitter (Syndication Token + api.vxtwitter.com Fallback, Video + GIF + Photo)

public struct TwitterExtractor: SiteExtractor {
    public var name: String { "X" }
    public init() {}

    public func matches(url: URL) -> Bool {
        ExtractorUtils.hostMatches(url, domains: ["x.com", "twitter.com", "vxtwitter.com", "fxtwitter.com", "fixupx.com"])
    }

    public func extract(url: URL, client: HTTPClient) async throws -> MediaInfo {
        guard let tweetID = ExtractorUtils.firstMatch("/status(?:es)?/(\\d+)", in: url.path) else {
            throw ExtractorError.notFound("No X/Twitter status ID found in URL.")
        }

        let token = Self.syndicationToken(for: tweetID)
        let syndURL = "https://cdn.syndication.twimg.com/tweet-result?id=\(tweetID)&lang=en&token=\(token)"
        if let obj = try? await client.fetchJSON(url: syndURL, includeCookies: false) as? [String: Any],
           obj["__typename"] as? String != "TweetTombstone",
           obj["tombstone"] == nil,
           let mediaDetails = obj["mediaDetails"] as? [[String: Any]], !mediaDetails.isEmpty {
            var formats: [MediaFormat] = []
            var firstPhoto: String?
            var dur: Double?
            for (mIdx, m) in mediaDetails.enumerated() {
                if firstPhoto == nil, let p = m["media_url_https"] as? String, !p.isEmpty { firstPhoto = p }
                if let vinfo = m["video_info"] as? [String: Any] {
                    if let dMs = vinfo["duration_millis"] as? Double { dur = dMs / 1000.0 }
                    for v in (vinfo["variants"] as? [[String: Any]]) ?? [] {
                        guard let vurl = v["url"] as? String else { continue }
                        let ct = (v["content_type"] as? String) ?? ""
                        if ct.contains("mpegURL") || vurl.contains(".m3u8") {
                            if let hls = try? await HLSEngine.extractFormats(client: client, playlistURL: vurl, idPrefix: "hls-\(mIdx)") {
                                formats.append(contentsOf: hls)
                            }
                        } else if ct.contains("mp4") {
                            let br = (v["bitrate"] as? Int).map(Int64.init)
                            let h = ExtractorUtils.firstMatch("/\\d+x(\\d+)/", in: vurl).flatMap(Int.init)
                            formats.append(MediaFormat(
                                id: "mp4-\(h ?? mIdx)-\(br ?? 0)",
                                url: vurl,
                                ext: "mp4",
                                proto: .http,
                                vcodec: "avc1",
                                acodec: "mp4a",
                                height: h,
                                bitrate: br
                            ))
                        }
                    }
                }
            }
            if !formats.isEmpty || firstPhoto != nil {
                let rawText = ((obj["text"] as? String) ?? "X \(tweetID)")
                    .replacingOccurrences(of: "\\s*https://t\\.co/\\S+", with: "", options: .regularExpression)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                let user = (obj["user"] as? [String: Any])
                let author = (user?["screen_name"] as? String) ?? (user?["name"] as? String)
                return MediaInfo(
                    id: tweetID,
                    title: rawText.isEmpty ? "X \(tweetID)" : String(rawText.prefix(100)),
                    site: "X",
                    webpageURL: "https://x.com/i/status/\(tweetID)",
                    author: author,
                    duration: dur,
                    thumbnail: firstPhoto,
                    formats: formats,
                    imageFallbackURL: formats.isEmpty ? firstPhoto.map { "\($0)?name=large" } : nil,
                    preferredFileStem: "tweet_\(tweetID)"
                )
            }
        }

        if let vx = try? await client.fetchJSON(url: "https://api.vxtwitter.com/Twitter/status/\(tweetID)", includeCookies: false) as? [String: Any],
           let extMedia = vx["media_extended"] as? [[String: Any]] {
            var formats: [MediaFormat] = []
            var firstPhoto: String?
            var thumb: String?
            var dur: Double?
            for (idx, m) in extMedia.enumerated() {
                guard let vurl = m["url"] as? String, !vurl.isEmpty else { continue }
                let tp = (m["type"] as? String) ?? ""
                if tp == "video" || tp == "gif" {
                    if thumb == nil { thumb = m["thumbnail_url"] as? String }
                    if dur == nil, let dMs = m["duration_millis"] as? Double { dur = dMs / 1000.0 }
                    let sz = m["size"] as? [String: Any]
                    formats.append(MediaFormat(
                        id: "mp4-\(idx)",
                        url: vurl,
                        ext: "mp4",
                        proto: .http,
                        vcodec: "avc1",
                        acodec: "mp4a",
                        width: sz?["width"] as? Int,
                        height: sz?["height"] as? Int
                    ))
                } else if tp == "image" && firstPhoto == nil {
                    firstPhoto = vurl
                }
            }
            if !formats.isEmpty || firstPhoto != nil {
                let txt = ((vx["text"] as? String) ?? "X \(tweetID)").trimmingCharacters(in: .whitespacesAndNewlines)
                return MediaInfo(
                    id: tweetID,
                    title: txt.isEmpty ? "X \(tweetID)" : String(txt.prefix(100)),
                    site: "X",
                    webpageURL: "https://x.com/i/status/\(tweetID)",
                    author: vx["user_screen_name"] as? String,
                    duration: dur,
                    thumbnail: thumb ?? firstPhoto,
                    formats: formats,
                    imageFallbackURL: formats.isEmpty ? firstPhoto : nil,
                    preferredFileStem: "tweet_\(tweetID)"
                )
            }
        }

        throw ExtractorError.notFound("No video or image found in X post \(tweetID).")
    }

    public static func syndicationToken(for id: String) -> String {
        guard let d = Double(id) else { return "0" }
        let val = (d / 1e15) * Double.pi
        let intPart = Int(val)
        var frac = val - Double(intPart)
        let digits = Array("0123456789abcdefghijklmnopqrstuvwxyz")
        var out = String(intPart, radix: 36)
        for _ in 0..<10 {
            frac *= 36
            let dIdx = max(0, min(35, Int(frac)))
            out.append(digits[dIdx])
            frac -= Double(dIdx)
        }
        return out.replacingOccurrences(of: "0", with: "")
    }
}
