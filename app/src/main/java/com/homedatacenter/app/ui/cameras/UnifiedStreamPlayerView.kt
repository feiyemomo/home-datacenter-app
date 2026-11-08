package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State machine states for dual-kernel video streaming and 4-second fallback.
 */
enum class FallbackState {
    IDLE,
    CONNECTING_WEBRTC,
    WEBRTC_CONNECTED,
    FALLBACK_TRIGGERED,
    FALLBACK_PLAYING,
    FAILED
}

/**
 * UnifiedStreamPlayerView — Composite dual-kernel player facade (v1.18.0).
 *
 * Requirements:
 * 1. Outermost layer is [PinchToZoomFrameLayout], preserving zoom scale and translation
 *    across WebRTC <-> ExoPlayer (MP4/HLS) kernel switches.
 * 2. Inner content hosts [SafeSurfaceViewRenderer] (WebRTC layer) and [StyledPlayerView]
 *    (ExoPlayer texture_view layer).
 * 3. Encapsulates and decouples the 4-second fallback state machine (WebRTC -> MP4/HLS).
 * 4. Enforces surface order rule: [playerView.player = player] MUST be set before calling [player.prepare()].
 * 5. Implements [PlayerCapabilities] interface (isLive, supportsSeeking, supportsSpeedAdjustment, resetZoom).
 * 6. Automatically calls [resetZoom(false)] on fullscreen transitions and orientation changes.
 */
class UnifiedStreamPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), PlayerCapabilities {

    // Active component references (bound via bindViews)
    lateinit var pinchToZoomLayout: PinchToZoomFrameLayout
        private set
    lateinit var playerView: StyledPlayerView
        private set
    lateinit var surfaceRenderer: SafeSurfaceViewRenderer
        private set
    var previewImageView: ImageView? = null
        private set
    var progressBar: ProgressBar? = null
        private set
    var errorTextView: TextView? = null
        private set
    var zoomBadgeTextView: TextView? = null
        private set
    var strategyTextView: TextView? = null
        private set

    // State machine properties
    var fallbackState: FallbackState = FallbackState.IDLE
        private set

