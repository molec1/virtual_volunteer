package com.virtualvolunteer.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ParticipantHashDao {

    @Insert
    suspend fun insert(hash: RaceParticipantHashEntity): Long

    @Query("SELECT * FROM race_participant_hashes WHERE raceId = :raceId")
    suspend fun listForRace(raceId: String): List<RaceParticipantHashEntity>

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
            h.createdAtEpochMillis AS createdAtEpochMillis,
            f.finishTimeEpochMillis AS finishTimeEpochMillis
        FROM race_participant_hashes h
        LEFT JOIN finish_records f 
            ON f.participantHashId = h.id AND f.raceId = h.raceId
        WHERE h.raceId = :raceId
        ORDER BY 
            CASE WHEN f.finishTimeEpochMillis IS NULL THEN 1 ELSE 0 END ASC,
            CASE WHEN f.finishTimeEpochMillis IS NULL THEN 0 ELSE f.finishTimeEpochMillis END DESC,
            h.id ASC
        """,
    )
    fun observeParticipantDashboard(raceId: String): Flow<List<ParticipantDashboardRow>>
}
