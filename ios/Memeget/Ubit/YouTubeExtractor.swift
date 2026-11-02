import Foundation
import JavaScriptCore
import CryptoKit

public struct YouTubeExtractor: SiteExtractor {
    public var name: String { "YouTube" }

    public init() {}

    public func matches(url: URL) -> Bool {
        ExtractorUtils.hostMatches(url, domains: ["youtube.com", "youtu.be", "youtube-nocookie.com"])
    }

    public func extract(url: URL, client: HTTPClient) async throws -> MediaInfo {
        guard let vid = Self.extractVideoID(from: url) else {
            throw ExtractorError.notFound("Could not find a YouTube video or Short at this link.")
        }
        return try await extractVideo(videoID: vid, client: client)
    }

    public static func extractVideoID(from url: URL) -> String? {
        let host = url.host?.lowercased() ?? ""
        let path = url.path
        if host == "youtu.be" || host.hasSuffix(".youtu.be") {
            let seg = path.split(separator: "/").first.map(String.init)
            if let s = seg, s.count == 11 { return s }
        }
        if let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
           let v = comps.queryItems?.first(where: { $0.name == "v" })?.value, v.count == 11 {
            return v
        }
        for prefix in ["/shorts/", "/embed/", "/live/", "/v/"] {
            if path.hasPrefix(prefix) {
                let rest = String(path.dropFirst(prefix.count)).split(separator: "/").first.map(String.init) ?? ""
                if rest.count == 11 { return rest }
            }
        }
        return nil
    }

    private struct YTClientProfile {
        let label: String
        let name: String
        let version: String
        let clientNameHeader: String
        let userAgent: String
        let extraFields: [String: Any]
        let supportsCookies: Bool
        let requiresJS: Bool
    }

    private static let visionOSProfile = YTClientProfile(
        label: "visionos",
        name: "VISIONOS",
        version: "1.02",
        clientNameHeader: "101",
        userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        extraFields: [
            "deviceMake": "Apple",
            "deviceModel": "RealityDevice17,1",
            "osName": "visionOS",
            "osVersion": "26.5.23O471",
        ],
        supportsCookies: false,
        requiresJS: false
    )

