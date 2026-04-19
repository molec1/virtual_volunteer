package com.virtualvolunteer.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "finish_records",
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
data class FinishRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raceId: String,
    /** Links to [RaceParticipantHashEntity.id] when matching used the race pool row. */
    val participantHashId: Long?,
    /** Snapshot of matched embedding (comma-separated floats) for protocol export. */
    val embedding: String,
    val finishTimeEpochMillis: Long,
    val photoPath: String,
)
