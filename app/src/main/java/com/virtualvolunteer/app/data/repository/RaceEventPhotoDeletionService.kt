package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.FinishDetectionDao
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.RaceDao
import java.io.File

/**
 * Deletes a race event photo file and reconciles DB, protocol finish, embeddings, and thumbnails.
 */
internal class RaceEventPhotoDeletionService(
    private val appContext: Context,
    private val raceDao: RaceDao,
    private val participantHashDao: ParticipantHashDao,
    private val participantEmbeddingDao: ParticipantEmbeddingDao,
    private val finishDetectionDao: FinishDetectionDao,
    private val raceXml: RaceXmlWriter,
    private val protocolFinish: RaceProtocolFinishSync,
    private val embeddingWriter: RaceParticipantEmbeddingWriter,
    private val thumbnails: RaceListThumbnailHelper,
) {

    suspend fun deleteRaceEventPhoto(raceId: String, photoAbsolutePath: String): Boolean {
        if (raceDao.getRace(raceId) == null) return false
        if (!RacePaths.isPathUnderStartOrFinishPhotosDir(appContext, raceId, photoAbsolutePath)) return false
        val target = try {
            File(photoAbsolutePath).canonicalPath
        } catch (_: Exception) {
            return false
        }

        fun canonicalSafe(path: String?): String? {
            if (path.isNullOrBlank()) return null
            return try {
                File(path).canonicalPath
            } catch (_: Exception) {
                null
            }
        }

        val detectionRows = finishDetectionDao.listAllForRace(raceId)
        val detectionIds = detectionRows
            .filter { canonicalSafe(it.sourcePhotoPath) == target }
            .map { it.id }
        val affectedParticipants = detectionRows
            .filter { canonicalSafe(it.sourcePhotoPath) == target }
            .map { it.participantHashId }
            .distinct()

        for (id in detectionIds) {
            finishDetectionDao.deleteById(id)
        }
        for (pid in affectedParticipants) {
            protocolFinish.recomputeProtocolFinishForParticipant(raceId, pid)
        }

        for (e in participantEmbeddingDao.listForRace(raceId)) {
            val sp = e.sourcePhotoPath ?: continue
            if (canonicalSafe(sp) == target) {
                participantEmbeddingDao.clearSourcePhotoPathByEmbeddingId(e.id)
            }
        }

        for (p in participantHashDao.listForRace(raceId)) {
            val srcMatch = canonicalSafe(p.sourcePhoto) == target
            val primaryMatch = canonicalSafe(p.primaryThumbnailPhotoPath) == target
            if (!srcMatch && !primaryMatch) continue
            val face = p.faceThumbnailPath?.takeIf { f ->
                File(f).exists() && canonicalSafe(f) != target
            }
            val newSource = if (srcMatch) {
                face ?: ""
            } else {
                p.sourcePhoto
            }
            val newPrimary = if (primaryMatch) {
                face
            } else {
                p.primaryThumbnailPhotoPath
            }
            if (newSource != p.sourcePhoto || newPrimary != p.primaryThumbnailPhotoPath) {
                participantHashDao.update(
                    p.copy(
                        sourcePhoto = newSource,
                        primaryThumbnailPhotoPath = newPrimary,
                    ),
                )
                embeddingWriter.syncParticipantPrimaryEmbeddingField(p.id)
            }
        }

        val race = raceDao.getRace(raceId) ?: return false
        if (canonicalSafe(race.lastPhotoPath) == target) {
            val cleared = race.copy(lastPhotoPath = null)
            raceDao.updateRace(cleared)
            raceXml.write(cleared)
        }

        File(photoAbsolutePath).delete()

        if (thumbnails.isPathUnderStartPhotosForRace(raceId, target)) {
            thumbnails.ensureRaceListThumbnail(raceId)
        }

        protocolFinish.refreshProtocolXml(raceId)
        return true
    }
}
