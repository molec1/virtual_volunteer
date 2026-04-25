package com.virtualvolunteer.app.data.repository

import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.RaceDao

/**
 * Lists races where a participant (or linked identity) appears, with finish rank when known.
 */
internal class ParticipantRaceHistoryReader(
    private val raceDao: RaceDao,
    private val participantHashDao: ParticipantHashDao,
) {

    suspend fun listRacesForParticipant(participantId: Long): List<ParticipantRaceSummary> {
        val seed = participantHashDao.getById(participantId) ?: return emptyList()
        val registryId = seed.identityRegistryId
        val hashRows = if (registryId != null) {
            participantHashDao.listHashesForIdentityRegistry(registryId)
        } else {
            listOf(seed)
        }
        return hashRows.mapNotNull { p ->
            val race = raceDao.getRace(p.raceId) ?: return@mapNotNull null
            val rank = finishRankForParticipantInRace(p.raceId, p.id)
            ParticipantRaceSummary(
                participantHashId = p.id,
                raceId = race.id,
                raceCreatedAtMillis = race.createdAtEpochMillis,
                raceStartedAtEpochMillis = race.startedAtEpochMillis,
                protocolFinishTimeEpochMillis = p.protocolFinishTimeEpochMillis,
                finishRank = rank,
                raceThumbnailPhotoPath = race.lastPhotoPath,
            )
        }.sortedByDescending { it.raceCreatedAtMillis }
    }

    private suspend fun finishRankForParticipantInRace(raceId: String, participantHashId: Long): Int? {
        val rows = RaceDashboardFinishRanks.assignFinishRanks(participantHashDao.getParticipantDashboardSnapshot(raceId))
        return rows.find { it.participantId == participantHashId }?.finishRank
    }
}
