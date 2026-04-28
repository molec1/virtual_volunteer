package com.virtualvolunteer.app.domain.matching

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinishFaceMatchPolicyTest {

    private fun participant(id: Long) = RaceParticipantHashEntity(
        id = id,
        raceId = "race",
        embedding = "0.0,1.0",
        sourcePhoto = "/tmp/p.jpg",
        createdAtEpochMillis = 0L,
    )

    private fun matchCandidate(id: Long, cos: Float): FaceMatchEngine.MatchCandidate {
        val p = participant(id)
        val set = ParticipantEmbeddingSet(p, listOf("0.0,1.0"))
        return FaceMatchEngine.MatchCandidate(set, cos)
    }

    @Test
    fun noCandidates() {
        val o = FinishFaceMatchPolicy.evaluate(0, emptyList())
        assertEquals(FinishFaceMatchPolicy.FinishFaceMatchDecision.NO_CANDIDATES, o.matchDecision)
        assertNull(o.matchedParticipant)
        assertNull(o.bestCos)
    }

    @Test
    fun example1_autoMatch() {
        val ranked = listOf(
            matchCandidate(1L, 0.67f),
            matchCandidate(2L, 0.40f),
        )
        val o = FinishFaceMatchPolicy.evaluate(2, ranked)
        assertEquals(FinishFaceMatchPolicy.FinishFaceMatchDecision.AUTO_MATCH_THRESHOLD, o.matchDecision)
        assertEquals(1L, o.matchedParticipant!!.id)
        assertEquals(0.67f, o.bestCos!!, 1e-5f)
    }

    @Test
    fun belowAutoThreshold_wideGap_stillNewUnknown() {
        val ranked = listOf(
            matchCandidate(10L, 0.62f),
            matchCandidate(11L, 0.31f),
        )
        val o = FinishFaceMatchPolicy.evaluate(2, ranked)
        assertEquals(FinishFaceMatchPolicy.FinishFaceMatchDecision.NEW_UNKNOWN, o.matchDecision)
        assertNull(o.matchedParticipant)
        assertEquals(0.31f, o.bestSecondGap!!, 1e-5f)
    }

    @Test
    fun example3_newUnknown_lowBestCos() {
        val ranked = listOf(
            matchCandidate(20L, 0.56f),
            matchCandidate(21L, 0.30f),
        )
        val o = FinishFaceMatchPolicy.evaluate(2, ranked)
        assertEquals(FinishFaceMatchPolicy.FinishFaceMatchDecision.NEW_UNKNOWN, o.matchDecision)
        assertNull(o.matchedParticipant)
    }

    @Test
    fun example4_newUnknown_justBelowAutoThreshold() {
        val ranked = listOf(
            matchCandidate(30L, 0.64f),
            matchCandidate(31L, 0.50f),
        )
        val o = FinishFaceMatchPolicy.evaluate(2, ranked)
        assertEquals(FinishFaceMatchPolicy.FinishFaceMatchDecision.NEW_UNKNOWN, o.matchDecision)
        assertNull(o.matchedParticipant)
    }

    @Test
    fun example5_singleCandidate_belowAutoThreshold() {
        val ranked = listOf(matchCandidate(50L, 0.61f))
        val o = FinishFaceMatchPolicy.evaluate(1, ranked)
        assertEquals(FinishFaceMatchPolicy.FinishFaceMatchDecision.NEW_UNKNOWN, o.matchDecision)
        assertNull(o.matchedParticipant)
        assertEquals(0.61f, o.bestCos!!, 1e-5f)
        assertNull(o.secondBestCos)
    }

    @Test
    fun poolNonEmptyButRankedEmpty_newUnknown() {
        val o = FinishFaceMatchPolicy.evaluate(3, emptyList())
        assertEquals(FinishFaceMatchPolicy.FinishFaceMatchDecision.NEW_UNKNOWN, o.matchDecision)
        assertNull(o.matchedParticipant)
    }
}
