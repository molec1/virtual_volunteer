package com.virtualvolunteer.app.domain

/**
 * Outcome of running the finish-line pipeline on one file, with a text log for batch test mode.
 */
data class FinishProcessResult(
    val newRecordsInserted: Int,
    val logText: String,
    /** False when the JPEG could not be decoded to a bitmap (no detector run). */
    val decodeSucceeded: Boolean = true,
    /** ML Kit face count after decode; meaningful only when [decodeSucceeded] is true. */
    val detectedFaceCount: Int = 0,
) {
    /** Coarse label for pipeline / queue one-liners (derived from [logText] + [newRecordsInserted]). */
    fun finishOutcomeLabel(): String = when {
        !decodeSucceeded -> "DECODE_FAILED"
        decodeSucceeded && detectedFaceCount == 0 -> "NO_FACES"
        newRecordsInserted > 0 -> "RECORDED"
        logText.contains("crop_failed_skip") || logText.contains("embedding_failed=") ->
            "ZERO_NEW_ROWS_FACE_SKIPPED"
        else -> "ZERO_NEW_ROWS"
    }

    fun debugSummaryLine(fileLabel: String): String =
        "file=$fileLabel faces=$detectedFaceCount newRows=$newRecordsInserted outcome=${finishOutcomeLabel()}"
}
