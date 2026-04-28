package com.virtualvolunteer.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.virtualvolunteer.app.data.local.AppDatabase
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.domain.FinishPhotoAnalysisQueue
import com.virtualvolunteer.app.domain.RacePhotoProcessorFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application entry point.
 * Provides a single [RaceRepository] instance for the whole process.
 */
class VirtualVolunteerApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val raceRepository: RaceRepository by lazy {
        RaceRepository(
            appContext = applicationContext,
            raceDao = database.raceDao(),
            participantHashDao = database.participantHashDao(),
            participantEmbeddingDao = database.participantEmbeddingDao(),
            finishDetectionDao = database.finishDetectionDao(),
            identityRegistryDao = database.identityRegistryDao(),
            embeddingMatchBlacklistDao = database.embeddingMatchBlacklistDao(),
        )
    }

    /** Owns ML/TFLite for background finish analysis; kept for process lifetime — do not close from UI. */
    private val backgroundFinishPhotoStack by lazy {
        RacePhotoProcessorFactory.createStack(applicationContext, raceRepository)
    }

    private val finishPhotoAnalysisJob = SupervisorJob()
    private val finishPhotoAnalysisScope =
        CoroutineScope(finishPhotoAnalysisJob + Dispatchers.Default)

    /** Queued [com.virtualvolunteer.app.domain.RacePhotoProcessor.ingestFinishPhoto] after CameraX saves a finish JPEG. */
    val finishPhotoAnalysisQueue: FinishPhotoAnalysisQueue by lazy {
        FinishPhotoAnalysisQueue(
            scope = finishPhotoAnalysisScope,
            appContext = applicationContext,
            processor = backgroundFinishPhotoStack.processor,
        )
    }

    private val pipelineLogLock = Any()
    private val pipelineLogDeque = ArrayDeque<String>(PIPELINE_DEBUG_MAX_LINES + 16)
    private val pipelineLogLiveData = MutableLiveData<List<String>>(emptyList())

    /**
     * In-memory pipeline log for the race-detail debug box (also mirrored to Logcat as `RacePipeline`).
     * Large enough for long finish imports / reprocess runs; oldest lines drop only after this cap.
     */
    val pipelineDebugLines: LiveData<List<String>> = pipelineLogLiveData

    fun appendPipelineLog(line: String) {
        Log.i("RacePipeline", line)
        synchronized(pipelineLogLock) {
            pipelineLogDeque.addLast(line)
            while (pipelineLogDeque.size > PIPELINE_DEBUG_MAX_LINES) pipelineLogDeque.removeFirst()
            pipelineLogLiveData.postValue(pipelineLogDeque.toList())
        }
    }

    companion object {
        /** Ring buffer capacity for [appendPipelineLog] / [pipelineDebugLines]. */
        const val PIPELINE_DEBUG_MAX_LINES: Int = 8192
    }
}
