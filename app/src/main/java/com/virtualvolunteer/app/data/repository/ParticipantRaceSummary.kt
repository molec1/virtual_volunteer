package com.virtualvolunteer.app.data.repository

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity

/** One race appearance for a participant (linked by identity registry when applicable). */
data class ParticipantRaceSummary(
    /** [RaceParticipantHashEntity.id] for this specific race (for photos / protocol in that event). */
    val participantHashId: Long,
    val raceId: String,
    val raceCreatedAtMillis: Long,
    val raceStartedAtEpochMillis: Long?,
    val protocolFinishTimeEpochMillis: Long?,
    val finishRank: Int?,
    val raceThumbnailPhotoPath: String?,
)
