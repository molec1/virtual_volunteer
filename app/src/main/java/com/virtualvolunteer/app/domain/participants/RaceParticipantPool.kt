package com.virtualvolunteer.app.domain.participants

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity

/**
 * Abstraction over where participant face descriptors come from.
 * MVP uses race-local hashes only; later this can delegate to a global participant repository.
 */
fun interface RaceParticipantPool {
    suspend fun participantHashes(raceId: String): List<RaceParticipantHashEntity>
}
