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

    /** All protocol rows linked to the same global identity (across races). */
    @Query(
        """
        SELECT * FROM race_participant_hashes 
        WHERE identityRegistryId = :identityRegistryId 
        ORDER BY createdAtEpochMillis DESC
        """,
    )
    suspend fun listHashesForIdentityRegistry(identityRegistryId: Long): List<RaceParticipantHashEntity>

    /** Latest participant hash row for this registry (for navigation into participant detail). */
    @Query(
        """
        SELECT id FROM race_participant_hashes 
        WHERE identityRegistryId = :identityRegistryId 
        ORDER BY createdAtEpochMillis DESC LIMIT 1
        """,
    )
    suspend fun findLatestParticipantHashIdForRegistry(identityRegistryId: Long): Long?

    @Update
    suspend fun update(row: RaceParticipantHashEntity)

    @Query("DELETE FROM race_participant_hashes WHERE id = :id AND raceId = :raceId")
    suspend fun deleteById(id: Long, raceId: String): Int

    @Query(
        """
        UPDATE race_participant_hashes SET identityRegistryId = :keeperRegistryId
        WHERE identityRegistryId = :donorRegistryId
        """,
    )
    suspend fun reassignIdentityRegistryLinks(donorRegistryId: Long, keeperRegistryId: Long): Int

    @Query("SELECT COUNT(*) FROM race_participant_hashes WHERE raceId = :raceId")
    suspend fun countForRace(raceId: String): Int

    @Query(
        """
        SELECT id FROM race_participant_hashes
        WHERE raceId = :raceId
          AND LENGTH(TRIM(:payload)) > 0
          AND TRIM(IFNULL(scannedPayload, '')) = TRIM(:payload)
        """,
    )
    suspend fun listParticipantIdsWithScannedPayload(raceId: String, payload: String): List<Long>

    @Query(
        """
        SELECT 
            h.id AS participantId,
            h.raceId AS raceId,
            COALESCE(
                (SELECT pe.embedding FROM participant_embeddings pe 
                 WHERE pe.participantId = h.id ORDER BY pe.id ASC LIMIT 1),
                h.embedding
            ) AS embedding,
            CASE 
                WHEN EXISTS (
                    SELECT 1 FROM participant_embeddings pe2
                    WHERE pe2.participantId = h.id AND LENGTH(TRIM(pe2.embedding)) > 0
                ) THEN 0
                ELSE h.embeddingFailed
            END AS embeddingFailed,
            h.sourcePhoto AS sourcePhoto,
            h.faceThumbnailPath AS faceThumbnailPath,
            h.primaryThumbnailPhotoPath AS primaryThumbnailPhotoPath,
            COALESCE(NULLIF(TRIM(h.scannedPayload), ''), ir.scannedPayload) AS scannedPayload,
            h.registryInfo AS registryInfo,
            COALESCE(
                r.startedAtEpochMillis,
                (SELECT MIN(h2.createdAtEpochMillis) FROM race_participant_hashes h2 WHERE h2.raceId = r.id),
                r.createdAtEpochMillis
            ) AS raceStartedAtEpochMillis,
            h.createdAtEpochMillis AS createdAtEpochMillis,
            h.protocolFinishTimeEpochMillis AS finishTimeEpochMillis,
            h.displayName AS displayName
        FROM race_participant_hashes h
        INNER JOIN races r ON r.id = h.raceId
        LEFT JOIN identity_registry ir ON ir.id = h.identityRegistryId
        WHERE h.raceId = :raceId
        ORDER BY 
            CASE WHEN h.protocolFinishTimeEpochMillis IS NULL THEN 1 ELSE 0 END ASC,
            h.protocolFinishTimeEpochMillis ASC,
            h.id ASC
        """,
    )
    fun observeParticipantDashboardRows(raceId: String): Flow<List<ParticipantDashboardDbRow>>

    @Query(
        """
        SELECT 
            h.id AS participantId,
            h.raceId AS raceId,
            COALESCE(
                (SELECT pe.embedding FROM participant_embeddings pe 
                 WHERE pe.participantId = h.id ORDER BY pe.id ASC LIMIT 1),
                h.embedding
            ) AS embedding,
            CASE 
                WHEN EXISTS (
                    SELECT 1 FROM participant_embeddings pe2
                    WHERE pe2.participantId = h.id AND LENGTH(TRIM(pe2.embedding)) > 0
                ) THEN 0
                ELSE h.embeddingFailed
            END AS embeddingFailed,
            h.sourcePhoto AS sourcePhoto,
            h.faceThumbnailPath AS faceThumbnailPath,
            h.primaryThumbnailPhotoPath AS primaryThumbnailPhotoPath,
            COALESCE(NULLIF(TRIM(h.scannedPayload), ''), ir.scannedPayload) AS scannedPayload,
            h.registryInfo AS registryInfo,
            COALESCE(
                r.startedAtEpochMillis,
                (SELECT MIN(h2.createdAtEpochMillis) FROM race_participant_hashes h2 WHERE h2.raceId = r.id),
                r.createdAtEpochMillis
            ) AS raceStartedAtEpochMillis,
            h.createdAtEpochMillis AS createdAtEpochMillis,
            h.protocolFinishTimeEpochMillis AS finishTimeEpochMillis,
            h.displayName AS displayName
        FROM race_participant_hashes h
        INNER JOIN races r ON r.id = h.raceId
        LEFT JOIN identity_registry ir ON ir.id = h.identityRegistryId
        WHERE h.raceId = :raceId
        ORDER BY 
            CASE WHEN h.protocolFinishTimeEpochMillis IS NULL THEN 1 ELSE 0 END ASC,
            h.protocolFinishTimeEpochMillis ASC,
            h.id ASC
        """,
    )
    suspend fun getParticipantDashboardSnapshot(raceId: String): List<ParticipantDashboardDbRow>
}
