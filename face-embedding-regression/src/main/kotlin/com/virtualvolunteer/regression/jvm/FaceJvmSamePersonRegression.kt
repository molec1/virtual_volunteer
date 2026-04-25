package com.virtualvolunteer.regression.jvm

import com.virtualvolunteer.app.domain.face.EmbeddingMath
import java.io.File
import java.util.Locale

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

enum class FaceJvmRegressionMode {
    STRICT,
    DIAGNOSTIC,
}

data class JvmSamePersonPairRow(
    val personId: String,
    val imageA: String,
    val imageB: String,
    val cosineSimilarity: Float?,
    val threshold: Float,
    val passed: Boolean,
    val embeddingDimA: Int?,
    val embeddingDimB: Int?,
    val error: String,
)

data class JvmSamePersonRegressionOutcome(
    val mode: FaceJvmRegressionMode,
    val threshold: Float,
    val personFoldersFound: Int,
    val personFoldersSkipped: Int,
    val embeddingsCreated: Int,
    val embeddingFailures: Int,
    val samePersonPairsChecked: Int,
    val samePersonPairsFailed: Int,
    val pairResults: List<JvmSamePersonPairRow>,
    val fatalReason: String?,
    /** When true, the runner did not load the model (missing optional testdata). */
    val skippedNoTestdata: Boolean,
)

fun runJvmSamePersonRegression(
    testdataFaceMatchingRoot: File,
    embedder: LocalFaceCropEmbedder,
    threshold: Float,
    mode: FaceJvmRegressionMode,
): JvmSamePersonRegressionOutcome {
    if (!testdataFaceMatchingRoot.exists() || !testdataFaceMatchingRoot.isDirectory) {
        return skippedOutcome(mode, threshold, "testdata/face_matching not found or not a directory.")
    }
    val suiteRoot = resolveSamePersonSuiteRoot(testdataFaceMatchingRoot)
    val isFlatLayout = suiteRoot.normalize().absolutePath == testdataFaceMatchingRoot.normalize().absolutePath

    val personDirs = suiteRoot.listFiles()
        ?.filter { it.isDirectory && it.name.isNotEmpty() && !it.name.startsWith(".") }
        ?.filter {
            if (!isFlatLayout) true
            else it.name != FaceJvmRegressionPaths.DIFFERENT_PERSONS_ROOT
        }
        ?.sortedBy { it.name }
        ?: emptyList()

    val personIds = personDirs.map { it.name }

    if (personIds.isEmpty()) {
        val hint =
            if (isFlatLayout) {
                "No person subfolders under ${testdataFaceMatchingRoot.absolutePath} " +
                    "(add directories with >=2 images each, or use ${FaceJvmRegressionPaths.SAME_PERSONS_ROOT}/<id>/)."
            } else {
                "No person subfolders under ${FaceJvmRegressionPaths.SAME_PERSONS_ROOT}/."
            }
        return skippedOutcome(mode, threshold, hint)
    }

    var skipped = 0
    val pathsToEmbed = LinkedHashMap<String, String>()
    for (personId in personIds) {
        val baseDir = File(suiteRoot, personId)
        val images = listLeafImageFiles(baseDir).sorted()
        if (images.size < 2) {
            skipped++
            continue
        }
        for (f in images) {
            val rel = relativeReportPath(testdataFaceMatchingRoot, f)
            pathsToEmbed[rel] = personId
        }
    }

    if (pathsToEmbed.isEmpty()) {
        return JvmSamePersonRegressionOutcome(
            mode = mode,
            threshold = threshold,
            personFoldersFound = personIds.size,
            personFoldersSkipped = skipped,
            embeddingsCreated = 0,
            embeddingFailures = 0,
            samePersonPairsChecked = 0,
            samePersonPairsFailed = 0,
            pairResults = emptyList(),
            fatalReason = "Every person folder had fewer than 2 images (nothing to compare).",
            skippedNoTestdata = false,
        )
    }

    val embeddingByPath = LinkedHashMap<String, FloatArray?>()
    val errorByPath = LinkedHashMap<String, String>()
    var embeddingsOk = 0
    var embeddingsFail = 0

    for ((relPath, _) in pathsToEmbed) {
        val file = File(testdataFaceMatchingRoot, relPath.replace('/', File.separatorChar)).normalize()
        try {
            val vec = embedder.embed(file)
            embeddingByPath[relPath] = vec
            embeddingsOk++
        } catch (e: Exception) {
            embeddingByPath[relPath] = null
            errorByPath[relPath] = e.message ?: e.javaClass.simpleName
            embeddingsFail++
        }
    }

    val pairResults = ArrayList<JvmSamePersonPairRow>()
    var pairsChecked = 0
    var pairsFailed = 0

    for (personId in personIds) {
        val baseDir = File(suiteRoot, personId)
        val images = listLeafImageFiles(baseDir).sorted()
        if (images.size < 2) continue
        val relImages = images.map { relativeReportPath(testdataFaceMatchingRoot, it) }
        for (i in relImages.indices) {
            for (j in i + 1 until relImages.size) {
                val a = relImages[i]
                val b = relImages[j]
                pairsChecked++
                val ea = embeddingByPath[a]
                val eb = embeddingByPath[b]
                val errA = errorByPath[a]
                val errB = errorByPath[b]
                val dimA = ea?.size
                val dimB = eb?.size
                when {
                    ea == null && eb == null -> {
                        val msg = "both embeddings failed: A=${errA ?: "?"} B=${errB ?: "?"}"
                        pairResults.add(
                            JvmSamePersonPairRow(
                                personId = personId,
                                imageA = a,
                                imageB = b,
                                cosineSimilarity = null,
                                threshold = threshold,
                                passed = false,
                                embeddingDimA = null,
                                embeddingDimB = null,
                                error = msg,
                            ),
                        )
                        pairsFailed++
                    }
                    ea == null -> {
                        val msg = "embedding A failed: ${errA ?: "?"}"
                        pairResults.add(
                            JvmSamePersonPairRow(
                                personId = personId,
                                imageA = a,
                                imageB = b,
                                cosineSimilarity = null,
                                threshold = threshold,
                                passed = false,
                                embeddingDimA = null,
                                embeddingDimB = dimB,
                                error = msg,
                            ),
                        )
                        pairsFailed++
                    }
                    eb == null -> {
                        val msg = "embedding B failed: ${errB ?: "?"}"
                        pairResults.add(
                            JvmSamePersonPairRow(
                                personId = personId,
                                imageA = a,
                                imageB = b,
                                cosineSimilarity = null,
                                threshold = threshold,
                                passed = false,
                                embeddingDimA = dimA,
                                embeddingDimB = null,
                                error = msg,
                            ),
                        )
                        pairsFailed++
                    }
                    else -> {
                        val cos = EmbeddingMath.cosineSimilarity(ea, eb)
                        val ok = cos >= threshold
                        if (!ok) pairsFailed++
                        pairResults.add(
                            JvmSamePersonPairRow(
                                personId = personId,
                                imageA = a,
                                imageB = b,
                                cosineSimilarity = cos,
                                threshold = threshold,
                                passed = ok,
                                embeddingDimA = dimA,
                                embeddingDimB = dimB,
                                error = "",
                            ),
                        )
                    }
                }
            }
        }
    }

    return JvmSamePersonRegressionOutcome(
        mode = mode,
        threshold = threshold,
        personFoldersFound = personIds.size,
        personFoldersSkipped = skipped,
        embeddingsCreated = embeddingsOk,
        embeddingFailures = embeddingsFail,
        samePersonPairsChecked = pairsChecked,
        samePersonPairsFailed = pairsFailed,
        pairResults = pairResults,
        fatalReason = null,
        skippedNoTestdata = false,
    )
}

