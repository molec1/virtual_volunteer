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

    /** Identities with a non-empty scanned code (standalone registry list). */
    @Query(
        """
        SELECT * FROM identity_registry
        WHERE scannedPayload IS NOT NULL AND LENGTH(TRIM(scannedPayload)) > 0
        ORDER BY createdAtEpochMillis DESC
        """,
    )
    fun observeWithScannedPayload(): Flow<List<IdentityRegistryEntity>>

    @Query("SELECT * FROM identity_registry")
    suspend fun listAll(): List<IdentityRegistryEntity>

    @Query("UPDATE identity_registry SET scannedPayload = :payload WHERE id = :id")
    suspend fun updateScannedPayload(id: Long, payload: String)

    @Query("UPDATE identity_registry SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String)

    @Query("SELECT * FROM identity_registry WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): IdentityRegistryEntity?

    @Query(
        """
        UPDATE identity_registry SET primaryThumbnailPhotoPath = :path 
        WHERE id = :id AND (primaryThumbnailPhotoPath IS NULL OR TRIM(primaryThumbnailPhotoPath) = '')
        """,
    )
    suspend fun updatePrimaryThumbnailIfMissing(id: Long, path: String): Int

    @Query("UPDATE identity_registry SET primaryThumbnailPhotoPath = :path WHERE id = :id")
    suspend fun updatePrimaryThumbnailPath(id: Long, path: String)
}
