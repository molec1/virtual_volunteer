package com.virtualvolunteer.app.regression

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import org.junit.Assert
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device regression: same-person face crops should produce cosine similarity >= threshold.
 * Optional: run only this class via connected tests after `:app:prepareFaceMatchingAndroidTestAssets`.
 * Local JVM equivalent: `./gradlew faceEmbeddingRegressionTest` (no device).
 */
@RunWith(AndroidJUnit4::class)
class ConnectedFaceEmbeddingRegressionTest {

    @Test
    fun samePersonCosineRegression() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val appContext = instrumentation.targetContext
        val args = try {
            InstrumentationRegistry.getArguments()
        } catch (_: IllegalStateException) {
            Bundle.EMPTY
        }
        val thresholdArg = args.getString(ARG_MIN_COSINE)?.toFloatOrNull()
        val threshold = thresholdArg ?: FaceMatchEngine.DEFAULT_MIN_COSINE

        val testAssets = testContext.assets
        val appAssets = appContext.assets
        val regressionAssets = when {
            FaceCropEmbeddingComparator.hasBundledSamePersonSuite(testAssets) -> testAssets
            FaceCropEmbeddingComparator.hasBundledSamePersonSuite(appAssets) -> appAssets
            else -> testAssets
        }
        if (!FaceCropEmbeddingComparator.hasBundledSamePersonSuite(regressionAssets)) {
            val listedTest = testAssets.list(FaceMatchingRegressionPaths.SAME_PERSONS_ROOT)
            val listedApp = appAssets.list(FaceMatchingRegressionPaths.SAME_PERSONS_ROOT)
            Log.w(
                TAG,
                "No same_persons/ in test or app assets. testPkg=${testContext.packageName} list(test)=" +
                    "${listedTest?.contentToString()} list(app)=${listedApp?.contentToString()} — run " +
                    ":app:prepareFaceMatchingAndroidTestAssets and ensure repo-root testdata/face_matching/ exists.",
            )
        }
        Assume.assumeTrue(
            "No bundled same_persons assets (omit or add testdata/face_matching to run this test).",
            FaceCropEmbeddingComparator.hasBundledSamePersonSuite(regressionAssets),
        )

        val outDir = appContext.filesDir
        File(outDir, FaceEmbeddingRegressionReport.CSV_FILENAME).delete()
        File(outDir, FaceEmbeddingRegressionReport.SUMMARY_FILENAME).delete()

        val embedder = TfliteFaceEmbedder(appContext.applicationContext)
        try {
            val outcome = FaceCropEmbeddingComparator().runSamePersonSuite(regressionAssets, embedder, threshold)
            FaceEmbeddingRegressionReport.writeSamePersonReports(
                outDir,
                outcome,
                deviceStorageHint =
                    "Host adb pull hint: use run-as with package=${appContext.packageName}; " +
                        "reports are under filesDir=${appContext.filesDir.absolutePath}",
            )
            Log.i(TAG, "Reports written to ${outDir.absolutePath}")

            val failures = mutableListOf<String>()
            outcome.fatalReason?.let { failures.add(it) }
            if (outcome.samePersonPairsChecked == 0) {
                failures.add("No same-person pairs were checked (insufficient images per folder or empty suite).")
            }
            if (outcome.embeddingFailures > 0) {
                failures.add("Embedding failures: ${outcome.embeddingFailures}")
            }
            if (outcome.samePersonPairsFailed > 0) {
                failures.add("Same-person pairs below threshold or with errors: ${outcome.samePersonPairsFailed}")
            }
            if (failures.isNotEmpty()) {
                Assert.fail(failures.joinToString("\n"))
            }
        } finally {
            embedder.close()
        }
    }

    companion object {
        private const val TAG = "ConnectedFaceEmbeddingRegression"
        /** Optional instrumentation argument to override [FaceMatchEngine.DEFAULT_MIN_COSINE]. */
        const val ARG_MIN_COSINE = "faceRegressionMinCosine"
    }
}
