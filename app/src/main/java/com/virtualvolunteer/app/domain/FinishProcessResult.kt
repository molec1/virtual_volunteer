package com.virtualvolunteer.app.domain

/**
 * Outcome of running the finish-line pipeline on one file, with a text log for batch test mode.
 */
data class FinishProcessResult(
    val newRecordsInserted: Int,
    val logText: String,
)
