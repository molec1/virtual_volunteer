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
        FinishDetectionEntity::class,
        IdentityRegistryEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun raceDao(): RaceDao
    abstract fun participantHashDao(): ParticipantHashDao
    abstract fun finishDetectionDao(): FinishDetectionDao
    abstract fun identityRegistryDao(): IdentityRegistryDao

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `identity_registry` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `embedding` TEXT NOT NULL,
                        `scannedPayload` TEXT,
                        `notes` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE race_participant_hashes ADD COLUMN scannedPayload TEXT")
                db.execSQL("ALTER TABLE race_participant_hashes ADD COLUMN registryInfo TEXT")
                db.execSQL("ALTER TABLE race_participant_hashes ADD COLUMN identityRegistryId INTEGER")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE race_participant_hashes ADD COLUMN displayName TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE race_participant_hashes ADD COLUMN firstFinishSeenAtEpochMillis INTEGER")
                db.execSQL("ALTER TABLE race_participant_hashes ADD COLUMN protocolFinishTimeEpochMillis INTEGER")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `finish_detections` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `raceId` TEXT NOT NULL,
                        `participantHashId` INTEGER NOT NULL,
                        `detectedAtEpochMillis` INTEGER NOT NULL,
                        `sourcePhotoPath` TEXT NOT NULL,
                        `matchCosineSimilarity` REAL,
                        FOREIGN KEY(`raceId`) REFERENCES `races`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`participantHashId`) REFERENCES `race_participant_hashes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_finish_detections_raceId` ON `finish_detections` (`raceId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_finish_detections_participantHashId` ON `finish_detections` (`participantHashId`)",
                )

                db.execSQL(
                    """
                    INSERT INTO finish_detections (raceId, participantHashId, detectedAtEpochMillis, sourcePhotoPath, matchCosineSimilarity)
                    SELECT raceId, participantHashId, finishTimeEpochMillis, photoPath, NULL
                    FROM finish_records
                    WHERE participantHashId IS NOT NULL
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    UPDATE race_participant_hashes
                    SET firstFinishSeenAtEpochMillis = (
                        SELECT MIN(fd.detectedAtEpochMillis)
                        FROM finish_detections fd
                        WHERE fd.participantHashId = race_participant_hashes.id
                          AND fd.raceId = race_participant_hashes.raceId
                    ),
                    protocolFinishTimeEpochMillis = (
                        SELECT MAX(fd2.detectedAtEpochMillis)
                        FROM finish_detections fd2
                        WHERE fd2.participantHashId = race_participant_hashes.id
                          AND fd2.raceId = race_participant_hashes.raceId
                          AND fd2.detectedAtEpochMillis <= (
                            (
                                SELECT MIN(fd3.detectedAtEpochMillis)
                                FROM finish_detections fd3
                                WHERE fd3.participantHashId = race_participant_hashes.id
                                  AND fd3.raceId = race_participant_hashes.raceId
                            ) + 30000
                          )
                    )
                    WHERE id IN (
                        SELECT DISTINCT participantHashId FROM finish_detections WHERE participantHashId IS NOT NULL
                    )
                    """.trimIndent(),
                )

                db.execSQL("DROP TABLE finish_records")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .fallbackToDestructiveMigration()
                .build()
    }
}
