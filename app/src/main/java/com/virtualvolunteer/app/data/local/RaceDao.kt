package com.virtualvolunteer.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RaceDao {

    @Insert
    suspend fun insertRace(race: RaceEntity)

    @Update
    suspend fun updateRace(race: RaceEntity)

    @Query("SELECT * FROM races ORDER BY createdAtEpochMillis DESC")
    fun observeAllRaces(): Flow<List<RaceEntity>>

    @Query("SELECT * FROM races WHERE id = :raceId LIMIT 1")
    suspend fun getRace(raceId: String): RaceEntity?

    @Query("SELECT * FROM races WHERE id = :raceId LIMIT 1")
    fun observeRace(raceId: String): Flow<RaceEntity?>
}
