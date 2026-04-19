package com.virtualvolunteer.app.domain.participants

import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.domain.matching.ParticipantEmbeddingSet

/**
 * Race-local participant pool backed by Room ([participant_embeddings] + legacy fallback).
 */
class RoomRaceParticipantPool(
    private val races: RaceRepository,
) : RaceParticipantPool {
    override suspend fun participantEmbeddingSets(raceId: String): List<ParticipantEmbeddingSet> =
        races.listParticipantEmbeddingSets(raceId)
}
