package com.virtualvolunteer.app.export

import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.data.local.IdentityRegistryDao
import com.virtualvolunteer.app.data.local.IdentityRegistryEntity
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingEntity
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** Summary of [ParticipantsExportService.importParticipantsWithEmbeddingsJson]. */
data class ParticipantsImportResult(
    val participantsCreated: Int = 0,
    val existingParticipantsMatched: Int = 0,
    val embeddingsInserted: Int = 0,
    val skippedNullOrBlankBarcode: Int = 0,
    val skippedMalformedEmbeddingVectors: Int = 0,
)

/** Summary of [ParticipantsExportService.importDeviceScannedIdentitiesJson]. */
data class DeviceIdentitiesImportResult(
    val identitiesCreated: Int = 0,
    val identitiesUpdated: Int = 0,
    val registryFaceVectorsAppliedFromFile: Int = 0,
    val skippedNullOrBlankBarcode: Int = 0,
    val skippedMalformedEmbeddingVectors: Int = 0,
    val fileVectorsNotStoredRegistryFacePresent: Int = 0,
)

/**
 * Race-scoped participant JSON ([exportParticipantsWithEmbeddingsJson]) and device **identity_registry**
 * JSON for scanned-on-device identities ([exportDeviceScannedIdentitiesJson]).
 */
