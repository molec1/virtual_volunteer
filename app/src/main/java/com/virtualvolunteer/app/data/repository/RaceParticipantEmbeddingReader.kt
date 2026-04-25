package com.virtualvolunteer.app.data.repository

import com.virtualvolunteer.app.data.local.ParticipantEmbeddingDao
import com.virtualvolunteer.app.data.local.ParticipantHashDao
import com.virtualvolunteer.app.domain.matching.ParticipantEmbeddingSet

/**
 * Loads per-participant embedding vectors for matching (multi-vector + legacy fallback).
 */
internal class RaceParticipantEmbeddingReader(
    private val participantHashDao: ParticipantHashDao,
    private val participantEmbeddingDao: ParticipantEmbeddingDao,
) {

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
}
