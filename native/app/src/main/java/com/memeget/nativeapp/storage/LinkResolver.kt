package com.memeget.nativeapp.storage

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

internal object LinkResolver {

    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private const val MAX_DOWNLOAD_BYTES = 350L * 1024 * 1024 // 350 MiB safety cap

    val URL_RE = Pattern.compile("""(https?://[^\s"'<>]+)""", Pattern.CASE_INSENSITIVE)
    val MEDIA_EXT_RE = Pattern.compile(
        """\.(mp4|m4v|mov|webm|gif|jpe?g|png|webp|bmp)(?:[?#]|$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val SYNDICATION_FEATURES = listOf(
        "tfw_timeline_list:",
        "tfw_follower_count_sunset:true",
        "tfw_tweet_edit_backend:on",
        "tfw_refsrc_session:on",
        "tfw_fosnr_soft_interventions_enabled:on",
        "tfw_show_birdwatch_pivots_enabled:on",
        "tfw_show_business_verified_badge:on",
        "tfw_duplicate_scribes_to_settings:on",
        "tfw_use_profile_image_shape_enabled:on",
        "tfw_show_blue_verified_badge:on",
        "tfw_legacy_timeline_sunset:true",
        "tfw_show_gov_verified_badge:on",
        "tfw_show_business_affiliate_badge:on",
        "tfw_tweet_edit_frontend:on"
    ).joinToString(";")

    data class MediaRef(
        val url: String,
        val mimeType: String? = null,
        val fileName: String? = null
    )

    data class DownloadedMedia(
        val file: File,
        val fileName: String,
        val mimeType: String,
        val sha256: String
    )

    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun extractUrl(vararg candidates: String?): String? {
        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            val m = URL_RE.matcher(c)
            if (m.find()) {
                val candidate = m.group(1)?.replace(Regex("[)\\].,]+$"), "") ?: continue
                val sanitized = sanitizeUrl(candidate)
                if (sanitized != null) return sanitized
            }
        }
        return null
    }

    fun sanitizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        val scheme = parsed.scheme.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        return parsed.toString()
    }

    fun decodeEntities(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("&#0*39;"), "'")
            .replace(Regex("&#x2F;", RegexOption.IGNORE_CASE), "/")
            .replace(Regex("&#0*47;"), "/")
    }

    fun absolutize(url: String, base: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        val baseUrl = base.toHttpUrlOrNull() ?: return trimmed
        return baseUrl.resolve(trimmed)?.toString() ?: trimmed
    }

    fun metaContent(html: String, vararg names: String): String? {
        for (name in names) {
            val esc = Pattern.quote(name)
            val after = Pattern.compile(
                """<meta[^>]+(?:property|name)=["']$esc["'][^>]+content=["']([^"']+)["']""",
                Pattern.CASE_INSENSITIVE
            )
            val before = Pattern.compile(
                """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$esc["']""",
                Pattern.CASE_INSENSITIVE
            )
            var m = after.matcher(html)
            if (m.find()) return decodeEntities(m.group(1).orEmpty())
            m = before.matcher(html)
            if (m.find()) return decodeEntities(m.group(1).orEmpty())
        }
        return null
    }

    private fun isShortlink(url: String): Boolean {
        return Pattern.compile("""https?://t\.co/""", Pattern.CASE_INSENSITIVE).matcher(url).find()
    }

    private fun unwrapShortlink(url: String, client: OkHttpClient): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .head()
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (finalUrl.isNotBlank() && finalUrl != url) finalUrl else url
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun isTwitter(url: String): Boolean {
        return Pattern.compile("""https?://(?:[\w-]+\.)*(?:twitter|x)\.com/""", Pattern.CASE_INSENSITIVE).matcher(url).find()
    }

    private fun isTenor(url: String): Boolean {
        return Pattern.compile("""https?://(?:[\w-]+\.)*tenor\.com/""", Pattern.CASE_INSENSITIVE).matcher(url).find()
    }

    private fun isMemedepot(url: String): Boolean {
        return Pattern.compile("""https?://(?:[\w-]+\.)*memedepot\.com/""", Pattern.CASE_INSENSITIVE).matcher(url).find()
    }

    fun tweetId(url: String): String? {
        val m = Pattern.compile("""(?:twitter|x)\.com/[^/]+/status(?:es)?/(\d+)""", Pattern.CASE_INSENSITIVE).matcher(url)
        return if (m.find()) m.group(1) else null
    }