class ParticipantsExportService(
    private val participantHashDao: ParticipantHashDao,
    private val participantEmbeddingDao: ParticipantEmbeddingDao,
    private val identityRegistryDao: IdentityRegistryDao,
) {

    companion object {
        private const val SCHEMA_VERSION_RACE = 1
        private const val SCHEMA_VERSION_DEVICE = 2
        const val EXPORT_KIND_DEVICE_SCANNED = "device_scanned_identities"
        /** Opaque marker for rows created only from JSON import (no on-disk photo). */
        private const val IMPORT_SOURCE_PHOTO = "import:participants-json"
    }

    suspend fun exportParticipantsWithEmbeddingsJson(raceId: String, destination: File) {
        val hashes = participantHashDao.listForRace(raceId)
        val participantsJson = JSONArray()
        for (h in hashes) {
            val embeddingRows = participantEmbeddingDao.listForParticipant(h.id)
            val embeddingsArr = JSONArray()
            if (embeddingRows.isEmpty()) {
                parseCommaSeparatedEmbeddingVector(h.embedding)?.let { vec ->
                    embeddingsArr.put(jsonArrayOfNumbers(vec))
                }
            } else {
                for (row in embeddingRows) {
                    parseCommaSeparatedEmbeddingVector(row.embedding)?.let { vec ->
                        embeddingsArr.put(jsonArrayOfNumbers(vec))
                    }
                }
            }
            val barcode = h.scannedPayload?.trim()?.takeIf { it.isNotEmpty() }
            val obj = JSONObject()
            obj.put("barcode", barcode ?: JSONObject.NULL)
            obj.put("name", h.displayName ?: JSONObject.NULL)
            obj.put("embeddings", embeddingsArr)
            participantsJson.put(obj)
        }
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION_RACE)
        root.put("raceId", raceId)
        root.put("exportedAtEpochMillis", System.currentTimeMillis())
        root.put("participants", participantsJson)
        destination.parentFile?.mkdirs()
        destination.writeText(root.toString(), Charsets.UTF_8)
    }

    /**
     * Merges participant embeddings from JSON produced by [exportParticipantsWithEmbeddingsJson].
     *
     * Idempotent and additive: never deletes or overwrites embeddings or participant fields;
     * only inserts missing embedding rows (exact vector equality on parsed components).
     *
     * @param raceId Target race; must equal `raceId` in the file root or [IllegalArgumentException] is thrown.
     */
    suspend fun importParticipantsWithEmbeddingsJson(raceId: String, source: File): ParticipantsImportResult {
        val root = JSONObject(source.readText(Charsets.UTF_8))
        val fileRaceId = root.getString("raceId")
        require(fileRaceId == raceId) {
            "Import raceId mismatch: file has \"$fileRaceId\" but import target is \"$raceId\""
        }
        val schema = root.getInt("schemaVersion")
        require(schema == SCHEMA_VERSION_RACE) {
            "Unsupported schemaVersion=$schema (expected $SCHEMA_VERSION_RACE)"
        }
        val participants = root.getJSONArray("participants")
        val now = System.currentTimeMillis()
        var participantsCreated = 0
        var existingParticipantsMatched = 0
        var embeddingsInserted = 0
        var skippedNullOrBlankBarcode = 0
        var skippedMalformedEmbeddingVectors = 0

        for (i in 0 until participants.length()) {
            val p = participants.getJSONObject(i)
            val barcode = parseBarcode(p)
            if (barcode == null) {
                skippedNullOrBlankBarcode++
                continue
            }
            val displayName = parseDisplayName(p)
            val (inputVectors, malformedDelta) = parseUniqueEmbeddingVectors(
                p.optJSONArray("embeddings") ?: JSONArray(),
            )
            skippedMalformedEmbeddingVectors += malformedDelta
            // Still allow creating / resolving a participant with no vectors (additive bib-only row).

            val existingIds = participantHashDao.listParticipantIdsWithScannedPayload(raceId, barcode)
            val participantId = if (existingIds.isEmpty()) {
                val firstStr = inputVectors.firstOrNull()?.let { vectorToCommaSeparated(it) } ?: ""
                val row = RaceParticipantHashEntity(
                    raceId = raceId,
                    embedding = firstStr,
                    embeddingFailed = inputVectors.isEmpty(),
                    sourcePhoto = IMPORT_SOURCE_PHOTO,
                    faceThumbnailPath = null,
                    scannedPayload = barcode,
                    registryInfo = null,
                    identityRegistryId = null,
                    displayName = displayName,
                    firstFinishSeenAtEpochMillis = null,
                    protocolFinishTimeEpochMillis = null,
                    primaryThumbnailPhotoPath = null,
                    createdAtEpochMillis = now,
                )
                val id = participantHashDao.insert(row)
                participantsCreated++
                id
            } else {
                existingParticipantsMatched++
                existingIds.minOrNull()!!
            }

            if (inputVectors.isEmpty()) continue

            val existingRows = participantEmbeddingDao.listForParticipant(participantId)
            val existingParsed = existingRows.mapNotNull { parseCommaSeparatedEmbeddingVector(it.embedding) }
                .toMutableList()
            if (existingParsed.isEmpty()) {
                val hashRow = participantHashDao.getById(participantId)
                parseCommaSeparatedEmbeddingVector(hashRow?.embedding.orEmpty())?.let { legacy ->
                    if (existingParsed.none { vectorsEqual(it, legacy) }) {
                        existingParsed += legacy
                    }
                }
            }
            for (vec in inputVectors) {
                if (existingParsed.any { vectorsEqual(it, vec) }) continue
                participantEmbeddingDao.insert(
                    ParticipantEmbeddingEntity(
                        participantId = participantId,
                        raceId = raceId,
                        embedding = vectorToCommaSeparated(vec),
                        sourceType = EmbeddingSourceType.START,
                        sourcePhotoPath = null,
                        createdAtEpochMillis = now,
                        qualityScore = null,
                    ),
                )
                existingParsed += vec
                embeddingsInserted++
            }
        }

        return ParticipantsImportResult(
            participantsCreated = participantsCreated,
            existingParticipantsMatched = existingParticipantsMatched,
            embeddingsInserted = embeddingsInserted,
            skippedNullOrBlankBarcode = skippedNullOrBlankBarcode,
            skippedMalformedEmbeddingVectors = skippedMalformedEmbeddingVectors,
        )
    }

    /**
     * Writes **device** identity data: one entry per distinct trimmed scan code on [identity_registry],
     * with embeddings unioned from each registry row in the group and from all race participants linked
     * to those registry ids (same pool idea as face-lookup ranking).
     */
    suspend fun exportDeviceScannedIdentitiesJson(destination: File) {
        val rows = identityRegistryDao.listWithScannedPayload()
        val byCode = rows.groupBy { it.scannedPayload!!.trim() }
        val identitiesJson = JSONArray()
        for (code in byCode.keys.sorted()) {
            val group = byCode.getValue(code)
            val embeddingsArr = JSONArray()
            val seen = mutableListOf<List<Double>>()
            fun addVec(vec: List<Double>?) {
                if (vec == null) return
                if (seen.any { vectorsEqual(it, vec) }) return
                seen += vec
                embeddingsArr.put(jsonArrayOfNumbers(vec))
            }
            for (ir in group) {
                parseCommaSeparatedEmbeddingVector(ir.embedding)?.let { addVec(it) }
                addParticipantLinkedVectors(ir.id) { vec -> addVec(vec) }
            }
            val mergedNotes = group.mapNotNull { it.notes?.trim()?.takeIf { n -> n.isNotEmpty() } }
                .distinct()
                .joinToString(" · ")
                .takeIf { it.isNotBlank() }
            val obj = JSONObject()
            obj.put("barcode", code)
            obj.put("name", mergedNotes ?: JSONObject.NULL)
            obj.put("embeddings", embeddingsArr)
            identitiesJson.put(obj)
        }
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION_DEVICE)
        root.put("exportKind", EXPORT_KIND_DEVICE_SCANNED)
        root.put("exportedAtEpochMillis", System.currentTimeMillis())
        root.put("identities", identitiesJson)
        destination.parentFile?.mkdirs()
        destination.writeText(root.toString(), Charsets.UTF_8)
    }

    private suspend fun addParticipantLinkedVectors(
        identityRegistryId: Long,
        addVec: (List<Double>?) -> Unit,
    ) {
        for (h in participantHashDao.listHashesForIdentityRegistry(identityRegistryId)) {
            val embeddingRows = participantEmbeddingDao.listForParticipant(h.id)
            if (embeddingRows.isEmpty()) {
                addVec(parseCommaSeparatedEmbeddingVector(h.embedding))
            } else {
                for (row in embeddingRows) {
                    addVec(parseCommaSeparatedEmbeddingVector(row.embedding))
                }
            }
        }
    }

    /**
     * Merges [exportDeviceScannedIdentitiesJson] output into **identity_registry** only (never creates
     * race protocol rows). Additive: merges notes; fills an empty registry **embedding** from the file
     * when the device has no vector yet for that scan code (including vectors already present only on
     * linked race participants). Extra file vectors are skipped when the registry row already stores a
     * non-blank face embedding (single-vector registry limitation).
     */
    suspend fun importDeviceScannedIdentitiesJson(source: File): DeviceIdentitiesImportResult {
        val root = JSONObject(source.readText(Charsets.UTF_8))
        val schema = root.getInt("schemaVersion")
        require(schema == SCHEMA_VERSION_DEVICE) {
            "Unsupported device identity schemaVersion=$schema (expected $SCHEMA_VERSION_DEVICE)"
        }
        require(root.getString("exportKind") == EXPORT_KIND_DEVICE_SCANNED) {
            "Not a device scanned-identities export (wrong exportKind)"
        }
        val identities = root.optJSONArray("identities") ?: JSONArray()
        val now = System.currentTimeMillis()
        var identitiesCreated = 0
        var identitiesUpdated = 0
        var registryFaceVectorsAppliedFromFile = 0
        var skippedNullOrBlankBarcode = 0
        var skippedMalformedEmbeddingVectors = 0
        var fileVectorsNotStoredRegistryFacePresent = 0

        for (i in 0 until identities.length()) {
            val p = identities.getJSONObject(i)
            val barcode = parseBarcode(p)
            if (barcode == null) {
                skippedNullOrBlankBarcode++
                continue
            }
            val importName = parseDisplayName(p)
            val (fileVectors, malformedDelta) = parseUniqueEmbeddingVectors(
                p.optJSONArray("embeddings") ?: JSONArray(),
            )
            skippedMalformedEmbeddingVectors += malformedDelta

            val devicePool = deviceVectorPoolForBarcode(barcode)
            val newFromFile = fileVectors.filter { fv -> devicePool.none { vectorsEqual(it, fv) } }

            val matches = identityRegistryDao.listAll()
                .filter { it.scannedPayload?.trim() == barcode }
                .sortedBy { it.id }
            val keeper = matches.minByOrNull { it.id }

            if (keeper == null) {
                val embStr = fileVectors.firstOrNull()?.let { vectorToCommaSeparated(it) } ?: ""
                identityRegistryDao.insert(
                    IdentityRegistryEntity(
                        embedding = embStr,
                        scannedPayload = barcode,
                        notes = importName,
                        createdAtEpochMillis = now,
                    ),
                )
                identitiesCreated++
                if (fileVectors.isNotEmpty() && embStr.isNotBlank()) {
                    registryFaceVectorsAppliedFromFile++
                }
                continue
            }

            val mergedNotes = mergeDistinctNotes(keeper.notes, importName)
            var newKeeper = keeper
            var touched = false

            if (mergedNotes != keeper.notes) {
                newKeeper = newKeeper.copy(notes = mergedNotes)
                touched = true
            }

            val keeperFaceBlank = keeper.embedding.trim().isEmpty()
            if (keeperFaceBlank && newFromFile.isNotEmpty()) {
                newKeeper = newKeeper.copy(embedding = vectorToCommaSeparated(newFromFile.first()))
                registryFaceVectorsAppliedFromFile++
                touched = true
            } else if (newFromFile.isNotEmpty()) {
                fileVectorsNotStoredRegistryFacePresent += newFromFile.size
            }

            if (touched) {
                identityRegistryDao.update(newKeeper)
                identitiesUpdated++
            }
        }

        return DeviceIdentitiesImportResult(
            identitiesCreated = identitiesCreated,
            identitiesUpdated = identitiesUpdated,
            registryFaceVectorsAppliedFromFile = registryFaceVectorsAppliedFromFile,
            skippedNullOrBlankBarcode = skippedNullOrBlankBarcode,
            skippedMalformedEmbeddingVectors = skippedMalformedEmbeddingVectors,
            fileVectorsNotStoredRegistryFacePresent = fileVectorsNotStoredRegistryFacePresent,
        )
    }

    private fun mergeDistinctNotes(existing: String?, import: String?): String? {
        val parts = listOfNotNull(
            existing?.trim()?.takeIf { it.isNotEmpty() },
            import?.trim()?.takeIf { it.isNotEmpty() },
        ).distinct()
        return parts.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    private suspend fun deviceVectorPoolForBarcode(barcode: String): List<List<Double>> {
        val out = mutableListOf<List<Double>>()
        fun addVec(vec: List<Double>?) {
            if (vec == null) return
            if (out.any { vectorsEqual(it, vec) }) return
            out += vec
        }
        for (ir in identityRegistryDao.listAll()) {
            if (ir.scannedPayload?.trim() != barcode) continue
            addVec(parseCommaSeparatedEmbeddingVector(ir.embedding))
            for (h in participantHashDao.listHashesForIdentityRegistry(ir.id)) {
                val embeddingRows = participantEmbeddingDao.listForParticipant(h.id)
                if (embeddingRows.isEmpty()) {
                    addVec(parseCommaSeparatedEmbeddingVector(h.embedding))
                } else {
                    for (row in embeddingRows) {
                        addVec(parseCommaSeparatedEmbeddingVector(row.embedding))
                    }
                }
            }
        }
        return out
    }

    private fun parseBarcode(p: JSONObject): String? {
        if (!p.has("barcode") || p.isNull("barcode")) return null
        return p.getString("barcode").trim().takeIf { it.isNotEmpty() }
    }

    private fun parseDisplayName(p: JSONObject): String? {
        if (!p.has("name") || p.isNull("name")) return null
        return p.getString("name").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Parses [embeddings], dedupes identical vectors within the payload (first occurrence wins).
     * Second value is the number of malformed / unusable inner entries (for diagnostics).
     */
    private fun parseUniqueEmbeddingVectors(embeddings: JSONArray): Pair<List<List<Double>>, Int> {
        var malformed = 0
        val out = ArrayList<List<Double>>()
        val seen = ArrayList<List<Double>>()
        for (i in 0 until embeddings.length()) {
            val item = embeddings.opt(i)
            if (item == null) {
                malformed++
                continue
            }
            if (item !is JSONArray) {
                malformed++
                continue
            }
            val vec = parseJsonEmbeddingVector(item)
            if (vec == null) {
                malformed++
                continue
            }
            if (seen.any { vectorsEqual(it, vec) }) continue
            seen += vec
            out += vec
        }
        return Pair(out, malformed)
    }

    private fun parseJsonEmbeddingVector(a: JSONArray): List<Double>? {
        if (a.length() == 0) return null
        val out = ArrayList<Double>(a.length())
        for (i in 0 until a.length()) {
            if (a.isNull(i)) return null
            try {
                out.add(a.getDouble(i))
            } catch (_: JSONException) {
                return null
            }
        }
        return out
    }

    private fun vectorsEqual(a: List<Double>, b: List<Double>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    private fun vectorToCommaSeparated(vec: List<Double>): String =
        vec.joinToString(",") { it.toString() }

    private fun jsonArrayOfNumbers(values: List<Double>): JSONArray {
        val a = JSONArray()
        for (v in values) {
            a.put(v)
        }
        return a
    }

    /**
     * Parses [raw] as comma-separated floats (same storage as [RaceParticipantHashEntity.embedding]).
     * Returns null when blank or when no numeric components remain after trimming.
     */
    private fun parseCommaSeparatedEmbeddingVector(raw: String): List<Double>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(',')
        val out = ArrayList<Double>(parts.size)
        for (p in parts) {
            val t = p.trim()
            if (t.isEmpty()) continue
            out.add(t.toDouble())
        }
        return out.takeIf { it.isNotEmpty() }
    }
}
