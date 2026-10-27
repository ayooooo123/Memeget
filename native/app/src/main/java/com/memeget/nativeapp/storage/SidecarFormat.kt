package com.memeget.nativeapp.storage

import com.memeget.nativeapp.Exemplar
import com.memeget.nativeapp.Meme
import com.memeget.nativeapp.SqliteLibraryStore
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.VectorRecord
import com.memeget.nativeapp.inference.ModelRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Base64

internal object SidecarFormat {

    const val NATIVE_SIDECAR_DIR = ".memeget-native"
    const val NATIVE_SIDECAR_FORMAT = "memeget-native-sidecar"
    const val NATIVE_SIDECAR_VERSION = 1

    const val LEGACY_SIDECAR_DIR = ".memeget"
    const val LEGACY_SIDECAR_FORMAT = "memeget-sidecar"
    const val LEGACY_SIDECAR_VERSION_2 = 2

    const val SIDECAR_CHUNKS = 64
    const val PRIMARY_DIM = 512

    fun chunkFor(name: String): String {
        var h = 0x811c9dc5.toInt()
        for (i in 0 until name.length) {
            h = h xor name[i].code
            h *= 0x01000193
        }
        val u = java.lang.Integer.toUnsignedLong(h)
        val chunkIdx = (u % SIDECAR_CHUNKS).toInt()
        return "%02x".format(chunkIdx)
    }

    private val GENERATION_FILE = Regex("""^(?:library_(\d+)_[0-9a-f]{2}|teachings_(\d+)|manifest_(\d+))\.json$""")

    /**
     * Which of the backup files [names] can go once generation [current] is committed: those of older generations,
     * except the newest older one with its own manifest, which stays as a fallback. Newer generations (another
     * install writing the same folder) and files of no generation, such as `manifest.json`, always stay.
     */
    fun staleGenerationFiles(names: Collection<String>, current: Long): List<String> {
        val generationOf = names.associateWith { name ->
            GENERATION_FILE.matchEntire(name)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toLongOrNull()
        }
        val fallback = generationOf.values.filterNotNull()
            .filter { it < current && "manifest_$it.json" in names }
            .maxOrNull()
        return generationOf.filter { (_, generation) -> generation != null && generation < current && generation != fallback }
            .keys.sorted()
    }

    fun digest(payload: String): String {
        var h = 0x811c9dc5.toInt()
        for (i in 0 until payload.length) {
            h = h xor payload[i].code
            h *= 0x01000193
        }
        val u = java.lang.Integer.toUnsignedLong(h)
        val lenStr = java.lang.Long.toString(payload.length.toLong(), 36)
        val hashStr = java.lang.Long.toString(u, 36)
        return "$lenStr.$hashStr"
    }

    fun readVerified(input: InputStream, expectedDigest: String): String? {
        // Version 1 uses SHA-256 for streamed backup chunks and length.FNV for
        // older chunks and teachings. Hash the original bytes for SHA-256.
        val sha256 = if (expectedDigest.length == 64) MessageDigest.getInstance("SHA-256") else null
        val checkedInput = if (sha256 != null) DigestInputStream(input, sha256) else input
        val payload = checkedInput.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val actualDigest = sha256?.digest()?.toHexString() ?: digest(payload)
        return payload.takeIf { actualDigest == expectedDigest }
    }

