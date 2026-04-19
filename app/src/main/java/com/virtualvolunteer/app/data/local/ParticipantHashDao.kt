package com.virtualvolunteer.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ParticipantHashDao {

    @Insert
    suspend fun insert(hash: RaceParticipantHashEntity): Long

    @Query("SELECT * FROM race_participant_hashes WHERE raceId = :raceId")
    suspend fun listForRace(raceId: String): List<RaceParticipantHashEntity>

    @Query("SELECT * FROM race_participant_hashes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RaceParticipantHashEntity?

    @Update
    suspend fun update(row: RaceParticipantHashEntity)

    @Query("DELETE FROM race_participant_hashes WHERE id = :id AND raceId = :raceId")
    suspend fun deleteById(id: Long, raceId: String): Int

    @Query("SELECT COUNT(*) FROM race_participant_hashes WHERE raceId = :raceId")
    suspend fun countForRace(raceId: String): Int

    @Query(
        """
        SELECT 
            h.id AS participantId,
            h.raceId AS raceId,
            h.embedding AS embedding,
            h.embeddingFailed AS embeddingFailed,
            h.sourcePhoto AS sourcePhoto,
            h.faceThumbnailPath AS faceThumbnailPath,
            h.scannedPayload AS scannedPayload,
            h.registryInfo AS registryInfo,
            COALESCE(
                r.startedAtEpochMillis,
                (SELECT MIN(h2.createdAtEpochMillis) FROM race_participant_hashes h2 WHERE h2.raceId = r.id),
                r.createdAtEpochMillis
            ) AS raceStartedAtEpochMillis,
            h.createdAtEpochMillis AS createdAtEpochMillis,
            (
                SELECT MIN(fr.finishTimeEpochMillis)
                FROM finish_records fr
                WHERE fr.raceId = h.raceId AND fr.participantHashId = h.id
            ) AS finishTimeEpochMillis
        FROM race_participant_hashes h
        INNER JOIN races r ON r.id = h.raceId
        WHERE h.raceId = :raceId
        ORDER BY 
            CASE WHEN (
                SELECT MIN(fr2.finishTimeEpochMillis)
                FROM finish_records fr2
                WHERE fr2.raceId = h.raceId AND fr2.participantHashId = h.id
            ) IS NULL THEN 1 ELSE 0 END ASC,
            (
                SELECT MIN(fr3.finishTimeEpochMillis)
                FROM finish_records fr3
                WHERE fr3.raceId = h.raceId AND fr3.participantHashId = h.id
            ) DESC,
            h.id ASC
        """,
    )
    fun observeParticipantDashboard(raceId: String): Flow<List<ParticipantDashboardRow>>
}
