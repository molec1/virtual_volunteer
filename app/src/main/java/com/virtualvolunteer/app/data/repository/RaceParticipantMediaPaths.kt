package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.FinishDetectionDao
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import java.io.File

internal class RaceParticipantMediaPaths(
    private val appContext: Context,
    private val participantHashDao: ParticipantHashDao,
    private val finishDetectionDao: FinishDetectionDao,
) {
    suspend fun listParticipantRacePhotos(raceId: String, participantId: Long): List<ParticipantRacePhoto> {
        val p = participantHashDao.getById(participantId) ?: return emptyList()
        require(p.raceId == raceId)
        val dets = finishDetectionDao.listForParticipantSorted(raceId, participantId)
        val finishCanonical = dets.map { File(it.sourcePhotoPath).canonicalPath }.toSet()

        fun normalizeExisting(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val f = File(path)
            if (!f.exists()) return null
            return f.canonicalPath
        }

        val out = mutableListOf<ParticipantRacePhoto>()
        val seen = LinkedHashSet<String>()

        fun push(path: String?, preferFinish: Boolean) {
            if (path.isNullOrBlank()) return
            if (RacePaths.isPathUnderRaceFacesDir(appContext, raceId, path)) return
            val canonical = normalizeExisting(path) ?: return
            val isFinish = preferFinish || finishCanonical.contains(canonical)
            if (!seen.add(canonical)) {
                val i = out.indexOfFirst { it.absolutePath == canonical }
                if (i >= 0 && isFinish && !out[i].isFinishFrame) {
                    out[i] = out[i].copy(isFinishFrame = true)
                }
                return
            }
            out.add(ParticipantRacePhoto(canonical, isFinish))
        }

        push(p.sourcePhoto, false)
        for (d in dets) {
            push(d.sourcePhotoPath, true)
        }
        return out
    }

    suspend fun listFinishPhotoPathsForRace(raceId: String): List<String> {
        val dir = RacePaths.finishPhotosDir(appContext, raceId)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f ->
            f.isFile && f.name.lowercase().let { n ->
                n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
            }
        } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { it.absolutePath }
    }
}
