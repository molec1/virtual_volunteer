package com.virtualvolunteer.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RaceEntity::class,
        RaceParticipantHashEntity::class,
        FinishRecordEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun raceDao(): RaceDao
    abstract fun participantHashDao(): ParticipantHashDao
    abstract fun finishRecordDao(): FinishRecordDao

    companion object {
        private const val DB_NAME = "virtual_volunteer.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE races ADD COLUMN lastPhotoPath TEXT")
                db.execSQL("ALTER TABLE race_participant_hashes ADD COLUMN faceThumbnailPath TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE race_participant_hashes ADD COLUMN embeddingFailed INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
    }
}
