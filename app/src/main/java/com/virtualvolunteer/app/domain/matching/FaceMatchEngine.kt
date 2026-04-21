package com.virtualvolunteer.app.domain.matching

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.domain.face.EmbeddingMath

/**
 * Nearest-neighbour match using cosine similarity on L2-normalized embeddings.
 * Each participant may have **multiple** stored vectors; the score for a participant is the **best**
 * cosine among its vectors.
 */
class FaceMatchEngine(
    private val minCosineSimilarity: Float = DEFAULT_MIN_COSINE,
) {

    fun nearest(observed: FloatArray, pool: List<ParticipantEmbeddingSet>): MatchScore? {
        if (pool.isEmpty()) return null
        var bestParticipant: RaceParticipantHashEntity? = null
        var bestSimOverall = -1f
        for (set in pool) {
            if (!set.hasEmbeddings) continue
            var bestForParticipant = -1f
            for (embStr in set.embeddingStrings) {
                val stored = EmbeddingMath.parseCommaSeparated(embStr)
                if (stored.isEmpty() || stored.size != observed.size) continue
                val sim = EmbeddingMath.cosineSimilarity(observed, stored)
                if (sim > bestForParticipant) bestForParticipant = sim
            }
            if (bestForParticipant > bestSimOverall) {
                bestSimOverall = bestForParticipant
                bestParticipant = set.participant
            }
        }
        return bestParticipant?.let { MatchScore(it, bestSimOverall) }
    }

    fun match(observed: FloatArray, pool: List<ParticipantEmbeddingSet>): RaceParticipantHashEntity? {
        val n = nearest(observed, pool) ?: return null
        return if (n.cosineSimilarity >= minCosineSimilarity) n.participant else null
    }

    /** Find top N matches, sorted best-first by [MatchCandidate.cosineSimilarity]. */
    fun nearestN(
        observed: FloatArray,
        pool: List<ParticipantEmbeddingSet>,
        n: Int,
    ): List<MatchCandidate> {
        val candidates = ArrayList<MatchCandidate>(pool.size)
        for (set in pool) {
            if (!set.hasEmbeddings) continue
            var bestForParticipant = -1f
            for (embStr in set.embeddingStrings) {
                val stored = EmbeddingMath.parseCommaSeparated(embStr)
                if (stored.isEmpty() || stored.size != observed.size) continue
                val sim = EmbeddingMath.cosineSimilarity(observed, stored)
                if (sim > bestForParticipant) bestForParticipant = sim
            }
            candidates.add(MatchCandidate(set, bestForParticipant))
        }
        return candidates.filter { it.cosineSimilarity >= minCosineSimilarity }
            .sortedByDescending { it.cosineSimilarity }
            .take(n)
    }

    data class MatchCandidate(
        val candidate: ParticipantEmbeddingSet,
        val cosineSimilarity: Float,
    )

    /** Same gate as [match] for debug UI. */
    fun threshold(): Float = minCosineSimilarity

    companion object {
        /** Conservative default; tune per device/lighting for field tests. */
        const val DEFAULT_MIN_COSINE: Float = 0.65f
    }
}
