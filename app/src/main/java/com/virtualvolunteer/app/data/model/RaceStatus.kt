package com.virtualvolunteer.app.data.model

/**
 * Lifecycle status for a race stored in Room and mirrored to race.xml.
 */
enum class RaceStatus {
    CREATED,
    STARTED,
    RECORDING,
    FINISHED,
    EXPORTED,
}
