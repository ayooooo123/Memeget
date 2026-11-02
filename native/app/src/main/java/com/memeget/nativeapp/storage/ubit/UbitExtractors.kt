package com.memeget.nativeapp.storage.ubit

import com.memeget.nativeapp.storage.LinkResolver
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

// Unified 35-platform extractor registry ported from uBit so Master can sling links from any video site straight into Memeget.

internal object UbitExtractors {

    private val DIRECT_VIDEO_EXTS = setOf("mp4", "m4v", "mov", "webm", "m4a", "mp3")
    private val DIRECT_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun hostMatches(url: HttpUrl, vararg domains: String): Boolean {
        val h = url.host.lowercase(Locale.ROOT)
        return domains.any { d -> h == d || h.endsWith(".$d") }
    }

    fun firstMatch(pattern: String, text: String, group: Int = 1, ignoreCase: Boolean = false): String? {
        val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val m = Regex(pattern, opts).find(text) ?: return null
        return m.groupValues.getOrNull(group)?.takeIf { it.isNotEmpty() }
    }

    fun allMatches(pattern: String, text: String, group: Int = 1, ignoreCase: Boolean = false): List<String> {
        val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex(pattern, opts).findAll(text).mapNotNull { m ->
            m.groupValues.getOrNull(group)?.takeIf { it.isNotEmpty() }
        }.toList()
    }

    fun unescapeJsString(raw: String): String {
        val wrapped = "\"" + raw.replace("\"", "\\\"") + "\""
        runCatching {
            val parsed = JSONTokener(wrapped).nextValue()
            if (parsed is String && parsed.isNotEmpty()) {
                return LinkResolver.decodeEntities(parsed)
            }
        }
        return LinkResolver.decodeEntities(
            raw
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u002F", "/")
                .replace("\\u003d", "=")
                .replace("\\u003D", "=")
                .replace("\\u0025", "%")
        )
    }

