package com.virtualvolunteer.regression.jvm

import java.io.File
import java.util.Locale

object FaceJvmRegressionReport {

    const val CSV_FILENAME = "same_person_cosine_report.csv"
    const val SUMMARY_FILENAME = "same_person_cosine_summary.txt"

    fun writeReports(outputDir: File, outcome: JvmSamePersonRegressionOutcome) {
        outputDir.mkdirs()
        val csvFile = File(outputDir, CSV_FILENAME)
        val summaryFile = File(outputDir, SUMMARY_FILENAME)
        csvFile.bufferedWriter().use { w ->
            w.appendLine(
                "person_id,image_a,image_b,cosine_similarity,threshold,passed," +
                    "embedding_dim_a,embedding_dim_b,error",
            )
            for (row in outcome.pairResults) {
                w.appendLine(
                    listOf(
                        csvEscape(row.personId),
                        csvEscape(row.imageA),
                        csvEscape(row.imageB),
                        row.cosineSimilarity?.toString() ?: "",
                        row.threshold.toString(),
                        row.passed.toString(),
                        row.embeddingDimA?.toString() ?: "",
                        row.embeddingDimB?.toString() ?: "",
                        csvEscape(row.error),
                    ).joinToString(","),
                )
            }
        }
        summaryFile.writeText(buildSummaryText(outcome))
    }

    private fun buildSummaryText(outcome: JvmSamePersonRegressionOutcome): String {
        val cosines = outcome.pairResults.mapNotNull { it.cosineSimilarity }
        val minC = cosines.minOrNull()
        val maxC = cosines.maxOrNull()
        val medC = median(cosines)
        val worst10 = outcome.pairResults
            .filter { it.cosineSimilarity != null }
            .sortedBy { it.cosineSimilarity }
            .take(10)
        val verdict = computeVerdict(outcome)
        val sb = StringBuilder()
        sb.appendLine(
            "Note: JVM regression applies EXIF orientation (metadata-extractor) like production OrientedPhotoBitmap; " +
                "resize uses BufferedImage/Graphics2D bilinear (Android uses Bitmap.createScaledBitmap) — small numeric differences are possible.",
        )
        sb.appendLine()
        sb.appendLine("Face embedding same-person regression (JVM local crops, no ML Kit)")
        sb.appendLine("threshold used: ${outcome.threshold}")
        sb.appendLine("mode: ${outcome.mode.name.lowercase(Locale.US)}")
        sb.appendLine("person folders found: ${outcome.personFoldersFound}")
        sb.appendLine("person folders skipped (<2 images): ${outcome.personFoldersSkipped}")
        sb.appendLine("embeddings created: ${outcome.embeddingsCreated}")
        sb.appendLine("embedding failures: ${outcome.embeddingFailures}")
        sb.appendLine("same-person pairs checked: ${outcome.samePersonPairsChecked}")
        sb.appendLine("failed same-person pairs: ${outcome.samePersonPairsFailed}")
        if (outcome.skippedNoTestdata && outcome.fatalReason != null) {
            sb.appendLine("skip reason: ${outcome.fatalReason}")
        } else if (outcome.fatalReason != null) {
            sb.appendLine("fatal: ${outcome.fatalReason}")
        }
        sb.appendLine("min cosine (pairs with valid cosine): ${minC?.toSummaryString() ?: "n/a"}")
        sb.appendLine("median cosine: ${medC?.toSummaryString() ?: "n/a"}")
        sb.appendLine("max cosine: ${maxC?.toSummaryString() ?: "n/a"}")
        sb.appendLine("worst 10 same-person pairs by cosine (lowest first):")
        if (worst10.isEmpty()) {
            sb.appendLine("  (none with valid cosine)")
        } else {
            for (p in worst10) {
                sb.appendLine(
                    "  ${p.cosineSimilarity?.toSummaryString()}  ${p.imageA}  ${p.imageB}  person=${p.personId}",
                )
            }
        }
        sb.appendLine("final verdict: $verdict")
        return sb.toString()
    }

    private fun computeVerdict(outcome: JvmSamePersonRegressionOutcome): String =
        when {
            outcome.skippedNoTestdata -> "SKIPPED"
            outcome.fatalReason != null -> "FAIL"
            outcome.embeddingFailures > 0 -> "FAIL"
            outcome.samePersonPairsFailed > 0 -> "FAIL"
            outcome.samePersonPairsChecked == 0 -> "FAIL"
            else -> "PASS"
        }

    private fun Float.toSummaryString(): String =
        String.format(Locale.US, "%.6f", this)

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) {
            s[mid]
        } else {
            (s[mid - 1] + s[mid]) / 2f
        }
    }

    private fun csvEscape(field: String): String {
        if (field.isEmpty()) return field
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return field
        return '"' + field.replace("\"", "\"\"") + '"'
    }
}
