package com.sweetgirlfriend.pet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetMotionGeometryTest {
    @Test
    fun `safe area combines asymmetric bars and cutout in full-display coordinates`() {
        val safe = PetMotionGeometry.safeArea(
            display = MotionRect(0f, 0f, 2400f, 1080f),
            systemBars = MotionInsets(left = 64f, top = 18f, right = 34f, bottom = 42f),
            displayCutout = MotionInsets(left = 112f, top = 0f, right = 8f, bottom = 0f),
        )

        assertEquals(MotionRect(112f, 18f, 2366f, 1038f), safe)
    }

    @Test
    fun `portrait gravity falls with Android sensor intuition instead of reversing`() {
        assertPoint(0f, 9.81f, PetMotionGeometry.sensorGravityToScreen(0f, 9.81f, 0))
        assertPoint(-9.81f, 0f, PetMotionGeometry.sensorGravityToScreen(9.81f, 0f, 0))
    }

    @Test
    fun `gravity rotates into all display orientations`() {
        val sensorX = 3f
        val sensorY = 7f

        assertPoint(-3f, 7f, PetMotionGeometry.sensorGravityToScreen(sensorX, sensorY, 0))
        assertPoint(-7f, -3f, PetMotionGeometry.sensorGravityToScreen(sensorX, sensorY, 1))
        assertPoint(3f, -7f, PetMotionGeometry.sensorGravityToScreen(sensorX, sensorY, 2))
        assertPoint(7f, 3f, PetMotionGeometry.sensorGravityToScreen(sensorX, sensorY, 3))
    }

    @Test
    fun `clockwise border poses put feet on all four safe edges and body inward`() {
        val bounds = MotionRect(11f, 23f, 311f, 623f)
        val width = bounds.width
        val height = bounds.height
        val top = PetMotionGeometry.borderPose(width / 2f, bounds)
        val right = PetMotionGeometry.borderPose(width + height / 2f, bounds)
        val bottom = PetMotionGeometry.borderPose(width + height + width / 2f, bounds)
        val left = PetMotionGeometry.borderPose(width * 2f + height + height / 2f, bounds)

        assertEquals(BorderEdge.TOP, top.edge)
        assertPoint(161f, 23f, top.foot)
        assertEquals(180f, top.surfaceRotationDegrees, EPSILON)

        assertEquals(BorderEdge.RIGHT, right.edge)
        assertPoint(311f, 323f, right.foot)
        assertEquals(-90f, right.surfaceRotationDegrees, EPSILON)

        assertEquals(BorderEdge.BOTTOM, bottom.edge)
        assertPoint(161f, 623f, bottom.foot)
        assertEquals(0f, bottom.surfaceRotationDegrees, EPSILON)

        assertEquals(BorderEdge.LEFT, left.edge)
        assertPoint(11f, 323f, left.foot)
        assertEquals(90f, left.surfaceRotationDegrees, EPSILON)

        assertTrue(listOf(top, right, bottom, left).all { it.faceLocalLeft })
        assertEquals(0f, top.velocityRotationDegrees, EPSILON)
        assertEquals(90f, right.velocityRotationDegrees, EPSILON)
        assertEquals(180f, bottom.velocityRotationDegrees, EPSILON)
        assertEquals(-90f, left.velocityRotationDegrees, EPSILON)
    }

    @Test
    fun `border projection is independent of character size`() {
        val bounds = MotionRect(40f, 90f, 1040f, 2210f)
        val progress = PetMotionGeometry.borderProgress(MotionPoint(1040f, 1100f), bounds)
        val pose = PetMotionGeometry.borderPose(progress, bounds)

        assertEquals(BorderEdge.RIGHT, pose.edge)
        assertPoint(1040f, 1100f, pose.foot)
    }

    @Test
    fun `rotation resize preserves edge and relative path position`() {
        val portrait = MotionRect(0f, 80f, 1080f, 2320f)
        val landscape = MotionRect(112f, 18f, 2366f, 1038f)
        val portraitRightHalf = portrait.width + portrait.height * 0.5f

        val remapped = PetMotionGeometry.remapBorderProgress(portraitRightHalf, portrait, landscape)
        val pose = PetMotionGeometry.borderPose(remapped, landscape)

        assertEquals(BorderEdge.RIGHT, pose.edge)
        assertPoint(2366f, 528f, pose.foot)
    }

    @Test
    fun `snap leaves eight dp gap while geometry does not impose drag air wall`() {
        val safe = MotionRect(0f, 80f, 1080f, 2320f)

        assertEquals(24f, PetMotionGeometry.snappedWindowX(100f, 420f, safe, 24f), EPSILON)
        assertEquals(636f, PetMotionGeometry.snappedWindowX(900f, 420f, safe, 24f), EPSILON)
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: MotionPoint) {
        assertEquals(expectedX, actual.x, EPSILON)
        assertEquals(expectedY, actual.y, EPSILON)
    }

    private companion object {
        const val EPSILON = 0.001f
    }
}
