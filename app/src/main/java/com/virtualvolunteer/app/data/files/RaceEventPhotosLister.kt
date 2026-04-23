package com.virtualvolunteer.app.data.files

import android.content.Context
import java.io.File

/** Lists full-frame JPEG/PNG/WebP files under [RacePaths.startPhotosDir] and [RacePaths.finishPhotosDir]. */
object RaceEventPhotosLister {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    fun listSortedNewestFirst(context: Context, raceId: String): List<String> {
        fun filesIn(dir: File): List<File> {
            if (!dir.isDirectory) return emptyList()
            return dir.listFiles()?.filter { f ->
                f.isFile && f.extension.lowercase() in IMAGE_EXT
            } ?: emptyList()
        }
        val merged = filesIn(RacePaths.startPhotosDir(context, raceId)) +
            filesIn(RacePaths.finishPhotosDir(context, raceId))
        return merged
            .sortedByDescending { it.lastModified() }
            .distinctBy { try { it.canonicalPath } catch (_: Exception) { it.absolutePath } }
            .map { it.absolutePath }
    }
}
