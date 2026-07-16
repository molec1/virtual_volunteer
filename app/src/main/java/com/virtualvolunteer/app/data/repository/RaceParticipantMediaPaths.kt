package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.FaceCropManifestDisk
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

        // Index manifest entries for this participant: canonical source → (cropFilePath?, bbox?)
        val cropBySource = HashMap<String, String>()
        val bboxBySource = HashMap<String, FaceBoundingBox>()
        run {
            val manifestFile = RacePaths.faceCropManifestFile(appContext, raceId)
            if (manifestFile.exists()) {
                for (e in FaceCropManifestDisk.readEntries(manifestFile)) {
                    if (e.participantHashId != participantId) continue
                    val canonical = normalizeExisting(e.sourcePhotoPath) ?: continue
                    val crop = e.cropFilePath?.trim()?.takeIf { it.isNotBlank() && File(it).exists() }
                    if (crop != null) {
                        cropBySource[canonical] = crop
                    } else if (e.visionWidth > 0 && e.visionHeight > 0
                        && e.right > e.left && e.bottom > e.top
                    ) {
                        bboxBySource[canonical] = FaceBoundingBox(
                            left = e.left, top = e.top, right = e.right, bottom = e.bottom,
                            sourceWidth = e.visionWidth, sourceHeight = e.visionHeight,
                        )
                    }
                }
            }
        }
        val startCanonical = normalizeExisting(p.sourcePhoto)
        val faceThumbnail = p.faceThumbnailPath?.trim()
            ?.takeIf { it.isNotBlank() && File(it).exists() }

        return out.map { row ->
            val tick = officialCanonical != null && row.absolutePath == officialCanonical
            val cropPath = cropBySource[row.absolutePath]
                ?: if (row.absolutePath == startCanonical) faceThumbnail else null
            val bbox = if (cropPath == null) bboxBySource[row.absolutePath] else null
            row.copy(isFinishFrame = tick, faceCropPath = cropPath, faceBoundingBox = bbox)
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
