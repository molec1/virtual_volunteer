package com.virtualvolunteer.app.domain.matching

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity

/**
 * One protocol participant with zero or more stored face embeddings (multi-vector identity).
 */
data class ParticipantEmbeddingSet(
    val participant: RaceParticipantHashEntity,
    /** Non-empty comma-separated embedding strings (same format as Room). */
    val embeddingStrings: List<String>,
) {
    val hasEmbeddings: Boolean get() = embeddingStrings.isNotEmpty()
}
