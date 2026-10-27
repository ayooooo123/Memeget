package com.memeget.nativeapp.storage

import android.util.JsonReader
import android.util.JsonToken
import com.memeget.nativeapp.ImportedAnalysis
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.data.TagMerge
import com.memeget.nativeapp.inference.ModelRegistry
import java.io.File
import kotlin.math.sqrt

/**
 * Reads a collection export's `manifest.json` without loading it whole (original-app
 * exports carry three vectors per item, ~90 MB for 2,400 memes). Only analysis that a
 * person or the media itself vouches for is kept: human-confirmed tags, OCR, transcripts
 * and, for original-media exports from a compatible encoder, the image vector. Machine
 * predictions are dropped so the native classifier rebuilds them.
 */
internal object CollectionManifest {
    const val FORMAT = "memeget-collection"

    /** Version 2 original-app exports hold the original media bytes these vectors describe. */
    private const val FIRST_ORIGINAL_MEDIA_VERSION = 2

    const val STAGE3_PACK_FORMAT = "memeget-stage3-pack"
    const val STAGE3_PACK_VERSION = 3

    data class Record(val file: String, val analysis: ImportedAnalysis)

    fun read(
        file: File,
        allowStage3Enrichment: Boolean = false,
        trustManifestMetadata: Boolean = false,
    ): List<Record> {
        var format = ""
        var version = 0
        var model = ""
        var headerRead = 0
        var records: List<Record>? = null
        JsonReader(file.bufferedReader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "format" -> { format = reader.nextString(); headerRead = headerRead or 1 }
                    "version" -> { version = reader.nextInt(); headerRead = headerRead or 2 }
                    "model" -> { model = reader.nextString(); headerRead = headerRead or 4 }
                    "memes" -> if (headerRead == 7) records = readMemes(reader, format, version, model, allowStage3Enrichment, trustManifestMetadata) else reader.skipValue()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return records ?: JsonReader(file.bufferedReader(Charsets.UTF_8)).use { reader ->
            var found: List<Record> = emptyList()
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "memes") found = readMemes(reader, format, version, model, allowStage3Enrichment, trustManifestMetadata) else reader.skipValue()
            }
            reader.endObject()
            found
        }
    }

    private fun readMemes(
        reader: JsonReader,
        format: String,
        version: Int,
        model: String,
        allowStage3Enrichment: Boolean,
        trustManifestMetadata: Boolean,
    ): List<Record> {
        require(format == FORMAT || format == STAGE3_PACK_FORMAT) { "Not a Memeget collection manifest" }
        val stage3Trusted = allowStage3Enrichment &&
            format == STAGE3_PACK_FORMAT &&
            version == STAGE3_PACK_VERSION &&
            model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT
        val effectiveMetadataTrust = trustManifestMetadata || stage3Trusted
        val vectorsTrusted = effectiveMetadataTrust && version >= FIRST_ORIGINAL_MEDIA_VERSION && compatibleModel(model)
        val records = ArrayList<Record>()
        reader.beginArray()
        while (reader.hasNext()) readRecord(reader, vectorsTrusted, stage3Trusted, effectiveMetadataTrust)?.let(records::add)
        reader.endArray()
        return records
    }

    // The original app stamps the export header "mobileclip-s2" and each item "mobileclip-s2@512".
    private fun compatibleModel(model: String): Boolean =
        model == TeachingPackIo.PACK_MODEL || model == "${TeachingPackIo.PACK_MODEL}@512" ||
            ModelRegistry.isCompatiblePrimaryModel(model)

    private data class ParsedTags(
        val human: List<Tag>,
        val rawVision: List<Triple<String, String, Float>>,
    )

    private fun readRecord(
        reader: JsonReader,
        vectorsTrusted: Boolean,
        stage3Trusted: Boolean,
        trustManifestMetadata: Boolean,
    ): Record? {
        var file: String? = null
        var parsedTags = ParsedTags(emptyList(), emptyList())
        var ocr = ""
        var transcript = ""
        var caption = ""
        var extraTerms = ""
        var twoDomainProvenance = ""
        var embedding: FloatArray? = null
        var itemModel = ""
        var pending = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "file" -> file = optString(reader)
                "tags" -> parsedTags = readTags(reader)
                "ocr" -> ocr = optString(reader).orEmpty().trim()
                "transcript" -> transcript = optString(reader).orEmpty().trim()
                "caption" -> caption = optString(reader).orEmpty().trim()
                "extraTerms" -> extraTerms = optString(reader).orEmpty().trim()
                "twoDomainProvenance" -> twoDomainProvenance = optString(reader).orEmpty().trim()
                "embedding" -> if (vectorsTrusted) embedding = readUnitVector(reader) else reader.skipValue()
                "embeddingModel", "model" -> itemModel = optString(reader).orEmpty()
                "pending" -> pending = if (reader.peek() == JsonToken.BOOLEAN) reader.nextBoolean() else { reader.skipValue(); false }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val path = file?.replace('\\', '/')?.trimStart('/') ?: return null
        val image = embedding?.takeIf { !pending && (itemModel.isEmpty() || compatibleModel(itemModel)) }
        val allowTwoDomain = stage3Trusted && image != null && twoDomainProvenance == "minicpm-v-4.7-1b"
        val twoDomTags = if (allowTwoDomain) {
            parsedTags.rawVision.map { (norm, facet, score) ->
                Tag(norm, facet, score, "vision", "two-domain", "unreviewed", "collection-import:$twoDomainProvenance")
            }
        } else emptyList()
        val trustedHumanTags = if (trustManifestMetadata) {
            parsedTags.human
        } else {
            parsedTags.human.map { t ->
                Tag(t.label, t.category, 0.5f, "imported_hint", "untrusted-import", "unreviewed", "untrusted-share")
            }
        }
        return Record(
            path,
            ImportedAnalysis(
                humanTags = trustedHumanTags,
                ocr = if (trustManifestMetadata) ocr else "",
                transcript = if (trustManifestMetadata) transcript else "",
                image = if (trustManifestMetadata) image else null,
                twoDomainCaption = if (allowTwoDomain) caption else "",
                twoDomainTerms = if (allowTwoDomain) extraTerms else "",
                twoDomainTags = twoDomTags,
            ),
        )
    }

    private fun readTags(reader: JsonReader): ParsedTags {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return ParsedTags(emptyList(), emptyList())
        }
        val humanOut = ArrayList<Tag>()
        val visionOut = ArrayList<Triple<String, String, Float>>()
        reader.beginArray()
        while (reader.hasNext()) {
            var label = ""
            var category = ""
            var source = ""
            var score = 0.0
            var confirmation: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "label" -> label = optString(reader).orEmpty()
                    "category" -> category = optString(reader).orEmpty()
                    "source" -> source = optString(reader).orEmpty()
                    "score" -> score = if (reader.peek() == JsonToken.NUMBER) reader.nextDouble() else { reader.skipValue(); 0.0 }
                    "confirmation" -> confirmation = optString(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val norm = TagMerge.normalizeTagLabel(label)
            if (norm.isEmpty()) continue
            val facet = category.trim().lowercase().takeUnless { it.isEmpty() || it == "user" } ?: "topic"
            val human = confirmation?.let { it == "confirmed" }
                ?: (source == "manual" || source == "exemplar" && score >= 0.999)
            if (human) {
                humanOut += Tag(norm, facet, 1f, "manual", "user", "confirmed", "collection-import")
            } else if (source == "vision" || source == "open_vocab") {
                visionOut += Triple(norm, facet, score.toFloat().coerceIn(0f, 1f))
            }
        }
        reader.endArray()
        return ParsedTags(humanOut, visionOut)
    }

    private fun readUnitVector(reader: JsonReader): FloatArray? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }
        val values = FloatArray(512)
        var count = 0
        var valid = true
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.NUMBER || count >= values.size) {
                reader.skipValue()
                valid = false
                continue
            }
            val value = reader.nextDouble().toFloat()
            if (!value.isFinite()) valid = false
            values[count++] = value
        }
        reader.endArray()
        if (!valid || count != values.size) return null
        val norm = sqrt(values.sumOf { it.toDouble() * it })
        return values.takeIf { norm in 0.99..1.01 }
    }

    private fun optString(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
}
