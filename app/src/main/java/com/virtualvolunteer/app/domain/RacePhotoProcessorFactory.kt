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
 * The caller owns them and must [MlKitFaceDetector.close] / [TfliteFaceEmbedder.close] when
 * done (e.g. in [androidx.fragment.app.Fragment.onDestroy]), same as before this factory existed.
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
