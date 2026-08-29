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
import com.virtualvolunteer.app.domain.face.FaceGeometryFilters
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
        private const val SERIES_PHOTO_WINDOW_MS = 3_000L
        private const val SERIES_MIN_COSINE = 0.3f
        /**
         * Relaxed cosine threshold for frames that are close in time (< [SERIES_TIGHT_WINDOW_MS]).
         * When a runner is sprinting toward the camera their face can change angle rapidly between
         * consecutive frames, causing the normal threshold to fail even though all positional checks
         * (center delta, size ratio) pass.
         *
         * Calibrated on race cc7a5378:
         *   • Confirmed same-finisher pairs with gap 334 ms, 640 ms, 717 ms, 1034 ms all have
         *     cosine < 0.30 but pass center-delta (≤ 0.10) and size-ratio (≤ 2.5) checks.
         *   • Window expanded from 500 ms → 2 000 ms because the motion-blur / angle-change
         *     effect persists up to ~2 s for a sprinting approach.
         * Spatial guards (SERIES_MAX_CENTER_DELTA=0.12, SERIES_MAX_SIZE_RATIO=3.0) remain the
         * primary defence against false merges within this tight window.
         */
        private const val SERIES_MIN_COSINE_TIGHT = 0.20f
        private const val SERIES_TIGHT_WINDOW_MS = 2_000L
        private const val SERIES_MAX_CENTER_DELTA = 0.12f
        /**
         * Max size ratio between a face in photo N and the same-area face in photo N-1 to be
         * considered a series match. Raised from 2.25 to 3.0 because a runner approaching the
         * camera at finish speed can grow 2.4–2.5× between consecutive frames (≈300–700 ms
         * apart). The cosine-similarity gate remains the primary guard against false matches.
         * Calibrated on race cc7a5378: ratio=2.42 blocked 29→31 and 2.44 blocked 53→54.
         */
        private const val SERIES_MAX_SIZE_RATIO = 3.0f
        private val FINISH_FILENAME_MILLIS = Regex("""^finish_(\d{10,})""")

        /**
         * Two-parameter small-face filter applied when the photo contains at least one face
         * whose area meets [SMALL_FACE_MIN_AREA_PX2].  Any face that fails either condition
         * is skipped (its embedding is never computed and no participant row is created):
         *
         *   1. Absolute floor — area ≥ SMALL_FACE_MIN_AREA_PX2
         *   2. Relative floor — area ≥ maxFaceArea / SMALL_FACE_RELATIVE_RATIO
         *
         * The effective per-photo cutoff is `max(SMALL_FACE_MIN_AREA_PX2, maxFaceArea / ratio)`.
         * If every detected face is below [SMALL_FACE_MIN_AREA_PX2] the filter is inactive
         * and all faces are processed normally (e.g. a solo runner appearing small in an
         * early burst frame).
         *
         * Calibrated on race cc7a5378 (89 finish photos, 18 confirmed finishers, 62 noise):
         *   • ABS=10 000 alone eliminates 20 noise entries; +÷3 eliminates 7 more (27 total).
         *   • All 18 protocol participants and all 10 borderline candidates are preserved.
         *   • Boundary case: pid=879 (Евгений КУЗНЕЦОВ, area ≈ 12–15 k) is the tightest
         *     survivor — in burst #15 the photo-max never exceeds 75 k, so ratio÷3 keeps the
         *     cutoff at 10 000 and pid=879 passes on the very first frame, then series
         *     carry-over protects subsequent frames.  Tightening to ÷2 would drop pid=879.
         */
        internal const val SMALL_FACE_MIN_AREA_PX2 = 10_000

        /**
         * Relative divisor for the small-face filter (see [SMALL_FACE_MIN_AREA_PX2]).
         * A face is skipped when its area < maxFaceAreaInPhoto / SMALL_FACE_RELATIVE_RATIO,
         * provided the absolute filter is already active.  Value 3 means faces occupying
         * less than 1/3 of the largest face in the same photo are discarded.
         */
        internal const val SMALL_FACE_RELATIVE_RATIO = 3

        /**
         * Unconditional hard floor: any face whose bounding-box area is below this value is
         * always skipped, regardless of other faces in the same photo.  This catches accidental
         * detections of very distant background faces that would otherwise sneak through when
         * they are the only face detected in a frame (so the conditional [SMALL_FACE_MIN_AREA_PX2]
         * filter would be inactive).
         *
         * Calibrated on race cc7a5378: the smallest confirmed real-participant area (across all
         * their detected frames) was ≈ 4 900 px² (Irina LARINA, early burst frame appearing
         * alone).  A floor of 3 000 px² eliminates phantom detections (areas 884–2 867 px²)
         * while leaving genuine solo-runner frames intact.
         */
        internal const val SMALL_FACE_HARD_FLOOR_PX2 = 3_000

        /**
         * Minimum cosine similarity required to append a new embedding to an existing
         * participant's pool via a standard (non-series) pool match.
         *
         * Setting this higher than the detection threshold prevents the "magnet" effect:
         * once a participant accumulates a few marginal-cosine wrong-person detections their
         * embedding set diversifies, causing even more wrong future matches.  Series matches
         * are exempt because temporal and spatial guards already ensure the same physical
         * person; their embedding is always worth appending.
         *
         * Calibrated on race cc7a5378: pid=1098 (Kostya, finished 17:50) accumulated 26
         * finish detections across 15+ minutes — including faces at cx=0.058 (far left edge)
         * and at 34:20 (15 min after his finish) — because each low-quality pool match
         * appended another alien embedding.  With this guard those detections are still
         * recorded (finish time unchanged) but don't pollute the embedding set.
         */
        private const val FINISH_EMBED_APPEND_MIN_COSINE = 0.50f
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

            // Hard floor: always applied, unconditionally.
            val afterHardFloor = detected.filter {
                it.boundingBox.width() * it.boundingBox.height() >= SMALL_FACE_HARD_FLOOR_PX2
            }
            val hardFloorSkipped = detected.size - afterHardFloor.size
            if (hardFloorSkipped > 0) {
                val msg = "hardFloor skipped=$hardFloorSkipped floor=$SMALL_FACE_HARD_FLOOR_PX2"
                pipelineLog(msg); sb.appendLine(msg)
            }

            if (afterHardFloor.isEmpty()) {
                pipelineLog("STOP: no faces after hard floor (no match / no finish row)")
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
                    detectedFaceCount = detected.size,
                )
            }

            // Conditional relative+absolute filter: active only when the photo has
            // at least one face meeting the absolute minimum.
            val maxRawFaceAreaPx2 = afterHardFloor.maxOf { it.boundingBox.width() * it.boundingBox.height() }
            val filterSmallFaces = maxRawFaceAreaPx2 >= SMALL_FACE_MIN_AREA_PX2
            val effectiveCutoffPx2 = if (filterSmallFaces) {
                maxOf(SMALL_FACE_MIN_AREA_PX2, maxRawFaceAreaPx2 / SMALL_FACE_RELATIVE_RATIO)
            } else {
                0
            }
            val facesToProcess = if (filterSmallFaces) {
                afterHardFloor.filter { it.boundingBox.width() * it.boundingBox.height() >= effectiveCutoffPx2 }
            } else {
                afterHardFloor
            }
            val smallFacesSkipped = afterHardFloor.size - facesToProcess.size
            if (smallFacesSkipped > 0) {
                val msg = "smallFaceFilter skipped=$smallFacesSkipped maxFaceArea=$maxRawFaceAreaPx2 " +
                    "effectiveCutoff=$effectiveCutoffPx2 absMin=$SMALL_FACE_MIN_AREA_PX2 ratio=$SMALL_FACE_RELATIVE_RATIO"
                pipelineLog(msg)
                sb.appendLine(msg)
            }

            val facesDir = RacePaths.facesDir(appContext, raceId)
            facesDir.mkdirs()

            var newRows = 0
            val currentSeriesFaces = mutableListOf<RecentFinishFace>()
            val participantIdsUsedThisPhoto = mutableSetOf<Long>()
            facesToProcess.forEachIndexed { index, face ->
                val faceNum = index + 1
                var optionalFinishThumb: File? = null
                val raw = Rect(face.boundingBox)
                val rawFaceHeightPx = raw.height()

                val profileAspect = if (rawFaceHeightPx <= 0) 1f else raw.width().toFloat() / rawFaceHeightPx
                val rawCxNorm = raw.exactCenterX() / bmp.width
                val faceRawArea = raw.width() * raw.height()
                val otherCxNorms = facesToProcess.mapNotNull { other ->
                    if (other === face) null else other.boundingBox.exactCenterX() / bmp.width
                }
                val edgeSkipTag = FaceGeometryFilters.finishEdgeSkipTag(
                    aspect = profileAspect,
                    cxNorm = rawCxNorm,
                    areaPx2 = faceRawArea,
                    sizeFilteredCount = facesToProcess.size,
                    otherCxNorms = otherCxNorms,
                )
                if (edgeSkipTag != null) {
                    val msg = "face#$faceNum $edgeSkipTag " +
                        "aspect=${"%.2f".format(profileAspect)} " +
                        "cxNorm=${"%.3f".format(rawCxNorm)} " +
                        "area=$faceRawArea group=${facesToProcess.size}"
                    pipelineLog(msg); sb.appendLine(msg)
                    return@forEachIndexed
                }

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

                val matchedExistingParticipant = standardMatchInAvailable != null || seriesParticipant != null

                if (resolvedParticipant.isVolunteer) {
                    // Volunteer recognised in the finish zone — update embeddings so future
                    // frames keep matching, but do NOT record a finish detection or touch the
                    // protocol finish time.
                    val appendedEmbedding = if (matchedExistingParticipant &&
                        (seriesParticipant != null || cos >= FINISH_EMBED_APPEND_MIN_COSINE)
                    ) {
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
                    currentSeriesFaces += RecentFinishFace(
                        participant = resolvedParticipant,
                        embedding = vec,
                        box = Rect(expanded),
                        visionWidth = bmp.width,
                        visionHeight = bmp.height,
                    )
                    participantIdsUsedThisPhoto += resolvedParticipant.id
                    pipelineLog(
                        "face#$faceNum volunteer_recognised participantId=${resolvedParticipant.id} " +
                            "cos=$cos embeddingAppended=$appendedEmbedding finish_detection=skipped",
                    )
                    sb.appendLine(
                        "face#$faceNum volunteer participant=${resolvedParticipant.id} cos=$cos finish_detection=skipped",
                    )
                    return@forEachIndexed
                }

                val outcome = races.recordFinishDetectionForParticipant(
                    raceId = raceId,
                    participantId = resolvedParticipant.id,
                    detectedAtEpochMillis = finishTimeEpochMillis,
                    sourcePhotoPath = photoFile.absolutePath,
                    matchCosineSimilarity = cos,
                    sourceEmbedding = vec,
                )

                // Series matches are always allowed to enrich the embedding set (same person in
                // a burst, guarded by spatial checks).  Standard pool matches only append when
                // the cosine is high enough to avoid the magnet drift described in
                // FINISH_EMBED_APPEND_MIN_COSINE.
                val appendedEmbedding = if (matchedExistingParticipant &&
                    (seriesParticipant != null || cos >= FINISH_EMBED_APPEND_MIN_COSINE)
                ) {
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

        val cosineThreshold = if (deltaMs < SERIES_TIGHT_WINDOW_MS) SERIES_MIN_COSINE_TIGHT else SERIES_MIN_COSINE
        var best: RecentSeriesMatch? = null
        previous.faces.forEach { face ->
            if (face.participant.id in participantIdsUsedThisPhoto) return@forEach
            if (!isSameImageArea(currentFaceBox, currentVisionWidth, currentVisionHeight, face)) return@forEach
            if (face.embedding.size != currentEmbedding.size) return@forEach
            val cosine = EmbeddingMath.cosineSimilarity(currentEmbedding, face.embedding)
            if (cosine < cosineThreshold) return@forEach
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
