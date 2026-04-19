package com.virtualvolunteer.app.domain.time

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Resolves a best-effort capture time for a photo file for offline protocol timing.
 *
 * Priority: EXIF DateTimeOriginal / DateTime → filename embedded millis (import_123_...) →
 * lastModified → current time.
 */
object PhotoTimestampResolver {

    private val exifPatterns = listOf(
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        },
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        },
    )

    private val importMillisRegex = Regex("""import_(\d{10,})_""")

    /**
     * Returns epoch millis for this file using the priority chain above.
     */
    fun resolveEpochMillis(file: File): Long {
        exifEpochMillis(file)?.let { return it }
        filenameEpochMillis(file.name)?.let { return it }
        val lm = file.lastModified()
        if (lm > 0L) return lm
        return System.currentTimeMillis()
    }

    /**
     * Maximum resolved time among image files under [dir], or null if none resolvable.
     */
    fun maxEpochAmongImages(dir: File): Long? {
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f ->
            f.isFile && isImageFile(f.name)
        } ?: return null
        if (files.isEmpty()) return null
        var max: Long? = null
        for (f in files) {
            val t = resolveEpochMillis(f)
            max = if (max == null) t else maxOf(max, t)
        }
        return max
    }

    private fun isImageFile(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
    }

    private fun exifEpochMillis(file: File): Long? {
        return try {
            val exif = ExifInterface(file)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: return null
            parseExifDate(raw)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun parseExifDate(raw: String): Date? {
        for (fmt in exifPatterns) {
            try {
                return fmt.parse(raw.trim())
            } catch (_: ParseException) {
                continue
            }
        }
        return null
    }

    private fun filenameEpochMillis(name: String): Long? {
        val m = importMillisRegex.find(name) ?: return null
        return m.groupValues[1].toLongOrNull()
    }
}
