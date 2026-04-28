package com.virtualvolunteer.app.data.files

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists face crop provenance per race in [RacePaths.faceCropManifestFile]: which full-frame file
 * the crop came from, rectangle in **EXIF-upright vision bitmap** pixel coordinates (same space as
 * ML Kit on [OrientedPhotoBitmap.decodeApplyingExifOrientation]), optional path to the JPEG under
 * [RacePaths.facesDir], and protocol participant row id for disambiguation when several people
 * appear on one frame.
 */
object FaceCropManifestDisk {

    data class Entry(
        val sourcePhotoPath: String,
        val visionWidth: Int,
        val visionHeight: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val participantHashId: Long,
        val cropFilePath: String?,
    )

    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(file: File): Any =
        locks.getOrPut(file.absolutePath) { Any() }

    fun canonicalPath(path: String): String? =
        try {
            File(path).canonicalPath
        } catch (_: Exception) {
            null
        }

    fun readEntries(manifestFile: File): List<Entry> {
        if (!manifestFile.exists() || manifestFile.length() == 0L) return emptyList()
        return try {
            FileInputStream(manifestFile).use { ins ->
                val parser = Xml.newPullParser()
                parser.setInput(InputStreamReader(ins, Charsets.UTF_8))
                val out = ArrayList<Entry>()
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "entry") {
                        val source = parser.getAttributeValue(null, "sourcePath") ?: ""
                        val vw = parser.getAttributeValue(null, "visionW")?.toIntOrNull() ?: 0
                        val vh = parser.getAttributeValue(null, "visionH")?.toIntOrNull() ?: 0
                        val left = parser.getAttributeValue(null, "left")?.toIntOrNull() ?: 0
                        val top = parser.getAttributeValue(null, "top")?.toIntOrNull() ?: 0
                        val right = parser.getAttributeValue(null, "right")?.toIntOrNull() ?: 0
                        val bottom = parser.getAttributeValue(null, "bottom")?.toIntOrNull() ?: 0
                        val pid = parser.getAttributeValue(null, "participantId")?.toLongOrNull() ?: 0L
                        val crop = parser.getAttributeValue(null, "cropPath")?.trim()?.takeIf { it.isNotEmpty() }
                        if (source.isNotBlank() && vw > 0 && vh > 0 && pid > 0L) {
                            out.add(
                                Entry(
                                    sourcePhotoPath = source,
                                    visionWidth = vw,
                                    visionHeight = vh,
                                    left = left,
                                    top = top,
                                    right = right,
                                    bottom = bottom,
                                    participantHashId = pid,
                                    cropFilePath = crop,
                                ),
                            )
                        }
                    }
                    event = parser.next()
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeEntries(manifestFile: File, entries: List<Entry>) {
        manifestFile.parentFile?.mkdirs()
        val sw = StringWriter()
        val ser: XmlSerializer = Xml.newSerializer()
        ser.setOutput(sw)
        ser.startDocument("utf-8", true)
        ser.startTag("", "manifest")
        ser.attribute("", "version", "1")
        for (e in entries) {
            ser.startTag("", "entry")
            ser.attribute("", "sourcePath", e.sourcePhotoPath)
            ser.attribute("", "visionW", e.visionWidth.toString())
            ser.attribute("", "visionH", e.visionHeight.toString())
            ser.attribute("", "left", e.left.toString())
            ser.attribute("", "top", e.top.toString())
            ser.attribute("", "right", e.right.toString())
            ser.attribute("", "bottom", e.bottom.toString())
            ser.attribute("", "participantId", e.participantHashId.toString())
            if (!e.cropFilePath.isNullOrBlank()) {
                ser.attribute("", "cropPath", e.cropFilePath)
            }
            ser.endTag("", "entry")
        }
        ser.endTag("", "manifest")
        ser.endDocument()
        FileOutputStream(manifestFile, false).use { out ->
            out.write(sw.toString().toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Drops any existing rows for the same canonical source + participant, then appends [entry].
     */
    fun upsertReplaceParticipantOnSource(context: Context, raceId: String, entry: Entry) {
        val file = RacePaths.faceCropManifestFile(context, raceId)
        val cNew = canonicalPath(entry.sourcePhotoPath) ?: return
        synchronized(lockFor(file)) {
            val existing = readEntries(file)
            val merged = existing.filterNot { e ->
                val c = canonicalPath(e.sourcePhotoPath)
                c == cNew && e.participantHashId == entry.participantHashId
            } + entry
            writeEntries(file, merged)
        }
    }

    fun removeEntriesForCanonicalSource(context: Context, raceId: String, sourceCanonical: String) {
        val file = RacePaths.faceCropManifestFile(context, raceId)
        synchronized(lockFor(file)) {
            if (!file.exists()) return
            val existing = readEntries(file)
            val kept = existing.filter { canonicalPath(it.sourcePhotoPath) != sourceCanonical }
            if (kept.isEmpty()) {
                file.delete()
            } else if (kept.size != existing.size) {
                writeEntries(file, kept)
            }
        }
    }

    fun removeEntriesForParticipant(context: Context, raceId: String, participantHashId: Long) {
        if (participantHashId <= 0L) return
        val file = RacePaths.faceCropManifestFile(context, raceId)
        synchronized(lockFor(file)) {
            if (!file.exists()) return
            val existing = readEntries(file)
            val kept = existing.filter { it.participantHashId != participantHashId }
            if (kept.isEmpty()) {
                file.delete()
            } else if (kept.size != existing.size) {
                writeEntries(file, kept)
            }
        }
    }

    fun deleteManifest(context: Context, raceId: String) {
        val file = RacePaths.faceCropManifestFile(context, raceId)
        synchronized(lockFor(file)) {
            file.delete()
        }
    }

    /**
     * @param participantHashId when &gt; 0, only entries for that protocol row; otherwise any participant on this source.
     */
    fun findEntryForPhoto(
        context: Context,
        raceId: String,
        sourceAbsolutePath: String,
        participantHashId: Long,
    ): Entry? {
        val file = RacePaths.faceCropManifestFile(context, raceId)
        if (!file.exists()) return null
        val cTarget = canonicalPath(sourceAbsolutePath) ?: return null
        val entries = readEntries(file)
        val filtered = entries.filter { e ->
            canonicalPath(e.sourcePhotoPath) == cTarget &&
                (participantHashId <= 0L || e.participantHashId == participantHashId)
        }
        if (filtered.isEmpty()) return null
        if (filtered.size == 1) return filtered.first()
        if (participantHashId > 0L) return filtered.last()
        return filtered.maxByOrNull { it.participantHashId }
    }
}
