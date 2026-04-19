package com.virtualvolunteer.app.domain.face

import kotlin.math.sqrt

/**
 * Vector math for L2-normalized face embeddings and cosine similarity.
 */
object EmbeddingMath {

    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum).takeIf { it > 1e-12f } ?: return v.copyOf()
        return FloatArray(v.size) { i -> v[i] / n }
    }

    /** Cosine similarity in [-1, 1] for arbitrary non-zero vectors (uses L2-normalized copies). */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        val na = l2Normalize(a)
        val nb = l2Normalize(b)
        var dot = 0f
        for (i in na.indices) dot += na[i] * nb[i]
        return dot.coerceIn(-1f, 1f)
    }

    fun parseCommaSeparated(s: String): FloatArray {
        if (s.isBlank()) return floatArrayOf()
        return s.split(',').map { it.trim().toFloat() }.toFloatArray()
    }

    /** Compact storage for Room (comma-separated floats). */
    fun formatCommaSeparated(v: FloatArray): String =
        v.joinToString(",") { it.toString() }
}
