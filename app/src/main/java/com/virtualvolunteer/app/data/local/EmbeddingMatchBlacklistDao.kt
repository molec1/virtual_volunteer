package com.virtualvolunteer.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmbeddingMatchBlacklistDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: EmbeddingMatchBlacklistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<EmbeddingMatchBlacklistEntity>): List<Long>

    @Query("SELECT aHash, bHash FROM embedding_match_blacklist")
    suspend fun listAllPairs(): List<EmbeddingMatchBlacklistPairRow>
}

/** Lightweight row for loading pairs into memory. */
data class EmbeddingMatchBlacklistPairRow(
    val aHash: String,
    val bHash: String,
)

