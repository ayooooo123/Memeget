package com.memeget.nativeapp.storage

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class CameraRollAsset(
    val sourceKey: String,
    val uri: String,
    val name: String,
    val kind: String,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val isScreenshotHint: Boolean,
)

/**
 * Enumerates Master's Android Camera Roll (`MediaStore` images & short videos) in milliseconds without
 * copying or decoding originals up front, tracking source keys so rejected personal photos never re-scan.
 */
internal object CameraRollScanner {

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasMediaAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val fullImages = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val fullVideos = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            if (fullImages || fullVideos) return true
            if (Build.VERSION.SDK_INT >= 34) {
                return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            }
            return false
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun enumerateAssets(context: Context, limit: Int = 25_000): List<CameraRollAsset> = withContext(Dispatchers.IO) {
        if (!hasMediaAccess(context)) return@withContext emptyList()
        val out = ArrayList<CameraRollAsset>(minOf(limit, 2048))

        fun scanCollection(
            collectionUri: android.net.Uri,
            kind: String,
            maxItems: Int,
        ) {
            if (maxItems <= 0) return
            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.DATE_MODIFIED)
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.MediaColumns.RELATIVE_PATH)
                    add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                }
                if (kind == "video") {
                    add(MediaStore.Video.VideoColumns.DURATION)
                }
            }.toTypedArray()

            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC"
            context.contentResolver.query(collectionUri, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val wCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val hCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME) else -1
                val durCol = if (kind == "video") cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1

                var count = 0
                while (cursor.moveToNext() && count < maxItems) {
                    val id = cursor.getLong(idCol)
                    val durMs = if (durCol >= 0 && !cursor.isNull(durCol)) cursor.getLong(durCol) else 0L
                    // Skip long multi-minute home videos right at the metadata cursor (> 3 minutes is never a reaction meme).
                    if (kind == "video" && durMs > 180_000L) continue

                    val contentUri = ContentUris.withAppendedId(collectionUri, id).toString()
                    val name = if (nameCol >= 0 && !cursor.isNull(nameCol)) cursor.getString(nameCol) else "${kind}_$id"
                    val modSec = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L
                    val width = if (wCol >= 0 && !cursor.isNull(wCol)) cursor.getInt(wCol) else 0
                    val height = if (hCol >= 0 && !cursor.isNull(heightColOr(hCol))) cursor.getInt(hCol) else 0
                    val relPath = if (relCol >= 0 && !cursor.isNull(relCol)) cursor.getString(relCol).orEmpty() else ""
                    val bucket = if (bucketCol >= 0 && !cursor.isNull(bucketCol)) cursor.getString(bucketCol).orEmpty() else ""
                    val screenshot = name.contains("screenshot", ignoreCase = true) ||
                        relPath.contains("screenshot", ignoreCase = true) ||
                        bucket.contains("screenshot", ignoreCase = true)

                    out += CameraRollAsset(
                        sourceKey = "mediastore:$kind:$id",
                        uri = contentUri,
                        name = name,
                        kind = kind,
                        modifiedAt = modSec * 1000L,
                        width = width,
                        height = height,
                        durationMs = durMs,
                        isScreenshotHint = screenshot,
                    )
                    count++
                }
            }
        }

        currentCoroutineContext().ensureActive()
        scanCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", limit)
        currentCoroutineContext().ensureActive()
        scanCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", (limit - out.size).coerceAtLeast(0))
        out.sortWith(compareByDescending<CameraRollAsset> { it.modifiedAt }.thenByDescending { it.sourceKey })
        out
    }

    private fun heightColOr(col: Int): Int = col
}
