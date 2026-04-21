package com.virtualvolunteer.app.data.local

/**
 * Participant list row for the dashboard adapter (ranking + display fields).
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
    val primaryThumbnailPhotoPath: String?,
    /** Race gun time for moving-time calculation (millis). */
    val raceStartedAtEpochMillis: Long?,
    val finishTimeEpochMillis: Long?,
    val displayName: String?,
    /** 1 = earliest finish in this race; null if no finish yet. */
    val finishRank: Int?,
)
