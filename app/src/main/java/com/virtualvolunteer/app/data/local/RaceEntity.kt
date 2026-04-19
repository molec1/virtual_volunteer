package com.virtualvolunteer.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.virtualvolunteer.app.data.model.RaceStatus

@Entity(
    tableName = "races",
    indices = [Index(value = ["createdAtEpochMillis"])]
)
data class RaceEntity(
    @PrimaryKey val id: String,
    val createdAtEpochMillis: Long,
    val startedAtEpochMillis: Long?,
    val finishedAtEpochMillis: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val status: RaceStatus,
    /** Absolute path of the race folder under app files dir. */
    val folderPath: String,
    /** Latest processed photo (start or finish) for dashboard preview; null if none. */
    val lastPhotoPath: String? = null,
)
