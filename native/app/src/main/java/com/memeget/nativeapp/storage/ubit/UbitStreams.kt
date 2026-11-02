package com.memeget.nativeapp.storage.ubit

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.SAXParserFactory
import kotlin.math.ceil

// HLS (Master/Media M3U8, #EXT-X-MEDIA separate audio, #EXT-X-MAP fMP4, AES-128-CBC) and MPEG-DASH (.mpd)
// engines from uBit, sucking segmented streams dry into clean local files.

internal data class UbitDashRepresentation(
    val id: String,
    val mimeType: String,
    val codecs: String?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val bandwidth: Long,
    val baseUrl: String?,
    val initUrl: String?,
    val segmentUrls: List<String>,
    val isLive: Boolean,
)

internal object UbitHlsEngine {

    fun parseAttributes(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < raw.length) {
            while (i < raw.length && (raw[i] == ',' || raw[i].isWhitespace())) i++
            val eq = raw.indexOf('=', i)
            if (eq < 0) break
            val key = raw.substring(i, eq).trim()
            var v = eq + 1
            if (v < raw.length && raw[v] == '"') {
                v++
                val q2 = raw.indexOf('"', v)
                if (q2 < 0) {
                    out[key] = raw.substring(v)
                    break
                } else {
                    out[key] = raw.substring(v, q2)
                    i = q2 + 1
                }
            } else {
                val cm = raw.indexOf(',', v)
                if (cm < 0) {
                    out[key] = raw.substring(v).trim()
                    break
                } else {
                    out[key] = raw.substring(v, cm).trim()
                    i = cm + 1
                }
            }
        }
        return out
    }

    fun resolveUrl(rel: String, base: String): String {
        val trimmed = rel.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val baseHttp = base.toHttpUrlOrNull() ?: return trimmed
        return baseHttp.resolve(trimmed)?.toString() ?: trimmed
    }

    fun extractFormats(
        client: UbitHttpClient,
        playlistUrl: String,
        headers: Map<String, String> = emptyMap(),
        idPrefix: String = "hls",
    ): List<UbitFormat> {
        val (text, finalUrl) = client.fetchText(playlistUrl, headers)
        if (!text.contains("#EXT-X-STREAM-INF")) {
            val isLive = !text.contains("#EXT-X-ENDLIST") && text.contains("#EXTINF:")
            return listOf(
                UbitFormat(
                    id = idPrefix,
                    url = finalUrl,
                    ext = "mp4",
                    proto = UbitStreamProto.HLS,
                    vcodec = "avc1",
                    acodec = "mp4a",
                    headers = headers,
                    isLive = isLive,
                )
            )
        }

        val audioGroups = LinkedHashMap<String, String>()
        val lines = text.lineSequence().map { it.trim() }.toList()
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                val attrs = parseAttributes(line.removePrefix("#EXT-X-MEDIA:"))
                val gid = attrs["GROUP-ID"]
                val uri = attrs["URI"]
                if (attrs["TYPE"] == "AUDIO" && !gid.isNullOrBlank() && !uri.isNullOrBlank()) {
                    if (!audioGroups.containsKey(gid) || attrs["DEFAULT"] == "YES") {
                        audioGroups[gid] = resolveUrl(uri, finalUrl)
                    }
                }
            }
        }

        val formats = ArrayList<UbitFormat>()
        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = parseAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))
                var nextIdx = idx + 1
                var uriLine = ""
                while (nextIdx < lines.size) {
                    if (lines[nextIdx].isNotEmpty() && !lines[nextIdx].startsWith("#")) {
                        uriLine = lines[nextIdx]
                        break
                    }
                    nextIdx++
                }
                if (uriLine.isNotEmpty()) {
                    val fullUri = resolveUrl(uriLine, finalUrl)
                    val bw = (attrs["AVERAGE-BANDWIDTH"] ?: attrs["BANDWIDTH"])?.toLongOrNull()
                    var width: Int? = null
                    var height: Int? = null
                    attrs["RESOLUTION"]?.lowercase(Locale.ROOT)?.split('x')?.takeIf { it.size == 2 }?.let { wh ->
                        width = wh[0].toIntOrNull()
                        height = wh[1].toIntOrNull()
                    }
                    val fps = attrs["FRAME-RATE"]?.toDoubleOrNull()
                    var vcodec: String? = "avc1"
                    var acodec: String? = "mp4a"
                    attrs["CODECS"]?.let { codecs ->
                        val parts = codecs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val vc = parts.firstOrNull { p ->
                            val lp = p.lowercase(Locale.ROOT)
                            !lp.startsWith("mp4a") && !lp.startsWith("opus") && !lp.startsWith("ac-3") && !lp.startsWith("ec-3")
                        }
                        val ac = parts.firstOrNull { p ->
                            val lp = p.lowercase(Locale.ROOT)
                            lp.startsWith("mp4a") || lp.startsWith("opus") || lp.startsWith("ac-3") || lp.startsWith("ec-3")
                        }
                        vcodec = vc ?: "none"
                        acodec = ac ?: "none"
                    }
                    val audioGroupId = attrs["AUDIO"]
                    val separateAudioUrl = if (!audioGroupId.isNullOrBlank()) audioGroups[audioGroupId] else null
                    if (separateAudioUrl != null && (acodec == null || acodec == "none")) {
                        acodec = "mp4a"
                    }
                    val fid = if ((height ?: 0) > 0) "$idPrefix-${height}p-${formats.size}" else "$idPrefix-${formats.size}"
                    formats.add(
                        UbitFormat(
                            id = fid,
                            url = fullUri,
                            ext = "mp4",
                            proto = UbitStreamProto.HLS,
                            vcodec = vcodec,
                            acodec = acodec,
                            width = width,
                            height = height,
                            fps = fps,
                            bitrate = bw,
                            headers = headers,
                            audioTrackUrl = separateAudioUrl,
                        )
                    )
                    idx = nextIdx
                }
            }
            idx++
        }
        return formats
    }

    private data class HlsSegment(
        val index: Int,
        val url: String,
        val byteRange: UbitByteRange?,
        val keyUrl: String?,
        val iv: ByteArray?,
        val mediaSeq: Long,
    )

    // Downloads an HLS media playlist into destination. Returns true when segments are fMP4, false when MPEG-TS.
    fun downloadStream(
        client: UbitHttpClient,
        playlistUrl: String,
        destination: File,
        headers: Map<String, String> = emptyMap(),
        connections: Int = 6,
        priorBytes: Long = 0L,
    ): Boolean {
        var (text, finalUrl) = client.fetchText(playlistUrl, headers)
        // If caller passed a master playlist directly, pick its highest-resolution variant first.
        if (text.contains("#EXT-X-STREAM-INF")) {
            val subFmts = extractFormats(client, playlistUrl, headers)
            val chosen = UbitFormatSelector.select(subFmts).primary
            val sub = client.fetchText(chosen.url, headers)
            text = sub.first
            finalUrl = sub.second
        }

        val isLive = !text.contains("#EXT-X-ENDLIST") && text.contains("#EXTINF:")
        if (isLive) {
            throw IOException("Live streams can't be saved as memes; share a clip or finished video link instead.")
        }

        var initUrl: String? = null
        var initRange: UbitByteRange? = null
        var mediaSeq = 0L
        var curRange: UbitByteRange? = null
        var prevEnd = 0L
        var curKeyUrl: String? = null
        var curIv: ByteArray? = null
        val segments = ArrayList<HlsSegment>()

        fun parseRange(s: String, prev: Long): Pair<UbitByteRange, Long> {
            val parts = s.split('@')
            val len = parts[0].trim().toLongOrNull() ?: 0L
            val st = if (parts.size == 2) parts[1].trim().toLongOrNull() ?: prev else prev
            return UbitByteRange(st, st + len - 1L) to (st + len)
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSeq = line.removePrefix("#EXT-X-MEDIA-SEQUENCE:").trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = parseAttributes(line.removePrefix("#EXT-X-MAP:"))
                    a["URI"]?.takeIf { it.isNotBlank() }?.let { initUrl = resolveUrl(it, finalUrl) }
                    a["BYTERANGE"]?.takeIf { it.isNotBlank() }?.let { initRange = parseRange(it, 0L).first }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = parseAttributes(line.removePrefix("#EXT-X-KEY:"))
                    when (a["METHOD"]) {
                        "AES-128" -> {
                            val u = a["URI"]
                            curKeyUrl = if (!u.isNullOrBlank()) resolveUrl(u, finalUrl) else null
                            val ivHex = a["IV"]?.removePrefix("0x")?.removePrefix("0X").orEmpty()
                            curIv = if (ivHex.length == 32) {
                                ByteArray(16) { k -> ivHex.substring(k * 2, k * 2 + 2).toInt(16).toByte() }
                            } else {
                                null
                            }
                        }
                        "NONE" -> {
                            curKeyUrl = null
                            curIv = null
                        }
                    }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val parsed = parseRange(line.removePrefix("#EXT-X-BYTERANGE:").trim(), prevEnd)
                    curRange = parsed.first
                    prevEnd = parsed.second
                }
                !line.startsWith("#") -> {
                    segments.add(
                        HlsSegment(
                            index = segments.size,
                            url = resolveUrl(line, finalUrl),
                            byteRange = curRange,
                            keyUrl = curKeyUrl,
                            iv = curIv,
                            mediaSeq = mediaSeq + segments.size,
                        )
                    )
                    curRange = null
                }
            }
        }

        if (segments.isEmpty()) {
            throw IOException("HLS playlist contained no playable media segments")
        }

        val totalBytes = AtomicLong(priorBytes)
        val initBytes = initUrl?.let { iu ->
            val r = initRange
            val b = if (r != null && r.length > 0L) {
                client.fetchRange(iu, r.start, r.end, headers)
            } else {
                val resp = client.fetch(iu, "GET", headers)
                if (resp.status !in 200..299) throw IOException("Failed fetching HLS init segment (${resp.status})")
                resp.body
            }
            if (totalBytes.addAndGet(b.size.toLong()) > client.maxDownloadBytes) {
                throw IOException("Media download exceeded maximum limit (${client.maxDownloadBytes / (1024 * 1024)} MiB)")
            }
            b
        }

        val segDir = File(destination.parentFile, "${destination.name}_segs")
        segDir.mkdirs()
        val keyCache = ConcurrentHashMap<String, ByteArray>()

        try {
            val workers = minOf(connections.coerceIn(1, 8), segments.size)
            val pool = Executors.newFixedThreadPool(workers)
            try {
                val tasks = segments.map { seg ->
                    Callable {
                        val rawBytes = if (seg.byteRange != null && seg.byteRange.length > 0L) {
                            client.fetchRange(seg.url, seg.byteRange.start, seg.byteRange.end, headers)
                        } else {
                            val resp = client.fetch(seg.url, "GET", headers, maxResponseBytes = client.maxDownloadBytes)
                            if (resp.status == 403 || resp.status == 410) throw UbitStaleLinkException(resp.status)
                            if (resp.status !in 200..299) throw IOException("HLS segment HTTP ${resp.status}")
                            resp.body
                        }
                        if (totalBytes.addAndGet(rawBytes.size.toLong()) > client.maxDownloadBytes) {
                            throw IOException("Media download exceeded maximum limit (${client.maxDownloadBytes / (1024 * 1024)} MiB)")
                        }
                        val payload = if (seg.keyUrl != null) {
                            val key = keyCache.computeIfAbsent(seg.keyUrl) { ku ->
                                val kr = client.fetch(ku, "GET", headers)
                                if (kr.status !in 200..299 || kr.body.size != 16) {
                                    throw IOException("Failed loading 16-byte HLS AES-128 key")
                                }
                                kr.body
                            }
                            val iv = seg.iv ?: ByteArray(16).also { buf ->
                                var sq = seg.mediaSeq
                                for (k in 0 until 8) {
                                    buf[15 - k] = (sq and 0xffL).toByte()
                                    sq = sq ushr 8
                                }
                            }
                            decryptAes128Cbc(rawBytes, key, iv)
                        } else {
                            rawBytes
                        }
                        val sf = File(segDir, "%06d.seg".format(Locale.US, seg.index))
                        sf.writeBytes(payload)
                    }
                }
                for (f in tasks.map { pool.submit(it) }) {
                    try {
                        f.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw (e.cause ?: e)
                    }
                }
            } finally {
                pool.shutdownNow()
            }

            val partFile = File(destination.parentFile, "${destination.name}.part")
            var firstBytes: ByteArray? = null
            BufferedOutputStream(FileOutputStream(partFile), 65536).use { out ->
                if (initBytes != null && initBytes.isNotEmpty()) {
                    out.write(initBytes)
                    firstBytes = initBytes.copyOfRange(0, minOf(16, initBytes.size))
                }
                for (i in segments.indices) {
                    val sf = File(segDir, "%06d.seg".format(Locale.US, i))
                    val sb = sf.readBytes()
                    if (firstBytes == null && sb.isNotEmpty()) {
                        firstBytes = sb.copyOfRange(0, minOf(16, sb.size))
                    }
                    if (sb.isNotEmpty()) out.write(sb)
                }
                out.flush()
            }
            if (destination.exists()) destination.delete()
            if (!partFile.renameTo(destination)) {
                partFile.copyTo(destination, overwrite = true)
                partFile.delete()
            }
            val head = firstBytes
            return (initBytes != null && initBytes.isNotEmpty()) ||
                (head != null && head.size >= 8 &&
                    (UbitMp4Muxer.rd4cc(head, 4) == "ftyp" || UbitMp4Muxer.rd4cc(head, 4) == "styp" || UbitMp4Muxer.rd4cc(head, 4) == "moof"))
        } finally {
            segDir.deleteRecursively()
        }
    }

    private fun decryptAes128Cbc(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val secret = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secret, ivSpec)
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, ivSpec)
            cipher.doFinal(cipherBytes)
        }
    }
}

