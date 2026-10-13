package com.memeget.nativeapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

/** Length-prefixed fields prevent ambiguous boundaries in user-controlled OCR and labels. */
internal fun cloudEvidenceStamp(meme: Meme): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val length = ByteBuffer.allocate(Int.SIZE_BYTES)
    fun field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        length.clear()
        length.putInt(bytes.size)
        digest.update(length.array())
        digest.update(bytes)
    }
    field(meme.uri)
    field(meme.contentHash)
    field(meme.ocr)
    field(meme.caption)
    field(meme.transcript)
    val evidenceTags = meme.tags.filter {
        it.source != "cloud" && it.source != "cloud_hint" || it.confirmation == "confirmed"
    }
        .sortedWith(compareBy<Tag>({ it.label }, { it.category }, { it.source }, { it.provenance }))
    field(evidenceTags.size.toString())
    for (tag in evidenceTags) {
        field(tag.label)
        field(tag.category)
        field(tag.source)
        field(tag.score.toString())
        field(tag.scoreDomain)
        field(tag.confirmation)
        field(tag.provenance)
    }
    val rejected = meme.rejectedLabels.map { it.trim().lowercase(Locale.ROOT) }.distinct().sorted()
    field(rejected.size.toString())
    rejected.forEach(::field)
    return cloudDigestHex(digest.digest())
}

internal fun cloudVectorStamp(record: VectorRecord?): String {
    if (record == null) return ""
    val bytes = ByteBuffer.allocate(record.image.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    record.image.forEach(bytes::putFloat)
    return cloudDigestHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
}

private fun cloudDigestHex(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    val result = CharArray(bytes.size * 2)
    for (index in bytes.indices) {
        val value = bytes[index].toInt() and 255
        result[index * 2] = alphabet[value ushr 4]
        result[index * 2 + 1] = alphabet[value and 15]
    }
    return String(result)
}
