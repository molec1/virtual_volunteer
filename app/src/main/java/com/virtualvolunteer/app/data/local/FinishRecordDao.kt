package com.virtualvolunteer.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FinishRecordDao {

    @Insert
    suspend fun insert(record: FinishRecordEntity): Long

    @Query("SELECT * FROM finish_records WHERE raceId = :raceId ORDER BY finishTimeEpochMillis ASC")
    suspend fun listForRace(raceId: String): List<FinishRecordEntity>

    @Query("SELECT participantHashId FROM finish_records WHERE raceId = :raceId AND participantHashId IS NOT NULL")
    suspend fun usedParticipantHashIds(raceId: String): List<Long>

    @Query("DELETE FROM finish_records WHERE raceId = :raceId AND participantHashId = :participantHashId")
    suspend fun deleteForParticipant(raceId: String, participantHashId: Long): Int

    @Query("SELECT COUNT(*) FROM finish_records WHERE raceId = :raceId")
    suspend fun countForRace(raceId: String): Int

    @Query("SELECT COUNT(*) FROM finish_records WHERE raceId = :raceId")
    fun observeCountForRace(raceId: String): Flow<Int>
}
