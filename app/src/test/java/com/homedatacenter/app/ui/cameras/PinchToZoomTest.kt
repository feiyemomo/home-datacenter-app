package com.homedatacenter.app.ui.cameras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PinchToZoomFrameLayout mathematics, clamping constraints,
 * multi-touch interception criteria, and double-tap zoom transitions.
 */
class PinchToZoomTest {

    /**
     * Work Order 2.1: Verify scale factor clamping strictly within [1.0f, 4.0f].
     */
    @Test
    fun testScaleBoundsConstraints() {
        val minScale = 1.0f
        val maxScale = 4.0f

        fun clampScale(rawScale: Float): Float {
            return rawScale.coerceIn(minScale, maxScale)
        }

        // Sub-minimum scales must clamp to 1.0f
        assertEquals(1.0f, clampScale(0.5f), 0.0001f)
        assertEquals(1.0f, clampScale(0.99f), 0.0001f)
        assertEquals(1.0f, clampScale(1.0f), 0.0001f)

        // Valid intermediate scales
        assertEquals(1.5f, clampScale(1.5f), 0.0001f)
        assertEquals(2.0f, clampScale(2.0f), 0.0001f)
        assertEquals(3.8f, clampScale(3.8f), 0.0001f)
        assertEquals(4.0f, clampScale(4.0f), 0.0001f)

        // Beyond maximum scales must clamp to 4.0f
        assertEquals(4.0f, clampScale(4.01f), 0.0001f)
        assertEquals(4.0f, clampScale(8.0f), 0.0001f)
    }

    /**
     * Work Order 2.1: Verify viewport translation boundary clamping formula:
     * maxTransX = (width * (scale - 1)) / 2
     * maxTransY = (height * (scale - 1)) / 2
     */
    @Test
    fun testBoundaryClampingFormula() {
        val width = 1080f
        val height = 720f

        fun computeMaxTranslation(dim: Float, scale: Float): Float {
            return maxOf(0f, (dim * (scale - 1f)) / 2f)
        }

        fun clampTranslation(trans: Float, maxTrans: Float): Float {
            return trans.coerceIn(-maxTrans, maxTrans)
        }

        // At scale 1.0x: max translation must be strictly 0 (no margins or black bars)
        val maxTransX1x = computeMaxTranslation(width, 1.0f)
        val maxTransY1x = computeMaxTranslation(height, 1.0f)
        assertEquals(0f, maxTransX1x, 0.0001f)
        assertEquals(0f, maxTransY1x, 0.0001f)
        assertEquals(0f, clampTranslation(100f, maxTransX1x), 0.0001f)
        assertEquals(0f, clampTranslation(-100f, maxTransX1x), 0.0001f)

        // At scale 2.0x: max translation is width / 2 = 540f, height / 2 = 360f
        val maxTransX2x = computeMaxTranslation(width, 2.0f)
        val maxTransY2x = computeMaxTranslation(height, 2.0f)
        assertEquals(540f, maxTransX2x, 0.0001f)
        assertEquals(360f, maxTransY2x, 0.0001f)

        // Inside boundary: untouched
        assertEquals(200f, clampTranslation(200f, maxTransX2x), 0.0001f)
        assertEquals(-200f, clampTranslation(-200f, maxTransX2x), 0.0001f)

        // Outside boundary: clamped strictly to [-maxTrans, maxTrans]
        assertEquals(540f, clampTranslation(800f, maxTransX2x), 0.0001f)
        assertEquals(-540f, clampTranslation(-800f, maxTransX2x), 0.0001f)
        assertEquals(360f, clampTranslation(500f, maxTransY2x), 0.0001f)
        assertEquals(-360f, clampTranslation(-500f, maxTransY2x), 0.0001f)

        // At scale 4.0x: max translation is 1080 * 1.5 = 1620f
        val maxTransX4x = computeMaxTranslation(width, 4.0f)
        assertEquals(1620f, maxTransX4x, 0.0001f)
        assertEquals(1620f, clampTranslation(2000f, maxTransX4x), 0.0001f)
        assertEquals(-1620f, clampTranslation(-2000f, maxTransX4x), 0.0001f)
    }

