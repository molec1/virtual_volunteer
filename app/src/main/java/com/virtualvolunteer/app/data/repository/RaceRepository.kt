package com.virtualvolunteer.app.data.repository

import android.content.Context
import android.util.Log
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.data.local.FinishDetectionDao
import com.virtualvolunteer.app.data.local.FinishDetectionEntity
import com.virtualvolunteer.app.data.local.IdentityRegistryDao
import com.virtualvolunteer.app.data.local.IdentityRegistryEntity
import com.virtualvolunteer.app.data.local.ParticipantDashboardDbRow
import com.virtualvolunteer.app.data.local.ParticipantDashboardRow
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingEntity
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.RaceDao
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.data.model.RaceStatus
import com.virtualvolunteer.app.data.xml.ProtocolFinishRow
import com.virtualvolunteer.app.data.xml.ProtocolXmlIo
import com.virtualvolunteer.app.data.xml.RaceXmlIo
import com.virtualvolunteer.app.data.xml.RaceXmlSnapshot
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.identity.GlobalIdentityResolution
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.matching.ParticipantEmbeddingSet
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import com.virtualvolunteer.app.export.RaceCsvExport
import com.virtualvolunteer.app.location.LocationCapture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/** One image file for a participant in a race; [isFinishFrame] when used as a finish detection source. */
data class ParticipantRacePhoto(
    val absolutePath: String,
    val isFinishFrame: Boolean,
)

/** Outcome after persisting one finish-photo face match (detection row + protocol aggregation). */
data class RecordFinishDetectionOutcome(
    val officialProtocolFinishMillis: Long?,
    /** True when this detection’s instant is after the first 30s finish series window (stored but does not move protocol time). */
    val detectionIgnoredForProtocolSeries: Boolean,
    /** True when [RaceParticipantHashEntity.protocolFinishTimeEpochMillis] changed compared to before this detection. */
    val protocolFinishTimeUpdated: Boolean,
)

/**
 * Local races repository: Room metadata + mirrored XML + folder layout.
 */
