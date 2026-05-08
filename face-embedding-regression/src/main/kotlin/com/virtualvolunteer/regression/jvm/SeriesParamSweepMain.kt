package com.virtualvolunteer.regression.jvm

import com.virtualvolunteer.app.domain.face.EmbeddingMath
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

// ── CLI entry point ─────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    val parsed = parseSeriesArgs(args)

    if (!parsed.testdataRoot.exists() || !parsed.testdataRoot.isDirectory) {
        println("SKIPPED: ${parsed.testdataRoot.absolutePath} not found.")
        exitProcess(0)
    }
    val modelFile = File(parsed.modelPath)
    if (!modelFile.isFile) {
        System.err.println("Model not found: ${modelFile.absolutePath}")
        exitProcess(1)
    }

    LocalFaceCropEmbedder(modelFile).use { embedder ->
        val pairs = collectConsecutivePairs(parsed.testdataRoot, embedder)
        if (pairs.isEmpty()) {
            println("No consecutive pairs found. Make sure person folders have ≥2 images with parseable timestamps.")
            exitProcess(0)
        }

        printPairStats(pairs)
        val results = runSweep(pairs, parsed.cosineValues, parsed.timeMsValues)
        printSweepGrid(results, parsed.cosineValues, parsed.timeMsValues)
        printTopCandidates(results, topN = 10)
        parsed.outFile?.let { writeResultsCsv(it, results) }
    }
}

// ── Timestamp extraction from filename ──────────────────────────────────────

private val TIMESTAMP_REGEX = Regex("""(?:^|_)(\d{10,13})(?:_|${'$'})""")

internal fun extractTimestampMs(name: String): Long? =
    TIMESTAMP_REGEX.findAll(name).mapNotNull { it.groupValues[1].toLongOrNull() }.firstOrNull()

// ── Data types ───────────────────────────────────────────────────────────────

data class CropEmbedding(
    val personId: String,
    val file: File,
    val timestampMs: Long,
    val embedding: FloatArray,
)

data class ConsecutivePair(
    val personId: String,
    val fileA: String,
    val fileB: String,
    val timestampA: Long,
    val timestampB: Long,
    val deltaMs: Long,
    val cosine: Float,
)

data class SweepPoint(
    val minCosine: Float,
    val maxTimeMs: Long,
    val matched: Int,
    val total: Int,
    val missedByTime: Int,
    val missedByCosine: Int,
    val missedByBoth: Int,
) {
    val coverage: Float get() = if (total == 0) 1f else matched.toFloat() / total
}

// ── Core: embed all crops and build consecutive pairs ────────────────────────

private fun collectConsecutivePairs(root: File, embedder: LocalFaceCropEmbedder): List<ConsecutivePair> {
    val personDirs = root.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != FaceJvmRegressionPaths.DIFFERENT_PERSONS_ROOT }
        ?.sortedBy { it.name }
        ?: return emptyList()

    val allPairs = mutableListOf<ConsecutivePair>()

    for (dir in personDirs) {
        val personId = dir.name
        val imageFiles = dir.listFiles()
            ?.filter { it.isFile && isImageExt(it.name) && !it.name.startsWith(".") }
            ?: continue

        // Embed each file; skip those with no parseable timestamp or embedding failure.
        val embeddings = imageFiles.mapNotNull { f ->
            val ts = extractTimestampMs(f.name) ?: run {
                System.err.println("  [WARN] no timestamp in filename: ${f.name} — skipped")
                return@mapNotNull null
            }
            val vec = runCatching { embedder.embed(f) }.getOrElse { e ->
                System.err.println("  [WARN] embed failed ${f.name}: ${e.message}")
                null
            } ?: return@mapNotNull null
            CropEmbedding(personId, f, ts, vec)
        }.toMutableList()

        // Sort by timestamp, then pair consecutive items.
        embeddings.sortBy { it.timestampMs }
        for (i in 0 until embeddings.size - 1) {
            val a = embeddings[i]
            val b = embeddings[i + 1]
            val delta = b.timestampMs - a.timestampMs
            val cos = EmbeddingMath.cosineSimilarity(a.embedding, b.embedding)
            allPairs += ConsecutivePair(
                personId = personId,
                fileA = a.file.name,
                fileB = b.file.name,
                timestampA = a.timestampMs,
                timestampB = b.timestampMs,
                deltaMs = delta,
                cosine = cos,
            )
        }
    }
    return allPairs
}

