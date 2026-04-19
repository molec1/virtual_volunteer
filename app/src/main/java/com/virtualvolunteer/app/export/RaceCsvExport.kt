package com.virtualvolunteer.app.export

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.ParticipantDashboardDbRow
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import java.io.File
import java.nio.charset.StandardCharsets

object RaceCsvExport {

    internal fun effectiveGunMillis(race: RaceEntity, rows: List<ParticipantDashboardDbRow>): Long =
        race.startedAtEpochMillis
            ?: rows.minOfOrNull { it.createdAtEpochMillis }
            ?: race.createdAtEpochMillis

    fun writeTimesCsv(context: Context, race: RaceEntity, rows: List<ParticipantDashboardDbRow>): File {
        val gun = effectiveGunMillis(race, rows)
        val finishers = rows
            .filter { it.finishTimeEpochMillis != null }
            .sortedWith(compareBy({ it.finishTimeEpochMillis }, { it.participantId }))
        val dir = RacePaths.exportDir(context, race.id)
        dir.mkdirs()
        val file = File(dir, "times_${race.id.take(8)}_${System.currentTimeMillis()}.csv")
        val sb = StringBuilder()
        val version = ExportVersionLabel.get()
        sb.append("STARTOFEVENT,")
            .append(RaceUiFormatter.formatCsvDateTime(gun))
            .append(',')
            .append(version)
            .append('\n')
        finishers.forEachIndexed { index, row ->
            val ft = row.finishTimeEpochMillis!!
            sb.append(index).append(',')
                .append(RaceUiFormatter.formatCsvDateTime(ft)).append(',')
                .append(RaceUiFormatter.formatCsvElapsed((ft - gun).coerceAtLeast(0)))
                .append('\n')
        }
        val endTime = finishers.maxOfOrNull { it.finishTimeEpochMillis!! } ?: gun
        sb.append("ENDOFEVENT,").append(RaceUiFormatter.formatCsvDateTime(endTime)).append('\n')
        file.writeText(sb.toString(), StandardCharsets.UTF_8)
        return file
    }

    fun writeParticipantsCsv(context: Context, race: RaceEntity, rows: List<ParticipantDashboardDbRow>): File {
        val finishers = rows
            .filter { it.finishTimeEpochMillis != null }
            .sortedWith(compareBy({ it.finishTimeEpochMillis }, { it.participantId }))
        val dir = RacePaths.exportDir(context, race.id)
        dir.mkdirs()
        val file = File(dir, "participants_${race.id.take(8)}_${System.currentTimeMillis()}.csv")
        val sb = StringBuilder()
        val version = ExportVersionLabel.get()
        val headerTime = RaceUiFormatter.formatCsvDateTime(System.currentTimeMillis())
        sb.append("Start of File,").append(headerTime).append(',').append(version).append('\n')
        finishers.forEachIndexed { index, row ->
            val code = row.scannedPayload?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
            val pos = "P%04d".format(index + 1)
            val ft = row.finishTimeEpochMillis!!
            sb.append(code).append(',').append(pos).append(',')
                .append(RaceUiFormatter.formatCsvDateTime(ft)).append('\n')
        }
        file.writeText(sb.toString(), StandardCharsets.UTF_8)
        return file
    }
}
