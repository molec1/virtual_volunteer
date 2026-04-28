package com.virtualvolunteer.app.ui.racedetail

import android.app.DatePickerDialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
                showTimeWithSecondsPicker(context, cal) {
                    scope.launch(Dispatchers.IO) {
                        repository.updateRaceStartedAtEpochMillis(raceId, cal.timeInMillis)
                    }
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun showTimeWithSecondsPicker(
        context: Context,
        cal: Calendar,
        onConfirmed: () -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (12 * density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
        }
        val hourPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 23
            value = cal.get(Calendar.HOUR_OF_DAY)
            wrapSelectorWheel = true
        }
        val minutePicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = cal.get(Calendar.MINUTE)
            wrapSelectorWheel = true
        }
        val secondPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = cal.get(Calendar.SECOND)
            wrapSelectorWheel = true
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(hourPicker, lp)
        row.addView(minutePicker, lp)
        row.addView(secondPicker, lp)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.race_start_time_clock_dialog_title)
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                cal.set(Calendar.HOUR_OF_DAY, hourPicker.value)
                cal.set(Calendar.MINUTE, minutePicker.value)
                cal.set(Calendar.SECOND, secondPicker.value)
                cal.set(Calendar.MILLISECOND, 0)
                onConfirmed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