private fun isImageExt(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return ext in setOf("jpg", "jpeg", "png")
}

// ── Statistics printout ───────────────────────────────────────────────────────

private fun printPairStats(pairs: List<ConsecutivePair>) {
    println("═══════════════════════════════════════════════════════════════")
    println("  CONSECUTIVE SAME-PERSON PAIR STATISTICS")
    println("═══════════════════════════════════════════════════════════════")
    println("  Total consecutive pairs: ${pairs.size}")
    println()

    val byPerson = pairs.groupBy { it.personId }
    println("  %-12s  %6s  %8s  %6s  %8s  %6s  %8s".format(
        "Person", "Pairs", "minΔms", "maxΔms", "avgΔms", "minCos", "avgCos",
    ))
    println("  " + "-".repeat(66))
    for ((pid, ps) in byPerson.entries.sortedBy { it.key }) {
        val minDelta = ps.minOf { it.deltaMs }
        val maxDelta = ps.maxOf { it.deltaMs }
        val avgDelta = ps.map { it.deltaMs }.average()
        val minCos   = ps.minOf { it.cosine }
        val avgCos   = ps.map { it.cosine }.average()
        println("  %-12s  %6d  %8d  %6d  %8.0f  %6.3f  %8.3f".format(
            pid, ps.size, minDelta, maxDelta, avgDelta, minCos, avgCos,
        ))
    }

    println()
    println("  Overall cosine distribution:")
    val cosBuckets = listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.01f)
    for (i in 0 until cosBuckets.size - 1) {
        val lo = cosBuckets[i]; val hi = cosBuckets[i + 1]
        val count = pairs.count { it.cosine >= lo && it.cosine < hi }
        println("    [%.1f, %.1f)  %3d pairs".format(lo, hi, count))
    }
    println()
    println("  Overall delta-ms distribution:")
    val timeBuckets = listOf(0L, 500L, 1000L, 1500L, 2000L, 3000L, 5000L, Long.MAX_VALUE)
    val timeBucketLabels = listOf("<500", "500-1000", "1000-1500", "1500-2000", "2000-3000", "3000-5000", ">5000")
    for (i in 0 until timeBucketLabels.size) {
        val lo = timeBuckets[i]; val hi = timeBuckets[i + 1]
        val count = pairs.count { it.deltaMs >= lo && it.deltaMs < hi }
        println("    %-12s  %3d pairs".format(timeBucketLabels[i], count))
    }
    println()
}

// ── Parameter sweep ───────────────────────────────────────────────────────────

private fun runSweep(
    pairs: List<ConsecutivePair>,
    cosineValues: List<Float>,
    timeMsValues: List<Long>,
): List<SweepPoint> {
    val results = mutableListOf<SweepPoint>()
    for (cos in cosineValues) {
        for (time in timeMsValues) {
            var matched = 0
            var missedByTime = 0
            var missedByCosine = 0
            var missedByBoth = 0
            for (p in pairs) {
                val timeOk = p.deltaMs <= time
                val cosOk  = p.cosine >= cos
                when {
                    timeOk && cosOk  -> matched++
                    !timeOk && cosOk -> missedByTime++
                    timeOk && !cosOk -> missedByCosine++
                    else             -> missedByBoth++
                }
            }
            results += SweepPoint(cos, time, matched, pairs.size, missedByTime, missedByCosine, missedByBoth)
        }
    }
    return results
}

// ── Grid printout ─────────────────────────────────────────────────────────────

private fun printSweepGrid(results: List<SweepPoint>, cosines: List<Float>, times: List<Long>) {
    println("═══════════════════════════════════════════════════════════════════════════")
    println("  COVERAGE GRID  (value = % of consecutive same-person pairs matched by series)")
    println("  Rows = SERIES_MIN_COSINE, Columns = SERIES_PHOTO_WINDOW_MS")
    println("═══════════════════════════════════════════════════════════════════════════")

    // Header
    print("  minCosine \\ maxMs")
    for (t in times) print("  %6d".format(t))
    println()
    print("  " + "-".repeat(18))
    for (t in times) print("  ------")
    println()

    val byKey = results.associateBy { it.minCosine to it.maxTimeMs }
    for (cos in cosines) {
        print("  %7.2f           ".format(cos))
        for (t in times) {
            val sp = byKey[cos to t]
            val pct = sp?.coverage?.times(100f) ?: 0f
            print("  %5.1f%%".format(pct))
        }
        println()
    }
    println()
}

