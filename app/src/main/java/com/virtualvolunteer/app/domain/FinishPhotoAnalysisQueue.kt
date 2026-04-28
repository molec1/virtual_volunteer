package com.virtualvolunteer.app.domain

import android.util.Log
import com.virtualvolunteer.app.VirtualVolunteerApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serializes finish-photo face detection / embedding work off the capture path so CameraX can save
 * JPEGs and unblock the shutter immediately. Uses the app [CoroutineScope] so work continues across
 * activity/fragment teardown and typical screen-off / short backgrounding while the process stays alive.
 */
class FinishPhotoAnalysisQueue(
    private val scope: CoroutineScope,
    private val appContext: android.content.Context,
    private val processor: RacePhotoProcessor,
) {

    private val channel = Channel<FinishJob>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in channel) {
                val result = withContext(Dispatchers.IO) {
                    processor.ingestFinishPhotoWithLog(job.raceId, job.file)
                }
                result.fold(
                    onSuccess = { fr ->
                        val sum = fr.debugSummaryLine(job.file.name)
                        pipelineLog(
                            "FINISH_QUEUE_OK race=${job.raceId.take(8)}… len=${job.file.length()} $sum",
                        )
                        Log.i(
                            TAG,
                            "finish queue detail race=${job.raceId} path=${job.file.absolutePath}\n${fr.logText}",
                        )
                        if (fr.newRecordsInserted == 0) {
                            Log.w(TAG, "finish queue zero new rows (check decode/faces): $sum path=${job.file.absolutePath}")
                        }
                    },
                    onFailure = { t ->
                        Log.e(TAG, "finish analysis failed raceId=${job.raceId} file=${job.file.name}", t)
                        pipelineLog(
                            "FINISH_QUEUE_FAIL race=${job.raceId.take(8)}… file=${job.file.name} err=${t.message}",
                        )
                    },
                )
            }
        }
    }

    fun enqueue(raceId: String, photoFile: File) {
        if (!photoFile.isFile) {
            Log.w(TAG, "enqueue skip missing file=${photoFile.absolutePath}")
            pipelineLog("FINISH_QUEUE_SKIP_NOT_A_FILE path=${photoFile.absolutePath}")
            return
        }
        val len = photoFile.length()
        if (len == 0L) {
            Log.w(TAG, "enqueue skip empty file=${photoFile.absolutePath}")
            pipelineLog("FINISH_QUEUE_SKIP_EMPTY_FILE path=${photoFile.absolutePath}")
            return
        }
        val ok = channel.trySend(FinishJob(raceId, photoFile))
        if (ok.isFailure) {
            Log.e(TAG, "enqueue failed for ${photoFile.name}", ok.exceptionOrNull())
            pipelineLog("FINISH_QUEUE_ENQUEUE_SEND_FAILED file=${photoFile.name}")
        } else {
            pipelineLog(
                "FINISH_QUEUE_ENQUEUED race=${raceId.take(8)}… file=${photoFile.name} len=$len",
            )
            Log.i(TAG, "finish enqueued race=$raceId path=${photoFile.absolutePath} len=$len")
        }
    }

    private fun pipelineLog(line: String) {
        (appContext.applicationContext as? VirtualVolunteerApp)?.appendPipelineLog(line)
    }

    private data class FinishJob(val raceId: String, val file: File)

    companion object {
        private const val TAG = "FinishPhotoAnalysisQueue"
    }
}
