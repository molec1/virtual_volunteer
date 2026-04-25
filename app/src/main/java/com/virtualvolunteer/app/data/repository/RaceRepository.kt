package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.RaceEventPhotosLister
import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.data.local.FinishDetectionDao
import com.virtualvolunteer.app.data.local.IdentityRegistryDao
import com.virtualvolunteer.app.data.local.IdentityRegistryEntity
import com.virtualvolunteer.app.data.local.ParticipantDashboardRow
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.RaceDao
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.domain.identity.GlobalIdentityResolution
import com.virtualvolunteer.app.domain.matching.ParticipantEmbeddingSet
import com.virtualvolunteer.app.export.DeviceIdentitiesImportResult
import com.virtualvolunteer.app.export.ParticipantsExportService
import com.virtualvolunteer.app.export.ParticipantsImportResult
import com.virtualvolunteer.app.export.RaceCsvExport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/** One image file for a participant in a race; [isFinishFrame] when used as a finish detection source. */
data class ParticipantRacePhoto(
    val absolutePath: String,
    val isFinishFrame: Boolean,
)

/**
 * One trimmed scan code on this device, ranked by best cosine between the lookup query vector(s)
 * and **any** historical embedding for that code (registry row + all linked race participants).
 */
data class ScannedIdentityLookupRank(
    val scanCodeTrimmed: String,
    val registryIds: Set<Long>,
    val maxCosineSimilarity: Float,
    val registryThumbnailPath: String?,
    val notes: String?,
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
 *
 * Still large after extracting merge/protocol/thumbnail/identity helpers; further splits would
 * separate read models (dashboard, photo lists) from write paths when that stays clear.
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

    private val raceXml = RaceXmlWriter(appContext)
    private val thumbnails = RaceListThumbnailHelper(appContext, raceDao)
    private val protocolFinish = RaceProtocolFinishSync(
        appContext,
        participantHashDao,
        participantEmbeddingDao,
        finishDetectionDao,
        FIRST_FINISH_SERIES_WINDOW_MS,
    )
    private val identityHelper = IdentityAssignmentHelper(
        identityRegistryDao,
        participantHashDao,
        participantEmbeddingDao,
    )
    private val embeddingWriter = RaceParticipantEmbeddingWriter(
        participantHashDao,
        participantEmbeddingDao,
        identityRegistryDao,
    )
    private val finishRecorder = RaceFinishDetectionRecorder(
        participantHashDao = participantHashDao,
        finishDetectionDao = finishDetectionDao,
        protocolFinish = protocolFinish,
        embeddingWriter = embeddingWriter,
        firstFinishSeriesWindowMs = FIRST_FINISH_SERIES_WINDOW_MS,
        logTag = TAG,
    )
    private val participantMediaPaths = RaceParticipantMediaPaths(
        appContext,
        participantHashDao,
        finishDetectionDao,
    )
    private val participantsExportService = ParticipantsExportService(
        participantHashDao,
        participantEmbeddingDao,
        identityRegistryDao,
    )
    private val scanMerge = ParticipantScanMergeCoordinator(
        raceDao = raceDao,
        participantHashDao = participantHashDao,
        finishDetectionDao = finishDetectionDao,
        participantEmbeddingDao = participantEmbeddingDao,
        identityRegistryDao = identityRegistryDao,
        logTag = TAG,
        syncParticipantPrimaryEmbedding = { embeddingWriter.syncParticipantPrimaryEmbeddingField(it) },
        recomputeProtocolFinish = { r, id -> protocolFinish.recomputeProtocolFinishForParticipant(r, id) },
        refreshProtocolXml = { protocolFinish.refreshProtocolXml(it) },
    )

    private val lifecycleStore = RaceLifecycleStore(
        appContext,
        raceDao,
        raceXml,
        thumbnails,
    )
    private val embeddingReader = RaceParticipantEmbeddingReader(
        participantHashDao,
        participantEmbeddingDao,
    )
    private val eventPhotoDeletion = RaceEventPhotoDeletionService(
        appContext,
        raceDao,
        participantHashDao,
        participantEmbeddingDao,
        finishDetectionDao,
        raceXml,
        protocolFinish,
        embeddingWriter,
        thumbnails,
    )
    private val participantRaceHistory = ParticipantRaceHistoryReader(
        raceDao,
        participantHashDao,
    )

    fun observeAllRaces(): Flow<List<RaceEntity>> = raceDao.observeAllRaces()

    fun observeRace(raceId: String): Flow<RaceEntity?> = raceDao.observeRace(raceId)

    fun observeParticipantDashboard(raceId: String): Flow<List<ParticipantDashboardRow>> =
        participantHashDao.observeParticipantDashboardRows(raceId).map { RaceDashboardFinishRanks.assignFinishRanks(it) }

    suspend fun getParticipantDashboardUi(raceId: String): List<ParticipantDashboardRow> =
        RaceDashboardFinishRanks.assignFinishRanks(participantHashDao.getParticipantDashboardSnapshot(raceId))

    fun observeFinishRecordCount(raceId: String): Flow<Int> =
        finishDetectionDao.observeCountForRace(raceId)

    /** Device-local identities with a scanned code, newest first (standalone registry screen). */
    fun observeIdentityRegistry(): Flow<List<IdentityRegistryEntity>> =
        identityRegistryDao.observeWithScannedPayload()

    /**
     * When a registry row has no valid [IdentityRegistryEntity.primaryThumbnailPhotoPath] on disk,
     * sets it from the oldest linked race participant’s first existing face or primary thumbnail path.
     */
    suspend fun ensureIdentityRegistryThumbnailsFromLinkedParticipants(rows: List<IdentityRegistryEntity>) {
        identityHelper.ensureIdentityRegistryThumbnailsFromLinkedParticipants(rows)
    }

    /**
     * Builds groups by **trimmed scan code** for every identity on device that has a scan, unions all
     * embeddings (each registry’s stored vector + every linked participant’s `participant_embeddings`,
     * with legacy `race_participant_hashes.embedding` only when the new table is empty for that row),
     * then scores each group with max cosine over all (query × historical) pairs of matching dimension.
     *
     * @param excludeParticipantIdForHistoricalPool when non-null, vectors from that protocol row are
     * omitted from the historical pool so the donor’s stored embeddings do not trivially match.
     */
    suspend fun rankScannedOnDeviceIdentitiesByCosine(
        queryEmbeddings: List<FloatArray>,
        excludeParticipantIdForHistoricalPool: Long? = null,
    ): List<ScannedIdentityLookupRank> =
        identityHelper.rankScannedOnDeviceIdentitiesByCosine(queryEmbeddings, excludeParticipantIdForHistoricalPool)

    suspend fun getRace(raceId: String): RaceEntity? = raceDao.getRace(raceId)

    suspend fun getParticipantHashById(id: Long): RaceParticipantHashEntity? =
        participantHashDao.getById(id)

    suspend fun updateLastProcessedPhoto(raceId: String, absolutePath: String?) {
        lifecycleStore.updateLastProcessedPhoto(raceId, absolutePath)
    }

    suspend fun applyOfflineRaceStartFromStartPhotos(raceId: String) {
        lifecycleStore.applyOfflineRaceStartFromStartPhotos(raceId)
    }

    suspend fun createNewRace(): String = lifecycleStore.createNewRace()

    suspend fun markStarted(raceId: String, startedAtEpochMillis: Long) {
        lifecycleStore.markStarted(raceId, startedAtEpochMillis)
    }

    suspend fun markRecording(raceId: String) {
        lifecycleStore.markRecording(raceId)
    }

    suspend fun markFinished(raceId: String, finishedAtEpochMillis: Long) {
        lifecycleStore.markFinished(raceId, finishedAtEpochMillis)
    }

    /** Updates gun / protocol start instant only (does not change [RaceEntity.status]). */
    suspend fun updateRaceStartedAtEpochMillis(raceId: String, startedAtEpochMillis: Long?) {
        lifecycleStore.updateRaceStartedAtEpochMillis(raceId, startedAtEpochMillis)
    }

    suspend fun markExported(raceId: String) {
        lifecycleStore.markExported(raceId)
    }

    /**
     * Inserts a participant row and, when [RaceParticipantHashEntity.embeddingFailed] is false,
     * records the first vector in [participant_embeddings] (see [initialEmbeddingSource]).
     */
    suspend fun insertParticipantHash(
        row: RaceParticipantHashEntity,
        initialEmbeddingSource: EmbeddingSourceType,
        primaryThumbnailPhotoPath: String? = null,
    ): Long = embeddingWriter.insertParticipantHash(row, initialEmbeddingSource, primaryThumbnailPhotoPath)

    /**
     * Updates [RaceParticipantHashEntity.primaryThumbnailPhotoPath] if the participant doesn't have one yet
     * and the provided path exists.
     */
    suspend fun updateParticipantPrimaryThumbnailIfMissing(participantId: Long, path: String?) {
        embeddingWriter.updateParticipantPrimaryThumbnailIfMissing(participantId, path)
    }

    /**
     * Build per-participant embedding sets for cosine matching (multi-vector).
     * Falls back to legacy [RaceParticipantHashEntity.embedding] when the new table is empty.
     */
    suspend fun listParticipantEmbeddingSets(raceId: String): List<ParticipantEmbeddingSet> =
        embeddingReader.listParticipantEmbeddingSets(raceId)

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
    ): Boolean = embeddingWriter.appendParticipantEmbeddingIfNew(
        raceId,
        participantId,
        embeddingCommaSeparated,
        sourceType,
        sourcePhotoPath,
        qualityScore,
        createdAtEpochMillis,
    )

    suspend fun resolveGlobalIdentity(embedding: FloatArray): GlobalIdentityResolution =
        identityHelper.resolveGlobalIdentity(embedding)

    suspend fun updateParticipantScan(raceId: String, participantId: Long, scannedPayload: String) {
        val trimmed = scannedPayload.trim()
        val row = participantHashDao.getById(participantId) ?: return
        require(row.raceId == raceId)
        participantHashDao.update(row.copy(scannedPayload = trimmed))
        row.identityRegistryId?.let { rid ->
            identityRegistryDao.updateScannedPayload(rid, trimmed)
        }
        scanMerge.mergeParticipantsSharingScannedPayload(raceId, keeperId = participantId, payload = trimmed)
        consolidateScanMergesForRace(raceId)
    }

    /**
     * Face-lookup confirmation: links this protocol row to an existing on-device scanned identity
     * (same outcome as scanning that code on the row): [scannedPayload], [RaceParticipantHashEntity.identityRegistryId],
     * [registryInfo], registry thumbnail sync, then [mergeParticipantsSharingScannedPayload] like [updateParticipantScan].
     *
     * @param registryIds registry row ids that share this trimmed code (canonical link uses the smallest id).
     */
    suspend fun assignParticipantToScannedIdentityFromFaceLookup(
        raceId: String,
        participantId: Long,
        scanCodeTrimmed: String,
        registryIds: Set<Long>,
    ) {
        if (registryIds.isEmpty()) return
        val row = participantHashDao.getById(participantId) ?: return
        require(row.raceId == raceId)

        val canonicalRegistryId = registryIds.minOrNull()!!
        val chosenIr = identityRegistryDao.getById(canonicalRegistryId) ?: return
        val scanStored = chosenIr.scannedPayload?.trim()?.takeIf { it.isNotEmpty() } ?: scanCodeTrimmed.trim()
        val registryInfo = listOfNotNull(chosenIr.notes, chosenIr.scannedPayload)
            .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .distinct()
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }

        participantHashDao.update(
            row.copy(
                scannedPayload = scanStored,
                identityRegistryId = canonicalRegistryId,
                registryInfo = registryInfo,
            ),
        )
        identityRegistryDao.updateScannedPayload(canonicalRegistryId, scanStored)

        sequenceOf(row.primaryThumbnailPhotoPath, row.faceThumbnailPath).forEach { path ->
            if (!path.isNullOrBlank() && File(path).exists()) {
                identityRegistryDao.updatePrimaryThumbnailIfMissing(canonicalRegistryId, path)
            }
        }
        embeddingWriter.updateParticipantPrimaryThumbnailIfMissing(participantId, chosenIr.primaryThumbnailPhotoPath)

        scanMerge.mergeParticipantsSharingScannedPayload(raceId, keeperId = participantId, payload = scanStored)
        protocolFinish.refreshProtocolXml(raceId)
        consolidateScanMergesForRace(raceId)
    }

    /**
     * Collapses duplicate **identity_registry** rows that share the same trimmed scan code (keeper =
     * smallest id), then merges protocol participants in [raceId] that share the same **effective**
     * scan text (race row or linked registry), like the dashboard COALESCE.
     */
    suspend fun consolidateScanMergesForRace(raceId: String) {
        scanMerge.consolidateScanMergesForRace(raceId)
    }

    /**
     * Same as [consolidateScanMergesForRace] for every race — used when opening the device-wide
     * scanned-identities list.
     */
    suspend fun consolidateAllScanMerges() {
        scanMerge.consolidateAllScanMerges()
    }

    /**
     * Face-lookup / operator correction: merge [donorId] into [keeperId] in this race
     * (finish detections + embeddings move to keeper; donor row removed). Does not require matching scans.
     */
    suspend fun mergeParticipantRowIntoKeeper(raceId: String, keeperId: Long, donorId: Long) {
        scanMerge.mergeParticipantIntoKeeper(raceId, keeperId = keeperId, donorId = donorId)
    }

    suspend fun updateParticipantDisplayName(raceId: String, participantId: Long, name: String?) {
        val row = participantHashDao.getById(participantId) ?: return
        require(row.raceId == raceId)
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        participantHashDao.update(row.copy(displayName = trimmed))
        protocolFinish.refreshProtocolXml(raceId)
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
     * Reads participants and embeddings for [raceId] and writes UTF-8 JSON to [destination].
     * Does not modify the database.
     */
    suspend fun exportParticipantsWithEmbeddingsJson(raceId: String, destination: File) {
        participantsExportService.exportParticipantsWithEmbeddingsJson(raceId, destination)
    }

    suspend fun importParticipants(raceId: String, source: File): ParticipantsImportResult =
        participantsExportService.importParticipantsWithEmbeddingsJson(raceId, source)

    suspend fun exportDeviceScannedIdentitiesJson(destination: File) {
        consolidateAllScanMerges()
        participantsExportService.exportDeviceScannedIdentitiesJson(destination)
    }

    suspend fun importDeviceScannedIdentitiesJson(source: File): DeviceIdentitiesImportResult {
        consolidateAllScanMerges()
        val result = participantsExportService.importDeviceScannedIdentitiesJson(source)
        consolidateAllScanMerges()
        return result
    }

    /**
     * Stores a matched finish detection and recomputes official protocol finish time for that participant.
     */
    suspend fun recordManualFinishDetection(
        raceId: String,
        participantId: Long,
        finishTimeEpochMillis: Long,
        sourcePhotoPath: String?,
        sourceEmbedding: FloatArray? = null,
    ): RecordFinishDetectionOutcome = finishRecorder.recordManualFinishDetection(
        raceId,
        participantId,
        finishTimeEpochMillis,
        sourcePhotoPath,
        sourceEmbedding,
    )

    suspend fun recordFinishDetectionForParticipant(
        raceId: String,
        participantId: Long,
        detectedAtEpochMillis: Long,
        sourcePhotoPath: String?,
        matchCosineSimilarity: Float?,
        sourceEmbedding: FloatArray? = null,
    ): RecordFinishDetectionOutcome = finishRecorder.recordFinishDetectionForParticipant(
        raceId,
        participantId,
        detectedAtEpochMillis,
        sourcePhotoPath,
        matchCosineSimilarity,
        sourceEmbedding,
    )

    suspend fun removeParticipantFromRace(raceId: String, participantId: Long) {
        participantHashDao.deleteById(participantId, raceId)
        protocolFinish.refreshProtocolXml(raceId)
    }

    suspend fun deleteRace(raceId: String) {
        lifecycleStore.deleteRace(raceId)
    }

    suspend fun listParticipantHashes(raceId: String): List<RaceParticipantHashEntity> =
        participantHashDao.listForRace(raceId)

    suspend fun listRacesForParticipant(participantId: Long): List<ParticipantRaceSummary> =
        participantRaceHistory.listRacesForParticipant(participantId)

    suspend fun resolveParticipantHashIdForRegistry(registryId: Long): Long? =
        participantHashDao.findLatestParticipantHashIdForRegistry(registryId)

    suspend fun countParticipantsForRace(raceId: String): Int =
        participantHashDao.countForRace(raceId)

    suspend fun finishRecordCount(raceId: String): Int =
        finishDetectionDao.countForRace(raceId)

    /**
     * Distinct photos for this participant in this race: start/source, face thumbnail, then finish-line sources.
     * Paths that appear in finish detections are marked [ParticipantRacePhoto.isFinishFrame].
     */
    suspend fun listParticipantRacePhotos(raceId: String, participantId: Long): List<ParticipantRacePhoto> =
        participantMediaPaths.listParticipantRacePhotos(raceId, participantId)

    /** Finish-line JPEGs/PNGs stored under this race (newest first). */
    suspend fun listFinishPhotoPathsForRace(raceId: String): List<String> =
        participantMediaPaths.listFinishPhotoPathsForRace(raceId)

    /** Oldest start photo in [start_photos] (first pre-start capture) for list previews. */
    suspend fun getFirstStartPhotoPathForRace(raceId: String): String? =
        thumbnails.getFirstStartPhotoPathForRace(raceId)

    /**
     * Ensures [RaceEntity.listThumbnailPath] points at a small on-disk JPEG derived from
     * [getFirstStartPhotoPathForRace]. Skips re-encode when the cache is newer than the source file.
     * Returns the path to the cached file, or null when there is no pre-start photo.
     */
    suspend fun ensureRaceListThumbnail(raceId: String): String? =
        thumbnails.ensureRaceListThumbnail(raceId)

    suspend fun refreshProtocolXml(raceId: String) {
        protocolFinish.refreshProtocolXml(raceId)
    }

    /** Full-frame start + finish photos for this race (newest first). */
    suspend fun listEventPhotoPaths(raceId: String): List<String> {
        if (raceDao.getRace(raceId) == null) return emptyList()
        return RaceEventPhotosLister.listSortedNewestFirst(appContext, raceId)
    }

    /**
     * Deletes a start/finish frame JPEG/PNG under the race folder, clears DB references to that path,
     * removes [FinishDetectionEntity] rows tied to it (then recomputes protocol finish), and refreshes
     * [protocol.xml]. Returns false if the race is missing or the path is not an allowed event photo file.
     */
    suspend fun deleteRaceEventPhoto(raceId: String, photoAbsolutePath: String): Boolean =
        eventPhotoDeletion.deleteRaceEventPhoto(raceId, photoAbsolutePath)
}
