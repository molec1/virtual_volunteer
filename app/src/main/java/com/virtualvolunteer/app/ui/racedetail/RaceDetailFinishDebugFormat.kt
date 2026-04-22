package com.virtualvolunteer.app.ui.racedetail

import com.virtualvolunteer.app.domain.debug.FinishPhotoDebugReport

internal fun formatFinishPhotoDebugReport(report: FinishPhotoDebugReport): String {
    val sb = StringBuilder()
    sb.appendLine("file=${report.photoPath}")
    sb.appendLine("detectedFaces=${report.detectedFaceCount}")
    sb.appendLine("---")
    for (face in report.faces) {
        sb.appendLine("Face #${face.faceIndex}")
        sb.appendLine("  embeddingPreview=${face.embeddingPreview}")
        sb.appendLine(
            "  nearestParticipantId=${face.nearestParticipantId} " +
                "nearestStoredEmbeddingPreview=${face.nearestParticipantEmbeddingPreview}",
        )
        sb.appendLine(
            "  cosineSimilarity=${face.cosineSimilarity} " +
                "threshold=${face.cosineThreshold} passesThreshold=${face.passesThreshold}",
        )
        sb.appendLine("  participantAlreadyFinished=${face.participantAlreadyFinished}")
        sb.appendLine("  wouldRecordAsNewFinish=${face.wouldRecordAsNewFinish}")
        sb.appendLine("---")
    }
    return sb.toString().trimEnd()
}