    fun doubleToRadix36(value: Double): String {
        if (value.isNaN() || value.isInfinite() || value == 0.0) return "0"
        val negative = value < 0
        val v = if (negative) -value else value

        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        var integer = Math.floor(v)
        var fraction = v - integer

        val nextVal = java.lang.Double.longBitsToDouble(java.lang.Double.doubleToLongBits(v) + 1L)
        var delta = 0.5 * (nextVal - v)

        val fractionChars = StringBuilder()
        if (delta > 0.0 && fraction >= delta) {
            do {
                fraction *= 36.0
                delta *= 36.0
                val digit = fraction.toInt()
                fractionChars.append(chars[digit])
                fraction -= digit.toDouble()
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1 != 0))) {
                    if (fraction + delta > 1.0) {
                        var carry = true
                        for (i in fractionChars.length - 1 downTo 0) {
                            val c = fractionChars[i]
                            val d = chars.indexOf(c)
                            if (d + 1 < 36) {
                                fractionChars.setCharAt(i, chars[d + 1])
                                carry = false
                                break
                            } else {
                                fractionChars.deleteCharAt(i)
                            }
                        }
                        if (carry) integer += 1.0
                        break
                    }
                }
            } while (fraction >= delta)
        }

        val intChars = StringBuilder()
        var temp = integer.toLong()
        if (temp == 0L) {
            intChars.append('0')
        } else {
            while (temp > 0L) {
                intChars.append(chars[(temp % 36L).toInt()])
                temp /= 36L
            }
            intChars.reverse()
        }

        val sb = StringBuilder()
        if (negative) sb.append('-')
        sb.append(intChars)
        if (fractionChars.isNotEmpty()) {
            sb.append('.')
            sb.append(fractionChars)
        }
        return sb.toString()
    }

    fun syndicationToken(id: String): String {
        val idNum = id.toDoubleOrNull() ?: return ""
        val v = (idNum / 1e15) * Math.PI
        val radix36 = doubleToRadix36(v)
        return radix36.replace(Regex("(0+|\\.)"), "")
    }

    private fun fetchText(url: String, client: OkHttpClient): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Couldn't open that link (HTTP ${resp.code}).")
            }
            return resp.body?.string() ?: throw IOException("Empty response from $url")
        }
    }

    private fun resolveTwitter(url: String, client: OkHttpClient): MediaRef {
        val id = tweetId(url) ?: return resolveGeneric(url, client)
        val token = syndicationToken(id)
        val apiUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$id&token=$token&features=$SYNDICATION_FEATURES&lang=en"

        val req = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Couldn't read that tweet (HTTP ${resp.code}).")
            }
            val jsonText = resp.body?.string() ?: throw IOException("Empty syndication response")
            val data = JSONObject(jsonText)

            if (data.optString("__typename") == "TweetTombstone" || data.has("tombstone")) {
                throw IOException("That tweet can't be opened without signing in to X (it may be age-restricted or protected).")
            }

            val mediaDetails = data.optJSONArray("mediaDetails")
            if (mediaDetails != null && mediaDetails.length() > 0) {
                // Try highest bitrate MP4 video
                for (i in 0 until mediaDetails.length()) {
                    val m = mediaDetails.optJSONObject(i) ?: continue
                    val videoInfo = m.optJSONObject("video_info")
                    val variants = videoInfo?.optJSONArray("variants")
                    if (variants != null) {
                        var bestUrl: String? = null
                        var highestBitrate = -1L
                        for (vIdx in 0 until variants.length()) {
                            val v = variants.optJSONObject(vIdx) ?: continue
                            if (v.optString("content_type") == "video/mp4") {
                                val u = v.optString("url")
                                val bitrate = v.optLong("bitrate", 0L)
                                if (u.isNotBlank() && bitrate >= highestBitrate) {
                                    highestBitrate = bitrate
                                    bestUrl = u
                                }
                            }
                        }
                        if (bestUrl != null) {
                            return MediaRef(url = bestUrl, mimeType = "video/mp4", fileName = "tweet_$id")
                        }
                    }
                }

                // If no video, look for still photo
                for (i in 0 until mediaDetails.length()) {
                    val m = mediaDetails.optJSONObject(i) ?: continue
                    val photoUrl = m.optString("media_url_https")
                    if (photoUrl.isNotBlank()) {
                        return MediaRef(url = "$photoUrl?name=large", mimeType = "image/jpeg", fileName = "tweet_$id")
                    }
                }
            }
            throw IOException("That tweet has no image or video to save.")
        }
    }

    private fun embeddedMedia(html: String, base: String): MediaRef? {
        val tagPattern = Pattern.compile("""<(?:video|source)\b[^>]*\bsrc=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val tagMatcher = tagPattern.matcher(html)
        if (tagMatcher.find()) {
            val src = tagMatcher.group(1).orEmpty()
            if (MEDIA_EXT_RE.matcher(src).find()) {
                return MediaRef(url = absolutize(decodeEntities(src), base))
            }
        }

        val directPattern = Pattern.compile(
            """https?://[^\s"'<>\\]+\.(?:mp4|m4v|webm|gif|jpe?g|png|webp)(?:[?#][^\s"'<>\\]*)?""",
            Pattern.CASE_INSENSITIVE
        )
        val directMatcher = directPattern.matcher(html)
        if (directMatcher.find()) {
            return MediaRef(url = decodeEntities(directMatcher.group(0).orEmpty()))
        }
        return null
    }

    private fun resolveGeneric(url: String, client: OkHttpClient): MediaRef {
        val html = fetchText(url, client)
        val video = metaContent(
            html,
            "og:video:secure_url",
            "og:video:url",
            "og:video",
            "twitter:player:stream"
        )
        if (!video.isNullOrBlank()) {
            return MediaRef(
                url = absolutize(video, url),
                mimeType = metaContent(html, "og:video:type")
            )
        }

        val image = metaContent(
            html,
            "og:image:secure_url",
            "og:image",
            "twitter:image",
            "twitter:image:src"
        )
        if (!image.isNullOrBlank()) {
            return MediaRef(
                url = absolutize(image, url),
                mimeType = metaContent(html, "og:image:type")
            )
        }

        val embedded = embeddedMedia(html, url)
        if (embedded != null) return embedded

        throw IOException("No image or video found at that link.")
    }

    private fun slugFromUrl(url: String): String {
        return try {
            val path = url.toHttpUrlOrNull()?.encodedPath ?: ""
            val parts = path.split("/").filter { it.isNotBlank() }
            if (parts.isNotEmpty()) parts.last() else "meme"
        } catch (_: Exception) {
            "meme"
        }
    }

    private fun resolveMemedepot(url: String, client: OkHttpClient): MediaRef {
        val ref = resolveGeneric(url, client)
        val slug = slugFromUrl(url)
        return ref.copy(fileName = ref.fileName ?: "memedepot_$slug")
    }

    private fun resolveImgflip(url: String, client: OkHttpClient): MediaRef {
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        val template = Regex("""^/(?:memegenerator|memetemplate)/(\d+)(?:/|$)""", RegexOption.IGNORE_CASE)
            .find(path) ?: return resolveGeneric(url, client)
        val id = template.groupValues[1]
        val page = if (path.startsWith("/memetemplate/", ignoreCase = true)) url
            else "https://imgflip.com/memetemplate/$id"
        val html = fetchText(page, client)
        // The template page marks its full-size image; generator pages only
        // contain a thumbnail among unrelated icons and trending memes.
        val tag = Regex("""<img\b[^>]*\s+id=["']mtm-img["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value
        val src = tag?.let {
            Regex("""\s+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        if (src.isNullOrBlank() || !MEDIA_EXT_RE.matcher(src).find()) {
            throw IOException("No template image found at that Imgflip link.")
        }
        return MediaRef(url = absolutize(decodeEntities(src), page), fileName = "imgflip_$id")
    }

    fun resolveMediaRef(rawUrl: String, client: OkHttpClient = defaultClient): MediaRef {
        var url = sanitizeUrl(rawUrl) ?: throw IOException("Invalid web link: $rawUrl")
        if (isShortlink(url)) {
            url = unwrapShortlink(url, client)
        }

        return when {
            MEDIA_EXT_RE.matcher(url).find() -> MediaRef(url = url)
            isTwitter(url) -> resolveTwitter(url, client)
            Pattern.compile("""https?://(?:www\.)?imgflip\.com/""", Pattern.CASE_INSENSITIVE).matcher(url).find() ->
                resolveImgflip(url, client)
            isTenor(url) -> resolveGeneric(url, client)
            isMemedepot(url) -> resolveMemedepot(url, client)
            else -> resolveGeneric(url, client)
        }
    }

    fun download(
        ref: MediaRef,
        cacheDir: File,
        client: OkHttpClient = defaultClient
    ): DownloadedMedia {
        cacheDir.mkdirs()
        var mimeType = ref.mimeType ?: MediaIoUtils.mimeForName(ref.url)
        var ext = MediaIoUtils.extForMime(mimeType).ifBlank {
            MediaIoUtils.extOf(ref.url).ifBlank { "mp4" }
        }

        val slug = Math.abs(ref.url.hashCode()) % 1_000_000_000
        val tempFile = File(cacheDir, "link_dl_${System.currentTimeMillis()}_$slug.$ext")

        val req = Request.Builder()
            .url(ref.url)
            .header("User-Agent", BROWSER_UA)
            .build()

        var digestHex: String
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                tempFile.delete()
                throw IOException("Download failed (HTTP ${resp.code}).")
            }

            val contentType = resp.header("Content-Type")?.split(";")?.get(0)?.trim()
            if (!contentType.isNullOrBlank() && (contentType.startsWith("image/") || contentType.startsWith("video/"))) {
                mimeType = contentType
                ext = MediaIoUtils.extForMime(mimeType)
            }

            val body = resp.body ?: throw IOException("Download body is empty")
            val md = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32768)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        totalBytes += read
                        if (totalBytes > MAX_DOWNLOAD_BYTES) {
                            tempFile.delete()
                            throw IOException("Media download exceeded maximum limit (${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MiB)")
                        }
                        md.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            digestHex = md.digest().joinToString("") { "%02x".format(it) }
        }

        val base = (ref.fileName ?: "shared_$slug").replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val finalFileName = if (base.lowercase(Locale.ROOT).endsWith(".$ext")) base else "$base.$ext"

        return DownloadedMedia(
            file = tempFile,
            fileName = finalFileName,
            mimeType = mimeType,
            sha256 = digestHex
        )
    }
}
