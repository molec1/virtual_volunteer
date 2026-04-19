package com.virtualvolunteer.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FinishRecordDao {

    @Insert
    suspend fun insert(record: FinishRecordEntity): Long

    @Query("SELECT * FROM finish_records WHERE raceId = :raceId ORDER BY finishTimeEpochMillis ASC")
    suspend fun listForRace(raceId: String): List<FinishRecordEntity>

    @Query("SELECT participantHashId FROM finish_records WHERE raceId = :raceId AND participantHashId IS NOT NULL")
    suspend fun usedParticipantHashIds(raceId: String): List<Long>
}
