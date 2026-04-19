package com.virtualvolunteer.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityRegistryDao {

    @Insert
    suspend fun insert(row: IdentityRegistryEntity): Long

    @Query("SELECT * FROM identity_registry ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<IdentityRegistryEntity>>

    @Query("SELECT * FROM identity_registry")
    suspend fun listAll(): List<IdentityRegistryEntity>

    @Query("UPDATE identity_registry SET scannedPayload = :payload WHERE id = :id")
    suspend fun updateScannedPayload(id: Long, payload: String)

    @Query("UPDATE identity_registry SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String)

    @Query("SELECT * FROM identity_registry WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): IdentityRegistryEntity?
}
