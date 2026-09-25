package com.homedatacenter.app.ui.cameras

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.CameraPreset
import com.homedatacenter.app.data.model.IceConfig
import com.homedatacenter.app.databinding.ActivityCameraDetailBinding
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.util.DecoderSupport
import com.homedatacenter.app.util.ExoPlayerRendererFactory
import com.homedatacenter.app.util.PlayerFullscreenHelper
import com.homedatacenter.app.util.WebRtcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Camera control screen: live video, PTZ, presets, codec, recording
 * plan, audio toggle, delete camera. Reachable from a [CameraCard]
 * tap (v1.5.2 redesign).
 *
 * The activity owns the ExoPlayer instance — inline playback was
 * moved out of the camera list so scrolling stays cheap (no per-row
 * MediaCodec allocations). Live stream uses MP4 (fMP4 via home-api
 * proxy) as primary and HLS as fallback, matching the previous inline
 * playback behavior.
 *
 * Role-based visibility:
 *  - Non-admin users can read the camera info and presets list, and
 *    trigger PTZ movements (PTZ is admin-gated on the server side, so
 *    a non-admin pressing the buttons will receive 403 — we hide the
 *    buttons client-side to avoid confusion, but server enforcement
 *    remains authoritative).
 *  - Admin users get the full control surface: audio/recording/codec
 *    toggles, preset add/delete, delete camera.
 *
 * The activity reads the initial camera from [EXTRA_CAMERA_JSON] (the
 * cached Camera object serialized as JSON). Mutations call the
 * repository and re-fetch the camera to refresh the UI rather than
 * mutating local state blindly — that way the displayed state always
 * matches what the server persisted.
 */
class CameraDetailActivity : AppCompatActivity() {

    private lateinit var _binding: ActivityCameraDetailBinding
    private val binding get() = _binding
    private lateinit var container: AppContainer

    private var camera: Camera? = null
    private var isAdmin: Boolean = false
    private var presets: List<CameraPreset> = emptyList()
    private lateinit var presetAdapter: PresetListAdapter

    // Live stream playback state. The player is created on demand
    // and released in onPause/onDestroy to free the MediaCodec.
    private var player: ExoPlayer? = null
    // Pre-prepared ExoPlayer used as a fast fallback when WebRTC
    // fails. Created with playWhenReady=false in prepareFallbackPlayer
    // (called at the start of startWebRtcStream) so that on WebRTC
    // onError the fallback can be promoted to the main `player` slot
    // and start playing immediately, skipping the ExoPlayer cold-start
    // delay. Released on WebRTC success (onConnected) and in
    // releaseExoPlayerOnly.
    private var fallbackPlayer: ExoPlayer? = null
    private var triedWebRtc = false
    private var triedMp4 = false
    private var triedHls = false
    private var audioEnabled = true
    private var recordingsDialog: RecordingsDialog? = null
    private var alertsDialog: AlertsDialog? = null
    private var fullscreenHelper: PlayerFullscreenHelper? = null

