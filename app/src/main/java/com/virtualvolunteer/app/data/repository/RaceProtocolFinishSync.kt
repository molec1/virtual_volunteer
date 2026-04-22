package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.FinishDetectionDao
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.data.xml.ProtocolFinishRow
import com.virtualvolunteer.app.data.xml.ProtocolXmlIo

internal class RaceProtocolFinishSync(
    private val appContext: Context,
    private val participantHashDao: ParticipantHashDao,
    private val participantEmbeddingDao: ParticipantEmbeddingDao,
    private val finishDetectionDao: FinishDetectionDao,
    private val firstFinishSeriesWindowMs: Long,
) {
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

    suspend fun recomputeProtocolFinishForParticipant(raceId: String, participantHashId: Long) {
        val row = participantHashDao.getById(participantHashId) ?: return
        val dets = finishDetectionDao.listForParticipantSorted(raceId, participantHashId)
        if (dets.isEmpty()) {
            participantHashDao.update(
                row.copy(firstFinishSeenAtEpochMillis = null, protocolFinishTimeEpochMillis = null),
            )
            return
        }
        val t0 = dets.minOf { it.detectedAtEpochMillis }
        val windowEnd = t0 + firstFinishSeriesWindowMs
        val protocolTime = dets.filter { it.detectedAtEpochMillis <= windowEnd }.maxOf { it.detectedAtEpochMillis }
        participantHashDao.update(
            row.copy(
                firstFinishSeenAtEpochMillis = t0,
                protocolFinishTimeEpochMillis = protocolTime,
            ),
        )
    }
}
