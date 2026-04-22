package com.virtualvolunteer.app.domain

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import java.io.File
import java.util.Locale

internal class FinishFolderTestProtocolBuilder(
    private val races: RaceRepository,
    private val finishPipeline: FinishPhotoPipeline,
) {
    suspend fun build(
        context: Context,
        raceId: String,
    ): Result<File> = runCatching {
        RacePaths.ensureRaceLayout(context, raceId)
        races.applyOfflineRaceStartFromStartPhotos(raceId)

        val dir = RacePaths.finishPhotosDir(context, raceId)
        val images = dir.listFiles { file ->
            file.isFile && file.name.lowercase(Locale.US).let { name ->
                name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".webp")
            }
        }?.sortedBy { it.name }.orEmpty()

        val sb = StringBuilder()
        sb.appendLine("raceId=$raceId")
        sb.appendLine("offlineStartAppliedFromStartPhotos=true")
        sb.appendLine("finishPhotoCount=${images.size}")
        sb.appendLine("---")

        var totalNew = 0
        for (file in images) {
            val ts = PhotoTimestampResolver.resolveEpochMillis(file)
            sb.appendLine("file=${file.name} resolvedFinishTimeMs=$ts")
            val outcome = finishPipeline.processFinishPhotoInternal(
                raceId = raceId,
                photoFile = file,
                finishTimeEpochMillis = ts,
            )
            totalNew += outcome.newRecordsInserted
            sb.appendLine("newRecordsInserted=${outcome.newRecordsInserted}")
            sb.append(outcome.logText)
            sb.appendLine("---")
            races.updateLastProcessedPhoto(raceId, file.absolutePath)
        }
        sb.appendLine("totalNewRecords=$totalNew")

        val logFile = RacePaths.testProtocolDebugLog(context, raceId)
        logFile.parentFile?.mkdirs()
        logFile.writeText(sb.toString(), Charsets.UTF_8)
        logFile
    }
}