class RaceRepository(
    private val appContext: Context,
    private val raceDao: RaceDao,
    private val participantHashDao: ParticipantHashDao,
    private val participantEmbeddingDao: ParticipantEmbeddingDao,
    private val finishDetectionDao: FinishDetectionDao,
    private val identityRegistryDao: IdentityRegistryDao,
) {

    companion object {
        /** Width of the “first finish series” window starting at the earliest detection instant. */
        const val FIRST_FINISH_SERIES_WINDOW_MS: Long = 30_000L

        private const val TAG = "RaceRepository"
    }

    fun observeAllRaces(): Flow<List<RaceEntity>> = raceDao.observeAllRaces()

    fun observeRace(raceId: String): Flow<RaceEntity?> = raceDao.observeRace(raceId)

    fun observeParticipantDashboard(raceId: String): Flow<List<ParticipantDashboardRow>> =
        participantHashDao.observeParticipantDashboardRows(raceId).map { assignFinishRanks(it) }

    suspend fun getParticipantDashboardUi(raceId: String): List<ParticipantDashboardRow> =
        assignFinishRanks(participantHashDao.getParticipantDashboardSnapshot(raceId))

    fun observeFinishRecordCount(raceId: String): Flow<Int> =
        finishDetectionDao.observeCountForRace(raceId)

    /** Device-local identities (face registry rows), newest first. */
    fun observeIdentityRegistry(): Flow<List<IdentityRegistryEntity>> =
        identityRegistryDao.observeAll()

    suspend fun getRace(raceId: String): RaceEntity? = raceDao.getRace(raceId)

    suspend fun getParticipantHashById(id: Long): RaceParticipantHashEntity? =
        participantHashDao.getById(id)

    suspend fun updateLastProcessedPhoto(raceId: String, absolutePath: String?) {
        val race = raceDao.getRace(raceId) ?: return
        raceDao.updateRace(race.copy(lastPhotoPath = absolutePath))
    }

    suspend fun applyOfflineRaceStartFromStartPhotos(raceId: String) {
        val dir = RacePaths.startPhotosDir(appContext, raceId)
        val maxExif = PhotoTimestampResolver.maxEpochAmongImages(dir) ?: return
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(startedAtEpochMillis = maxExif)
        raceDao.updateRace(updated)
        writeRaceXml(updated)
    }

    suspend fun createNewRace(): String {
        val id = UUID.randomUUID().toString()
        val folder = RacePaths.ensureRaceLayout(appContext, id)
        val now = System.currentTimeMillis()

        val location = LocationCapture.tryGetCurrentLocation(appContext)

        val entity = RaceEntity(
            id = id,
            createdAtEpochMillis = now,
            startedAtEpochMillis = null,
            finishedAtEpochMillis = null,
            latitude = location?.latitude,
            longitude = location?.longitude,
            status = RaceStatus.CREATED,
            folderPath = folder.absolutePath,
            lastPhotoPath = null,
        )
        raceDao.insertRace(entity)
        writeRaceXml(entity)
        ProtocolXmlIo.write(RacePaths.protocolXml(appContext, id), id, emptyList())
        return id
    }

    suspend fun markStarted(raceId: String, startedAtEpochMillis: Long) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(
            status = RaceStatus.STARTED,
            startedAtEpochMillis = startedAtEpochMillis,
        )
        raceDao.updateRace(updated)
        writeRaceXml(updated)
    }

    suspend fun markRecording(raceId: String) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(status = RaceStatus.RECORDING)
        raceDao.updateRace(updated)
        writeRaceXml(updated)
    }

    suspend fun markFinished(raceId: String, finishedAtEpochMillis: Long) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(
            status = RaceStatus.FINISHED,
            finishedAtEpochMillis = finishedAtEpochMillis,
        )
        raceDao.updateRace(updated)
        writeRaceXml(updated)
    }

    suspend fun markExported(raceId: String) {
        val race = raceDao.getRace(raceId) ?: return
        val updated = race.copy(status = RaceStatus.EXPORTED)
        raceDao.updateRace(updated)
        writeRaceXml(updated)
    }

    /**
     * Inserts a participant row and, when [RaceParticipantHashEntity.embeddingFailed] is false,
     * records the first vector in [participant_embeddings] (see [initialEmbeddingSource]).
     */
    suspend fun insertParticipantHash(
        row: RaceParticipantHashEntity,
        initialEmbeddingSource: EmbeddingSourceType,
    ): Long {
        val id = participantHashDao.insert(row)
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
        return id
    }

    /**
     * Build per-participant embedding sets for cosine matching (multi-vector).
     * Falls back to legacy [RaceParticipantHashEntity.embedding] when the new table is empty.
     */
    suspend fun listParticipantEmbeddingSets(raceId: String): List<ParticipantEmbeddingSet> {
        val hashes = participantHashDao.listForRace(raceId)
        val byPid = participantEmbeddingDao.listForRace(raceId).groupBy { it.participantId }
        return hashes.map { h ->
            val fromTable = byPid[h.id].orEmpty().map { it.embedding }.filter { it.isNotBlank() }
            val strings = if (fromTable.isNotEmpty()) {
                fromTable
            } else if (!h.embeddingFailed && h.embedding.isNotBlank()) {
                listOf(h.embedding)
            } else {
                emptyList()
            }
            ParticipantEmbeddingSet(participant = h, embeddingStrings = strings)
        }
    }

    /**
     * Appends a finish (or merge) embedding if it is not already stored exactly for this participant.
     * @return true if a new row was inserted.
     */
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

    private suspend fun syncParticipantPrimaryEmbeddingField(participantId: Long) {
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

    suspend fun updateParticipantScan(raceId: String, participantId: Long, scannedPayload: String) {
        val trimmed = scannedPayload.trim()
        val row = participantHashDao.getById(participantId) ?: return
        require(row.raceId == raceId)
        participantHashDao.update(row.copy(scannedPayload = trimmed))
        row.identityRegistryId?.let { rid ->
            identityRegistryDao.updateScannedPayload(rid, trimmed)
        }
        mergeParticipantsSharingScannedPayload(raceId, keeperId = participantId, payload = trimmed)
    }

    /**
     * When two protocol rows carry the same scanned code, merge extras into [keeperId]
     * (finish detections + embeddings), preserving one official participant.
     */
    private suspend fun mergeParticipantsSharingScannedPayload(raceId: String, keeperId: Long, payload: String) {
        if (payload.isBlank()) return
        val ids = participantHashDao.listParticipantIdsWithScannedPayload(raceId, payload)
        val donors = ids.filter { it != keeperId }.distinct()
        if (donors.isEmpty()) return
        Log.i(TAG, "mergeParticipantsSharingScannedPayload keeper=$keeperId donors=$donors payloadLen=${payload.length}")
        for (donorId in donors) {
            mergeParticipantIntoKeeper(raceId, keeperId = keeperId, donorId = donorId)
        }
    }

    private suspend fun mergeParticipantIntoKeeper(raceId: String, keeperId: Long, donorId: Long) {
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

        syncParticipantPrimaryEmbeddingField(keeperId)
        recomputeProtocolFinishForParticipant(raceId, keeperId)
        refreshProtocolXml(raceId)
        Log.i(TAG, "mergeParticipantIntoKeeper done keeper=$keeperId mergedDonor=$donorId")
    }

    suspend fun updateParticipantDisplayName(raceId: String, participantId: Long, name: String?) {
        val row = participantHashDao.getById(participantId) ?: return
        require(row.raceId == raceId)
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        participantHashDao.update(row.copy(displayName = trimmed))
        refreshProtocolXml(raceId)
    }

    suspend fun exportTimesCsv(raceId: String): Result<File> = runCatching {
        val race = getRace(raceId) ?: error("Race not found")
        val rows = participantHashDao.getParticipantDashboardSnapshot(raceId)
        RaceCsvExport.writeTimesCsv(appContext, race, rows)
    }

    suspend fun exportParticipantsCsv(raceId: String): Result<File> = runCatching {
        val race = getRace(raceId) ?: error("Race not found")
        val rows = participantHashDao.getParticipantDashboardSnapshot(raceId)
        RaceCsvExport.writeParticipantsCsv(appContext, race, rows)
    }

    /**
     * Stores a matched finish detection and recomputes official protocol finish time for that participant.
     */
    suspend fun recordFinishDetection(
        raceId: String,
        participantHashId: Long,
        detectedAtEpochMillis: Long,
        sourcePhotoPath: String,
        matchCosineSimilarity: Float?,
    ): RecordFinishDetectionOutcome {
        val before = participantHashDao.getById(participantHashId)
            ?: error("participant not found")
        val prevProtocol = before.protocolFinishTimeEpochMillis

        finishDetectionDao.insert(
            FinishDetectionEntity(
                raceId = raceId,
                participantHashId = participantHashId,
                detectedAtEpochMillis = detectedAtEpochMillis,
                sourcePhotoPath = sourcePhotoPath,
                matchCosineSimilarity = matchCosineSimilarity,
            ),
        )

        recomputeProtocolFinishForParticipant(raceId, participantHashId)

        val after = participantHashDao.getById(participantHashId)
            ?: error("participant missing after update")

        val t0 = after.firstFinishSeenAtEpochMillis
        val ignored = if (t0 != null) {
            detectedAtEpochMillis > t0 + FIRST_FINISH_SERIES_WINDOW_MS
        } else {
            false
        }
        val protocolUpdated = prevProtocol != after.protocolFinishTimeEpochMillis

        refreshProtocolXml(raceId)

        return RecordFinishDetectionOutcome(
            officialProtocolFinishMillis = after.protocolFinishTimeEpochMillis,
            detectionIgnoredForProtocolSeries = ignored,
            protocolFinishTimeUpdated = protocolUpdated,
        )
    }

    suspend fun removeParticipantFromRace(raceId: String, participantId: Long) {
        participantHashDao.deleteById(participantId, raceId)
        refreshProtocolXml(raceId)
    }

    suspend fun deleteRace(raceId: String) {
        raceDao.deleteRaceById(raceId)
        val folder = RacePaths.raceFolder(appContext, raceId)
        if (folder.exists()) {
            folder.deleteRecursively()
        }
    }

    suspend fun listParticipantHashes(raceId: String): List<RaceParticipantHashEntity> =
        participantHashDao.listForRace(raceId)

    suspend fun countParticipantsForRace(raceId: String): Int =
        participantHashDao.countForRace(raceId)

    suspend fun finishRecordCount(raceId: String): Int =
        finishDetectionDao.countForRace(raceId)

    /**
     * Distinct photos for this participant in this race: start/source, face thumbnail, then finish-line sources.
     * Paths that appear in finish detections are marked [ParticipantRacePhoto.isFinishFrame].
     */
    suspend fun listParticipantRacePhotos(raceId: String, participantId: Long): List<ParticipantRacePhoto> {
        val p = participantHashDao.getById(participantId) ?: return emptyList()
        require(p.raceId == raceId)
        val dets = finishDetectionDao.listForParticipantSorted(raceId, participantId)
        val finishCanonical = dets.map { File(it.sourcePhotoPath).canonicalPath }.toSet()

        fun normalizeExisting(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val f = File(path)
            if (!f.exists()) return null
            return f.canonicalPath
        }

        val out = mutableListOf<ParticipantRacePhoto>()
        val seen = LinkedHashSet<String>()

        fun push(path: String?, preferFinish: Boolean) {
            val canonical = normalizeExisting(path) ?: return
            val isFinish = preferFinish || finishCanonical.contains(canonical)
            if (!seen.add(canonical)) {
                val i = out.indexOfFirst { it.absolutePath == canonical }
                if (i >= 0 && isFinish && !out[i].isFinishFrame) {
                    out[i] = out[i].copy(isFinishFrame = true)
                }
                return
            }
            out.add(ParticipantRacePhoto(canonical, isFinish))
        }

        push(p.sourcePhoto, false)
        push(p.faceThumbnailPath, false)
        for (d in dets) {
            push(d.sourcePhotoPath, true)
        }
        return out
    }

    suspend fun refreshProtocolXml(raceId: String) {
        val allParticipants = participantHashDao.listForRace(raceId)
        val names = allParticipants.associate { it.id to it.displayName }
        val participants = allParticipants
            .filter { it.protocolFinishTimeEpochMillis != null }
            .sortedBy { it.protocolFinishTimeEpochMillis!! }
        val rows = participants.map { p ->
            val t = p.protocolFinishTimeEpochMillis!!
            val photoPath = resolvePhotoPathForProtocolFinish(raceId, p.id, t)
            val emb = protocolEmbeddingForParticipant(p.id, p)
            ProtocolFinishRow(
                participantHashId = p.id,
                embedding = emb,
                finishTimeEpochMillis = t,
                photoPath = photoPath,
            )
        }
        ProtocolXmlIo.write(RacePaths.protocolXml(appContext, raceId), raceId, rows, names)
    }

    private suspend fun protocolEmbeddingForParticipant(participantId: Long, legacy: RaceParticipantHashEntity): String {
        val rows = participantEmbeddingDao.listForParticipant(participantId)
        val first = rows.firstOrNull()?.embedding?.takeIf { it.isNotBlank() }
        return first ?: legacy.embedding
    }

    private suspend fun resolvePhotoPathForProtocolFinish(
        raceId: String,
        participantHashId: Long,
        protocolFinishMillis: Long,
    ): String {
        val dets = finishDetectionDao.listForParticipantSorted(raceId, participantHashId)
        val exact = dets.firstOrNull { it.detectedAtEpochMillis == protocolFinishMillis }
        if (exact != null) return exact.sourcePhotoPath
        return dets.lastOrNull { it.detectedAtEpochMillis <= protocolFinishMillis }?.sourcePhotoPath ?: ""
    }

    private suspend fun recomputeProtocolFinishForParticipant(raceId: String, participantHashId: Long) {
        val row = participantHashDao.getById(participantHashId) ?: return
        val dets = finishDetectionDao.listForParticipantSorted(raceId, participantHashId)
        if (dets.isEmpty()) {
            participantHashDao.update(
                row.copy(firstFinishSeenAtEpochMillis = null, protocolFinishTimeEpochMillis = null),
            )
            return
        }
        val t0 = dets.minOf { it.detectedAtEpochMillis }
        val windowEnd = t0 + FIRST_FINISH_SERIES_WINDOW_MS
        val protocolTime = dets.filter { it.detectedAtEpochMillis <= windowEnd }.maxOf { it.detectedAtEpochMillis }
        participantHashDao.update(
            row.copy(
                firstFinishSeenAtEpochMillis = t0,
                protocolFinishTimeEpochMillis = protocolTime,
            ),
        )
    }

    private fun assignFinishRanks(rows: List<ParticipantDashboardDbRow>): List<ParticipantDashboardRow> {
        val orderedFinishers = rows
            .filter { it.finishTimeEpochMillis != null }
            .sortedWith(compareBy({ it.finishTimeEpochMillis }, { it.participantId }))
        val rankById = orderedFinishers.mapIndexed { idx, r ->
            r.participantId to (idx + 1)
        }.toMap()
        return rows.map { db ->
            ParticipantDashboardRow(
                participantId = db.participantId,
                raceId = db.raceId,
                embedding = db.embedding,
                embeddingFailed = db.embeddingFailed,
                sourcePhoto = db.sourcePhoto,
                faceThumbnailPath = db.faceThumbnailPath,
                scannedPayload = db.scannedPayload,
                registryInfo = db.registryInfo,
                raceStartedAtEpochMillis = db.raceStartedAtEpochMillis,
                finishTimeEpochMillis = db.finishTimeEpochMillis,
                displayName = db.displayName,
                finishRank = rankById[db.participantId],
            )
        }
    }

    private fun writeRaceXml(entity: RaceEntity) {
        val snap = RaceXmlSnapshot(
            id = entity.id,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            startedAtEpochMillis = entity.startedAtEpochMillis,
            finishedAtEpochMillis = entity.finishedAtEpochMillis,
            latitude = entity.latitude,
            longitude = entity.longitude,
            status = entity.status,
        )
        RaceXmlIo.write(RacePaths.raceXml(appContext, entity.id), snap)
    }
}