    private val recordAudioLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            android.widget.Toast.makeText(this, "麦克风权限已获取，请长按开始对讲", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(this, "需开启麦克风权限以使用语音对讲功能", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // WebRTC live stream client (v1.5.3 primary path). Lazily
    // initialized in onCreate after the camera is known; shutdown
    // in onDestroy releases the PeerConnectionFactory + EGL context.
    private var webRtcClient: WebRtcClient? = null
    // True while a WebRTC attempt is in-flight — prevents the
    // reload button from triggering overlapping offers.
    private var webRtcInProgress = false
    // v1.5.8: WebRTC live stream control state. Pause freezes the
    // last rendered frame by disabling the sink; mute toggles the
    // audio track's enabled state. Both are local-only — the
    // PeerConnection stays alive so resume is instant.
    private var webRtcPaused = false
    private var webRtcMuted = false
    // Cached ICE config from /api/v1/cameras/ice — fetched once
    // per activity instance (re-fetched on retry if null).
    private var cachedIceConfig: IceConfig? = null

    // v1.10.7: self-healing network retry state for remote/tunnel streams
    private var streamRetryCount = 0
    private var streamRetryJob: kotlinx.coroutines.Job? = null
    private val maxStreamRetries = 3
    private var isBackNavigating = false
    private var wasInPipMode = false
    internal var isLivePausedForDialog = false

    private data class PipInsetState(
        val view: View,
        val fitsSystemWindows: Boolean,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
    )
    private val savedAncestorPipStates = mutableListOf<PipInsetState>()

    private fun handleBackPress() {
        if (isBackNavigating) return

        // 1. If RecordingsDialog is showing, delegate back press to it
        val recDialog = recordingsDialog
        if (recDialog != null && recDialog.isShowing) {
            if (recDialog.onBackPressedCustom()) {
                return
            }
            recDialog.dismiss()
            recordingsDialog = null
            return
        }

        // 2. If AlertsDialog is showing, delegate back press to it
        val altDialog = alertsDialog
        if (altDialog != null && altDialog.isShowing) {
            if (altDialog.onBackPressedCustom()) {
                return
            }
            altDialog.dismiss()
            alertsDialog = null
            return
        }

        // 3. If in fullscreen mode, exit fullscreen first
        if (fullscreenHelper?.isFullscreen == true) {
            fullscreenHelper?.exitFullscreen()
            return
        }

        // 4. Truly exiting CameraDetailActivity
        isBackNavigating = true
        if (isTaskRoot) {
            val intent = Intent(this, com.homedatacenter.app.ui.main.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityCameraDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container
        isAdmin = container.prefsManager.isAdmin

        binding.toolbar.setNavigationOnClickListener { handleBackPress() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
        binding.toolbar.inflateMenu(R.menu.menu_camera_detail)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_pip -> {
                    enterPipMode()
                    true
                }
                else -> false
            }
        }

        // Deserialize the camera passed in via Intent extra.
        val cameraJson = intent.getStringExtra(EXTRA_CAMERA_JSON)
        if (cameraJson.isNullOrEmpty()) {
            finish()
            return
        }
        camera = try {
            NetworkFactory.json.decodeFromString(Camera.serializer(), cameraJson)
        } catch (_: Exception) {
            null
        }
        if (camera == null) {
            finish()
            return
        }

        setupHeader()
        setupVideo()
        setupActions()
        setupTalkback()
        setupPinchZoom()
        setupPtz()
        setupPresets()
        setupSettings()

        loadPresets()

        // v1.5.13: revert the v1.5.12 RECORD_AUDIO permission
        // request. WebRTC recvonly doesn't call AudioRecord.startRecording()
        // so RECORD_AUDIO isn't required; only MODIFY_AUDIO_SETTINGS
        // v1.12.12: If launched with an initial timestamp (e.g. alert click "查看录像"),
        // do NOT start live stream or load preview frame in background!
        // Immediately pause live playback and show the recordings dialog to prevent background audio leaks.
        val initialTs = intent.getLongExtra(EXTRA_INITIAL_TIMESTAMP, 0L)
        if (initialTs > 0L) {
            isLivePausedForDialog = true
            binding.root.post {
                if (!isFinishing) showRecordings(initialTs)
            }
        } else {
            startPlayback()
            loadPreviewFrame()
        }
    }

    fun enterPipMode(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
            val rect = android.graphics.Rect()
            binding.videoContainer.getGlobalVisibleRect(rect)
            if (!rect.isEmpty) {
                val targetRatio = 16f / 9f
                val currentRatio = rect.width().toFloat() / rect.height().toFloat()
                val hintRect = if (currentRatio > targetRatio) {
                    val newWidth = (rect.height() * targetRatio).toInt()
                    val offset = (rect.width() - newWidth) / 2
                    android.graphics.Rect(rect.left + offset, rect.top, rect.left + offset + newWidth, rect.bottom)
                } else {
                    val newHeight = (rect.width() / targetRatio).toInt()
                    val offset = (rect.height() - newHeight) / 2
                    android.graphics.Rect(rect.left, rect.top + offset, rect.right, rect.top + offset + newHeight)
                }
                builder.setSourceRectHint(hintRect)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            return enterPictureInPictureMode(builder.build())
        }
        return false
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val aspectRatio = Rational(16, 9)
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                val rect = android.graphics.Rect()
                binding.videoContainer.getGlobalVisibleRect(rect)
                if (!rect.isEmpty) {
                    val targetRatio = 16f / 9f
                    val currentRatio = rect.width().toFloat() / rect.height().toFloat()
                    val hintRect = if (currentRatio > targetRatio) {
                        val newWidth = (rect.height() * targetRatio).toInt()
                        val offset = (rect.width() - newWidth) / 2
                        android.graphics.Rect(rect.left + offset, rect.top, rect.left + offset + newWidth, rect.bottom)
                    } else {
                        val newHeight = (rect.width() / targetRatio).toInt()
                        val offset = (rect.height() - newHeight) / 2
                        android.graphics.Rect(rect.left, rect.top + offset, rect.right, rect.top + offset + newHeight)
                    }
                    builder.setSourceRectHint(hintRect)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(isPlaybackActive())
                }
                setPictureInPictureParams(builder.build())
            } catch (e: Exception) {
                android.util.Log.w(TAG, "updatePipParams failed: ${e.message}")
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isBackNavigating || isFinishing) {
            return
        }
        if (isPlaybackActive()) {
            enterPipMode()
        }
    }

    private fun isPlaybackActive(): Boolean {
        return (webRtcClient != null && (webRtcClient?.hasActiveStream() == true || webRtcInProgress || binding.surfaceRenderer.visibility == View.VISIBLE)) ||
                (player != null && (player?.isPlaying == true || player?.playbackState == Player.STATE_READY || player?.playbackState == Player.STATE_BUFFERING))
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val parent = binding.videoContainer.parent as? ViewGroup
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.id != binding.videoContainer.id) {
                    child.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
                }
            }
        }
        if (isInPictureInPictureMode) {
            wasInPipMode = true
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.decorView.background = null
            WindowCompat.setDecorFitsSystemWindows(window, false)
            savedAncestorPipStates.clear()
            var node: View? = binding.videoContainer
            val rootView = binding.root.rootView
            while (node != null && node !== rootView) {
                savedAncestorPipStates.add(
                    PipInsetState(
                        view = node,
                        fitsSystemWindows = node.fitsSystemWindows,
                        paddingLeft = node.paddingLeft,
                        paddingTop = node.paddingTop,
                        paddingRight = node.paddingRight,
                        paddingBottom = node.paddingBottom,
                    )
                )
                node.fitsSystemWindows = false
                node.setPadding(0, 0, 0, 0)
                node = node.parent as? View
            }
            rootView.fitsSystemWindows = false
            rootView.setPadding(0, 0, 0, 0)

            binding.root.scrollTo(0, 0)
            binding.root.background = null
            (binding.videoContainer.parent as? ViewGroup)?.let { p ->
                p.background = null
                val plp = p.layoutParams
                plp.width = ViewGroup.LayoutParams.MATCH_PARENT
                plp.height = ViewGroup.LayoutParams.MATCH_PARENT
                p.layoutParams = plp
            }
            binding.videoContainer.background = null
            binding.toolbar.visibility = View.GONE
            binding.webRtcControls.visibility = View.GONE
            binding.tvStreamStrategy.visibility = View.GONE
            binding.tvHlsNotice.visibility = View.GONE
            binding.tvVideoError.visibility = View.GONE
            binding.ivPreviewFrame.visibility = View.GONE
            binding.playerView.useController = false

            val lp = binding.videoContainer.layoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.videoContainer.layoutParams = lp

            val playerLp = binding.playerView.layoutParams
            playerLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            playerLp.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.playerView.layoutParams = playerLp

            val rendererLp = binding.surfaceRenderer.layoutParams
            rendererLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            rendererLp.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.surfaceRenderer.layoutParams = rendererLp

            binding.videoContainer.requestLayout()
            binding.surfaceRenderer.requestLayout()

            binding.surfaceRenderer.post {
                val client = webRtcClient
                if (client != null && (client.hasActiveStream() || binding.surfaceRenderer.visibility == View.VISIBLE)) {
                    client.reattachVideoTrack(binding.surfaceRenderer)
                }
            }
        } else {
            window.setBackgroundDrawableResource(R.color.bg_primary)
            // Immediately stop audio when leaving PiP to eliminate any audio residue
            webRtcClient?.stopPlayoutImmediately()
            webRtcClient?.setAudioEnabled(false)
            player?.pause()
            player?.volume = 0f
            player?.playWhenReady = false
            fallbackPlayer?.stop()
            fallbackPlayer?.release()
            fallbackPlayer = null

            if (isFinishing || isDestroyed) {
                try { binding.surfaceRenderer.release() } catch (_: Exception) {}
                try { webRtcClient?.release() } catch (_: Exception) {}
                releaseExoPlayerOnly()
                return
            }

            if (wasInPipMode) {
                // If leaving PiP, check whether the user dismissed the PiP window or expanded it.
                // When dismissed by user, Android stops foregrounding the activity and transitions to STOPPED.
                // Use Handler(Looper.getMainLooper()) instead of View.post because if the window was dismissed,
                // binding.root is detached from WindowManager and View.post will never execute!
                val mainHandler = Handler(Looper.getMainLooper())
                val finishCheck = Runnable {
                    if (!isFinishing && !isDestroyed && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        android.util.Log.d(TAG, "PiP dismissed by user (activity not resumed): finishing")
                        webRtcClient?.stopPlayoutImmediately()
                        try { binding.surfaceRenderer.release() } catch (_: Exception) {}
                        try { webRtcClient?.release() } catch (_: Exception) {}
                        releaseExoPlayerOnly()
                        finish()
                    }
                }
                mainHandler.post(finishCheck)
                mainHandler.postDelayed(finishCheck, 250)
            }

            WindowCompat.setDecorFitsSystemWindows(window, true)
            savedAncestorPipStates.asReversed().forEach { state ->
                state.view.fitsSystemWindows = state.fitsSystemWindows
                state.view.setPadding(
                    state.paddingLeft,
                    state.paddingTop,
                    state.paddingRight,
                    state.paddingBottom,
                )
            }
            savedAncestorPipStates.clear()

            binding.root.setBackgroundResource(R.color.bg_primary)
            (binding.videoContainer.parent as? ViewGroup)?.let { p ->
                val plp = p.layoutParams
                plp.width = ViewGroup.LayoutParams.MATCH_PARENT
                plp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                p.layoutParams = plp
            }
            binding.videoContainer.setBackgroundResource(R.color.card_bg)
            binding.toolbar.visibility = View.VISIBLE
            binding.tvStreamStrategy.visibility =
                if (binding.tvStreamStrategy.text.isNotEmpty()) View.VISIBLE else View.GONE
            binding.playerView.useController = true

            val density = resources.displayMetrics.density
            val defaultHeight = (200 * density).toInt()
            val lp = binding.videoContainer.layoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.videoContainer.layoutParams = lp

            val playerLp = binding.playerView.layoutParams
            playerLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            playerLp.height = defaultHeight
            binding.playerView.layoutParams = playerLp

            val rendererLp = binding.surfaceRenderer.layoutParams
            rendererLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            rendererLp.height = defaultHeight
            binding.surfaceRenderer.layoutParams = rendererLp

            if (binding.surfaceRenderer.visibility == View.VISIBLE) {
                binding.webRtcControls.visibility = View.VISIBLE
            }

            binding.videoContainer.requestLayout()
            binding.surfaceRenderer.requestLayout()
        }
    }

    override fun onPause() {
        super.onPause()
        // If in PiP mode, the floating window is still actively playing and visible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode && !isFinishing) {
            return
        }
        streamRetryJob?.cancel()
        webRtcClient?.stopPlayoutImmediately()
        webRtcClient?.setAudioEnabled(false)
        player?.pause()
        player?.volume = 0f
        player?.playWhenReady = false
        fallbackPlayer?.stop()
        releaseExoPlayerOnly()
        if (wasInPipMode || isFinishing) {
            try { binding.surfaceRenderer.release() } catch (_: Exception) {}
            try { webRtcClient?.release() } catch (_: Exception) {}
            if (!isFinishing) {
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // No longer in foreground and invisible on screen: stop playback and release WebRTC to prevent audio leaks
        val shouldFinish = wasInPipMode || isFinishing
        streamRetryJob?.cancel()
        webRtcClient?.stopPlayoutImmediately()
        webRtcClient?.setAudioEnabled(false)
        player?.pause()
        player?.volume = 0f
        player?.playWhenReady = false
        fallbackPlayer?.stop()
        try { binding.surfaceRenderer.release() } catch (_: Exception) {}
        try { webRtcClient?.release() } catch (_: Exception) {}
        releaseExoPlayerOnly()
        if (shouldFinish && !isFinishing) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        wasInPipMode = false
        // If live stream is paused because a dialog (recordings/alerts) is open, do not resume yet
        if (recordingsDialog?.isShowing == true || alertsDialog?.isShowing == true) {
            return
        }
        try { binding.surfaceRenderer.release() } catch (_: Exception) {}
        val client = webRtcClient
        if (client != null && (client.hasActiveStream() || binding.surfaceRenderer.visibility == View.VISIBLE)) {
            try {
                binding.surfaceRenderer.init(client.eglBase.eglBaseContext, null)
                binding.surfaceRenderer.setMirror(false)
                binding.surfaceRenderer.setScalingType(
                    RendererCommon.ScalingType.SCALE_ASPECT_FILL
                )
                client.reattachVideoTrack(binding.surfaceRenderer)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "WebRTC resume re-attach failed: ${e.message}")
            }
            if (audioEnabled && !webRtcMuted) {
                client.setAudioEnabled(true)
            }
        } else if (!isPlaybackActive() && !isLivePausedForDialog) {
            startPlayback()
        } else {
            player?.let { p ->
                p.volume = if (audioEnabled) 1.0f else 0.0f
                p.playWhenReady = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamRetryJob?.cancel()
        streamRetryJob = null
        webRtcClient?.stopPlayoutImmediately()
        webRtcClient?.setAudioEnabled(false)
        releaseExoPlayerOnly()
        fullscreenHelper?.release()
        recordingsDialog?.dismiss()
        alertsDialog?.dismiss()
        recordingsDialog = null
        alertsDialog = null
        try { binding.surfaceRenderer.release() } catch (_: Exception) {}
        try { webRtcClient?.release() } catch (_: Exception) {}
    }

    /**
     * v1.6.10: releases only ExoPlayer + WebRTC control flags. Does
     * NOT touch the WebRTC PeerConnection. Called from onPause so
     * the PeerConnection can survive across activity lifecycle.
     */
    private fun releaseExoPlayerOnly() {
        val old = player
        player = null
        binding.playerView.player = null
        old?.release()
        // Also release the pre-prepared fallback player if it's still
        // waiting (not yet promoted). Once promoted it's tracked by
        // `player` above. Covers releasePlayer / onPause / onDestroy.
        val fb = fallbackPlayer
        fallbackPlayer = null
        fb?.release()
        // Reset fallback ladder flags so reload retries WebRTC first.
        triedMp4 = false
        triedHls = false
        // Hide WebRTC-only overlays so they don't linger during reload.
        binding.btnWebRtcFullscreen.visibility = View.GONE
        binding.webRtcControls.visibility = View.GONE
    }

    private fun setupHeader() {
        val cam = camera ?: return
        val statusText = if (cam.isOnline) {
            getString(R.string.camera_online)
        } else {
            getString(R.string.camera_offline)
        }
        val statusColor = getColor(if (cam.isOnline) R.color.online else R.color.offline)
        binding.toolbar.title = cam.name
        binding.toolbar.subtitle = buildString {
            append(statusText)
            if (cam.vendor.isNotBlank()) {
                append(" · ")
                append(cam.vendor)
            }
            if (cam.host.isNotBlank()) {
                append(" · ")
                append(cam.host)
            }
            if (cam.codec.isNotBlank()) {
                append(" · ")
                append("codec=").append(cam.codec.uppercase())
            }
        }
        // Set subtitle text color to match status
        binding.toolbar.setSubtitleTextColor(statusColor)
    }

    private fun setupPtz() {
        val cam = camera ?: return
        val isAdmin = container.prefsManager.isAdmin
        val hasPtzPermission = isAdmin || (cam.ownerId > 0 && cam.ownerId == container.prefsManager.userId) || cam.canPtz
        if (!cam.hasPtz || !hasPtzPermission) {
            binding.tvPtzUnsupported.visibility = View.GONE
            binding.ptzGrid.visibility = View.GONE
            binding.seekPtzSpeed.isEnabled = false
            // Hide PTZ section title and presets entirely when no PTZ support or no permission
            binding.tvPtzSectionTitle.visibility = View.GONE
            binding.cardPtz.visibility = View.GONE
            binding.tvPresetsSectionTitle.visibility = View.GONE
            binding.cardPresets.visibility = View.GONE
            return
        }
        binding.tvPtzUnsupported.visibility = View.GONE
        binding.ptzGrid.visibility = View.VISIBLE
        binding.tvPtzSectionTitle.visibility = View.VISIBLE
        binding.cardPtz.visibility = View.VISIBLE

        val buttons = mapOf(
            binding.btnPtzUp to "up",
            binding.btnPtzDown to "down",
            binding.btnPtzLeft to "left",
            binding.btnPtzRight to "right",
            binding.btnPtzStop to "stop",
        )
        // v1.6.40: PTZ controls are available to all users — the
        // server-side endpoint enforces its own authorization. The
        // buttons are always visible when the camera supports PTZ.
        buttons.forEach { (btn, command) ->
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { sendPtz(command) }
        }

        binding.seekPtzSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progress / 100.0
                binding.tvPtzSpeed.text = String.format("%.2f", speed)
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
    }

    private fun setupPresets() {
        presetAdapter = PresetListAdapter(
            isAdmin = isAdmin,
            onGoto = { preset -> gotoPreset(preset) },
            onDelete = { preset -> deletePreset(preset) },
        )
        binding.rvPresets.layoutManager = LinearLayoutManager(this)
        binding.rvPresets.adapter = presetAdapter

        binding.btnAddPreset.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnAddPreset.setOnClickListener { showAddPresetDialog() }
    }

    private fun setupSettings() {
        val cam = camera ?: return

        // The share button is visible to admins OR the camera's owner.
        // Admin-only controls (audio / recording / codec / delete) live
        // in groupAdminControls and stay hidden for non-admin owners.
        val isOwner = cam.ownerId == container.prefsManager.userId
        val canShare = isAdmin || isOwner

        // Non-admin non-owner: hide the entire settings section.
        // PTZ controls above remain visible — they have their own
        // admin-gated visibility in setupPtz() and the server still
        // enforces authorization.
        if (!canShare) {
            binding.tvSettingsSectionTitle.visibility = View.GONE
            binding.cardSettings.visibility = View.GONE
            return
        }

        // Share button — admin or owner. Opens ShareCameraDialog which
        // lists current shares and provides add/revoke actions.
        binding.btnShareCamera.visibility = View.VISIBLE
        binding.btnShareCamera.setOnClickListener {
            ShareCameraDialog(this, container, cam.id).show()
        }

        // Admin-only controls: audio / recording / codec / delete.
        // Hidden entirely for non-admin owners (they only see the
        // share button above).
        if (!isAdmin) {
            binding.groupAdminControls.visibility = View.GONE
            return
        }
        binding.groupAdminControls.visibility = View.VISIBLE

        // Audio switch — initial state from camera capabilities.
        binding.switchAudio.isChecked = cam.hasAudio
        binding.switchAudio.isEnabled = isAdmin
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            if (!isAdmin) {
                binding.switchAudio.isChecked = cam.hasAudio
                toast(R.string.camera_admin_required)
                return@setOnCheckedChangeListener
            }
            updateAudio(isChecked)
        }

        // Recording switch — Frigate continuous recording.
        // The recording plan flag is surfaced by the backend via the
        // Camera.meta map under the "recording" key as
        // {enabled: bool, retention_days: int, segment_seconds: int}.
        // Older backends that don't populate this field will leave the
        // switch off by default; the user can still toggle it on.
        val recordingEnabled = camera?.isRecordingEnabled ?: false
        binding.switchRecording.isChecked = recordingEnabled
        binding.switchRecording.isEnabled = isAdmin
        binding.switchRecording.setOnCheckedChangeListener { _, isChecked ->
            if (!isAdmin) {
                binding.switchRecording.isChecked = recordingEnabled
                toast(R.string.camera_admin_required)
                return@setOnCheckedChangeListener
            }
            setRecordingPlan(isChecked)
        }

        // Codec button — only H264 is supported via this API.
        binding.btnCodecH264.visibility = View.VISIBLE
        binding.btnCodecH264.setOnClickListener { updateCodec() }

        // Delete camera — admin only.
        binding.btnDelete.visibility = View.VISIBLE
        binding.btnDelete.setOnClickListener { confirmDeleteCamera() }
    }

    // --- API actions ---

    private fun sendPtz(command: String) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        val speed = (binding.seekPtzSpeed.progress / 100.0).coerceIn(0.1, 1.0)
        lifecycleScope.launch {
            try {
                container.getRepository().moveCamera(token, cam.id, command, speed)
                toast("PTZ: $command")
            } catch (e: Exception) {
                toast("PTZ 失败: ${e.message}")
            }
        }
    }

    private fun loadPresets() {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        // /presets/discover is JWT-only (read access) — non-admin can
        // list presets but not modify them.
        lifecycleScope.launch {
            try {
                presets = container.getRepository().listCameraPresets(token, cam.id)
                presetAdapter.submit(presets)
                binding.tvPresetsEmpty.visibility =
                    if (presets.isEmpty()) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                binding.tvPresetsEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun gotoPreset(preset: CameraPreset) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                // Use the preset's name as the alias if the user has
                // set one; otherwise fall back to the token (which
                // won't match any alias and will 404). In practice
                // gotoCameraPreset only works on aliases set via
                // PUT /presets/{alias}.
                val alias = preset.name.ifBlank { preset.token }
                container.getRepository().gotoCameraPreset(token, cam.id, alias)
                toast("前往: ${preset.name.ifBlank { preset.token }}")
            } catch (e: Exception) {
                toast("前往预设失败: ${e.message}")
            }
        }
    }

    private fun deletePreset(preset: CameraPreset) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        val alias = preset.name.ifBlank { return }
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_logout_title)
            .setMessage("删除预设位 \"$alias\"？")
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                lifecycleScope.launch {
                    try {
                        container.getRepository().deleteCameraPreset(token, cam.id, alias)
                        toast("已删除")
                        loadPresets()
                    } catch (e: Exception) {
                        toast("删除失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showAddPresetDialog() {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return

        val dialogContainer = LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val aliasEdit = EditText(this).apply { hint = getString(R.string.camera_preset_alias) }
        val tokenEdit = EditText(this).apply { hint = getString(R.string.camera_preset_token) }
        dialogContainer.addView(aliasEdit)
        dialogContainer.addView(tokenEdit)

        AlertDialog.Builder(this)
            .setTitle(R.string.camera_preset_add)
            .setView(dialogContainer)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val alias = aliasEdit.text.toString().trim()
                val tokenStr = tokenEdit.text.toString().trim()
                if (alias.isEmpty() || tokenStr.isEmpty()) {
                    toast("别名和 token 都不能为空")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        this@CameraDetailActivity.container.getRepository()
                            .setCameraPreset(token, cam.id, alias, tokenStr)
                        toast("已添加")
                        loadPresets()
                    } catch (e: Exception) {
                        toast("添加失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun updateAudio(enabled: Boolean) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                container.getRepository().updateCameraAudio(token, cam.id, enabled)
                toast(if (enabled) "音频已启用" else "音频已禁用")
                refreshCamera()
            } catch (e: Exception) {
                toast("音频切换失败: ${e.message}")
                binding.switchAudio.isChecked = !enabled
            }
        }
    }

    private fun setRecordingPlan(enabled: Boolean) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                container.getRepository().setRecordingPlan(token, cam.id, enabled)
                toast(if (enabled) "已开启持续录像" else "已关闭持续录像")
            } catch (e: Exception) {
                toast("录像设置失败: ${e.message}")
                binding.switchRecording.isChecked = !enabled
            }
        }
    }

    private fun updateCodec() {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_codec_section)
            .setMessage(getString(R.string.camera_codec_h264))
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                lifecycleScope.launch {
                    try {
                        container.getRepository().updateCameraCodec(token, cam.id, "h264")
                        toast("已切换至 H264")
                        refreshCamera()
                    } catch (e: Exception) {
                        toast("切换失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun confirmDeleteCamera() {
        val cam = camera ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_delete)
            .setMessage(R.string.camera_delete_confirm)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val token = container.prefsManager.token ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        container.getRepository().deleteCamera(token, cam.id)
                        toast("已删除")
                        finish()
                    } catch (e: Exception) {
                        toast("删除失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun refreshCamera() {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                val fresh = container.getRepository().getCamera(token, cam.id)
                camera = fresh
                setupHeader()
                binding.switchAudio.isChecked = fresh.hasAudio
            } catch (_: Exception) {
                // Network failure: keep showing the old state.
            }
        }
    }

    // --- Live video playback ---

    /**
     * Wires the [StyledPlayerView] and kicks off the initial live
     * stream load. The player is created on demand and released in
     * onPause/onDestroy to free the MediaCodec for other apps.
     */
    private fun setupVideo() {
        camera ?: return
        // Surface must be attached before prepare() to avoid the
        // "setOutputSurface -- failed to set consumer usage (BAD_INDEX)"
        // issue on software decoders. The StyledPlayerView in the
        // layout already provides the surface; we attach the player
        // to it before calling prepare() in preparePlayback().
        binding.btnReloadStream.setOnClickListener { startPlayback() }
        binding.tvVideoError.setOnClickListener { startPlayback() }

        // Fullscreen button on the player controller: tapping forces
        // landscape orientation and hides the toolbar + action row +
        // header card + PTZ + presets + settings sections so the
        // video fills the screen. The Activity has
        // configChanges=orientation|screenSize in the manifest so
        // ExoPlayer isn't torn down on rotation. Live stream playback
        // has no meaningful speed control, so no speed button here —
        // speed buttons are only added to recording/alerts playback.
        //
        // surfaceRenderer is passed as secondaryPlayerView so the
        // same fullscreen logic applies when WebRTC (not ExoPlayer)
        // is the active renderer: only one of them is VISIBLE at a
        // time, and the helper resizes whichever is showing.
        //
        // btnWebRtcFullscreen is the standalone overlay used only
        // in WebRTC mode (when playerView is GONE, its built-in
        // controller button isn't reachable). The helper wires its
        // click listener to [toggleFullscreen] in [attach].
        val helper = PlayerFullscreenHelper(
            playerView = binding.playerView,
            hostView = binding.root,
            hideOnFullscreen = listOf(
                binding.toolbar,
                binding.actionButtonsRow,
                binding.cardPtz,
                binding.rvPresets.parent.parent as View, // presets section LinearLayout
            ),
            speedButton = null,
            secondaryPlayerView = binding.surfaceRenderer,
            fullscreenButton = binding.btnWebRtcFullscreen,
        )
        helper.attach()
        fullscreenHelper = helper

        // Initialize WebRTC client early so it's ready when the
        // first startPlayback() runs. init() is idempotent.
        ensureWebRtcClient()

        // v1.6.35: pre-negotiate the SDP offer in the background so
        // that when startPlayback() fires (called from onCreate after
        // all setup* methods complete), startStream can reuse the
        // prepared PeerConnection and skip the 800ms LAN / 5s remote
        // ICE gathering phase. startPlayback() is NOT called here —
        // onCreate() calls it after setupVideo() returns, giving the
        // prepare a head start during the remaining setup* calls.
        precomputeSdpOffer()
    }

    private fun setupActions() {
        binding.btnRecordings.setOnClickListener { showRecordings() }
        binding.btnAlerts.setOnClickListener { showAlerts() }
        setupWebRtcControls()
    }

    private fun setupPinchZoom() {
        binding.pinchZoomContainer.onZoomChanged = { scale ->
            if (scale > 1.05f) {
                binding.tvZoomBadge.visibility = View.VISIBLE
                binding.tvZoomBadge.text = String.format(java.util.Locale.US, "%.1fx 双击复位", scale)
            } else {
                binding.tvZoomBadge.visibility = View.GONE
            }
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTalkback() {
        val cam = camera ?: return
        if (!cam.hasTwoWayAudio) {
            binding.cardTalkback.visibility = View.GONE
            return
        }
        binding.cardTalkback.visibility = View.VISIBLE

        binding.btnTalkback.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            this,
                            android.Manifest.permission.RECORD_AUDIO
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        recordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        return@setOnTouchListener true
                    }
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    binding.btnTalkback.text = "松开 结束"
                    binding.btnTalkback.setIconResource(R.drawable.ic_mic)
                    binding.tvTalkbackHint.text = "正在向摄像机讲话..."
                    binding.tvTalkbackHint.setTextColor(resources.getColor(R.color.online, theme))
                    val success = webRtcClient?.startTalkback() ?: false
                    if (!success) {
                        android.widget.Toast.makeText(this, "对讲启动失败，请检查摄像头网络连接", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    binding.btnTalkback.text = "按住 对讲"
                    binding.tvTalkbackHint.text = "按住说话，松开发送到摄像机"
                    binding.tvTalkbackHint.setTextColor(resources.getColor(R.color.text_hint, theme))
                    webRtcClient?.stopTalkback()
                    true
                }
                else -> false
            }
        }
    }

    fun pauseLivePlayback() {
        isLivePausedForDialog = true
        streamRetryJob?.cancel()
        webRtcClient?.setAudioEnabled(false)
        webRtcClient?.setVideoEnabled(false)
        player?.playWhenReady = false
    }

    fun resumeLivePlayback() {
        if (recordingsDialog?.isShowing == true || alertsDialog?.isShowing == true) {
            return
        }
        if (!isLivePausedForDialog) return
        isLivePausedForDialog = false
        val client = webRtcClient
        if (client != null && client.hasActiveStream()) {
            client.setVideoEnabled(true)
            if (audioEnabled && !webRtcMuted) {
                client.setAudioEnabled(true)
            }
        } else if (player != null) {
            player?.playWhenReady = true
        } else {
            startPlayback()
        }
    }

    fun showRecordings(initialTimestamp: Long = 0L) {
        val cam = camera ?: return
        recordingsDialog?.dismiss()
        pauseLivePlayback()
        // v1.6.0: pass [initialTimestamp] (unix seconds) so the
        // RecordingsDialog can auto-open the right day and seek to
        // the alert's exact moment. Zero (default) means "open the
        // dialog normally with no auto-seek".
        recordingsDialog = RecordingsDialog(
            context = this,
            camera = cam,
            container = container,
            initialTimestamp = initialTimestamp,
        ).apply {
            setOnDismissListener {
                recordingsDialog = null
                if (alertsDialog == null && !isFinishing && !isDestroyed) {
                    resumeLivePlayback()
                }
            }
            show()
        }
    }

    private fun showAlerts() {
        val cam = camera ?: return
        alertsDialog?.dismiss()
        pauseLivePlayback()
        alertsDialog = AlertsDialog(this, cam, container).apply {
            setOnDismissListener {
                alertsDialog = null
                if (recordingsDialog == null && !isLivePausedForDialog && !isFinishing && !isDestroyed) {
                    resumeLivePlayback()
                }
            }
            show()
        }
    }

    /**
     * v1.5.8: Wire up the WebRTC-only control bar (pause / mute /
     * fullscreen). ExoPlayer's built-in controller isn't visible
     * when playerView is GONE (which it is during WebRTC playback),
     * so we provide a minimal control bar at the bottom of the
     * videoContainer.
     *
     * Pause: freezes the last frame by calling setEnabled(false) on
     * the video track. The PeerConnection stays alive — the backend
     * keeps sending RTP, we just stop rendering. Resume is instant.
     *
     * Mute: toggles the audio track's enabled state. Same PeerConnection
     * semantics — local-only change.
     *
     * Fullscreen: delegates to the shared PlayerFullscreenHelper via
     * the `fullscreenButton` parameter (wired in setupVideo).
     */
    private fun setupWebRtcControls() {
        binding.btnWebRtcPause.setOnClickListener {
            webRtcPaused = !webRtcPaused
            try {
                webRtcClient?.setVideoEnabled(!webRtcPaused)
            } catch (_: Exception) {}
            try {
                player?.playWhenReady = !webRtcPaused
            } catch (_: Exception) {}
            updateWebRtcControlButtons()
        }
        binding.btnWebRtcMute.setOnClickListener {
            webRtcMuted = !webRtcMuted
            try {
                webRtcClient?.setAudioEnabled(!webRtcMuted)
            } catch (_: Exception) {}
            updateWebRtcControlButtons()
        }
        binding.btnWebRtcPip.setOnClickListener {
            enterPipMode()
        }
    }

    private fun updateWebRtcControlButtons() {
        binding.btnWebRtcPause.setIconResource(
            if (webRtcPaused) R.drawable.ic_play_circle else R.drawable.ic_pause
        )
        binding.btnWebRtcMute.setIconResource(
            if (webRtcMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on
        )
    }

    /**
     * v1.5.9: Updates the stream strategy badge in the top-left of
     * the video container. The badge shows which live transport is
     * active so the user can tell at a glance whether they're on
     * the low-latency WebRTC path or one of the ExoPlayer fallbacks
     * (MP4 / HLS). Pass null to hide the badge (e.g. on error).
     */
    private fun updateStreamStrategy(strategy: String?) {
        if (strategy.isNullOrBlank()) {
            binding.tvStreamStrategy.visibility = View.GONE
            binding.tvHlsNotice.visibility = View.GONE
        } else {
            binding.tvStreamStrategy.text = strategy
            binding.tvStreamStrategy.visibility = View.VISIBLE
            // v1.6.24: HLS is only selected when WebRTC can't reach
            // the backend (Tunnel path with no P2P fallback) and MP4
            // also failed or was skipped. In that case latency is
            // 1.5s+ vs WebRTC's sub-second — surface a small notice
            // so the user knows the high latency is expected, not a
            // bug. Hidden for WebRTC / MP4 (those transports have
            // sub-second latency).
            binding.tvHlsNotice.visibility =
                if (strategy == "HLS") View.VISIBLE else View.GONE
        }
    }

    /**
     * v1.6.16: Fetch a JPEG snapshot from /api/v1/cameras/:id/frame
     * and display it in [binding.ivPreviewFrame] while the video
     * stream is connecting. This mirrors the web dashboard's "preview"
     * mode, which shows a cheap JPEG frame (~1.5s on remote) before
     * the expensive WebRTC/MP4 cold start (~5-15s on remote).
     *
     * The preview is hidden by [hidePreviewFrame] once any video
     * surface becomes visible (WebRTC onConnected, or ExoPlayer
     * onPlaybackStateChanged → STATE_READY).
     */
    private fun loadPreviewFrame() {
        val cam = camera ?: return
        // v1.8.14: skip preview frame when camera is offline to avoid
        // unnecessary network requests that will fail anyway.
        if (!cam.isOnline) return
        val container = (application as HomeCenterApp).container
        val baseUrl = container.getApiBaseUrl().ifBlank { return }
        val token = container.prefsManager.token ?: return
        // Task 10: request a downscaled JPEG (quality=30, width=640) so
        // the preview frame loads fast on slow Cloudflare Tunnel links;
        // the full-resolution frame is never needed for a placeholder.
        val url = "${baseUrl.trimEnd('/')}/api/v1/cameras/${cam.id}/frame?quality=30&width=640"

        lifecycleScope.launch {
            // v1.7.3: retry up to 2 times on failure to cover go2rtc cold start
            // (ffmpeg producer connecting to RTSP source). Each retry waits 3s.
            var bitmap: android.graphics.Bitmap? = null
            for (attempt in 0..2) {
                if (isFinishing) return@launch
                try {
                    bitmap = withContext(Dispatchers.IO) {
                        // v1.6.16: use the shared OkHttpClient so this request
                        // reuses the connection pool/interceptors that
                        // BaseUrlResolver.warmupConnection already warmed.
                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .header("User-Agent", NetworkFactory.USER_AGENT)
                            .addHeader("Authorization", "Bearer $token")
                            .build()
                        container.okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@use null
                            response.body?.bytes()?.let { bytes ->
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                        }
                    }
                    if (bitmap != null) break
                } catch (e: Exception) {
                    // Preview frame is best-effort; log and retry if attempts remain.
                    android.util.Log.w(TAG, "Preview frame fetch attempt ${attempt + 1} failed: ${e.message}")
                }
                if (attempt < 2 && bitmap == null) {
                    delay(3000)
                }
            }
            if (bitmap != null && !isFinishing) {
                binding.ivPreviewFrame.setImageBitmap(bitmap)
                binding.ivPreviewFrame.visibility = View.VISIBLE
            }
        }
    }

    /** Hide the JPEG preview frame once a real video surface is showing. */
    private fun hidePreviewFrame() {
        if (binding.ivPreviewFrame.visibility == View.VISIBLE) {
            binding.ivPreviewFrame.visibility = View.GONE
            binding.ivPreviewFrame.setImageDrawable(null)
        }
    }

    private fun startPlayback() {
        val cam = camera ?: return
        // Reset the fallback ladder. The reload button should always
        // try WebRTC first (it's the lowest-latency transport and the
        // one the user explicitly asked for in v1.5.3).
        triedWebRtc = false
        triedMp4 = false
        triedHls = false

        binding.tvVideoError.setOnClickListener {
            val c = camera
            if (c != null && c.isOnline) {
                streamRetryJob?.cancel()
                streamRetryCount = 0
                binding.tvVideoError.visibility = View.GONE
                binding.progressVideo.visibility = View.VISIBLE
                setupVideo()
            }
        }

        // v1.8.14: if camera is offline, show error immediately
        // instead of attempting WebRTC/MP4/HLS which will all fail
        // and waste network requests. The user sees a clear message
        // that the camera is unavailable.
        if (!cam.isOnline) {
            binding.tvVideoError.visibility = View.VISIBLE
            binding.progressVideo.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            binding.surfaceRenderer.visibility = View.GONE
            binding.webRtcControls.visibility = View.GONE
            binding.btnWebRtcFullscreen.visibility = View.GONE
            updateStreamStrategy(null)
            return
        }

        // v1.5.9: show the loading badge before any transport is
        // selected. The badge is updated to the actual transport
        // (WebRTC / MP4 / HLS) once startWebRtcStream / startMp4Playback
        // picks one. Hidden only when the player errors out.
        updateStreamStrategy("加载中")

        // v1.6.24: always attempt WebRTC first when the camera is
        // online and the WebRTC client initialized successfully —
        // regardless of whether BaseUrlResolver picked LAN, IPv6
        // direct, or Tunnel. Previously (v1.6.23) WebRTC was skipped
        // on Tunnel because Cloudflare Tunnel can't relay UDP RTP —
        // true, but the WebRTC client also tries STUN-based P2P and
        // public-IPv4/IPv6 direct paths, which occasionally succeed
        // on remote links (sub-second latency vs HLS's 1.5s+). When
        // WebRTC fails the WebRtcClient's 6s connect timeout fires
        // onError and the existing fallback ladder continues to
        // MP4/HLS.
        //
        // The isLan flag passed to WebRtcClient.startStream (inside
        // startWebRtcStream) is still derived from isDirectPath() —
        // on Tunnel it's false, so the client uses 5s ICE gathering,
        // 6s connect timeout, and ENABLES TCP candidates (go2rtc
        // exposes 8555 TCP as a fallback when UDP is blocked by
        // carrier NAT). BaseUrlResolver's three-tier probe
        // (LAN > IPv6 direct > Tunnel) handles IPv6 reachability
        // detection; no separate ConnectivityManager check is needed.
        if (cam.isOnline && ensureWebRtcClient()) {
            binding.tvVideoError.visibility = View.GONE
            binding.progressVideo.visibility = View.VISIBLE
            startWebRtcStream(cam)
            return
        }

        // WebRTC factory init failed: skip WebRTC,
        // let startMp4Playback pick the right ExoPlayer transport
        // (HLS on remote, MP4 on LAN).
        startMp4Playback(cam)
    }

    /**
     * Initializes the WebRTC client + SurfaceViewRenderer once.
     * Returns true if WebRTC is available for streaming, false if
     * the caller should fall back to ExoPlayer. Safe to call
     * repeatedly — subsequent calls are no-ops.
     *
     * v1.5.6: prefers the pre-warmed WebRtcClient from AppContainer
     * (PeerConnectionFactory + EGL context pre-built on app launch
     * or login) to save ~300-500ms on first camera detail open.
     * Falls back to synchronous init when warming isn't ready yet.
     */
    private fun ensureWebRtcClient(): Boolean {
        if (webRtcClient != null) return true
        // v1.6.10: use the shared WebRtcClient from AppContainer.
        // The PeerConnectionFactory + EGL context are now shared
        // across all CameraDetailActivity instances so opening a
        // second camera doesn't pay the 300-500ms factory init
        // cost again.
        val client = container.getOrInitWebRtcClient() ?: run {
            // v1.6.41: log the silent failure so the preload path is
            // diagnosable. Previously this returned false with no log,
            // making it hard to tell why precomputeSdpOffer was
            // skipped (webRtcClient stays null -> precompute early-
            // returns at `val client = webRtcClient ?: return`).
            android.util.Log.w(TAG, "ensureWebRtcClient: getOrInitWebRtcClient returned null (baseUrl/token unavailable or factory init failed)")
            return false
        }
        webRtcClient = client
        return try {
            // Init the SurfaceViewRenderer with the WebRTC client's
            // EGL context. Must run on the main thread (EGL surface
            // creation requires it on most GPUs).
            binding.surfaceRenderer.init(client.eglBase.eglBaseContext, null)
            binding.surfaceRenderer.setMirror(false)
            // SCALE_ASPECT_FILL so the video fills the renderer bounds
            // without letterboxing — matches ExoPlayer's resize_mode
            // = fixed_width behavior.
            binding.surfaceRenderer.setScalingType(
                RendererCommon.ScalingType.SCALE_ASPECT_FILL
            )
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "WebRtcClient init failed: ${e.message}", e)
            false
        }
    }

    /**
     * v1.6.35: Pre-negotiate the SDP offer in the background. Fetches
     * the ICE config (from cache or network), builds the ICE server
     * list, and calls [WebRtcClient.prepareOffer] which creates a
     * PeerConnection + completes ICE gathering without POSTing to the
     * backend. When [startWebRtcStream] fires next, it consumes the
     * prepared PC and skips the ICE gathering phase.
     *
     * Best-effort: if the user taps Play before this finishes,
     * [startWebRtcStream] cancels the prepare job and runs the full
     * flow. If ICE config fetch fails, the method silently returns
     * and the full flow runs as before.
     */
    private fun precomputeSdpOffer() {
        val cam = camera ?: return
        val client = webRtcClient ?: return
        val token = container.prefsManager.token ?: return

        // v1.6.41: entry log at Log.i so the preload trigger is
        // visible in logcat without a debug filter. Confirms the
        // onCreate -> setupVideo -> precomputeSdpOffer chain fired.
        android.util.Log.i(TAG, "precomputeSdpOffer: entry, cameraId=${cam.id}")

        lifecycleScope.launch {
            try {
                // v1.6.41: check isDirectPath FIRST. On LAN/IPv6
                // direct, iceServers is always empty (host candidates
                // suffice), so we skip the network ICE config fetch
                // entirely — on cold start this saves a 1.4s+ Tunnel
                // round-trip that would needlessly delay prepareOffer.
                // isDirectPath() is a pure field comparison against
                // `resolved` (no network probe), so it never blocks.
                val isDirectPath = container.baseUrlResolver.isDirectPath()

                // Only fetch ICE config on non-direct (Tunnel) paths.
                // On direct paths the fetch is unnecessary work since
                // iceServers is forced to emptyList() below.
                val iceConfig = if (isDirectPath) {
                    cachedIceConfig ?: container.getIceConfig()
                } else {
                    cachedIceConfig
                        ?: container.getIceConfig()
                        ?: try {
                            container.getOrFetchIceConfig(token).also { cachedIceConfig = it }
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "precomputeSdpOffer: ICE config fetch failed: ${e.message}")
                            null
                        }
                }

                val iceServers = if (isDirectPath) {
                    emptyList()
                } else {
                    iceConfig?.ice_servers?.map { srv ->
                        PeerConnection.IceServer.builder(srv.urls).apply {
                            srv.username?.let { setUsername(it) }
                            srv.credential?.let { setPassword(it) }
                        }.createIceServer()
                    } ?: emptyList()
                }

                client.prepareOffer(cam.id, iceServers, isDirectPath, cam.hasTwoWayAudio)
                android.util.Log.i(TAG, "precomputeSdpOffer: started for cameraId=${cam.id} (directPath=$isDirectPath, twoWayAudio=${cam.hasTwoWayAudio})")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "precomputeSdpOffer failed: ${e.message}")
            }
        }
    }

    /**
     * Kicks off a WebRTC stream attempt. On success, shows the
     * SurfaceViewRenderer and hides the ExoPlayer StyledPlayerView.
     * On failure, falls back to MP4 (and then HLS).
     */
    private fun startWebRtcStream(cam: Camera) {
        val client = webRtcClient ?: run {
            startMp4Playback(cam); return
        }
        if (webRtcInProgress) return
        webRtcInProgress = true
        triedWebRtc = true

        // v1.5.12: release any lingering ExoPlayer BEFORE starting
        // WebRTC. ExoPlayer's setAudioAttributes(handleAudioFocus=true)
        // registers an AudioFocusRequest on STREAM_MUSIC; if we
        // don't release it before WebRTC starts, the system keeps
        // ExoPlayer's AudioFocus active and WebRTC's AudioTrack
        // output gets ducked or routed to the wrong stream — the
        // user sees video but hears nothing. releasePlayer() calls
        // ExoPlayer.release() which internally abandons AudioFocus.
        releasePlayer()

        // v1.5.9: mark the active transport. WebRTC is the primary
        // path; if it fails the onError callback flips the badge to
        // "MP4" when the fallback starts.
        updateStreamStrategy("WebRTC")

        // Show the WebRTC surface + standalone fullscreen button,
        // hide the ExoPlayer view (its controller UI is irrelevant
        // while WebRTC is rendering).
        // v1.5.8: show the WebRTC control bar (pause / mute /
        // fullscreen) instead of just a fullscreen overlay button
        // so the user has parity with ExoPlayer's controller.
        binding.playerView.visibility = View.GONE
        binding.surfaceRenderer.visibility = View.VISIBLE
        binding.webRtcControls.visibility = View.VISIBLE
        binding.btnWebRtcFullscreen.visibility = View.VISIBLE
        binding.progressVideo.visibility = View.VISIBLE
        binding.tvVideoError.visibility = View.GONE
        // Initial state: not paused, not muted.
        webRtcPaused = false
        webRtcMuted = false
        updateWebRtcControlButtons()

        // Pre-prepare the ExoPlayer fallback (playWhenReady=false) so
        // that if WebRTC fails we can promote it to the main player
        // slot instantly, skipping the ExoPlayer cold-start delay.
        // ExoPlayer.prepare() is async so this doesn't block the
        // WebRTC negotiation below.
        prepareFallbackPlayer(cam)

        // Fetch ICE config (cached after first call). The list may
        // be empty on LAN — host candidates are enough there.
        // v1.5.7: prefer container.getCachedIceConfig() to skip the
        // network round-trip entirely. The cache is warmed by
        // DashboardFragment.refreshAll() -> AppContainer.prefetchIceConfig(),
        // so by the time the user opens a camera the config is
        // usually already in memory.
        lifecycleScope.launch {
            val iceConfig = cachedIceConfig ?: try {
                val token = container.prefsManager.token ?: ""
                container.getOrFetchIceConfig(token).also {
                    cachedIceConfig = it
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "ICE config fetch failed: ${e.message}")
                null
            }
            // Convert to PeerConnection.IceServer list expected by
            // WebRtcClient. Empty list is OK — host candidates will
            // be gathered automatically.
            // v1.6.10: on LAN, skip STUN/TURN servers entirely.
            // The backend is on the same subnet so host candidates
            // are sufficient — STUN/TURN gathering adds 1-2s delay
            // for no benefit on LAN. The isLan() check comes from
            // BaseUrlResolver which probes /api/v1/system/status.
            // v1.6.23: also skip STUN/TURN on IPv6 direct — the NAS
            // is reachable directly over IPv6 (no NAT), so host
            // candidates are sufficient. isDirectPath() is true for
            // both LAN and IPv6 direct.
            val isDirectPath = container.baseUrlResolver.isDirectPath()
            val iceServers = if (isDirectPath) {
                emptyList()
            } else {
                iceConfig?.ice_servers?.map { srv ->
                    PeerConnection.IceServer.builder(srv.urls).apply {
                        srv.username?.let { setUsername(it) }
                        srv.credential?.let { setPassword(it) }
                    }.createIceServer()
                } ?: emptyList()
            }
            android.util.Log.d(TAG, "WebRTC ICE servers: ${iceServers.size} (directPath=$isDirectPath)")

            client.startStream(
                cameraId = cam.id,
                surfaceRenderer = binding.surfaceRenderer,
                iceServers = iceServers,
                isLan = isDirectPath,
                enableTwoWayAudio = cam.hasTwoWayAudio,
                listener = object : WebRtcClient.Listener {
                    override fun onConnected() {
                        webRtcInProgress = false
                        streamRetryCount = 0
                        streamRetryJob?.cancel()
                        if (isLivePausedForDialog) {
                            android.util.Log.d(TAG, "WebRTC connected while dialog open: staying muted & paused")
                            client.setAudioEnabled(false)
                            client.setVideoEnabled(false)
                            return
                        }
                        binding.tvVideoError.text = getString(R.string.camera_video_failed)
                        binding.progressVideo.visibility = View.GONE
                        binding.tvVideoError.visibility = View.GONE
                        // v1.6.16: hide the JPEG preview frame once the
                        // real WebRTC video surface is live.
                        hidePreviewFrame()
                        // WebRTC succeeded: release the pre-prepared
                        // fallback player — it's no longer needed.
                        fallbackPlayer?.release()
                        fallbackPlayer = null
                        // v1.5.9: confirm the active transport once
                        // ICE reaches CONNECTED — the badge was already
                        // "WebRTC" from startWebRtcStream, this just
                        // re-asserts it in case the user opened the
                        // page during the fallback window.
                        updateStreamStrategy("WebRTC")
                        updatePipParams()
                        android.util.Log.d(TAG, "WebRTC connected")
                    }

                    override fun onError(reason: String) {
                        webRtcInProgress = false
                        if (isLivePausedForDialog) {
                            android.util.Log.d(TAG, "WebRTC error while dialog open: ignoring fallback")
                            return
                        }
                        android.util.Log.w(TAG, "WebRTC failed: $reason — falling back to MP4")
                        // Hide WebRTC surface, show ExoPlayer surface.
                        binding.surfaceRenderer.visibility = View.GONE
                        binding.webRtcControls.visibility = View.GONE
                        binding.btnWebRtcFullscreen.visibility = View.GONE
                        binding.playerView.visibility = View.VISIBLE
                        // Promote the pre-prepared fallback player to
                        // the main slot if it's still alive. This skips
                        // the ExoPlayer cold-start delay since the media
                        // source was already prepared in the background
                        // while WebRTC was negotiating. If the fallback
                        // player is null (prepare failed or never ran),
                        // fall back to the original startMp4Playback path.
                        val fb = fallbackPlayer
                        if (fb != null) {
                            player = fb
                            fallbackPlayer = null
                            binding.playerView.player = fb
                            fb.playWhenReady = true
                            fb.volume = if (audioEnabled) 1.0f else 0.0f
                            // Re-derive the strategy badge from the
                            // current path config (the fallback player
                            // was prepared with this transport).
                            val isDirectPath = container.baseUrlResolver.isDirectPath()
                            val mp4Url = resolveMp4Url(cam)
                            val hlsUrl = resolveHlsUrl(cam)
                            val useMp4 = when {
                                isDirectPath -> mp4Url.isNotBlank()
                                hlsUrl.isNotBlank() -> false
                                else -> mp4Url.isNotBlank()
                            }
                            updateStreamStrategy(if (useMp4) "MP4" else "HLS")
                            fullscreenHelper?.onPlayerChanged(fb)
                        } else {
                            startMp4Playback(cam)
                        }
                    }

                    override fun onIceStateChanged(state: PeerConnection.IceConnectionState) {
                        android.util.Log.d(TAG, "ICE: $state")
                    }
                },
            )
        }
    }

    /**
     * ExoPlayer fallback path (MP4 primary, HLS secondary). Used
     * when WebRTC is unavailable or has failed. Wrapped in its own
     * method so [startWebRtcStream]'s onError callback can resume
     * the fallback ladder cleanly.
     */
    private fun startMp4Playback(cam: Camera) {
        val mp4Url = resolveMp4Url(cam)
        val hlsUrl = resolveHlsUrl(cam)
        if (mp4Url.isBlank() && hlsUrl.isBlank()) {
            binding.tvVideoError.visibility = View.VISIBLE
            binding.progressVideo.visibility = View.GONE
            // Make sure the ExoPlayer surface is visible so the
            // error TextView (centered in the same FrameLayout) is
            // laid out correctly.
            binding.playerView.visibility = View.VISIBLE
            binding.surfaceRenderer.visibility = View.GONE
            binding.webRtcControls.visibility = View.GONE
            binding.btnWebRtcFullscreen.visibility = View.GONE
            // v1.5.9: no transport could be selected — hide the
            // badge so it doesn't show a stale "加载中" next to
            // the error message.
            updateStreamStrategy(null)
            return
        }
        binding.tvVideoError.visibility = View.GONE
        // v1.6.18: skip MP4 on remote networks, fall straight to HLS.
        // The dashboard's fallback ladder is WebRTC → HLS (no MP4
        // middle tier). MP4 is an infinite fragmented-MP4 stream
        // served by go2rtc through Cloudflare Tunnel — on remote
        // networks the combination of 1.4s+ TTFB + tunnel buffer
        // flushing + ExoPlayer's ProgressiveMediaSource (which has
        // no segment-retry logic) causes frequent connection resets
        // that surface as PlaybackException. HLS, by contrast,
        // fetches discrete .ts segments with per-segment retry,
        // making it far more resilient on flaky links.
        //
        // LAN keeps MP4 as the first fallback because go2rtc's fMP4
        // stream starts in ~1-2s on LAN vs 3-5s for HLS (HLS needs
        // to generate the init segment + first .ts segment).
        // v1.6.23: IPv6 direct path uses the same MP4-first strategy
        // as LAN — the NAS is directly reachable over IPv6 (no
        // Tunnel), so MP4's fMP4 stream starts just as fast.
        val isDirectPath = container.baseUrlResolver.isDirectPath()
        // v1.6.18: on remote, prefer HLS over MP4 (see comment above).
        // If HLS URL is missing on remote, fall back to MP4 rather
        // than erroring out — better to try MP4 than show a black screen.
        val useMp4 = when {
            isDirectPath -> mp4Url.isNotBlank()
            hlsUrl.isNotBlank() -> false
            else -> mp4Url.isNotBlank() // remote but no HLS — try MP4
        }
        // v1.5.9: show "MP4" or "HLS" depending on which URL we're
        // about to feed ExoPlayer. preparePlayback() will further
        // update the badge when it falls back from MP4 to HLS.
        updateStreamStrategy(if (useMp4) "MP4" else "HLS")
        // MP4 (fMP4 stream via home-api proxy) is the primary
        // ExoPlayer transport on LAN — HLS Init() on go2rtc can 404 on
        // cold streams. See the inline-playback comment in the
        // previous CameraAdapter for the full rationale.
        preparePlayback(mp4Url, hlsUrl, useMp4 = useMp4)
    }

    /**
     * Pre-prepares a fallback ExoPlayer (playWhenReady=false) while
     * WebRTC is negotiating. Mirrors [preparePlayback]'s player +
     * media source configuration, but does NOT call play() — the
     * player stays in the prepared-but-paused state so it can be
     * promoted to the main `player` slot instantly if WebRTC fails.
     *
     * The source type (MP4 vs HLS) is chosen with the same
     * isDirectPath logic as [startMp4Playback]: MP4 on LAN/IPv6
     * direct, HLS on Tunnel.
     *
     * ExoPlayer.prepare() is async (network fetches happen on
     * internal threads), so this is safe to call on the main thread
     * without blocking the WebRTC negotiation.
     */
    private fun prepareFallbackPlayer(cam: Camera) {
        // Release any stale fallback player from a previous attempt.
        fallbackPlayer?.release()
        fallbackPlayer = null

        val mp4Url = resolveMp4Url(cam)
        val hlsUrl = resolveHlsUrl(cam)
        if (mp4Url.isBlank() && hlsUrl.isBlank()) return

        val isDirectPath = container.baseUrlResolver.isDirectPath()
        val useMp4 = when {
            isDirectPath -> mp4Url.isNotBlank()
            hlsUrl.isNotBlank() -> false
            else -> mp4Url.isNotBlank()
        }
        val url = if (useMp4) mp4Url else hlsUrl
        if (url.isBlank()) return

        val token = container.prefsManager.token
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setConnectTimeoutMs(30_000)
            setReadTimeoutMs(60_000)
            setUserAgent(NetworkFactory.USER_AGENT)
            if (!token.isNullOrEmpty()) {
                setDefaultRequestProperties(
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "Cookie" to "home_token=$token",
                    ),
                )
            }
        }

        val mediaSource = if (useMp4) {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
        } else {
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(1_500)
                        .setMaxOffsetMs(5_000)
                        .setMinOffsetMs(500)
                        .build()
                )
                .build()
            HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 1_000,
                /* maxBufferMs= */ 3_000,
                /* bufferForPlaybackMs= */ 500,
                /* bufferForPlaybackAfterRebufferMs= */ 1_000,
            )
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = ExoPlayerRendererFactory.create(this)
        val newPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
        newPlayer.setAudioAttributes(
            com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        newPlayer.apply {
            setMediaSource(mediaSource)
            playWhenReady = false
            volume = if (audioEnabled) 1.0f else 0.0f
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    // Only update UI once this player has been promoted
                    // to the main `player` slot. While it's still the
                    // fallback (playWhenReady=false), state changes are
                    // internal and shouldn't affect the visible UI.
                    if (this@CameraDetailActivity.player !== newPlayer) return
                    binding.progressVideo.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) {
                        streamRetryCount = 0
                        streamRetryJob?.cancel()
                        binding.tvVideoError.text = getString(R.string.camera_video_failed)
                        binding.tvVideoError.visibility = View.GONE
                        hidePreviewFrame()
                        updatePipParams()
                    }
                    if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                        binding.progressVideo.visibility = View.GONE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e(
                        TAG,
                        "Fallback player error (useMp4=$useMp4): ${error.message}",
                        error,
                    )
                    if (fallbackPlayer === newPlayer) {
                        // Still in the fallback slot (not promoted):
                        // release and null out so WebRTC onError falls
                        // back to startMp4Playback.
                        try { newPlayer.release() } catch (_: Exception) {}
                        fallbackPlayer = null
                    } else if (this@CameraDetailActivity.player === newPlayer) {
                        // Promoted to main player: fall back to the
                        // other transport (MP4 -> HLS), or trigger self-healing retry.
                        if (useMp4 && hlsUrl.isNotBlank() && !triedHls) {
                            preparePlayback(mp4Url, hlsUrl, useMp4 = false)
                        } else {
                            handleStreamPlaybackFailure()
                        }
                    }
                }
            })
            prepare()
        }
        fallbackPlayer = newPlayer
    }

    private fun preparePlayback(mp4Url: String, hlsUrl: String, useMp4: Boolean) {
        releasePlayer()
        val url = if (useMp4) mp4Url else hlsUrl
        if (url.isBlank()) {
            binding.tvVideoError.visibility = View.VISIBLE
            // v1.5.9: blank URL means no transport available —
            // hide the badge.
            updateStreamStrategy(null)
            return
        }
        // v1.5.9: reflect the actual transport that ExoPlayer is
        // about to use. This is also reached when MP4 fails over to
        // HLS (see onPlayerError below), so the badge correctly
        // transitions MP4 -> HLS even mid-session.
        updateStreamStrategy(if (useMp4) "MP4" else "HLS")

        binding.progressVideo.visibility = View.VISIBLE
        binding.tvVideoError.visibility = View.GONE

        val token = container.prefsManager.token
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            // Cloudflare Tunnel from China can be very slow (TTFB
            // 1.4s+, 10s+ timeouts). Generous timeouts let the
            // stream establish before ExoPlayer gives up.
            setConnectTimeoutMs(30_000)
            setReadTimeoutMs(60_000)
            setUserAgent(NetworkFactory.USER_AGENT)
            if (!token.isNullOrEmpty()) {
                setDefaultRequestProperties(
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "Cookie" to "home_token=$token",
                    ),
                )
            }
        }

        val mediaSource = if (useMp4) {
            triedMp4 = true
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
        } else {
            triedHls = true
            // v1.6.19: tightened live offset for 0.5s LL-HLS segments.
            // targetOffset=1.5s (3 segments) down from 3s — matches
            // the new segment size and gets the user closer to live.
            // minOffset=500ms (1 segment) lets ExoPlayer chase live
            // aggressively when the playlist advances.
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(1_500)
                        .setMaxOffsetMs(5_000)
                        .setMinOffsetMs(500)
                        .build()
                )
                .build()
            HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }

        // v1.6.19: tightened LoadControl for the new 0.5s LL-HLS
        // segments. With segment=0.5s (config.yml), 2 segments = 1s
        // of content — enough to start playback without stalls on
        // most networks. bufferForPlayback=500ms lets ExoPlayer
        // start as soon as one partial segment (~167ms) is buffered,
        // cutting first-frame from ~3s to ~1.5-2s on remote.
        // maxBuffer=3s (6 segments) keeps memory bounded while
        // allowing enough runway to absorb Cloudflare Tunnel jitter.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 1_000,
                /* maxBufferMs= */ 3_000,
                /* bufferForPlaybackMs= */ 500,
                /* bufferForPlaybackAfterRebufferMs= */ 1_000,
            )
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = ExoPlayerRendererFactory.create(this)
        val newPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
        // CRITICAL: attach the surface BEFORE prepare(). ExoPlayer
        // creates the MediaCodec during prepare() — if no surface is
        // attached at that point, the codec initializes in no-surface
        // mode and never renders frames.
        binding.playerView.player = newPlayer
        newPlayer.setAudioAttributes(
            com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        newPlayer.apply {
            setMediaSource(mediaSource)
            playWhenReady = true
            volume = if (audioEnabled) 1.0f else 0.0f
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.progressVideo.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) {
                        streamRetryCount = 0
                        streamRetryJob?.cancel()
                        binding.tvVideoError.text = getString(R.string.camera_video_failed)
                        binding.tvVideoError.visibility = View.GONE
                        // v1.6.16: hide the JPEG preview frame once
                        // ExoPlayer has its first frame ready.
                        hidePreviewFrame()
                        updatePipParams()
                    }
                    if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                        binding.progressVideo.visibility = View.GONE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e(
                        TAG,
                        "Playback error (useMp4=$useMp4): ${error.message}",
                        error,
                    )
                    // Failover: MP4 → HLS → self-healing retry.
                    if (useMp4 && hlsUrl.isNotBlank() && !triedHls) {
                        preparePlayback(mp4Url, hlsUrl, useMp4 = false)
                    } else {
                        handleStreamPlaybackFailure()
                    }
                }
            })
            prepare()
        }
        player = newPlayer
        fullscreenHelper?.onPlayerChanged(newPlayer)
    }

    /**
     * v1.10.7: self-healing retry logic when both live transports have failed.
     * Backs off up to 3 times (2s, 4s, 6s) before permanently showing the
     * error state, allowing temporary Cloudflare Tunnel or WiFi drops to heal
     * automatically without requiring the user to exit and re-enter.
     */
    private fun handleStreamPlaybackFailure() {
        releasePlayer()
        val cam = camera
        if (cam != null && cam.isOnline && streamRetryCount < maxStreamRetries) {
            streamRetryCount++
            val delayMs = streamRetryCount * 2000L
            binding.tvVideoError.text = "网络连接中断，正在重连 ($streamRetryCount/$maxStreamRetries)..."
            binding.tvVideoError.visibility = View.VISIBLE
            binding.progressVideo.visibility = View.VISIBLE
            updateStreamStrategy("重连中")
            streamRetryJob?.cancel()
            streamRetryJob = lifecycleScope.launch {
                delay(delayMs)
                if (!isFinishing && !isDestroyed) {
                    android.util.Log.i(TAG, "Retrying camera stream (attempt $streamRetryCount)...")
                    setupVideo()
                }
            }
        } else {
            binding.tvVideoError.text = "视频加载失败，点击重试"
            binding.tvVideoError.visibility = View.VISIBLE
            binding.progressVideo.visibility = View.GONE
            updateStreamStrategy(null)
        }
    }

    private fun releasePlayer() {
        // v1.6.10: legacy entry point. Routes to releaseExoPlayerOnly
        // so the WebRTC PeerConnection survives onPause (see comment
        // in onPause). Kept because startWebRtcStream / fallback
        // paths still call releasePlayer() to clear ExoPlayer before
        // starting a new stream.
        releaseExoPlayerOnly()
    }

    private fun resolveMp4Url(camera: Camera): String {
        // Preferred: home-api proxy that streams fragmented MP4
        // straight from go2rtc. ExoPlayer consumes the infinite fMP4
        // stream via ProgressiveMediaSource.
        val baseUrl = container.getApiBaseUrl().orEmpty()
        if (baseUrl.isBlank()) return ""
        return "${baseUrl.trimEnd('/')}/api/v1/cameras/${camera.id}/stream.mp4"
    }

    private fun resolveHlsUrl(camera: Camera): String {
        val baseUrl = container.getApiBaseUrl().orEmpty()
        val stream = camera.stream
        // v1.8.48: prefer the camera's native-HEVC passthrough HLS when the
        // device can decode HEVC and the backend exposed an <name>_hevc
        // stream (zero-transcode). Falls back to the H.264 transcode chain.
        val hlsHevcUrl = stream?.hlsHevcUrl?.trim().orEmpty()
        if (hlsHevcUrl.isNotEmpty() && DecoderSupport.canDecodeHevc()) {
            return resolveAbsoluteUrl(hlsHevcUrl)
        }
        val hlsUrl = stream?.hlsUrl?.trim().orEmpty()
        if (hlsUrl.isNotEmpty()) return resolveAbsoluteUrl(hlsUrl)

        val streamName = stream?.streamName?.trim().orEmpty()
        if (streamName.isEmpty() || baseUrl.isBlank()) return ""
        return "${baseUrl.trimEnd('/')}/api/stream.m3u8?src=${Uri.encode(streamName)}&mp4="
    }

    private fun resolveAbsoluteUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val baseUrl = container.getApiBaseUrl().orEmpty()
        if (baseUrl.isBlank()) return url
        val origin = baseUrl.trimEnd('/')
        return if (url.startsWith('/')) "$origin$url" else "$origin/$url"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    // --- Preset list adapter (inline) ---

    private class PresetListAdapter(
        private val isAdmin: Boolean,
        private val onGoto: (CameraPreset) -> Unit,
        private val onDelete: (CameraPreset) -> Unit,
    ) : RecyclerView.Adapter<PresetListAdapter.VH>() {

        private val items = mutableListOf<CameraPreset>()

        fun submit(list: List<CameraPreset>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(32, 32, 32, 32)
                textSize = 14f
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val preset = items[position]
            val tv = (holder.itemView as TextView)
            tv.text = buildString {
                append(preset.name.ifBlank { preset.token })
                if (preset.name.isNotBlank()) {
                    append("  (").append(preset.token).append(")")
                }
            }
            tv.setOnClickListener { onGoto(preset) }
            tv.setOnLongClickListener {
                if (isAdmin) {
                    onDelete(preset)
                    true
                } else false
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        private const val TAG = "CameraDetailActivity"
        const val EXTRA_CAMERA_JSON = "camera_json"
        // v1.6.0: optional initial playback position as a unix
        // timestamp (seconds). When present (passed by the alerts
        // fragment when the user taps "查看录像" on an alert), the
        // activity auto-opens the RecordingsDialog with this timestamp
        // so playback starts at the alert's exact time.
        const val EXTRA_INITIAL_TIMESTAMP = "initial_timestamp"
        // v1.6.23: removed cachedIpv6Available / lastIpv6CheckMs +
        // checkPhoneIpv6Connectivity(). IPv6 reachability is now
        // probed by BaseUrlResolver.probeSync() as part of the
        // three-tier URL resolution (LAN > IPv6 direct > Tunnel).
        // The old ConnectivityManager-based check was redundant with
        // the resolver's HTTP probe to the IPv6 direct URL — if the
        // resolver picked IPV6_DIRECT_URL, both phone-IPv6 and
        // NAS-IPv6-reachability are already confirmed.
    }
}
