package com.virtualvolunteer.app.domain.face

import kotlin.math.abs

/**
 * Size-independent edge-of-frame rules shared by start, volunteer, and finish pipelines.
 *
 * A **peer group** is 2–4 faces that already survived the per-photo size filter: typically a
 * 2–3 person group shot filling the frame. Outer members of that group sit around
 * `|cx − 0.5| ≈ 0.24–0.26` and must not be treated as corridor-edge bystanders.
 *
 * Extreme edges (`|cx − 0.5| > [PEER_GROUP_MAX_CX_HALF]`), profiles, and crowded frames
 * (5+ size-filtered faces) still use the strict 0.20 band.
 */
object FaceGeometryFilters {

    /**
     * Default start/volunteer band: keep only `cx ∈ [0.30, 0.70]`.
     * See [keepStartPeripheral] for the peer-group exception.
     */
    const val START_PERIPHERAL_HARD_X = 0.20f

    /**
     * Outer bound of the peer-group exception. At 4K this is ≈ 820 px from each edge.
     * Calibrated on 2026-08-29 group photos: left-of-two `cx=0.246`, right-of-three `cx=0.741`.
     */
    const val PEER_GROUP_MAX_CX_HALF = 0.30f

    const val PEER_GROUP_MIN_COUNT = 2
    const val PEER_GROUP_MAX_COUNT = 4

    const val PERIPHERAL_PROFILE_ASPECT_RATIO = 0.65f
    const val PERIPHERAL_X_HALF = 0.19f
    const val PERIPHERAL_CENTER_MARGIN = 0.05f
    const val FRONTAL_EDGE_X = 0.20f
    const val FRONTAL_EDGE_MIN_AREA_PX2 = 15_000

    fun isPeerGroup(sizeFilteredCount: Int): Boolean =
        sizeFilteredCount in PEER_GROUP_MIN_COUNT..PEER_GROUP_MAX_COUNT

    fun isFrontal(aspect: Float): Boolean = aspect >= PERIPHERAL_PROFILE_ASPECT_RATIO

    fun cxHalfDist(cxNorm: Float): Float = abs(cxNorm - 0.5f)

    /**
     * Start / volunteer seeding: keep central faces; also keep a frontal peer in a
     * 2–4 face group out to [PEER_GROUP_MAX_CX_HALF].
     */
    fun keepStartPeripheral(cxNorm: Float, aspect: Float, sizeFilteredCount: Int): Boolean {
        val half = cxHalfDist(cxNorm)
        if (half <= START_PERIPHERAL_HARD_X) return true
        if (half > PEER_GROUP_MAX_CX_HALF) return false
        return isPeerGroup(sizeFilteredCount) && isFrontal(aspect)
    }

    fun hasMoreCentralFace(cxNorm: Float, otherCxNorms: List<Float>): Boolean {
        val half = cxHalfDist(cxNorm)
        return otherCxNorms.any { abs(it - 0.5f) < half - PERIPHERAL_CENTER_MARGIN }
    }

    /**
     * Finish two-part edge filter. Returns a skip tag, or `null` to keep the face.
     *
     * Part A — profile at edge (unchanged).
     * Part B — large frontal at edge with a more-central face, unless the photo is a
     * small peer group and this face is still inside [PEER_GROUP_MAX_CX_HALF].
     */
    fun finishEdgeSkipTag(
        aspect: Float,
        cxNorm: Float,
        areaPx2: Int,
        sizeFilteredCount: Int,
        otherCxNorms: List<Float>,
    ): String? {
        val half = cxHalfDist(cxNorm)
        val isProfile = !isFrontal(aspect)
        val isAlone = sizeFilteredCount == 1
        val hasCenter = hasMoreCentralFace(cxNorm, otherCxNorms)
        return when {
            isProfile && half > PERIPHERAL_X_HALF ->
                if (isAlone || hasCenter) "profile_peripheral_skip" else null
            !isProfile &&
                half > FRONTAL_EDGE_X &&
                areaPx2 >= FRONTAL_EDGE_MIN_AREA_PX2 &&
                hasCenter -> {
                if (isPeerGroup(sizeFilteredCount) && half <= PEER_GROUP_MAX_CX_HALF) {
                    null
                } else {
                    "frontal_edge_skip"
                }
            }
            else -> null
        }
    }
}