    private static let iosProfile = YTClientProfile(
        label: "ios",
        name: "IOS",
        version: "21.26.4",
        clientNameHeader: "5",
        userAgent: "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        extraFields: [
            "deviceMake": "Apple",
            "deviceModel": "iPhone16,2",
            "osName": "iPhone",
            "osVersion": "18.3.2.22D82",
        ],
        supportsCookies: false,
        requiresJS: false
    )

    private static let tvSamsungProfile = YTClientProfile(
        label: "tv_samsung",
        name: "TVHTML5",
        version: "5.20260707",
        clientNameHeader: "7",
        userAgent: "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
        extraFields: [
            "deviceMake": "Samsung",
            "deviceModel": "UKS9800",
            "osName": "Tizen",
            "osVersion": "2.4.0",
        ],
        supportsCookies: true,
        requiresJS: true
    )

    private static let webProfile = YTClientProfile(
        label: "web",
        name: "WEB",
        version: "2.20261029.11.00",
        clientNameHeader: "1",
        userAgent: HTTPClient.defaultBrowserUA,
        extraFields: [:],
        supportsCookies: true,
        requiresJS: true
    )

    private struct WatchContext {
        var visitorData: String?
        var jsURL: String?
        var sts: Int?
        var playerResponse: [String: Any]?
    }

    private func fetchWatchContext(videoID: String, client: HTTPClient) async -> WatchContext {
        var ctx = WatchContext()
        if let (html, _) = try? await client.fetchText(
            url: "https://www.youtube.com/watch?v=\(videoID)&bpctr=9999999999&has_verified=1",
            headers: ["Accept-Language": "en-US,en;q=0.9"]
        ) {
            ctx.visitorData = ExtractorUtils.firstMatch("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"", in: html)
            if let jsRel = ExtractorUtils.firstMatch("\"jsUrl\"\\s*:\\s*\"([^\"]+base\\.js)\"", in: html) {
                ctx.jsURL = jsRel.hasPrefix("http") ? jsRel : "https://www.youtube.com\(jsRel)"
            }
            ctx.sts = ExtractorUtils.firstMatch("\"signatureTimestamp\"\\s*:\\s*(\\d+)", in: html).flatMap(Int.init)
            if let ipr = ExtractorUtils.extractJSONAfter("var ytInitialPlayerResponse =", in: html) as? [String: Any] {
                ctx.playerResponse = ipr
            }
        }
        if ctx.visitorData == nil,
           let (sw, _) = try? await client.fetchText(url: "https://www.youtube.com/sw.js_data", includeCookies: false) {
            ctx.visitorData = ExtractorUtils.firstMatch("\"(Cgt[A-Za-z0-9_-]{10,})\"", in: sw)
        }
        return ctx
    }

    private func verifyTailRangePlayable(url: String, contentLength: Int64?, userAgent: String, client: HTTPClient) async -> Bool {
        let clen = contentLength ?? 0
        let probeStart = clen > 131_072 ? (clen - 4096) : 0
        let probeEnd = clen > 131_072 ? (clen - 1) : 2047
        do {
            let chunk = try await client.fetchRangeBytes(
                url: url,
                range: ByteRange(start: probeStart, end: probeEnd),
                headers: ["User-Agent": userAgent]
            )
            return !chunk.isEmpty
        } catch {
            return false
        }
    }

    private func extractVideo(videoID: String, client: HTTPClient) async throws -> MediaInfo {
        let watchCtx = await fetchWatchContext(videoID: videoID, client: client)

        let candidateProfiles: [YTClientProfile] = [
            Self.visionOSProfile,
            Self.iosProfile,
            Self.tvSamsungProfile,
            Self.webProfile,
        ]

        var chosenResp: [String: Any]?
        var chosenFormats: [MediaFormat] = []
        var chosenHLSManifest: String?
        var chosenHLSUA: String = Self.visionOSProfile.userAgent
        var cachedSolver: JSPlayerCipherSolver?
        var loadedSolver = false
        var lastReason: String?

        for profile in candidateProfiles {
            guard let resp = try? await callPlayer(
                videoID: videoID,
                profile: profile,
                visitorData: watchCtx.visitorData,
                sts: profile.requiresJS ? watchCtx.sts : nil,
                client: client
            ) else { continue }

            let pStatus = (resp["playabilityStatus"] as? [String: Any])?["status"] as? String ?? ""
            if pStatus != "OK" && resp["streamingData"] == nil {
                if let r = (resp["playabilityStatus"] as? [String: Any])?["reason"] as? String {
                    lastReason = r
                }
                continue
            }
            guard let sd = resp["streamingData"] as? [String: Any] else { continue }

            if chosenHLSManifest == nil, let hlsM = sd["hlsManifestUrl"] as? String {
                chosenHLSManifest = hlsM
                chosenHLSUA = profile.userAgent
            }

            let rawFmts = ((sd["formats"] as? [[String: Any]]) ?? []) + ((sd["adaptiveFormats"] as? [[String: Any]]) ?? [])
            let hasCipher = rawFmts.contains { $0["url"] == nil && ($0["signatureCipher"] != nil || $0["cipher"] != nil) }
            let needsJS = hasCipher || (profile.requiresJS && !rawFmts.isEmpty)

            if needsJS && !loadedSolver {
                loadedSolver = true
                cachedSolver = try? await loadPlayerCipherSolver(videoID: videoID, knownJSURL: watchCtx.jsURL, client: client)
            }

            var parsed: [MediaFormat] = []
            for f in rawFmts {
                guard let itag = f["itag"] as? Int else { continue }
                var streamURL = f["url"] as? String
                if streamURL == nil, let cipherStr = (f["signatureCipher"] as? String) ?? (f["cipher"] as? String),
                   let solver = cachedSolver {
                    streamURL = solver.resolveCipher(cipherStr)
                }
                guard var finalStreamURL = streamURL, !finalStreamURL.isEmpty else { continue }
                if let solver = cachedSolver {
                    finalStreamURL = solver.transformNParam(in: finalStreamURL)
                }

                let mime = (f["mimeType"] as? String) ?? "video/mp4"
                let (ext, vcodec, acodec) = Self.parseYouTubeMime(mime)
                let width = f["width"] as? Int
                let height = f["height"] as? Int
                let fps = (f["fps"] as? Double) ?? (f["fps"] as? Int).map(Double.init)
                let bitrate = (f["averageBitrate"] as? Int64) ?? (f["bitrate"] as? Int64) ?? (f["bitrate"] as? Int).map(Int64.init)
                let clen = (f["contentLength"] as? String).flatMap(Int64.init)
                let qLabel = (f["qualityLabel"] as? String)

                var initR: ByteRange?
                if let ir = f["initRange"] as? [String: Any],
                   let s = (ir["start"] as? String).flatMap(Int64.init),
                   let e = (ir["end"] as? String).flatMap(Int64.init) {
                    initR = ByteRange(start: s, end: e)
                }
                var idxR: ByteRange?
                if let xr = f["indexRange"] as? [String: Any],
                   let s = (xr["start"] as? String).flatMap(Int64.init),
                   let e = (xr["end"] as? String).flatMap(Int64.init) {
                    idxR = ByteRange(start: s, end: e)
                }

                parsed.append(MediaFormat(
                    id: String(itag),
                    url: finalStreamURL,
                    ext: ext,
                    proto: .http,
                    vcodec: vcodec,
                    acodec: acodec,
                    width: width,
                    height: height,
                    fps: fps,
                    bitrate: bitrate,
                    filesize: clen,
                    audioSampleRate: (f["audioSampleRate"] as? String).flatMap(Int.init),
                    qualityLabel: qLabel,
                    headers: ["User-Agent": profile.userAgent],
                    initRange: initR,
                    indexRange: idxR
                ))
            }

            if let probeTarget = parsed.first(where: { ($0.filesize ?? 0) > 2_000_000 }) ?? parsed.first {
                let playable = await verifyTailRangePlayable(
                    url: probeTarget.url,
                    contentLength: probeTarget.filesize,
                    userAgent: profile.userAgent,
                    client: client
                )
                if playable {
                    chosenResp = resp
                    chosenFormats = parsed
                    break
                }
            } else if sd["hlsManifestUrl"] != nil {
                chosenResp = resp
                break
            }
        }

        let metaResp = chosenResp ?? watchCtx.playerResponse
        guard let resp = metaResp else {
            throw ExtractorError.notFound("YouTube player request failed for \(videoID).")
        }

        let details = (resp["videoDetails"] as? [String: Any])
            ?? (watchCtx.playerResponse?["videoDetails"] as? [String: Any])
            ?? [:]
        let micro = ((watchCtx.playerResponse?["microformat"] ?? resp["microformat"]) as? [String: Any])?["playerMicroformatRenderer"] as? [String: Any]

        let title = (details["title"] as? String) ?? videoID
        let author = (details["author"] as? String)
        let description = (details["shortDescription"] as? String) ?? ((micro?["description"] as? [String: Any])?["simpleText"] as? String)
        let duration = (details["lengthSeconds"] as? String).flatMap(Double.init)
        let isLive = (details["isLiveContent"] as? Bool == true) && ((details["isLive"] as? Bool == true) || duration == 0)
        let uploadDate = (micro?["publishDate"] as? String).map { String($0.prefix(10)) }
        let thumb = "https://i.ytimg.com/vi/\(videoID)/maxresdefault.jpg"

        var formats = chosenFormats
        if let hlsManifest = chosenHLSManifest ?? ((watchCtx.playerResponse?["streamingData"] as? [String: Any])?["hlsManifestUrl"] as? String) {
            if isLive || formats.isEmpty {
                if let hlsFmts = try? await HLSEngine.extractFormats(client: client, playlistURL: hlsManifest, headers: ["User-Agent": chosenHLSUA], idPrefix: "hls") {
                    for var hf in hlsFmts {
                        hf.isLive = isLive
                        formats.append(hf)
                    }
                }
            }
        }

        if formats.isEmpty {
            throw ExtractorError.notFound("YouTube: \(lastReason ?? "No playable non-DRM streams returned")")
        }

        let chapters = SponsorAndSubtitles.chaptersFromDescription(description, totalDuration: duration)

        return MediaInfo(
            id: videoID,
            title: title,
            site: "YouTube",
            webpageURL: "https://www.youtube.com/watch?v=\(videoID)",
            author: author,
            uploadDate: uploadDate,
            description: description,
            duration: duration,
            thumbnail: thumb,
            isLive: isLive,
            formats: formats,
            chapters: chapters,
            preferredFileStem: "youtube_\(videoID)"
        )
    }

    private func callPlayer(
        videoID: String,
        profile: YTClientProfile,
        visitorData: String?,
        sts: Int?,
        client: HTTPClient
    ) async throws -> [String: Any] {
        var clientDict: [String: Any] = [
            "clientName": profile.name,
            "clientVersion": profile.version,
            "hl": "en",
            "gl": "US",
            "userAgent": profile.userAgent,
        ]
        if let visitorData, !visitorData.isEmpty {
            clientDict["visitorData"] = visitorData
        }
        for (k, v) in profile.extraFields { clientDict[k] = v }

        let context: [String: Any] = ["client": clientDict]
        var bodyObj: [String: Any] = [
            "videoId": videoID,
            "context": context,
            "contentCheckOk": true,
            "racyCheckOk": true,
        ]
        if let sts {
            bodyObj["playbackContext"] = [
                "contentPlaybackContext": [
                    "signatureTimestamp": sts,
                    "html5Preference": "HTML5_PREF_WANTS",
                ]
            ]
        }

        let bodyData = try JSONSerialization.data(withJSONObject: bodyObj)
        var headers: [String: String] = [
            "Content-Type": "application/json",
            "User-Agent": profile.userAgent,
            "X-YouTube-Client-Name": profile.clientNameHeader,
            "X-YouTube-Client-Version": profile.version,
            "Origin": "https://www.youtube.com",
        ]
        if let visitorData, !visitorData.isEmpty {
            headers["X-Goog-Visitor-Id"] = visitorData
        }

        if profile.supportsCookies,
           let sapisid = client.cookies.cookieValue(named: "SAPISID", forDomain: "youtube.com")
                ?? client.cookies.cookieValue(named: "__Secure-3PAPISID", forDomain: "youtube.com") {
            let ts = Int(Date().timeIntervalSince1970)
            let input = "\(ts) \(sapisid) https://www.youtube.com"
            let sha1 = Insecure.SHA1.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
            headers["Authorization"] = "SAPISIDHASH \(ts)_\(sha1)"
            headers["X-Origin"] = "https://www.youtube.com"
        }

        let json = try await client.fetchJSON(
            url: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            method: "POST",
            headers: headers,
            body: bodyData,
            includeCookies: profile.supportsCookies
        )
        return (json as? [String: Any]) ?? [:]
    }

    private static func parseYouTubeMime(_ mime: String) -> (ext: String, vcodec: String?, acodec: String?) {
        let mainPart = mime.split(separator: ";").first.map { String($0).trimmingCharacters(in: .whitespaces).lowercased() } ?? "video/mp4"
        let isAudioOnly = mainPart.hasPrefix("audio/")
        let isWebM = mainPart.contains("webm")
        let ext = isWebM ? "webm" : (isAudioOnly ? "m4a" : "mp4")

        let codecsStr = ExtractorUtils.firstMatch("codecs=\"([^\"]+)\"", in: mime) ?? ""
        let codecs = codecsStr.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }

        if isAudioOnly {
            return (ext, "none", codecs.first ?? (isWebM ? "opus" : "mp4a.40.2"))
        }
        if codecs.count >= 2 {
            return (ext, codecs[0], codecs[1])
        }
        return (ext, codecs.first ?? "avc1", "none")
    }

    private func loadPlayerCipherSolver(videoID: String, knownJSURL: String?, client: HTTPClient) async throws -> JSPlayerCipherSolver? {
        var resolvedJSURL = knownJSURL
        if resolvedJSURL == nil {
            let (embedHTML, _) = try await client.fetchText(url: "https://www.youtube.com/embed/\(videoID)")
            if let jsRel = ExtractorUtils.firstMatch("\"jsUrl\":\"([^\"]+base\\.js)\"", in: embedHTML) {
                resolvedJSURL = jsRel.hasPrefix("http") ? jsRel : "https://www.youtube.com\(jsRel)"
            }
        }
        guard let jsURL = resolvedJSURL else { return nil }
        let (jsCode, _) = try await client.fetchText(url: jsURL, includeCookies: false)
        return JSPlayerCipherSolver(playerJS: jsCode)
    }
}

