package com.virtualvolunteer.app.domain

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.domain.debug.FinishFaceDebugRow
import com.virtualvolunteer.app.domain.debug.FinishPhotoDebugReport
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.face.FaceCropBounds
import com.virtualvolunteer.app.domain.face.FaceEmbedder
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.participants.RaceParticipantPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class FinishPhotoDebugAnalyzer(
    private val races: RaceRepository,
    private val pool: RaceParticipantPool,
    private val faces: MlKitFaceDetector,
    private val embedder: FaceEmbedder,
    private val matcher: FaceMatchEngine,
    private val decodeVisionBitmap: (File) -> Bitmap?,
) {
    companion object {
        private const val TAG = "RacePhotoProcessor"
    }

    suspend fun analyze(
        raceId: String,
        photoFile: File,
    ): Result<FinishPhotoDebugReport> = runCatching {
        val bmp = decodeVisionBitmap(photoFile)
            ?: return@runCatching FinishPhotoDebugReport(
                photoPath = photoFile.absolutePath,
                detectedFaceCount = 0,
                faces = emptyList(),
            )
        try {
            val detected = faces.detectFaces(bmp)
            Log.i(TAG, "analyzeFinishDebug file=${photoFile.name} detectedFaceCount=${detected.size}")
            val baseSets = pool.participantEmbeddingSets(raceId).filter { it.hasEmbeddings }
            val threshold = matcher.threshold()

            val rows = ArrayList<FinishFaceDebugRow>(detected.size)
            val margin = FaceCropBounds.DEFAULT_MARGIN_PER_SIDE
            var availableThisPhoto = baseSets.toMutableList()
            detected.forEachIndexed { index, face ->
                val raw = Rect(face.boundingBox)
                val expanded = FaceCropBounds.expandFaceRect(raw, bmp.width, bmp.height, margin)
                val crop = FaceCropBounds.cropBitmap(bmp, expanded) ?: run {
                    Log.w(TAG, "analyzeFinishDebug face#${index + 1} crop_failed")
                    rows.add(
                        FinishFaceDebugRow(
                            faceIndex = index + 1,
                            embeddingPreview = "",
                            nearestParticipantId = null,
                            nearestParticipantEmbeddingPreview = null,
                            cosineSimilarity = null,
                            cosineThreshold = threshold,
                            passesThreshold = false,
                            participantAlreadyFinished = false,
                            wouldRecordAsNewFinish = false,
                        ),
                    )
                    return@forEachIndexed
                }
                val vec = runCatching {
                    withContext(Dispatchers.Default) { embedder.embed(crop) }
                }.getOrElse { err ->
                    Log.e(TAG, "analyzeFinishDebug face#${index + 1} embedding_failed", err)
                    crop.recycle()
                    rows.add(
                        FinishFaceDebugRow(
                            faceIndex = index + 1,
                            embeddingPreview = "",
                            nearestParticipantId = null,
                            nearestParticipantEmbeddingPreview = null,
                            cosineSimilarity = null,
                            cosineThreshold = threshold,
                            passesThreshold = false,
                            participantAlreadyFinished = false,
                            wouldRecordAsNewFinish = false,
                        ),
                    )
                    return@forEachIndexed
                }
                crop.recycle()
                val preview = previewEmbedding(vec)
                val picked = matcher.match(vec, availableThisPhoto)
                val nearestDiag = matcher.nearest(vec, baseSets)
                val pickedSet = picked?.let { p -> baseSets.find { it.participant.id == p.id } }
                val nearestId = picked?.id ?: nearestDiag?.participant?.id
                val sim = when {
                    picked != null && pickedSet != null ->
                        matcher.nearest(vec, listOf(pickedSet))!!.cosineSimilarity
                    nearestDiag != null -> nearestDiag.cosineSimilarity
                    else -> null
                }
                val passes = picked != null
                val matchRow = picked?.let { races.getParticipantHashById(it.id) }
                val hasOfficialFinish = matchRow?.protocolFinishTimeEpochMillis != null
                val wouldRecord = picked != null
                if (picked != null) {
                    availableThisPhoto.removeAll { it.participant.id == picked.id }
                }
                rows.add(
                    FinishFaceDebugRow(
                        faceIndex = index + 1,
                        embeddingPreview = preview,
                        nearestParticipantId = nearestId,
                        nearestParticipantEmbeddingPreview = pickedSet?.embeddingStrings?.firstOrNull()?.let { e ->
                            if (e.length > 64) e.take(64) + "…" else e
                        } ?: nearestDiag?.participant?.embedding?.let { e ->
                            if (e.length > 64) e.take(64) + "…" else e
                        },
                        cosineSimilarity = sim,
                        cosineThreshold = threshold,
                        passesThreshold = passes,
                        participantAlreadyFinished = hasOfficialFinish,
                        wouldRecordAsNewFinish = wouldRecord,
                    ),
                )
            }

            FinishPhotoDebugReport(
                photoPath = photoFile.absolutePath,
                detectedFaceCount = detected.size,
                faces = rows,
            )
        } finally {
            bmp.recycle()
        }
    }

    private fun previewEmbedding(vec: FloatArray): String {
        val s = EmbeddingMath.formatCommaSeparated(vec)
        return if (s.length > 96) s.take(96) + "…" else s
    }
}
