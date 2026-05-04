package com.virtualvolunteer.app.domain

import android.content.Context
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.participants.RoomRaceParticipantPool

/**
 * Central construction for [RacePhotoProcessor] and its pipeline dependencies so wiring
 * and future threshold tweaks stay in one place.
 *
 * Each [createStack] call returns new [MlKitFaceDetector] and [TfliteFaceEmbedder] instances.
 * [VirtualVolunteerApp] keeps one stack for the process (finish queue + in-camera start ingest);
 * do not close those detectors from UI code.
 */
object RacePhotoProcessorFactory {

    fun createStack(
        appContext: Context,
        raceRepository: RaceRepository,
    ): RacePhotoProcessorStack {
        val faceDetector = MlKitFaceDetector()
        val faceEmbedder = TfliteFaceEmbedder(appContext)
        val processor = RacePhotoProcessor(
            races = raceRepository,
            faces = faceDetector,
            embedder = faceEmbedder,
            matcher = FaceMatchEngine(),
            pool = RoomRaceParticipantPool(raceRepository),
            appContext = appContext,
        )
        return RacePhotoProcessorStack(
            faceDetector = faceDetector,
            faceEmbedder = faceEmbedder,
            processor = processor,
        )
    }
}

data class RacePhotoProcessorStack(
    val faceDetector: MlKitFaceDetector,
    val faceEmbedder: TfliteFaceEmbedder,
    val processor: RacePhotoProcessor,
)
