package com.virtualvolunteer.app.data.repository

import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.data.local.IdentityRegistryDao
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import java.io.File

/** One stored embedding row for participant-detail previews (may span linked protocol rows). */
data class ParticipantEmbeddingPreviewRow(
    val embeddingId: Long,
    val participantHashId: Long,
    val raceId: String,
    val sourceType: EmbeddingSourceType,
    val createdAtEpochMillis: Long,
    /** Best-effort preview path; file may be missing. */
    val previewPhotoPath: String?,
    val raceLabelShort: String,
)

internal class ParticipantFaceDataMutations(
    private val participantHashDao: ParticipantHashDao,
    private val participantEmbeddingDao: ParticipantEmbeddingDao,
    private val identityRegistryDao: IdentityRegistryDao,
    private val embeddingWriter: RaceParticipantEmbeddingWriter,
    private val protocolFinish: RaceProtocolFinishSync,
) {

    suspend fun listEmbeddingPreviews(seedParticipantId: Long): List<ParticipantEmbeddingPreviewRow> {
        val seed = participantHashDao.getById(seedParticipantId) ?: return emptyList()
        val hashRows = linkedParticipantRowsSuspend(seed)
        val out = ArrayList<ParticipantEmbeddingPreviewRow>()
        for (p in hashRows) {
            val raceShort = p.raceId.take(8)
            for (e in participantEmbeddingDao.listForParticipant(p.id)) {
                out.add(
                    ParticipantEmbeddingPreviewRow(
                        embeddingId = e.id,
                        participantHashId = p.id,
                        raceId = p.raceId,
                        sourceType = e.sourceType,
                        createdAtEpochMillis = e.createdAtEpochMillis,
                        previewPhotoPath = resolvePreviewPath(e.sourcePhotoPath, p),
                        raceLabelShort = raceShort,
                    ),
                )
            }
        }
        return out.sortedWith(compareBy({ it.createdAtEpochMillis }, { it.embeddingId }))
    }

    /**
     * Deletes all [participant_embeddings] for every protocol row linked to the same identity as [seedParticipantId]
     * (or only that row when there is no registry), clears legacy mirrored [RaceParticipantHashEntity.embedding],
     * clears the linked [IdentityRegistryEntity] face vector and registry thumbnail when present.
     * Scan codes, display names, and protocol finish rows are unchanged.
     */
    suspend fun removeAllEmbeddingsForLinkedParticipants(seedParticipantId: Long) {
        val seed = participantHashDao.getById(seedParticipantId) ?: return
        val hashRows = linkedParticipantRowsSuspend(seed)
        val raceIds = hashRows.map { it.raceId }.toSet()
        for (p in hashRows) {
            participantEmbeddingDao.deleteAllForParticipant(p.id)
            embeddingWriter.syncParticipantPrimaryEmbeddingField(p.id)
        }
        seed.identityRegistryId?.let { rid ->
            identityRegistryDao.getById(rid)?.let { reg ->
                identityRegistryDao.update(
                    reg.copy(
                        embedding = "",
                        primaryThumbnailPhotoPath = null,
                    ),
                )
            }
        }
        raceIds.forEach { protocolFinish.refreshProtocolXml(it) }
    }

    /**
     * Deletes the [IdentityRegistryEntity] row and sets [RaceParticipantHashEntity.identityRegistryId] to null
     * for all protocol rows that pointed at it. Does not delete protocol participants or finish detections.
     */
    suspend fun deleteRegistryUnlinkKeepProtocol(registryId: Long) {
        val raceIds = participantHashDao.listHashesForIdentityRegistry(registryId).map { it.raceId }.distinct()
        participantHashDao.clearIdentityRegistryLinks(registryId)
        identityRegistryDao.deleteById(registryId)
        raceIds.forEach { protocolFinish.refreshProtocolXml(it) }
    }

    private suspend fun linkedParticipantRowsSuspend(seed: RaceParticipantHashEntity): List<RaceParticipantHashEntity> {
        val registryId = seed.identityRegistryId
        return if (registryId != null) {
            participantHashDao.listHashesForIdentityRegistry(registryId)
        } else {
            listOf(seed)
        }
    }

    private fun resolvePreviewPath(sourcePhotoPath: String?, p: RaceParticipantHashEntity): String? {
        val candidates = listOfNotNull(
            sourcePhotoPath?.takeIf { fileExists(it) },
            p.primaryThumbnailPhotoPath?.takeIf { fileExists(it) },
            p.faceThumbnailPath?.takeIf { fileExists(it) },
        )
        return candidates.firstOrNull()
    }

    private fun fileExists(path: String): Boolean = path.isNotBlank() && File(path).exists()
}