// ── Top candidates ────────────────────────────────────────────────────────────

private fun printTopCandidates(results: List<SweepPoint>, topN: Int) {
    println("═══════════════════════════════════════════════════════════════")
    println("  TOP $topN PARAMETER COMBINATIONS (by coverage, then tighter params preferred)")
    println("═══════════════════════════════════════════════════════════════")
    println("  %-10s  %-10s  %8s  %7s  %12s  %13s  %13s".format(
        "minCosine", "maxTimeMs", "coverage", "matched", "missedByTime", "missedByCosine", "missedByBoth",
    ))
    println("  " + "-".repeat(80))

    // Sort: highest coverage first, then smallest time window, then highest cosine
    val sorted = results.sortedWith(
        compareByDescending<SweepPoint> { it.coverage }
            .thenBy { it.maxTimeMs }
            .thenByDescending { it.minCosine },
    )
    for (sp in sorted.take(topN)) {
        println("  %-10.2f  %-10d  %7.1f%%  %7d  %12d  %13d  %13d".format(
            sp.minCosine, sp.maxTimeMs, sp.coverage * 100f,
            sp.matched, sp.missedByTime, sp.missedByCosine, sp.missedByBoth,
        ))
    }
    println()
    val best = sorted.firstOrNull()
    if (best != null) {
        println("  ★ Suggested: SERIES_MIN_COSINE=${best.minCosine}f  SERIES_PHOTO_WINDOW_MS=${best.maxTimeMs}L")
        println("    (${best.matched}/${best.total} consecutive pairs matched in series)")
        println()
    }
}

// ── CSV output ────────────────────────────────────────────────────────────────

private fun writeResultsCsv(outFile: File, results: List<SweepPoint>) {
    outFile.parentFile?.mkdirs()
    outFile.bufferedWriter().use { w ->
        w.write("minCosine,maxTimeMs,coverage,matched,total,missedByTime,missedByCosine,missedByBoth\n")
        for (sp in results) {
            w.write("${sp.minCosine},${sp.maxTimeMs},${sp.coverage},${sp.matched},${sp.total},${sp.missedByTime},${sp.missedByCosine},${sp.missedByBoth}\n")
        }
    }
    println("Results written to ${outFile.absolutePath}")
}

// ── CLI parsing ───────────────────────────────────────────────────────────────

private data class SeriesArgs(
    val testdataRoot: File,
    val modelPath: String,
    val cosineValues: List<Float>,
    val timeMsValues: List<Long>,
    val outFile: File?,
)

private fun parseSeriesArgs(args: Array<String>): SeriesArgs {
    var testdataRoot: String? = null
    var modelPath: String? = null
    var cosineStr: String? = null
    var timeStr: String? = null
    var outPath: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--testdata-root" -> testdataRoot = args.getOrNull(++i) ?: error("--testdata-root needs a value")
            "--model"         -> modelPath    = args.getOrNull(++i) ?: error("--model needs a value")
            "--cosines"       -> cosineStr    = args.getOrNull(++i) ?: error("--cosines needs a value")
            "--times-ms"      -> timeStr      = args.getOrNull(++i) ?: error("--times-ms needs a value")
            "--out"           -> outPath      = args.getOrNull(++i) ?: error("--out needs a value")
            else              -> error("Unknown arg: ${args[i]}")
        }
        i++
    }
    val defaultCosines = listOf(0.20f, 0.25f, 0.30f, 0.35f, 0.40f, 0.45f, 0.50f, 0.55f, 0.60f)
    val defaultTimes   = listOf(500L, 750L, 1000L, 1500L, 2000L, 2500L, 3000L, 4000L, 5000L)
    return SeriesArgs(
        testdataRoot = File(requireNotNull(testdataRoot) { "--testdata-root required" }),
        modelPath    = requireNotNull(modelPath) { "--model required" },
        cosineValues = cosineStr?.split(",")?.map { it.trim().toFloat() } ?: defaultCosines,
        timeMsValues = timeStr?.split(",")?.map { it.trim().toLong() }   ?: defaultTimes,
        outFile      = outPath?.let { File(it) },
    )
}
