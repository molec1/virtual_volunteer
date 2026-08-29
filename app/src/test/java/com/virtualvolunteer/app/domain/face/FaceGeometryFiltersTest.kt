package com.virtualvolunteer.app.domain.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceGeometryFiltersTest {

    @Test
    fun startKeepsCentralFace() {
        assertTrue(FaceGeometryFilters.keepStartPeripheral(0.50f, aspect = 0.80f, sizeFilteredCount = 1))
    }

    @Test
    fun startDropsLoneFacePastHardBand() {
        assertFalse(FaceGeometryFilters.keepStartPeripheral(0.246f, aspect = 0.76f, sizeFilteredCount = 1))
    }

    @Test
    fun startKeepsLeftOfTwoPeerGroup() {
        // 2026-08-29 IMG_083256: left runner cx=0.246, pair of similar-sized faces.
        assertTrue(FaceGeometryFilters.keepStartPeripheral(0.246f, aspect = 0.76f, sizeFilteredCount = 2))
        assertTrue(FaceGeometryFilters.keepStartPeripheral(0.532f, aspect = 0.77f, sizeFilteredCount = 2))
    }

    @Test
    fun startKeepsRightOfThreePeerGroup() {
        // 2026-08-29 IMG_083328: right person cx=0.741 in a group of three.
        assertTrue(FaceGeometryFilters.keepStartPeripheral(0.306f, aspect = 0.72f, sizeFilteredCount = 3))
        assertTrue(FaceGeometryFilters.keepStartPeripheral(0.564f, aspect = 0.76f, sizeFilteredCount = 3))
        assertTrue(FaceGeometryFilters.keepStartPeripheral(0.741f, aspect = 0.80f, sizeFilteredCount = 3))
    }

    @Test
    fun startStillDropsExtremeEdgeEvenInGroup() {
        assertFalse(FaceGeometryFilters.keepStartPeripheral(0.08f, aspect = 0.80f, sizeFilteredCount = 3))
        assertFalse(FaceGeometryFilters.keepStartPeripheral(0.92f, aspect = 0.80f, sizeFilteredCount = 3))
    }

    @Test
    fun startStillDropsProfileInPeerBand() {
        assertFalse(FaceGeometryFilters.keepStartPeripheral(0.246f, aspect = 0.50f, sizeFilteredCount = 2))
    }

    @Test
    fun startCrowdKeepsStrictBand() {
        assertFalse(FaceGeometryFilters.keepStartPeripheral(0.246f, aspect = 0.76f, sizeFilteredCount = 8))
        assertTrue(FaceGeometryFilters.keepStartPeripheral(0.45f, aspect = 0.76f, sizeFilteredCount = 8))
    }

    @Test
    fun finishKeepsSmallDistantEdgeRunner() {
        val tag = FaceGeometryFilters.finishEdgeSkipTag(
            aspect = 0.80f,
            cxNorm = 0.25f,
            areaPx2 = 12_000,
            sizeFilteredCount = 2,
            otherCxNorms = listOf(0.50f),
        )
        assertNull(tag)
    }

    @Test
    fun finishKeepsRightOfThreePeerGroup() {
        val tag = FaceGeometryFilters.finishEdgeSkipTag(
            aspect = 0.80f,
            cxNorm = 0.741f,
            areaPx2 = 60_708,
            sizeFilteredCount = 3,
            otherCxNorms = listOf(0.306f, 0.564f),
        )
        assertNull(tag)
    }

    @Test
    fun finishStillDropsLargeBystanderOnExtremeEdge() {
        val tag = FaceGeometryFilters.finishEdgeSkipTag(
            aspect = 0.80f,
            cxNorm = 0.08f,
            areaPx2 = 80_000,
            sizeFilteredCount = 3,
            otherCxNorms = listOf(0.45f, 0.55f),
        )
        assertEquals("frontal_edge_skip", tag)
    }

    @Test
    fun finishStillDropsEdgeFaceInCrowd() {
        val tag = FaceGeometryFilters.finishEdgeSkipTag(
            aspect = 0.80f,
            cxNorm = 0.25f,
            areaPx2 = 40_000,
            sizeFilteredCount = 6,
            otherCxNorms = listOf(0.50f, 0.52f, 0.48f, 0.55f, 0.44f),
        )
        assertEquals("frontal_edge_skip", tag)
    }

    @Test
    fun finishStillDropsProfileAtEdge() {
        val tag = FaceGeometryFilters.finishEdgeSkipTag(
            aspect = 0.50f,
            cxNorm = 0.25f,
            areaPx2 = 20_000,
            sizeFilteredCount = 2,
            otherCxNorms = listOf(0.50f),
        )
        assertEquals("profile_peripheral_skip", tag)
    }
}
