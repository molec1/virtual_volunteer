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

        fun normalizeExisting(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val f = File(path)
            if (!f.exists()) return null
            return f.canonicalPath
        }

        val out = mutableListOf<ParticipantRacePhoto>()
        val seen = LinkedHashSet<String>()

        fun push(path: String?) {
            if (path.isNullOrBlank()) return
            if (RacePaths.isPathUnderRaceFacesDir(appContext, raceId, path)) return
            val canonical = normalizeExisting(path) ?: return
            if (!seen.add(canonical)) return
            out.add(ParticipantRacePhoto(canonical, isFinishFrame = false))
        }

        push(p.sourcePhoto)
        for (d in dets) {
            push(d.sourcePhotoPath)
        }

        val officialCanonical = ProtocolFinishPhotoPicker
            .pickSourcePhotoPath(p.protocolFinishTimeEpochMillis, dets)
            ?.let { normalizeExisting(it) }
        return out.map { row ->
            val tick = officialCanonical != null && row.absolutePath == officialCanonical
            row.copy(isFinishFrame = tick)
        }
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
