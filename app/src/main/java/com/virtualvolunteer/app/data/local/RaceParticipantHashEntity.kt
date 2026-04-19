package com.virtualvolunteer.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "race_participant_hashes",
    foreignKeys = [
        ForeignKey(
            entity = RaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["raceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("raceId")]
)
data class RaceParticipantHashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raceId: String,
    /** Comma-separated floats when embedding succeeded; empty when [embeddingFailed]. */
    val embedding: String,
    /** True when TFLite embedding failed; row exists for debugging (thumbnail still saved). */
    val embeddingFailed: Boolean = false,
    /** Absolute path of the source image file. */
    val sourcePhoto: String,
    /** Absolute path to cropped face JPEG under race faces/ folder; null if not saved. */
    val faceThumbnailPath: String? = null,
    val createdAtEpochMillis: Long,
)
