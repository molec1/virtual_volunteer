package com.virtualvolunteer.app.domain.participants

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.data.repository.RaceRepository

/**
 * Race-local participant hash pool backed by Room.
 */
class RoomRaceParticipantPool(
    private val races: RaceRepository,
) : RaceParticipantPool {
    override suspend fun participantHashes(raceId: String): List<RaceParticipantHashEntity> =
        races.listParticipantHashes(raceId)
}
