package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.FinishRecordDao
import com.virtualvolunteer.app.data.local.FinishRecordEntity
import com.virtualvolunteer.app.data.local.ParticipantDashboardRow
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.RaceDao
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.data.model.RaceStatus
import com.virtualvolunteer.app.data.xml.ProtocolXmlIo
import com.virtualvolunteer.app.data.xml.RaceXmlIo
import com.virtualvolunteer.app.data.xml.RaceXmlSnapshot
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import com.virtualvolunteer.app.location.LocationCapture
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Local races repository: Room metadata + mirrored XML + folder layout.
 */
class RaceRepository(
    private val appContext: Context,
    private val raceDao: RaceDao,
    private val participantHashDao: ParticipantHashDao,
    private val finishRecordDao: FinishRecordDao,
) {

    fun observeAllRaces(): Flow<List<RaceEntity>> = raceDao.observeAllRaces()

    fun observeRace(raceId: String): Flow<RaceEntity?> = raceDao.observeRace(raceId)

    fun observeParticipantDashboard(raceId: String): Flow<List<ParticipantDashboardRow>> =
        participantHashDao.observeParticipantDashboard(raceId)

    suspend fun getRace(raceId: String): RaceEntity? = raceDao.getRace(raceId)

    suspend fun updateLastProcessedPhoto(raceId: String, absolutePath: String?) {
        val race = raceDao.getRace(raceId) ?: return
        raceDao.updateRace(race.copy(lastPhotoPath = absolutePath))
    }

    /**
     * Offline test helper: sets [RaceEntity.startedAtEpochMillis] to the maximum resolved
     * timestamp among images in start_photos (EXIF-first), and mirrors race.xml.
     */
    suspend fun applyOfflineRaceStartFromStartPhotos(raceId: String) {
        val dir = RacePaths.startPhotosDir(appContext, raceId)
        val maxExif = PhotoTimestampResolver.maxEpochAmongImages(dir) ?: return
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(startedAtEpochMillis = maxExif)
        raceDao.updateRace(updated)
        writeRaceXml(updated)
    }

    /**
     * Creates a new race folder, initial race.xml + empty protocol.xml, and a Room row.
     * Attempts to capture coordinates when permission allows; otherwise leaves nulls.
     */
    suspend fun createNewRace(): String {
        val id = UUID.randomUUID().toString()
        val folder = RacePaths.ensureRaceLayout(appContext, id)
        val now = System.currentTimeMillis()

        val location = LocationCapture.tryGetCurrentLocation(appContext)

        val entity = RaceEntity(
            id = id,
            createdAtEpochMillis = now,
            startedAtEpochMillis = null,
            finishedAtEpochMillis = null,
            latitude = location?.latitude,
            longitude = location?.longitude,
            status = RaceStatus.CREATED,
            folderPath = folder.absolutePath,
            lastPhotoPath = null,
        )
        raceDao.insertRace(entity)
        writeRaceXml(entity)
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
        writeRaceXml(updated)
    }

    suspend fun markRecording(raceId: String) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(status = RaceStatus.RECORDING)
        raceDao.updateRace(updated)
        writeRaceXml(updated)
    }

    suspend fun markFinished(raceId: String, finishedAtEpochMillis: Long) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(
            status = RaceStatus.FINISHED,
            finishedAtEpochMillis = finishedAtEpochMillis,
        )
        raceDao.updateRace(updated)
        writeRaceXml(updated)
    }

    suspend fun markExported(raceId: String) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(status = RaceStatus.EXPORTED)
        raceDao.updateRace(updated)
        writeRaceXml(updated)
    }

    suspend fun insertParticipantHash(row: RaceParticipantHashEntity): Long =
        participantHashDao.insert(row)

    suspend fun listParticipantHashes(raceId: String): List<RaceParticipantHashEntity> =
        participantHashDao.listForRace(raceId)

    suspend fun countParticipantsForRace(raceId: String): Int =
        participantHashDao.countForRace(raceId)

    suspend fun insertFinishRecord(record: FinishRecordEntity): Long {
        val id = finishRecordDao.insert(record)
        refreshProtocolXml(record.raceId)
        return id
    }

    suspend fun listFinishRecords(raceId: String): List<FinishRecordEntity> =
        finishRecordDao.listForRace(raceId)

    suspend fun usedParticipantHashIds(raceId: String): Set<Long> =
        finishRecordDao.usedParticipantHashIds(raceId).toSet()

    suspend fun refreshProtocolXml(raceId: String) {
        val rows = finishRecordDao.listForRace(raceId)
        ProtocolXmlIo.write(RacePaths.protocolXml(appContext, raceId), raceId, rows)
    }

    private fun writeRaceXml(entity: RaceEntity) {
        val snap = RaceXmlSnapshot(
            id = entity.id,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            startedAtEpochMillis = entity.startedAtEpochMillis,
            finishedAtEpochMillis = entity.finishedAtEpochMillis,
            latitude = entity.latitude,
            longitude = entity.longitude,
            status = entity.status,
        )
        RaceXmlIo.write(RacePaths.raceXml(appContext, entity.id), snap)
    }
}