    // Extracts balanced {...} or [...] JSON object/array immediately following marker in HTML/JS.
    fun extractJsonAfter(marker: String, text: String): Any? {
        val idx0 = text.indexOf(marker)
        if (idx0 < 0) return null
        var i = idx0 + marker.length
        while (i < text.length && (text[i].isWhitespace() || text[i] == '=')) i++
        if (i >= text.length || (text[i] != '{' && text[i] != '[')) return null
        val openCh = text[i]
        val closeCh = if (openCh == '{') '}' else ']'
        var depth = 0
        var inStr = false
        var quoteCh = '"'
        var esc = false
        val start = i
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == quoteCh) inStr = false
            } else {
                when (c) {
                    '"', '\'' -> {
                        inStr = true
                        quoteCh = c
                    }
                    openCh -> depth++
                    closeCh -> {
                        depth--
                        if (depth == 0) {
                            val sub = text.substring(start, i + 1).replace(":undefined", ":null")
                            return runCatching { JSONTokener(sub).nextValue() }.getOrNull()
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    fun pageTitle(html: String): String? {
        LinkResolver.metaContent(html, "og:title", "twitter:title")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val t = firstMatch("<title[^>]*>([\\s\\S]*?)</title>", html, ignoreCase = true)
        return t?.let { LinkResolver.decodeEntities(it).trim() }?.takeIf { it.isNotEmpty() }
    }

    fun extract(rawUrl: String, client: UbitHttpClient): UbitMediaInfo {
        val parsed = rawUrl.trim().toHttpUrlOrNull() ?: throw IOException("Invalid web link: $rawUrl")
        val pathExt = parsed.encodedPath.substringAfterLast('/', "").substringAfterLast('.', "").lowercase(Locale.ROOT)

        // Direct manifest / file fast paths
        if (pathExt == "m3u8") {
            val fmts = UbitHlsEngine.extractFormats(client, parsed.toString())
            return UbitMediaInfo(
                id = "hls",
                title = "HLS Stream",
                site = parsed.host,
                webpageUrl = parsed.toString(),
                isLive = fmts.any { it.isLive },
                formats = fmts,
            )
        }
        if (pathExt == "mpd") {
            val (fmts, reps) = UbitDashEngine.extractFormats(client, parsed.toString())
            return UbitMediaInfo(
                id = "dash",
                title = "DASH Stream",
                site = parsed.host,
                webpageUrl = parsed.toString(),
                isLive = fmts.any { it.isLive },
                formats = fmts,
                dashRepresentations = reps,
            )
        }
        if (pathExt in DIRECT_VIDEO_EXTS) {
            val isAud = pathExt == "m4a" || pathExt == "mp3"
            val stem = runCatching {
                URLDecoder.decode(parsed.encodedPath.substringAfterLast('/').substringBeforeLast('.'), "UTF-8")
            }.getOrDefault("video").ifBlank { "video" }
            return UbitMediaInfo(
                id = stem,
                title = stem,
                site = parsed.host,
                webpageUrl = parsed.toString(),
                formats = listOf(
                    UbitFormat(
                        id = "direct-$pathExt",
                        url = parsed.toString(),
                        ext = if (pathExt == "m4v" || pathExt == "mov") "mp4" else pathExt,
                        vcodec = if (isAud) "none" else "avc1",
                        acodec = if (pathExt == "mp3") "mp3" else "mp4a",
                    )
                ),
            )
        }
        if (pathExt in DIRECT_IMAGE_EXTS) {
            return UbitMediaInfo(
                id = "image",
                title = parsed.encodedPath.substringAfterLast('/'),
                site = parsed.host,
                webpageUrl = parsed.toString(),
                imageFallbackUrl = parsed.toString(),
            )
        }

        return when {
            hostMatches(parsed, "youtube.com", "youtu.be", "youtube-nocookie.com") -> extractYouTube(parsed, client)
            hostMatches(parsed, "x.com", "twitter.com", "vxtwitter.com", "fxtwitter.com", "fixupx.com") -> extractTwitter(parsed, client)
            hostMatches(parsed, "reddit.com", "redd.it", "v.redd.it") -> extractReddit(parsed, client)
            hostMatches(parsed, "tiktok.com", "vm.tiktok.com", "vt.tiktok.com") -> extractTikTok(parsed, client)
            hostMatches(parsed, "instagram.com") -> extractInstagram(parsed, client)
            hostMatches(parsed, "facebook.com", "fb.watch") -> extractFacebook(parsed, client)
            hostMatches(parsed, "threads.net", "threads.com") -> extractGeneric(parsed.toString(), client, forceSite = "Threads")
            hostMatches(parsed, "bsky.app") -> extractBluesky(parsed, client)
            hostMatches(parsed, "twitch.tv") -> extractTwitch(parsed, client)
            hostMatches(parsed, "kick.com") -> extractKick(parsed, client)
            hostMatches(parsed, "streamable.com") -> extractStreamable(parsed, client)
            hostMatches(parsed, "vimeo.com") -> extractVimeo(parsed, client)
            hostMatches(parsed, "bilibili.com", "b23.tv", "bili2233.cn") -> extractBilibili(parsed, client)
            hostMatches(parsed, "dailymotion.com", "dai.ly") -> extractDailymotion(parsed, client)
            hostMatches(parsed, "rumble.com") -> extractRumble(parsed, client)
            hostMatches(parsed, "odysee.com", "lbry.tv") -> extractOdysee(parsed, client)
            parsed.encodedPath.startsWith("/videos/watch/") || parsed.encodedPath.startsWith("/w/") -> extractPeerTube(parsed, client)
            hostMatches(parsed, "archive.org") -> extractInternetArchive(parsed, client)
            hostMatches(parsed, "loom.com") -> extractLoom(parsed, client)
            hostMatches(parsed, "ted.com") -> extractTed(parsed, client)
            hostMatches(parsed, "imgur.com") -> extractImgur(parsed, client)
            hostMatches(parsed, "pinterest.com", "pin.it") -> extractPinterest(parsed, client)
            hostMatches(parsed, "coub.com") -> extractCoub(parsed, client)
            hostMatches(parsed, "9gag.com") -> extractNineGag(parsed, client)
            hostMatches(parsed, "xiaohongshu.com", "xhslink.com") -> extractXiaohongshu(parsed, client)
            hostMatches(parsed, "soundcloud.com", "on.soundcloud.com") -> extractSoundCloud(parsed, client)
            hostMatches(parsed, "bandcamp.com") -> extractBandcamp(parsed, client)
            hostMatches(parsed, "github.com") && (parsed.encodedPath.contains("/assets/") || parsed.encodedPath.contains("/user-attachments/")) ->
                extractGitHubAsset(parsed, client)
            Regex("^/@[^/]+/(\\d+)$").matches(parsed.encodedPath) -> extractMastodon(parsed, client)
            else -> {
                val site = when {
                    hostMatches(parsed, "tumblr.com") -> "Tumblr"
                    hostMatches(parsed, "vk.com", "vkvideo.ru") -> "VK"
                    hostMatches(parsed, "douyin.com", "iesdouyin.com") -> "Douyin"
                    hostMatches(parsed, "kuaishou.com") -> "Kuaishou"
                    hostMatches(parsed, "weibo.com", "weibo.cn") -> "Weibo"
                    hostMatches(parsed, "mixcloud.com") -> "Mixcloud"
                    else -> null
                }
                extractGeneric(parsed.toString(), client, forceSite = site)
            }
        }
    }

    // MARK: - 1. YouTube (VISIONOS 1.02 + IOS + TVHTML5 + Tail-Range PO-Token Probe)

    private data class YtProfile(
        val name: String,
        val version: String,
        val clientIdHeader: String,
        val userAgent: String,
        val extra: Map<String, String>,
    )

    private val YT_PROFILES = listOf(
        YtProfile(
            name = "VISIONOS",
            version = "1.02",
            clientIdHeader = "101",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            extra = mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1", "osName" to "visionOS", "osVersion" to "26.5.23O471"),
        ),
        YtProfile(
            name = "IOS",
            version = "21.26.4",
            clientIdHeader = "5",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra = mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
        ),
        YtProfile(
            name = "TVHTML5",
            version = "5.20260707",
            clientIdHeader = "7",
            userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
            extra = mapOf("deviceMake" to "Samsung", "deviceModel" to "UKS9800", "osName" to "Tizen", "osVersion" to "2.4.0"),
        ),
    )

    fun extractYouTubeVideoId(u: HttpUrl): String? {
        if (hostMatches(u, "youtu.be")) {
            val seg = u.pathSegments.firstOrNull().orEmpty()
            if (seg.length == 11) return seg
        }
        u.queryParameter("v")?.takeIf { it.length == 11 }?.let { return it }
        val p = u.encodedPath
        for (pfx in listOf("/shorts/", "/embed/", "/live/", "/v/")) {
            if (p.startsWith(pfx)) {
                val id = p.removePrefix(pfx).substringBefore('/')
                if (id.length == 11) return id
            }
        }
        return null
    }

    private fun extractYouTube(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val vid = extractYouTubeVideoId(u)
            ?: throw IOException("Could not find a YouTube video or Short ID at that link.")

        val origin = "${u.scheme}://${u.host}"
        val ytApiBase = if (u.host == "127.0.0.1" || u.host == "localhost") origin else "https://www.youtube.com"

        var visitorData: String? = null
        var watchIpr: JSONObject? = null
        runCatching {
            val (whtml, _) = client.fetchText(
                "$ytApiBase/watch?v=$vid&bpctr=9999999999&has_verified=1",
                headers = mapOf("Accept-Language" to "en-US,en;q=0.9"),
            )
            visitorData = firstMatch("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"", whtml)
            watchIpr = extractJsonAfter("var ytInitialPlayerResponse =", whtml) as? JSONObject
        }
        if (visitorData.isNullOrBlank()) {
            runCatching {
                val (sw, _) = client.fetchText("$ytApiBase/sw.js_data", includeCookies = false)
                visitorData = firstMatch("\"(Cgt[A-Za-z0-9_-]{10,})\"", sw)
            }
        }

        var chosenResp: JSONObject? = null
        var chosenFmts: List<UbitFormat> = emptyList()
        var hlsManifest: String? = null
        var hlsUa: String = UbitHttpClient.DEFAULT_BROWSER_UA
        var lastReason: String? = null

        for (prof in YT_PROFILES) {
            val cObj = JSONObject().apply {
                put("clientName", prof.name)
                put("clientVersion", prof.version)
                put("hl", "en")
                put("gl", "US")
                put("userAgent", prof.userAgent)
                if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                for ((k, v) in prof.extra) put(k, v)
            }
            val bodyJson = JSONObject().apply {
                put("videoId", vid)
                put("context", JSONObject().put("client", cObj))
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            val hdrs = LinkedHashMap<String, String>().apply {
                put("Content-Type", "application/json")
                put("User-Agent", prof.userAgent)
                put("X-YouTube-Client-Name", prof.clientIdHeader)
                put("X-YouTube-Client-Version", prof.version)
                put("Origin", "https://www.youtube.com")
                if (!visitorData.isNullOrBlank()) put("X-Goog-Visitor-Id", visitorData!!)
            }

            val resp = runCatching {
                client.fetchJsonObject(
                    url = "$ytApiBase/youtubei/v1/player?prettyPrint=false",
                    method = "POST",
                    headers = hdrs,
                    body = bodyJson.toString().toByteArray(Charsets.UTF_8),
                    includeCookies = false,
                )
            }.getOrNull() ?: continue

            val playStatus = resp.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
            val sd = resp.optJSONObject("streamingData")
            if (playStatus != "OK" && sd == null) {
                resp.optJSONObject("playabilityStatus")?.optString("reason")?.takeIf { it.isNotBlank() }?.let { lastReason = it }
                continue
            }
            if (sd != null && hlsManifest.isNullOrBlank()) {
                sd.optString("hlsManifestUrl").takeIf { it.isNotBlank() }?.let {
                    hlsManifest = it
                    hlsUa = prof.userAgent
                }
            }

            val parsed = ArrayList<UbitFormat>()
            if (sd != null) {
                for (arrKey in listOf("formats", "adaptiveFormats")) {
                    val arr = sd.optJSONArray(arrKey) ?: continue
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONObject(i) ?: continue
                        val furl = f.optString("url")
                        if (furl.isBlank()) continue
                        val mime = f.optString("mimeType", "video/mp4")
                        val isAud = mime.startsWith("audio/")
                        val isWebm = "webm" in mime
                        val ext = if (isWebm) "webm" else if (isAud) "m4a" else "mp4"
                        val codecsStr = firstMatch("codecs=\"([^\"]+)\"", mime).orEmpty()
                        val comma = codecsStr.indexOf(',')
                        val (vcodec, acodec) = when {
                            isAud -> "none" to codecsStr.ifBlank { "mp4a.40.2" }
                            comma >= 0 -> codecsStr.substring(0, comma).trim() to codecsStr.substring(comma + 1).trim()
                            else -> codecsStr.ifBlank { "avc1" } to "none"
                        }
                        val idxObj = f.optJSONObject("indexRange")
                        val indexRange = if (idxObj != null) {
                            UbitByteRange(idxObj.optLong("start", 0L), idxObj.optLong("end", 0L))
                        } else null
                        parsed.add(
                            UbitFormat(
                                id = f.optInt("itag", parsed.size).toString(),
                                url = furl,
                                ext = ext,
                                proto = UbitStreamProto.HTTP,
                                vcodec = vcodec,
                                acodec = acodec,
                                width = f.optInt("width", 0).takeIf { it > 0 },
                                height = f.optInt("height", 0).takeIf { it > 0 },
                                fps = f.optDouble("fps", 0.0).takeIf { it > 0.0 },
                                bitrate = f.optLong("averageBitrate", f.optLong("bitrate", 0L)).takeIf { it > 0L },
                                filesize = f.optString("contentLength").toLongOrNull(),
                                qualityLabel = f.optString("qualityLabel").takeIf { it.isNotBlank() },
                                headers = mapOf("User-Agent" to prof.userAgent),
                                indexRange = indexRange,
                            )
                        )
                    }
                }
            }

            if (parsed.isNotEmpty()) {
                val probe = parsed.firstOrNull { (it.filesize ?: 0L) > 2_000_000L } ?: parsed.first()
                val clen = probe.filesize ?: 0L
                val s = if (clen > 131072L) clen - 4096L else 0L
                val e = if (clen > 131072L) clen - 1L else 2047L
                val playable = runCatching {
                    client.fetchRange(probe.url, s, e, mapOf("User-Agent" to prof.userAgent)).isNotEmpty()
                }.getOrDefault(false)
                if (playable) {
                    chosenResp = resp
                    chosenFmts = parsed
                    break
                }
            } else if (!hlsManifest.isNullOrBlank()) {
                chosenResp = resp
                break
            }
        }

        val metaResp = chosenResp ?: watchIpr
            ?: throw IOException("YouTube player request failed${lastReason?.let { ": $it" }.orEmpty()}")
        val vd = metaResp.optJSONObject("videoDetails") ?: watchIpr?.optJSONObject("videoDetails") ?: JSONObject()
        val duration = vd.optString("lengthSeconds").toDoubleOrNull() ?: 0.0
        val isLive = vd.optBoolean("isLiveContent", false) && (vd.optBoolean("isLive", false) || duration == 0.0)

        val allFormats = ArrayList(chosenFmts)
        if ((isLive || allFormats.isEmpty()) && !hlsManifest.isNullOrBlank()) {
            runCatching {
                allFormats.addAll(UbitHlsEngine.extractFormats(client, hlsManifest!!, mapOf("User-Agent" to hlsUa), "hls"))
            }
        }
        if (allFormats.isEmpty()) {
            throw IOException("YouTube: ${lastReason ?: "No playable non-DRM streams available."}")
        }

        val mf = (watchIpr ?: metaResp).optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        return UbitMediaInfo(
            id = vid,
            title = vd.optString("title").ifBlank { "youtube_$vid" },
            site = "YouTube",
            webpageUrl = "https://www.youtube.com/watch?v=$vid",
            author = vd.optString("author").takeIf { it.isNotBlank() },
            uploadDate = mf?.optString("publishDate")?.take(10)?.takeIf { it.isNotBlank() },
            description = vd.optString("shortDescription").takeIf { it.isNotBlank() },
            duration = duration.takeIf { it > 0.0 },
            thumbnail = "https://i.ytimg.com/vi/$vid/maxresdefault.jpg",
            isLive = isLive,
            formats = allFormats,
            preferredFileStem = "youtube_$vid",
        )
    }

    // MARK: - 2. X / Twitter (Syndication CDN + api.vxtwitter.com Fallback, Video + GIF + Photo)

    private val SYNDICATION_FEATURES = listOf(
        "tfw_timeline_list:", "tfw_follower_count_sunset:true", "tfw_tweet_edit_backend:on",
        "tfw_refsrc_session:on", "tfw_fosnr_soft_interventions_enabled:on", "tfw_show_birdwatch_pivots_enabled:on",
        "tfw_show_business_verified_badge:on", "tfw_duplicate_scribes_to_settings:on",
        "tfw_use_profile_image_shape_enabled:on", "tfw_show_blue_verified_badge:on",
        "tfw_legacy_timeline_sunset:true", "tfw_show_gov_verified_badge:on",
        "tfw_show_business_affiliate_badge:on", "tfw_tweet_edit_frontend:on",
    ).joinToString(";")

    private fun extractTwitter(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val tweetId = LinkResolver.tweetId(u.toString())
            ?: firstMatch("/status(?:es)?/(\\d+)", u.encodedPath)
            ?: return extractGeneric(u.toString(), client, forceSite = "X")

        val token = LinkResolver.syndicationToken(tweetId)
        val syndUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=$token&features=$SYNDICATION_FEATURES&lang=en"
        var tombstoneReason: String? = null

        runCatching {
            val data = client.fetchJsonObject(syndUrl, includeCookies = false)
            if (data != null) {
                if (data.optString("__typename") == "TweetTombstone" || data.has("tombstone")) {
                    tombstoneReason = "That tweet can't be opened without signing in to X (it may be age-restricted or protected)."
                } else {
                    val mediaDetails = data.optJSONArray("mediaDetails")
                    if (mediaDetails != null && mediaDetails.length() > 0) {
                        val formats = ArrayList<UbitFormat>()
                        var firstPhoto: String? = null
                        var duration: Double? = null
                        for (i in 0 until mediaDetails.length()) {
                            val m = mediaDetails.optJSONObject(i) ?: continue
                            val photo = m.optString("media_url_https")
                            if (firstPhoto == null && photo.isNotBlank()) firstPhoto = photo
                            val vinfo = m.optJSONObject("video_info")
                            if (vinfo != null) {
                                if (duration == null && vinfo.has("duration_millis")) {
                                    duration = vinfo.optDouble("duration_millis", 0.0) / 1000.0
                                }
                                val variants = vinfo.optJSONArray("variants")
                                if (variants != null) {
                                    for (vIdx in 0 until variants.length()) {
                                        val v = variants.optJSONObject(vIdx) ?: continue
                                        val vurl = v.optString("url")
                                        val ct = v.optString("content_type")
                                        if (vurl.isBlank()) continue
                                        if (ct.contains("mpegURL", ignoreCase = true) || vurl.contains(".m3u8")) {
                                            runCatching {
                                                formats.addAll(UbitHlsEngine.extractFormats(client, vurl, idPrefix = "hls-$i"))
                                            }
                                        } else if (ct.contains("mp4", ignoreCase = true)) {
                                            val h = firstMatch("/\\d+x(\\d+)/", vurl)?.toIntOrNull()
                                            val w = firstMatch("/(\\d+)x\\d+/", vurl)?.toIntOrNull()
                                            val br = v.optLong("bitrate", 0L)
                                            formats.add(
                                                UbitFormat(
                                                    id = "mp4-${h ?: i}-$br",
                                                    url = vurl,
                                                    ext = "mp4",
                                                    vcodec = "avc1",
                                                    acodec = "mp4a",
                                                    width = w,
                                                    height = h,
                                                    bitrate = br,
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val user = data.optJSONObject("user")
                        val author = user?.optString("screen_name")?.takeIf { it.isNotBlank() }
                        val title = data.optString("text", "tweet_$tweetId")
                            .replace(Regex("\\s*https://t\\.co/\\S+"), "")
                            .trim()
                            .ifBlank { "tweet_$tweetId" }
                        if (formats.isNotEmpty() || firstPhoto != null) {
                            return UbitMediaInfo(
                                id = tweetId,
                                title = title.take(100),
                                site = "X",
                                webpageUrl = "https://x.com/i/status/$tweetId",
                                author = author,
                                duration = duration,
                                thumbnail = firstPhoto,
                                formats = formats,
                                imageFallbackUrl = if (formats.isEmpty() && firstPhoto != null) "$firstPhoto?name=large" else null,
                                imageFallbackMime = "image/jpeg",
                                preferredFileStem = "tweet_$tweetId",
                            )
                        }
                    }
                }
            }
        }

        // Fallback: api.vxtwitter.com handles NSFW/age-gated tweets & multi-video carousels cleanly.
        runCatching {
            val vx = client.fetchJsonObject("https://api.vxtwitter.com/Twitter/status/$tweetId", includeCookies = false)
            val extMedia = vx?.optJSONArray("media_extended")
            if (extMedia != null && extMedia.length() > 0) {
                val formats = ArrayList<UbitFormat>()
                var firstPhoto: String? = null
                var thumb: String? = null
                var duration: Double? = null
                for (i in 0 until extMedia.length()) {
                    val m = extMedia.optJSONObject(i) ?: continue
                    val type = m.optString("type")
                    val murl = m.optString("url")
                    if (murl.isBlank()) continue
                    if (type == "video" || type == "gif") {
                        if (thumb == null) thumb = m.optString("thumbnail_url").takeIf { it.isNotBlank() }
                        if (duration == null && m.has("duration_millis")) {
                            duration = m.optDouble("duration_millis", 0.0) / 1000.0
                        }
                        val sz = m.optJSONObject("size")
                        formats.add(
                            UbitFormat(
                                id = "mp4-$i",
                                url = murl,
                                ext = "mp4",
                                vcodec = "avc1",
                                acodec = "mp4a",
                                width = sz?.optInt("width", 0)?.takeIf { it > 0 },
                                height = sz?.optInt("height", 0)?.takeIf { it > 0 },
                            )
                        )
                    } else if (type == "image" && firstPhoto == null) {
                        firstPhoto = murl
                    }
                }
                if (formats.isNotEmpty() || firstPhoto != null) {
                    val txt = vx.optString("text", "tweet_$tweetId").trim().ifBlank { "tweet_$tweetId" }
                    return UbitMediaInfo(
                        id = tweetId,
                        title = txt.take(100),
                        site = "X",
                        webpageUrl = "https://x.com/i/status/$tweetId",
                        author = vx.optString("user_screen_name").takeIf { it.isNotBlank() },
                        duration = duration,
                        thumbnail = thumb ?: firstPhoto,
                        formats = formats,
                        imageFallbackUrl = if (formats.isEmpty()) firstPhoto else null,
                        imageFallbackMime = "image/jpeg",
                        preferredFileStem = "tweet_$tweetId",
                    )
                }
            }
        }

        throw IOException(tombstoneReason ?: "That tweet has no image or video to save.")
    }

    // MARK: - 3. Reddit (v.redd.it HLS + DASH + JSON API + Crossposts + Images + Embed Fallback)

    private fun extractReddit(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        if (u.host.equals("v.redd.it", ignoreCase = true)) {
            val vid = u.pathSegments.firstOrNull().orEmpty().ifBlank { "reddit" }
            val hlsUrl = "https://v.redd.it/$vid/HLSPlaylist.m3u8"
            val fmts = runCatching { UbitHlsEngine.extractFormats(client, hlsUrl) }.getOrDefault(emptyList())
            if (fmts.isNotEmpty()) {
                return UbitMediaInfo(
                    id = vid,
                    title = "Reddit $vid",
                    site = "Reddit",
                    webpageUrl = u.toString(),
                    formats = fmts,
                    preferredFileStem = "reddit_$vid",
                )
            }
        }

        val resolvedStr = runCatching { client.fetchText(u.toString()).second }.getOrDefault(u.toString())
        val resolved = resolvedStr.toHttpUrlOrNull() ?: u
        val cleanPath = resolved.encodedPath.trimEnd('/')
        val origin = if (resolved.host == "127.0.0.1" || resolved.host == "localhost") {
            "${resolved.scheme}://${resolved.host}:${resolved.port}"
        } else {
            "https://www.reddit.com"
        }

        var dashReps: List<UbitDashRepresentation> = emptyList()
        runCatching {
            val arr = client.fetchJsonArray("$origin$cleanPath.json?raw_json=1", headers = mapOf("Accept" to "application/json"))
            val children = arr?.optJSONObject(0)?.optJSONObject("data")?.optJSONArray("children")
            var post = children?.optJSONObject(0)?.optJSONObject("data")
            val cross = post?.optJSONArray("crosspost_parent_list")?.optJSONObject(0)
            if (cross != null) post = cross

            if (post != null) {
                val id = post.optString("id").ifBlank { "reddit" }
                val title = post.optString("title").ifBlank { "Reddit $id" }
                val author = post.optString("author").takeIf { it.isNotBlank() }
                val thumb = post.optString("thumbnail").takeIf { it.startsWith("http") }

                val rv = post.optJSONObject("secure_media")?.optJSONObject("reddit_video")
                    ?: post.optJSONObject("media")?.optJSONObject("reddit_video")
                    ?: post.optJSONObject("preview")?.optJSONObject("reddit_video_preview")

                val formats = ArrayList<UbitFormat>()
                if (rv != null) {
                    rv.optString("hls_url").takeIf { it.isNotBlank() }?.let { hls ->
                        runCatching { formats.addAll(UbitHlsEngine.extractFormats(client, hls)) }
                    }
                    rv.optString("dash_url").takeIf { it.isNotBlank() }?.let { dash ->
                        runCatching {
                            val (df, dr) = UbitDashEngine.extractFormats(client, dash)
                            formats.addAll(df)
                            dashReps = dr
                        }
                    }
                    rv.optString("fallback_url").takeIf { it.isNotBlank() }?.let { fb ->
                        formats.add(
                            UbitFormat(
                                id = "fallback",
                                url = fb,
                                ext = "mp4",
                                vcodec = "avc1",
                                acodec = "none",
                                height = rv.optInt("height", 0).takeIf { it > 0 },
                            )
                        )
                    }
                    if (formats.isNotEmpty()) {
                        return UbitMediaInfo(
                            id = id,
                            title = title,
                            site = "Reddit",
                            webpageUrl = resolved.toString(),
                            author = author,
                            duration = rv.optDouble("duration", 0.0).takeIf { it > 0.0 },
                            thumbnail = thumb,
                            formats = formats,
                            dashRepresentations = dashReps,
                            preferredFileStem = "reddit_$id",
                        )
                    }
                }

                val dest = post.optString("url_overridden_by_dest")
                if (dest.startsWith("http") && dest != resolved.toString()) {
                    val destLower = dest.lowercase(Locale.ROOT)
                    if (DIRECT_IMAGE_EXTS.any { destLower.substringBefore('?').endsWith(".$it") }) {
                        return UbitMediaInfo(
                            id = id,
                            title = title,
                            site = "Reddit",
                            webpageUrl = resolved.toString(),
                            imageFallbackUrl = dest,
                            preferredFileStem = "reddit_$id",
                        )
                    }
                    return extract(dest, client)
                }
            }
        }

        // Embed HTML fallback when Reddit API returns 403
        val embedBase = if (resolved.host == "127.0.0.1" || resolved.host == "localhost") origin else "https://embed.reddit.com"
        val (embedHtml, _) = client.fetchText("$embedBase$cleanPath")
        val hlsMatch = firstMatch("(https?://[^\"'\\s]+HLSPlaylist\\.m3u8[^\"'\\s]*)", embedHtml)
        if (hlsMatch != null) {
            val cleanHls = unescapeJsString(hlsMatch)
            val fmts = UbitHlsEngine.extractFormats(client, cleanHls)
            return UbitMediaInfo(
                id = cleanPath.substringAfterLast('/'),
                title = pageTitle(embedHtml) ?: "Reddit Video",
                site = "Reddit",
                webpageUrl = resolved.toString(),
                formats = fmts,
            )
        }
        throw IOException("This Reddit post has no downloadable video or image.")
    }

    // MARK: - 4. TikTok (Shortlinks + Rehydration JSON + Referer/Cookies)

    private fun extractTikTok(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val (html, finalUrlStr) = client.fetchText(u.toString())
        val effectiveUrl = if (finalUrlStr.contains("127.0.0.1") || finalUrlStr.contains("localhost")) u else (finalUrlStr.toHttpUrlOrNull() ?: u)
        val vidId = firstMatch("/video/(\\d+)", effectiveUrl.encodedPath)
            ?: firstMatch("/video/(\\d+)", finalUrlStr)
            ?: kotlin.math.abs(effectiveUrl.encodedPath.hashCode()).toString()

        val rehyd = (extractJsonAfter("__UNIVERSAL_DATA_FOR_REHYDRATION__ =", html) as? JSONObject)
            ?: firstMatch("<script[^>]+id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>([\\s\\S]*?)</script>", html)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }

        val item = rehyd
            ?.optJSONObject("__DEFAULT_SCOPE__")
            ?.optJSONObject("webapp.video-detail")
            ?.optJSONObject("itemInfo")
            ?.optJSONObject("itemStruct")
        val video = item?.optJSONObject("video")
        if (video != null) {
            val formats = ArrayList<UbitFormat>()
            val headers = LinkedHashMap<String, String>().apply {
                put("Referer", "https://www.tiktok.com/")
                (client.cookieHeaderFor(u.toString()) ?: client.allCookiesHeader())?.let { put("Cookie", it) }
            }
            for (key in listOf("playAddr", "downloadAddr")) {
                val addr = video.optString(key)
                if (addr.startsWith("http")) {
                    formats.add(
                        UbitFormat(
                            id = key,
                            url = unescapeJsString(addr),
                            ext = "mp4",
                            vcodec = "avc1",
                            acodec = "mp4a",
                            width = video.optInt("width", 0).takeIf { it > 0 },
                            height = video.optInt("height", 0).takeIf { it > 0 },
                            headers = headers,
                        )
                    )
                }
            }
            // Also check bitrateInfo PlayAddr UrlList
            val brArr = video.optJSONArray("bitrateInfo")
            if (brArr != null) {
                for (i in 0 until brArr.length()) {
                    val bi = brArr.optJSONObject(i) ?: continue
                    val playObj = bi.optJSONObject("PlayAddr") ?: continue
                    val uList = playObj.optJSONArray("UrlList") ?: continue
                    val firstU = uList.optString(0)
                    if (firstU.startsWith("http")) {
                        formats.add(
                            UbitFormat(
                                id = "br-$i",
                                url = unescapeJsString(firstU),
                                ext = "mp4",
                                vcodec = if (bi.optString("CodecType").contains("h265", true)) "hvc1" else "avc1",
                                acodec = "mp4a",
                                width = playObj.optInt("Width", 0).takeIf { it > 0 },
                                height = playObj.optInt("Height", 0).takeIf { it > 0 },
                                bitrate = bi.optLong("Bitrate", 0L).takeIf { it > 0L },
                                headers = headers,
                            )
                        )
                    }
                }
            }
            if (formats.isNotEmpty()) {
                val desc = item.optString("desc").ifBlank { "TikTok $vidId" }
                return UbitMediaInfo(
                    id = vidId,
                    title = desc.take(100),
                    site = "TikTok",
                    webpageUrl = finalUrlStr,
                    author = item.optJSONObject("author")?.optString("uniqueId")?.takeIf { it.isNotBlank() },
                    duration = video.optDouble("duration", 0.0).takeIf { it > 0.0 },
                    thumbnail = video.optString("cover").takeIf { it.isNotBlank() },
                    formats = formats,
                    preferredFileStem = "tiktok_$vidId",
                )
            }
        }
        return extractGeneric(finalUrlStr, client, forceSite = "TikTok", preloadedHtml = html)
    }

    // MARK: - 5. Instagram & Facebook

    private fun extractInstagram(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val shortcode = firstMatch("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)", u.encodedPath)
            ?: return extractGeneric(u.toString(), client, forceSite = "Instagram")
        val origin = if (u.host == "127.0.0.1" || u.host == "localhost") "${u.scheme}://${u.host}:${u.port}" else "https://www.instagram.com"
        val embedUrl = "$origin/p/$shortcode/embed/captioned/"
        val html = runCatching { client.fetchText(embedUrl).first }.getOrDefault("")
        val formats = ArrayList<UbitFormat>()
        for (raw in allMatches("\"video_url\"\\s*:\\s*\"([^\"]+)\"", html)) {
            val clean = unescapeJsString(raw)
            if (clean.startsWith("http")) {
                formats.add(UbitFormat(id = "ig-${formats.size}", url = clean, ext = "mp4", vcodec = "avc1", acodec = "mp4a"))
            }
        }
        if (formats.isNotEmpty()) {
            return UbitMediaInfo(
                id = shortcode,
                title = pageTitle(html) ?: "Instagram $shortcode",
                site = "Instagram",
                webpageUrl = u.toString(),
                thumbnail = LinkResolver.metaContent(html, "og:image"),
                formats = formats,
                preferredFileStem = "ig_$shortcode",
            )
        }
        return extractGeneric(u.toString(), client, forceSite = "Instagram")
    }

    private fun extractFacebook(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val (html, finalUrl) = client.fetchText(
            u.toString(),
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Sec-Fetch-Mode" to "navigate",
            ),
        )
        val formats = ArrayList<UbitFormat>()
        val keys = listOf(
            Triple("browser_native_hd_url", "HD", 720),
            Triple("playable_url_quality_hd", "HD", 720),
            Triple("browser_native_sd_url", "SD", 480),
            Triple("playable_url", "SD", 480),
        )
        for ((key, label, h) in keys) {
            val m = firstMatch("\"$key\"\\s*:\\s*\"([^\"]+)\"", html) ?: continue
            val clean = unescapeJsString(m)
            if (clean.startsWith("http") && formats.none { it.url == clean }) {
                formats.add(UbitFormat(id = "${label.lowercase(Locale.ROOT)}-${formats.size}", url = clean, ext = "mp4", vcodec = "avc1", acodec = "mp4a", height = h, qualityLabel = label))
            }
        }
        if (formats.isNotEmpty()) {
            return UbitMediaInfo(
                id = kotlin.math.abs(finalUrl.hashCode()).toString(),
                title = pageTitle(html) ?: "Facebook Video",
                site = "Facebook",
                webpageUrl = finalUrl,
                thumbnail = LinkResolver.metaContent(html, "og:image"),
                formats = formats,
            )
        }
        return extractGeneric(finalUrl, client, forceSite = "Facebook", preloadedHtml = html)
    }

    // MARK: - 6. Bluesky (ATProto XRPC)

    private fun extractBluesky(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val handle = firstMatch("/profile/([^/]+)/post/", u.encodedPath)
        val rkey = firstMatch("/post/([A-Za-z0-9]+)", u.encodedPath)
        if (handle == null || rkey == null) throw IOException("Not a Bluesky post URL.")

        val atUri = URLEncoder.encode("at://$handle/app.bsky.feed.post/$rkey", "UTF-8")
        val apiBase = if (u.host == "127.0.0.1" || u.host == "localhost") "${u.scheme}://${u.host}:${u.port}" else "https://public.api.bsky.app"
        val root = client.fetchJsonObject("$apiBase/xrpc/app.bsky.feed.getPostThread?depth=0&parentHeight=0&uri=$atUri", includeCookies = false)
        val post = root?.optJSONObject("thread")?.optJSONObject("post")
            ?: throw IOException("Bluesky post unavailable.")
        val text = post.optJSONObject("record")?.optString("text", "Bluesky $rkey")?.trim().orEmpty()
        val author = post.optJSONObject("author")?.optString("handle")?.ifBlank { handle } ?: handle
        val embed = post.optJSONObject("embed") ?: JSONObject()
        val media = embed.optJSONObject("media") ?: embed

        val playlist = media.optString("playlist")
        if (playlist.isNotBlank()) {
            val fmts = UbitHlsEngine.extractFormats(client, playlist)
            return UbitMediaInfo(
                id = rkey,
                title = text.ifBlank { "Bluesky $rkey" }.take(100),
                site = "Bluesky",
                webpageUrl = u.toString(),
                author = author,
                thumbnail = media.optString("thumbnail").takeIf { it.isNotBlank() },
                formats = fmts,
                preferredFileStem = "bsky_$rkey",
            )
        }

        val firstImage = media.optJSONArray("images")?.optJSONObject(0)?.optString("fullsize")?.takeIf { it.isNotBlank() }
        if (firstImage != null) {
            return UbitMediaInfo(
                id = rkey,
                title = text.ifBlank { "Bluesky $rkey" }.take(100),
                site = "Bluesky",
                webpageUrl = u.toString(),
                author = author,
                imageFallbackUrl = firstImage,
                preferredFileStem = "bsky_$rkey",
            )
        }
        throw IOException("This Bluesky post has no video or image.")
    }

    // MARK: - 7. Twitch (Clips + VODs via GQL) & Kick (Clips + Videos)

    private const val TWITCH_GQL_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

    private fun extractTwitch(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val gqlUrl = if (u.host == "127.0.0.1" || u.host == "localhost") "${u.scheme}://${u.host}:${u.port}/gql" else "https://gql.twitch.tv/gql"
        if (u.host.equals("clips.twitch.tv", ignoreCase = true) || u.encodedPath.contains("/clip/")) {
            val slug = u.pathSegments.lastOrNull { it.isNotBlank() }.orEmpty()
            val reqArr = JSONArray().put(
                JSONObject()
                    .put("operationName", "VideoAccessToken_Clip")
                    .put("variables", JSONObject().put("slug", slug))
                    .put(
                        "extensions",
                        JSONObject().put(
                            "persistedQuery",
                            JSONObject().put("version", 1).put("sha256Hash", "36b89d2507fce29e5ca551df756d27c1cfe079e2609642b4390aa4c35796eb11")
                        )
                    )
            )
            val arr = client.fetchJsonArray(
                url = gqlUrl,
                method = "POST",
                headers = mapOf("Client-ID" to TWITCH_GQL_CLIENT_ID, "Content-Type" to "application/json"),
                body = reqArr.toString().toByteArray(Charsets.UTF_8),
            )
            val clip = arr?.optJSONObject(0)?.optJSONObject("data")?.optJSONObject("clip")
                ?: throw IOException("Twitch clip not found.")
            val pb = clip.optJSONObject("playbackAccessToken") ?: throw IOException("Twitch clip token missing.")
            val sig = pb.optString("signature")
            val tok = URLEncoder.encode(pb.optString("value"), "UTF-8")
            val formats = ArrayList<UbitFormat>()
            val quals = clip.optJSONArray("videoQualities")
            if (quals != null) {
                for (i in 0 until quals.length()) {
                    val q = quals.optJSONObject(i) ?: continue
                    val src = q.optString("sourceURL")
                    if (src.isBlank()) continue
                    val qual = q.optString("quality", "720")
                    formats.add(
                        UbitFormat(
                            id = "clip-${qual}p",
                            url = "$src?sig=$sig&token=$tok",
                            ext = "mp4",
                            vcodec = "avc1",
                            acodec = "mp4a",
                            height = qual.toIntOrNull(),
                            fps = q.optDouble("frameRate", 0.0).takeIf { it > 0.0 },
                        )
                    )
                }
            }
            if (formats.isEmpty()) throw IOException("Twitch clip had no MP4 streams.")
            return UbitMediaInfo(
                id = slug,
                title = clip.optString("title").ifBlank { slug },
                site = "Twitch",
                webpageUrl = u.toString(),
                author = clip.optJSONObject("broadcaster")?.optString("displayName"),
                duration = clip.optDouble("durationSeconds", 0.0).takeIf { it > 0.0 },
                thumbnail = clip.optString("thumbnailURL").takeIf { it.isNotBlank() },
                formats = formats,
                preferredFileStem = "twitch_$slug",
            )
        }

        val vodId = firstMatch("/videos/(\\d+)", u.encodedPath)
            ?: throw IOException("Share a Twitch clip or VOD link to download it into Memeget.")
        val query = JSONObject().put(
            "query",
            "{ video(id: \"$vodId\") { title lengthSeconds previewThumbnailURL(width:1280,height:720) owner { displayName } } playbackAccessToken( params: { platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\" }, vodID: \"$vodId\" ) { value signature } }"
        )
        val resp = client.fetchJsonObject(
            url = gqlUrl,
            method = "POST",
            headers = mapOf("Client-ID" to TWITCH_GQL_CLIENT_ID, "Content-Type" to "application/json"),
            body = query.toString().toByteArray(Charsets.UTF_8),
        )
        val dataObj = resp?.optJSONObject("data") ?: throw IOException("Twitch VOD $vodId unavailable.")
        val pb = dataObj.optJSONObject("playbackAccessToken") ?: throw IOException("Twitch VOD $vodId unavailable.")
        val tok = URLEncoder.encode(pb.optString("value"), "UTF-8")
        val sig = pb.optString("signature")
        val m3u8 = "https://usher.ttvnw.net/vod/$vodId.m3u8?nauth=$tok&nauthsig=$sig&allow_source=true&allow_audio_only=true"
        val fmts = UbitHlsEngine.extractFormats(client, m3u8)
        val vid = dataObj.optJSONObject("video") ?: JSONObject()
        return UbitMediaInfo(
            id = vodId,
            title = vid.optString("title").ifBlank { "Twitch VOD $vodId" },
            site = "Twitch",
            webpageUrl = u.toString(),
            author = vid.optJSONObject("owner")?.optString("displayName"),
            duration = vid.optDouble("lengthSeconds", 0.0).takeIf { it > 0.0 },
            thumbnail = vid.optString("previewThumbnailURL").takeIf { it.isNotBlank() },
            formats = fmts,
            preferredFileStem = "twitch_$vodId",
        )
    }

    private fun extractKick(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val base = if (u.host == "127.0.0.1" || u.host == "localhost") "${u.scheme}://${u.host}:${u.port}" else "https://kick.com"
        val clipId = u.queryParameter("clip") ?: firstMatch("/clips/([A-Za-z0-9_]+)", u.encodedPath)
        if (!clipId.isNullOrBlank()) {
            val clip = client.fetchJsonObject("$base/api/v2/clips/$clipId")?.optJSONObject("clip")
                ?: throw IOException("Kick clip unavailable.")
            val curl = clip.optString("clip_url")
            if (curl.isBlank()) throw IOException("Kick clip stream missing.")
            val fmts = if (curl.contains(".m3u8")) {
                UbitHlsEngine.extractFormats(client, curl)
            } else {
                listOf(UbitFormat(id = "mp4", url = curl, ext = "mp4", vcodec = "avc1", acodec = "mp4a"))
            }
            return UbitMediaInfo(
                id = clipId,
                title = clip.optString("title").ifBlank { "Kick $clipId" },
                site = "Kick",
                webpageUrl = u.toString(),
                thumbnail = clip.optString("thumbnail_url").takeIf { it.isNotBlank() },
                formats = fmts,
                preferredFileStem = "kick_$clipId",
            )
        }
        val vidUuid = firstMatch("/videos?/([0-9a-fA-F-]{36})", u.encodedPath)
            ?: throw IOException("Share a Kick clip or video link to download it into Memeget.")
        val obj = client.fetchJsonObject("$base/api/v1/video/$vidUuid") ?: throw IOException("Kick video unavailable.")
        val src = obj.optString("source")
        if (src.isBlank()) throw IOException("Kick video stream missing.")
        val fmts = UbitHlsEngine.extractFormats(client, src)
        return UbitMediaInfo(
            id = vidUuid,
            title = obj.optJSONObject("livestream")?.optString("session_title")?.ifBlank { "Kick Video" } ?: "Kick Video",
            site = "Kick",
            webpageUrl = u.toString(),
            formats = fmts,
            preferredFileStem = "kick_${vidUuid.take(8)}",
        )
    }

    // MARK: - 8. Streamable, Vimeo, Bilibili, Dailymotion, Rumble, Odysee, PeerTube, Internet Archive, Loom, TED

    private fun extractStreamable(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val code = u.pathSegments.lastOrNull { it.isNotBlank() } ?: throw IOException("Invalid Streamable link.")
        val base = if (u.host == "127.0.0.1" || u.host == "localhost") "${u.scheme}://${u.host}:${u.port}" else "https://api.streamable.com"
        val obj = client.fetchJsonObject("$base/videos/$code") ?: throw IOException("Streamable video unavailable.")
        val files = obj.optJSONObject("files") ?: throw IOException("Streamable files missing.")
        val formats = ArrayList<UbitFormat>()
        for (key in files.keys()) {
            val f = files.optJSONObject(key) ?: continue
            var furl = f.optString("url")
            if (furl.startsWith("//")) furl = "https:$furl"
            if (furl.startsWith("http")) {
                formats.add(
                    UbitFormat(
                        id = key,
                        url = furl,
                        ext = "mp4",
                        vcodec = "avc1",
                        acodec = "mp4a",
                        width = f.optInt("width", 0).takeIf { it > 0 },
                        height = f.optInt("height", 0).takeIf { it > 0 },
                        bitrate = f.optLong("bitrate", 0L).takeIf { it > 0L },
                        filesize = f.optLong("size", 0L).takeIf { it > 0L },
                    )
                )
            }
        }
        if (formats.isEmpty()) throw IOException("No playable Streamable files found.")
        return UbitMediaInfo(
            id = code,
            title = obj.optString("title").ifBlank { "Streamable $code" },
            site = "Streamable",
            webpageUrl = u.toString(),
            duration = obj.optDouble("duration", 0.0).takeIf { it > 0.0 },
            formats = formats,
            preferredFileStem = "streamable_$code",
        )
    }

    private fun extractVimeo(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val videoId = firstMatch("(?:video/|vimeo\\.com/)(?:channels/[^/]+/|groups/[^/]+/videos/)?(\\d{6,12})", u.toString())
            ?: throw IOException("Not a Vimeo video link.")
        val hash = firstMatch("/$videoId/([0-9a-fA-F]{6,})", u.encodedPath) ?: u.queryParameter("h")
        var cfgUrl = "https://player.vimeo.com/video/$videoId/config"
        if (!hash.isNullOrBlank()) cfgUrl += "?h=$hash"
        val headers = mapOf("Referer" to "https://vimeo.com/")
        val cfg = client.fetchJsonObject(cfgUrl, headers = headers) ?: throw IOException("Could not read Vimeo config.")
        val videoObj = cfg.optJSONObject("video") ?: JSONObject()
        val files = cfg.optJSONObject("request")?.optJSONObject("files") ?: JSONObject()
        val formats = ArrayList<UbitFormat>()
        var sawDrm = false

        val prog = files.optJSONArray("progressive")
        if (prog != null) {
            for (i in 0 until prog.length()) {
                val p = prog.optJSONObject(i) ?: continue
                val purl = p.optString("url")
                if (purl.startsWith("http")) {
                    formats.add(
                        UbitFormat(
                            id = "http-${p.optString("quality", i.toString())}",
                            url = purl,
                            ext = "mp4",
                            vcodec = "avc1",
                            acodec = "mp4a",
                            width = p.optInt("width", 0).takeIf { it > 0 },
                            height = p.optInt("height", 0).takeIf { it > 0 },
                            fps = p.optDouble("fps", 0.0).takeIf { it > 0.0 },
                            headers = headers,
                        )
                    )
                }
            }
        }
        val cdns = files.optJSONObject("hls")?.optJSONObject("cdns")
        if (cdns != null) {
            for (k in cdns.keys()) {
                val cdn = cdns.optJSONObject(k) ?: continue
                val m3u8 = cdn.optString("url").ifBlank { cdn.optString("avc_url") }
                if (m3u8.contains("/drm/")) {
                    sawDrm = true
                    continue
                }
                if (m3u8.startsWith("http")) {
                    val hf = runCatching { UbitHlsEngine.extractFormats(client, m3u8, headers) }.getOrDefault(emptyList())
                    if (hf.isNotEmpty()) {
                        formats.addAll(hf)
                        break
                    }
                }
            }
        }
        if (formats.isEmpty() && sawDrm) {
            return UbitMediaInfo(id = videoId, title = videoObj.optString("title", "Vimeo $videoId"), site = "Vimeo", webpageUrl = u.toString(), isDrm = true)
        }
        if (formats.isEmpty()) throw IOException("No non-DRM streams available for Vimeo $videoId.")
        return UbitMediaInfo(
            id = videoId,
            title = videoObj.optString("title").ifBlank { "Vimeo $videoId" },
            site = "Vimeo",
            webpageUrl = u.toString(),
            author = videoObj.optJSONObject("owner")?.optString("name"),
            duration = videoObj.optDouble("duration", 0.0).takeIf { it > 0.0 },
            formats = formats,
            preferredFileStem = "vimeo_$videoId",
        )
    }

    private fun extractBilibili(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val (_, finalUrlStr) = client.fetchText(u.toString())
        val bvid = firstMatch("(BV[0-9A-Za-z]{10})", finalUrlStr)
            ?: throw IOException("Could not find Bilibili BV ID.")
        val bh = mapOf("Referer" to "https://www.bilibili.com/")
        val view = client.fetchJsonObject("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", headers = bh)?.optJSONObject("data")
            ?: throw IOException("Bilibili video metadata unavailable.")
        val cid = view.optLong("cid", 0L)
        val pd = client.fetchJsonObject(
            "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=80&fnval=4048&fourk=1",
            headers = bh,
        )?.optJSONObject("data") ?: throw IOException("Bilibili playurl unavailable.")

        val formats = ArrayList<UbitFormat>()
        val dash = pd.optJSONObject("dash")
        val vArr = dash?.optJSONArray("video")
        if (vArr != null) {
            for (i in 0 until vArr.length()) {
                val vf = vArr.optJSONObject(i) ?: continue
                val bu = vf.optString("baseUrl").ifBlank { vf.optString("base_url") }
                if (bu.startsWith("http")) {
                    formats.add(
                        UbitFormat(
                            id = "v-${vf.optInt("id", i)}-$i",
                            url = bu,
                            ext = "mp4",
                            vcodec = vf.optString("codecs", "avc1"),
                            acodec = "none",
                            width = vf.optInt("width", 0).takeIf { it > 0 },
                            height = vf.optInt("height", 0).takeIf { it > 0 },
                            bitrate = vf.optLong("bandwidth", 0L).takeIf { it > 0L },
                            headers = bh,
                        )
                    )
                }
            }
        }
        val aArr = dash?.optJSONArray("audio")
        if (aArr != null) {
            for (i in 0 until aArr.length()) {
                val af = aArr.optJSONObject(i) ?: continue
                val bu = af.optString("baseUrl").ifBlank { af.optString("base_url") }
                if (bu.startsWith("http")) {
                    formats.add(
                        UbitFormat(
                            id = "a-${af.optInt("id", i)}-$i",
                            url = bu,
                            ext = "m4a",
                            vcodec = "none",
                            acodec = "mp4a.40.2",
                            bitrate = af.optLong("bandwidth", 0L).takeIf { it > 0L },
                            headers = bh,
                        )
                    )
                }
            }
        }
        if (formats.isEmpty()) throw IOException("No playable Bilibili streams found.")
        return UbitMediaInfo(
            id = bvid,
            title = view.optString("title").ifBlank { bvid },
            site = "Bilibili",
            webpageUrl = finalUrlStr,
            author = view.optJSONObject("owner")?.optString("name"),
            duration = view.optDouble("duration", 0.0).takeIf { it > 0.0 },
            formats = formats,
            preferredFileStem = "bilibili_$bvid",
        )
    }

    private fun extractDailymotion(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val vid = if (u.host.equals("dai.ly", ignoreCase = true)) {
            u.pathSegments.firstOrNull().orEmpty()
        } else {
            firstMatch("/video/([a-zA-Z0-9]+)", u.encodedPath).orEmpty()
        }
        if (vid.isBlank()) throw IOException("No Dailymotion video ID found.")
        val md = client.fetchJsonObject("https://www.dailymotion.com/player/metadata/video/$vid")
            ?: throw IOException("Dailymotion metadata unavailable.")
        val m3u8 = md.optJSONObject("qualities")?.optJSONArray("auto")?.optJSONObject(0)?.optString("url").orEmpty()
        if (m3u8.isBlank()) throw IOException("No playable Dailymotion stream.")
        return UbitMediaInfo(
            id = vid,
            title = md.optString("title").ifBlank { vid },
            site = "Dailymotion",
            webpageUrl = u.toString(),
            duration = md.optDouble("duration", 0.0).takeIf { it > 0.0 },
            isLive = md.optBoolean("is_live", false),
            formats = UbitHlsEngine.extractFormats(client, m3u8),
            preferredFileStem = "dailymotion_$vid",
        )
    }

    private fun extractRumble(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val (html, finalUrl) = client.fetchText(u.toString())
        val vid = firstMatch("/embed/(v[a-z0-9]+)", html)
            ?: firstMatch("\"video\"\\s*:\\s*\"(v[a-z0-9]+)\"", html)
            ?: return extractGeneric(finalUrl, client, forceSite = "Rumble", preloadedHtml = html)
        val obj = client.fetchJsonObject("https://rumble.com/embedJS/u3/?request=video&ver=2&v=$vid")
            ?: throw IOException("Rumble embed API failed.")
        val formats = ArrayList<UbitFormat>()
        val ua = obj.optJSONObject("ua")
        if (ua != null) {
            for (kind in ua.keys()) {
                val kindDict = ua.optJSONObject(kind) ?: continue
                for (resKey in kindDict.keys()) {
                    val item = kindDict.optJSONObject(resKey) ?: continue
                    val surl = item.optString("url")
                    if (!surl.startsWith("http")) continue
                    if (kind == "hls" || surl.contains(".m3u8")) {
                        runCatching { formats.addAll(UbitHlsEngine.extractFormats(client, surl)) }
                    } else {
                        formats.add(UbitFormat(id = "$kind-$resKey", url = surl, ext = "mp4", vcodec = "avc1", acodec = "mp4a", height = resKey.toIntOrNull()))
                    }
                }
            }
        }
        if (formats.isEmpty()) return extractGeneric(finalUrl, client, forceSite = "Rumble", preloadedHtml = html)
        return UbitMediaInfo(
            id = vid,
            title = obj.optString("title").ifBlank { vid },
            site = "Rumble",
            webpageUrl = finalUrl,
            duration = obj.optDouble("duration", 0.0).takeIf { it > 0.0 },
            formats = formats,
            preferredFileStem = "rumble_$vid",
        )
    }

    private fun extractOdysee(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val lbryUri = "lbry://" + u.encodedPath.trim('/').replace(":", "#")
        val rpc = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "resolve")
            .put("params", JSONObject().put("urls", JSONArray().put(lbryUri)))
        val resMap = client.fetchJsonObject(
            url = "https://api.na-backend.odysee.com/api/v1/proxy?m=resolve",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = rpc.toString().toByteArray(Charsets.UTF_8),
        )?.optJSONObject("result")
        val firstKey = resMap?.keys()?.asSequence()?.firstOrNull()
        val claim = firstKey?.let { resMap.optJSONObject(it) }
        val claimId = claim?.optString("claim_id").orEmpty()
        val name = claim?.optString("name").orEmpty()
        if (claimId.isBlank() || name.isBlank()) return extractGeneric(u.toString(), client, forceSite = "Odysee")

        val streamUrl = "https://player.odycdn.com/api/v3/streams/free/$name/$claimId/stream.mp4"
        val hlsUrl = "https://player.odycdn.com/v6/streams/$claimId/master.m3u8"
        val formats = ArrayList<UbitFormat>()
        runCatching { formats.addAll(UbitHlsEngine.extractFormats(client, hlsUrl)) }
        formats.add(UbitFormat(id = "direct-mp4", url = streamUrl, ext = "mp4", vcodec = "avc1", acodec = "mp4a"))
        return UbitMediaInfo(
            id = claimId,
            title = claim?.optJSONObject("value")?.optString("title")?.ifBlank { name } ?: name,
            site = "Odysee",
            webpageUrl = u.toString(),
            formats = formats,
            preferredFileStem = "odysee_${claimId.take(10)}",
        )
    }

    private fun extractPeerTube(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val uuid = u.pathSegments.lastOrNull { it.isNotBlank() } ?: throw IOException("Invalid PeerTube link.")
        val obj = runCatching {
            client.fetchJsonObject("${u.scheme}://${u.host}${if (u.port != 80 && u.port != 443) ":${u.port}" else ""}/api/v1/videos/$uuid")
        }.getOrNull() ?: return extractGeneric(u.toString(), client, forceSite = "PeerTube")

        val formats = ArrayList<UbitFormat>()
        val files = obj.optJSONArray("files")
        if (files != null) {
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val furl = f.optString("fileUrl")
                if (furl.startsWith("http")) {
                    val h = f.optJSONObject("resolution")?.optInt("id", 0)?.takeIf { it > 0 }
                    formats.add(UbitFormat(id = "pt-${h ?: i}", url = furl, ext = "mp4", vcodec = "avc1", acodec = "mp4a", height = h))
                }
            }
        }
        val sps = obj.optJSONArray("streamingPlaylists")
        if (sps != null) {
            for (i in 0 until sps.length()) {
                val m3u8 = sps.optJSONObject(i)?.optString("playlistUrl").orEmpty()
                if (m3u8.startsWith("http")) {
                    runCatching { formats.addAll(UbitHlsEngine.extractFormats(client, m3u8)) }
                }
            }
        }
        if (formats.isEmpty()) return extractGeneric(u.toString(), client, forceSite = "PeerTube")
        return UbitMediaInfo(
            id = uuid,
            title = obj.optString("name").ifBlank { uuid },
            site = "PeerTube",
            webpageUrl = u.toString(),
            formats = formats,
            preferredFileStem = "peertube_${uuid.take(10)}",
        )
    }

    private fun extractInternetArchive(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val itemId = firstMatch("/(?:details|download|embed)/([^/?#]+)", u.encodedPath)
            ?: throw IOException("Not an Internet Archive item URL.")
        val metaRoot = client.fetchJsonObject("https://archive.org/metadata/$itemId")
            ?: throw IOException("Internet Archive metadata unavailable.")
        val files = metaRoot.optJSONArray("files") ?: throw IOException("No files in Internet Archive item.")
        val formats = ArrayList<UbitFormat>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val fname = f.optString("name")
            val ext = fname.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in setOf("mp4", "m4a", "webm", "mp3")) continue
            val enc = fname.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val isAud = ext == "m4a" || ext == "mp3"
            formats.add(
                UbitFormat(
                    id = fname,
                    url = "https://archive.org/download/$itemId/$enc",
                    ext = ext,
                    vcodec = if (isAud) "none" else "avc1",
                    acodec = if (ext == "mp3") "mp3" else "mp4a",
                    width = f.optString("width").toIntOrNull(),
                    height = f.optString("height").toIntOrNull(),
                    filesize = f.optString("size").toLongOrNull(),
                )
            )
        }
        if (formats.isEmpty()) throw IOException("No downloadable video/audio in Internet Archive item $itemId.")
        return UbitMediaInfo(
            id = itemId,
            title = metaRoot.optJSONObject("metadata")?.optString("title")?.ifBlank { itemId } ?: itemId,
            site = "Internet Archive",
            webpageUrl = u.toString(),
            formats = formats,
            preferredFileStem = "archive_$itemId",
        )
    }

    private fun extractLoom(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val vidId = firstMatch("/(?:share|embed)/([0-9a-f]{32})", u.encodedPath)
            ?: return extractGeneric(u.toString(), client, forceSite = "Loom")
        val gql = JSONObject()
            .put("operationName", "GetVideoTranscodedUrl")
            .put("variables", JSONObject().put("videoId", vidId).put("forceOriginal", false))
            .put("query", "query GetVideoTranscodedUrl(\$videoId: ID!, \$forceOriginal: Boolean) { fetchTranscodedUrl(videoId: \$videoId, forceOriginal: \$forceOriginal) { url } }")
        val surl = runCatching {
            client.fetchJsonObject(
                url = "https://www.loom.com/graphql",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = gql.toString().toByteArray(Charsets.UTF_8),
            )?.optJSONObject("data")?.optJSONObject("fetchTranscodedUrl")?.optString("url")
        }.getOrNull().orEmpty()
        if (surl.startsWith("http")) {
            val fmts = if (surl.contains(".m3u8")) UbitHlsEngine.extractFormats(client, surl)
            else listOf(UbitFormat(id = "mp4", url = surl, ext = "mp4", vcodec = "avc1", acodec = "mp4a"))
            return UbitMediaInfo(id = vidId, title = "Loom ${vidId.take(8)}", site = "Loom", webpageUrl = u.toString(), formats = fmts, preferredFileStem = "loom_${vidId.take(8)}")
        }
        return extractGeneric(u.toString(), client, forceSite = "Loom")
    }

    private fun extractTed(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val (html, finalUrl) = client.fetchText(u.toString())
        val formats = ArrayList<UbitFormat>()
        for (mp4 in allMatches("(https://py\\.tedcdn\\.com/[^\"'\\s]+\\.mp4)", html).distinct()) {
            val q = firstMatch("-(\\d+k)\\.mp4", mp4) ?: "mp4-${formats.size}"
            formats.add(UbitFormat(id = q, url = mp4, ext = "mp4", vcodec = "avc1", acodec = "mp4a", qualityLabel = q))
        }
        for (m3u8 in allMatches("(https://hls\\.ted\\.com/[^\"'\\s]+\\.m3u8[^\"'\\s]*)", html).distinct()) {
            runCatching { formats.addAll(UbitHlsEngine.extractFormats(client, unescapeJsString(m3u8))) }
        }
        if (formats.isEmpty()) return extractGeneric(finalUrl, client, forceSite = "TED", preloadedHtml = html)
        return UbitMediaInfo(id = u.pathSegments.lastOrNull().orEmpty(), title = pageTitle(html) ?: "TED Talk", site = "TED", webpageUrl = finalUrl, formats = formats)
    }

    // MARK: - 9. Imgur, Pinterest, Coub, 9GAG, Xiaohongshu, SoundCloud, Bandcamp, GitHub, Mastodon

    private fun extractImgur(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val id = firstMatch("/(?:gallery/|a/|t/[^/]+/)?(?:[A-Za-z0-9-]+-)?([A-Za-z0-9]{5,8})(?:\\.[a-z0-9]+)?$", u.encodedPath)
        if (id != null) {
            val directMp4 = "https://i.imgur.com/$id.mp4"
            val head = runCatching { client.fetch(directMp4, method = "HEAD") }.getOrNull()
            if (head != null && head.status == 200 && (head.contentType?.startsWith("video/") != false)) {
                return UbitMediaInfo(
                    id = id,
                    title = "Imgur $id",
                    site = "Imgur",
                    webpageUrl = u.toString(),
                    formats = listOf(UbitFormat(id = "mp4", url = directMp4, ext = "mp4", vcodec = "avc1", acodec = "mp4a")),
                    preferredFileStem = "imgur_$id",
                )
            }
        }
        return extractGeneric(u.toString(), client, forceSite = "Imgur")
    }

    private fun extractPinterest(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val (html, finalUrl) = client.fetchText(u.toString())
        val formats = ArrayList<UbitFormat>()
        for (v in allMatches("(https://v1\\.pinimg\\.com/videos/[^\"'\\s]+\\.(?:mp4|m3u8))", html).distinct()) {
            if (v.endsWith(".m3u8")) {
                runCatching { formats.addAll(UbitHlsEngine.extractFormats(client, v)) }
            } else {
                formats.add(UbitFormat(id = "mp4-${formats.size}", url = v, ext = "mp4", vcodec = "avc1", acodec = "mp4a"))
            }
        }
        if (formats.isEmpty()) return extractGeneric(finalUrl, client, forceSite = "Pinterest", preloadedHtml = html)
        return UbitMediaInfo(id = "pin", title = pageTitle(html) ?: "Pinterest Video", site = "Pinterest", webpageUrl = finalUrl, formats = formats)
    }

    private fun extractCoub(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val cid = firstMatch("/view/([A-Za-z0-9]+)", u.encodedPath)
            ?: return extractGeneric(u.toString(), client, forceSite = "Coub")
        val obj = runCatching { client.fetchJsonObject("https://coub.com/api/v2/coubs/$cid") }.getOrNull()
        val fv = obj?.optJSONObject("file_versions")?.optJSONObject("html5")
            ?: return extractGeneric(u.toString(), client, forceSite = "Coub")
        val formats = ArrayList<UbitFormat>()
        val vdict = fv.optJSONObject("video")
        if (vdict != null) {
            for (k in vdict.keys()) {
                val vu = vdict.optJSONObject(k)?.optString("url").orEmpty()
                if (vu.startsWith("http")) {
                    val h = when (k) { "higher" -> 960; "high" -> 720; "med" -> 480; else -> 360 }
                    formats.add(UbitFormat(id = "v-$k", url = vu, ext = "mp4", vcodec = "avc1", acodec = "none", height = h))
                }
            }
        }
        val adict = fv.optJSONObject("audio")
        if (adict != null) {
            for (k in adict.keys()) {
                val au = adict.optJSONObject(k)?.optString("url").orEmpty()
                if (au.startsWith("http") && au.endsWith(".m4a", ignoreCase = true)) {
                    formats.add(UbitFormat(id = "a-$k", url = au, ext = "m4a", vcodec = "none", acodec = "mp4a"))
                }
            }
        }
        if (formats.isEmpty()) return extractGeneric(u.toString(), client, forceSite = "Coub")
        return UbitMediaInfo(id = cid, title = obj.optString("title").ifBlank { "Coub $cid" }, site = "Coub", webpageUrl = u.toString(), formats = formats, preferredFileStem = "coub_$cid")
    }

    private fun extractNineGag(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val pid = firstMatch("/gag/([A-Za-z0-9]+)", u.encodedPath)
            ?: return extractGeneric(u.toString(), client, forceSite = "9GAG")
        val mp4 = "https://img-9gag-fun.9cache.com/photo/${pid}_460sv.mp4"
        val head = runCatching { client.fetch(mp4, method = "HEAD") }.getOrNull()
        if (head != null && head.status == 200) {
            return UbitMediaInfo(
                id = pid,
                title = "9GAG $pid",
                site = "9GAG",
                webpageUrl = u.toString(),
                formats = listOf(UbitFormat(id = "mp4", url = mp4, ext = "mp4", vcodec = "avc1", acodec = "mp4a")),
                preferredFileStem = "9gag_$pid",
            )
        }
        return extractGeneric(u.toString(), client, forceSite = "9GAG")
    }

    private fun extractXiaohongshu(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val (html, finalUrl) = client.fetchText(u.toString())
        val formats = ArrayList<UbitFormat>()
        val hdrs = mapOf("Referer" to "https://www.xiaohongshu.com/")
        for (raw in allMatches("(https?:\\\\?/\\\\?/sns-video-[^\"'\\s<>]+\\.mp4(?:\\?[^\"'\\s<>]*)?)", html).distinct()) {
            formats.add(UbitFormat(id = "xhs-${formats.size}", url = unescapeJsString(raw), ext = "mp4", vcodec = "avc1", acodec = "mp4a", headers = hdrs))
        }
        if (formats.isNotEmpty()) {
            return UbitMediaInfo(id = "xhs", title = pageTitle(html) ?: "Xiaohongshu Video", site = "Xiaohongshu", webpageUrl = finalUrl, formats = formats)
        }
        return extractGeneric(finalUrl, client, forceSite = "Xiaohongshu", preloadedHtml = html)
    }

    private fun extractSoundCloud(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val (html, finalUrl) = client.fetchText(u.toString())
        var cid = firstMatch("client_id[\"']?\\s*[:=]\\s*[\"']([a-zA-Z0-9]{32})[\"']", html)
        if (cid == null) {
            for (jsUrl in allMatches("<script[^>]+src=[\"'](https://a-v2\\.sndcdn\\.com/assets/[^\"']+\\.js)[\"']", html).takeLast(4).reversed()) {
                val js = runCatching { client.fetchText(jsUrl, includeCookies = false).first }.getOrNull() ?: continue
                cid = firstMatch("client_id\\s*:\\s*\"([a-zA-Z0-9]{32})\"", js)
                if (cid != null) break
            }
        }
        if (cid == null) throw IOException("Could not discover SoundCloud client_id.")
        val enc = URLEncoder.encode(finalUrl, "UTF-8")
        val res = client.fetchJsonObject("https://api-v2.soundcloud.com/resolve?url=$enc&client_id=$cid")
            ?: throw IOException("SoundCloud could not resolve that link.")
        val tauth = res.optString("track_authorization")
        val tcs = res.optJSONObject("media")?.optJSONArray("transcodings")
        val formats = ArrayList<UbitFormat>()
        if (tcs != null) {
            for (i in 0 until tcs.length()) {
                val tc = tcs.optJSONObject(i) ?: continue
                val protoStr = tc.optJSONObject("format")?.optString("protocol").orEmpty()
                val mime = tc.optJSONObject("format")?.optString("mime_type").orEmpty()
                if ("encrypted" in protoStr || "opus" in mime) continue
                var lookup = "${tc.optString("url")}?client_id=$cid"
                if (tauth.isNotBlank()) lookup += "&track_authorization=$tauth"
                val au = runCatching { client.fetchJsonObject(lookup)?.optString("url") }.getOrNull().orEmpty()
                if (au.startsWith("http")) {
                    formats.add(
                        UbitFormat(
                            id = "$protoStr-mp3",
                            url = au,
                            ext = "mp3",
                            proto = if ("hls" in protoStr || ".m3u8" in au) UbitStreamProto.HLS else UbitStreamProto.HTTP,
                            vcodec = "none",
                            acodec = "mp3",
                            bitrate = 128_000L,
                        )
                    )
                }
            }
        }
        if (formats.isEmpty()) throw IOException("No downloadable SoundCloud stream.")
        return UbitMediaInfo(id = res.optLong("id").toString(), title = res.optString("title", "SoundCloud"), site = "SoundCloud", webpageUrl = finalUrl, formats = formats)
    }

    private fun extractBandcamp(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val (html, finalUrl) = client.fetchText(u.toString())
        val attr = firstMatch("data-tralbum=[\"']([^\"']+)[\"']", html)
            ?: return extractGeneric(finalUrl, client, forceSite = "Bandcamp", preloadedHtml = html)
        val tr = runCatching { JSONObject(LinkResolver.decodeEntities(attr)) }.getOrNull()
            ?: throw IOException("Could not parse Bandcamp track metadata.")
        val t0 = tr.optJSONArray("trackinfo")?.optJSONObject(0) ?: throw IOException("No Bandcamp stream available.")
        var mp3 = t0.optJSONObject("file")?.optString("mp3-128").orEmpty()
        if (mp3.startsWith("//")) mp3 = "https:$mp3"
        if (!mp3.startsWith("http")) throw IOException("This Bandcamp track has no free stream.")
        return UbitMediaInfo(
            id = t0.optLong("id").toString(),
            title = t0.optString("title", "Bandcamp"),
            site = "Bandcamp",
            webpageUrl = finalUrl,
            formats = listOf(UbitFormat(id = "mp3-128", url = mp3, ext = "mp3", vcodec = "none", acodec = "mp3", bitrate = 128_000L)),
        )
    }

    private fun extractGitHubAsset(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val head = client.fetch(u.toString(), method = "HEAD")
        val fin = head.finalUrl.ifBlank { u.toString() }
        val id = fin.substringAfterLast('/').substringBefore('?').ifBlank { "github" }
        return UbitMediaInfo(
            id = id,
            title = id.substringBeforeLast('.'),
            site = "GitHub",
            webpageUrl = u.toString(),
            formats = listOf(UbitFormat(id = "mp4", url = fin, ext = "mp4", vcodec = "avc1", acodec = "mp4a")),
        )
    }

    private fun extractMastodon(u: HttpUrl, client: UbitHttpClient): UbitMediaInfo {
        val statusId = firstMatch("^/@[^/]+/(\\d+)$", u.encodedPath)
            ?: return extractGeneric(u.toString(), client, forceSite = "Mastodon")
        val origin = "${u.scheme}://${u.host}${if (u.port != 80 && u.port != 443) ":${u.port}" else ""}"
        val st = runCatching { client.fetchJsonObject("$origin/api/v1/statuses/$statusId") }.getOrNull()
        val atts = st?.optJSONArray("media_attachments")
        if (atts != null && atts.length() > 0) {
            val formats = ArrayList<UbitFormat>()
            var firstImg: String? = null
            for (i in 0 until atts.length()) {
                val a = atts.optJSONObject(i) ?: continue
                val tp = a.optString("type")
                val murl = a.optString("url")
                if (murl.isBlank()) continue
                if (tp == "video" || tp == "gifv") {
                    formats.add(UbitFormat(id = "masto-$i", url = murl, ext = "mp4", vcodec = "avc1", acodec = "mp4a"))
                } else if (tp == "image" && firstImg == null) {
                    firstImg = murl
                }
            }
            if (formats.isNotEmpty() || firstImg != null) {
                return UbitMediaInfo(
                    id = statusId,
                    title = "Mastodon $statusId",
                    site = "Mastodon",
                    webpageUrl = u.toString(),
                    author = st.optJSONObject("account")?.optString("acct"),
                    formats = formats,
                    imageFallbackUrl = if (formats.isEmpty()) firstImg else null,
                    preferredFileStem = "mastodon_$statusId",
                )
            }
        }
        return extractGeneric(u.toString(), client, forceSite = "Mastodon")
    }

    // MARK: - Generic HTML5 / OpenGraph / JSON-LD / HLS / DASH / Image Fallback Extractor

    fun extractGeneric(
        rawUrl: String,
        client: UbitHttpClient,
        forceSite: String? = null,
        preloadedHtml: String? = null,
    ): UbitMediaInfo {
        val finalUrlStr: String
        val html: String
        if (preloadedHtml != null) {
            finalUrlStr = rawUrl
            html = preloadedHtml
        } else {
            val resp = client.fetch(
                rawUrl,
                headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            )
            if (resp.status !in 200..299) {
                throw IOException("Couldn't open that link (HTTP ${resp.status}).")
            }
            finalUrlStr = resp.finalUrl.ifBlank { rawUrl }
            val ct = resp.contentType?.lowercase(Locale.ROOT).orEmpty()
            if (ct.startsWith("video/") || ct.startsWith("audio/")) {
                val isAud = ct.startsWith("audio/")
                val ext = if (ct.contains("webm")) "webm" else if (isAud) "m4a" else "mp4"
                return UbitMediaInfo(
                    id = "direct",
                    title = finalUrlStr.substringAfterLast('/').ifBlank { "media" },
                    site = forceSite ?: (finalUrlStr.toHttpUrlOrNull()?.host ?: "web"),
                    webpageUrl = finalUrlStr,
                    formats = listOf(UbitFormat(id = "direct", url = finalUrlStr, ext = ext, vcodec = if (isAud) "none" else "avc1", acodec = "mp4a")),
                )
            }
            if (ct.startsWith("image/")) {
                return UbitMediaInfo(
                    id = "direct-image",
                    title = finalUrlStr.substringAfterLast('/').ifBlank { "meme" },
                    site = forceSite ?: (finalUrlStr.toHttpUrlOrNull()?.host ?: "web"),
                    webpageUrl = finalUrlStr,
                    imageFallbackUrl = finalUrlStr,
                    imageFallbackMime = ct,
                )
            }
            html = resp.text()
        }

        val host = finalUrlStr.toHttpUrlOrNull()?.host ?: "web"
        val headers = mapOf("Referer" to finalUrlStr)
        val formats = ArrayList<UbitFormat>()
        var dashReps: List<UbitDashRepresentation> = emptyList()
        val seen = HashSet<String>()

        fun addVideoCandidate(cand: String) {
            val trimmed = cand.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("blob:", ignoreCase = true)) return
            val abs = LinkResolver.absolutize(unescapeJsString(trimmed), finalUrlStr)
            if (!abs.startsWith("http") || !seen.add(abs)) return
            val low = abs.lowercase(Locale.ROOT)
            val pathPart = low.substringBefore('?').substringBefore('#')
            when {
                ".m3u8" in low -> {
                    runCatching {
                        formats.addAll(UbitHlsEngine.extractFormats(client, abs, headers, "hls-${formats.size}"))
                    }
                }
                pathPart.endsWith(".mpd") -> {
                    runCatching {
                        val (df, dr) = UbitDashEngine.extractFormats(client, abs, headers)
                        formats.addAll(df)
                        dashReps = dr
                    }
                }
                pathPart.endsWith(".mp4") || pathPart.endsWith(".m4v") || pathPart.endsWith(".mov") || pathPart.endsWith(".webm") -> {
                    val ext = if (pathPart.endsWith(".webm")) "webm" else "mp4"
                    formats.add(
                        UbitFormat(
                            id = "web-${formats.size}",
                            url = abs,
                            ext = ext,
                            vcodec = "avc1",
                            acodec = "mp4a",
                            headers = headers,
                        )
                    )
                }
            }
        }

        // 1. OpenGraph / Twitter stream meta tags (even without .mp4 suffix in URL query strings, e.g. Tenor / CDN signed URLs)
        val ogVideo = LinkResolver.metaContent(html, "og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream")
        val ogVideoType = LinkResolver.metaContent(html, "og:video:type")?.lowercase(Locale.ROOT).orEmpty()
        if (!ogVideo.isNullOrBlank()) {
            val absOg = LinkResolver.absolutize(ogVideo, finalUrlStr)
            if (".m3u8" in absOg.lowercase(Locale.ROOT) || "mpegurl" in ogVideoType) {
                addVideoCandidate(absOg)
            } else if (!ogVideoType.contains("text/html") && !ogVideoType.contains("shockwave-flash") &&
                (ogVideoType.startsWith("video/") || LinkResolver.MEDIA_EXT_RE.matcher(absOg).find())
            ) {
                if (seen.add(absOg)) {
                    val ext = if ("webm" in ogVideoType || absOg.substringBefore('?').endsWith(".webm", true)) "webm" else "mp4"
                    formats.add(UbitFormat(id = "og-video", url = absOg, ext = ext, vcodec = "avc1", acodec = "mp4a", headers = headers))
                }
            }
        }

        // 2. JSON-LD contentUrl & HTML5 <video>/<source> & inline .m3u8 / .mp4
        for (cu in allMatches("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"", html)) addVideoCandidate(cu)
        for (src in allMatches("<(?:video|source)\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']", html, ignoreCase = true)) addVideoCandidate(src)
        for (m3u8 in allMatches("(https?://[^\\s\"'<>\\\\]+\\.m3u8(?:\\?[^\\s\"'<>\\\\]*)?)", html, ignoreCase = true)) addVideoCandidate(m3u8)
        if (formats.isEmpty()) {
            for (mp4 in allMatches("(https?://[^\\s\"'<>\\\\]+\\.(?:mp4|m4v|webm)(?:[?#][^\\s\"'<>\\\\]*)?)", html, ignoreCase = true)) {
                addVideoCandidate(mp4)
            }
        }

        // 3. Still image / GIF fallback for meme pages (Tenor, Memedepot, OG images, embedded <img>)
        var imageFallback: String? = null
        var imageMime: String? = null
        val ogImg = LinkResolver.metaContent(html, "og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")
        if (!ogImg.isNullOrBlank()) {
            imageFallback = LinkResolver.absolutize(ogImg, finalUrlStr)
            imageMime = LinkResolver.metaContent(html, "og:image:type")
        } else {
            val inlineImg = firstMatch("(https?://[^\\s\"'<>\\\\]+\\.(?:gif|jpe?g|png|webp)(?:[?#][^\\s\"'<>\\\\]*)?)", html, ignoreCase = true)
            if (inlineImg != null) imageFallback = LinkResolver.decodeEntities(inlineImg)
        }

        if (formats.isEmpty() && imageFallback == null) {
            throw IOException("No image or video found at that link.")
        }

        return UbitMediaInfo(
            id = kotlin.math.abs(finalUrlStr.hashCode()).toString(),
            title = pageTitle(html) ?: host,
            site = forceSite ?: host,
            webpageUrl = finalUrlStr,
            thumbnail = ogImg?.let { LinkResolver.absolutize(it, finalUrlStr) },
            formats = formats,
            dashRepresentations = dashReps,
            imageFallbackUrl = if (formats.isEmpty()) imageFallback else null,
            imageFallbackMime = imageMime,
        )
    }
}
