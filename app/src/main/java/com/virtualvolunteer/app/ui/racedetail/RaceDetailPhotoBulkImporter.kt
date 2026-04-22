package com.virtualvolunteer.app.ui.racedetail

import android.net.Uri
import android.util.Log
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.files.UriFileCopy
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import java.io.File

internal data class RaceDetailFinishImportStats(
    val copied: Int,
    val finishRowsAdded: Int,
    val ingestFailures: Int,
    val lastDest: File?,
)

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
        var copied = 0
        var finishRowsAdded = 0
        var ingestFailures = 0
        var lastDest: File? = null
        app.appendPipelineLog("—— importFinishPhotos (${uris.size} uri(s)) ——")
        for (uri in uris) {
            try {
                val name = UriFileCopy.displayName(ctx, uri)
                val dest = RacePhotoProcessor.uniqueImportedFile(dir, name)
                UriFileCopy.copyToFile(ctx, uri, dest)
                copied++
                lastDest = dest
                val ingest = photoProcessor.ingestFinishPhoto(raceId, dest)
                ingest.onSuccess { n -> finishRowsAdded += n }
                ingest.onFailure {
                    ingestFailures++
                    Log.w(TAG, "ingestFinishPhoto failed for ${dest.name}", it)
                    app.appendPipelineLog("IMPORT_INGEST_FAILED file=${dest.name} err=${it.message}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "import finish photo failed", t)
                ingestFailures++
                app.appendPipelineLog("IMPORT_COPY_FAILED err=${t.message}")
            }
        }
        lastDest?.let { repo.updateLastProcessedPhoto(raceId, it.absolutePath) }
        app.appendPipelineLog(
            "importFinishPhotos done copied=$copied finishRowsAdded=$finishRowsAdded failures=$ingestFailures",
        )
        return RaceDetailFinishImportStats(copied, finishRowsAdded, ingestFailures, lastDest)
    }
}