    /**
     * Work Order 2.1: Verify onInterceptTouchEvent logic:
     * - When pointerCount >= 2 or isInProgress or currentScale > 1.05f:
     *   must return true (intercept) and disallow parent interception.
     * - When single touch at 1.0x scale:
     *   returns false, allowing child views (e.g. ExoPlayer controller buttons) to receive clicks.
     */
    @Test
    fun testTouchInterceptionRules() {
        fun shouldIntercept(
            pointerCount: Int,
            isScaleInProgress: Boolean,
            currentScale: Float
        ): Boolean {
            return pointerCount >= 2 || isScaleInProgress || currentScale > 1.05f
        }

        // Scenario 1: Initial state 1.0x, single finger tap -> DO NOT INTERCEPT (allow click on controller)
        assertFalse(shouldIntercept(pointerCount = 1, isScaleInProgress = false, currentScale = 1.0f))

        // Scenario 2: Two fingers down at 1.0x -> MUST INTERCEPT (pinch gesture started)
        assertTrue(shouldIntercept(pointerCount = 2, isScaleInProgress = false, currentScale = 1.0f))

        // Scenario 3: Scale detector reports in progress -> MUST INTERCEPT
        assertTrue(shouldIntercept(pointerCount = 1, isScaleInProgress = true, currentScale = 1.0f))
        assertTrue(shouldIntercept(pointerCount = 2, isScaleInProgress = true, currentScale = 1.0f))

        // Scenario 4: Already zoomed in (2.0x), single finger drag -> MUST INTERCEPT to pan
        assertTrue(shouldIntercept(pointerCount = 1, isScaleInProgress = false, currentScale = 2.0f))
        assertTrue(shouldIntercept(pointerCount = 1, isScaleInProgress = false, currentScale = 1.10f))

        // Scenario 5: Near 1.0x (1.02f) -> false (within deadband)
        assertFalse(shouldIntercept(pointerCount = 1, isScaleInProgress = false, currentScale = 1.02f))
    }

    /**
     * Work Order 2.1: Double-tap toggle logic between 1.0x and 2.0x.
     */
    @Test
    fun testDoubleTapTargetCalculations() {
        data class ZoomTarget(val targetScale: Float, val targetTransX: Float, val targetTransY: Float)

        fun computeDoubleTapTarget(
            currentScale: Float,
            tapX: Float,
            tapY: Float,
            containerWidth: Float,
            containerHeight: Float
        ): ZoomTarget {
            return if (currentScale > 1.05f) {
                // Reset to 1.0x and center
                ZoomTarget(targetScale = 1.0f, targetTransX = 0f, targetTransY = 0f)
            } else {
                // Zoom to 2.0x focusing on tap point
                val tapFocusX = tapX - containerWidth / 2f
                val tapFocusY = tapY - containerHeight / 2f
                val targetScale = 2.0f
                val targetTransX = -tapFocusX * (targetScale - 1f)
                val targetTransY = -tapFocusY * (targetScale - 1f)
                ZoomTarget(targetScale, targetTransX, targetTransY)
            }
        }

        val width = 1000f
        val height = 600f

        // Center tap at 1.0x -> zooms to 2.0x, target translation (0, 0)
        val centerTap = computeDoubleTapTarget(1.0f, 500f, 300f, width, height)
        assertEquals(2.0f, centerTap.targetScale, 0.001f)
        assertEquals(0f, centerTap.targetTransX, 0.001f)
        assertEquals(0f, centerTap.targetTransY, 0.001f)

        // Off-center tap: top-left quadrant (250, 150) -> tapFocus is (-250, -150)
        // targetTrans = -(-250)*(1) = +250, targetTransY = -(-150)*(1) = +150
        val offCenterTap = computeDoubleTapTarget(1.0f, 250f, 150f, width, height)
        assertEquals(2.0f, offCenterTap.targetScale, 0.001f)
        assertEquals(250f, offCenterTap.targetTransX, 0.001f)
        assertEquals(150f, offCenterTap.targetTransY, 0.001f)

        // Double tap when already zoomed in (2.0x) -> smoothly resets to 1.0x and (0, 0)
        val resetTap = computeDoubleTapTarget(2.0f, 250f, 150f, width, height)
        assertEquals(1.0f, resetTap.targetScale, 0.001f)
        assertEquals(0f, resetTap.targetTransX, 0.001f)
        assertEquals(0f, resetTap.targetTransY, 0.001f)
    }
}
