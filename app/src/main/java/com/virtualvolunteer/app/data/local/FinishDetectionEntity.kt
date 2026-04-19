package com.virtualvolunteer.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "finish_detections",
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
            childColumns = ["participantHashId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("raceId"),
        Index("participantHashId"),
    ],
)
data class FinishDetectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raceId: String,
    val participantHashId: Long,
    /** Finish instant for this crop (typically EXIF/session time for this photo). */
    val detectedAtEpochMillis: Long,
    val sourcePhotoPath: String,
    /** Cosine similarity for this match, if embedding match succeeded. */
    val matchCosineSimilarity: Float?,
)
