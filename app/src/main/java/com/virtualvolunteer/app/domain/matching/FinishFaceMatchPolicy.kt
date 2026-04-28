package com.virtualvolunteer.app.domain.matching

import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import java.util.Locale

/**
 * Finish-line face identity: match when best cosine ≥ [AUTO_MATCH_THRESHOLD].
 */
object FinishFaceMatchPolicy {

    const val AUTO_MATCH_THRESHOLD: Float = 0.65f

    /**
     * Log / analytics labels for finish matching decisions (stable string values).
     */
    enum class FinishFaceMatchDecision {
        AUTO_MATCH_THRESHOLD,
        NEW_UNKNOWN,
        NO_CANDIDATES,
    }

    /**
     * @param candidatePoolSize number of embedding sets in the finish match pool for this face
     * @param rankedBestFirst best-first list from [FaceMatchEngine.rankedByCosine] (typically top 2 for logs)
     */
    fun evaluate(
        candidatePoolSize: Int,
        rankedBestFirst: List<FaceMatchEngine.MatchCandidate>,
    ): FinishFaceMatchOutcome {
        if (candidatePoolSize == 0) {
            return FinishFaceMatchOutcome(
                matchedParticipant = null,
                matchDecision = FinishFaceMatchDecision.NO_CANDIDATES,
                bestCos = null,
                secondBestCos = null,
                bestSecondGap = null,
            )
        }
        if (rankedBestFirst.isEmpty()) {
            return FinishFaceMatchOutcome(
                matchedParticipant = null,
                matchDecision = FinishFaceMatchDecision.NEW_UNKNOWN,
                bestCos = null,
                secondBestCos = null,
                bestSecondGap = null,
            )
        }
        val best = rankedBestFirst[0]
        val bestCos = best.cosineSimilarity
        val second = rankedBestFirst.getOrNull(1)
        val secondBestCos = second?.cosineSimilarity
        val bestSecondGap = secondBestCos?.let { bestCos - it }

        if (bestCos >= AUTO_MATCH_THRESHOLD) {
            return FinishFaceMatchOutcome(
                matchedParticipant = best.candidate.participant,
                matchDecision = FinishFaceMatchDecision.AUTO_MATCH_THRESHOLD,
                bestCos = bestCos,
                secondBestCos = secondBestCos,
                bestSecondGap = bestSecondGap,
            )
        }

        return FinishFaceMatchOutcome(
            matchedParticipant = null,
            matchDecision = FinishFaceMatchDecision.NEW_UNKNOWN,
            bestCos = bestCos,
            secondBestCos = secondBestCos,
            bestSecondGap = bestSecondGap,
        )
    }
}

data class FinishFaceMatchOutcome(
    val matchedParticipant: RaceParticipantHashEntity?,
    val matchDecision: FinishFaceMatchPolicy.FinishFaceMatchDecision,
    val bestCos: Float?,
    val secondBestCos: Float?,
    val bestSecondGap: Float?,
)

fun FinishFaceMatchOutcome.formatFinishMatchDecisionLogLine(faceNum: Int, rawFaceHeightPx: Int): String =
    buildString {
        append("face#$faceNum finishMatchDecision ")
        append("rawFaceHeightPx=$rawFaceHeightPx ")
        append("bestCos=${bestCos?.toLogFloat() ?: "null"} ")
        append("secondBestCos=${secondBestCos?.toLogFloat() ?: "null"} ")
        append("bestSecondGap=${bestSecondGap?.toLogFloat() ?: "null"} ")
        append("AUTO_MATCH_THRESHOLD=${FinishFaceMatchPolicy.AUTO_MATCH_THRESHOLD.toLogFloat()} ")
        append("matchDecision=${matchDecision.name} ")
        append(
            if (matchedParticipant != null) {
                "matchedParticipantId=${matchedParticipant.id}"
            } else {
                "matchedParticipantId=null"
            },
        )
    }

private fun Float.toLogFloat(): String = String.format(Locale.US, "%.4f", this)
