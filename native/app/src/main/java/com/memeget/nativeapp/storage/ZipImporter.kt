package com.memeget.nativeapp.storage

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal object ZipImporter {

    private const val MAX_ENTRY_BYTES = 350L * 1024 * 1024 // 350 MiB cap per entry to guard against zip bombs
    private const val MAX_ENTRIES_COUNT = 5000 // Guard against excessive entries in one zip

    /** Kind of the root `manifest.json` a collection export carries next to its media. */
    const val MANIFEST_KIND = "manifest"

    data class ExtractedEntry(
        val tempFile: File,
        /** Archive path with forward slashes, as a collection manifest's `file` field names it. */
        val entryPath: String,
        val entryName: String,
        val kind: String,
        val sha256: String
    )

    private fun isJunk(path: String, basename: String): Boolean {
        if (path.contains("__MACOSX/")) return true
        if (basename.startsWith(".")) return true
        if (basename.equals("thumbs.db", ignoreCase = true)) return true
        return false
    }

    private fun cleanBasename(path: String): String {
        val normalized = path.replace('\\', '/')
        val parts = normalized.split("/").filter { it.isNotBlank() }
        return parts.lastOrNull() ?: ""
    }

    /** Next media entry, or the root collection manifest (kind [MANIFEST_KIND]); null at the end. */
    fun extractNextEntry(
        zis: ZipInputStream,
        cacheDir: File
    ): ExtractedEntry? {
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            val name = entry.name
            // Zip-Slip protection
            if (name.contains("..")) {
                zis.closeEntry()
                entry = zis.nextEntry
                continue
            }

            if (entry.isDirectory) {
                zis.closeEntry()
                entry = zis.nextEntry
                continue
            }

            val basename = cleanBasename(name)
            if (basename.isBlank() || isJunk(name, basename)) {
                zis.closeEntry()
                entry = zis.nextEntry
                continue
            }

            val entryPath = name.replace('\\', '/').trimStart('/')
            val kind = if (entryPath == "manifest.json") MANIFEST_KIND else MediaIoUtils.kindOf(basename)
            if (kind == null) {
                zis.closeEntry()
                entry = zis.nextEntry
                continue
            }

            // Valid media entry! Stream to temp file with bounded size and SHA-256 calculation
            val safeBasename = basename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val tempFile = File(cacheDir, "zip_entry_${System.currentTimeMillis()}_${safeBasename}")
            tempFile.parentFile?.mkdirs()

            val md = MessageDigest.getInstance("SHA-256")
            var entryBytes = 0L
            var failed = false

            try {
                FileOutputStream(tempFile).use { fos ->
                    val buffer = ByteArray(32768)
                    var read: Int
                    while (zis.read(buffer).also { read = it } != -1) {
                        entryBytes += read
                        if (entryBytes > MAX_ENTRY_BYTES) {
                            failed = true
                            break
                        }
                        md.update(buffer, 0, read)
                        fos.write(buffer, 0, read)
                    }
                }
            } catch (_: Exception) {
                failed = true
            }

            zis.closeEntry()

            if (failed || entryBytes == 0L) {
                tempFile.delete()
                entry = zis.nextEntry
                continue
            }

            val hashHex = md.digest().joinToString("") { "%02x".format(it) }
            return ExtractedEntry(
                tempFile = tempFile,
                entryPath = entryPath,
                entryName = basename,
                kind = kind,
                sha256 = hashHex
            )
        }
        return null
    }
}
