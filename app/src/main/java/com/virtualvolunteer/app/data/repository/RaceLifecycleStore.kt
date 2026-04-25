package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.RaceDao
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.model.RaceStatus
import com.virtualvolunteer.app.data.xml.ProtocolXmlIo
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import java.io.File
import java.util.UUID

/**
 * Race folder layout, Room race row, mirrored race.xml, and list thumbnail hooks for lifecycle transitions.
 */
internal class RaceLifecycleStore(
    private val appContext: Context,
    private val raceDao: RaceDao,
    private val raceXml: RaceXmlWriter,
    private val thumbnails: RaceListThumbnailHelper,
) {

    suspend fun updateLastProcessedPhoto(raceId: String, absolutePath: String?) {
        val race = raceDao.getRace(raceId) ?: return
        raceDao.updateRace(race.copy(lastPhotoPath = absolutePath))
        if (absolutePath != null && thumbnails.isPathUnderStartPhotosForRace(raceId, absolutePath)) {
            thumbnails.ensureRaceListThumbnail(raceId)
        }
    }

    suspend fun applyOfflineRaceStartFromStartPhotos(raceId: String) {
        val dir = RacePaths.startPhotosDir(appContext, raceId)
        val maxExif = PhotoTimestampResolver.maxEpochAmongImages(dir) ?: return
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(startedAtEpochMillis = maxExif)
        raceDao.updateRace(updated)
        raceXml.write(updated)
    }

    suspend fun createNewRace(): String {
        val id = UUID.randomUUID().toString()
        val folder = RacePaths.ensureRaceLayout(appContext, id)
        val now = System.currentTimeMillis()

        val entity = RaceEntity(
            id = id,
            createdAtEpochMillis = now,
            startedAtEpochMillis = null,
            finishedAtEpochMillis = null,
            status = RaceStatus.CREATED,
            folderPath = folder.absolutePath,
            lastPhotoPath = null,
            listThumbnailPath = null,
        )
        raceDao.insertRace(entity)
        raceXml.write(entity)
        ProtocolXmlIo.write(RacePaths.protocolXml(appContext, id), id, emptyList())
        return id
    }

    suspend fun markStarted(raceId: String, startedAtEpochMillis: Long) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(
            status = RaceStatus.STARTED,
            startedAtEpochMillis = startedAtEpochMillis,
        )
        raceDao.updateRace(updated)
        raceXml.write(updated)
    }

    suspend fun markRecording(raceId: String) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(status = RaceStatus.RECORDING)
        raceDao.updateRace(updated)
        raceXml.write(updated)
    }

    suspend fun markFinished(raceId: String, finishedAtEpochMillis: Long) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(
            status = RaceStatus.FINISHED,
            finishedAtEpochMillis = finishedAtEpochMillis,
        )
        raceDao.updateRace(updated)
        raceXml.write(updated)
    }

    /** Updates gun / protocol start instant only (does not change [RaceEntity.status]). */
    suspend fun updateRaceStartedAtEpochMillis(raceId: String, startedAtEpochMillis: Long?) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(startedAtEpochMillis = startedAtEpochMillis)
        raceDao.updateRace(updated)
        raceXml.write(updated)
    }

    suspend fun markExported(raceId: String) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(status = RaceStatus.EXPORTED)
        raceDao.updateRace(updated)
        raceXml.write(updated)
    }

    suspend fun deleteRace(raceId: String) {
        raceDao.deleteRaceById(raceId)
        val folder = RacePaths.raceFolder(appContext, raceId)
        if (folder.exists()) {
            folder.deleteRecursively()
        }
    }
}
