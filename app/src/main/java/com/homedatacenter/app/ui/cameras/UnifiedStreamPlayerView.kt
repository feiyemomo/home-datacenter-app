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

    // Internal primary components
    private var internalPinchZoom: PinchToZoomFrameLayout
    private var internalContentContainer: FrameLayout
    private var internalPlayerView: StyledPlayerView
    private var internalSurfaceRenderer: SafeSurfaceViewRenderer
    private var internalPreviewImageView: ImageView
    private var internalProgressBar: ProgressBar
    private var internalErrorTextView: TextView
    private var internalZoomBadgeTextView: TextView
    private var internalStrategyTextView: TextView

    // Active component references (default to internal, or bound via bindViews)
    var pinchToZoomLayout: PinchToZoomFrameLayout private set
    var playerView: StyledPlayerView private set
    var surfaceRenderer: SafeSurfaceViewRenderer private set
    var previewImageView: ImageView? private set
    var progressBar: ProgressBar? private set
    var errorTextView: TextView? private set
    var zoomBadgeTextView: TextView? private set
    var strategyTextView: TextView? private set

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
            StreamTransport.MP4, StreamTransport.HLS -> playerView.player?.isPlaying == true
            StreamTransport.NONE -> false
        }

    override val currentPositionMs: Long
        get() = playerView.player?.currentPosition ?: 0L

    override val durationMs: Long
        get() = playerView.player?.duration ?: 0L

    var onFallbackTriggered: (() -> Unit)? = null
    var onStateChanged: ((FallbackState) -> Unit)? = null

    init {
        // Construct outermost PinchToZoomFrameLayout
        internalPinchZoom = PinchToZoomFrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Inner zoom content container (transformed by pinch-to-zoom)
        internalContentContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // ExoPlayer StyledPlayerView with fit mode
        internalPlayerView = StyledPlayerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        // WebRTC SafeSurfaceViewRenderer
        internalSurfaceRenderer = SafeSurfaceViewRenderer(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }

        // Preview frame
        internalPreviewImageView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Loading spinner
        internalProgressBar = ProgressBar(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }

        // Error message text view
        internalErrorTextView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }

        // Zoom badge
        internalZoomBadgeTextView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setMargins(0, 16, 0, 0)
            }
            visibility = View.GONE
        }

        // Stream strategy badge
        internalStrategyTextView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(16, 16, 0, 0)
            }
            visibility = View.GONE
        }

        // Assemble view hierarchy
        internalContentContainer.addView(internalPlayerView)
        internalContentContainer.addView(internalSurfaceRenderer)
        internalContentContainer.addView(internalPreviewImageView)
        internalPinchZoom.addView(internalContentContainer)

        addView(internalPinchZoom)
        addView(internalProgressBar)
        addView(internalErrorTextView)
        addView(internalZoomBadgeTextView)
        addView(internalStrategyTextView)

        // Point active references to internal views
        pinchToZoomLayout = internalPinchZoom
        playerView = internalPlayerView
        surfaceRenderer = internalSurfaceRenderer
        previewImageView = internalPreviewImageView
        progressBar = internalProgressBar
        errorTextView = internalErrorTextView
        zoomBadgeTextView = internalZoomBadgeTextView
        strategyTextView = internalStrategyTextView

        setupZoomBadgeListener()
    }

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
        playerView.player = exoPlayer
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
     */
    fun onWebRtcConnected() {
        cancelFallbackWatchdog()
        fallbackState = FallbackState.WEBRTC_CONNECTED
        currentTransport = StreamTransport.WEBRTC
        surfaceRenderer.visibility = View.VISIBLE
        playerView.visibility = View.GONE
        errorTextView?.visibility = View.GONE
        onStateChanged?.invoke(fallbackState)
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
        surfaceRenderer.visibility = View.GONE
        playerView.visibility = View.VISIBLE
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
        playerView.player?.play()
    }

    override fun pause() {
        playerView.player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (supportsSeeking) {
            playerView.player?.seekTo(positionMs)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (supportsSpeedAdjustment) {
            playerView.player?.setPlaybackSpeed(speed)
        }
    }

    override fun setAudioEnabled(enabled: Boolean) {
        playerView.player?.volume = if (enabled) 1.0f else 0.0f
    }

    override fun resetZoom(animate: Boolean) {
        pinchToZoomLayout.resetZoom(animate)
    }

    /**
     * Release references and clean up fallback jobs.
     */
    fun release() {
        cancelFallbackWatchdog()
        fallbackState = FallbackState.IDLE
        playerView.player = null
    }
}
