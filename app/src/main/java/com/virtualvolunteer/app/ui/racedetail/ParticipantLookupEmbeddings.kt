package com.virtualvolunteer.app.ui.racedetail

import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import java.io.File

internal suspend fun probeEmbeddingsForParticipant(
    repo: RaceRepository,
    raceId: String,
    participantId: Long,
): List<FloatArray> {
    val sets = repo.listParticipantEmbeddingSets(raceId)
    val set = sets.find { it.participant.id == participantId } ?: return emptyList()
    val fromTable = set.embeddingStrings.mapNotNull { str ->
        EmbeddingMath.parseCommaSeparated(str).takeIf { it.isNotEmpty() }
    }
    if (fromTable.isNotEmpty()) return fromTable
    val row = repo.getParticipantHashById(participantId) ?: return emptyList()
    if (!row.embeddingFailed && row.embedding.isNotBlank()) {
        val legacy = EmbeddingMath.parseCommaSeparated(row.embedding)
        if (legacy.isNotEmpty()) return listOf(legacy)
    }
    return emptyList()
}

internal sealed class LookupPhotoEmbeddingResult {
    data class Ok(val embedding: FloatArray) : LookupPhotoEmbeddingResult()
    data object DecodeFailed : LookupPhotoEmbeddingResult()
    data object NoFaces : LookupPhotoEmbeddingResult()
    data object EmbedFailed : LookupPhotoEmbeddingResult()
}

/**
 * Decode + first-face crop + embed from a temp file (caller owns temp lifecycle).
 * Must run from a coroutine ([MlKitFaceDetector.detectFaces] is suspend).
 */
internal suspend fun extractLookupEmbeddingFromTemp(
    photoProcessor: RacePhotoProcessor,
    tmp: File,
): LookupPhotoEmbeddingResult {
    val bmp = photoProcessor.loadVisionBitmap(tmp) ?: return LookupPhotoEmbeddingResult.DecodeFailed
    try {
        val detected = photoProcessor.faces.detectFaces(bmp)
        if (detected.isEmpty()) return LookupPhotoEmbeddingResult.NoFaces
        val face = detected.first()
        val crop = photoProcessor.cropFace(bmp, face) ?: return LookupPhotoEmbeddingResult.EmbedFailed
        val vec = runCatching { photoProcessor.embedder.embed(crop) }.getOrNull()
        crop.recycle()
        return if (vec != null) {
            LookupPhotoEmbeddingResult.Ok(vec)
        } else {
            LookupPhotoEmbeddingResult.EmbedFailed
        }
    } finally {
        bmp.recycle()
    }
}
