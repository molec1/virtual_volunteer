package com.virtualvolunteer.app.domain.face

import kotlin.math.sqrt

/**
 * Duplicated from the app module for JVM-only regression (keep in sync with app
 * `EmbeddingMath`).
 */
object EmbeddingMath {

    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum).takeIf { it > 1e-12f } ?: return v.copyOf()
        return FloatArray(v.size) { i -> v[i] / n }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        val na = l2Normalize(a)
        val nb = l2Normalize(b)
        var dot = 0f
        for (i in na.indices) dot += na[i] * nb[i]
        return dot.coerceIn(-1f, 1f)
    }

    fun maxCosineSimilarityAcrossPairs(queries: List<FloatArray>, storages: List<FloatArray>): Float? {
        var best = -1f
        var anyComparable = false
        for (q in queries) {
            if (q.isEmpty()) continue
            for (s in storages) {
                if (s.isEmpty() || s.size != q.size) continue
                anyComparable = true
                val c = cosineSimilarity(q, s)
                if (c > best) best = c
            }
        }
        return if (anyComparable) best else null
    }

    fun parseCommaSeparated(s: String): FloatArray {
        if (s.isBlank()) return floatArrayOf()
        return s.split(',').map { it.trim().toFloat() }.toFloatArray()
    }

    fun formatCommaSeparated(v: FloatArray): String =
        v.joinToString(",") { it.toString() }
}
