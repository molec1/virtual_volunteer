package com.virtualvolunteer.app.ui.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

object RaceUiFormatter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val timeShortFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    // Uses device locale for localised month names, e.g. "18 Apr 2026" / "18 апр. 2026"
    private val dateReadableFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    // Day + month only, for items whose year already appeared above, e.g. "18 Apr"
    private val dateShortFormat = SimpleDateFormat("d MMM", Locale.getDefault())

    /** Always includes calendar date and time to second precision (device zone). Thread-safe. */
    private val csvDateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.US)

    fun formatDate(epochMillis: Long): String = dateFormat.format(Date(epochMillis))

    /** Human-readable date using device locale, e.g. "18 Apr 2026". */
    fun formatDateReadable(epochMillis: Long): String = dateReadableFormat.format(Date(epochMillis))

    /** Day + abbreviated month only, for items where the year was already shown above, e.g. "18 Apr". */
    fun formatDateShort(epochMillis: Long): String = dateShortFormat.format(Date(epochMillis))

    /** Calendar year extracted from [epochMillis] in the device's default time zone. */
    fun calendarYear(epochMillis: Long): Int = java.util.Calendar.getInstance()
        .also { it.timeInMillis = epochMillis }.get(java.util.Calendar.YEAR)

    fun formatTime(epochMillis: Long): String = timeFormat.format(Date(epochMillis))

    /** Time without seconds, e.g. "14:32". */
    fun formatTimeShort(epochMillis: Long): String = timeShortFormat.format(Date(epochMillis))

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

    /** CSV exports: full date + time including seconds (`dd/MM/yyyy HH:mm:ss`, device default zone). */
    fun formatDateTimeWithSeconds(epochMillis: Long): String =
        csvDateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    /** Elapsed column in timings CSV uses {@code HH:mm:ss} including a zero hours segment. */
    fun formatCsvElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

}
