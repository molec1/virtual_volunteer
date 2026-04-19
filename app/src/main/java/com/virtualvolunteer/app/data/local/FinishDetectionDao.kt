package com.virtualvolunteer.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FinishDetectionDao {

    @Insert
    suspend fun insert(row: FinishDetectionEntity): Long

    @Query(
        """
        SELECT * FROM finish_detections 
        WHERE raceId = :raceId AND participantHashId = :participantHashId 
        ORDER BY detectedAtEpochMillis ASC, id ASC
        """,
    )
    suspend fun listForParticipantSorted(
        raceId: String,
        participantHashId: Long,
    ): List<FinishDetectionEntity>

    @Query(
        """
        DELETE FROM finish_detections 
        WHERE raceId = :raceId AND participantHashId = :participantHashId
        """,
    )
    suspend fun deleteForParticipant(raceId: String, participantHashId: Long): Int

    @Query("SELECT COUNT(*) FROM finish_detections WHERE raceId = :raceId")
    suspend fun countForRace(raceId: String): Int

    @Query("SELECT COUNT(*) FROM finish_detections WHERE raceId = :raceId")
    fun observeCountForRace(raceId: String): Flow<Int>
}
