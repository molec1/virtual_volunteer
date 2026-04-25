package com.virtualvolunteer.app.regression

import java.io.File
import java.util.Locale

object FaceEmbeddingRegressionReport {

    const val CSV_FILENAME = "same_person_cosine_report.csv"
    const val SUMMARY_FILENAME = "same_person_cosine_summary.txt"

    fun writeSamePersonReports(
        outputDir: File,
        outcome: SamePersonRegressionOutcome,
        deviceStorageHint: String? = null,
    ) {
        outputDir.mkdirs()
        val csvFile = File(outputDir, CSV_FILENAME)
        val summaryFile = File(outputDir, SUMMARY_FILENAME)
        csvFile.bufferedWriter().use { w ->
            w.appendLine(
                "person_id,image_a,image_b,cosine_similarity,threshold,passed,embedding_a,embedding_b,error",
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
                        csvEscape(row.embeddingAFormatted),
                        csvEscape(row.embeddingBFormatted),
                        csvEscape(row.error),
                    ).joinToString(","),
                )
            }
        }
        summaryFile.writeText(buildSummaryText(outcome, deviceStorageHint))
    }

    private fun buildSummaryText(outcome: SamePersonRegressionOutcome, deviceStorageHint: String?): String {
        val cosines = outcome.pairResults.mapNotNull { it.cosineSimilarity }
        val minC = cosines.minOrNull()
        val maxC = cosines.maxOrNull()
        val medC = median(cosines)
        val worst10 = outcome.pairResults
            .filter { it.cosineSimilarity != null }
            .sortedBy { it.cosineSimilarity }
            .take(10)
        val verdict = when {
            outcome.fatalReason != null -> "FAIL"
            outcome.embeddingFailures > 0 -> "FAIL"
            outcome.samePersonPairsFailed > 0 -> "FAIL"
            outcome.samePersonPairsChecked == 0 -> "FAIL"
            else -> "PASS"
        }
        val sb = StringBuilder()
        deviceStorageHint?.let {
            sb.appendLine(it.trim())
            sb.appendLine()
        }
        sb.appendLine("Face embedding same-person regression (face crops, no ML Kit detection)")
        sb.appendLine("threshold used: ${outcome.threshold}")
        sb.appendLine("person folders found: ${outcome.personFoldersFound}")
        sb.appendLine("person folders skipped (<2 images): ${outcome.personFoldersSkipped}")
        sb.appendLine("embeddings created: ${outcome.embeddingsCreated}")
        sb.appendLine("embedding failures: ${outcome.embeddingFailures}")
        sb.appendLine("same-person pairs checked: ${outcome.samePersonPairsChecked}")
        sb.appendLine("failed same-person pairs: ${outcome.samePersonPairsFailed}")
        if (outcome.fatalReason != null) {
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
