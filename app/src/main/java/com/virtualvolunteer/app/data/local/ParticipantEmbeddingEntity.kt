package com.virtualvolunteer.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "participant_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = RaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["raceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RaceParticipantHashEntity::class,
            parentColumns = ["id"],
            childColumns = ["participantId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("raceId"),
        Index("participantId"),
        Index(value = ["raceId", "participantId"]),
    ],
)
data class ParticipantEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: Long,
    val raceId: String,
    /** Comma-separated floats; same encoding as legacy participant embedding. */
    val embedding: String,
    val sourceType: EmbeddingSourceType,
    val sourcePhotoPath: String?,
    val createdAtEpochMillis: Long,
    /** Optional diagnostic (e.g. cosine vs best stored vector for finish matches). */
    val qualityScore: Float?,
)
