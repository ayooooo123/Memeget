package com.memeget.nativeapp.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

internal object FloatBlob {

    fun toBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.float
        }
        return floats
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            s += a[i] * b[i]
        }
        return s
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 0f || normB <= 0f) return 0f
        return dot / (sqrt(normA) * sqrt(normB))
    }

    fun normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        if (norm <= 0f) return vec.copyOf()
        val inv = 1.0f / sqrt(norm)
        val out = FloatArray(vec.size)
        for (i in vec.indices) out[i] = vec[i] * inv
        return out
    }

    fun maxCosTo(a: FloatArray, vs: List<FloatArray>): Float {
        var best = -1f
        for (v in vs) {
            val s = dot(a, v)
            if (s > best) best = s
        }
        return best
    }

    fun fingerprint(vec: FloatArray): String =
        MessageDigest.getInstance("SHA-256").digest(toBytes(vec)).joinToString("") { "%02x".format(it) }
}
