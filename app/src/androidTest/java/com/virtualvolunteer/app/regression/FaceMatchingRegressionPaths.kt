package com.virtualvolunteer.app.regression

/**
 * Asset paths when test images are packaged from `testdata/face_matching/` into androidTest assets.
 *
 * Future: [DIFFERENT_PERSONS_ROOT] will hold cases where two images must **not** match.
 */
object FaceMatchingRegressionPaths {
    const val SAME_PERSONS_ROOT = "same_persons"
    const val DIFFERENT_PERSONS_ROOT = "different_persons"
}
