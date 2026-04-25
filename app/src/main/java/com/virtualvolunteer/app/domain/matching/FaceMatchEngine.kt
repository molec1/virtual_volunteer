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

    private fun bestCosineForParticipantSet(queries: List<FloatArray>, set: ParticipantEmbeddingSet): Float? {
        if (!set.hasEmbeddings) return null
        val storages = set.embeddingStrings.mapNotNull { str ->
            EmbeddingMath.parseCommaSeparated(str).takeIf { it.isNotEmpty() }
        }
        if (storages.isEmpty()) return null
        return EmbeddingMath.maxCosineSimilarityAcrossPairs(queries, storages)
    }

    fun nearest(observed: FloatArray, pool: List<ParticipantEmbeddingSet>): MatchScore? {
        if (pool.isEmpty()) return null
        val queries = listOf(observed)
        var bestParticipant: RaceParticipantHashEntity? = null
        var bestSimOverall = -1f
        for (set in pool) {
            val sim = bestCosineForParticipantSet(queries, set) ?: continue
            if (sim > bestSimOverall) {
                bestSimOverall = sim
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
        val queries = listOf(observed)
        val candidates = ArrayList<MatchCandidate>(pool.size)
        for (set in pool) {
            if (!set.hasEmbeddings) continue
            val sim = bestCosineForParticipantSet(queries, set) ?: -1f
            candidates.add(MatchCandidate(set, sim))
        }
        return candidates.filter { it.cosineSimilarity >= minCosineSimilarity }
            .sortedByDescending { it.cosineSimilarity }
            .take(n)
    }

    /**
     * All participants with embeddings, sorted best-first by cosine (no threshold).
     * For operator-driven re-assignment / lookup lists.
     */
    fun rankedByCosine(
        observed: FloatArray,
        pool: List<ParticipantEmbeddingSet>,
        topN: Int = 40,
    ): List<MatchCandidate> {
        val queries = listOf(observed)
        val candidates = ArrayList<MatchCandidate>(pool.size)
        for (set in pool) {
            val sim = bestCosineForParticipantSet(queries, set) ?: continue
            candidates.add(MatchCandidate(set, sim))
        }
        return candidates.sortedByDescending { it.cosineSimilarity }.take(topN)
    }

    /**
     * Like [rankedByCosine] but the query side may be **multiple** vectors; each pool participant’s
     * score is the maximum cosine over all query×stored pairs (same “best over embeddings” rule as
     * finish matching, applied symmetrically).
     */
    fun rankedByCosineFromQueries(
        observeds: List<FloatArray>,
        pool: List<ParticipantEmbeddingSet>,
        topN: Int = 40,
    ): List<MatchCandidate> {
        val validObs = observeds.filter { it.isNotEmpty() }
        if (validObs.isEmpty()) return emptyList()
        val candidates = ArrayList<MatchCandidate>(pool.size)
        for (set in pool) {
            val sim = bestCosineForParticipantSet(validObs, set) ?: continue
            candidates.add(MatchCandidate(set, sim))
        }
        return candidates.sortedByDescending { it.cosineSimilarity }.take(topN)
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
