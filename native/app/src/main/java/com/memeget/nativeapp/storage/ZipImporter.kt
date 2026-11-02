package com.memeget.nativeapp.storage

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal object ZipImporter {

    const val MAX_ENTRY_BYTES = 350L * 1024 * 1024 // 350 MiB cap per entry to guard against zip bombs
    /** Entries in one archive, skipped ones included. A large meme collection holds tens of thousands. */
    const val MAX_ENTRIES_COUNT = 200_000
    /**
     * Zip-bomb guard: each entry, and the archive as a whole, may inflate to this many times the bytes read for it,
     * plus [INFLATION_ALLOWANCE_BYTES]. Memes are already-compressed media (about 1:1) and manifest JSON deflates a
     * few times over; bombs run near 1000:1. Measuring each entry by itself keeps a large honest part of an archive
     * from paying for a bomb after it.
     */
    const val MAX_INFLATION_RATIO = 100L
    const val INFLATION_ALLOWANCE_BYTES = 64L * 1024 * 1024
    /**
     * Hard ceiling on everything one archive inflates to, far above any meme collection a phone holds. The archive
     * streams, so its own size never needs room on the phone.
     */
    const val MAX_TOTAL_INFLATED_BYTES = 64L * 1024 * 1024 * 1024

    /** Manifests carry vectors for every item (~90 MB per 2,400 memes), so they get their own cap. */
    const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024 * 1024
    /** Kind of the root `manifest.json` a collection export carries next to its media. */
    const val MANIFEST_KIND = "manifest"

    /**
     * The archive's manifest could not be read, so its labels, text and vectors cannot come along.
     * Thrown after the entry is closed: the next call continues with the following entry.
     */
    class UnreadableManifest(message: String) : IOException(message)

    /** The archive inflates past what [ArchiveBudget] allows; nothing more is read from it. */
    class ArchiveTooLarge(message: String) : IOException(message)

    /** Counts the bytes read from the archive, so inflation is measured against what the archive really holds. */
    class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
        override fun markSupported(): Boolean = false
    }

    /** Limits for one archive read through [source]; share it across every [extractNextEntry] call on that archive. */
    class ArchiveBudget(
        private val source: CountingInputStream,
        val maxEntries: Int = MAX_ENTRIES_COUNT,
        val maxInflationRatio: Long = MAX_INFLATION_RATIO,
        val inflationAllowance: Long = INFLATION_ALLOWANCE_BYTES,
        val maxTotalInflated: Long = MAX_TOTAL_INFLATED_BYTES,
    ) {
        var entriesSeen: Int = 0
            private set
        var totalInflatedBytes: Long = 0L
            private set
        private var entryInflatedBytes = 0L
        private var entryStart = 0L

        fun countEntry() {
            if (++entriesSeen > maxEntries) throw ArchiveTooLarge("ZIP archive has more than $maxEntries entries")
            entryInflatedBytes = 0L
            entryStart = source.count
        }

        fun countInflated(bytes: Int) {
            totalInflatedBytes += bytes
            entryInflatedBytes += bytes
            if (totalInflatedBytes > maxTotalInflated) {
                throw ArchiveTooLarge("ZIP archive unpacks to more than ${maxTotalInflated / (1024 * 1024)} MiB")
            }
            if (entryInflatedBytes > inflationAllowance + maxInflationRatio * (source.count - entryStart) ||
                totalInflatedBytes > inflationAllowance + maxInflationRatio * source.count
            ) {
                throw ArchiveTooLarge("ZIP archive inflates more than $maxInflationRatio times its size; it may be a zip bomb")
            }
        }
    }

    data class ExtractedEntry(
        val tempFile: File,
        /** Archive path with forward slashes, as a collection manifest's `file` field names it. */
        val entryPath: String,
        val entryName: String,
        val kind: String,
        val sha256: String,
        val byteSize: Long = 0L,
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

    /**
     * Next media entry, or the root collection manifest (kind [MANIFEST_KIND]); null at the end.
     * Unusable media entries are skipped; an unusable manifest throws [UnreadableManifest].
     */
    private fun drainSkippedEntryBounded(zis: ZipInputStream, budget: ArchiveBudget) {
        val scratch = ByteArray(32768)
        while (true) {
            val n = zis.read(scratch)
            if (n == -1) break
            budget.countInflated(n)
        }
        zis.closeEntry()
    }

    fun extractNextEntry(
        zis: ZipInputStream,
        cacheDir: File,
        manifestLimit: Long = MAX_MANIFEST_BYTES,
        budget: ArchiveBudget,
    ): ExtractedEntry? {
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            budget.countEntry()

            val name = entry.name
            if (name.contains("..")) {
                drainSkippedEntryBounded(zis, budget)
                entry = zis.nextEntry
                continue
            }

            if (entry.isDirectory) {
                drainSkippedEntryBounded(zis, budget)
                entry = zis.nextEntry
                continue
            }

            val basename = cleanBasename(name)
            if (basename.isBlank() || isJunk(name, basename)) {
                drainSkippedEntryBounded(zis, budget)
                entry = zis.nextEntry
                continue
            }

            val entryPath = name.replace('\\', '/').trimStart('/')
            val kind = if (entryPath == "manifest.json") MANIFEST_KIND else MediaIoUtils.kindOf(basename)
            if (kind == null) {
                drainSkippedEntryBounded(zis, budget)
                entry = zis.nextEntry
                continue
            }

            val limit = if (kind == MANIFEST_KIND) manifestLimit else MAX_ENTRY_BYTES
            val safeBasename = basename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val tempFile = File(cacheDir, "zip_entry_${System.currentTimeMillis()}_${safeBasename}")
            tempFile.parentFile?.mkdirs()

            val md = MessageDigest.getInstance("SHA-256")
            var entryBytes = 0L
            var hitLimit = false

            try {
                FileOutputStream(tempFile).use { fos ->
                    val buffer = ByteArray(32768)
                    var read: Int
                    while (zis.read(buffer).also { read = it } != -1) {
                        entryBytes += read
                        budget.countInflated(read)
                        if (entryBytes > limit) {
                            hitLimit = true
                            break
                        }
                        md.update(buffer, 0, read)
                        fos.write(buffer, 0, read)
                    }
                }
            } catch (failure: Throwable) {
                // Skipping the entry would inflate its rest unbudgeted, and a full disk fails every entry after it.
                tempFile.delete()
                throw failure
            }

            if (hitLimit) {
                tempFile.delete()
                drainSkippedEntryBounded(zis, budget)
                if (kind == MANIFEST_KIND) {
                    throw UnreadableManifest("its manifest is larger than ${limit / (1024 * 1024)} MiB")
                }
                entry = zis.nextEntry
                continue
            }

            zis.closeEntry()

            if (entryBytes == 0L) {
                tempFile.delete()
                if (kind == MANIFEST_KIND) {
                    throw UnreadableManifest("its manifest is empty")
                }
                entry = zis.nextEntry
                continue
            }

            val hashHex = md.digest().joinToString("") { "%02x".format(it) }
            return ExtractedEntry(
                tempFile = tempFile,
                entryPath = entryPath,
                entryName = basename,
                kind = kind,
                sha256 = hashHex,
                byteSize = entryBytes,
            )
        }
        return null
    }
}
