package com.virtualvolunteer.app.regression

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.face.FaceEmbedder
import java.util.Locale

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

data class SamePersonPairResult(
    val personId: String,
    val imageA: String,
    val imageB: String,
    val cosineSimilarity: Float?,
    val threshold: Float,
    val passed: Boolean,
    val embeddingAFormatted: String,
    val embeddingBFormatted: String,
    val error: String,
)

data class SamePersonRegressionOutcome(
    val threshold: Float,
    val personFoldersFound: Int,
    val personFoldersSkipped: Int,
    val embeddingsCreated: Int,
    val embeddingFailures: Int,
    val samePersonPairsChecked: Int,
    val samePersonPairsFailed: Int,
    val pairResults: List<SamePersonPairResult>,
    val fatalReason: String?,
)

/**
 * Loads face crops from packaged assets and compares embeddings for same-person pairs.
 * Single-threaded; the embedder must not be used concurrently elsewhere.
 */
class FaceCropEmbeddingComparator {

    fun runSamePersonSuite(
        assets: AssetManager,
        embedder: FaceEmbedder,
        threshold: Float,
    ): SamePersonRegressionOutcome {
        val personIds = assets.list(FaceMatchingRegressionPaths.SAME_PERSONS_ROOT)
            ?.filter { it.isNotEmpty() && !it.startsWith(".") }
            ?.sorted()
            ?: emptyList()

        if (personIds.isEmpty()) {
            return emptyOutcome(
                threshold,
                fatalReason = "No subfolders under ${FaceMatchingRegressionPaths.SAME_PERSONS_ROOT}/",
            )
        }

        var skipped = 0
        val pathsToEmbed = LinkedHashMap<String, String>()
        for (personId in personIds) {
            val base = "${FaceMatchingRegressionPaths.SAME_PERSONS_ROOT}/$personId"
            val images = listLeafImageAssetPaths(assets, base).sorted()
            if (images.size < 2) {
                skipped++
                continue
            }
            for (p in images) {
                pathsToEmbed[p] = personId
            }
        }

        if (pathsToEmbed.isEmpty()) {
            return emptyOutcome(
                threshold,
                fatalReason = "Every person folder had fewer than 2 images (nothing to compare).",
                personFoldersFound = personIds.size,
                personFoldersSkipped = skipped,
            )
        }

        val embeddingByPath = LinkedHashMap<String, FloatArray?>()
        val errorByPath = LinkedHashMap<String, String>()
        var embeddingsOk = 0
        var embeddingsFail = 0

        for ((assetPath, _) in pathsToEmbed) {
            try {
                assets.open(assetPath).use { input ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
                    val bitmap = BitmapFactory.decodeStream(input, null, opts)
                        ?: throw IllegalStateException("BitmapFactory.decodeStream returned null")
                    try {
                        val vec = embedder.embed(bitmap)
                        embeddingByPath[assetPath] = vec
                        embeddingsOk++
                    } finally {
                        bitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                embeddingByPath[assetPath] = null
                errorByPath[assetPath] = e.message ?: e.javaClass.simpleName
                embeddingsFail++
            }
        }

        val pairResults = ArrayList<SamePersonPairResult>()
        var pairsChecked = 0
        var pairsFailed = 0

        for (personId in personIds) {
            val base = "${FaceMatchingRegressionPaths.SAME_PERSONS_ROOT}/$personId"
            val images = listLeafImageAssetPaths(assets, base).sorted()
            if (images.size < 2) continue
            for (i in images.indices) {
                for (j in i + 1 until images.size) {
                    val a = images[i]
                    val b = images[j]
                    pairsChecked++
                    val ea = embeddingByPath[a]
                    val eb = embeddingByPath[b]
                    val errA = errorByPath[a]
                    val errB = errorByPath[b]
                    val embAStr = ea?.let { EmbeddingMath.formatCommaSeparated(it) } ?: ""
                    val embBStr = eb?.let { EmbeddingMath.formatCommaSeparated(it) } ?: ""
                    when {
                        ea == null && eb == null -> {
                            val msg = "both embeddings failed: A=${errA ?: "?"} B=${errB ?: "?"}"
                            pairResults.add(
                                SamePersonPairResult(
                                    personId = personId,
                                    imageA = a,
                                    imageB = b,
                                    cosineSimilarity = null,
                                    threshold = threshold,
                                    passed = false,
                                    embeddingAFormatted = embAStr,
                                    embeddingBFormatted = embBStr,
                                    error = msg,
                                ),
                            )
                            pairsFailed++
                        }
                        ea == null -> {
                            val msg = "embedding A failed: ${errA ?: "?"}"
                            pairResults.add(
                                SamePersonPairResult(
                                    personId = personId,
                                    imageA = a,
                                    imageB = b,
                                    cosineSimilarity = null,
                                    threshold = threshold,
                                    passed = false,
                                    embeddingAFormatted = embAStr,
                                    embeddingBFormatted = embBStr,
                                    error = msg,
                                ),
                            )
                            pairsFailed++
                        }
                        eb == null -> {
                            val msg = "embedding B failed: ${errB ?: "?"}"
                            pairResults.add(
                                SamePersonPairResult(
                                    personId = personId,
                                    imageA = a,
                                    imageB = b,
                                    cosineSimilarity = null,
                                    threshold = threshold,
                                    passed = false,
                                    embeddingAFormatted = embAStr,
                                    embeddingBFormatted = embBStr,
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
                                SamePersonPairResult(
                                    personId = personId,
                                    imageA = a,
                                    imageB = b,
                                    cosineSimilarity = cos,
                                    threshold = threshold,
                                    passed = ok,
                                    embeddingAFormatted = embAStr,
                                    embeddingBFormatted = embBStr,
                                    error = "",
                                ),
                            )
                        }
                    }
                }
            }
        }

        return SamePersonRegressionOutcome(
            threshold = threshold,
            personFoldersFound = personIds.size,
            personFoldersSkipped = skipped,
            embeddingsCreated = embeddingsOk,
            embeddingFailures = embeddingsFail,
            samePersonPairsChecked = pairsChecked,
            samePersonPairsFailed = pairsFailed,
            pairResults = pairResults,
            fatalReason = null,
        )
    }

    private fun emptyOutcome(
        threshold: Float,
        fatalReason: String,
        personFoldersFound: Int = 0,
        personFoldersSkipped: Int = 0,
    ) = SamePersonRegressionOutcome(
        threshold = threshold,
        personFoldersFound = personFoldersFound,
        personFoldersSkipped = personFoldersSkipped,
        embeddingsCreated = 0,
        embeddingFailures = 0,
        samePersonPairsChecked = 0,
        samePersonPairsFailed = 0,
        pairResults = emptyList(),
        fatalReason = fatalReason,
    )

    companion object {
        fun hasBundledSamePersonSuite(assets: AssetManager): Boolean {
            val list = assets.list(FaceMatchingRegressionPaths.SAME_PERSONS_ROOT) ?: return false
            return list.any { it.isNotEmpty() && !it.startsWith(".") }
        }

        private fun isImageFileName(name: String): Boolean {
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot >= name.length - 1) return false
            val ext = name.substring(dot + 1).lowercase(Locale.US)
            return ext in IMAGE_EXTENSIONS
        }

        private fun listLeafImageAssetPaths(assets: AssetManager, dirUnderAssets: String): List<String> {
            val names = assets.list(dirUnderAssets) ?: return emptyList()
            val out = ArrayList<String>()
            for (name in names) {
                if (name.startsWith(".")) continue
                val path = "$dirUnderAssets/$name"
                val children = assets.list(path)
                when {
                    children == null -> {
                        if (isImageFileName(name)) out.add(path)
                    }
                    children.isEmpty() -> Unit
                    else -> out.addAll(listLeafImageAssetPaths(assets, path))
                }
            }
            return out
        }
    }
}
