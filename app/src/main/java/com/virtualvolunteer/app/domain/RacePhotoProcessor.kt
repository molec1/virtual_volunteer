package com.virtualvolunteer.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
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

    /** Same bitmap space ML Kit expects: full decode + EXIF upright orientation. */
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
