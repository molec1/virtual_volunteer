package com.virtualvolunteer.app.ui.util

import com.virtualvolunteer.app.data.model.RaceStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RaceUiFormatter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun formatDate(epochMillis: Long): String = dateFormat.format(Date(epochMillis))

    fun formatTime(epochMillis: Long): String = timeFormat.format(Date(epochMillis))

    fun formatDateTime(epochMillis: Long): String = dateTimeFormat.format(Date(epochMillis))

    fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatStatus(status: RaceStatus): String = status.name
}