    fun encodeVector(floats: FloatArray?): String {
        if (floats == null || floats.isEmpty()) return ""
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) buf.putFloat(f)
        return Base64.getEncoder().encodeToString(buf.array())
    }

    fun decodeVector(b64: String?): FloatArray? {
        if (b64.isNullOrBlank()) return null
        val bytes = try {
            Base64.getDecoder().decode(b64.trim())
        } catch (_: Exception) {
            return null
        }
        if (bytes.isEmpty() || bytes.size % 4 != 0) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buf.float
        }
        return floats
    }

    data class ChunkStamp(
        val file: String,
        val count: Int,
        val digest: String,
        val bytes: Int
    )

    data class NativeManifest(
        val format: String,
        val version: Int,
        val generation: Long,
        val writtenAt: Long,
        val model: String,
        val dim: Int,
        val memeCount: Int,
        val chunks: Map<String, ChunkStamp>,
        val teachings: ChunkStamp
    )

    data class RestoredMeme(
        val meme: Meme,
        val vectorRecord: VectorRecord?
    )

    fun serializeNativeMemeJsonObject(m: Meme, v: VectorRecord?): JSONObject {
        val obj = JSONObject()
        obj.put("name", m.name)
        obj.put("kind", m.kind)
        obj.put("contentHash", m.contentHash)
        obj.put("modifiedAt", m.modifiedAt)
        obj.put("ocr", m.ocr)
        if (m.vlmOcr.isNotBlank()) obj.put("vlmOcr", m.vlmOcr)
        obj.put("caption", m.caption)
        obj.put("transcript", m.transcript)
        obj.put("extraTerms", m.extraTerms)
        obj.put("visionState", m.visionState)
        obj.put("audioState", m.audioState)
        obj.put("cloudCaption", m.cloudCaption)
        obj.put("cloudTerms", m.cloudTerms)
        obj.put("legacyImported", m.legacyImported)
        if (m.copyCount > 0) obj.put("copyCount", m.copyCount)
        if (m.lastCopiedAt > 0L) obj.put("lastCopiedAt", m.lastCopiedAt)
        val tagsArray = JSONArray()
        for (t in m.tags) {
            val tagObj = JSONObject()
            tagObj.put("label", t.label)
            tagObj.put("category", t.category)
            tagObj.put("score", t.score.toDouble())
            tagObj.put("source", t.source)
            tagObj.put("scoreDomain", t.scoreDomain)
            tagObj.put("confirmation", t.confirmation)
            tagObj.put("provenance", t.provenance)
            tagsArray.put(tagObj)
        }
        obj.put("tags", tagsArray)

        if (m.rejectedLabels.isNotEmpty()) {
            val rejArray = JSONArray()
            for (r in m.rejectedLabels) rejArray.put(r)
            obj.put("rejectedLabels", rejArray)
        }

        if (m.cloudObservation != null) {
            try {
                obj.put("cloudObservation", JSONObject(SqliteLibraryStore.serializeCloudResult(m.cloudObservation)))
            } catch (_: Exception) {}
        }

        if (v != null) {
            obj.put("model", v.model)
            obj.put("embedding", encodeVector(v.image))
            if (v.caption != null && m.caption.isNotBlank()) {
                obj.put("captionEmbedding", encodeVector(v.caption))
            }
            if (v.visual != null) {
                obj.put("visualEmbedding", encodeVector(v.visual))
                obj.put("visualModel", v.visualModel)
            }
            if (v.scenes != null && v.scenes.isNotEmpty()) {
                obj.put("sceneEmbeddings", encodeVector(v.scenes))
            }
        }
        return obj
    }

    fun serializeNativeMemeItem(m: Meme, v: VectorRecord?): String {
        return serializeNativeMemeJsonObject(m, v).toString()
    }

    fun serializeNativeChunk(
        generation: Long,
        chunkKey: String,
        memes: List<Pair<Meme, VectorRecord?>>
    ): String {
        val root = JSONObject()
        root.put("format", NATIVE_SIDECAR_FORMAT)
        root.put("version", NATIVE_SIDECAR_VERSION)
        root.put("generation", generation)
        root.put("chunk", chunkKey)
        root.put("count", memes.size)

        val memesArray = JSONArray()
        for ((m, v) in memes) {
            memesArray.put(serializeNativeMemeJsonObject(m, v))
        }
        root.put("memes", memesArray)
        return root.toString()
    }

    fun parseNativeChunk(
        jsonText: String,
        uriByNameOrHash: (name: String, hash: String) -> Pair<String, String>?
    ): List<RestoredMeme> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }

        if (root.optString("format") != NATIVE_SIDECAR_FORMAT) return emptyList()
        val memesArray = root.optJSONArray("memes") ?: return emptyList()

        val results = mutableListOf<RestoredMeme>()
        for (i in 0 until memesArray.length()) {
            val obj = memesArray.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            val contentHash = obj.optString("contentHash")
            if (name.isBlank() && contentHash.isBlank()) continue

            val target = uriByNameOrHash(name, contentHash) ?: continue
            // The resolver validates nonblank hashes; a filename alone cannot transfer reviewed evidence.
            val contentVerified = contentHash.isNotBlank()
            val uri = target.first
            val kind = target.second.ifBlank { obj.optString("kind", "image") }

            val tagsList = mutableListOf<Tag>()
            val tagsArray = obj.optJSONArray("tags")
            if (tagsArray != null) {
                for (j in 0 until tagsArray.length()) {
                    val tObj = tagsArray.optJSONObject(j) ?: continue
                    val label = tObj.optString("label").trim()
                    if (label.isBlank()) continue
                    tagsList.add(
                        Tag(
                            label = label,
                            category = tObj.optString("category", "topic"),
                            score = if (contentVerified) tObj.optDouble("score", 0.0).toFloat() else 0f,
                            source = if (contentVerified) tObj.optString("source", "prompt") else "legacy",
                            scoreDomain = if (contentVerified) tObj.optString("scoreDomain", "uncalibrated") else "uncalibrated",
                            confirmation = if (contentVerified) tObj.optString("confirmation", "unreviewed") else "unreviewed",
                            provenance = if (contentVerified) tObj.optString("provenance", "") else "legacy-native-sidecar"
                        )
                    )
                }
            }

            val caption = obj.optString("caption")
            val imageVec = if (contentVerified) decodeVector(obj.optString("embedding")) else null
            val captionVec = if (contentVerified && caption.isNotBlank()) decodeVector(obj.optString("captionEmbedding")) else null
            val visualVec = if (contentVerified) decodeVector(obj.optString("visualEmbedding")) else null
            val scenesVec = if (contentVerified) decodeVector(obj.optString("sceneEmbeddings")) else null
            val visualModel = obj.optString("visualModel")
            val model = obj.optString("model")
            val rejList = mutableListOf<String>()
            val rejArray = if (contentVerified) obj.optJSONArray("rejectedLabels") else null
            if (rejArray != null) {
                for (j in 0 until rejArray.length()) {
                    val s = rejArray.optString(j).trim()
                    if (s.isNotBlank()) rejList.add(s)
                }
            }

            val obsObj = if (contentVerified) obj.optJSONObject("cloudObservation") else null
            val cloudObs = obsObj?.let { SqliteLibraryStore.deserializeCloudResult(it.toString()) }

            val vectorRecord = if (imageVec != null && imageVec.size == PRIMARY_DIM) {
                VectorRecord(
                    id = 0L,
                    image = imageVec,
                    caption = if (caption.isNotBlank()) captionVec else null,
                    visual = visualVec,
                    model = model,
                    visualModel = visualModel,
                    scenes = scenesVec?.takeIf { it.size >= PRIMARY_DIM * 2 && it.size % PRIMARY_DIM == 0 },
                )
            } else null

            val isCompatible = vectorRecord != null && ModelRegistry.isCompatiblePrimaryModel(vectorRecord.model)

            val restoredMeme = Meme(
                id = 0L,
                uri = uri,
                name = name,
                kind = kind,
                ocr = obj.optString("ocr"),
                caption = caption,
                transcript = obj.optString("transcript"),
                tags = tagsList,
                extraTerms = obj.optString("extraTerms"),
                modifiedAt = obj.optLong("modifiedAt", 0L),
                pending = !isCompatible,
                visionState = if (isCompatible) obj.optString("visionState", "done") else "pending",
                audioState = if (!contentVerified && kind == "video") "pending" else obj.optString("audioState", "none"),
                contentHash = contentHash,
                cloudCaption = obj.optString("cloudCaption"),
                cloudTerms = obj.optString("cloudTerms"),
                legacyImported = !contentVerified || obj.optBoolean("legacyImported", false),
                cloudObservation = cloudObs,
                rejectedLabels = rejList,
                vlmOcr = obj.optString("vlmOcr"),
                copyCount = obj.optInt("copyCount", 0).coerceAtLeast(0),
                lastCopiedAt = obj.optLong("lastCopiedAt", 0L).coerceAtLeast(0L),
            )
            results.add(RestoredMeme(restoredMeme, vectorRecord))
        }

        return results
    }

    fun parseLegacyChunk(
        jsonText: String,
        uriByName: (name: String) -> Pair<String, String>?,
    ): List<RestoredMeme> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }

        if (root.optString("format") != LEGACY_SIDECAR_FORMAT) return emptyList()
        val memesArray = root.optJSONArray("memes") ?: return emptyList()

        val results = mutableListOf<RestoredMeme>()
        for (i in 0 until memesArray.length()) {
            val obj = memesArray.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name.isBlank()) continue

            val target = uriByName(name) ?: continue
            val uri = target.first
            val kind = target.second.ifBlank { obj.optString("kind", "image") }

            // Name-only legacy matches are marked with legacy provenance and unreviewed confirmation
            val tagsList = mutableListOf<Tag>()
            val tagsArray = obj.optJSONArray("tags")
            if (tagsArray != null) {
                for (j in 0 until tagsArray.length()) {
                    val tObj = tagsArray.optJSONObject(j) ?: continue
                    val label = tObj.optString("label").trim()
                    if (label.isBlank()) continue
                    tagsList.add(
                        Tag(
                            label = label,
                            category = tObj.optString("category", "topic"),
                            score = 0f,
                            source = "legacy",
                            scoreDomain = "uncalibrated",
                            confirmation = "unreviewed",
                            provenance = "legacy-sidecar"
                        )
                    )
                }
            }

            val caption = obj.optString("caption")

            val restoredMeme = Meme(
                id = 0L,
                uri = uri,
                name = name,
                kind = kind,
                ocr = obj.optString("ocr"),
                caption = caption,
                transcript = obj.optString("transcript"),
                tags = tagsList,
                extraTerms = obj.optString("extraTerms"),
                modifiedAt = obj.optLong("modifiedAt", 0L),
                pending = true,
                visionState = "pending",
                // Preserve completed silent audio
                audioState = obj.optString("audioState", "none"),
                contentHash = "",
                cloudCaption = "",
                cloudTerms = "",
                legacyImported = true // Legacy file name matching is unverifiable content identity
            )

            // Legacy sidecars cannot verify that the current file has the backed-up bytes.
            results.add(RestoredMeme(restoredMeme, null))
        }

        return results
    }

    fun serializeNativeManifest(
        generation: Long,
        chunks: Map<String, ChunkStamp>,
        teachings: ChunkStamp,
        totalMemeCount: Int,
        writtenAt: Long = System.currentTimeMillis()
    ): String {
        val root = JSONObject()
        root.put("format", NATIVE_SIDECAR_FORMAT)
        root.put("version", NATIVE_SIDECAR_VERSION)
        root.put("generation", generation)
        root.put("writtenAt", writtenAt)
        root.put("model", ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
        root.put("memeCount", totalMemeCount)

        val chunksObj = JSONObject()
        for ((k, stamp) in chunks) {
            val sObj = JSONObject()
            sObj.put("file", stamp.file)
            sObj.put("count", stamp.count)
            sObj.put("digest", stamp.digest)
            sObj.put("bytes", stamp.bytes)
            chunksObj.put(k, sObj)
        }
        root.put("chunks", chunksObj)

        val teachObj = JSONObject()
        teachObj.put("file", teachings.file)
        teachObj.put("count", teachings.count)
        teachObj.put("digest", teachings.digest)
        teachObj.put("bytes", teachings.bytes)
        root.put("teachings", teachObj)

        return root.toString()
    }

    fun parseNativeManifest(jsonText: String): NativeManifest? {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return null
        }

        if (root.optString("format") != NATIVE_SIDECAR_FORMAT) return null
        val version = root.optInt("version", 0)
        if (version <= 0 || version > NATIVE_SIDECAR_VERSION) return null

        val generation = root.optLong("generation", 0L)
        val writtenAt = root.optLong("writtenAt", 0L)
        val model = root.optString("model")
        val dim = root.optInt("dim", 0)
        val memeCount = root.optInt("memeCount", 0)

        val chunksMap = mutableMapOf<String, ChunkStamp>()
        val chunksObj = root.optJSONObject("chunks")
        if (chunksObj != null) {
            val keys = chunksObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val cObj = chunksObj.optJSONObject(k) ?: continue
                chunksMap[k] = ChunkStamp(
                    file = cObj.optString("file"),
                    count = cObj.optInt("count"),
                    digest = cObj.optString("digest"),
                    bytes = cObj.optInt("bytes")
                )
            }
        }

        val teachObj = root.optJSONObject("teachings")
        val teachingsStamp = if (teachObj != null) {
            ChunkStamp(
                file = teachObj.optString("file"),
                count = teachObj.optInt("count"),
                digest = teachObj.optString("digest"),
                bytes = teachObj.optInt("bytes")
            )
        } else {
            ChunkStamp(file = "", count = 0, digest = "", bytes = 0)
        }

        return NativeManifest(
            format = NATIVE_SIDECAR_FORMAT,
            version = version,
            generation = generation,
            writtenAt = writtenAt,
            model = model,
            dim = dim,
            memeCount = memeCount,
            chunks = chunksMap,
            teachings = teachingsStamp
        )
    }
}
