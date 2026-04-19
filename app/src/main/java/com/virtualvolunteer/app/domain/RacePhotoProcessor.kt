package com.virtualvolunteer.app.domain

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.FinishRecordEntity
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.domain.debug.FinishFaceDebugRow
import com.virtualvolunteer.app.domain.debug.FinishPhotoDebugReport
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.face.FaceCropBounds
import com.virtualvolunteer.app.domain.face.FaceDebugOverlay
import com.virtualvolunteer.app.domain.face.FaceEmbedder
import com.virtualvolunteer.app.domain.face.FaceThumbnailSaver
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.OrientedPhotoBitmap
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.matching.MatchScore
import com.virtualvolunteer.app.domain.participants.RaceParticipantPool
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * Orchestrates ML Kit detection (stage 1), TFLite embeddings (stage 2), Room persistence, and protocol updates.
 */
class RacePhotoProcessor(
    private val races: RaceRepository,
    private val faces: MlKitFaceDetector,
    private val embedder: FaceEmbedder,
    private val matcher: FaceMatchEngine,
    private val pool: RaceParticipantPool,
    private val appContext: Context,
) {

    private fun pipelineLog(line: String) {
        Log.i(TAG, line)
        (appContext.applicationContext as? VirtualVolunteerApp)?.appendPipelineLog(line)
    }

    /**
     * Stage 1: ML Kit on EXIF-upright bitmap. Stage 2: expand+crop, thumbnail, TFLite embedding.
     * Inserts a participant row whenever crop succeeds; [RaceParticipantHashEntity.embeddingFailed]
     * marks TFLite failures while keeping the row visible for debugging.
     */
    suspend fun ingestStartPhoto(raceId: String, photoFile: File): Result<Int> = runCatching {
        RacePaths.ensureRaceLayout(appContext, raceId)
        pipelineLog("—— ingestStartPhoto ——")
        pipelineLog("raceId=${raceId.take(8)}… sourceFile=${photoFile.name}")
        pipelineLog(OrientedPhotoBitmap.describeExifOrientation(photoFile))

        val bmp = loadVisionBitmap(photoFile) ?: run {
            val msg = "DECODE_FAILED path=${photoFile.absolutePath}"
            Log.e(TAG, msg)
            pipelineLog(msg)
            return@runCatching 0
        }
        pipelineLog("visionBitmap=${bmp.width}x${bmp.height} (after EXIF upright correction)")

        val facesDir = RacePaths.facesDir(appContext, raceId)
        val debugDir = RacePaths.debugDir(appContext, raceId)
        debugDir.mkdirs()
        val createdAt = PhotoTimestampResolver.resolveEpochMillis(photoFile)
        val margin = FaceCropBounds.DEFAULT_MARGIN_PER_SIDE

        try {
            val detected = faces.detectFaces(bmp)
            pipelineLog("detectedFaceCount=${detected.size}")
            Log.i(TAG, "ingestStartPhoto detectedFaceCount=${detected.size}")

            detected.forEachIndexed { i, face ->
                pipelineLog("detectorFace[${i + 1}] boundingBox=${face.boundingBox}")
                Log.i(TAG, "face[${i + 1}] bbox=${face.boundingBox}")
            }

            if (detected.isNotEmpty()) {
                val overlayFile = File(
                    debugDir,
                    "start_${photoFile.nameWithoutExtension}_${System.currentTimeMillis()}.jpg",
                )
                val overlayOk = FaceDebugOverlay.saveAnnotatedCopy(
                    bmp,
                    detected,
                    margin,
                    overlayFile,
                )
                pipelineLog("debugOverlaySaved=$overlayOk file=${overlayFile.absolutePath}")
            }

            if (detected.isEmpty()) {
                pipelineLog("STOP: no faces (skip crop/embed/insert)")
                Log.w(TAG, "ingestStartPhoto no faces")
                return@runCatching 0
            }

            var inserted = 0
            detected.forEachIndexed { index, face ->
                val faceNum = index + 1
                val raw = Rect(face.boundingBox)
                val expanded = FaceCropBounds.expandFaceRect(raw, bmp.width, bmp.height, margin)
                pipelineLog("face#$faceNum rawBoundingBox=$raw expandedBoundingBox=$expanded marginPerSide=$margin")

                val crop = FaceCropBounds.cropBitmap(bmp, expanded)
                pipelineLog("face#$faceNum cropSucceeded=${crop != null}")
                if (crop == null) {
                    Log.w(TAG, "ingestStartPhoto face#$faceNum crop_failed")
                    return@forEachIndexed
                }

                val thumbFile = FaceThumbnailSaver.thumbnailFile(facesDir, photoFile, faceNum)
                var thumbnailSaved = false
                try {
                    FaceThumbnailSaver.saveJpeg(crop, thumbFile)
                    thumbnailSaved = thumbFile.exists() && thumbFile.length() > 0L
                } catch (e: Exception) {
                    Log.e(TAG, "thumbnail save failed face#$faceNum", e)
                    pipelineLog("face#$faceNum thumbnailSaved=false err=${e.message}")
                }
                pipelineLog("face#$faceNum thumbnailSaved=$thumbnailSaved path=${thumbFile.absolutePath}")

                val embedResult = runCatching {
                    withContext(Dispatchers.Default) { embedder.embed(crop) }
                }
                crop.recycle()

                val vec = embedResult.getOrNull()
                val embeddingFailed = vec == null
                val embeddingStr = vec?.let { EmbeddingMath.formatCommaSeparated(it) } ?: ""

                if (embeddingFailed) {
                    embedResult.exceptionOrNull()?.let { err ->
                        Log.e(TAG, "embedding failed face#$faceNum", err)
                        pipelineLog("face#$faceNum descriptorCreated=false err=${err.message}")
                    }
                } else {
                    pipelineLog("face#$faceNum descriptorCreated=true dim=${vec.size}")
                }

                val rowId = races.insertParticipantHash(
                    RaceParticipantHashEntity(
                        raceId = raceId,
                        embedding = embeddingStr,
                        embeddingFailed = embeddingFailed,
                        sourcePhoto = photoFile.absolutePath,
                        faceThumbnailPath = thumbFile.absolutePath,
                        createdAtEpochMillis = createdAt,
                    ),
                )
                val total = races.countParticipantsForRace(raceId)
                pipelineLog(
                    "face#$faceNum participantRowInserted=true id=$rowId embeddingFailed=$embeddingFailed " +
                        "totalParticipants=$total",
                )
                Log.i(
                    TAG,
                    "insert id=$rowId embeddingFailed=$embeddingFailed totalParticipants=$total",
                )
                inserted++
            }

            pipelineLog("ingestStartPhoto done insertedRows=$inserted")
            inserted
        } finally {
            races.updateLastProcessedPhoto(raceId, photoFile.absolutePath)
            bmp.recycle()
        }
    }

    suspend fun ingestFinishPhoto(raceId: String, photoFile: File): Result<Int> = runCatching {
        val ts = PhotoTimestampResolver.resolveEpochMillis(photoFile)
        val inserted = processFinishPhotoInternal(
            raceId = raceId,
            photoFile = photoFile,
            finishTimeEpochMillis = ts,
        ).newRecordsInserted
        races.updateLastProcessedPhoto(raceId, photoFile.absolutePath)
        inserted
    }

    suspend fun ingestFinishPhotoWithLog(raceId: String, photoFile: File): Result<FinishProcessResult> =
        runCatching {
            val ts = PhotoTimestampResolver.resolveEpochMillis(photoFile)
            val result = processFinishPhotoInternal(
                raceId = raceId,
                photoFile = photoFile,
                finishTimeEpochMillis = ts,
            )
            races.updateLastProcessedPhoto(raceId, photoFile.absolutePath)
            result
        }

    suspend fun analyzeFinishPhotoDebug(
        raceId: String,
        photoFile: File,
    ): Result<FinishPhotoDebugReport> = runCatching {
        val bmp = loadVisionBitmap(photoFile)
            ?: return@runCatching FinishPhotoDebugReport(
                photoPath = photoFile.absolutePath,
                detectedFaceCount = 0,
                faces = emptyList(),
            )
        try {
            val detected = faces.detectFaces(bmp)
            Log.i(TAG, "analyzeFinishDebug file=${photoFile.name} detectedFaceCount=${detected.size}")
            val basePool = pool.participantHashes(raceId).filter { !it.embeddingFailed }
            val used = races.usedParticipantHashIds(raceId)
            val threshold = matcher.threshold()

            val rows = ArrayList<FinishFaceDebugRow>(detected.size)
            val margin = FaceCropBounds.DEFAULT_MARGIN_PER_SIDE
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
                val nearest: MatchScore? = matcher.nearest(vec, basePool)
                val nearestId = nearest?.participant?.id
                val sim = nearest?.cosineSimilarity
                val passes = sim != null && sim >= threshold
                val alreadyDone = nearestId != null && nearestId in used
                val wouldRecord = passes && nearestId != null && !alreadyDone
                rows.add(
                    FinishFaceDebugRow(
                        faceIndex = index + 1,
                        embeddingPreview = preview,
                        nearestParticipantId = nearestId,
                        nearestParticipantEmbeddingPreview = nearest?.participant?.embedding?.let { e ->
                            if (e.length > 64) e.take(64) + "…" else e
                        },
                        cosineSimilarity = sim,
                        cosineThreshold = threshold,
                        passesThreshold = passes,
                        participantAlreadyFinished = alreadyDone,
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

    suspend fun buildTestProtocolFromFinishFolder(
        context: Context,
        raceId: String,
    ): Result<File> = runCatching {
        RacePaths.ensureRaceLayout(context, raceId)
        races.applyOfflineRaceStartFromStartPhotos(raceId)

        val dir = RacePaths.finishPhotosDir(context, raceId)
        val images = dir.listFiles { file ->
            file.isFile && file.name.lowercase(Locale.US).let { name ->
                name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".webp")
            }
        }?.sortedBy { it.name }.orEmpty()

        val sb = StringBuilder()
        sb.appendLine("raceId=$raceId")
        sb.appendLine("offlineStartAppliedFromStartPhotos=true")
        sb.appendLine("finishPhotoCount=${images.size}")
        sb.appendLine("---")

        var totalNew = 0
        for (file in images) {
            val ts = PhotoTimestampResolver.resolveEpochMillis(file)
            sb.appendLine("file=${file.name} resolvedFinishTimeMs=$ts")
            val outcome = processFinishPhotoInternal(
                raceId = raceId,
                photoFile = file,
                finishTimeEpochMillis = ts,
            )
            totalNew += outcome.newRecordsInserted
            sb.appendLine("newRecordsInserted=${outcome.newRecordsInserted}")
            sb.append(outcome.logText)
            sb.appendLine("---")
            races.updateLastProcessedPhoto(raceId, file.absolutePath)
        }
        sb.appendLine("totalNewRecords=$totalNew")

        val logFile = RacePaths.testProtocolDebugLog(context, raceId)
        logFile.parentFile?.mkdirs()
        logFile.writeText(sb.toString(), Charsets.UTF_8)
        logFile
    }

    private suspend fun processFinishPhotoInternal(
        raceId: String,
        photoFile: File,
        finishTimeEpochMillis: Long,
    ): FinishProcessResult {
        RacePaths.ensureRaceLayout(appContext, raceId)
        pipelineLog("—— ingestFinishPhoto ——")
        pipelineLog("raceId=${raceId.take(8)}… sourceFile=${photoFile.name}")
        pipelineLog("finishTimeEpochMillis=$finishTimeEpochMillis (${Date(finishTimeEpochMillis)})")
        pipelineLog(OrientedPhotoBitmap.describeExifOrientation(photoFile))

        val bmp = loadVisionBitmap(photoFile)
            ?: run {
                val msg = "DECODE_FAILED path=${photoFile.absolutePath}"
                pipelineLog(msg)
                return FinishProcessResult(0, logNoBitmap(photoFile) + msg + "\n")
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
                return FinishProcessResult(0, sb.toString())
            }

            val basePool = pool.participantHashes(raceId)
            val embeddingPool = basePool.filter { !it.embeddingFailed }
            val used = races.usedParticipantHashIds(raceId).toMutableSet()
            val available = embeddingPool.filter { it.id !in used }.toMutableList()

            pipelineLog("participantPoolSize=${basePool.size} withValidEmbedding=${embeddingPool.size}")
            pipelineLog("alreadyFinishedIdsCount=${used.size} stillAvailableToMatch=${available.size} threshold=${matcher.threshold()}")
            sb.appendLine("poolSize=${basePool.size} embeddingPool=${embeddingPool.size} available=${available.size}")

            if (embeddingPool.isEmpty()) {
                pipelineLog("STOP: no participants with embeddings to match")
                sb.appendLine("ERROR=no_embedding_pool")
                return FinishProcessResult(0, sb.toString())
            }

            var newRows = 0
            detected.forEachIndexed { index, face ->
                val faceNum = index + 1
                val raw = Rect(face.boundingBox)
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
                crop.recycle()

                val vec = embedResult.getOrNull()
                if (vec == null) {
                    embedResult.exceptionOrNull()?.let { err ->
                        Log.e(TAG, "finishPhoto face#$faceNum embedding failed", err)
                        pipelineLog("face#$faceNum descriptorCreated=false err=${err.message}")
                    }
                    sb.appendLine("face#$faceNum embedding_failed=${embedResult.exceptionOrNull()?.message}")
                    return@forEachIndexed
                }

                pipelineLog("face#$faceNum descriptorCreated=true dim=${vec.size}")

                val nearestAll = matcher.nearest(vec, embeddingPool)
                val nId = nearestAll?.participant?.id
                val nCos = nearestAll?.cosineSimilarity
                val thr = matcher.threshold()
                pipelineLog(
                    "face#$faceNum nearestParticipantId=$nId cosine=$nCos threshold=$thr passesDistance=${
                        nCos != null && nCos >= thr
                    }",
                )
                sb.appendLine("face#$faceNum nearestId=$nId cosine=$nCos thr=$thr")

                if (available.isEmpty()) {
                    val msg = "face#$faceNum skip=no_available_participants_all_finished_or_empty"
                    pipelineLog(msg)
                    sb.appendLine(msg)
                    return@forEachIndexed
                }

                val match = matcher.match(vec, available)
                if (match == null) {
                    val msg =
                        "face#$faceNum no_match_within_threshold bestCosine=$nCos (need >=$thr)"
                    pipelineLog(msg)
                    sb.appendLine(msg)
                    return@forEachIndexed
                }

                races.insertFinishRecord(
                    FinishRecordEntity(
                        raceId = raceId,
                        participantHashId = match.id,
                        embedding = match.embedding,
                        finishTimeEpochMillis = finishTimeEpochMillis,
                        photoPath = photoFile.absolutePath,
                    ),
                )
                newRows++
                used.add(match.id)
                available.removeAll { it.id == match.id }
                val matchVec = EmbeddingMath.parseCommaSeparated(match.embedding)
                val cos = EmbeddingMath.cosineSimilarity(vec, matchVec)
                val ok = "face#$faceNum RECORDED finishRow participantHashId=${match.id} cosine=$cos photo=${photoFile.name}"
                pipelineLog(ok)
                Log.i(TAG, ok)
                sb.appendLine("face#$faceNum RECORDED participantHashId=${match.id} cosine=$cos")
            }

            pipelineLog("ingestFinishPhoto done newFinishRows=$newRows")
            return FinishProcessResult(newRows, sb.toString())
        } finally {
            bmp.recycle()
        }
    }

    private fun previewEmbedding(vec: FloatArray): String {
        val s = EmbeddingMath.formatCommaSeparated(vec)
        return if (s.length > 96) s.take(96) + "…" else s
    }

    /** Same bitmap space ML Kit expects: full decode + EXIF upright orientation. */
    private fun loadVisionBitmap(photoFile: File) =
        OrientedPhotoBitmap.decodeApplyingExifOrientation(photoFile)

    private fun logNoBitmap(file: File): String =
        "decode_failed path=${file.absolutePath}\n"

    companion object {
        private const val TAG = "RacePhotoProcessor"

        fun defaultOutputStartPhotoFile(context: Context, raceId: String): File {
            val dir = RacePaths.startPhotosDir(context, raceId)
            dir.mkdirs()
            return File(dir, "start_${System.currentTimeMillis()}.jpg")
        }

        fun defaultOutputFinishPhotoFile(context: Context, raceId: String): File {
            val dir = RacePaths.finishPhotosDir(context, raceId)
            dir.mkdirs()
            return File(dir, "finish_${System.currentTimeMillis()}.jpg")
        }

        fun uniqueImportedFile(dir: File, originalName: String): File {
            dir.mkdirs()
            val safe = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return File(dir, "import_${System.currentTimeMillis()}_$safe")
        }
    }
}
