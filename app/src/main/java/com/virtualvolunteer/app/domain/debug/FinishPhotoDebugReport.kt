package com.virtualvolunteer.app.domain.debug

/**
 * Single-image finish analysis for test / debug UI (does not mutate the database by itself).
 */
data class FinishPhotoDebugReport(
    val photoPath: String,
    val detectedFaceCount: Int,
    val faces: List<FinishFaceDebugRow>,
)

data class FinishFaceDebugRow(
    val faceIndex: Int,
    /** Short preview of comma-separated embedding prefix. */
    val embeddingPreview: String,
    val nearestParticipantId: Long?,
    val nearestParticipantEmbeddingPreview: String?,
    /** Best cosine similarity against full start pool (-1..1). */
    val cosineSimilarity: Float?,
    val cosineThreshold: Float,
    val passesThreshold: Boolean,
    val participantAlreadyFinished: Boolean,
    val wouldRecordAsNewFinish: Boolean,
)