internal object UbitDashEngine {

    private data class TimelineEntry(val t: Long?, val d: Long, val r: Int)

    fun extractFormats(
        client: UbitHttpClient,
        mpdUrl: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<List<UbitFormat>, List<UbitDashRepresentation>> {
        val (xml, finalUrl) = client.fetchText(mpdUrl, headers)
        val reps = parseMpd(xml, finalUrl)
        val formats = reps.map { rep ->
            val mime = rep.mimeType.lowercase(Locale.ROOT)
            val isAudio = mime.startsWith("audio/") || (rep.width == null && rep.height == null && (rep.codecs?.startsWith("mp4a") == true || rep.codecs?.contains("opus") == true))
            val isWebm = "webm" in mime
            val ext = if (isWebm) "webm" else if (isAudio) "m4a" else "mp4"
            UbitFormat(
                id = "dash-${rep.id}",
                url = finalUrl,
                ext = ext,
                proto = UbitStreamProto.DASH,
                vcodec = if (isAudio) "none" else (rep.codecs ?: "avc1"),
                acodec = if (isAudio) (rep.codecs ?: "mp4a.40.2") else "none",
                width = rep.width,
                height = rep.height,
                fps = rep.fps,
                bitrate = rep.bandwidth.takeIf { it > 0L },
                headers = headers,
                isLive = rep.isLive,
            )
        }
        return formats to reps
    }

    fun parseMpd(xml: String, manifestUrl: String): List<UbitDashRepresentation> {
        val reps = ArrayList<UbitDashRepresentation>()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newSAXParser()

        val handler = object : DefaultHandler() {
            var isDynamic = false
            var mediaDuration: Double? = null

            var mpdBaseUrl: String? = null
            var periodBaseUrl: String? = null
            var adaptMime = ""
            var adaptCodecs: String? = null
            var adaptBaseUrl: String? = null
            var adaptTemplate: Map<String, String>? = null
            val adaptTimeline = ArrayList<TimelineEntry>()

            var inRep = false
            var repAttrs: Map<String, String> = emptyMap()
            var repDirectBase = ""
            var inBaseUrl = false
            val baseUrlChars = StringBuilder()
            var repTemplate: Map<String, String>? = null
            val repTimeline = ArrayList<TimelineEntry>()
            var repInitSource: String? = null
            val stack = ArrayList<String>()

            private fun attrsToMap(attributes: Attributes): Map<String, String> {
                val m = LinkedHashMap<String, String>()
                for (i in 0 until attributes.length) {
                    val rawName = attributes.getQName(i).ifEmpty { attributes.getLocalName(i) }
                    val local = rawName.substringAfterLast(':')
                    m[local] = attributes.getValue(i)
                }
                return m
            }

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val raw = (qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()).substringAfterLast(':')
                stack.add(raw)
                val map = attrsToMap(attributes)
                when (raw) {
                    "MPD" -> {
                        isDynamic = map["type"] == "dynamic"
                        map["mediaPresentationDuration"]?.let { mediaDuration = parseIsoDuration(it) }
                    }
                    "AdaptationSet" -> {
                        adaptMime = map["mimeType"] ?: map["contentType"].orEmpty()
                        adaptCodecs = map["codecs"]
                        adaptBaseUrl = null
                        adaptTemplate = null
                        adaptTimeline.clear()
                    }
                    "Representation" -> {
                        inRep = true
                        repAttrs = map
                        repDirectBase = ""
                        repTemplate = null
                        repTimeline.clear()
                        repInitSource = null
                    }
                    "BaseURL" -> {
                        inBaseUrl = true
                        baseUrlChars.setLength(0)
                    }
                    "SegmentTemplate" -> {
                        if (inRep) repTemplate = map else adaptTemplate = map
                    }
                    "Initialization" -> {
                        map["sourceURL"]?.let { repInitSource = it }
                    }
                    "S" -> {
                        val t = map["t"]?.toLongOrNull()
                        val d = map["d"]?.toLongOrNull() ?: 0L
                        val r = map["r"]?.toIntOrNull() ?: 0
                        val entry = TimelineEntry(t, d, r)
                        if (inRep) repTimeline.add(entry) else adaptTimeline.add(entry)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inBaseUrl) baseUrlChars.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val raw = (qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()).substringAfterLast(':')
                if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                when (raw) {
                    "BaseURL" -> {
                        inBaseUrl = false
                        val trimmed = baseUrlChars.toString().trim()
                        if (trimmed.isNotEmpty()) {
                            when (stack.lastOrNull()) {
                                "MPD" -> mpdBaseUrl = UbitHlsEngine.resolveUrl(trimmed, manifestUrl)
                                "Period" -> periodBaseUrl = UbitHlsEngine.resolveUrl(trimmed, mpdBaseUrl ?: manifestUrl)
                                "AdaptationSet" -> adaptBaseUrl = UbitHlsEngine.resolveUrl(trimmed, periodBaseUrl ?: mpdBaseUrl ?: manifestUrl)
                                "Representation" -> repDirectBase = trimmed
                            }
                        }
                    }
                    "Representation" -> {
                        inRep = false
                        finalizeRep()
                    }
                }
            }

            private fun finalizeRep() {
                val repId = repAttrs["id"] ?: "rep-${reps.size}"
                val mime = repAttrs["mimeType"] ?: adaptMime
                val codecs = repAttrs["codecs"] ?: adaptCodecs
                val width = repAttrs["width"]?.toIntOrNull()
                val height = repAttrs["height"]?.toIntOrNull()
                val bw = repAttrs["bandwidth"]?.toLongOrNull() ?: 0L
                val fps = repAttrs["frameRate"]?.let { fr ->
                    val parts = fr.split('/')
                    if (parts.size == 2) {
                        val n = parts[0].toDoubleOrNull()
                        val d = parts[1].toDoubleOrNull()
                        if (n != null && d != null && d > 0.0) n / d else null
                    } else {
                        fr.toDoubleOrNull()
                    }
                }

                val effectiveBase = adaptBaseUrl ?: periodBaseUrl ?: mpdBaseUrl ?: manifestUrl
                val resolvedRepBase = if (repDirectBase.isEmpty()) effectiveBase else UbitHlsEngine.resolveUrl(repDirectBase, effectiveBase)

                var initUrl: String? = null
                val segs = ArrayList<String>()
                val tpl = repTemplate ?: adaptTemplate
                if (tpl != null) {
                    val timeline = if (repTimeline.isNotEmpty()) repTimeline else adaptTimeline
                    val initTpl = tpl["initialization"] ?: repInitSource
                    if (!initTpl.isNullOrBlank()) {
                        initUrl = UbitHlsEngine.resolveUrl(fillTemplate(initTpl, repId, bw, null, null), resolvedRepBase)
                    }
                    val mediaTpl = tpl["media"]
                    if (!mediaTpl.isNullOrBlank()) {
                        val startNum = tpl["startNumber"]?.toIntOrNull() ?: 1
                        val timescale = tpl["timescale"]?.toDoubleOrNull() ?: 1.0
                        if (timeline.isNotEmpty()) {
                            var curTime = 0L
                            var num = startNum
                            for (entry in timeline) {
                                if (entry.t != null) curTime = entry.t
                                val count = (entry.r + 1).coerceAtLeast(0)
                                for (k in 0 until count) {
                                    segs.add(UbitHlsEngine.resolveUrl(fillTemplate(mediaTpl, repId, bw, num, curTime), resolvedRepBase))
                                    curTime += entry.d
                                    num++
                                }
                            }
                        } else {
                            val durAttr = tpl["duration"]?.toDoubleOrNull()
                            val totalDur = mediaDuration
                            if (durAttr != null && durAttr > 0.0 && totalDur != null && totalDur > 0.0) {
                                val segDurSec = durAttr / timescale.coerceAtLeast(1.0)
                                val count = ceil(totalDur / segDurSec).toInt().coerceIn(1, 10000)
                                for (i in 0 until count) {
                                    val tVal = (i.toDouble() * durAttr).toLong()
                                    segs.add(UbitHlsEngine.resolveUrl(fillTemplate(mediaTpl, repId, bw, startNum + i, tVal), resolvedRepBase))
                                }
                            }
                        }
                    }
                } else if (repDirectBase.isNotEmpty()) {
                    repInitSource?.let { initUrl = UbitHlsEngine.resolveUrl(it, resolvedRepBase) }
                }

                reps.add(
                    UbitDashRepresentation(
                        id = repId,
                        mimeType = mime,
                        codecs = codecs,
                        width = width,
                        height = height,
                        fps = fps,
                        bandwidth = bw,
                        baseUrl = if (segs.isEmpty()) resolvedRepBase else null,
                        initUrl = initUrl,
                        segmentUrls = segs,
                        isLive = isDynamic,
                    )
                )
            }
        }

        parser.parse(InputSource(StringReader(xml)), handler)
        return reps
    }

    fun downloadRepresentation(
        client: UbitHttpClient,
        mpdUrl: String,
        formatId: String,
        cachedReps: List<UbitDashRepresentation>,
        destination: File,
        headers: Map<String, String> = emptyMap(),
        connections: Int = 6,
        priorBytes: Long = 0L,
    ) {
        val repId = formatId.removePrefix("dash-")
        val reps = cachedReps.ifEmpty {
            val (xml, finalUrl) = client.fetchText(mpdUrl, headers)
            parseMpd(xml, finalUrl)
        }
        val rep = reps.firstOrNull { it.id == repId }
            ?: throw IOException("DASH representation '$repId' not found in manifest")
        if (rep.isLive) {
            throw IOException("Live streams can't be saved as memes; share a clip or finished video link instead.")
        }

        if (rep.segmentUrls.isEmpty() && !rep.baseUrl.isNullOrBlank()) {
            client.downloadFile(rep.baseUrl, destination, headers, connections = connections)
            return
        }
        if (rep.segmentUrls.isEmpty()) {
            throw IOException("DASH representation '$repId' had no segment URLs")
        }

        val totalBytes = AtomicLong(priorBytes)
        val initBytes = rep.initUrl?.let { iu ->
            val r = client.fetch(iu, "GET", headers)
            if (r.status !in 200..299) throw IOException("DASH init segment HTTP ${r.status}")
            if (totalBytes.addAndGet(r.body.size.toLong()) > client.maxDownloadBytes) {
                throw IOException("Media download exceeded maximum limit (${client.maxDownloadBytes / (1024 * 1024)} MiB)")
            }
            r.body
        }

        val segDir = File(destination.parentFile, "${destination.name}_dash_segs")
        segDir.mkdirs()
        try {
            val workers = minOf(connections.coerceIn(1, 8), rep.segmentUrls.size)
            val pool = Executors.newFixedThreadPool(workers)
            try {
                val tasks = rep.segmentUrls.mapIndexed { idx, su ->
                    Callable {
                        val r = client.fetch(su, "GET", headers, maxResponseBytes = client.maxDownloadBytes)
                        if (r.status == 403 || r.status == 410) throw UbitStaleLinkException(r.status)
                        if (r.status !in 200..299) throw IOException("DASH segment HTTP ${r.status}")
                        if (totalBytes.addAndGet(r.body.size.toLong()) > client.maxDownloadBytes) {
                            throw IOException("Media download exceeded maximum limit (${client.maxDownloadBytes / (1024 * 1024)} MiB)")
                        }
                        File(segDir, "%06d.seg".format(Locale.US, idx)).writeBytes(r.body)
                    }
                }
                for (f in tasks.map { pool.submit(it) }) {
                    try {
                        f.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw (e.cause ?: e)
                    }
                }
            } finally {
                pool.shutdownNow()
            }

            val partFile = File(destination.parentFile, "${destination.name}.part")
            BufferedOutputStream(FileOutputStream(partFile), 65536).use { out ->
                if (initBytes != null && initBytes.isNotEmpty()) out.write(initBytes)
                for (i in rep.segmentUrls.indices) {
                    val sb = File(segDir, "%06d.seg".format(Locale.US, i)).readBytes()
                    if (sb.isNotEmpty()) out.write(sb)
                }
                out.flush()
            }
            if (destination.exists()) destination.delete()
            if (!partFile.renameTo(destination)) {
                partFile.copyTo(destination, overwrite = true)
                partFile.delete()
            }
        } finally {
            segDir.deleteRecursively()
        }
    }

    private fun fillTemplate(tpl: String, repId: String, bandwidth: Long, number: Int?, time: Long?): String {
        var s = tpl
            .replace("$$", "\u0001")
            .replace("\$RepresentationID$", repId)
            .replace("\$Bandwidth$", bandwidth.toString())
        if (number != null) s = replacePaddedToken(s, "Number", number.toLong())
        if (time != null) s = replacePaddedToken(s, "Time", time)
        return s.replace("\u0001", "$")
    }

    private fun replacePaddedToken(input: String, token: String, value: Long): String {
        val re = Regex("""\$${Regex.escape(token)}(?:%0(\d+)d)?\$""")
        return re.replace(input) { m ->
            val width = m.groupValues.getOrNull(1)?.toIntOrNull()
            if (width != null && width > 0) "%0${width}d".format(Locale.US, value) else value.toString()
        }
    }

    private fun parseIsoDuration(s: String): Double? {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""").find(s) ?: return null
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val total = h * 3600.0 + min * 60.0 + sec
        return total.takeIf { it > 0.0 }
    }
}
