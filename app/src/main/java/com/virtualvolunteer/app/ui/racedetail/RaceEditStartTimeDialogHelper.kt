package com.virtualvolunteer.app.ui.racedetail

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.repository.RaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object RaceEditStartTimeDialogHelper {

    fun show(
        context: Context,
        scope: CoroutineScope,
        raceId: String,
        latestRace: RaceEntity?,
        repository: RaceRepository,
    ) {
        val race = latestRace ?: return
        val cal = Calendar.getInstance()
        race.startedAtEpochMillis?.let { cal.timeInMillis = it }

        DatePickerDialog(
            context,
            { _, y, m, d ->
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, m)
                cal.set(Calendar.DAY_OF_MONTH, d)
                TimePickerDialog(
                    context,
                    { _, h, min ->
                        cal.set(Calendar.HOUR_OF_DAY, h)
                        cal.set(Calendar.MINUTE, min)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        scope.launch(Dispatchers.IO) {
                            repository.updateRaceStartedAtEpochMillis(raceId, cal.timeInMillis)
                        }
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }
}
