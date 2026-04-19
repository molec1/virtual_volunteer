package com.virtualvolunteer.app.data.local

import androidx.room.TypeConverter
import com.virtualvolunteer.app.data.model.RaceStatus

class Converters {

    @TypeConverter
    fun raceStatusToString(status: RaceStatus): String = status.name

    @TypeConverter
    fun stringToRaceStatus(value: String): RaceStatus =
        try {
            RaceStatus.valueOf(value)
        } catch (_: IllegalArgumentException) {
            RaceStatus.CREATED
        }

    @TypeConverter
    fun embeddingSourceToString(v: EmbeddingSourceType): String = v.name

    @TypeConverter
    fun stringToEmbeddingSource(value: String): EmbeddingSourceType =
        try {
            EmbeddingSourceType.valueOf(value)
        } catch (_: IllegalArgumentException) {
            EmbeddingSourceType.START
        }
}
