package com.virtualvolunteer.app.data.local

/**
 * Join projection for participant list UI (race-local pool + optional finish record).
 */
data class ParticipantDashboardRow(
    val participantId: Long,
    val raceId: String,
    val embedding: String,
    val embeddingFailed: Boolean,
    val sourcePhoto: String,
    val faceThumbnailPath: String?,
    val scannedPayload: String?,
    val registryInfo: String?,
    /** Race gun time for moving-time calculation (millis). */
    val raceStartedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val finishTimeEpochMillis: Long?,
)
