package com.virtualvolunteer.app.domain.matching

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity

/**
 * Nearest-neighbour score for embedding comparison (cosine similarity, higher is better).
 */
data class MatchScore(
    val participant: RaceParticipantHashEntity,
    val cosineSimilarity: Float,
)
