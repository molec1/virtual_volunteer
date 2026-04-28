package com.virtualvolunteer.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.virtualvolunteer.app.data.files.FaceCropManifestDisk
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.face.FaceCropBounds
import com.virtualvolunteer.app.domain.face.FaceDebugOverlay
import com.virtualvolunteer.app.domain.face.FaceEmbedder
import com.virtualvolunteer.app.domain.face.FaceThumbnailSaver
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.OrientedPhotoBitmap
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.matching.formatFinishMatchDecisionLogLine
import com.virtualvolunteer.app.domain.participants.RaceParticipantPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

internal class FinishPhotoPipeline(
    private val appContext: Context,
    private val races: RaceRepository,
    private val pool: RaceParticipantPool,
    private val faces: MlKitFaceDetector,
    private val embedder: FaceEmbedder,
    private val matcher: FaceMatchEngine,
    private val decodeVisionBitmap: (File) -> Bitmap?,
    private val pipelineLog: (String) -> Unit,
) {
    companion object {
        private const val TAG = "RacePhotoProcessor"
    }

    suspend fun processFinishPhotoInternal(
        raceId: String,
        photoFile: File,
        finishTimeEpochMillis: Long,
    ): FinishProcessResult {
        RacePaths.ensureRaceLayout(appContext, raceId)
        pipelineLog("—— ingestFinishPhoto ——")
        pipelineLog("raceId=${raceId.take(8)}… sourceFile=${photoFile.name}")
        pipelineLog("finishTimeEpochMillis=$finishTimeEpochMillis (${Date(finishTimeEpochMillis)})")
        pipelineLog(OrientedPhotoBitmap.describeExifOrientation(photoFile))

        val bmp = decodeVisionBitmap(photoFile)
            ?: run {
                val msg = "DECODE_FAILED path=${photoFile.absolutePath}"
                pipelineLog(msg)
                return FinishProcessResult(
                    newRecordsInserted = 0,
                    logText = logNoBitmap(photoFile) + msg + "\n",
                    decodeSucceeded = false,
                    detectedFaceCount = 0,
                )
            }

        pipelineLog("visionBitmap=${bmp.width}x${bmp.height} (after EXIF upright correction)")

        val debugDir = RacePaths.debugDir(appContext, raceId)
        debugDir.mkdirs()
        val margin = FaceCropBounds.DEFAULT_MARGIN_PER_SIDE

        val sb = StringBuilder()
        try {
            val detected = faces.detectFaces(bmp)
            pipelineLog("detectedFaceCount=${detected.size}")
            Log.i(TAG, "finishPhoto file=${photoFile.name} detectedFaceCount=${detected.size}")
            sb.appendLine("detectedFaces=${detected.size}")
            sb.appendLine("finishTimeEpochMillis=$finishTimeEpochMillis")

            detected.forEachIndexed { i, f ->
                val line = "detectorFace[${i + 1}] boundingBox=${f.boundingBox}"
                pipelineLog(line)
                sb.appendLine(line)
            }

            if (detected.isNotEmpty()) {
                val overlayFile = File(
                    debugDir,
                    "finish_${photoFile.nameWithoutExtension}_${System.currentTimeMillis()}.jpg",
                )
                val overlayOk = FaceDebugOverlay.saveAnnotatedCopy(bmp, detected, margin, overlayFile)
                pipelineLog("debugOverlaySaved=$overlayOk file=${overlayFile.absolutePath}")
                sb.appendLine("debugOverlaySaved=$overlayOk path=${overlayFile.absolutePath}")
            }

            if (detected.isEmpty()) {
                pipelineLog("STOP: no faces (no match / no finish row)")
                return FinishProcessResult(
                    newRecordsInserted = 0,
                    logText = sb.toString(),
                    decodeSucceeded = true,
                    detectedFaceCount = 0,
                )
            }

            val facesDir = RacePaths.facesDir(appContext, raceId)
            facesDir.mkdirs()

            var newRows = 0
            detected.forEachIndexed { index, face ->
                val faceNum = index + 1
                var optionalFinishThumb: File? = null
                val raw = Rect(face.boundingBox)
                val rawFaceHeightPx = raw.height()
                val expanded = FaceCropBounds.expandFaceRect(raw, bmp.width, bmp.height, margin)
                val boxLine =
                    "face#$faceNum rawBoundingBox=$raw expandedBoundingBox=$expanded marginPerSide=$margin"
                pipelineLog(boxLine)
                sb.appendLine(boxLine)

                val crop = FaceCropBounds.cropBitmap(bmp, expanded)
                pipelineLog("face#$faceNum cropSucceeded=${crop != null}")
                if (crop == null) {
                    sb.appendLine("face#$faceNum crop_failed_skip")
                    return@forEachIndexed
                }

                val embedResult = runCatching {
                    withContext(Dispatchers.Default) { embedder.embed(crop) }
                }

                val vec = embedResult.getOrNull()
                if (vec == null) {
                    crop.recycle()
                    embedResult.exceptionOrNull()?.let { err ->
                        Log.e(TAG, "finishPhoto face#$faceNum embedding failed", err)
                        pipelineLog("face#$faceNum descriptorCreated=false err=${err.message}")
                    }
                    sb.appendLine("face#$faceNum embedding_failed=${embedResult.exceptionOrNull()?.message}")
                    return@forEachIndexed
                }

                pipelineLog("face#$faceNum descriptorCreated=true dim=${vec.size}")
                val observedStr = EmbeddingMath.formatCommaSeparated(vec)
                val blacklist = races.getEmbeddingMatchBlacklistSnapshot()

                val baseSets = pool.participantEmbeddingSets(raceId)
                val embeddingPool = baseSets.filter { it.hasEmbeddings }
                val availableThisPhoto = embeddingPool.toMutableList()

                pipelineLog(
                    "participantPoolSize=${baseSets.size} withValidEmbedding=${embeddingPool.size} " +
                        "availableThisPhoto=${availableThisPhoto.size} threshold=${matcher.threshold()}",
                )
                sb.appendLine(
                    "poolSize=${baseSets.size} embeddingPool=${embeddingPool.size} availableThisPhoto=${availableThisPhoto.size}",
                )

                val nearestAll = matcher.nearest(vec, observedStr, embeddingPool, blacklist)
                val nId = nearestAll?.participant?.id
                val nCos = nearestAll?.cosineSimilarity
                val thr = matcher.threshold()
                pipelineLog(
                    "face#$faceNum nearestParticipantId=$nId bestParticipantCos=$nCos threshold=$thr passesDistance=${
                        nCos != null && nCos >= thr
                    }",
                )
                sb.appendLine("face#$faceNum nearestId=$nId cosine=$nCos thr=$thr")

                val finishMatchOutcome =
                    matcher.matchFinishQualityAware(vec, observedStr, availableThisPhoto, blacklist)
                val matchInAvailable = finishMatchOutcome.matchedParticipant
                val finishDecisionLine =
                    finishMatchOutcome.formatFinishMatchDecisionLogLine(faceNum, rawFaceHeightPx)
                pipelineLog(finishDecisionLine)
                sb.appendLine(finishDecisionLine)
                Log.i(TAG, finishDecisionLine)

                val resolvedParticipant: RaceParticipantHashEntity = if (matchInAvailable != null) {
                    matchInAvailable
                } else {
                    pipelineLog(
                        "face#$faceNum no_finish_pool_match creating_participant_from_finish " +
                            "nearestCos=$nCos threshold=$thr matchDecision=${finishMatchOutcome.matchDecision.name}",
                    )
                    sb.appendLine(
                        "face#$faceNum new_participant_from_finish nearestCos=$nCos thr=$thr " +
                            "matchDecision=${finishMatchOutcome.matchDecision.name}",
                    )
                    val thumbFile = FaceThumbnailSaver.thumbnailFile(facesDir, photoFile, faceNum)
                    runCatching {
                        FaceThumbnailSaver.saveJpeg(crop, thumbFile)
                    }.exceptionOrNull()?.let { err ->
                        Log.e(TAG, "finish thumb save failed face#$faceNum", err)
                        pipelineLog("face#$faceNum thumbnailSaved=false err=${err.message}")
                    }
                    optionalFinishThumb = thumbFile.takeIf { it.exists() }
                    val globalId = races.resolveGlobalIdentity(vec)
                    val newId = races.insertParticipantHash(
                        RaceParticipantHashEntity(
                            raceId = raceId,
                            embedding = observedStr,
                            embeddingFailed = false,
                            sourcePhoto = photoFile.absolutePath,
                            faceThumbnailPath = if (thumbFile.exists()) thumbFile.absolutePath else null,
                            scannedPayload = null,
                            registryInfo = globalId.registryInfo,
                            identityRegistryId = globalId.registryId,
                            displayName = null,
                            createdAtEpochMillis = finishTimeEpochMillis,
                        ),
                        initialEmbeddingSource = EmbeddingSourceType.FINISH_AUTO,
                        primaryThumbnailPhotoPath = if (thumbFile.exists()) thumbFile.absolutePath else null,
                    )
                    races.listParticipantHashes(raceId).first { it.id == newId }
                }

                val resolvedSet = embeddingPool.find { it.participant.id == resolvedParticipant.id }
                    ?: baseSets.find { it.participant.id == resolvedParticipant.id }
                val cos = when {
                    matchInAvailable != null && resolvedSet != null && resolvedSet.hasEmbeddings ->
                        matcher.nearest(vec, observedStr, listOf(resolvedSet), blacklist)!!.cosineSimilarity
                    matchInAvailable == null ->
                        1f
                    else ->
                        EmbeddingMath.cosineSimilarity(vec, EmbeddingMath.parseCommaSeparated(resolvedParticipant.embedding))
                }

                val outcome = races.recordFinishDetectionForParticipant(
                    raceId = raceId,
                    participantId = resolvedParticipant.id,
                    detectedAtEpochMillis = finishTimeEpochMillis,
                    sourcePhotoPath = photoFile.absolutePath,
                    matchCosineSimilarity = cos,
                    sourceEmbedding = vec,
                )

                val appendedEmbedding = if (matchInAvailable != null) {
                    races.appendParticipantEmbeddingIfNew(
                        raceId = raceId,
                        participantId = resolvedParticipant.id,
                        embeddingCommaSeparated = observedStr,
                        sourceType = EmbeddingSourceType.FINISH_AUTO,
                        sourcePhotoPath = photoFile.absolutePath,
                        qualityScore = cos,
                        createdAtEpochMillis = finishTimeEpochMillis,
                    )
                } else {
                    false
                }
                newRows++
                FaceCropManifestDisk.upsertReplaceParticipantOnSource(
                    appContext,
                    raceId,
                    FaceCropManifestDisk.Entry(
                        sourcePhotoPath = photoFile.absolutePath,
                        visionWidth = bmp.width,
                        visionHeight = bmp.height,
                        left = expanded.left,
                        top = expanded.top,
                        right = expanded.right,
                        bottom = expanded.bottom,
                        participantHashId = resolvedParticipant.id,
                        cropFilePath = optionalFinishThumb?.absolutePath,
                    ),
                )

                pipelineLog(
                    "face#$faceNum candidateSource=${photoFile.absolutePath} nearestParticipantId=$nId bestScore=$nCos " +
                        "matchedParticipant=${resolvedParticipant.id} matchCos=$cos embeddingAppended=$appendedEmbedding " +
                        "finish_detection stored " +
                        "detectedAt=$finishTimeEpochMillis " +
                        "officialProtocol=${outcome.officialProtocolFinishMillis} " +
                        "protocolFinishUpdated=${outcome.protocolFinishTimeUpdated} " +
                        "ignoredLateSeries=${outcome.detectionIgnoredForProtocolSeries}",
                )
                sb.appendLine(
                    "face#$faceNum detection participant=${resolvedParticipant.id} cos=$cos " +
                        "official=${outcome.officialProtocolFinishMillis} " +
                        "protocolUpdated=${outcome.protocolFinishTimeUpdated} " +
                        "ignoredLate=${outcome.detectionIgnoredForProtocolSeries}",
                )
                Log.i(
                    TAG,
                    "finish_detection id=${resolvedParticipant.id} protocol=${outcome.officialProtocolFinishMillis} " +
                        "ignoredLate=${outcome.detectionIgnoredForProtocolSeries}",
                )

                availableThisPhoto.removeAll { it.participant.id == resolvedParticipant.id }

                crop.recycle()
            }

            pipelineLog("ingestFinishPhoto done newFinishRows=$newRows")
            return FinishProcessResult(
                newRecordsInserted = newRows,
                logText = sb.toString(),
                decodeSucceeded = true,
                detectedFaceCount = detected.size,
            )
        } finally {
            bmp.recycle()
        }
    }

    private fun logNoBitmap(file: File): String =
        "decode_failed path=${file.absolutePath}\n"
}
