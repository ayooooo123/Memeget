package com.memeget.nativeapp.editor

import android.content.Context
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
internal object DraftJournalStore {

    const val DRAFT_EXPIRY_MS = 7L * 24L * 60L * 60L * 1000L // 7 days

    data class SourceIdentity(
        val uri: String,
        val name: String = "",
        val kind: String = "image",
        val width: Int = 0,
        val height: Int = 0,
        val durationUs: Long? = null,
        val byteSize: Long? = null,
        val modifiedTimeMs: Long? = null
    )

    data class DraftRestoreResult(
        val restored: Boolean,
        val project: MemeEditProject? = null,
        val savedAtMs: Long = 0L,
        val hasCorruptedSlot: Boolean = false,
        val reason: String? = null
    )

    private val journalMutex = Mutex()

    private fun draftDir(context: Context, sessionId: String): File {
        val dir = File(context.cacheDir, "meme_drafts/$sessionId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sessionAssetsDir(context: Context, sessionId: String): File {
        val dir = File(context.cacheDir, "meme_drafts/$sessionId/assets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun computeChecksum(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    suspend fun saveDraft(
        context: Context,
        sessionId: String,
        project: MemeEditProject,
        sourceByteSize: Long? = null,
        sourceModifiedTimeMs: Long? = null
    ): Boolean = journalMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val dir = draftDir(context, sessionId)
                val slotA = File(dir, "slot_a.json")
                val slotB = File(dir, "slot_b.json")

                val genA = readGeneration(slotA)
                val genB = readGeneration(slotB)

                val slotACorrupted = slotA.exists() && slotA.length() > 0L && readValidDraft(slotA) == null
                val slotBCorrupted = slotB.exists() && slotB.length() > 0L && readValidDraft(slotB) == null

                // Do not overwrite an unreadable/corrupted draft slot
                val (targetSlot, nextGen) = when {
                    slotACorrupted && !slotBCorrupted -> Pair(slotB, max(0L, genB) + 1L)
                    slotBCorrupted && !slotACorrupted -> Pair(slotA, max(0L, genA) + 1L)
                    genA >= genB -> Pair(slotB, genA + 1L)
                    else -> Pair(slotA, genB + 1L)
                }

                val projectJson = project.toJson()
                val payload = JSONObject().apply {
                    put("version", 1)
                    put("generation", nextGen)
                    put("savedAtMs", System.currentTimeMillis())
                    put("sourceUri", project.source.uri)
                    put("sourceName", project.source.name)
                    put("sourceKind", project.source.kind.wire)
                    put("sourceWidth", project.source.width)
                    put("sourceHeight", project.source.height)
                    if (project.source.durationUs != null) put("durationUs", project.source.durationUs)
                    if (sourceByteSize != null) put("byteSize", sourceByteSize)
                    if (sourceModifiedTimeMs != null) put("modifiedTimeMs", sourceModifiedTimeMs)
                    put("project", projectJson)
                }

                val payloadStr = payload.toString()
                val checksum = computeChecksum(payloadStr)

                val envelope = JSONObject().apply {
                    put("checksum", checksum)
                    put("payload", payload)
                }

                val tempFile = File(dir, "temp_${System.currentTimeMillis()}.json")
                FileOutputStream(tempFile).use { out ->
                    out.write(envelope.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                }

                if (!tempFile.renameTo(targetSlot)) {
                    tempFile.copyTo(targetSlot, overwrite = true)
                    tempFile.delete()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun restoreDraft(
        context: Context,
        sessionId: String,
        expectedSourceUri: String
    ): DraftRestoreResult = restoreDraft(
        context = context,
        sessionId = sessionId,
        expectedSource = SourceIdentity(uri = expectedSourceUri)
    )

    suspend fun restoreDraft(
        context: Context,
        sessionId: String,
        expectedSource: SourceIdentity
    ): DraftRestoreResult = journalMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val dir = draftDir(context, sessionId)
                val slotA = File(dir, "slot_a.json")
                val slotB = File(dir, "slot_b.json")

                val slotAExists = slotA.exists() && slotA.length() > 0L
                val slotBExists = slotB.exists() && slotB.length() > 0L

                val draftA = readValidDraft(slotA)
                val draftB = readValidDraft(slotB)

                val hasCorruptedSlot = (slotAExists && draftA == null) || (slotBExists && draftB == null)

                val bestDraft = when {
                    draftA != null && draftB != null -> if (draftA.generation >= draftB.generation) draftA else draftB
                    draftA != null -> draftA
                    draftB != null -> draftB
                    else -> null
                }

                if (bestDraft == null) {
                    return@withContext DraftRestoreResult(
                        restored = false,
                        hasCorruptedSlot = hasCorruptedSlot,
                        reason = if (hasCorruptedSlot) "Unreadable draft slot preserved" else "No valid draft found"
                    )
                }

                val now = System.currentTimeMillis()
                if (now - bestDraft.savedAtMs > DRAFT_EXPIRY_MS) {
                    discardDraftLocked(context, sessionId)
                    return@withContext DraftRestoreResult(restored = false, reason = "Draft expired")
                }

                if (bestDraft.sourceUri != expectedSource.uri) {
                    return@withContext DraftRestoreResult(restored = false, reason = "Source URI mismatch")
                }
                if (expectedSource.width > 0 && bestDraft.sourceWidth > 0 && bestDraft.sourceWidth != expectedSource.width) {
                    return@withContext DraftRestoreResult(restored = false, reason = "Source dimensions mismatch")
                }
                if (expectedSource.height > 0 && bestDraft.sourceHeight > 0 && bestDraft.sourceHeight != expectedSource.height) {
                    return@withContext DraftRestoreResult(restored = false, reason = "Source dimensions mismatch")
                }
                if (expectedSource.durationUs != null && bestDraft.durationUs != null && bestDraft.durationUs != expectedSource.durationUs) {
                    return@withContext DraftRestoreResult(restored = false, reason = "Source duration mismatch")
                }
                if (expectedSource.byteSize != null && bestDraft.byteSize != null && bestDraft.byteSize != expectedSource.byteSize) {
                    return@withContext DraftRestoreResult(restored = false, reason = "Source byte size mismatch")
                }
                if (expectedSource.modifiedTimeMs != null && bestDraft.modifiedTimeMs != null && bestDraft.modifiedTimeMs != expectedSource.modifiedTimeMs) {
                    return@withContext DraftRestoreResult(restored = false, reason = "Source modified time mismatch")
                }

                DraftRestoreResult(
                    restored = true,
                    project = bestDraft.project,
                    savedAtMs = bestDraft.savedAtMs,
                    hasCorruptedSlot = hasCorruptedSlot
                )
            } catch (e: Exception) {
                DraftRestoreResult(restored = false, reason = e.message)
            }
        }
    }

    suspend fun discardDraft(
        context: Context,
        sessionId: String
    ): Unit = journalMutex.withLock {
        withContext(Dispatchers.IO) {
            discardDraftLocked(context, sessionId)
        }
    }

    // The caller owns journalMutex and runs on the IO dispatcher.
    private fun discardDraftLocked(context: Context, sessionId: String) {
        try {
            val dir = File(context.cacheDir, "meme_drafts/$sessionId")
            if (dir.exists()) dir.deleteRecursively()
        } catch (_: Exception) {}
    }

    fun createSessionAssetFile(context: Context, sessionId: String, extension: String = "png"): File {
        val dir = sessionAssetsDir(context, sessionId)
        return File(dir, "asset_${System.currentTimeMillis()}_${(1000..9999).random()}.$extension")
    }

    private data class ValidDraft(
        val generation: Long,
        val savedAtMs: Long,
        val sourceUri: String,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val durationUs: Long?,
        val byteSize: Long?,
        val modifiedTimeMs: Long?,
        val project: MemeEditProject
    )

    private fun readGeneration(file: File): Long {
        if (!file.exists() || !file.isFile) return -1L
        return try {
            val content = file.readText(Charsets.UTF_8)
            val root = JSONObject(content)
            root.optJSONObject("payload")?.optLong("generation", -1L) ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun readValidDraft(file: File): ValidDraft? {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            val root = JSONObject(content)
            val checksum = root.optString("checksum", "")
            val payload = root.optJSONObject("payload") ?: return null

            val payloadStr = payload.toString()
            if (computeChecksum(payloadStr) != checksum) return null

            val generation = payload.optLong("generation", -1L)
            val savedAtMs = payload.optLong("savedAtMs", 0L)
            val sourceUri = payload.optString("sourceUri", "")
            val sourceWidth = payload.optInt("sourceWidth", 0)
            val sourceHeight = payload.optInt("sourceHeight", 0)
            val durationUs = if (payload.has("durationUs")) payload.optLong("durationUs") else null
            val byteSize = if (payload.has("byteSize")) payload.optLong("byteSize") else null
            val modifiedTimeMs = if (payload.has("modifiedTimeMs")) payload.optLong("modifiedTimeMs") else null
            val projectJson = payload.optJSONObject("project") ?: return null
            val project = MemeEditProject.fromJson(projectJson)

            ValidDraft(generation, savedAtMs, sourceUri, sourceWidth, sourceHeight, durationUs, byteSize, modifiedTimeMs, project)
        } catch (_: Exception) {
            null
        }
    }
}
