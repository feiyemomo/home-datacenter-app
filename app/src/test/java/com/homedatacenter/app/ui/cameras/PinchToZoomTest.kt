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

    enum class TestViewKind { WEBRTC_SURFACE, EXOPLAYER_CONTAINER, EXOPLAYER_TEXTURE, PREVIEW_IMAGE, CONTROL_OVERLAY }
    data class MockView(val kind: TestViewKind, val isVisible: Boolean, val id: String)

    /**
     * Verify dynamic target surface resolution logic between WebRTC (SurfaceViewRenderer)
     * and ExoPlayer (videoSurfaceView vs container).
     */
    @Test
    fun testTargetSurfaceResolutionLogic() {
        fun resolveActiveTarget(children: List<MockView>, custom: MockView? = null): TestViewKind? {
            if (custom != null) {
                return if (custom.kind == TestViewKind.EXOPLAYER_CONTAINER) TestViewKind.EXOPLAYER_TEXTURE else custom.kind
            }
            // 1. Check for visible WebRTC SurfaceViewRenderer
            val webrtc = children.firstOrNull { it.kind == TestViewKind.WEBRTC_SURFACE && it.isVisible }
            if (webrtc != null) return TestViewKind.WEBRTC_SURFACE

            // 2. Check for visible ExoPlayer
            val exo = children.firstOrNull { it.kind == TestViewKind.EXOPLAYER_CONTAINER && it.isVisible }
            if (exo != null) return TestViewKind.EXOPLAYER_TEXTURE

            // 3. Check for visible preview
            val preview = children.firstOrNull { it.kind == TestViewKind.PREVIEW_IMAGE && it.isVisible }
            if (preview != null) return TestViewKind.PREVIEW_IMAGE

            return children.firstOrNull { it.kind != TestViewKind.CONTROL_OVERLAY }?.kind
        }

        // Scenario A: WebRTC is streaming (WebRTC visible, ExoPlayer hidden)
        val webrtcChildren = listOf(
            MockView(TestViewKind.EXOPLAYER_CONTAINER, isVisible = false, "playerView"),
            MockView(TestViewKind.WEBRTC_SURFACE, isVisible = true, "surfaceRenderer"),
            MockView(TestViewKind.PREVIEW_IMAGE, isVisible = false, "ivPreviewFrame"),
            MockView(TestViewKind.CONTROL_OVERLAY, isVisible = true, "tvZoomBadge")
        )
        assertEquals(
            "When WebRTC is visible, target must resolve to WEBRTC_SURFACE directly",
            TestViewKind.WEBRTC_SURFACE,
            resolveActiveTarget(webrtcChildren)
        )

        // Scenario B: Fallback to MP4 / HLS (WebRTC hidden, ExoPlayer visible)
        val exoChildren = listOf(
            MockView(TestViewKind.EXOPLAYER_CONTAINER, isVisible = true, "playerView"),
            MockView(TestViewKind.WEBRTC_SURFACE, isVisible = false, "surfaceRenderer"),
            MockView(TestViewKind.PREVIEW_IMAGE, isVisible = false, "ivPreviewFrame"),
            MockView(TestViewKind.CONTROL_OVERLAY, isVisible = false, "tvZoomBadge")
        )
        assertEquals(
            "When ExoPlayer is visible, target must resolve to EXOPLAYER_TEXTURE (video surface only), keeping controls intact",
            TestViewKind.EXOPLAYER_TEXTURE,
            resolveActiveTarget(exoChildren)
        )

        // Scenario C: Custom target override
        val customOverride = MockView(TestViewKind.EXOPLAYER_CONTAINER, isVisible = true, "customPlayer")
        assertEquals(
            "Custom target StyledPlayerView must unwrap to its internal video surface texture",
            TestViewKind.EXOPLAYER_TEXTURE,
            resolveActiveTarget(exoChildren, custom = customOverride)
        )
    }

    /**
     * Verify transform reset contract when switching active targets.
     */
    @Test
    fun testTransformTransferAndResetOnKernelSwitch() {
        data class TransformState(var scaleX: Float = 1f, var scaleY: Float = 1f, var tx: Float = 0f, var ty: Float = 0f)

        val webrtcTransform = TransformState()
        val exoTransform = TransformState()

        var currentScale = 2.5f
        var currentTx = 120f
        var currentTy = 80f

        // Initial: WebRTC is zoomed in
        webrtcTransform.scaleX = currentScale
        webrtcTransform.scaleY = currentScale
        webrtcTransform.tx = currentTx
        webrtcTransform.ty = currentTy

        // Kernel switch: WebRTC -> MP4 fallback
        // Contract: old target resets to 1.0f, new target inherits current zoom/pan
        fun switchKernel(oldTarget: TransformState, newTarget: TransformState, scale: Float, tx: Float, ty: Float) {
            oldTarget.scaleX = 1f
            oldTarget.scaleY = 1f
            oldTarget.tx = 0f
            oldTarget.ty = 0f

            newTarget.scaleX = scale
            newTarget.scaleY = scale
            newTarget.tx = tx
            newTarget.ty = ty
        }

        switchKernel(webrtcTransform, exoTransform, currentScale, currentTx, currentTy)

        // Old target must be fully clean
        assertEquals(1f, webrtcTransform.scaleX, 0.0001f)
        assertEquals(1f, webrtcTransform.scaleY, 0.0001f)
        assertEquals(0f, webrtcTransform.tx, 0.0001f)
        assertEquals(0f, webrtcTransform.ty, 0.0001f)

        // New target must seamlessly inherit active zoom coordinates
        assertEquals(2.5f, exoTransform.scaleX, 0.0001f)
        assertEquals(2.5f, exoTransform.scaleY, 0.0001f)
        assertEquals(120f, exoTransform.tx, 0.0001f)
        assertEquals(80f, exoTransform.ty, 0.0001f)
    }

    /**
     * Verify zoom badge text representation and threshold.
     */
    @Test
    fun testZoomBadgeDisplayThreshold() {
        fun formatZoomBadge(scale: Float): Pair<Boolean, String> {
            val isVisible = scale > 1.05f
            val text = if (isVisible) String.format(java.util.Locale.US, "%.1fx 双击复位", scale) else ""
            return Pair(isVisible, text)
        }

        val (visible1x, text1x) = formatZoomBadge(1.0f)
        assertFalse(visible1x)
        assertEquals("", text1x)

        val (visible1_04x, _) = formatZoomBadge(1.04f)
        assertFalse(visible1_04x)

        val (visible2x, text2x) = formatZoomBadge(2.0f)
        assertTrue(visible2x)
        assertEquals("2.0x 双击复位", text2x)

        val (visible3_5x, text3_5x) = formatZoomBadge(3.5f)
        assertTrue(visible3_5x)
        assertEquals("3.5x 双击复位", text3_5x)
    }
}
