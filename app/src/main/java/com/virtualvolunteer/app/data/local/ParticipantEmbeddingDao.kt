package com.virtualvolunteer.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ParticipantEmbeddingDao {

    @Insert
    suspend fun insert(row: ParticipantEmbeddingEntity): Long

    @Query(
        """
        SELECT * FROM participant_embeddings 
        WHERE participantId = :participantId 
        ORDER BY id ASC
        """,
    )
    suspend fun listForParticipant(participantId: Long): List<ParticipantEmbeddingEntity>

    @Query("SELECT * FROM participant_embeddings WHERE raceId = :raceId")
    suspend fun listForRace(raceId: String): List<ParticipantEmbeddingEntity>

    @Query(
        """
        UPDATE participant_embeddings 
        SET participantId = :newParticipantId 
        WHERE raceId = :raceId AND participantId = :oldParticipantId
        """,
    )
    suspend fun reassignParticipant(raceId: String, oldParticipantId: Long, newParticipantId: Long): Int

    @Query(
        """
        SELECT embedding FROM participant_embeddings 
        WHERE participantId = :participantId AND embedding != ''
        """,
    )
    suspend fun listEmbeddingStringsForParticipant(participantId: Long): List<String>

    @Query("UPDATE participant_embeddings SET sourcePhotoPath = NULL WHERE id = :id")
    suspend fun clearSourcePhotoPathByEmbeddingId(id: Long): Int
}
