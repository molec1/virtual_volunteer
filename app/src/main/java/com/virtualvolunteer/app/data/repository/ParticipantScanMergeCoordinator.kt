package com.virtualvolunteer.app.data.repository

import android.util.Log
import com.virtualvolunteer.app.data.local.FinishDetectionDao
import com.virtualvolunteer.app.data.local.IdentityRegistryDao
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.RaceDao
import java.io.File

internal class ParticipantScanMergeCoordinator(
    private val raceDao: RaceDao,
    private val participantHashDao: ParticipantHashDao,
    private val finishDetectionDao: FinishDetectionDao,
    private val participantEmbeddingDao: ParticipantEmbeddingDao,
    private val identityRegistryDao: IdentityRegistryDao,
    private val logTag: String,
    private val syncParticipantPrimaryEmbedding: suspend (Long) -> Unit,
    private val recomputeProtocolFinish: suspend (raceId: String, participantHashId: Long) -> Unit,
    private val refreshProtocolXml: suspend (raceId: String) -> Unit,
) {
    suspend fun consolidateScanMergesForRace(raceId: String) {
        consolidateDuplicateIdentityRegistryRows()
        consolidateParticipantsSharingEffectiveScanCode(raceId)
    }

    suspend fun consolidateAllScanMerges() {
        consolidateDuplicateIdentityRegistryRows()
        for (rid in raceDao.listAllRaceIds()) {
            consolidateParticipantsSharingEffectiveScanCode(rid)
        }
    }

    suspend fun mergeParticipantsSharingScannedPayload(raceId: String, keeperId: Long, payload: String) {
        if (payload.isBlank()) return
        val ids = participantHashDao.listParticipantIdsWithScannedPayload(raceId, payload)
        val donors = ids.filter { it != keeperId }.distinct()
        if (donors.isEmpty()) return
        Log.i(logTag, "mergeParticipantsSharingScannedPayload keeper=$keeperId donors=$donors payloadLen=${payload.length}")
        for (donorId in donors) {
            mergeParticipantIntoKeeper(raceId, keeperId = keeperId, donorId = donorId)
        }
    }

    suspend fun mergeParticipantIntoKeeper(raceId: String, keeperId: Long, donorId: Long) {
        if (donorId == keeperId) return
        val keeper = participantHashDao.getById(keeperId) ?: return
        val donor = participantHashDao.getById(donorId) ?: return
        require(keeper.raceId == raceId && donor.raceId == raceId)

        finishDetectionDao.reassignParticipant(raceId, donorId, keeperId)
        participantEmbeddingDao.reassignParticipant(raceId, donorId, keeperId)

        val mergedScan = keeper.scannedPayload?.takeIf { it.isNotBlank() } ?: donor.scannedPayload
        val mergedName = keeper.displayName?.takeIf { it.isNotBlank() } ?: donor.displayName
        val mergedRegistryInfo = keeper.registryInfo ?: donor.registryInfo
        val mergedIr = keeper.identityRegistryId ?: donor.identityRegistryId

        participantHashDao.update(
            keeper.copy(
                scannedPayload = mergedScan,
                displayName = mergedName,
                registryInfo = mergedRegistryInfo,
                identityRegistryId = mergedIr,
            ),
        )

        participantHashDao.deleteById(donorId, raceId)

        syncParticipantPrimaryEmbedding(keeperId)
        recomputeProtocolFinish(raceId, keeperId)
        refreshProtocolXml(raceId)
        Log.i(logTag, "mergeParticipantIntoKeeper done keeper=$keeperId mergedDonor=$donorId")
    }

    private suspend fun consolidateDuplicateIdentityRegistryRows() {
        val all = identityRegistryDao.listAll()
        val byTrimmed = all
            .mapNotNull { ir ->
                val code = ir.scannedPayload?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                code to ir
            }
            .groupBy({ it.first }, { it.second })
        for ((_, list) in byTrimmed) {
            if (list.size <= 1) continue
            val keeper = list.minBy { it.id }
            for (donor in list.filter { it.id != keeper.id }.sortedBy { it.id }) {
                mergeIdentityRegistryDonorIntoKeeper(keeperId = keeper.id, donorId = donor.id)
            }
        }
    }

    private suspend fun mergeIdentityRegistryDonorIntoKeeper(keeperId: Long, donorId: Long) {
        if (donorId == keeperId) return
        val keeper = identityRegistryDao.getById(keeperId) ?: return
        val donor = identityRegistryDao.getById(donorId) ?: return

        participantHashDao.reassignIdentityRegistryLinks(donorId, keeperId)

        val mergedNotes = listOf(keeper.notes, donor.notes)
            .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .distinct()
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }

        val mergedThumbnail = keeper.primaryThumbnailPhotoPath?.trim()?.takeIf { t ->
            t.isNotEmpty() && File(t).exists()
        } ?: donor.primaryThumbnailPhotoPath?.trim()?.takeIf { t ->
            t.isNotEmpty() && File(t).exists()
        }

        val mergedEmbedding = keeper.embedding.trim().takeIf { it.isNotEmpty() } ?: donor.embedding
        val mergedScan = keeper.scannedPayload?.trim()?.takeIf { it.isNotEmpty() }
            ?: donor.scannedPayload?.trim()?.takeIf { it.isNotEmpty() }

        identityRegistryDao.update(
            keeper.copy(
                notes = mergedNotes,
                primaryThumbnailPhotoPath = mergedThumbnail,
                embedding = mergedEmbedding,
                scannedPayload = mergedScan,
            ),
        )
        identityRegistryDao.deleteById(donorId)
        Log.i(logTag, "mergeIdentityRegistryDonorIntoKeeper keeper=$keeperId mergedDonor=$donorId")
    }

    private suspend fun consolidateParticipantsSharingEffectiveScanCode(raceId: String) {
        val rows = participantHashDao.getParticipantDashboardSnapshot(raceId)
        val byCode = rows
            .mapNotNull { r ->
                r.scannedPayload?.trim()?.takeIf { it.isNotEmpty() }?.let { code -> code to r }
            }
            .groupBy({ it.first }, { it.second })
        for ((_, group) in byCode) {
            if (group.size <= 1) continue
            val keeperId = group.minOf { it.participantId }
            val donors = group.map { it.participantId }.filter { it != keeperId }.sorted()
            for (donorId in donors) {
                mergeParticipantIntoKeeper(raceId, keeperId = keeperId, donorId = donorId)
            }
        }
    }
}
