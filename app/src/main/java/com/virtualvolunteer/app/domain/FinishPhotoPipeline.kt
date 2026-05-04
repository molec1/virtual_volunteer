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
        private const val SERIES_PHOTO_WINDOW_MS = 1_000L
        private const val SERIES_MIN_COSINE = 0.4f
        private const val SERIES_MAX_CENTER_DELTA = 0.12f
        private const val SERIES_MAX_SIZE_RATIO = 2.25f
        private val FINISH_FILENAME_MILLIS = Regex("""^finish_(\d{10,})""")
    }

    private val recentFinishPhotosByRace = mutableMapOf<String, RecentFinishPhoto>()

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
        val seriesCaptureTimeEpochMillis = seriesCaptureTimeEpochMillis(photoFile, finishTimeEpochMillis)

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

            if (detected.isEmpty()) {
                pipelineLog("STOP: no faces (no match / no finish row)")
                rememberRecentFinishPhoto(
                    raceId = raceId,
                    photoFile = photoFile,
                    captureTimeEpochMillis = seriesCaptureTimeEpochMillis,
                    faces = emptyList(),
                )
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
            val currentSeriesFaces = mutableListOf<RecentFinishFace>()
            val participantIdsUsedThisPhoto = mutableSetOf<Long>()
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
                val thr = matcher.threshold()
                val seriesMatch = findRecentSeriesMatch(
                    raceId = raceId,
                    currentPhoto = photoFile,
                    currentCaptureTimeEpochMillis = seriesCaptureTimeEpochMillis,
                    currentFaceBox = expanded,
                    currentVisionWidth = bmp.width,
                    currentVisionHeight = bmp.height,
                    currentEmbedding = vec,
                    participantIdsUsedThisPhoto = participantIdsUsedThisPhoto,
                )
                val seriesParticipant = seriesMatch?.face?.participant
                if (seriesMatch != null) {
                    val line =
                        "face#$faceNum recent_series_match participantId=${seriesParticipant?.id} " +
                            "cos=${seriesMatch.cosineSimilarity} deltaMs=${seriesMatch.deltaMs} " +
                            "skipFullEmbeddingPool=true"
                    pipelineLog(line)
                    sb.appendLine(line)
                }

                var nearestParticipantId: Long? = null
                var nearestParticipantCosine: Float? = null
                var standardMatchInAvailable: RaceParticipantHashEntity? = null
                var standardMatchCosine: Float? = null
                var matchDecisionName = "SERIES_MATCH"

                if (seriesParticipant == null) {
                    val blacklist = races.getEmbeddingMatchBlacklistSnapshot()
                    val baseSets = pool.participantEmbeddingSets(raceId)
                    val embeddingPool = baseSets.filter { it.hasEmbeddings }
                    val availableThisPhoto = embeddingPool
                        .filterNot { it.participant.id in participantIdsUsedThisPhoto }
                        .toMutableList()

                    pipelineLog(
                        "participantPoolSize=${baseSets.size} withValidEmbedding=${embeddingPool.size} " +
                            "availableThisPhoto=${availableThisPhoto.size} threshold=$thr",
                    )
                    sb.appendLine(
                        "poolSize=${baseSets.size} embeddingPool=${embeddingPool.size} " +
                            "availableThisPhoto=${availableThisPhoto.size}",
                    )

                    val nearestAll = matcher.nearest(vec, observedStr, embeddingPool, blacklist)
                    nearestParticipantId = nearestAll?.participant?.id
                    nearestParticipantCosine = nearestAll?.cosineSimilarity
                    pipelineLog(
                        "face#$faceNum nearestParticipantId=$nearestParticipantId " +
                            "bestParticipantCos=$nearestParticipantCosine threshold=$thr passesDistance=${
                                nearestParticipantCosine != null && nearestParticipantCosine >= thr
                            }",
                    )
                    sb.appendLine("face#$faceNum nearestId=$nearestParticipantId cosine=$nearestParticipantCosine thr=$thr")

                    val finishMatchOutcome =
                        matcher.matchFinishQualityAware(vec, observedStr, availableThisPhoto, blacklist)
                    standardMatchInAvailable = finishMatchOutcome.matchedParticipant
                    matchDecisionName = finishMatchOutcome.matchDecision.name
                    val finishDecisionLine =
                        finishMatchOutcome.formatFinishMatchDecisionLogLine(faceNum, rawFaceHeightPx)
                    pipelineLog(finishDecisionLine)
                    sb.appendLine(finishDecisionLine)
                    Log.i(TAG, finishDecisionLine)

                    val resolvedSet = standardMatchInAvailable?.let { matched ->
                        embeddingPool.find { it.participant.id == matched.id }
                            ?: baseSets.find { it.participant.id == matched.id }
                    }
                    if (resolvedSet != null && resolvedSet.hasEmbeddings) {
                        standardMatchCosine =
                            matcher.nearest(vec, observedStr, listOf(resolvedSet), blacklist)!!.cosineSimilarity
                    }
                }

                val resolvedParticipant: RaceParticipantHashEntity = if (standardMatchInAvailable != null) {
                    standardMatchInAvailable
                } else if (seriesParticipant != null) {
                    seriesParticipant
                } else {
                    pipelineLog(
                        "face#$faceNum no_finish_pool_match creating_participant_from_finish " +
                            "nearestCos=$nearestParticipantCosine threshold=$thr matchDecision=$matchDecisionName",
                    )
                    sb.appendLine(
                        "face#$faceNum new_participant_from_finish nearestCos=$nearestParticipantCosine thr=$thr " +
                            "matchDecision=$matchDecisionName",
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

                val cos = when {
                    standardMatchCosine != null ->
                        standardMatchCosine
                    seriesParticipant != null ->
                        seriesMatch!!.cosineSimilarity
                    standardMatchInAvailable == null ->
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

                val matchedExistingParticipant = standardMatchInAvailable != null || seriesParticipant != null
                val appendedEmbedding = if (matchedExistingParticipant) {
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
                currentSeriesFaces += RecentFinishFace(
                    participant = resolvedParticipant,
                    embedding = vec,
                    box = Rect(expanded),
                    visionWidth = bmp.width,
                    visionHeight = bmp.height,
                )
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
                    "face#$faceNum candidateSource=${photoFile.absolutePath} " +
                        "nearestParticipantId=$nearestParticipantId bestScore=$nearestParticipantCosine " +
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

                participantIdsUsedThisPhoto += resolvedParticipant.id

                crop.recycle()
            }

            rememberRecentFinishPhoto(
                raceId = raceId,
                photoFile = photoFile,
                captureTimeEpochMillis = seriesCaptureTimeEpochMillis,
                faces = currentSeriesFaces,
            )
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

    private fun seriesCaptureTimeEpochMillis(photoFile: File, fallbackEpochMillis: Long): Long =
        FINISH_FILENAME_MILLIS.find(photoFile.name)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: fallbackEpochMillis

    private fun findRecentSeriesMatch(
        raceId: String,
        currentPhoto: File,
        currentCaptureTimeEpochMillis: Long,
        currentFaceBox: Rect,
        currentVisionWidth: Int,
        currentVisionHeight: Int,
        currentEmbedding: FloatArray,
        participantIdsUsedThisPhoto: Set<Long>,
    ): RecentSeriesMatch? {
        val previous = recentFinishPhotosByRace[raceId] ?: return null
        if (previous.sourcePhotoPath == currentPhoto.absolutePath) return null
        val deltaMs = currentCaptureTimeEpochMillis - previous.captureTimeEpochMillis
        if (deltaMs < 0L || deltaMs >= SERIES_PHOTO_WINDOW_MS) return null

        var best: RecentSeriesMatch? = null
        previous.faces.forEach { face ->
            if (face.participant.id in participantIdsUsedThisPhoto) return@forEach
            if (!isSameImageArea(currentFaceBox, currentVisionWidth, currentVisionHeight, face)) return@forEach
            if (face.embedding.size != currentEmbedding.size) return@forEach
            val cosine = EmbeddingMath.cosineSimilarity(currentEmbedding, face.embedding)
            if (cosine < SERIES_MIN_COSINE) return@forEach
            if (best == null || cosine > best!!.cosineSimilarity) {
                best = RecentSeriesMatch(face = face, cosineSimilarity = cosine, deltaMs = deltaMs)
            }
        }
        return best
    }

    private fun isSameImageArea(
        currentBox: Rect,
        currentVisionWidth: Int,
        currentVisionHeight: Int,
        previousFace: RecentFinishFace,
    ): Boolean {
        if (currentVisionWidth <= 0 || currentVisionHeight <= 0 ||
            previousFace.visionWidth <= 0 || previousFace.visionHeight <= 0
        ) {
            return false
        }
        val currentCenterX = currentBox.exactCenterX() / currentVisionWidth
        val currentCenterY = currentBox.exactCenterY() / currentVisionHeight
        val previousCenterX = previousFace.box.exactCenterX() / previousFace.visionWidth
        val previousCenterY = previousFace.box.exactCenterY() / previousFace.visionHeight
        if (kotlin.math.abs(currentCenterX - previousCenterX) > SERIES_MAX_CENTER_DELTA) return false
        if (kotlin.math.abs(currentCenterY - previousCenterY) > SERIES_MAX_CENTER_DELTA) return false

        val currentWidth = currentBox.width().toFloat() / currentVisionWidth
        val currentHeight = currentBox.height().toFloat() / currentVisionHeight
        val previousWidth = previousFace.box.width().toFloat() / previousFace.visionWidth
        val previousHeight = previousFace.box.height().toFloat() / previousFace.visionHeight
        return sizeRatioWithinLimit(currentWidth, previousWidth) &&
            sizeRatioWithinLimit(currentHeight, previousHeight)
    }

    private fun sizeRatioWithinLimit(a: Float, b: Float): Boolean {
        if (a <= 0f || b <= 0f) return false
        return maxOf(a, b) / minOf(a, b) <= SERIES_MAX_SIZE_RATIO
    }

    private fun rememberRecentFinishPhoto(
        raceId: String,
        photoFile: File,
        captureTimeEpochMillis: Long,
        faces: List<RecentFinishFace>,
    ) {
        recentFinishPhotosByRace[raceId] = RecentFinishPhoto(
            sourcePhotoPath = photoFile.absolutePath,
            captureTimeEpochMillis = captureTimeEpochMillis,
            faces = faces,
        )
    }

    private data class RecentFinishPhoto(
        val sourcePhotoPath: String,
        val captureTimeEpochMillis: Long,
        val faces: List<RecentFinishFace>,
    )

    private data class RecentFinishFace(
        val participant: RaceParticipantHashEntity,
        val embedding: FloatArray,
        val box: Rect,
        val visionWidth: Int,
        val visionHeight: Int,
    )

    private data class RecentSeriesMatch(
        val face: RecentFinishFace,
        val cosineSimilarity: Float,
        val deltaMs: Long,
    )

    // Future split: move burst-frame matching and persistence side effects into focused helpers.
}
