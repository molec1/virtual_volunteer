package com.virtualvolunteer.app.domain.matching

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.domain.face.EmbeddingMath

/**
 * Nearest-neighbour match using cosine similarity on L2-normalized embeddings.
 * Each participant may have **multiple** stored vectors; the score for a participant is the **best**
 * cosine among its vectors.
 */
class FaceMatchEngine(
    private val minCosineSimilarity: Float = FinishFaceMatchPolicy.AUTO_MATCH_THRESHOLD,
) {

    private fun bestCosineForParticipantSet(
        queries: List<FloatArray>,
        queryEmbeddingString: String?,
        set: ParticipantEmbeddingSet,
        blacklist: EmbeddingMatchBlacklistSnapshot?,
    ): Float? {
        if (!set.hasEmbeddings) return null
        val storages = set.embeddingStrings.mapNotNull { str ->
            val v = EmbeddingMath.parseCommaSeparated(str).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            str to v
        }
        if (storages.isEmpty()) return null

        var best = -1f
        var anyComparable = false
        for (q in queries) {
            if (q.isEmpty()) continue
            for ((sStr, sVec) in storages) {
                if (sVec.isEmpty() || sVec.size != q.size) continue
                if (blacklist != null && queryEmbeddingString != null) {
                    if (blacklist.isBlockedByEmbeddingStrings(queryEmbeddingString, sStr)) continue
                }
                anyComparable = true
                val c = EmbeddingMath.cosineSimilarity(q, sVec)
                if (c > best) best = c
            }
        }
        return if (anyComparable) best else null
    }

    fun nearest(
        observed: FloatArray,
        observedEmbeddingString: String? = null,
        pool: List<ParticipantEmbeddingSet>,
        blacklist: EmbeddingMatchBlacklistSnapshot? = null,
    ): MatchScore? {
        if (pool.isEmpty()) return null
        val queries = listOf(observed)
        var bestParticipant: RaceParticipantHashEntity? = null
        var bestSimOverall = -1f
        for (set in pool) {
            val sim = bestCosineForParticipantSet(queries, observedEmbeddingString, set, blacklist) ?: continue
            if (sim > bestSimOverall) {
                bestSimOverall = sim
                bestParticipant = set.participant
            }
        }
        return bestParticipant?.let { MatchScore(it, bestSimOverall) }
    }

    fun match(
        observed: FloatArray,
        observedEmbeddingString: String? = null,
        pool: List<ParticipantEmbeddingSet>,
        blacklist: EmbeddingMatchBlacklistSnapshot? = null,
    ): RaceParticipantHashEntity? {
        val n = nearest(observed, observedEmbeddingString, pool, blacklist) ?: return null
        return if (n.cosineSimilarity >= minCosineSimilarity) n.participant else null
    }

    /**
     * Finish-line matching: [FinishFaceMatchPolicy] ([FinishFaceMatchPolicy.AUTO_MATCH_THRESHOLD] only).
     */
    fun matchFinishQualityAware(
        observed: FloatArray,
        observedEmbeddingString: String? = null,
        pool: List<ParticipantEmbeddingSet>,
        blacklist: EmbeddingMatchBlacklistSnapshot? = null,
    ): FinishFaceMatchOutcome {
        val ranked = rankedByCosine(observed, observedEmbeddingString, pool, topN = 2, blacklist = blacklist)
        return FinishFaceMatchPolicy.evaluate(pool.size, ranked)
    }

    /** Find top N matches, sorted best-first by [MatchCandidate.cosineSimilarity]. */
    fun nearestN(
        observed: FloatArray,
        observedEmbeddingString: String? = null,
        pool: List<ParticipantEmbeddingSet>,
        n: Int,
        blacklist: EmbeddingMatchBlacklistSnapshot? = null,
    ): List<MatchCandidate> {
        val queries = listOf(observed)
        val candidates = ArrayList<MatchCandidate>(pool.size)
        for (set in pool) {
            if (!set.hasEmbeddings) continue
            val sim = bestCosineForParticipantSet(queries, observedEmbeddingString, set, blacklist) ?: -1f
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
        observedEmbeddingString: String? = null,
        pool: List<ParticipantEmbeddingSet>,
        topN: Int = 40,
        blacklist: EmbeddingMatchBlacklistSnapshot? = null,
    ): List<MatchCandidate> {
        val queries = listOf(observed)
        val candidates = ArrayList<MatchCandidate>(pool.size)
        for (set in pool) {
            val sim = bestCosineForParticipantSet(queries, observedEmbeddingString, set, blacklist) ?: continue
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
        observedEmbeddingStrings: List<String>? = null,
        pool: List<ParticipantEmbeddingSet>,
        topN: Int = 40,
        blacklist: EmbeddingMatchBlacklistSnapshot? = null,
    ): List<MatchCandidate> {
        val validObs = observeds.filter { it.isNotEmpty() }
        if (validObs.isEmpty()) return emptyList()
        val candidates = ArrayList<MatchCandidate>(pool.size)
        val queryString = observedEmbeddingStrings?.firstOrNull()
        for (set in pool) {
            val sim = bestCosineForParticipantSet(validObs, queryString, set, blacklist) ?: continue
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
        /** Same floor as [FinishFaceMatchPolicy.AUTO_MATCH_THRESHOLD] (start scans / disk reattach). */
        const val DEFAULT_MIN_COSINE: Float = FinishFaceMatchPolicy.AUTO_MATCH_THRESHOLD
    }
}
