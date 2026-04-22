package com.virtualvolunteer.app.data.local

import android.content.Context
import android.util.Log
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
        ParticipantEmbeddingEntity::class,
        FinishDetectionEntity::class,
        IdentityRegistryEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun raceDao(): RaceDao
    abstract fun participantHashDao(): ParticipantHashDao
    abstract fun participantEmbeddingDao(): ParticipantEmbeddingDao
    abstract fun finishDetectionDao(): FinishDetectionDao
    abstract fun identityRegistryDao(): IdentityRegistryDao

    companion object {
        private const val DB_NAME = "virtual_volunteer.db"

        private const val TAG = "AppDatabase"

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

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `participant_embeddings` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `participantId` INTEGER NOT NULL,
                        `raceId` TEXT NOT NULL,
                        `embedding` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `sourcePhotoPath` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `qualityScore` REAL,
                        FOREIGN KEY(`raceId`) REFERENCES `races`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`participantId`) REFERENCES `race_participant_hashes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_participant_embeddings_raceId` ON `participant_embeddings` (`raceId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_participant_embeddings_participantId` ON `participant_embeddings` (`participantId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_participant_embeddings_raceId_participantId` ON `participant_embeddings` (`raceId`, `participantId`)",
                )

                db.execSQL(
                    """
                    INSERT INTO `participant_embeddings` (
                        participantId, raceId, embedding, sourceType, sourcePhotoPath, createdAtEpochMillis, qualityScore
                    )
                    SELECT
                        id,
                        raceId,
                        embedding,
                        CASE
                            WHEN sourcePhoto LIKE '%finish_photos%' THEN 'FINISH_AUTO'
                            ELSE 'START'
                        END,
                        sourcePhoto,
                        createdAtEpochMillis,
                        NULL
                    FROM race_participant_hashes
                    WHERE embeddingFailed = 0
                      AND embedding IS NOT NULL
                      AND LENGTH(TRIM(embedding)) > 0
                    """.trimIndent(),
                )

                val cursor = db.query("SELECT COUNT(*) FROM participant_embeddings")
                cursor.moveToFirst()
                val n = cursor.getLong(0)
                cursor.close()
                Log.i(TAG, "MIGRATION_7_8 migrated participant_embeddings rows=$n")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE race_participant_hashes ADD COLUMN primaryThumbnailPhotoPath TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE identity_registry ADD COLUMN primaryThumbnailPhotoPath TEXT")
            }
        }

        /**
         * Drops [RaceEntity] latitude / longitude; adds [RaceEntity.listThumbnailPath] for the main-menu preview cache.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `races_new` (
                        `id` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `startedAtEpochMillis` INTEGER,
                        `finishedAtEpochMillis` INTEGER,
                        `status` TEXT NOT NULL,
                        `folderPath` TEXT NOT NULL,
                        `lastPhotoPath` TEXT,
                        `listThumbnailPath` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO races_new (id, createdAtEpochMillis, startedAtEpochMillis, finishedAtEpochMillis, status, folderPath, lastPhotoPath, listThumbnailPath)
                    SELECT id, createdAtEpochMillis, startedAtEpochMillis, finishedAtEpochMillis, status, folderPath, lastPhotoPath, NULL
                    FROM races
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE races")
                db.execSQL("ALTER TABLE races_new RENAME TO races")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_races_createdAtEpochMillis` ON `races`(`createdAtEpochMillis`)")
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
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                )
                .fallbackToDestructiveMigration()
                .build()
    }
}
