package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.RaceDao
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import java.io.File

internal class RaceListThumbnailHelper(
    private val appContext: Context,
    private val raceDao: RaceDao,
) {
    fun isPathUnderStartPhotosForRace(raceId: String, absolutePath: String): Boolean = try {
        val start = RacePaths.startPhotosDir(appContext, raceId).canonicalFile
        val p = File(absolutePath).canonicalFile
        p.path.startsWith(start.path + File.separator) || p.path == start.path
    } catch (_: Exception) {
        false
    }

    suspend fun getFirstStartPhotoPathForRace(raceId: String): String? {
        val dir = RacePaths.startPhotosDir(appContext, raceId)
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f ->
            f.isFile && f.name.lowercase().let { n ->
                n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
            }
        } ?: return null
        return files.minByOrNull { it.lastModified() }?.absolutePath
    }

    suspend fun ensureRaceListThumbnail(raceId: String): String? {
        val source = getFirstStartPhotoPathForRace(raceId) ?: return clearListThumbnailState(raceId)
        val sourceFile = File(source)
        val outFile = RacePaths.raceListThumbnailFile(appContext, raceId)
        val race = raceDao.getRace(raceId) ?: return null
        val cached = race.listThumbnailPath
        if (cached != null && File(cached).exists() &&
            outFile.exists() &&
            cached == outFile.absolutePath &&
            outFile.lastModified() >= sourceFile.lastModified()
        ) {
            return outFile.absolutePath
        }
        if (!PreviewImageLoader.writeListThumbnailJpegFromPhotoSource(source, outFile)) {
            return clearListThumbnailState(raceId)
        }
        raceDao.updateRace(race.copy(listThumbnailPath = outFile.absolutePath))
        return outFile.absolutePath
    }

    private suspend fun clearListThumbnailState(raceId: String): String? {
        val f = RacePaths.raceListThumbnailFile(appContext, raceId)
        if (f.exists()) f.delete()
        val race = raceDao.getRace(raceId) ?: return null
        if (race.listThumbnailPath != null) {
            raceDao.updateRace(race.copy(listThumbnailPath = null))
        }
        return null
    }
}