    private var fallbackJob: Job? = null
    var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main)

    // PlayerCapabilities implementation
    override var isLive: Boolean = true
    override var supportsSeeking: Boolean = false
    override var supportsSpeedAdjustment: Boolean = false
    override var currentTransport: StreamTransport = StreamTransport.NONE

    override val isPlaying: Boolean
        get() = when (currentTransport) {
            StreamTransport.WEBRTC -> fallbackState == FallbackState.WEBRTC_CONNECTED
            StreamTransport.MP4, StreamTransport.HLS -> if (::playerView.isInitialized) playerView.player?.isPlaying == true else false
            StreamTransport.NONE -> false
        }

    override val currentPositionMs: Long
        get() = if (::playerView.isInitialized) playerView.player?.currentPosition ?: 0L else 0L

    override val durationMs: Long
        get() = if (::playerView.isInitialized) playerView.player?.duration ?: 0L else 0L

    var onFallbackTriggered: (() -> Unit)? = null
    var onStateChanged: ((FallbackState) -> Unit)? = null

    /**
     * Bind existing layout components (e.g. from ActivityCameraDetailBinding)
     * so that the facade can govern an externally inflated view hierarchy.
     */
    fun bindViews(
        pinchZoom: PinchToZoomFrameLayout,
        exoPlayerView: StyledPlayerView,
        renderer: SafeSurfaceViewRenderer,
        preview: ImageView? = null,
        progress: ProgressBar? = null,
        errorView: TextView? = null,
        zoomBadge: TextView? = null,
        strategyBadge: TextView? = null
    ) {
        pinchToZoomLayout = pinchZoom
        playerView = exoPlayerView
        surfaceRenderer = renderer
        previewImageView = preview
        progressBar = progress
        errorTextView = errorView
        zoomBadgeTextView = zoomBadge
        strategyTextView = strategyBadge

        setupZoomBadgeListener()
    }

    private fun setupZoomBadgeListener() {
        pinchToZoomLayout.onZoomChanged = { scale ->
            val badge = zoomBadgeTextView
            if (badge != null) {
                if (scale > 1.05f) {
                    badge.visibility = View.VISIBLE
                    badge.text = String.format(java.util.Locale.US, "%.1fx 双击复位", scale)
                } else {
                    badge.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Enforce ExoPlayer Surface Order Rule:
     * [playerView.player = player] MUST be executed BEFORE [player.prepare()].
     */
    fun attachPlayer(exoPlayer: ExoPlayer, autoPrepare: Boolean = true) {
        if (::playerView.isInitialized) {
            playerView.player = exoPlayer
        }
        if (autoPrepare) {
            exoPlayer.prepare()
        }
    }

    /**
     * Start the 4-second WebRTC fallback watchdog.
     * If WebRTC fails to connect within [timeoutMs], triggers [onFallbackTriggered]
     * to promote MP4/HLS fallback stream.
     */
    fun startFallbackWatchdog(
        timeoutMs: Long = 4000L,
        onFallback: (() -> Unit)? = null
    ) {
        cancelFallbackWatchdog()
        fallbackState = FallbackState.CONNECTING_WEBRTC
        currentTransport = StreamTransport.WEBRTC
        onStateChanged?.invoke(fallbackState)

        fallbackJob = coroutineScope.launch {
            delay(timeoutMs)
            if (fallbackState == FallbackState.CONNECTING_WEBRTC) {
                fallbackState = FallbackState.FALLBACK_TRIGGERED
                onStateChanged?.invoke(fallbackState)
                onFallback?.invoke()
                this@UnifiedStreamPlayerView.onFallbackTriggered?.invoke()
            }
        }
    }

    /**
     * Cancel any active fallback watchdog timer.
     */
    fun cancelFallbackWatchdog() {
        fallbackJob?.cancel()
        fallbackJob = null
    }

    /**
     * Notify that WebRTC connected successfully, canceling the fallback watchdog.
     * Note: Surface visibility is switched only after first frame is actually rendered.
     */
    fun onWebRtcConnected() {
        cancelFallbackWatchdog()
        fallbackState = FallbackState.WEBRTC_CONNECTED
        currentTransport = StreamTransport.WEBRTC
        errorTextView?.visibility = View.GONE
        onStateChanged?.invoke(fallbackState)
    }

    /**
     * Switch video surface visibility only after first frame is actually rendered.
     */
    fun onWebRtcFirstFrameRendered() {
        if (::surfaceRenderer.isInitialized) {
            surfaceRenderer.visibility = View.VISIBLE
        }
        if (::playerView.isInitialized) {
            playerView.visibility = View.GONE
        }
        progressBar?.visibility = View.GONE
        previewImageView?.visibility = View.GONE
    }

    /**
     * Notify that WebRTC failed, triggering immediate fallback transition.
     */
    fun onWebRtcFailed(onFallback: (() -> Unit)? = null) {
        cancelFallbackWatchdog()
        fallbackState = FallbackState.FALLBACK_TRIGGERED
        onStateChanged?.invoke(fallbackState)
        onFallback?.invoke()
        this@UnifiedStreamPlayerView.onFallbackTriggered?.invoke()
    }

    /**
     * Mark fallback player (MP4/HLS) as actively playing.
     */
    fun onFallbackActive(transport: StreamTransport) {
        fallbackState = FallbackState.FALLBACK_PLAYING
        currentTransport = transport
        if (::surfaceRenderer.isInitialized) {
            surfaceRenderer.visibility = View.GONE
        }
        if (::playerView.isInitialized) {
            playerView.visibility = View.VISIBLE
        }
        errorTextView?.visibility = View.GONE
        onStateChanged?.invoke(fallbackState)
    }

    /**
     * Update stream capabilities for LIVE vs VOD mode.
     */
    fun setPlaybackMode(mode: PlaybackMode) {
        when (mode) {
            PlaybackMode.LIVE -> {
                isLive = true
                supportsSeeking = false
                supportsSpeedAdjustment = false
            }
            PlaybackMode.VOD -> {
                isLive = false
                supportsSeeking = true
                supportsSpeedAdjustment = true
            }
        }
    }

    /**
     * Fullscreen change hook: automatically reset zoom to avoid clipping/matrix distortion.
     */
    fun onFullscreenChanged(isFullscreen: Boolean) {
        resetZoom(animate = false)
    }

    /**
     * Orientation change hook: automatically reset zoom to prevent viewport clipping.
     */
    public override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resetZoom(animate = false)
    }

    // PlayerCapabilities delegators
    override fun play() {
        if (::playerView.isInitialized) {
            playerView.player?.play()
        }
    }

    override fun pause() {
        if (::playerView.isInitialized) {
            playerView.player?.pause()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (supportsSeeking && ::playerView.isInitialized) {
            playerView.player?.seekTo(positionMs)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (supportsSpeedAdjustment && ::playerView.isInitialized) {
            playerView.player?.setPlaybackSpeed(speed)
        }
    }

    override fun setAudioEnabled(enabled: Boolean) {
        if (::playerView.isInitialized) {
            playerView.player?.volume = if (enabled) 1.0f else 0.0f
        }
    }

    override fun resetZoom(animate: Boolean) {
        if (::pinchToZoomLayout.isInitialized) {
            pinchToZoomLayout.resetZoom(animate)
        }
    }

    /**
     * Release references and clean up fallback jobs.
     */
    fun release() {
        cancelFallbackWatchdog()
        fallbackState = FallbackState.IDLE
        if (::playerView.isInitialized) {
            playerView.player = null
        }
    }
}
