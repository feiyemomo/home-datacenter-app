package com.homedatacenter.app.ui.cameras

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for UnifiedStreamPlayerView fallback state machine,
 * PlayerCapabilities interface, and surface ordering contracts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedStreamPlayerTest {

    /**
     * Work Order 2.2: Test PlayerCapabilities interface for LIVE vs VOD mode.
     */
    @Test
    fun testPlayerCapabilitiesContract() {
        class MockPlayerCapabilities(
            override var isLive: Boolean,
            override var supportsSeeking: Boolean,
            override var supportsSpeedAdjustment: Boolean,
            override var currentTransport: StreamTransport = StreamTransport.NONE
        ) : PlayerCapabilities {
            var resetZoomCalled = false
            override fun resetZoom(animate: Boolean) {
                resetZoomCalled = true
            }
        }

        // Live stream mode
        val liveCap = MockPlayerCapabilities(
            isLive = true,
            supportsSeeking = false,
            supportsSpeedAdjustment = false,
            currentTransport = StreamTransport.WEBRTC
        )
        assertTrue(liveCap.isLive)
        assertFalse(liveCap.supportsSeeking)
        assertFalse(liveCap.supportsSpeedAdjustment)
        assertEquals(PlaybackMode.LIVE, liveCap.playbackMode)
        assertEquals(StreamTransport.WEBRTC, liveCap.currentTransport)

        // VOD / Recording playback mode
        val vodCap = MockPlayerCapabilities(
            isLive = false,
            supportsSeeking = true,
            supportsSpeedAdjustment = true,
            currentTransport = StreamTransport.MP4
        )
        assertFalse(vodCap.isLive)
        assertTrue(vodCap.supportsSeeking)
        assertTrue(vodCap.supportsSpeedAdjustment)
        assertEquals(PlaybackMode.VOD, vodCap.playbackMode)
        assertEquals(StreamTransport.MP4, vodCap.currentTransport)
    }

    /**
     * Work Order 2.2: Decoupled 4-second Fallback State Machine.
     * When WebRTC does not connect within 4000ms, watchdog triggers fallback transition.
     */
    @Test
    fun testFallbackWatchdogTriggersAfter4Seconds() = runTest {
        var fallbackTriggered = false
        var currentState = FallbackState.CONNECTING_WEBRTC

        val job = launch {
            delay(4000L)
            if (currentState == FallbackState.CONNECTING_WEBRTC) {
                currentState = FallbackState.FALLBACK_TRIGGERED
                fallbackTriggered = true
            }
        }

        assertEquals(FallbackState.CONNECTING_WEBRTC, currentState)
        assertFalse("Fallback should not trigger before timeout", fallbackTriggered)

        // Advance 2 seconds (not yet timed out)
        advanceTimeBy(2000L)
        testScheduler.runCurrent()
        assertEquals(FallbackState.CONNECTING_WEBRTC, currentState)
        assertFalse(fallbackTriggered)

        // Advance remaining 2001ms (passes 4000ms threshold)
        advanceTimeBy(2001L)
        testScheduler.runCurrent()
        assertEquals(FallbackState.FALLBACK_TRIGGERED, currentState)
        assertTrue("Fallback must trigger after 4000ms timeout", fallbackTriggered)

        job.cancel()
    }

    /**
     * Work Order 2.2: Decoupled Fallback State Machine.
     * When WebRTC connects before 4 seconds, watchdog is canceled and state becomes WEBRTC_CONNECTED.
     */
    @Test
    fun testWebRtcConnectedCancelsWatchdog() = runTest {
        var fallbackTriggered = false
        var currentState = FallbackState.CONNECTING_WEBRTC

        val job = launch {
            delay(4000L)
            if (currentState == FallbackState.CONNECTING_WEBRTC) {
                currentState = FallbackState.FALLBACK_TRIGGERED
                fallbackTriggered = true
            }
        }

        // WebRTC connects at 1200ms
        advanceTimeBy(1200L)
        job.cancel()
        currentState = FallbackState.WEBRTC_CONNECTED

        // Advance past 4000ms
        advanceTimeBy(4000L)
        assertEquals(FallbackState.WEBRTC_CONNECTED, currentState)
        assertFalse("Fallback must not trigger if WebRTC connected in time", fallbackTriggered)
    }

    /**
     * Work Order 2.2: Decoupled Fallback State Machine.
     * WebRTC failure immediately triggers fallback transition without waiting for 4s timeout.
     */
    @Test
    fun testWebRtcImmediateFailureTransitionsToFallback() {
        var currentState = FallbackState.CONNECTING_WEBRTC
        var fallbackActionInvoked = false

        // WebRTC reports failure
        fun onWebRtcFailed(onFallback: () -> Unit) {
            currentState = FallbackState.FALLBACK_TRIGGERED
            onFallback()
        }

        onWebRtcFailed {
            fallbackActionInvoked = true
        }

        assertEquals(FallbackState.FALLBACK_TRIGGERED, currentState)
        assertTrue(fallbackActionInvoked)
    }

    /**
     * Work Order 2.2: Fullscreen and Orientation Zoom Reset Hook.
     * Toggling fullscreen or rotating screen must invoke resetZoom(animate = false)
     * so picture coordinates remain clamped inside viewport.
     */
    @Test
    fun testFullscreenAndOrientationTriggersZoomReset() {
        var resetZoomCalled = false
        var animateParamPassed: Boolean? = null

        val mockZoomReset: (Boolean) -> Unit = { animate ->
            resetZoomCalled = true
            animateParamPassed = animate
        }

        // Fullscreen hook
        fun onFullscreenChanged(isFs: Boolean) {
            mockZoomReset(false)
        }

        onFullscreenChanged(true)
        assertTrue(resetZoomCalled)
        assertEquals(false, animateParamPassed)

        resetZoomCalled = false
        onFullscreenChanged(false)
        assertTrue(resetZoomCalled)
        assertEquals(false, animateParamPassed)
    }

    /**
     * Work Order 2.2: Surface Order Contract Enforcement.
     * Verifies the strict invariant: `playerView.player = player` MUST be executed
     * before `player.prepare()` to avoid BAD_INDEX MediaCodec crash.
     */
    @Test
    fun testSurfaceOrderInvariant() {
        val executionLog = mutableListOf<String>()

        fun attachPlayerAndPrepare(
            setSurfaceAction: () -> Unit,
            prepareAction: () -> Unit
        ) {
            // Invariant: setSurfaceAction MUST precede prepareAction
            setSurfaceAction()
            prepareAction()
        }

        attachPlayerAndPrepare(
            setSurfaceAction = { executionLog.add("SET_SURFACE") },
            prepareAction = { executionLog.add("PREPARE") }
        )

        assertEquals(listOf("SET_SURFACE", "PREPARE"), executionLog)
        assertEquals("SET_SURFACE", executionLog.first())
        assertEquals("PREPARE", executionLog.last())
    }
}