private final class JSPlayerCipherSolver: @unchecked Sendable {
    private let lock = NSLock()
    private let ctx: JSContext?
    private var hasSigFn = false
    private var hasNFn = false

    init(playerJS: String) {
        let context = JSContext()
        self.ctx = context
        if let sigFnName = ExtractorUtils.firstMatch("\\b[cs]\\s*&&\\s*[adf]\\.set\\([^,]+,\\s*encodeURIComponent\\(([a-zA-Z0-9$]+)\\(", in: playerJS)
            ?? ExtractorUtils.firstMatch("([a-zA-Z0-9$]+)\\s*=\\s*function\\(\\s*a\\s*\\)\\s*\\{\\s*a\\s*=\\s*a\\.split\\(\\s*\"\"\\s*\\)", in: playerJS) {
            let escapedFn = NSRegularExpression.escapedPattern(for: sigFnName)
            if let fnBody = ExtractorUtils.firstMatch("(?:var\\s+)?\(escapedFn)\\s*=\\s*(function\\(\\s*a\\s*\\)\\s*\\{[^}]+\\})", in: playerJS) {
                var script = ""
                if let helperObj = ExtractorUtils.firstMatch(";([a-zA-Z0-9$]{2,})\\.[a-zA-Z0-9$]+\\(a,\\d+\\)", in: fnBody) {
                    let escObj = NSRegularExpression.escapedPattern(for: helperObj)
                    if let objDecl = ExtractorUtils.firstMatch("(var\\s+\(escObj)\\s*=\\s*\\{[\\s\\S]*?\\};)", in: playerJS) {
                        script += objDecl + "\n"
                    }
                }
                script += "var __decipher = \(fnBody);"
                context?.evaluateScript(script)
                hasSigFn = true
            }
        }
        if let nArrName = ExtractorUtils.firstMatch("\\.get\\(\"n\"\\)\\)&&\\(b=([a-zA-Z0-9$]+)(?:\\[(\\d+)\\])?\\([a-zA-Z0-9]\\)", in: playerJS) {
            var fnName = nArrName
            let escArr = NSRegularExpression.escapedPattern(for: nArrName)
            if let realFn = ExtractorUtils.firstMatch("var\\s+\(escArr)\\s*=\\s*\\[([a-zA-Z0-9$]+)\\]", in: playerJS) {
                fnName = realFn
            }
            let escFn = NSRegularExpression.escapedPattern(for: fnName)
            if let nBody = ExtractorUtils.firstMatch("\(escFn)\\s*=\\s*(function\\([a-zA-Z0-9]\\)\\{[\\s\\S]*?return\\s+[a-zA-Z0-9]\\.join\\(\"\"\\)\\})", in: playerJS) {
                context?.evaluateScript("var __nsolve = \(nBody);")
                if context?.exception == nil { hasNFn = true }
                context?.exception = nil
            }
        }
    }

