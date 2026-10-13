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
        destFile: File
    ) {
        destFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open input stream for $sourceUri")
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

    fun decodeUprightBitmap(context: Context, uri: Uri): Bitmap? {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
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
}
