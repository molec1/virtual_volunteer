package com.virtualvolunteer.app.data.repository

/**
 * Summary after [RaceRepository.executeFullDiskPhotoReprocess] walks stored start/finish originals
 * and rebuilds participants, finish matching, and protocol from disk.
 */
data class RaceReprocessResult(
    val startPhotosProcessed: Int,
    val startFacesInserted: Int,
    val startIngestFailures: Int,
    val finishPhotosProcessed: Int,
    val finishPipelineNewRows: Int,
    val finishIngestFailures: Int,
    val identityHintsCaptured: Int,
    val identityHintsRestored: Int,
)
