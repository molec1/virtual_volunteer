package com.virtualvolunteer.app.data.xml

import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One official finish line in [protocol.xml] per participant with a [protocolFinishTimeEpochMillis].
 */
data class ProtocolFinishRow(
    val participantHashId: Long,
    val embedding: String,
    val finishTimeEpochMillis: Long,
    val photoPath: String,
)

/**
 * Writes finish protocol.xml: one entry per participant, ordered by official protocol finish time.
 */
object ProtocolXmlIo {

    private val isoUtc: SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun write(
        file: File,
        raceId: String,
        records: List<ProtocolFinishRow>,
        displayNameByParticipantId: Map<Long, String?> = emptyMap(),
    ) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<protocol raceId=\"").append(escape(raceId)).append("\">\n")
        for (r in records) {
            sb.append("  <finish")
            sb.append(" embedding=\"").append(escape(truncateEmbedding(r.embedding))).append("\"")
            sb.append(" finishTime=\"").append(escape(isoUtc.format(Date(r.finishTimeEpochMillis)))).append("\"")
            sb.append(" photo=\"").append(escape(File(r.photoPath).name)).append("\"")
            sb.append(" participantHashId=\"").append(r.participantHashId).append("\"")
            val name = displayNameByParticipantId[r.participantHashId]
            if (!name.isNullOrBlank()) {
                sb.append(" displayName=\"").append(escape(name)).append("\"")
            }
            sb.append(" />\n")
        }
        sb.append("</protocol>\n")
        file.writeText(sb.toString(), StandardCharsets.UTF_8)
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private fun truncateEmbedding(value: String, maxLen: Int = 800): String =
        if (value.length <= maxLen) value else value.take(maxLen) + "…"
}