    func resolveCipher(_ cipherQuery: String) -> String? {
        var comps = URLComponents()
        comps.percentEncodedQuery = cipherQuery
        let items = comps.queryItems ?? []
        guard var baseURL = items.first(where: { $0.name == "url" })?.value else { return nil }
        let sp = items.first(where: { $0.name == "sp" })?.value ?? "sig"
        if let s = items.first(where: { $0.name == "s" })?.value, hasSigFn {
            lock.lock()
            let decodedSig = ctx?.objectForKeyedSubscript("__decipher")?.call(withArguments: [s])?.toString()
            lock.unlock()
            if let sig = decodedSig, !sig.isEmpty, sig != "undefined" {
                let sep = baseURL.contains("?") ? "&" : "?"
                baseURL += "\(sep)\(sp)=\(sig.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? sig)"
            }
        }
        return baseURL
    }

    func transformNParam(in url: String) -> String {
        guard hasNFn, var comps = URLComponents(string: url),
              var items = comps.queryItems,
              let idx = items.firstIndex(where: { $0.name == "n" }),
              let oldN = items[idx].value else {
            return url
        }
        lock.lock()
        let newN = ctx?.objectForKeyedSubscript("__nsolve")?.call(withArguments: [oldN])?.toString()
        lock.unlock()
        if let newN, !newN.isEmpty, newN != "undefined", newN != oldN {
            items[idx].value = newN
            comps.queryItems = items
            return comps.url?.absoluteString ?? url
        }
        return url
    }
}
