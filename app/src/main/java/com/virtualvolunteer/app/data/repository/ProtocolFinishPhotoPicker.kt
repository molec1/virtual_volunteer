package com.virtualvolunteer.app.data.repository

import com.virtualvolunteer.app.data.local.FinishDetectionEntity

/**
 * Picks which finish-line source photo corresponds to [protocolFinishTimeEpochMillis],
 * matching [RaceProtocolFinishSync] / protocol.xml export.
 */
internal object ProtocolFinishPhotoPicker {
    fun pickSourcePhotoPath(
        protocolFinishMillis: Long?,
        detectionsSortedAsc: List<FinishDetectionEntity>,
    ): String? {
        if (protocolFinishMillis == null || detectionsSortedAsc.isEmpty()) return null
        val exact = detectionsSortedAsc.firstOrNull { it.detectedAtEpochMillis == protocolFinishMillis }
        if (exact != null) return exact.sourcePhotoPath
        return detectionsSortedAsc.lastOrNull { it.detectedAtEpochMillis <= protocolFinishMillis }?.sourcePhotoPath
    }
}
