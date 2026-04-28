package com.virtualvolunteer.app.data.files

import android.content.Context
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import java.io.File

/** Lists full-frame JPEG/PNG/WebP files under [RacePaths.startPhotosDir] and [RacePaths.finishPhotosDir]. */
object RaceEventPhotosLister {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    private fun imageFilesIn(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { f ->
            f.isFile && f.extension.lowercase() in IMAGE_EXT
        } ?: emptyList()
    }

    private fun sortedOldestFirst(files: List<File>): List<File> =
        files
            .distinctBy { try { it.canonicalPath } catch (_: Exception) { it.absolutePath } }
            .sortedWith(
                compareBy<File> { PhotoTimestampResolver.resolveEpochMillis(it) }
                    .thenBy { it.name },
            )

    /** Start-line originals under [RacePaths.startPhotosDir], oldest first (stable re-ingest order). */
    fun listStartPhotoFilesSortedOldestFirst(context: Context, raceId: String): List<File> =
        sortedOldestFirst(imageFilesIn(RacePaths.startPhotosDir(context, raceId)))

    /** Finish-line originals under [RacePaths.finishPhotosDir], oldest first (stable re-match order). */
    fun listFinishPhotoFilesSortedOldestFirst(context: Context, raceId: String): List<File> =
        sortedOldestFirst(imageFilesIn(RacePaths.finishPhotosDir(context, raceId)))

    fun listSortedNewestFirst(context: Context, raceId: String): List<String> {
        val merged = imageFilesIn(RacePaths.startPhotosDir(context, raceId)) +
            imageFilesIn(RacePaths.finishPhotosDir(context, raceId))
        return merged
            .sortedByDescending { it.lastModified() }
            .distinctBy { try { it.canonicalPath } catch (_: Exception) { it.absolutePath } }
            .map { it.absolutePath }
    }
}
