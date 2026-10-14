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

    data class Record(val file: String, val analysis: ImportedAnalysis)

    fun read(file: File): List<Record> {
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
                    // Exporters write the header first; any other member order costs a second pass.
                    "memes" -> if (headerRead == 7) records = readMemes(reader, format, version, model) else reader.skipValue()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return records ?: JsonReader(file.bufferedReader(Charsets.UTF_8)).use { reader ->
            var found: List<Record> = emptyList()
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "memes") found = readMemes(reader, format, version, model) else reader.skipValue()
            }
            reader.endObject()
            found
        }
    }

    private fun readMemes(reader: JsonReader, format: String, version: Int, model: String): List<Record> {
        require(format == FORMAT) { "Not a Memeget collection manifest" }
        val vectorsTrusted = version >= FIRST_ORIGINAL_MEDIA_VERSION && compatibleModel(model)
        val records = ArrayList<Record>()
        reader.beginArray()
        while (reader.hasNext()) readRecord(reader, vectorsTrusted)?.let(records::add)
        reader.endArray()
        return records
    }

    // The original app stamps the export header "mobileclip-s2" and each item "mobileclip-s2@512".
    private fun compatibleModel(model: String): Boolean =
        model == TeachingPackIo.PACK_MODEL || model == "${TeachingPackIo.PACK_MODEL}@512" ||
            model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT

    private fun readRecord(reader: JsonReader, vectorsTrusted: Boolean): Record? {
        var file: String? = null
        var tags: List<Tag> = emptyList()
        var ocr = ""
        var transcript = ""
        var embedding: FloatArray? = null
        var itemModel = ""
        var pending = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "file" -> file = optString(reader)
                "tags" -> tags = readHumanTags(reader)
                "ocr" -> ocr = optString(reader).orEmpty().trim()
                "transcript" -> transcript = optString(reader).orEmpty().trim()
                "embedding" -> if (vectorsTrusted) embedding = readUnitVector(reader) else reader.skipValue()
                "embeddingModel", "model" -> itemModel = optString(reader).orEmpty()
                "pending" -> pending = if (reader.peek() == JsonToken.BOOLEAN) reader.nextBoolean() else { reader.skipValue(); false }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val path = file?.replace('\\', '/')?.trimStart('/') ?: return null
        val image = embedding?.takeIf { !pending && (itemModel.isEmpty() || compatibleModel(itemModel)) }
        return Record(path, ImportedAnalysis(tags, ocr, transcript, image))
    }

    private fun readHumanTags(reader: JsonReader): List<Tag> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val out = ArrayList<Tag>()
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
            // Native exports record confirmation; the original app marks people's labels by source:
            // hand tags, and teach-sheet labels written at full score.
            val human = confirmation?.let { it == "confirmed" }
                ?: (source == "manual" || source == "exemplar" && score >= 0.999)
            if (norm.isEmpty() || !human) continue
            // Imported as hand tags so they train like the user's own bulk tags.
            val facet = category.trim().lowercase().takeUnless { it.isEmpty() || it == "user" } ?: "topic"
            out += Tag(norm, facet, 1f, "manual", "user", "confirmed", "collection-import")
        }
        reader.endArray()
        return out
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
