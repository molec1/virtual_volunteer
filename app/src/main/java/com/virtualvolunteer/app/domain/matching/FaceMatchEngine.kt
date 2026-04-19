package com.virtualvolunteer.app.domain.matching

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.domain.face.EmbeddingMath

/**
 * Tunable nearest-neighbour match using cosine similarity on L2-normalized embeddings.
 */
class FaceMatchEngine(
    private val minCosineSimilarity: Float = DEFAULT_MIN_COSINE,
) {

    fun nearest(observed: FloatArray, pool: List<RaceParticipantHashEntity>): MatchScore? {
        if (pool.isEmpty()) return null
        var bestRow: RaceParticipantHashEntity? = null
        var bestSim = -1f
        for (row in pool) {
            if (row.embeddingFailed) continue
            val stored = EmbeddingMath.parseCommaSeparated(row.embedding)
            if (stored.isEmpty() || stored.size != observed.size) continue
            val sim = EmbeddingMath.cosineSimilarity(observed, stored)
            if (sim > bestSim) {
                bestSim = sim
                bestRow = row
            }
        }
        return bestRow?.let { MatchScore(it, bestSim) }
    }

    fun match(observed: FloatArray, pool: List<RaceParticipantHashEntity>): RaceParticipantHashEntity? {
        val n = nearest(observed, pool) ?: return null
        return if (n.cosineSimilarity >= minCosineSimilarity) n.participant else null
    }

    /** Same gate as [match] for debug UI. */
    fun threshold(): Float = minCosineSimilarity

    companion object {
        /** Conservative default; tune per device/lighting for field tests. */
        const val DEFAULT_MIN_COSINE: Float = 0.65f
    }
}
