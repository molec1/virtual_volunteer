package com.virtualvolunteer.regression.jvm

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val outDir = File(parsed.outDir).also { it.mkdirs() }

    if (!parsed.testdataRoot.exists() || !parsed.testdataRoot.isDirectory) {
        println(
            "SKIPPED: testdata/face_matching not found. Expected repo-root testdata/face_matching/ with either " +
                "same_persons/<person_id>/ (…) or flat <person_id>/ (…).",
        )
        val skipOutcome = JvmSamePersonRegressionOutcome(
            mode = parsed.mode,
            threshold = parsed.threshold,
            personFoldersFound = 0,
            personFoldersSkipped = 0,
            embeddingsCreated = 0,
            embeddingFailures = 0,
            samePersonPairsChecked = 0,
            samePersonPairsFailed = 0,
            pairResults = emptyList(),
            fatalReason = "Missing testdata/face_matching directory.",
            skippedNoTestdata = true,
        )
        FaceJvmRegressionReport.writeReports(outDir, skipOutcome)
        println("Wrote reports under ${outDir.absolutePath}")
        exitProcess(0)
    }

    val modelFile = File(parsed.modelPath)
    if (!modelFile.isFile) {
        System.err.println("Model not found: ${modelFile.absolutePath}")
        exitProcess(1)
    }

    LocalFaceCropEmbedder(modelFile).use { embedder ->
        val outcome = runJvmSamePersonRegression(
            testdataFaceMatchingRoot = parsed.testdataRoot,
            embedder = embedder,
            threshold = parsed.threshold,
            mode = parsed.mode,
        )
        FaceJvmRegressionReport.writeReports(outDir, outcome)
        println("Wrote reports under ${outDir.absolutePath}")

        val verdict = when {
            outcome.skippedNoTestdata -> "SKIPPED"
            outcome.fatalReason != null -> "FAIL"
            outcome.embeddingFailures > 0 -> "FAIL"
            outcome.samePersonPairsFailed > 0 -> "FAIL"
            outcome.samePersonPairsChecked == 0 -> "FAIL"
            else -> "PASS"
        }
        println("faceEmbeddingRegressionTest: $verdict")

        val strictFail =
            parsed.mode == FaceJvmRegressionMode.STRICT &&
                verdict != "PASS" &&
                verdict != "SKIPPED"
        if (strictFail) {
            System.err.println(
                "faceEmbeddingRegressionTest failed ($verdict). See same_person_cosine_report.csv and " +
                    "same_person_cosine_summary.txt under ${outDir.absolutePath}. " +
                    "Use -PfaceTestMode=diagnostic to exit 0 while keeping reports, or -PfaceRegressionMinCosine=… to tune the threshold for local data.",
            )
            exitProcess(1)
        }
        exitProcess(0)
    }
}

private data class ParsedArgs(
    val testdataRoot: File,
    val modelPath: String,
    val outDir: String,
    val mode: FaceJvmRegressionMode,
    val threshold: Float,
)

private fun parseArgs(args: Array<String>): ParsedArgs {
    var testdataRoot: String? = null
    var modelPath: String? = null
    var outDir: String? = null
    var modeStr = "strict"
    var minCosine: Float? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--testdata-root" -> {
                testdataRoot = args.getOrNull(++i)
                    ?: throw IllegalArgumentException("--testdata-root needs a value")
            }
            "--model" -> {
                modelPath = args.getOrNull(++i)
                    ?: throw IllegalArgumentException("--model needs a value")
            }
            "--out" -> {
                outDir = args.getOrNull(++i)
                    ?: throw IllegalArgumentException("--out needs a value")
            }
            "--mode" -> {
                modeStr = args.getOrNull(++i)
                    ?: throw IllegalArgumentException("--mode needs a value")
            }
            "--min-cosine" -> {
                minCosine = args.getOrNull(++i)?.toFloatOrNull()
                    ?: throw IllegalArgumentException("--min-cosine needs a float")
            }
            else -> throw IllegalArgumentException("Unknown arg: ${args[i]}")
        }
        i++
    }
    return ParsedArgs(
        testdataRoot = File(requireNotNull(testdataRoot) { "--testdata-root required" }),
        modelPath = requireNotNull(modelPath) { "--model required" },
        outDir = requireNotNull(outDir) { "--out required" },
        mode = when (modeStr.lowercase()) {
            "diagnostic" -> FaceJvmRegressionMode.DIAGNOSTIC
            "strict" -> FaceJvmRegressionMode.STRICT
            else -> throw IllegalArgumentException("Unknown --mode $modeStr (use strict or diagnostic)")
        },
        threshold = minCosine ?: JVM_REGRESSION_DEFAULT_MIN_COSINE,
    )
}
