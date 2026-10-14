package com.memeget.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import com.memeget.nativeapp.editor.MediaEditKind
import com.memeget.nativeapp.editor.MemeEditSource
import com.memeget.nativeapp.editor.NativeVideoComposition
import com.memeget.nativeapp.editor.ProjectCore
import com.memeget.nativeapp.storage.CollectionManifest
import com.memeget.nativeapp.storage.CollectionZipExporter
import com.memeget.nativeapp.storage.LinkResolver
import com.memeget.nativeapp.storage.MediaIoUtils
import com.memeget.nativeapp.storage.SidecarFormat
import com.memeget.nativeapp.storage.TeachingPackIo
import com.memeget.nativeapp.storage.ZipImporter
import expo.modules.memegetbg.MemeMediaProbe
import expo.modules.memegetbg.VideoFrameExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal class LibraryFiles(
    private val context: Context,
    private val store: LibraryStore
) {
    private val clipboardMutex = Mutex()
    /**
     * Intake that adds library items (folder scans and restores, shared files, ZIPs, links) runs one at a time. Each
     * looks for an existing copy before writing one, so two running together could each miss the item the other is
     * adding and copy the same file twice.
     */
    private val intakeGate = Mutex()

    private fun allocateUniqueFile(dir: File, baseName: String): File {
        val candidate = File(dir, baseName)
        if (!candidate.exists()) return candidate
        val nameWithoutExt = candidate.nameWithoutExtension
        val ext = candidate.extension.let { if (it.isNotBlank()) ".$it" else "" }
        var counter = 1
        var unique: File
        do {
            unique = File(dir, "${nameWithoutExt}_${counter}${ext}")
            counter++
        } while (unique.exists())
        return unique
    }

    /**
     * Finds library items by their bytes, for one import. Items found by scanning a linked folder have no hash until
     * they are indexed, so when no known hash matches, unhashed items of the same size are hashed as well. Without
     * that, sharing a file or collection that is already in the linked folder wrote a second copy of each file into it.
     * A hash worked out here is saved on its item, so indexing can still reuse the analysis an export carried for it.
     */
    private inner class LibraryContent {
        private var byHash: HashMap<String, Long>? = null
        private val unhashed = ArrayList<Meme>()
        private val sizes = HashMap<String, Long>()

        suspend fun find(hash: String, size: Long): Long? {
            if (hash.isBlank()) return null
            val known = byHash ?: load()
            known[hash]?.let { return it }
            val candidates = unhashed.iterator()
            while (candidates.hasNext()) {
                currentCoroutineContext().ensureActive()
                val meme = candidates.next()
                val memeSize = sizes.getOrPut(meme.uri) { sizeOf(meme.uri) }
                if (memeSize >= 0 && memeSize != size) continue
                val memeHash = hashOf(meme.uri) ?: continue
                candidates.remove()
                store.setContentHash(meme.id, memeHash)
                known.putIfAbsent(memeHash, meme.id)
                if (memeHash == hash) return meme.id
            }
            return null
        }

        fun add(hash: String, id: Long) {
            if (hash.isNotBlank() && id > 0) byHash?.putIfAbsent(hash, id)
        }

        private suspend fun load(): HashMap<String, Long> {
            val known = HashMap<String, Long>()
            store.allMemes().collect { meme ->
                if (meme.contentHash.isBlank()) unhashed += meme else known.putIfAbsent(meme.contentHash, meme.id)
            }
            // One listing per linked folder gives the sizes a document query per item would.
            for (folder in store.folders()) {
                for (doc in MediaIoUtils.listFilesFast(context, Uri.parse(folder.uri), mediaOnly = true)) {
                    if (doc.size > 0) sizes[doc.uri.toString()] = doc.size
                }
            }
            byHash = known
            return known
        }

        /** Byte size, or -1 when it can't be told cheaply (the item is then hashed whatever its size). */
        private fun sizeOf(uri: String): Long = try {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "file") {
                File(parsed.path ?: "").takeIf(File::isFile)?.length() ?: -1L
            } else {
                context.contentResolver.query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                } ?: -1L
            }
        } catch (_: Exception) {
            -1L
        }

        private fun hashOf(uri: String): String? = try {
            val parsed = Uri.parse(uri)
            val input = if (parsed.scheme == "file") File(parsed.path ?: "").inputStream() else context.contentResolver.openInputStream(parsed)
            input?.use { MediaIoUtils.computeSha256(it) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun scan(folder: Folder): List<Long> = intakeGate.withLock { scanLocked(folder) }

    private suspend fun scanLocked(folder: Folder): List<Long> = withContext(Dispatchers.IO) {
        val folderUri = Uri.parse(folder.uri)
        val discovered = MediaIoUtils.listFilesFast(context, folderUri, mediaOnly = true)
        val newIds = mutableListOf<Long>()

        for (doc in discovered) {
            val uriStr = doc.uri.toString()
            val existing = store.memeByUri(uriStr)
            if (existing == null) {
                val id = store.insertPending(
                    uri = uriStr,
                    name = doc.displayName,
                    kind = doc.kind,
                    modifiedAt = doc.lastModified,
                    contentHash = ""
                )
                if (id > 0) newIds.add(id)
            }
        }
        newIds
    }

    suspend fun link(uri: Uri): Folder = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }

        val folderName = DocumentFile.fromTreeUri(context, uri)?.name
            ?: MediaIoUtils.getDisplayName(context, uri).ifBlank { "Linked folder" }

        val folder = Folder(
            uri = uri.toString(),
            name = folderName,
            addedAt = System.currentTimeMillis()
        )

        store.addFolder(folder.uri, folder.name)
        scan(folder)
        restore(folder)
        folder
    }

    suspend fun importUri(uri: Uri, mime: String? = null): List<Long> = intakeGate.withLock { importUriLocked(uri, mime) }

    private suspend fun importUriLocked(uri: Uri, mime: String?): List<Long> = withContext(Dispatchers.IO) {
        val displayName = MediaIoUtils.getDisplayName(context, uri)
        val resolvedMime = mime ?: context.contentResolver.getType(uri) ?: MediaIoUtils.mimeForName(displayName)

        // ZIP import handling
        if (resolvedMime == "application/zip" || displayName.lowercase(Locale.ROOT).endsWith(".zip")) {
            return@withContext importZip(uri)
        }
        // Teaching files carry examples, not media.
        if (resolvedMime == "application/json" || displayName.lowercase(Locale.ROOT).endsWith(".json")) {
            importTeaching(uri)
            return@withContext emptyList()
        }

        // Shared media intake: stage source immediately to app-private cache before background continuation
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "import_${System.currentTimeMillis()}.bin" }
        val stagedFile = File(context.cacheDir, "stage_${System.currentTimeMillis()}_$safeName")
        try {
            MediaIoUtils.copyUriToFile(context, uri, stagedFile)
            val sha256 = MediaIoUtils.computeSha256(stagedFile)

            // Duplicate detection by content SHA-256, including linked-folder files not indexed yet
            val existing = LibraryContent().find(sha256, stagedFile.length())
            if (existing != null) {
                stagedFile.delete()
                return@withContext listOf(existing)
            }

            val folders = store.folders()
            val targetFolder = folders.firstOrNull()

            val targetUriStr: String
            val finalName: String

            if (targetFolder != null) {
                val folderTreeUri = Uri.parse(targetFolder.uri)
                val newDocUri = MediaIoUtils.createSafDocument(context, folderTreeUri, safeName, resolvedMime)
                    ?: throw IllegalStateException("Failed to create document in linked folder")

                context.contentResolver.openOutputStream(newDocUri)?.use { out ->
                    stagedFile.inputStream().use { inp ->
                        inp.copyTo(out)
                    }
                } ?: throw IllegalStateException("Failed to stream staged file to linked folder")

                targetUriStr = newDocUri.toString()
                finalName = MediaIoUtils.getDisplayName(context, newDocUri).ifBlank { safeName }
            } else {
                // If no linked folder, copy to app-private storage: user's originals never deleted
                val importsDir = File(context.filesDir, "imports")
                importsDir.mkdirs()
                val destFile = File(importsDir, "import_${System.currentTimeMillis()}_$safeName")
                stagedFile.copyTo(destFile, overwrite = true)
                targetUriStr = Uri.fromFile(destFile).toString()
                finalName = destFile.name
            }

            val kind = MediaIoUtils.kindOf(finalName) ?: if (resolvedMime.startsWith("video/")) "video" else "image"
            val id = store.insertPending(
                uri = targetUriStr,
                name = finalName,
                kind = kind,
                modifiedAt = System.currentTimeMillis(),
                contentHash = sha256
            )
            listOf(id)
        } finally {
            stagedFile.delete()
        }
    }

    private suspend fun importZip(zipUri: Uri): List<Long> {
        val stagedZip = File(context.cacheDir, "zip_${System.currentTimeMillis()}.zip")
        var manifest: File? = null
        try {
            MediaIoUtils.copyUriToFile(context, zipUri, stagedZip)
            val zis = ZipInputStream(FileInputStream(stagedZip))
            val importedIds = mutableListOf<Long>()
            val idByEntry = HashMap<String, Long>()
            val folders = store.folders()
            val targetFolder = folders.firstOrNull()
            var manifestProblem: String? = null
            val library = LibraryContent()

            zis.use { stream ->
                while (true) {
                    val extracted = try {
                        ZipImporter.extractNextEntry(stream, context.cacheDir)
                    } catch (unreadable: ZipImporter.UnreadableManifest) {
                        manifestProblem = unreadable.message
                        continue
                    } ?: break
                    if (extracted.kind == ZipImporter.MANIFEST_KIND) {
                        manifest?.delete()
                        manifest = extracted.tempFile
                        continue
                    }
                    try {
                        val duplicate = library.find(extracted.sha256, extracted.tempFile.length())
                        if (duplicate != null) {
                            importedIds.add(duplicate)
                            idByEntry[extracted.entryPath] = duplicate
                            continue
                        }

                        val targetUriStr: String
                        val finalName: String
                        val mimeType = MediaIoUtils.mimeForName(extracted.entryName)

                        if (targetFolder != null) {
                            val folderTreeUri = Uri.parse(targetFolder.uri)
                            val newDocUri = MediaIoUtils.createSafDocument(context, folderTreeUri, extracted.entryName, mimeType)
                                ?: continue

                            context.contentResolver.openOutputStream(newDocUri)?.use { out ->
                                extracted.tempFile.inputStream().use { inp ->
                                    inp.copyTo(out)
                                }
                            } ?: continue

                            targetUriStr = newDocUri.toString()
                            finalName = MediaIoUtils.getDisplayName(context, newDocUri).ifBlank { extracted.entryName }
                        } else {
                            val importsDir = File(context.filesDir, "imports")
                            importsDir.mkdirs()
                            val destFile = File(importsDir, "zip_${System.currentTimeMillis()}_${extracted.entryName}")
                            extracted.tempFile.copyTo(destFile, overwrite = true)
                            targetUriStr = Uri.fromFile(destFile).toString()
                            finalName = destFile.name
                        }

                        val id = store.insertPending(
                            uri = targetUriStr,
                            name = finalName,
                            kind = extracted.kind,
                            modifiedAt = System.currentTimeMillis(),
                            contentHash = extracted.sha256
                        )
                        if (id > 0) {
                            importedIds.add(id)
                            idByEntry[extracted.entryPath] = id
                            // A later entry with the same bytes is the same meme.
                            library.add(extracted.sha256, id)
                        }
                    } finally {
                        extracted.tempFile.delete()
                    }
                }
            }
            // Media first; then what the export knew about each item, matched by archive path.
            manifest?.let { file ->
                for (record in CollectionManifest.read(file)) {
                    val id = idByEntry[record.file] ?: continue
                    store.applyImportedAnalysis(id, record.analysis)
                }
                store.relinkTeachings()
            }
            // Media are in either way; say plainly when what the export knew about them is not.
            manifestProblem?.let { throw IOException("Imported ${importedIds.size} items without their labels: $it") }
            return importedIds
        } finally {
            stagedZip.delete()
            manifest?.delete()
        }
    }

    suspend fun importIntent(intent: Intent): List<Long> = withContext(Dispatchers.IO) {
        val action = intent.action
        val type = intent.type

        when (action) {
            Intent.ACTION_SEND -> {
                val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.data

                if (streamUri != null) {
                    return@withContext importUri(streamUri, type)
                }

                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    val url = LinkResolver.extractUrl(text)
                    if (url != null) {
                        return@withContext importLink(url)
                    }
                }
                emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                } ?: emptyList()

                val ids = mutableListOf<Long>()
                for (u in uris) {
                    ids.addAll(importUri(u, type))
                }
                ids
            }
            else -> emptyList()
        }
    }

    suspend fun importLink(text: String): List<Long> = intakeGate.withLock { importLinkLocked(text) }

    private suspend fun importLinkLocked(text: String): List<Long> = withContext(Dispatchers.IO) {
        val url = LinkResolver.extractUrl(text) ?: throw IllegalArgumentException("No valid link found in input")
        val ref = LinkResolver.resolveMediaRef(url)
        val downloaded = LinkResolver.download(ref, context.cacheDir)

        try {
            val duplicate = LibraryContent().find(downloaded.sha256, downloaded.file.length())
            if (duplicate != null) {
                return@withContext listOf(duplicate)
            }

            val folders = store.folders()
            val targetFolder = folders.firstOrNull()

            val targetUriStr: String
            val finalName: String

            if (targetFolder != null) {
                val folderTreeUri = Uri.parse(targetFolder.uri)
                val newDocUri = MediaIoUtils.createSafDocument(context, folderTreeUri, downloaded.fileName, downloaded.mimeType)
                    ?: throw IllegalStateException("Failed to create document in linked folder")

                context.contentResolver.openOutputStream(newDocUri)?.use { out ->
                    downloaded.file.inputStream().use { inp ->
                        inp.copyTo(out)
                    }
                } ?: throw IllegalStateException("Failed to stream downloaded file to linked folder")

                targetUriStr = newDocUri.toString()
                finalName = MediaIoUtils.getDisplayName(context, newDocUri).ifBlank { downloaded.fileName }
            } else {
                val importsDir = File(context.filesDir, "imports")
                importsDir.mkdirs()
                val destFile = allocateUniqueFile(importsDir, downloaded.fileName)
                downloaded.file.copyTo(destFile, overwrite = false)
                targetUriStr = Uri.fromFile(destFile).toString()
                finalName = destFile.name
            }
            val kind = MediaIoUtils.kindOf(finalName) ?: if (downloaded.mimeType.startsWith("video/")) "video" else "image"
            val id = store.insertPending(
                uri = targetUriStr,
                name = finalName,
                kind = kind,
                modifiedAt = System.currentTimeMillis(),
                contentHash = downloaded.sha256
            )
            listOf(id)
        } finally {
            downloaded.file.delete()
        }
    }

    suspend fun saveNew(uri: String, name: String, kind: String): Long = withContext(Dispatchers.IO) {
        val srcUri = Uri.parse(uri)
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "meme_${System.currentTimeMillis()}.png" }
        val mimeType = MediaIoUtils.mimeForName(safeName)

        val folders = store.folders()
        val targetFolder = folders.firstOrNull()

        val targetUriStr: String
        val finalName: String
        val sha256: String

        if (targetFolder != null) {
            val folderTreeUri = Uri.parse(targetFolder.uri)
            val newDocUri = MediaIoUtils.createSafDocument(context, folderTreeUri, safeName, mimeType)
                ?: throw IllegalStateException("Could not create document in linked folder")

            MediaIoUtils.copyUriToTarget(context, srcUri, newDocUri)
            targetUriStr = newDocUri.toString()
            finalName = MediaIoUtils.getDisplayName(context, newDocUri).ifBlank { safeName }

            sha256 = context.contentResolver.openInputStream(newDocUri)?.use {
                MediaIoUtils.computeSha256(it)
            } ?: ""
        } else {
            val mediaDir = File(context.filesDir, "media")
            mediaDir.mkdirs()
            val destFile = allocateUniqueFile(mediaDir, safeName)
            MediaIoUtils.copyUriToFile(context, srcUri, destFile)
            targetUriStr = Uri.fromFile(destFile).toString()
            finalName = destFile.name
            sha256 = MediaIoUtils.computeSha256(destFile)
        }

        store.insertPending(
            uri = targetUriStr,
            name = finalName,
            kind = kind,
            modifiedAt = System.currentTimeMillis(),
            contentHash = sha256
        )
    }

    suspend fun restore(folder: Folder): Int = intakeGate.withLock { restoreLocked(folder) }

    private suspend fun restoreLocked(folder: Folder): Int = withContext(Dispatchers.IO) {
        val folderUri = Uri.parse(folder.uri)
        val mediaFiles = MediaIoUtils.listFilesFast(context, folderUri, mediaOnly = true)
        val filesByExactName = HashMap<String, MediaIoUtils.SafDocument>()
        for (f in mediaFiles) {
            filesByExactName[f.displayName] = f
        }

        val hashCache = HashMap<String, String>()
        fun getFileHash(doc: MediaIoUtils.SafDocument): String {
            return hashCache.getOrPut(doc.documentId) {
                try {
                    context.contentResolver.openInputStream(doc.uri)?.use {
                        MediaIoUtils.computeSha256(it)
                    } ?: ""
                } catch (_: Exception) {
                    ""
                }
            }
        }

        var restoredCount = 0

        // 1. Check Native Sidecar (.memeget-native)
        val nativeDir = MediaIoUtils.findChildSafDocument(context, folderUri, SidecarFormat.NATIVE_SIDECAR_DIR)
        if (nativeDir != null) {
            val allNativeFiles = MediaIoUtils.listFilesFast(context, nativeDir, mediaOnly = false)
            val nativeFilesByName = allNativeFiles.associateBy { it.displayName }

            data class ManifestCandidate(val doc: MediaIoUtils.SafDocument, val manifest: SidecarFormat.NativeManifest)
            val candidates = ArrayList<ManifestCandidate>()

            for (f in allNativeFiles) {
                if (f.displayName == "manifest.json" || (f.displayName.startsWith("manifest_") && f.displayName.endsWith(".json"))) {
                    try {
                        val text = context.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
                        if (!text.isNullOrBlank()) {
                            val parsed = SidecarFormat.parseNativeManifest(text)
                            if (parsed != null) {
                                candidates.add(ManifestCandidate(f, parsed))
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            candidates.sortByDescending { it.manifest.generation }

            var chosenManifest: SidecarFormat.NativeManifest? = null
            for (cand in candidates) {
                var valid = true
                for ((_, stamp) in cand.manifest.chunks) {
                    val chunkDoc = nativeFilesByName[stamp.file]
                    if (chunkDoc == null) {
                        valid = false
                        break
                    }
                    if (stamp.bytes > 0 && chunkDoc.size > 0 && chunkDoc.size != stamp.bytes.toLong()) {
                        valid = false
                        break
                    }
                    val chunkText = context.contentResolver.openInputStream(chunkDoc.uri)?.let {
                        SidecarFormat.readVerified(it, stamp.digest)
                    }
                    if (chunkText == null) {
                        valid = false
                        break
                    }
                }
                if (valid && cand.manifest.teachings.file.isNotBlank()) {
                    val teachDoc = nativeFilesByName[cand.manifest.teachings.file]
                    if (teachDoc == null) {
                        valid = false
                    } else {
                        val teachText = context.contentResolver.openInputStream(teachDoc.uri)?.let {
                            SidecarFormat.readVerified(it, cand.manifest.teachings.digest)
                        }
                        if (teachText == null) {
                            valid = false
                        }
                    }
                }
                if (valid) {
                    chosenManifest = cand.manifest
                    break
                }
            }

            if (chosenManifest != null) {
                for ((_, stamp) in chosenManifest.chunks) {
                    val chunkDoc = nativeFilesByName[stamp.file] ?: continue
                    val chunkText = context.contentResolver.openInputStream(chunkDoc.uri)?.use {
                        it.bufferedReader().readText()
                    } ?: continue

                    val restoredMemes = SidecarFormat.parseNativeChunk(chunkText) { name, hash ->
                        if (hash.isNotBlank()) {
                            val nameMatch = filesByExactName[name]
                            if (nameMatch != null && getFileHash(nameMatch) == hash) {
                                return@parseNativeChunk Pair(nameMatch.uri.toString(), nameMatch.kind)
                            }
                            for (doc in mediaFiles) {
                                if (getFileHash(doc) == hash) {
                                    return@parseNativeChunk Pair(doc.uri.toString(), doc.kind)
                                }
                            }
                            null
                        } else {
                            val match = filesByExactName[name]
                            if (match != null) Pair(match.uri.toString(), match.kind) else null
                        }
                    }

                    for (r in restoredMemes) {
                        val ok = store.restore(r.meme, r.vectorRecord)
                        if (ok) restoredCount++
                    }
                }

                if (chosenManifest.teachings.file.isNotBlank()) {
                    val teachDoc = nativeFilesByName[chosenManifest.teachings.file]
                    if (teachDoc != null) {
                        val teachText = context.contentResolver.openInputStream(teachDoc.uri)?.use {
                            it.bufferedReader().readText()
                        }
                        if (!teachText.isNullOrBlank()) {
                            try {
                                val exemplars = TeachingPackIo.parseNativeTeachings(teachText)
                                store.importExemplars(exemplars, pack = "", asSelf = false)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        // 2. Check Legacy Sidecar (.memeget) - Read-only legacy restore
        val legacyDir = MediaIoUtils.findChildSafDocument(context, folderUri, SidecarFormat.LEGACY_SIDECAR_DIR)
        if (legacyDir != null) {
            val manifestDoc = MediaIoUtils.findChildSafDocument(context, legacyDir, "manifest.json")
            val chunkKeys = mutableListOf<String>()

            if (manifestDoc != null) {
                val manifestText = context.contentResolver.openInputStream(manifestDoc)?.use {
                    it.bufferedReader().readText()
                }
                if (!manifestText.isNullOrBlank()) {
                    try {
                        val json = org.json.JSONObject(manifestText)
                        val chunksObj = json.optJSONObject("chunks")
                        if (chunksObj != null) {
                            val keys = chunksObj.keys()
                            while (keys.hasNext()) chunkKeys.add(keys.next())
                        }
                    } catch (_: Exception) {}
                }
            }

            if (chunkKeys.isEmpty()) {
                for (i in 0 until SidecarFormat.SIDECAR_CHUNKS) {
                    chunkKeys.add("%02x".format(i))
                }
            }

            for (key in chunkKeys) {
                val chunkFileName = "library-$key.json"
                val chunkDoc = MediaIoUtils.findChildSafDocument(context, legacyDir, chunkFileName) ?: continue
                val chunkText = context.contentResolver.openInputStream(chunkDoc)?.use {
                    it.bufferedReader().readText()
                } ?: continue

                val restoredMemes = SidecarFormat.parseLegacyChunk(
                    jsonText = chunkText,
                    uriByName = { name ->
                        val match = filesByExactName[name]
                        if (match != null) Pair(match.uri.toString(), match.kind) else null
                    },
                )

                for (r in restoredMemes) {
                    val ok = store.restore(r.meme, r.vectorRecord)
                    if (ok) restoredCount++
                }
            }

            val teachDoc = MediaIoUtils.findChildSafDocument(context, legacyDir, "teachings.json")
            if (teachDoc != null) {
                val teachText = context.contentResolver.openInputStream(teachDoc)?.use {
                    it.bufferedReader().readText()
                }
                if (!teachText.isNullOrBlank()) {
                    try {
                        val exemplars = TeachingPackIo.parseTeachingPack(teachText)
                        store.importExemplars(exemplars, pack = "", asSelf = true)
                    } catch (_: Exception) {}
                }
            }
        }

        restoredCount
    }

    suspend fun backup(folder: Folder): Unit = withContext(Dispatchers.IO) {
        val folderUri = Uri.parse(folder.uri)
        val generation = System.currentTimeMillis()

        val nativeDir = MediaIoUtils.ensureDirectory(context, folderUri, SidecarFormat.NATIVE_SIDECAR_DIR)
            ?: throw IllegalStateException("Could not create .memeget-native directory")

        // Spool chunks using 64 temporary files to avoid whole-library in-memory materialization
        val tempChunkFiles = Array(SidecarFormat.SIDECAR_CHUNKS) {
            File.createTempFile("native_chunk_${it}_", ".json", context.cacheDir)
        }
        val tempWriters = Array(SidecarFormat.SIDECAR_CHUNKS) {
            tempChunkFiles[it].bufferedWriter(Charsets.UTF_8)
        }
        val chunkCounts = IntArray(SidecarFormat.SIDECAR_CHUNKS)

        var totalCount = 0
        try {
            store.allMemes().collect { meme ->
                if (meme.uri.startsWith(folder.uri) || store.folders().size <= 1) {
                    val chunkKey = SidecarFormat.chunkFor(meme.name)
                    val chunkIdx = chunkKey.toInt(16)
                    val vectors = store.vectors(meme.id)

                    val writer = tempWriters[chunkIdx]
                    if (chunkCounts[chunkIdx] > 0) {
                        writer.write(",")
                    }
                    val memeJson = SidecarFormat.serializeNativeMemeItem(meme, vectors)
                    writer.write(memeJson)
                    chunkCounts[chunkIdx]++
                    totalCount++
                }
            }
        } finally {
            for (w in tempWriters) {
                try { w.close() } catch (_: Exception) {}
            }
        }

        val chunkStamps = mutableMapOf<String, SidecarFormat.ChunkStamp>()
        for (i in 0 until SidecarFormat.SIDECAR_CHUNKS) {
            val key = "%02x".format(i)
            val fileName = "library_${generation}_${key}.json"
            val count = chunkCounts[i]
            val tempFile = tempChunkFiles[i]

            val chunkDocUri = MediaIoUtils.createSafDocument(context, nativeDir, fileName, "application/json")
                ?: throw IllegalStateException("Could not create chunk document $fileName")

            val outStream = context.contentResolver.openOutputStream(chunkDocUri)
                ?: throw IllegalStateException("Could not open output stream for chunk document $fileName")

            val md = java.security.MessageDigest.getInstance("SHA-256")
            var byteCount = 0
            outStream.use { out ->
                val header = """{"format":"${SidecarFormat.NATIVE_SIDECAR_FORMAT}","version":${SidecarFormat.NATIVE_SIDECAR_VERSION},"generation":$generation,"chunk":"$key","count":$count,"memes":["""
                val hBytes = header.toByteArray(Charsets.UTF_8)
                out.write(hBytes)
                md.update(hBytes)
                byteCount += hBytes.size

                tempFile.inputStream().use { inp ->
                    val buf = ByteArray(16384)
                    var read: Int
                    while (inp.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        md.update(buf, 0, read)
                        byteCount += read
                    }
                }

                val footer = "]}"
                val fBytes = footer.toByteArray(Charsets.UTF_8)
                out.write(fBytes)
                md.update(fBytes)
                byteCount += fBytes.size
            }
            tempFile.delete()

            val digestHex = md.digest().joinToString("") { "%02x".format(it) }
            chunkStamps[key] = SidecarFormat.ChunkStamp(
                file = fileName,
                count = count,
                digest = digestHex,
                bytes = byteCount
            )
        }

        // Export lossless native teachings
        val exemplars = store.exemplars()
        val teachingsJson = TeachingPackIo.serializeNativeTeachings(exemplars, name = "Backup teachings", createdAt = generation)
        val teachingsDigest = SidecarFormat.digest(teachingsJson)
        val teachingsFileName = "teachings_${generation}.json"
        val teachingsDocUri = MediaIoUtils.createSafDocument(context, nativeDir, teachingsFileName, "application/json")
            ?: throw IllegalStateException("Could not create teachings document")

        val teachBytes = teachingsJson.toByteArray(Charsets.UTF_8)
        val teachStream = context.contentResolver.openOutputStream(teachingsDocUri)
            ?: throw IllegalStateException("Could not open output stream for teachings document")
        teachStream.use { it.write(teachBytes) }

        val teachingsStamp = SidecarFormat.ChunkStamp(
            file = teachingsFileName,
            count = exemplars.size,
            digest = teachingsDigest,
            bytes = teachBytes.size
        )

        // Safe immutable manifest commit: write manifest_<generation>.json
        val manifestJson = SidecarFormat.serializeNativeManifest(
            generation = generation,
            chunks = chunkStamps,
            teachings = teachingsStamp,
            totalMemeCount = totalCount,
            writtenAt = generation
        )
        val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)

        val genManifestFileName = "manifest_${generation}.json"
        val genManifestUri = MediaIoUtils.createSafDocument(context, nativeDir, genManifestFileName, "application/json")
            ?: throw IllegalStateException("Could not create generation manifest $genManifestFileName")
        val genOut = context.contentResolver.openOutputStream(genManifestUri)
            ?: throw IllegalStateException("Could not open output stream for generation manifest")
        genOut.use { it.write(manifestBytes) }

        // Also write/update manifest.json pointing to newest generation
        var manifestUri = MediaIoUtils.findChildSafDocument(context, nativeDir, "manifest.json")
        if (manifestUri == null) {
            manifestUri = MediaIoUtils.createSafDocument(context, nativeDir, "manifest.json", "application/json")
        }
        if (manifestUri != null) {
            val mainOut = context.contentResolver.openOutputStream(manifestUri)
            mainOut?.use { it.write(manifestBytes) }
        }
    }

    suspend fun importTeaching(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: throw IllegalArgumentException("Could not read teaching pack file")

        val exemplars = TeachingPackIo.parseNativeTeachings(text)
        val packName = MediaIoUtils.getDisplayName(context, uri).substringBeforeLast(".")
        val imported = store.importExemplars(exemplars, pack = packName, asSelf = false)
        store.relinkTeachings()
        imported
    }

    suspend fun exportTeaching(destination: Uri): Unit = withContext(Dispatchers.IO) {
        val exemplars = store.exemplars()
        val text = TeachingPackIo.serializeNativeTeachings(exemplars, name = "Exported Teaching Pack")
        val outStream = context.contentResolver.openOutputStream(destination)
            ?: throw IllegalStateException("Could not write to destination $destination")
        outStream.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun exportCollection(destination: Uri, progress: (WorkProgress) -> Unit): Unit = withContext(Dispatchers.IO) {
        val count = store.count()
        CollectionZipExporter.exportZip(
            context = context,
            destinationUri = destination,
            allMemes = store.allMemes(),
            totalEstimate = count,
            getPosterBytes = { meme ->
                val thumbPath = thumbnail(meme)
                if (thumbPath.isNotBlank()) {
                    val thumbUri = Uri.parse(thumbPath)
                    context.contentResolver.openInputStream(thumbUri)?.use { it.readBytes() }
                } else null
            },
            getVectorRecord = { id -> store.vectors(id) },
            progress = progress
        )
    }

    suspend fun exportToDownloads(uri: String, name: String, mime: String): Uri = withContext(Dispatchers.IO) {
        MediaIoUtils.saveToDownloads(context, Uri.parse(uri), name, mime)
    }

    fun share(context: Context, memes: List<Meme>) {
        if (memes.isEmpty()) return
        val shareDir = File(context.cacheDir, "share")
        shareDir.mkdirs()

        val shareUris = arrayListOf<Uri>()
        var singleMime = MediaIoUtils.MIME_OCTET

        for (m in memes) {
            val safeName = m.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "meme_${m.id}.bin" }
            val staged = File(shareDir, "share_${m.id}_$safeName")
            try {
                MediaIoUtils.copyUriToFile(context, Uri.parse(m.uri), staged)
                val contentUri = MediaIoUtils.shareableUriForFile(context, staged)
                shareUris.add(contentUri)
                singleMime = MediaIoUtils.mimeForName(m.name)
            } catch (_: Exception) {}
        }

        if (shareUris.isEmpty()) return

        val intent = if (shareUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = singleMime
                putExtra(Intent.EXTRA_STREAM, shareUris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val chooser = Intent.createChooser(intent, "Share meme").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    suspend fun copyToClipboard(uri: String, name: String): Unit = clipboardMutex.withLock {
        withContext(Dispatchers.IO) {
            val clipDir = File(context.cacheDir, "clipboard")
            clipDir.mkdirs()
            var converted: File? = null
            var staged: File? = null
            var published = false
            try {
                var source = Uri.parse(uri)
                var copyName = name
                var mime = context.contentResolver.getType(source) ?: MediaIoUtils.mimeForName(name)
                if (mime == "video/webm" || MediaIoUtils.extOf(name) == "webm") {
                    val probe = MemeMediaProbe.probe(context, uri)
                    val rotated = probe.rotationDegrees == 90 || probe.rotationDegrees == 270
                    val videoSource = MemeEditSource(
                        uri = uri,
                        name = name,
                        kind = MediaEditKind.VIDEO,
                        width = if (rotated) probe.height else probe.width,
                        height = if (rotated) probe.width else probe.height,
                        durationUs = requireNotNull(probe.durationUs) { "Could not read video duration" },
                    )
                    converted = NativeVideoComposition.exportVideo(
                        context,
                        ProjectCore.createDefaultVideoProject(videoSource),
                        exportId = "clipboard_${System.nanoTime()}",
                    ).file
                    source = Uri.fromFile(converted)
                    copyName = "${name.substringBeforeLast('.')}.mp4"
                    mime = MediaIoUtils.MIME_MP4
                }
                val readyFile = File.createTempFile("clip_", ".${MediaIoUtils.extForMime(mime)}", clipDir)
                staged = readyFile
                MediaIoUtils.copyUriToFile(context, source, readyFile)
                ensureActive()
                val contentUri = FileProvider.getUriForFile(
                    context, "${context.packageName}.memegetclip", readyFile, copyName,
                )
                val clip = ClipData(copyName, arrayOf(mime), ClipData.Item(contentUri))
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(clip)
                published = true
                // Publish only complete files; failed copies must leave the previous clip readable.
                clipDir.listFiles()?.forEach { if (it != readyFile) it.delete() }
            } finally {
                if (!published) staged?.delete()
                converted?.delete()
            }
        }
    }

    suspend fun thumbnail(meme: Meme): String = withContext(Dispatchers.IO) {
        if (meme.thumbUri.isNotBlank()) {
            val uri = Uri.parse(meme.thumbUri)
            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                if (f.exists() && f.length() > 0) return@withContext meme.thumbUri
            } else {
                return@withContext meme.thumbUri
            }
        }

        if (meme.kind == "video") {
            try {
                val extractedPath = VideoFrameExtractor.extract(context, meme.uri, -1.0)
                val tempFile = File(extractedPath.removePrefix("file://"))
                if (tempFile.exists()) {
                    val thumbsDir = File(context.filesDir, "thumbs")
                    thumbsDir.mkdirs()
                    val destFile = File(thumbsDir, "thumb_${meme.id}_${System.currentTimeMillis()}.jpg")
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                    val resultUri = Uri.fromFile(destFile).toString()
                    store.setThumb(meme.id, resultUri)
                    return@withContext resultUri
                }
            } catch (_: Exception) {}
        }

        // A video without a poster has none: its own file is not an image for callers to load.
        if (meme.kind == "video") "" else meme.uri
    }

    suspend fun frames(meme: Meme, maxFrames: Int = 4): List<String> = withContext(Dispatchers.IO) {
        val boundedCount = maxFrames.coerceIn(1, 8)
        val results = mutableListOf<String>()

        if (meme.kind == "image") {
            val uprightFile = File(context.cacheDir, "frame_${meme.id}_${System.currentTimeMillis()}_0.jpg")
            val success = MediaIoUtils.writeUprightJpeg(context, Uri.parse(meme.uri), uprightFile)
            if (success && uprightFile.exists()) {
                results.add(Uri.fromFile(uprightFile).toString())
            } else {
                results.add(meme.uri)
            }
            return@withContext results
        }

        // Video: extract frames at bounded distinct timestamps
        try {
            val probe = MemeMediaProbe.probe(context, meme.uri)
            val durSec = (probe.durationUs ?: 1_000_000L) / 1_000_000.0

            for (i in 1..boundedCount) {
                val t = (durSec * i) / (boundedCount + 1)
                try {
                    val extractedPath = VideoFrameExtractor.extract(context, meme.uri, t)
                    val temp = File(extractedPath.removePrefix("file://"))
                    if (temp.exists()) {
                        val ownedFile = File(context.cacheDir, "frame_${meme.id}_${System.currentTimeMillis()}_$i.jpg")
                        temp.copyTo(ownedFile, overwrite = true)
                        temp.delete()
                        results.add(Uri.fromFile(ownedFile).toString())
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            val thumb = thumbnail(meme)
            if (thumb.isNotBlank()) results.add(thumb)
        }

        results
    }

    suspend fun deleteOriginal(meme: Meme): Boolean = withContext(Dispatchers.IO) {
        deleteFile(meme.uri)
    }

    suspend fun deleteFile(uriStr: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "content") {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else if (uri.scheme == "file") {
                val f = File(uri.path ?: return@withContext false)
                f.delete()
            } else {
                val f = File(uriStr)
                f.delete()
            }
        } catch (_: Exception) {
            false
        }
    }
}
