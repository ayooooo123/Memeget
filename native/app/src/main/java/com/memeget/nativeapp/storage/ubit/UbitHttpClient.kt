package com.memeget.nativeapp.storage.ubit

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// Ephemeral HTTP + work-stealing byte-range engine built over Memeget's OkHttpClient, eager to swallow every stream whole.

internal class UbitStaleLinkException(val code: Int) : IOException("Stream link expired (HTTP $code)")

internal data class UbitHttpResponse(
    val status: Int,
    val finalUrl: String,
    val contentType: String?,
    val contentLength: Long,
    val acceptRanges: Boolean,
    val body: ByteArray,
) {
    fun text(): String = body.toString(Charsets.UTF_8)
}

internal class UbitHttpClient(
    val okHttp: OkHttpClient,
    val userAgent: String = DEFAULT_BROWSER_UA,
    val maxDownloadBytes: Long = DEFAULT_MAX_BYTES,
    initialCookies: Map<String, Map<String, String>> = emptyMap(),
) {
    // Per-extraction transient cookie jar so page-handshake tokens (TikTok ttwid/tt_chain_token, IG, etc.) ride onto CDN fetches.
    private val hostCookies = ConcurrentHashMap<String, MutableMap<String, String>>().apply {
        for ((dom, kvs) in initialCookies) {
            put(dom, ConcurrentHashMap(kvs))
        }
    }

    fun snapshotCookies(): Map<String, Map<String, String>> =
        hostCookies.mapValues { (_, v) -> HashMap(v) }

    private fun recordCookies(requestedUrl: String, responseUrl: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        val keys = linkedSetOf<String>()
        requestedUrl.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT)?.let { keys.add(rootDomain(it)) }
        responseUrl.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT)?.let { keys.add(rootDomain(it)) }
        for (domainKey in keys) {
            val bucket = hostCookies.computeIfAbsent(domainKey) { ConcurrentHashMap() }
            for (raw in setCookieHeaders) {
                val pair = raw.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = pair.substring(0, eq).trim()
                    val v = pair.substring(eq + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) bucket[k] = v
                }
            }
        }
    }

    fun cookieHeaderFor(url: String): String? {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) ?: return null
        val bucket = hostCookies[rootDomain(host)] ?: return null
        if (bucket.isEmpty()) return null
        return bucket.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun allCookiesHeader(): String? {
        val merged = LinkedHashMap<String, String>()
        for (bucket in hostCookies.values) {
            for ((k, v) in bucket) merged[k] = v
        }
        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun rootDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts.last()}" else host
    }

    private fun buildRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        includeCookies: Boolean = true,
    ): Request {
        val builder = Request.Builder().url(url)
        var hasUa = false
        var hasCookie = false
        for ((k, v) in headers) {
            if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
            if (k.equals("Cookie", ignoreCase = true)) hasCookie = true
            builder.header(k, v)
        }
        if (!hasUa) builder.header("User-Agent", userAgent)
        if (includeCookies && !hasCookie) {
            cookieHeaderFor(url)?.let { builder.header("Cookie", it) }
        }
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/octet-stream"
        when (method.uppercase(Locale.ROOT)) {
            "HEAD" -> builder.head()
            "POST" -> builder.post((body ?: ByteArray(0)).toRequestBody(contentType.toMediaType()))
            else -> builder.get()
        }
        return builder.build()
    }

    fun fetch(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        includeCookies: Boolean = true,
        maxResponseBytes: Long = 32L * 1024 * 1024,
    ): UbitHttpResponse {
        val req = buildRequest(url, method, headers, body, includeCookies)
        okHttp.newCall(req).execute().use { resp ->
            recordCookies(url, resp.request.url.toString(), resp.headers("Set-Cookie"))
            val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()
            val cl = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val acceptRanges = resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            if (method.equals("HEAD", ignoreCase = true)) {
                return UbitHttpResponse(resp.code, resp.request.url.toString(), ct, cl, acceptRanges, ByteArray(0))
            }
            val respBody = resp.body
            val bytes = if (respBody == null) {
                ByteArray(0)
            } else {
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var total = 0L
                respBody.byteStream().use { inp ->
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        total += n
                        if (total > maxResponseBytes) {
                            throw IOException("Response from $url exceeded safety limit ($maxResponseBytes bytes)")
                        }
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            }
            return UbitHttpResponse(resp.code, resp.request.url.toString(), ct, cl, acceptRanges, bytes)
        }
    }

    fun fetchText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        includeCookies: Boolean = true,
    ): Pair<String, String> {
        val resp = fetch(url, "GET", headers, null, includeCookies)
        if (resp.status !in 200..299) {
            throw IOException("HTTP ${resp.status} fetching $url")
        }
        return resp.text() to resp.finalUrl
    }

    fun fetchJson(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        includeCookies: Boolean = true,
    ): Any? {
        val merged = LinkedHashMap(headers)
        if (merged.keys.none { it.equals("Accept", ignoreCase = true) }) {
            merged["Accept"] = "application/json, text/plain, */*"
        }
        val resp = fetch(url, method, merged, body, includeCookies)
        if (resp.status !in 200..299) {
            throw IOException("HTTP ${resp.status} fetching JSON from $url")
        }
        val txt = resp.text().trim()
        if (txt.isEmpty()) return null
        return JSONTokener(txt).nextValue()
    }

    fun fetchJsonObject(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        includeCookies: Boolean = true,
    ): JSONObject? = fetchJson(url, method, headers, body, includeCookies) as? JSONObject

    fun fetchJsonArray(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        includeCookies: Boolean = true,
    ): JSONArray? = fetchJson(url, method, headers, body, includeCookies) as? JSONArray

    fun fetchRange(
        url: String,
        start: Long,
        endInclusive: Long,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        val merged = LinkedHashMap(headers)
        merged["Range"] = "bytes=$start-$endInclusive"
        val expectedMax = (endInclusive - start + 1L).coerceAtLeast(1024L) + 65536L
        val resp = fetch(url, "GET", merged, null, true, maxResponseBytes = minOf(maxDownloadBytes, expectedMax))
        if (resp.status == 403 || resp.status == 410) throw UbitStaleLinkException(resp.status)
        if (resp.status != 206 && resp.status != 200) {
            throw IOException("HTTP ${resp.status} on byte range $start-$endInclusive")
        }
        return if (resp.status == 200 && start > 0 && resp.body.size > start) {
            val endIdx = minOf(resp.body.size, (endInclusive + 1L).toInt())
            resp.body.copyOfRange(start.toInt(), endIdx)
        } else {
            resp.body
        }
    }

    // Downloads a stream to destination, splitting across parallel HTTP Range connections when supported,
    // and enforcing maxDownloadBytes across every byte written.
    fun downloadFile(
        url: String,
        destination: File,
        headers: Map<String, String> = emptyMap(),
        knownSize: Long? = null,
        connections: Int = 6,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): String? {
        destination.parentFile?.mkdirs()

        // Probe size & range support via a tiny 0-0 byte range or HEAD when size is large enough to parallelize.
        var totalSize = knownSize ?: -1L
        var supportsRanges = false
        var detectedMime: String? = null

        if (connections > 1) {
            runCatching {
                val probeHeaders = LinkedHashMap(headers).apply { put("Range", "bytes=0-0") }
                val req = buildRequest(url, "GET", probeHeaders, null, true)
                okHttp.newCall(req).execute().use { resp ->
                    recordCookies(url, resp.request.url.toString(), resp.headers("Set-Cookie"))
                    if (resp.code == 403 || resp.code == 410) throw UbitStaleLinkException(resp.code)
                    detectedMime = resp.header("Content-Type")?.substringBefore(';')?.trim()
                    if (resp.code == 206) {
                        supportsRanges = true
                        val cr = resp.header("Content-Range").orEmpty() // bytes 0-0/123456
                        val slash = cr.lastIndexOf('/')
                        if (slash > 0) {
                            val totalPart = cr.substring(slash + 1).trim()
                            totalPart.toLongOrNull()?.let { totalSize = it }
                        }
                    }
                }
            }.onFailure { err ->
                if (err is UbitStaleLinkException) throw err
            }
        }

        if (totalSize > maxDownloadBytes) {
            throw IOException("Media download exceeded maximum limit (${maxDownloadBytes / (1024 * 1024)} MiB)")
        }

        val minChunk = 512L * 1024L
        if (supportsRanges && totalSize >= minChunk * 2L && connections > 1) {
            val workerCount = minOf(connections.coerceIn(1, 8), (totalSize / minChunk).toInt().coerceAtLeast(1))
            val partFile = File(destination.parentFile, "${destination.name}.part")
            try {
                RandomAccessFile(partFile, "rw").use { raf -> raf.setLength(totalSize) }
                val pieceSize = (totalSize + workerCount - 1L) / workerCount
                val ranges = ArrayList<UbitByteRange>(workerCount)
                var cursor = 0L
                while (cursor < totalSize) {
                    val end = minOf(totalSize - 1L, cursor + pieceSize - 1L)
                    ranges.add(UbitByteRange(cursor, end))
                    cursor = end + 1L
                }

                val pool = Executors.newFixedThreadPool(ranges.size)
                val writtenTotal = AtomicLong(0L)
                try {
                    val futures = ranges.map { range ->
                        pool.submit(Callable {
                            val chunkHeaders = LinkedHashMap(headers).apply {
                                put("Range", "bytes=${range.start}-${range.end}")
                            }
                            val req = buildRequest(url, "GET", chunkHeaders, null, true)
                            okHttp.newCall(req).execute().use { resp ->
                                if (resp.code == 403 || resp.code == 410) throw UbitStaleLinkException(resp.code)
                                if (resp.code != 206) throw IOException("Server dropped range support mid-stream (${resp.code})")
                                val body = resp.body ?: throw IOException("Empty range body")
                                RandomAccessFile(partFile, "rw").use { raf ->
                                    raf.seek(range.start)
                                    val buf = ByteArray(32768)
                                    var remaining = range.length
                                    body.byteStream().use { inp ->
                                        while (remaining > 0L) {
                                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                            val n = inp.read(buf, 0, toRead)
                                            if (n <= 0) break
                                            val tot = writtenTotal.addAndGet(n.toLong())
                                            if (tot > maxDownloadBytes) {
                                                throw IOException("Media download exceeded maximum limit (${maxDownloadBytes / (1024 * 1024)} MiB)")
                                            }
                                            raf.write(buf, 0, n)
                                            remaining -= n.toLong()
                                            onProgress?.invoke(tot, totalSize)
                                        }
                                    }
                                    if (remaining != 0L) {
                                        throw IOException("Truncated range response (${range.length - remaining}/${range.length} bytes)")
                                    }
                                }
                            }
                        })
                    }
                    for (f in futures) {
                        try {
                            f.get()
                        } catch (e: java.util.concurrent.ExecutionException) {
                            throw (e.cause ?: e)
                        }
                    }
                } finally {
                    pool.shutdownNow()
                }
                if (destination.exists()) destination.delete()
                if (!partFile.renameTo(destination)) {
                    partFile.copyTo(destination, overwrite = true)
                    partFile.delete()
                }
                return detectedMime
            } catch (stale: UbitStaleLinkException) {
                partFile.delete()
                throw stale
            } catch (limitErr: IOException) {
                partFile.delete()
                if (limitErr.message?.contains("maximum limit") == true) throw limitErr
                // Fall through to single-connection stream on quirky CDN range failures.
            }
        }

        // Single-connection streaming fallback
        val req = buildRequest(url, "GET", headers, null, true)
        okHttp.newCall(req).execute().use { resp ->
            recordCookies(url, resp.request.url.toString(), resp.headers("Set-Cookie"))
            if (resp.code == 403 || resp.code == 410) throw UbitStaleLinkException(resp.code)
            if (!resp.isSuccessful) {
                throw IOException("Download failed (HTTP ${resp.code}).")
            }
            val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()
            val body = resp.body ?: throw IOException("Download body is empty")
            val expectedLen = body.contentLength().takeIf { it > 0L } ?: totalSize
            var total = 0L
            val partFile = File(destination.parentFile, "${destination.name}.part")
            try {
                body.byteStream().use { inp ->
                    FileOutputStream(partFile).use { out ->
                        val buf = ByteArray(32768)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            total += n
                            if (total > maxDownloadBytes) {
                                throw IOException("Media download exceeded maximum limit (${maxDownloadBytes / (1024 * 1024)} MiB)")
                            }
                            out.write(buf, 0, n)
                            onProgress?.invoke(total, expectedLen)
                        }
                    }
                }
                if (destination.exists()) destination.delete()
                if (!partFile.renameTo(destination)) {
                    partFile.copyTo(destination, overwrite = true)
                    partFile.delete()
                }
                return ct ?: detectedMime
            } catch (e: Throwable) {
                partFile.delete()
                throw e
            }
        }
    }

    companion object {
        const val DEFAULT_BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val DEFAULT_MAX_BYTES = 350L * 1024L * 1024L
    }
}
