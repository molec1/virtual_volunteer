package com.virtualvolunteer.app.data.repository

import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.data.local.IdentityRegistryDao
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingEntity
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import java.io.File

internal class RaceParticipantEmbeddingWriter(
    private val participantHashDao: ParticipantHashDao,
    private val participantEmbeddingDao: ParticipantEmbeddingDao,
    private val identityRegistryDao: IdentityRegistryDao,
) {
    suspend fun insertParticipantHash(
        row: RaceParticipantHashEntity,
        initialEmbeddingSource: EmbeddingSourceType,
        primaryThumbnailPhotoPath: String? = null,
    ): Long {
        val id = participantHashDao.insert(row.copy(primaryThumbnailPhotoPath = primaryThumbnailPhotoPath))
        if (!row.embeddingFailed && row.embedding.isNotBlank()) {
            participantEmbeddingDao.insert(
                ParticipantEmbeddingEntity(
                    participantId = id,
                    raceId = row.raceId,
                    embedding = row.embedding,
                    sourceType = initialEmbeddingSource,
                    sourcePhotoPath = row.sourcePhoto,
                    createdAtEpochMillis = row.createdAtEpochMillis,
                    qualityScore = null,
                ),
            )
        }
        syncParticipantPrimaryEmbeddingField(id)
        if (!primaryThumbnailPhotoPath.isNullOrBlank()) {
            row.identityRegistryId?.let { registryId ->
                identityRegistryDao.updatePrimaryThumbnailIfMissing(registryId, primaryThumbnailPhotoPath)
            }
        }
        return id
    }

    suspend fun updateParticipantPrimaryThumbnailIfMissing(participantId: Long, path: String?) {
        if (path.isNullOrBlank()) return
        val f = File(path)
        if (!f.exists()) return

        val p = participantHashDao.getById(participantId) ?: return
        if (p.primaryThumbnailPhotoPath.isNullOrBlank()) {
            participantHashDao.update(p.copy(primaryThumbnailPhotoPath = path))
        }
    }

    suspend fun appendParticipantEmbeddingIfNew(
        raceId: String,
        participantId: Long,
        embeddingCommaSeparated: String,
        sourceType: EmbeddingSourceType,
        sourcePhotoPath: String?,
        qualityScore: Float?,
        createdAtEpochMillis: Long,
    ): Boolean {
        if (embeddingCommaSeparated.isBlank()) return false
        val existing = participantEmbeddingDao.listEmbeddingStringsForParticipant(participantId)
        if (existing.any { it == embeddingCommaSeparated }) {
            return false
        }
        participantEmbeddingDao.insert(
            ParticipantEmbeddingEntity(
                participantId = participantId,
                raceId = raceId,
                embedding = embeddingCommaSeparated,
                sourceType = sourceType,
                sourcePhotoPath = sourcePhotoPath,
                createdAtEpochMillis = createdAtEpochMillis,
                qualityScore = qualityScore,
            ),
        )
        syncParticipantPrimaryEmbeddingField(participantId)
        return true
    }

    suspend fun syncParticipantPrimaryEmbeddingField(participantId: Long) {
        val p = participantHashDao.getById(participantId) ?: return
        val strings = participantEmbeddingDao.listEmbeddingStringsForParticipant(participantId)
        val primary = strings.firstOrNull().orEmpty()
        val failed = primary.isBlank()
        participantHashDao.update(
            p.copy(
                embedding = primary,
                embeddingFailed = failed,
            ),
        )
    }
}
