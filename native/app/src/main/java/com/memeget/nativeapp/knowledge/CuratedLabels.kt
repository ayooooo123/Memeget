package com.memeget.nativeapp.knowledge

import android.content.Context
import com.memeget.nativeapp.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.memeget.nativeapp.Tag
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A bundled label. [associations] are its search words; for a TV show or movie they are also the names that give a
 * meme the title, and [names] says which of them name the title itself, not a character (Titles.Names).
 */
internal data class CuratedLabelDef(
    val label: String,
    val prompt: String,
    val category: String,
    val associations: List<String>,
    val vec: FloatArray,
    val names: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CuratedLabelDef
        return label == other.label
    }
    override fun hashCode(): Int = label.hashCode()
}

internal data class NegativeAnchorDef(
    val text: String,
    val vec: FloatArray,
)

internal object CuratedLabels {

    /** "MGL3": prompt vectors from [com.memeget.nativeapp.inference.ClipTokenizer] ids. */
    private const val CACHE_VERSION = 0x4D474C33

    data class OcrRule(val regex: Regex, val label: String, val category: String)

    val OCR_RULES = listOf(
        OcrRule(Regex("remilia|milady", RegexOption.IGNORE_CASE), "Milady", "character"),
        OcrRule(Regex("apustaja", RegexOption.IGNORE_CASE), "Apu Apustaja", "character"),
        OcrRule(Regex("pepe(\\s|the|$)", RegexOption.IGNORE_CASE), "Pepe the Frog", "character"),
        OcrRule(Regex("\\bgroyper\\b", RegexOption.IGNORE_CASE), "Groyper", "character"),
        OcrRule(Regex("\\bwagmi\\b|\\bngmi\\b|\\bhodl\\b|diamond hands", RegexOption.IGNORE_CASE), "Crypto / NFT", "topic"),
    )

    fun ocrTags(text: String): List<Tag> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Tag>()
        for (r in OCR_RULES) {
            if (r.regex.containsMatchIn(text)) {
                out.add(
                    Tag(
                        label = r.label.lowercase(),
                        category = r.category,
                        score = 1.0f,
                        source = "ocr",
                        scoreDomain = "rule",
                        confirmation = "unreviewed",
                        provenance = "ocr",
                    )
                )
            }
        }
        return out
    }

    /** The bundled labels and anchors as written, without vectors; reading them needs no model. */
    fun loadDefinitions(context: Context): Pair<List<CuratedLabelDef>, List<NegativeAnchorDef>> =
        parseDefinitions(context.assets.open("knowledge/label-prompts.json").use { it.readBytes() })

    private fun parseDefinitions(source: ByteArray): Pair<List<CuratedLabelDef>, List<NegativeAnchorDef>> {
        val root = JSONObject(source.toString(Charsets.UTF_8))
        val labelRows = root.getJSONArray("labels")
        val anchorRows = root.getJSONArray("anchors")
        val labels = ArrayList<CuratedLabelDef>(labelRows.length())
        val anchors = ArrayList<NegativeAnchorDef>(anchorRows.length())
        for (index in 0 until labelRows.length()) {
            val row = labelRows.getJSONObject(index)
            val associations = row.optJSONArray("associations")
            val names = row.optJSONArray("names")
            labels += CuratedLabelDef(
                row.getString("label"), row.getString("prompt"), row.optString("category", "topic"),
                List(associations?.length() ?: 0) { associations!!.getString(it) }, FloatArray(0),
                List(names?.length() ?: 0) { names!!.getString(it) },
            )
        }
        for (index in 0 until anchorRows.length()) {
            anchors += NegativeAnchorDef(anchorRows.getJSONObject(index).getString("text"), FloatArray(0))
        }
        return labels to anchors
    }

    /**
     * The bundled labels and anchors with their text vectors: from the cache file when it matches this build's labels
     * and text model, else encoded now with the text model, unless [encodeIfMissing] is false, which returns null then.
     */
    suspend fun loadLabelVectors(
        context: Context?,
        engine: InferenceEngine,
        encodeIfMissing: Boolean = true,
    ): Pair<List<CuratedLabelDef>, List<NegativeAnchorDef>>? =
        withContext(Dispatchers.IO) {
            val app = requireNotNull(context) { "Curated knowledge requires application assets" }
            val source = app.assets.open("knowledge/label-prompts.json").use { it.readBytes() }
            val sourceHash = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
            val (labels, anchors) = parseDefinitions(source)
            // Encode with this adapter; legacy vectors cannot establish recipe compatibility.
            // Cache native prompts under both the complete recipe and source digest. The header's version
            // (MGL3) marks prompts tokenized by ClipTokenizer; MGL2 caches hold the old tokenizer's vectors.
            val cache = File(app.filesDir, "native_label_vectors.bin")
            fun valid(vector: FloatArray): Boolean {
                var norm = 0.0
                for (value in vector) {
                    if (!value.isFinite()) return false
                    norm += value.toDouble() * value
                }
                return vector.size == 512 && norm in 0.99..1.01
            }
            if (cache.isFile && cache.length() <= 4L * 1024 * 1024) {
                try {
                    return@withContext DataInputStream(cache.inputStream().buffered()).use { input ->
                        require(input.readInt() == CACHE_VERSION)
                        require(input.readUTF() == engine.primaryModel && input.readUTF() == sourceHash)
                        require(input.readInt() == labels.size && input.readInt() == anchors.size)
                        fun vector(): FloatArray = FloatArray(512) { input.readFloat() }.also { require(valid(it)) }
                        val readyLabels = labels.map { it.copy(vec = vector()) }
                        val readyAnchors = anchors.map { it.copy(vec = vector()) }
                        require(input.read() == -1)
                        readyLabels to readyAnchors
                    }
                } catch (_: java.io.IOException) {
                    // Interrupted or obsolete caches are rebuilt, never used as foreign vectors.
                } catch (_: IllegalArgumentException) {
                    // Header, dimensions, recipe or vector validation failed.
                }
            }
            if (!encodeIfMissing) return@withContext null
            val readyLabels = labels.map {
                currentCoroutineContext().ensureActive()
                it.copy(vec = engine.embedText(it.prompt).also { vector -> require(valid(vector)) })
            }
            val readyAnchors = anchors.map {
                currentCoroutineContext().ensureActive()
                it.copy(vec = engine.embedText(it.text).also { vector -> require(valid(vector)) })
            }
            val temporary = File.createTempFile("label_vectors_", ".tmp", app.filesDir)
            try {
                DataOutputStream(temporary.outputStream().buffered()).use { output ->
                    output.writeInt(CACHE_VERSION)
                    output.writeUTF(engine.primaryModel)
                    output.writeUTF(sourceHash)
                    output.writeInt(readyLabels.size)
                    output.writeInt(readyAnchors.size)
                    for (label in readyLabels) for (value in label.vec) output.writeFloat(value)
                    for (anchor in readyAnchors) for (value in anchor.vec) output.writeFloat(value)
                }
                currentCoroutineContext().ensureActive()
                Files.move(temporary.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.delete()
            }
            readyLabels to readyAnchors
        }
}
