package com.virtualvolunteer.app.data.xml

import com.virtualvolunteer.app.data.model.RaceStatus

/**
 * Minimal race state persisted in race.xml (mirrored with Room for MVP).
 */
data class RaceXmlSnapshot(
    val id: String,
    val createdAtEpochMillis: Long,
    val startedAtEpochMillis: Long?,
    val finishedAtEpochMillis: Long?,
    val status: RaceStatus,
)
