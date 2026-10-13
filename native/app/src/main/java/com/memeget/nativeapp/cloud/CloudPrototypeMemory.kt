package com.memeget.nativeapp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

internal data class PrototypeRecord(
    val label: String, val category: String, val vector: FloatArray, val modelFingerprint: String,
    val contentHash: String, val createdAt: Long, val confidence: Float, val provenance: String,
)
internal data class RejectionRecord(val contentHash: String, val label: String, val rejectedAt: Long)

/** Weak template hints, never training positives or captions copied between images. */
internal class CloudPrototypeMemory(private val storageDir: File, private val timeProvider: () -> Long = { System.currentTimeMillis() }) {
    private val file = File(storageDir, "cloud_memory.bin")
    private val prototypes = ArrayList<PrototypeRecord>()
    private val rejections = linkedMapOf<String, RejectionRecord>()
    private var saturated = false

    init { storageDir.mkdirs(); load() }

    @Synchronized
    fun addPrototype(label: String, category: String, vector: FloatArray, modelFingerprint: String, contentHash: String, confidence: Float, provenance: String) {
        if (saturated || !validVector(vector) || !validHash(contentHash) || isRejected(contentHash, label)) return
        require(label.isNotBlank() && label.length <= 160 && category.length <= 48 && modelFingerprint.length in 1..256 && provenance.length <= 512)
        require(confidence.isFinite() && confidence in 0f..1f)
        prune()
        val normalizedLabel = label.trim()
        prototypes.removeAll { it.label.equals(normalizedLabel, true) && it.contentHash == contentHash }
        while (prototypes.count { it.label.equals(normalizedLabel, true) } >= MAX_PER_LABEL) {
            prototypes.removeAt(prototypes.indexOfFirst { it.label.equals(normalizedLabel, true) })
        }
        while (prototypes.size >= MAX_PROTOTYPES) prototypes.removeAt(0)
        prototypes += PrototypeRecord(normalizedLabel, category, vector.copyOf(), modelFingerprint, contentHash, timeProvider(), confidence, provenance)
        trimToBytes()
        persist()
    }

    @Synchronized
    fun rejectLabel(contentHash: String, label: String) {
        require(validHash(contentHash) && label.isNotBlank() && label.length <= 160)
        val key = key(contentHash, label)
        if (key !in rejections && rejections.size >= MAX_REJECTIONS) {
            // Fail closed rather than evicting a rejection and resurrecting the discarded claim.
            saturated = true
            prototypes.clear()
        } else rejections[key] = RejectionRecord(contentHash, label.trim(), timeProvider())
        prototypes.removeAll { it.label.equals(label, true) && it.contentHash == contentHash }
        trimToBytes()
        persist()
    }

    @Synchronized
    fun findHints(queryVector: FloatArray, modelFingerprint: String, contentHash: String = "", limit: Int = 8): List<Tag> {
        if (saturated || !validVector(queryVector) || limit <= 0) return emptyList()
        if (prune()) persist()
        val best = HashMap<String, Pair<PrototypeRecord, Float>>()
        for (prototype in prototypes) {
            if (prototype.modelFingerprint != modelFingerprint || isRejected(contentHash, prototype.label)) continue
            var cosine = 0f
            for (i in queryVector.indices) cosine += queryVector[i] * prototype.vector[i]
            if (cosine < SIMILARITY_THRESHOLD) continue
            val labelKey = prototype.label.lowercase(Locale.ROOT)
            if (cosine > (best[labelKey]?.second ?: -1f)) best[labelKey] = prototype to cosine
        }
        return best.values.sortedWith(compareByDescending<Pair<PrototypeRecord, Float>> { it.second }.thenBy { it.first.label })
            .take(limit.coerceAtMost(32)).map { (prototype, cosine) ->
                Tag(prototype.label, prototype.category, cosine.coerceIn(0f, 1f), "cloud_hint", "cosine-not-accuracy", "unreviewed", prototype.provenance)
            }
    }

    @Synchronized fun isRejected(contentHash: String, label: String): Boolean = contentHash.isNotEmpty() && key(contentHash, label) in rejections
    @Synchronized fun count(): Int { if (prune()) persist(); return prototypes.size }
    @Synchronized fun clear() {
        prototypes.clear()
        // Retain rejection tombstones: forgetting observations must not restore a rejected identity.
        persist()
    }

