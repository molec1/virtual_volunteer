package com.virtualvolunteer.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.virtualvolunteer.app.data.local.AppDatabase
import com.virtualvolunteer.app.data.repository.RaceRepository

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
            finishDetectionDao = database.finishDetectionDao(),
            identityRegistryDao = database.identityRegistryDao(),
        )
    }

    private val pipelineLogLock = Any()
    private val pipelineLogDeque = ArrayDeque<String>(55)
    private val pipelineLogLiveData = MutableLiveData<List<String>>(emptyList())

    /** Last ~50 lines of start-photo pipeline diagnostics (in-memory). */
    val pipelineDebugLines: LiveData<List<String>> = pipelineLogLiveData

    fun appendPipelineLog(line: String) {
        Log.i("RacePipeline", line)
        synchronized(pipelineLogLock) {
            pipelineLogDeque.addLast(line)
            while (pipelineLogDeque.size > 50) pipelineLogDeque.removeFirst()
            pipelineLogLiveData.postValue(pipelineLogDeque.toList())
        }
    }
}
