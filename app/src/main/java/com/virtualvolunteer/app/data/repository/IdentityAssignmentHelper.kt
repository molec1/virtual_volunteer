package com.virtualvolunteer.app.data.repository

import com.virtualvolunteer.app.data.local.IdentityRegistryDao
import com.virtualvolunteer.app.data.local.IdentityRegistryEntity
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.identity.GlobalIdentityResolution
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import java.io.File

internal class IdentityAssignmentHelper(
    private val identityRegistryDao: IdentityRegistryDao,
    private val participantHashDao: ParticipantHashDao,
    private val participantEmbeddingDao: ParticipantEmbeddingDao,
) {
    suspend fun ensureIdentityRegistryThumbnailsFromLinkedParticipants(rows: List<IdentityRegistryEntity>) {
        for (row in rows) {
            val cur = row.primaryThumbnailPhotoPath?.trim().orEmpty()
            if (cur.isNotBlank() && File(cur).exists()) continue

            val hashes = participantHashDao.listHashesForIdentityRegistry(row.id).asReversed()
            val picked = hashes.firstNotNullOfOrNull { h ->
                sequenceOf(h.faceThumbnailPath, h.primaryThumbnailPhotoPath)
                    .firstOrNull { p ->
                        val t = p?.trim().orEmpty()
                        t.isNotBlank() && File(t).exists()
                    }?.trim()
            } ?: continue

            identityRegistryDao.updatePrimaryThumbnailPath(row.id, picked)
        }
    }

    suspend fun rankScannedOnDeviceIdentitiesByCosine(
        queryEmbeddings: List<FloatArray>,
        excludeParticipantIdForHistoricalPool: Long? = null,
    ): List<ScannedIdentityLookupRank> {
        val queries = queryEmbeddings.filter { it.isNotEmpty() }
        if (queries.isEmpty()) return emptyList()

        val byCode = identityRegistryDao.listAll()
            .mapNotNull { ir ->
                val code = ir.scannedPayload?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                code to ir
            }
            .groupBy({ it.first }, { it.second })

        val ranked = ArrayList<ScannedIdentityLookupRank>(byCode.size)
        for ((code, irs) in byCode) {
            val registryIds = irs.map { it.id }.toSet()
            val vectors = ArrayList<FloatArray>()
            for (ir in irs) {
                val rv = EmbeddingMath.parseCommaSeparated(ir.embedding)
                if (rv.isNotEmpty()) vectors.add(rv)
            }
            for (rid in registryIds) {
                for (h in participantHashDao.listHashesForIdentityRegistry(rid)) {
                    if (excludeParticipantIdForHistoricalPool != null && h.id == excludeParticipantIdForHistoricalPool) {
                        continue
                    }
                    val fromTable = participantEmbeddingDao.listEmbeddingStringsForParticipant(h.id)
                        .mapNotNull { s ->
                            EmbeddingMath.parseCommaSeparated(s).takeIf { v -> v.isNotEmpty() }
                        }
                    if (fromTable.isNotEmpty()) {
                        vectors.addAll(fromTable)
                    } else if (!h.embeddingFailed && h.embedding.isNotBlank()) {
                        val lv = EmbeddingMath.parseCommaSeparated(h.embedding)
                        if (lv.isNotEmpty()) vectors.add(lv)
                    }
                }
            }
            if (vectors.isEmpty()) continue

            val best = EmbeddingMath.maxCosineSimilarityAcrossPairs(queries, vectors) ?: continue

            val thumb = irs.firstNotNullOfOrNull { r ->
                r.primaryThumbnailPhotoPath?.trim()?.takeIf { t ->
                    t.isNotBlank() && File(t).exists()
                }
            }
            val notes = irs.firstNotNullOfOrNull { r ->
                r.notes?.trim()?.takeIf { it.isNotEmpty() }
            }
            ranked.add(
                ScannedIdentityLookupRank(
                    scanCodeTrimmed = code,
                    registryIds = registryIds,
                    maxCosineSimilarity = best,
                    registryThumbnailPath = thumb,
                    notes = notes,
                ),
            )
        }
        ranked.sortByDescending { it.maxCosineSimilarity }
        return ranked
    }

    suspend fun resolveGlobalIdentity(embedding: FloatArray): GlobalIdentityResolution {
        val rows = identityRegistryDao.listAll()
        var best: IdentityRegistryEntity? = null
        var bestSim = -1f
        for (row in rows) {
            val stored = EmbeddingMath.parseCommaSeparated(row.embedding)
            if (stored.isEmpty() || stored.size != embedding.size) continue
            val sim = EmbeddingMath.cosineSimilarity(embedding, stored)
            if (sim > bestSim) {
                bestSim = sim
                best = row
            }
        }
        val threshold = FaceMatchEngine.DEFAULT_MIN_COSINE
        if (best != null && bestSim >= threshold) {
            val info = listOfNotNull(best.notes, best.scannedPayload)
                .filter { !it.isNullOrBlank() }
                .joinToString(" · ")
                .takeIf { it.isNotBlank() }
            return GlobalIdentityResolution(
                registryId = best.id,
                registryInfo = info,
                matchedExisting = true,
            )
        }
        val embeddingStr = EmbeddingMath.formatCommaSeparated(embedding)
        val newId = identityRegistryDao.insert(
            IdentityRegistryEntity(
                embedding = embeddingStr,
                scannedPayload = null,
                notes = null,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        return GlobalIdentityResolution(
            registryId = newId,
            registryInfo = null,
            matchedExisting = false,
        )
    }
}