    private fun prune(): Boolean {
        val now = timeProvider()
        return prototypes.removeAll { it.createdAt > now || now - it.createdAt > EXPIRY_MILLIS }
    }

    private fun trimToBytes() {
        while (encodedSize() > MAX_BYTES && prototypes.isNotEmpty()) prototypes.removeAt(0)
        require(encodedSize() <= MAX_BYTES) { "Cloud memory metadata exceeds its fixed budget" }
    }

    private fun encodedSize(): Int = 13 + rejections.values.sumOf { utfSize(it.contentHash) + utfSize(it.label) + 8 } +
        prototypes.sumOf { utfSize(it.label) + utfSize(it.category) + utfSize(it.modelFingerprint) + utfSize(it.contentHash) + utfSize(it.provenance) + 8 + 4 + 512 * 4 }

    private fun persist() {
        trimToBytes()
        val bytes = ByteArrayOutputStream(encodedSize())
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeBoolean(saturated)
            out.writeInt(rejections.size)
            rejections.values.forEach { out.writeUTF(it.contentHash); out.writeUTF(it.label); out.writeLong(it.rejectedAt) }
            out.writeInt(prototypes.size)
            prototypes.forEach { row ->
                out.writeUTF(row.label); out.writeUTF(row.category); out.writeUTF(row.modelFingerprint)
                out.writeUTF(row.contentHash); out.writeUTF(row.provenance); out.writeLong(row.createdAt); out.writeFloat(row.confidence)
                row.vector.forEach(out::writeFloat)
            }
        }
        writeCloudAtomic(file, bytes.toByteArray())
    }

    private fun load() {
        if (!file.exists()) return
        try {
            require(file.length() in 13..MAX_BYTES.toLong())
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == MAGIC)
                saturated = input.readBoolean()
                val rejected = input.readInt()
                require(rejected in 0..MAX_REJECTIONS)
                repeat(rejected) {
                    val hash = input.readUTF(); val label = input.readUTF(); val time = input.readLong()
                    require(validHash(hash) && label.length in 1..160)
                    rejections[key(hash, label)] = RejectionRecord(hash, label, time)
                }
                val count = input.readInt()
                require(count in 0..MAX_PROTOTYPES)
                repeat(count) {
                    val label = input.readUTF(); val category = input.readUTF(); val model = input.readUTF()
                    val hash = input.readUTF(); val provenance = input.readUTF(); val time = input.readLong(); val confidence = input.readFloat()
                    require(label.length in 1..160 && category.length <= 48 && model.length in 1..256 && validHash(hash) && provenance.length <= 512)
                    require(confidence.isFinite() && confidence in 0f..1f)
                    val vector = FloatArray(512) { input.readFloat() }
                    require(validVector(vector))
                    if (!isRejected(hash, label)) prototypes += PrototypeRecord(label, category, vector, model, hash, time, confidence, provenance)
                }
                require(input.read() == -1)
            }
            require(prototypes.groupingBy { it.label.lowercase(Locale.ROOT) }.eachCount().values.all { it <= MAX_PER_LABEL })
            prune()
        } catch (_: Exception) {
            prototypes.clear()
            saturated = true
            // A corrupt rejection record cannot safely be interpreted as an empty blacklist.
        }
    }

    private fun key(hash: String, label: String) = hash + ":" + label.trim().lowercase(Locale.ROOT)
    private fun validHash(hash: String) = hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }
    private fun validVector(vector: FloatArray): Boolean {
        if (vector.size != 512) return false
        var squared = 0.0
        for (value in vector) { if (!value.isFinite()) return false; squared += value.toDouble() * value }
        return abs(sqrt(squared) - 1.0) <= 0.01
    }
    private fun utfSize(value: String): Int = 2 + value.sumOf { when (it.code) { in 1..127 -> 1; in 0..2047 -> 2; else -> 3 } }

    companion object {
        const val MAX_PROTOTYPES = 2048
        const val MAX_PER_LABEL = 8
        const val EXPIRY_MILLIS = 30L * 24 * 3600 * 1000
        const val SIMILARITY_THRESHOLD = 0.88f
        private const val MAX_REJECTIONS = 4096
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAGIC = 0x4D505232
    }
}
