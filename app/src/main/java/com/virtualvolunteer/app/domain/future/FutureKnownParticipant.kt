package com.virtualvolunteer.app.domain.future

/**
 * Future-facing shape for a device-wide participant registry (not persisted in MVP Room schema).
 *
 * Later versions can introduce a dedicated Room entity and DAO separate from per-race tables.
 */
data class FutureKnownParticipant(
    val id: Long,
    val name: String?,
    val qrCode: String?,
    val barcode: String?,
    val faceHash: String?,
    val notes: String?,
)
