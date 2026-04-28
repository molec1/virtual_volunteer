package com.virtualvolunteer.app.ui.racedetail

import android.net.Uri
import android.util.Log
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.files.UriFileCopy
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import java.io.File

internal data class RaceDetailFinishImportStats(
    val uriCount: Int,
    val copied: Int,
    /** [ingestFinishPhotoWithLog] returned success (pipeline ran; includes decode failures as structured result). */
    val ingestCompleted: Int,
    val finishRowsAdded: Int,
    /** Decode OK and ML Kit returned zero faces. */
    val photosWithNoFaces: Int,
    val photosDecodeFailed: Int,
    val totalFacesDetected: Int,
    val copyFailures: Int,
    val ingestExceptionFailures: Int,
    /** Ingest OK but [FinishProcessResult.newRecordsInserted] == 0 (no rows, skipped faces, etc.). */
    val finishFilesWithZeroNewRows: Int,
    val lastDest: File?,
) {
    val errorCount: Int get() = copyFailures + ingestExceptionFailures
}

internal class RaceDetailPhotoBulkImporter(
    private val app: VirtualVolunteerApp,
    private val raceId: String,
    private val photoProcessor: RacePhotoProcessor,
) {
    companion object {
        private const val TAG = "RaceDetail"
    }

    suspend fun importStartPhotoUris(uris: List<Uri>): Pair<Int, Int> {
        val ctx = app.applicationContext
        val dir = RacePaths.startPhotosDir(ctx, raceId)
        var files = 0
        var hashes = 0
        for (uri in uris) {
            try {
                val name = UriFileCopy.displayName(ctx, uri)
                val dest = RacePhotoProcessor.uniqueImportedFile(dir, name)
                UriFileCopy.copyToFile(ctx, uri, dest)
                files++
                val result = photoProcessor.ingestStartPhoto(raceId, dest)
                result.onSuccess { count -> hashes += count }
            } catch (t: Throwable) {
                Log.w(TAG, "import start photo failed", t)
            }
        }
        app.raceRepository.ensureRaceListThumbnail(raceId)
        return files to hashes
    }

    suspend fun importFinishPhotoUris(uris: List<Uri>): RaceDetailFinishImportStats {
        val ctx = app.applicationContext
        val repo = app.raceRepository
        val dir = RacePaths.finishPhotosDir(ctx, raceId)
        val uriCount = uris.size
        var copied = 0
        var ingestCompleted = 0
        var finishRowsAdded = 0
        var finishFilesWithZeroNewRows = 0
        var photosWithNoFaces = 0
        var photosDecodeFailed = 0
        var totalFacesDetected = 0
        var copyFailures = 0
        var ingestExceptionFailures = 0
        var lastDest: File? = null
        app.appendPipelineLog("—— importFinishPhotos ($uriCount uri(s)) ——")
        for ((uriIndex, uri) in uris.withIndex()) {
            try {
                val name = UriFileCopy.displayName(ctx, uri)
                val dest = RacePhotoProcessor.uniqueImportedFile(dir, name)
                UriFileCopy.copyToFile(ctx, uri, dest)
                copied++
                lastDest = dest
                val ingest = photoProcessor.ingestFinishPhotoWithLog(raceId, dest)
                ingest.onSuccess { fr ->
                    ingestCompleted++
                    finishRowsAdded += fr.newRecordsInserted
                    if (fr.newRecordsInserted == 0) finishFilesWithZeroNewRows++
                    if (!fr.decodeSucceeded) {
                        photosDecodeFailed++
                    } else {
                        totalFacesDetected += fr.detectedFaceCount
                        if (fr.detectedFaceCount == 0) photosWithNoFaces++
                    }
                    app.appendPipelineLog(
                        "IMPORT_FINISH_OK [${uriIndex + 1}/$uriCount] ${fr.debugSummaryLine(dest.name)}",
                    )
                    Log.i(TAG, "import finish detail ${dest.absolutePath}\n${fr.logText}")
                    if (fr.newRecordsInserted == 0) {
                        Log.w(TAG, "import finish zero new rows: ${fr.debugSummaryLine(dest.name)}")
                    }
                }
                ingest.onFailure {
                    ingestExceptionFailures++
                    Log.w(TAG, "ingestFinishPhoto failed for ${dest.name}", it)
                    app.appendPipelineLog("IMPORT_INGEST_FAILED file=${dest.name} err=${it.message}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "import finish photo failed", t)
                copyFailures++
                app.appendPipelineLog("IMPORT_COPY_FAILED err=${t.message}")
            }
        }
        lastDest?.let { repo.updateLastProcessedPhoto(raceId, it.absolutePath) }
        app.appendPipelineLog(
            "importFinishPhotos SUMMARY uris=$uriCount copied=$copied ingestOk=$ingestCompleted " +
                "errorsCopy=$copyFailures errorsIngest=$ingestExceptionFailures " +
                "noFacePhotos=$photosWithNoFaces decodeFailed=$photosDecodeFailed " +
                "facesDetectedSum=$totalFacesDetected newRows=$finishRowsAdded zeroNewRowFiles=$finishFilesWithZeroNewRows",
        )
        return RaceDetailFinishImportStats(
            uriCount = uriCount,
            copied = copied,
            ingestCompleted = ingestCompleted,
            finishRowsAdded = finishRowsAdded,
            photosWithNoFaces = photosWithNoFaces,
            photosDecodeFailed = photosDecodeFailed,
            totalFacesDetected = totalFacesDetected,
            copyFailures = copyFailures,
            ingestExceptionFailures = ingestExceptionFailures,
            finishFilesWithZeroNewRows = finishFilesWithZeroNewRows,
            lastDest = lastDest,
        )
    }
}
