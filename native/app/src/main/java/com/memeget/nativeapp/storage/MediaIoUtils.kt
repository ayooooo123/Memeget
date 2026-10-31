package com.memeget.nativeapp.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

internal object MediaIoUtils {

    const val MIME_JPEG = "image/jpeg"
    const val MIME_PNG = "image/png"
    const val MIME_MP4 = "video/mp4"
    const val MIME_OCTET = "application/octet-stream"

    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    val VIDEO_EXTS = setOf("mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp")

    private val VIDEO_MIME = mapOf(
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "m4v" to "video/x-m4v",
        "3gp" to "video/3gpp"
    )

    private val IMAGE_MIME = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif"
    )

    fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    fun kindOf(name: String): String? {
        val ext = extOf(name)
        return when {
            IMAGE_EXTS.contains(ext) -> "image"
            VIDEO_EXTS.contains(ext) -> "video"
            else -> null
        }
    }

    fun mimeForName(name: String): String {
        val ext = extOf(name)
        return IMAGE_MIME[ext] ?: VIDEO_MIME[ext] ?: if (kindOf(name) == "video") MIME_MP4 else MIME_JPEG
    }

    fun extForMime(mime: String): String {
        val clean = mime.lowercase(Locale.ROOT).split(';')[0].trim()
        for ((ext, m) in VIDEO_MIME) if (m == clean) return ext
        for ((ext, m) in IMAGE_MIME) if (m == clean) return ext
        return if (clean.startsWith("video/")) "mp4" else "jpg"
    }

    fun computeSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeSha256(file: File): String {
        return file.inputStream().use { computeSha256(it) }
    }

    data class SafDocument(
        val documentId: String,
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val lastModified: Long,
        val size: Long,
        val kind: String
    )

    fun listFilesFast(context: Context, treeUri: Uri, mediaOnly: Boolean): List<SafDocument> {
        val results = mutableListOf<SafDocument>()
        val contentResolver = context.contentResolver

        val parentDocId = try {
            if (DocumentsContract.isDocumentUri(context, treeUri)) {
                DocumentsContract.getDocumentId(treeUri)
            } else {
                DocumentsContract.getTreeDocumentId(treeUri)
            }
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(childrenUri, projection, null, null, null)
            if (cursor != null) {
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val docId = if (idIdx >= 0) cursor.getString(idIdx) else null ?: continue
                    val displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null ?: continue
                    val mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null ?: ""
                    val lastModified = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR || displayName.startsWith(".")) {
                        continue
                    }

                    val kind = kindOf(displayName) ?: when {
                        mimeType.startsWith("image/") -> "image"
                        mimeType.startsWith("video/") -> "video"
                        else -> null
                    }
                    if (mediaOnly && kind == null) continue

                    val itemUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    results.add(
                        SafDocument(
                            documentId = docId,
                            uri = itemUri,
                            displayName = displayName,
                            mimeType = mimeType,
                            lastModified = lastModified,
                            size = size,
                            kind = kind ?: "file"
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return listFilesFallback(context, treeUri, childrenUri, mediaOnly)
        } finally {
            cursor?.close()
        }
        return results
    }

    private fun listFilesFallback(
        context: Context,
        treeUri: Uri,
        childrenUri: Uri,
        mediaOnly: Boolean,
    ): List<SafDocument> {
        val results = mutableListOf<SafDocument>()
        // Query IDs only when a provider rejects the full projection. Retain the
        // selected child directory; fromTreeUri would enumerate the grant root.
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val file = DocumentFile.fromSingleUri(context, uri) ?: continue
                    val mimeType = file.type.orEmpty()
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = file.name ?: continue
                    if (name.startsWith(".")) continue
                    val kind = kindOf(name) ?: when {
                        mimeType.startsWith("video/") -> "video"
                        mimeType.startsWith("image/") -> "image"
                        else -> null
                    }
                    if (mediaOnly && kind == null) continue
                    results.add(
                        SafDocument(
                            documentId = docId,
                            uri = uri,
                            displayName = name,
                            mimeType = mimeType.ifBlank { mimeForName(name) },
                            lastModified = file.lastModified(),
                            size = file.length(),
                            kind = kind ?: "file",
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return results
    }

    fun findChildSafDocument(context: Context, parentFolderUri: Uri, displayName: String): Uri? {
        val parentDocId = try {
            if (DocumentsContract.isDocumentUri(context, parentFolderUri)) {
                DocumentsContract.getDocumentId(parentFolderUri)
            } else {
                DocumentsContract.getTreeDocumentId(parentFolderUri)
            }
        } catch (_: Exception) {
            null
        }

        if (parentDocId != null) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentFolderUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    if (name.equals(displayName, ignoreCase = true)) {
                        val docId = if (idIdx >= 0) cursor.getString(idIdx) else null
                        if (docId != null) {
                            return DocumentsContract.buildDocumentUriUsingTree(parentFolderUri, docId)
                        }
                    }
                }
            }
        }

        val dir = DocumentFile.fromTreeUri(context, parentFolderUri) ?: return null
        return dir.findFile(displayName)?.uri
    }

    fun ensureDirectory(context: Context, parentFolderUri: Uri, dirName: String): Uri? {
        val existing = findChildSafDocument(context, parentFolderUri, dirName)
        if (existing != null) return existing

        val parentDocId = try {
            if (DocumentsContract.isDocumentUri(context, parentFolderUri)) {
                DocumentsContract.getDocumentId(parentFolderUri)
            } else {
                DocumentsContract.getTreeDocumentId(parentFolderUri)
            }
        } catch (_: Exception) {
            null
        }

        if (parentDocId != null) {
            try {
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(parentFolderUri, parentDocId)
                val created = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    dirName
                )
                if (created != null) return created
            } catch (_: Exception) {}
        }

        val dir = DocumentFile.fromTreeUri(context, parentFolderUri) ?: return null
        return dir.createDirectory(dirName)?.uri
    }

    fun createSafDocument(
        context: Context,
        parentFolderUri: Uri,
        displayName: String,
        mimeType: String
    ): Uri? {
        val parentDocId = try {
            if (DocumentsContract.isDocumentUri(context, parentFolderUri)) {
                DocumentsContract.getDocumentId(parentFolderUri)
            } else {
                DocumentsContract.getTreeDocumentId(parentFolderUri)
            }
        } catch (_: Exception) {
            null
        }

        if (parentDocId != null) {
            try {
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(parentFolderUri, parentDocId)
                val created = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    mimeType,
                    displayName
                )
                if (created != null) return created
            } catch (_: Exception) {}
        }

        val dir = DocumentFile.fromTreeUri(context, parentFolderUri) ?: return null
        val baseName = if (displayName.contains(".")) {
            displayName.substringBeforeLast(".")
        } else {
            displayName
        }
        return dir.createFile(mimeType, baseName)?.uri
    }

    fun getDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.lastPathSegment?.ifBlank { "media.bin" } ?: "media.bin"
        }
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        val segment = uri.lastPathSegment.orEmpty()
        return if (segment.contains(":")) segment.substringAfterLast(":") else segment.ifBlank { "media.bin" }
    }

    fun copyUriToTarget(
        context: Context,
        sourceUri: Uri,
        targetUri: Uri
    ) {
        val resolver = context.contentResolver
        resolver.openInputStream(sourceUri)?.use { input ->
            resolver.openOutputStream(targetUri)?.use { output ->
                input.copyTo(output)
            } ?: throw IllegalStateException("Could not open output stream for $targetUri")
        } ?: throw IllegalStateException("Could not open input stream for $sourceUri")
    }

    fun copyUriToFile(
        context: Context,
        sourceUri: Uri,
        destFile: File,
        maxBytes: Long = Long.MAX_VALUE,
    ) {
        destFile.parentFile?.mkdirs()
        var failed = false
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    val buf = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) {
                            failed = true
                            throw java.io.IOException("Input exceeds maximum allowed size (${maxBytes / (1024 * 1024)} MiB)")
                        }
                        output.write(buf, 0, n)
                    }
                }
            } ?: throw IllegalStateException("Could not open input stream for $sourceUri")
        } catch (t: Throwable) {
            if (failed) destFile.delete()
            throw t
        }
    }
    fun shareableUriForFile(context: Context, file: File): Uri {
        val authority = "${context.packageName}.files"
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun saveToDownloads(
        context: Context,
        sourceUri: Uri,
        name: String,
        mimeType: String
    ): Uri {
        val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "export.bin" }
        val mime = mimeType.ifBlank { MIME_OCTET }
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                } ?: throw IllegalStateException("Could not open MediaStore output stream")
            } ?: throw IllegalStateException("Could not open input stream for $sourceUri")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = File(dir, safe)
            resolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Could not open input stream for $sourceUri")
            return Uri.fromFile(dest)
        }
    }

    /**
     * Width over height of the picture as shown upright, read from the file header without decoding pixels; null when
     * the file can't be read. Video sizes come from the container, with its rotation applied.
     */
    fun uprightAspect(context: Context, uri: Uri, video: Boolean): Float? {
        if (video) {
            val retriever = android.media.MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                when {
                    width <= 0 || height <= 0 -> null
                    rotation % 180 != 0 -> height.toFloat() / width
                    else -> width.toFloat() / height
                }
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Only JPEG and HEIF carry an orientation the decoder doesn't apply; reading EXIF is a second open.
        val mime = bounds.outMimeType.orEmpty()
        val quarterTurned = (mime == "image/jpeg" || mime == "image/heif" || mime == "image/heic") && try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
                    else -> false
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
        return if (quarterTurned) bounds.outHeight.toFloat() / bounds.outWidth else bounds.outWidth.toFloat() / bounds.outHeight
    }

    /** EXIF-upright bitmap; [sampleSize] > 1 decodes a power-of-two subsample. */
    fun decodeUprightBitmap(context: Context, uri: Uri, sampleSize: Int = 1): Bitmap? {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: return null

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }

    fun writeUprightJpeg(context: Context, sourceUri: Uri, destinationFile: File): Boolean {
        destinationFile.parentFile?.mkdirs()
        val bitmap = decodeUprightBitmap(context, sourceUri) ?: return false
        return try {
            destinationFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Width of the picture the image model and OCR read: the original app's `toJpeg` default. */
    const val MODEL_INPUT_WIDTH = 768
    /** Caps upscaled thin strips; only pictures taller than ~10.7:1 deviate from the original app. */
    private const val MODEL_INPUT_MAX_HEIGHT = 8192

    /**
     * Writes the picture the image model and OCR read for one frame the way the original app made it
     * (expo-image-manipulator resize to 768 px wide, aspect kept, JPEG quality 90): its exports and
     * teachings were all made from these, so vectors stay comparable. It also turns formats the
     * native decoder (OpenCV) cannot read, such as WebP, GIF and HEIF, into JPEG.
     */
    fun writeModelInputJpeg(context: Context, sourceUri: Uri, destinationFile: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported or damaged image")
        // Huge sources decode subsampled but stay at least four times the model width.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MODEL_INPUT_WIDTH * 4) sample *= 2
        val decoded = decodeUprightBitmap(context, sourceUri, sample) ?: throw IOException("Unsupported or damaged image")
        var width = MODEL_INPUT_WIDTH
        var height = maxOf(1, (MODEL_INPUT_WIDTH.toFloat() * decoded.height / decoded.width).toInt())
        if (height > MODEL_INPUT_MAX_HEIGHT) {
            width = maxOf(1, (MODEL_INPUT_MAX_HEIGHT.toFloat() * decoded.width / decoded.height).toInt())
            height = MODEL_INPUT_MAX_HEIGHT
        }
        val scaled = if (decoded.width == width && decoded.height == height) decoded else Bitmap.createScaledBitmap(decoded, width, height, true)
        try {
            destinationFile.parentFile?.mkdirs()
            val written = destinationFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!written) throw IOException("Could not write the image the models read")
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /**
     * Slices up to 3 un-squashed square crops along the long shaft of tall screenshots or wide multi-panel strips
     * (`aspect >= 1.65`), leaving near-square frames untouched so `recipe-et0.9.2` teaching vectors stay byte-faithful.
     */
    fun writeAspectSquareCrops(inputJpeg: File, outDir: File): List<File> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(inputJpeg.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return emptyList()
        val shortSide = minOf(w, h)
        val longSide = maxOf(w, h)
        if (shortSide < 96 || longSide.toFloat() / shortSide.toFloat() < 1.65f) return emptyList()

        val bitmap = BitmapFactory.decodeFile(inputJpeg.absolutePath) ?: return emptyList()
        val crops = ArrayList<File>(3)
        try {
            outDir.mkdirs()
            val side = minOf(bitmap.width, bitmap.height)
            val span = maxOf(bitmap.width, bitmap.height) - side
            val offsets = if (longSide.toFloat() / shortSide.toFloat() >= 2.35f) {
                listOf(0, span / 2, span).distinct()
            } else {
                listOf(0, span).distinct()
            }
            val vertical = bitmap.height > bitmap.width
            for ((idx, off) in offsets.withIndex()) {
                val x = if (vertical) 0 else off
                val y = if (vertical) off else 0
                val sub = Bitmap.createBitmap(bitmap, x, y, side, side)
                try {
                    val dest = File(outDir, "${inputJpeg.nameWithoutExtension}_crop$idx.jpg")
                    if (dest.outputStream().use { sub.compress(Bitmap.CompressFormat.JPEG, 90, it) }) {
                        crops += dest
                    }
                } finally {
                    if (sub !== bitmap) sub.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
        return crops
    }

    data class PanelSubCropOutcome(
        val panelCount: Int,
        val hasPanelGutter: Boolean,
        val cropFiles: List<File>,
    )

    /**
     * Detects 2-panel, 3-panel, and 2x2 4-panel comic gutters (Drake, Two Buttons, Gru, Wojak grids),
     * elongated strips, and MINER cluttered sub-crops so square multi-panel memes stop collapsing into one muddy vector.
     */
    fun writePanelAndSubCrops(inputJpeg: File, outDir: File, ocrBoxCount: Int = 0): PanelSubCropOutcome {
        val bitmap = BitmapFactory.decodeFile(inputJpeg.absolutePath) ?: return PanelSubCropOutcome(1, false, emptyList())
        try {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 96 || h < 96) return PanelSubCropOutcome(1, false, emptyList())

            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            val rgb = ByteArray(w * h * 3)
            var p = 0
            for (px in argb) {
                rgb[p++] = ((px shr 16) and 0xFF).toByte()
                rgb[p++] = ((px shr 8) and 0xFF).toByte()
                rgb[p++] = (px and 0xFF).toByte()
            }

            val nativePacked = runCatching {
                com.memeget.nativeapp.inference.NativeBridge.nativeDetectPanelCrops(rgb, w, h, 3, ocrBoxCount, true)
            }.getOrNull()

            val panelCount: Int
            val hasGutter: Boolean
            val rects = ArrayList<IntArray>(4)
            if (nativePacked != null && nativePacked.size >= 4) {
                panelCount = nativePacked[0]
                hasGutter = nativePacked[1] != 0 || nativePacked[2] != 0
                var i = 4
                while (i + 3 < nativePacked.size && rects.size < 4) {
                    rects += intArrayOf(nativePacked[i], nativePacked[i + 1], nativePacked[i + 2], nativePacked[i + 3])
                    i += 4
                }
            } else {
                val fallback = detectPanelRectsKotlin(rgb, w, h, ocrBoxCount)
                panelCount = fallback.first
                hasGutter = fallback.second
                rects += fallback.third
            }

            if (rects.isEmpty()) return PanelSubCropOutcome(panelCount, hasGutter, emptyList())
            outDir.mkdirs()
            val crops = ArrayList<File>(rects.size)
            for ((idx, r) in rects.withIndex()) {
                val cx = r[0].coerceIn(0, w - 1)
                val cy = r[1].coerceIn(0, h - 1)
                val cw = r[2].coerceIn(1, w - cx)
                val ch = r[3].coerceIn(1, h - cy)
                if (cw < 64 || ch < 64) continue
                val sub = Bitmap.createBitmap(bitmap, cx, cy, cw, ch)
                try {
                    val dest = File(outDir, "${inputJpeg.nameWithoutExtension}_panel$idx.jpg")
                    if (dest.outputStream().use { sub.compress(Bitmap.CompressFormat.JPEG, 90, it) }) {
                        crops += dest
                    }
                } finally {
                    if (sub !== bitmap) sub.recycle()
                }
            }
            return PanelSubCropOutcome(panelCount, hasGutter, crops)
        } finally {
            bitmap.recycle()
        }
    }

    private fun detectPanelRectsKotlin(
        rgb: ByteArray,
        width: Int,
        height: Int,
        ocrBoxCount: Int,
    ): Triple<Int, Boolean, List<IntArray>> {
        fun gutterSplits(horizontal: Boolean): List<Int> {
            val primary = if (horizontal) height else width
            val cross = if (horizontal) width else height
            if (primary < 160 || cross < 160) return emptyList()
            val start = (primary * 0.22f).toInt()
            val end = (primary * 0.78f).toInt()
            val step = maxOf(1, cross / 96)
            val stds = FloatArray(primary) { 999f }
            val means = FloatArray(primary) { 128f }
            val cStart = (cross * 0.04f).toInt()
            val cEnd = (cross * 0.96f).toInt()
            for (idx in maxOf(0, start - 10)..minOf(primary - 1, end + 10)) {
                var sum = 0.0
                var sumSq = 0.0
                var n = 0
                var c = cStart
                while (c < cEnd) {
                    val x = if (horizontal) c else idx
                    val y = if (horizontal) idx else c
                    val off = (y * width + x) * 3
                    val l = 0.299 * (rgb[off].toInt() and 0xFF) +
                        0.587 * (rgb[off + 1].toInt() and 0xFF) +
                        0.114 * (rgb[off + 2].toInt() and 0xFF)
                    sum += l
                    sumSq += l * l
                    n++
                    c += step
                }
                if (n >= 8) {
                    val mean = sum / n
                    val variance = maxOf(0.0, sumSq / n - mean * mean)
                    means[idx] = mean.toFloat()
                    stds[idx] = kotlin.math.sqrt(variance).toFloat()
                }
            }
            val cands = ArrayList<Pair<Int, Float>>()
            var p = start
            while (p <= end) {
                if (stds[p] <= 7.5f) {
                    val r0 = p
                    while (p <= end && stds[p] <= 8.5f) p++
                    val r1 = p - 1
                    val rw = r1 - r0 + 1
                    if (rw in 2..maxOf(24, primary / 14)) {
                        val mid = (r0 + r1) / 2
                        val bIdx = maxOf(0, r0 - 6)
                        val aIdx = minOf(primary - 1, r1 + 6)
                        val act = maxOf(stds[bIdx], stds[aIdx])
                        val jmp = maxOf(kotlin.math.abs(means[mid] - means[bIdx]), kotlin.math.abs(means[mid] - means[aIdx]))
                        if (act >= 14f || jmp >= 22f) cands += mid to (act + jmp)
                    }
                } else {
                    p++
                }
            }
            val chosen = ArrayList<Int>(2)
            val minGap = (primary * 0.22f).toInt()
            for ((center, _) in cands.sortedByDescending { it.second }) {
                if (chosen.none { kotlin.math.abs(it - center) < minGap }) {
                    chosen += center
                    if (chosen.size == 2) break
                }
            }
            return chosen.sorted()
        }

        val hSplits = gutterSplits(true)
        val vSplits = gutterSplits(false)
        if (hSplits.isNotEmpty() || vSplits.isNotEmpty()) {
            val ys = listOf(0) + hSplits + listOf(height)
            val xs = listOf(0) + vSplits + listOf(width)
            val rects = ArrayList<IntArray>(4)
            for (yi in 0 until ys.size - 1) {
                for (xi in 0 until xs.size - 1) {
                    if (rects.size >= 4) break
                    val pw = xs[xi + 1] - xs[xi]
                    val ph = ys[yi + 1] - ys[yi]
                    if (pw >= 64 && ph >= 64) rects += intArrayOf(xs[xi], ys[yi], pw, ph)
                }
            }
            if (rects.size >= 2) return Triple(rects.size, true, rects)
        }

        val shortSide = minOf(width, height)
        val longSide = maxOf(width, height)
        val aspect = longSide.toFloat() / shortSide.toFloat()
        if (aspect >= 1.65f) {
            val span = longSide - shortSide
            val offsets = if (aspect >= 2.35f) listOf(0, span / 2, span).distinct() else listOf(0, span).distinct()
            val vertical = height > width
            val rects = offsets.map { off ->
                if (vertical) intArrayOf(0, off, shortSide, shortSide) else intArrayOf(off, 0, shortSide, shortSide)
            }
            return Triple(rects.size, false, rects)
        }

        if (ocrBoxCount >= 3 && width >= 192 && height >= 192) {
            val cw = (width * 0.62f).toInt()
            val ch = (height * 0.62f).toInt()
            val rects = listOf(
                intArrayOf(0, 0, cw, ch),
                intArrayOf(width - cw, 0, cw, ch),
                intArrayOf(0, height - ch, cw, ch),
                intArrayOf(width - cw, height - ch, cw, ch),
            )
            return Triple(1, false, rects)
        }
        return Triple(1, false, emptyList())
    }
}
