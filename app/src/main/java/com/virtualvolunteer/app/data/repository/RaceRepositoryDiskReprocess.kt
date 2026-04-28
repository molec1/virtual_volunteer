package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.FaceCropManifestDisk
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.IdentityRegistryDao
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.matching.ParticipantEmbeddingSet

/**
 * Captures face vectors + operator/registry metadata before a full disk reprocess so it can be
 * reattached to the best-matching new participant rows (same cosine gate as finish matching).
 */
data class RaceReprocessIdentityHint(
    val queryEmbeddings: List<FloatArray>,
    val scannedPayloadTrimmed: String?,
    val identityRegistryId: Long?,
    val displayNameTrimmed: String?,
)

internal object RaceRepositoryDiskReprocess {

    suspend fun buildHints(
        participantHashDao: ParticipantHashDao,
        participantEmbeddingDao: ParticipantEmbeddingDao,
        raceId: String,
    ): List<RaceReprocessIdentityHint> {
        val participants = participantHashDao.listForRace(raceId)
        return participants.mapNotNull { p ->
            val rows = participantEmbeddingDao.listForParticipant(p.id)
            val fromTable = rows.mapNotNull { row ->
                EmbeddingMath.parseCommaSeparated(row.embedding).takeIf { it.isNotEmpty() }
            }
            val legacy = if (fromTable.isEmpty() && p.embedding.isNotBlank()) {
                listOf(EmbeddingMath.parseCommaSeparated(p.embedding)).filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            val queries = fromTable + legacy
            if (queries.isEmpty()) return@mapNotNull null
            val scan = p.scannedPayload?.trim()?.takeIf { it.isNotEmpty() }
            val name = p.displayName?.trim()?.takeIf { it.isNotEmpty() }
            if (scan == null && name == null && p.identityRegistryId == null) {
                return@mapNotNull null
            }
            RaceReprocessIdentityHint(
                queryEmbeddings = queries,
                scannedPayloadTrimmed = scan,
                identityRegistryId = p.identityRegistryId,
                displayNameTrimmed = name,
            )
        }
    }

    suspend fun wipeParticipantRowsAndAuxFaceDirs(
        appContext: Context,
        raceId: String,
        participantHashDao: ParticipantHashDao,
        repo: RaceRepository,
    ) {
        participantHashDao.deleteAllForRace(raceId)
        clearRaceAuxFaceDirs(appContext, raceId)
        repo.updateLastProcessedPhoto(raceId, null)
    }

    private fun clearRaceAuxFaceDirs(context: Context, raceId: String) {
        FaceCropManifestDisk.deleteManifest(context, raceId)
        for (dir in listOf(RacePaths.facesDir(context, raceId), RacePaths.debugDir(context, raceId))) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        }
    }

    private fun bestCosineHintAgainstSet(
        hintVectors: List<FloatArray>,
        set: ParticipantEmbeddingSet,
    ): Float? {
        val storages = set.embeddingStrings.mapNotNull { str ->
            EmbeddingMath.parseCommaSeparated(str).takeIf { it.isNotEmpty() }
        }
        return EmbeddingMath.maxCosineSimilarityAcrossPairs(hintVectors, storages)
    }

    suspend fun applyIdentityHintsGreedy(
        raceId: String,
        hints: List<RaceReprocessIdentityHint>,
        minCosine: Float,
        repo: RaceRepository,
        identityRegistryDao: IdentityRegistryDao,
    ): Int {
        if (hints.isEmpty()) return 0
        val sorted = hints.sortedWith(
            compareByDescending<RaceReprocessIdentityHint> {
                (if (it.identityRegistryId != null) 2 else 0) +
                    (if (!it.scannedPayloadTrimmed.isNullOrBlank()) 1 else 0)
            }.thenByDescending { it.queryEmbeddings.size },
        )
        val assigned = mutableSetOf<Long>()
        var restored = 0
        for (hint in sorted) {
            val pool = repo.listParticipantEmbeddingSets(raceId)
                .filter { it.participant.id !in assigned && it.hasEmbeddings }
            var bestId: Long? = null
            var bestCos = -1f
            for (set in pool) {
                val c = bestCosineHintAgainstSet(hint.queryEmbeddings, set) ?: continue
                if (c > bestCos) {
                    bestCos = c
                    bestId = set.participant.id
                }
            }
            if (bestId == null || bestCos < minCosine) continue
            assigned.add(bestId)
            restoreMetadata(repo, identityRegistryDao, raceId, bestId, hint)
            restored++
        }
        return restored
    }

    private suspend fun restoreMetadata(
        repo: RaceRepository,
        identityRegistryDao: IdentityRegistryDao,
        raceId: String,
        participantId: Long,
        hint: RaceReprocessIdentityHint,
    ) {
        val regId = hint.identityRegistryId
        if (regId != null) {
            val ir = identityRegistryDao.getById(regId)
            if (ir != null) {
                val code = hint.scannedPayloadTrimmed?.takeIf { it.isNotEmpty() }
                    ?: ir.scannedPayload?.trim()?.takeIf { it.isNotEmpty() }
                if (!code.isNullOrEmpty()) {
                    repo.assignParticipantToScannedIdentityFromFaceLookup(
                        raceId,
                        participantId,
                        code,
                        registryIds = setOf(regId),
                    )
                }
            } else if (!hint.scannedPayloadTrimmed.isNullOrBlank()) {
                repo.updateParticipantScan(raceId, participantId, hint.scannedPayloadTrimmed!!)
            }
        } else if (!hint.scannedPayloadTrimmed.isNullOrBlank()) {
            repo.updateParticipantScan(raceId, participantId, hint.scannedPayloadTrimmed!!)
        }
        if (!hint.displayNameTrimmed.isNullOrBlank()) {
            repo.updateParticipantDisplayName(raceId, participantId, hint.displayNameTrimmed)
        }
    }
}
