package com.virtualvolunteer.regression.jvm

/**
 * Default same-person cosine floor; must stay in sync with
 * [com.virtualvolunteer.app.domain.matching.FaceMatchEngine.DEFAULT_MIN_COSINE] in the app module.
 */
const val JVM_REGRESSION_DEFAULT_MIN_COSINE: Float = 0.65f

object FaceJvmRegressionPaths {
    /** Matches androidTest [com.virtualvolunteer.app.regression.FaceMatchingRegressionPaths.SAME_PERSONS_ROOT]. */
    const val SAME_PERSONS_ROOT = "same_persons"
    /** Ignored as a person id when scanning flat [testdata/face_matching] layout. */
    const val DIFFERENT_PERSONS_ROOT = "different_persons"
}
