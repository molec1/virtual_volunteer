package com.virtualvolunteer.app.data.repository

import com.virtualvolunteer.app.data.local.ParticipantDashboardDbRow
import com.virtualvolunteer.app.data.local.ParticipantDashboardRow

internal object RaceDashboardFinishRanks {
    fun assignFinishRanks(rows: List<ParticipantDashboardDbRow>): List<ParticipantDashboardRow> {
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
                primaryThumbnailPhotoPath = db.primaryThumbnailPhotoPath,
                finishRank = rankById[db.participantId],
            )
        }
    }
}
