package com.memeget.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal class PayloadTooLargeException(message: String) : IllegalArgumentException(message)
internal data class PreparedPayload(
    val stateText: String,
    val imagesBase64DataUris: List<String>,
    val sourceContentHash: String,
    val frameHash: String,
    val totalEstimatedBytes: Long,
    val textBytes: Long,
)

/** Snapshot, hash and decode the same bytes; fresh JPEGs carry no source metadata. */
internal object CloudPayloadPrep {
    const val MAX_REQUEST_BYTES = 1024L * 1024L
    const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
    private const val EDGE = 640

    suspend fun prepare(context: Context, meme: Meme, config: CloudConfig): PreparedPayload = withContext(Dispatchers.IO) {
        val (state, textBytes) = buildTextState(meme, config)
        val directory = File(context.cacheDir, "cloud_payload").apply { mkdirs() }
        val snapshot = File(directory, UUID.randomUUID().toString())
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = if (meme.uri.startsWith('/')) File(meme.uri).inputStream()
                else context.contentResolver.openInputStream(Uri.parse(meme.uri))
            requireNotNull(input) { "The source document is unavailable" }
            input.use { source ->
                snapshot.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > MAX_SOURCE_BYTES) throw PayloadTooLargeException("Source exceeds the 512 MiB preparation limit")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val hash = hex(digest.digest())
            require(meme.contentHash.isNotEmpty() && meme.contentHash == hash) { "The media changed or has not been indexed; re-index it before cloud analysis" }
            val wantsImages = config.consentVisualMedia && (config.provider != CloudProviderType.TYPESAFE_SYSTEMONE || config.captionEndpointEnabled)
            val images = ArrayList<String>(2)
            val frameDigest = MessageDigest.getInstance("SHA-256")
            var imageBytes = 0L
            if (wantsImages) {
                if (meme.kind == "video") {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(snapshot.absolutePath)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        require(width > 0 && height > 0) { "Cannot determine video dimensions safely" }
                        val scale = minOf(1.0, EDGE.toDouble() / maxOf(width, height))
                        val targetW = (width * scale).toInt().coerceAtLeast(1)
                        val targetH = (height * scale).toInt().coerceAtLeast(1)
                        val times = listOf(duration / 10, duration / 2).distinct().take(2)
                        for (millis in times) {
                            currentCoroutineContext().ensureActive()
                            val frame = if (Build.VERSION.SDK_INT >= 27) {
                                retriever.getScaledFrameAtTime(millis * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetW, targetH)
                            } else {
                                require(width.toLong() * height <= 2_000_000) { "This Android version cannot safely prepare large video frames" }
                                retriever.getFrameAtTime(millis * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            } ?: throw IOException("A selected video frame could not be decoded")
                            val jpeg = try { encode(frame) } finally { frame.recycle() }
                            frameDigest.update(jpeg)
                            images += "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
                            imageBytes += images.last().length
                        }
                    } finally { retriever.release() }
                } else {
                    val image = decodeUpright(snapshot)
                    val jpeg = try { encode(image) } finally { image.recycle() }
                    frameDigest.update(jpeg)
                    images += "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
                    imageBytes += images.last().length
                }
            }
            PreparedPayload(state, images, hash, if (images.isEmpty()) "" else hex(frameDigest.digest()), textBytes + imageBytes, textBytes)
        } finally { snapshot.delete() }
    }

    fun buildTextState(meme: Meme, config: CloudConfig): Pair<String, Long> {
        if (!config.consentTextState) return "" to 0L
        val text = buildString {
            if (meme.ocr.isNotBlank()) append("Untrusted OCR observation:\n").append(meme.ocr.take(6000)).append('\n')
            if (meme.caption.isNotBlank()) append("Unverified local caption:\n").append(meme.caption.take(2000)).append('\n')
            if (config.consentTranscript && meme.transcript.isNotBlank()) append("Untrusted transcript observation:\n").append(meme.transcript.take(4000))
        }.trim()
        return text to text.toByteArray(Charsets.UTF_8).size.toLong()
    }

    fun computeBytesSha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun validatePayloadSize(jsonString: String) {
        if (jsonString.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) throw PayloadTooLargeException("Complete request exceeds 1 MiB")
    }

    private fun decodeUpright(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unrecognized image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > EDGE * 2) sample *= 2
        val image = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Image decode failed")
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        if (matrix.isIdentity) return image
        return try { Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true) }
        finally { image.recycle() }
    }

    private fun encode(source: Bitmap): ByteArray {
        val scale = minOf(1.0, EDGE.toDouble() / maxOf(source.width, source.height))
        val output = Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(source, null, android.graphics.Rect(0, 0, output.width, output.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            val bytes = ByteArrayOutputStream()
            check(output.compress(Bitmap.CompressFormat.JPEG, 80, bytes)) { "JPEG preparation failed" }
            if (bytes.size() > 350 * 1024) throw PayloadTooLargeException("Selected frame exceeds the upload bound")
            return bytes.toByteArray()
        } finally { output.recycle() }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
