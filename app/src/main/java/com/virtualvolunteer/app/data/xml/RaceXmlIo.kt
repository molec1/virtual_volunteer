package com.virtualvolunteer.app.data.xml

import android.util.Xml
import com.virtualvolunteer.app.data.model.RaceStatus
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Reads/writes race.xml using a small, readable schema for local-first MVP.
 *
 * Root element: `<race>` with ISO-8601 timestamps using UTC (`...Z`) where applicable.
 */
object RaceXmlIo {

    /** XmlPullParser uses null for the default namespace. Not a compile-time constant. */
    private val ns: String? = null

    fun write(file: File, snapshot: RaceXmlSnapshot) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            val writer = OutputStreamWriter(out, StandardCharsets.UTF_8)
            writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.append("<race")
            writer.append(" id=\"").append(escape(snapshot.id)).append("\"")
            writer.append(" createdAt=\"").append(snapshot.createdAtEpochMillis.toString()).append("\"")
            writer.append(" startedAt=\"").append(snapshot.startedAtEpochMillis?.toString() ?: "").append("\"")
            writer.append(" finishedAt=\"").append(snapshot.finishedAtEpochMillis?.toString() ?: "").append("\"")
            writer.append(" latitude=\"").append(snapshot.latitude?.toString() ?: "").append("\"")
            writer.append(" longitude=\"").append(snapshot.longitude?.toString() ?: "").append("\"")
            writer.append(" status=\"").append(snapshot.status.name).append("\"")
            writer.append("></race>\n")
            writer.flush()
        }
    }

    fun read(file: File): RaceXmlSnapshot? {
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use { input ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(input, StandardCharsets.UTF_8.name())
                parser.nextTag()
                parser.require(XmlPullParser.START_TAG, ns, "race")
                val id = parser.getAttributeValue(ns, "id") ?: return null
                val created = parser.getAttributeValue(ns, "createdAt")?.toLongOrNull() ?: return null
                val started = parser.getAttributeValue(ns, "startedAt")?.toLongOrNull()
                val finished = parser.getAttributeValue(ns, "finishedAt")?.toLongOrNull()
                val lat = parser.getAttributeValue(ns, "latitude")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(ns, "longitude")?.toDoubleOrNull()
                val statusName = parser.getAttributeValue(ns, "status")
                val status = statusName?.let {
                    try {
                        RaceStatus.valueOf(it)
                    } catch (_: IllegalArgumentException) {
                        RaceStatus.CREATED
                    }
                } ?: RaceStatus.CREATED
                RaceXmlSnapshot(
                    id = id,
                    createdAtEpochMillis = created,
                    startedAtEpochMillis = started,
                    finishedAtEpochMillis = finished,
                    latitude = lat,
                    longitude = lon,
                    status = status,
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: XmlPullParserException) {
            null
        }
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