/**
 * Preferred: [FaceJvmRegressionPaths.SAME_PERSONS_ROOT] under [faceMatchingRoot] (matches androidTest assets).
 * Fallback: [faceMatchingRoot] itself when person folders live directly under `testdata/face_matching/<id>/`.
 */
private fun resolveSamePersonSuiteRoot(faceMatchingRoot: File): File {
    val nested = File(faceMatchingRoot, FaceJvmRegressionPaths.SAME_PERSONS_ROOT)
    return if (nested.isDirectory) nested else faceMatchingRoot
}

private fun skippedOutcome(
    mode: FaceJvmRegressionMode,
    threshold: Float,
    reason: String,
) = JvmSamePersonRegressionOutcome(
    mode = mode,
    threshold = threshold,
    personFoldersFound = 0,
    personFoldersSkipped = 0,
    embeddingsCreated = 0,
    embeddingFailures = 0,
    samePersonPairsChecked = 0,
    samePersonPairsFailed = 0,
    pairResults = emptyList(),
    fatalReason = reason,
    skippedNoTestdata = true,
)

private fun relativeReportPath(faceMatchingRoot: File, file: File): String {
    val rootPath = faceMatchingRoot.toPath().normalize()
    val rel = rootPath.relativize(file.toPath().normalize()).toString().replace(File.separatorChar, '/')
    return rel
}

private fun isImageFileName(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot >= name.length - 1) return false
    val ext = name.substring(dot + 1).lowercase(Locale.US)
    return ext in IMAGE_EXTENSIONS
}

private fun listLeafImageFiles(dir: File): List<File> {
    val names = dir.listFiles() ?: return emptyList()
    val out = ArrayList<File>()
    for (f in names) {
        if (f.name.startsWith(".")) continue
        when {
            f.isDirectory -> out.addAll(listLeafImageFiles(f))
            f.isFile && isImageFileName(f.name) -> out.add(f)
        }
    }
    return out
}
