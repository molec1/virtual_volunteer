package com.virtualvolunteer.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.data.repository.RaceReprocessResult
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.google.mlkit.vision.face.Face
import com.virtualvolunteer.app.domain.debug.FinishPhotoDebugReport
import com.virtualvolunteer.app.domain.face.FaceCropBounds
import com.virtualvolunteer.app.domain.face.FaceEmbedder
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.OrientedPhotoBitmap
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.participants.RaceParticipantPool
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import java.io.File

/**
 * Orchestrates ML Kit detection (stage 1), TFLite embeddings (stage 2), Room persistence, and protocol updates.
 * Heavy paths live in [StartPhotoIngestor], [FinishPhotoPipeline], [FinishPhotoDebugAnalyzer], [FinishFolderTestProtocolBuilder].
 */
class RacePhotoProcessor(
    private val races: RaceRepository,
    internal val faces: MlKitFaceDetector,
    internal val embedder: FaceEmbedder,
    internal val matcher: FaceMatchEngine,
    private val pool: RaceParticipantPool,
    private val appContext: Context,
) {

    private fun pipelineLog(line: String) {
        Log.i(TAG, line)
        (appContext.applicationContext as? VirtualVolunteerApp)?.appendPipelineLog(line)
    }

    private val finishPipeline = FinishPhotoPipeline(
        appContext = appContext,
        races = races,
        pool = pool,
        faces = faces,
        embedder = embedder,
        matcher = matcher,
        decodeVisionBitmap = ::loadVisionBitmap,
        pipelineLog = ::pipelineLog,
    )

    private val startIngestor = StartPhotoIngestor(
        appContext = appContext,
        races = races,
        faces = faces,
        embedder = embedder,
        matcher = matcher,
        decodeVisionBitmap = ::loadVisionBitmap,
        pipelineLog = ::pipelineLog,
    )

    private val finishDebug = FinishPhotoDebugAnalyzer(
        races = races,
        pool = pool,
        faces = faces,
        embedder = embedder,
        matcher = matcher,
        decodeVisionBitmap = ::loadVisionBitmap,
    )

    private val finishFolderTest = FinishFolderTestProtocolBuilder(
        races = races,
        finishPipeline = finishPipeline,
    )

    /**
     * Stage 1: ML Kit on EXIF-upright bitmap. Stage 2: expand+crop, thumbnail, TFLite embedding.
     * Inserts a participant row whenever crop succeeds; [RaceParticipantHashEntity.embeddingFailed]
     * marks TFLite failures while keeping the row visible for debugging.
     */
    suspend fun ingestStartPhoto(raceId: String, photoFile: File): Result<Int> =
        startIngestor.ingest(raceId, photoFile)

    suspend fun ingestFinishPhoto(raceId: String, photoFile: File): Result<Int> = runCatching {
        val ts = PhotoTimestampResolver.resolveEpochMillis(photoFile)
        val inserted = finishPipeline.processFinishPhotoInternal(
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
            val result = finishPipeline.processFinishPhotoInternal(
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
    ): Result<FinishPhotoDebugReport> = finishDebug.analyze(raceId, photoFile)

    suspend fun buildTestProtocolFromFinishFolder(
        context: Context,
        raceId: String,
    ): Result<File> = finishFolderTest.build(context, raceId)

    /**
     * Replays stored **full-frame** start/finish originals: clears protocol DB rows and race `faces/` + `debug/`
     * crops, re-detects and re-embeds like import, re-runs finish matching, refreshes protocol.
     * Operator scan / registry / display metadata is snapshotted before wipe and reapplied when cosine
     * to a new row is ≥ the same threshold as finish auto-match.
     */
    suspend fun reprocessRaceFromStoredEventPhotos(raceId: String): Result<RaceReprocessResult> = runCatching {
        pipelineLog("—— reprocessRaceFromStoredEventPhotos ——")
        val out = races.executeFullDiskPhotoReprocess(
            raceId = raceId,
            ingestStart = { rid, file ->
                val r = ingestStartPhoto(rid, file)
                r.onSuccess { n ->
                    pipelineLog("REPROCESS_START_DETAIL file=${file.name} participantHashesInserted=$n")
                }
                r.onFailure { e ->
                    Log.e(TAG, "reprocess start ingest failed ${file.absolutePath}", e)
                    pipelineLog("REPROCESS_START_DETAIL_FAIL file=${file.name} err=${e.message}")
                }
                r
            },
            ingestFinishNewRows = { rid, file ->
                val r = ingestFinishPhotoWithLog(rid, file)
                r.onSuccess { fr ->
                    pipelineLog("REPROCESS_FINISH_DETAIL ${fr.debugSummaryLine(file.name)}")
                    Log.i(TAG, "reprocess finish detail ${file.absolutePath}\n${fr.logText}")
                }
                r.onFailure { e ->
                    Log.e(TAG, "reprocess finish ingest failed ${file.absolutePath}", e)
                    pipelineLog("REPROCESS_FINISH_DETAIL_FAIL file=${file.name} err=${e.message}")
                }
                r.map { it.newRecordsInserted }
            },
            progressLog = { line -> pipelineLog(line) },
        )
        pipelineLog(
            "reprocessRace done startPhotos=${out.startPhotosProcessed} startFaces=${out.startFacesInserted} " +
                "startIngestFail=${out.startIngestFailures} " +
                "finishPhotos=${out.finishPhotosProcessed} finishNew=${out.finishPipelineNewRows} " +
                "finishIngestFail=${out.finishIngestFailures} " +
                "hints=${out.identityHintsCaptured} restored=${out.identityHintsRestored}",
        )
        out
    }

    /** Full-frame decode + EXIF upright for ML Kit ([OrientedPhotoBitmap]); bitmap is short-lived. */
    internal fun loadVisionBitmap(photoFile: File) =
        OrientedPhotoBitmap.decodeApplyingExifOrientation(photoFile)

    internal fun cropFace(bmp: Bitmap, face: Face): Bitmap? {
        val raw = Rect(face.boundingBox)
        val expanded = FaceCropBounds.expandFaceRect(raw, bmp.width, bmp.height, FaceCropBounds.DEFAULT_MARGIN_PER_SIDE)
        return FaceCropBounds.cropBitmap(bmp, expanded)
    }

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
