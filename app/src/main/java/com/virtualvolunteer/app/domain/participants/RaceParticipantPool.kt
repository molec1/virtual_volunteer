package com.virtualvolunteer.app.domain.participants

import com.virtualvolunteer.app.domain.matching.ParticipantEmbeddingSet

/**
 * Abstraction over where participant face descriptors come from (multi-embedding per participant).
 */
fun interface RaceParticipantPool {
    suspend fun participantEmbeddingSets(raceId: String): List<ParticipantEmbeddingSet>
}
