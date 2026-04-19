package com.virtualvolunteer.app.data.local

/**
 * Room projection for [ParticipantHashDao] dashboard queries (before UI-only fields are applied).
 */
data class ParticipantDashboardDbRow(
    val participantId: Long,
    val raceId: String,
    val embedding: String,
    val embeddingFailed: Boolean,
    val sourcePhoto: String,
    val faceThumbnailPath: String?,
    val scannedPayload: String?,
    val registryInfo: String?,
    val raceStartedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val finishTimeEpochMillis: Long?,
    val displayName: String?,
)
