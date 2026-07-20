package com.homedatacenter.app.ui.cameras

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import com.homedatacenter.app.util.ExoPlayerRendererFactory
import com.homedatacenter.app.util.PlayerFullscreenHelper
import com.homedatacenter.app.util.WebRtcClient
import kotlinx.coroutines.launch
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
    private var triedWebRtc = false
    private var triedMp4 = false
    private var triedHls = false
    private var audioEnabled = true
    private var recordingsDialog: RecordingsDialog? = null
    private var alertsDialog: AlertsDialog? = null
    private var fullscreenHelper: PlayerFullscreenHelper? = null

    // WebRTC live stream client (v1.5.3 primary path). Lazily
    // initialized in onCreate after the camera is known; shutdown
    // in onDestroy releases the PeerConnectionFactory + EGL context.
    private var webRtcClient: WebRtcClient? = null
    // True while a WebRTC attempt is in-flight — prevents the
    // reload button from triggering overlapping offers.
    private var webRtcInProgress = false
    // Cached ICE config from /api/v1/cameras/ice — fetched once
    // per activity instance (re-fetched on retry if null).
    private var cachedIceConfig: IceConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityCameraDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container
        isAdmin = container.prefsManager.isAdmin

        binding.toolbar.setNavigationOnClickListener { finish() }

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
        setupPtz()
        setupPresets()
        setupSettings()

        loadPresets()
    }

    override fun onPause() {
        super.onPause()
        // Release the MediaCodec when leaving the foreground so it
        // can be reclaimed by other apps. Playback resumes on resume
        // via setupVideo() — actually, we don't auto-resume here to
        // keep the behavior predictable; the user taps "重新加载"
        // to restart the stream. This avoids buffering loops when
        // the activity is briefly paused (e.g. notification shade).
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        fullscreenHelper?.release()
        recordingsDialog?.dismiss()
        alertsDialog?.dismiss()
        recordingsDialog = null
        alertsDialog = null
        // Release order matters: SurfaceViewRenderer's EGL surfaces
        // must be torn down BEFORE the PeerConnectionFactory's
        // EglBase context is released, otherwise eglReleaseSurface
        // can throw. The try/catch guards against double-release.
        try { binding.surfaceRenderer.release() } catch (_: Exception) {}
        try { webRtcClient?.shutdown() } catch (_: Exception) {}
        webRtcClient = null
    }

    private fun setupHeader() {
        val cam = camera ?: return
        binding.tvCameraName.text = cam.name
        binding.tvCameraMeta.text = buildString {
            if (cam.vendor.isNotBlank()) append(cam.vendor)
            if (cam.host.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(cam.host)
            }
            if (cam.codec.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("codec=").append(cam.codec.uppercase())
            }
        }
        binding.tvCameraStatus.text = if (cam.isOnline) {
            getString(R.string.camera_online)
        } else {
            getString(R.string.camera_offline)
        }
        binding.tvCameraStatus.setTextColor(
            getColor(if (cam.isOnline) R.color.online else R.color.offline)
        )
    }

    private fun setupPtz() {
        val cam = camera ?: return
        if (!cam.hasPtz) {
            binding.tvPtzUnsupported.visibility = View.VISIBLE
            binding.ptzGrid.visibility = View.GONE
            binding.seekPtzSpeed.isEnabled = false
            return
        }
        binding.tvPtzUnsupported.visibility = View.GONE
        binding.ptzGrid.visibility = View.VISIBLE

        val buttons = mapOf(
            binding.btnPtzUp to "up",
            binding.btnPtzDown to "down",
            binding.btnPtzLeft to "left",
            binding.btnPtzRight to "right",
            binding.btnPtzStop to "stop",
        )
        buttons.forEach { (btn, command) ->
            // PTZ is admin-gated on the server; hide for non-admin to
            // avoid confusion. Server remains authoritative.
            btn.visibility = if (isAdmin) View.VISIBLE else View.GONE
            btn.setOnClickListener { sendPtz(command) }
        }
        if (!isAdmin) {
            binding.tvPtzUnsupported.visibility = View.VISIBLE
            binding.tvPtzUnsupported.text = getString(R.string.camera_admin_required)
            binding.ptzGrid.visibility = View.GONE
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
        binding.btnCodecH264.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnCodecH264.setOnClickListener { updateCodec() }

        // Delete camera — admin only.
        binding.btnDelete.visibility = if (isAdmin) View.VISIBLE else View.GONE
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
                binding.cardHeader,
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

        startPlayback()
    }

    private fun setupActions() {
        binding.btnRecordings.setOnClickListener { showRecordings() }
        binding.btnAlerts.setOnClickListener { showAlerts() }
    }

    private fun showRecordings() {
        val cam = camera ?: return
        recordingsDialog?.dismiss()
        recordingsDialog = RecordingsDialog(this, cam, container).apply { show() }
    }

    private fun showAlerts() {
        val cam = camera ?: return
        alertsDialog?.dismiss()
        alertsDialog = AlertsDialog(this, cam, container).apply { show() }
    }

    private fun startPlayback() {
        val cam = camera ?: return
        // Reset the fallback ladder. The reload button should always
        // try WebRTC first (it's the lowest-latency transport and the
        // one the user explicitly asked for in v1.5.3).
        triedWebRtc = false
        triedMp4 = false
        triedHls = false

        // WebRTC is the primary live transport (v1.5.3). It only
        // applies to ONLINE cameras — an offline camera has no
        // go2rtc stream to offer.
        if (cam.isOnline && ensureWebRtcClient()) {
            binding.tvVideoError.visibility = View.GONE
            binding.progressVideo.visibility = View.VISIBLE
            startWebRtcStream(cam)
            return
        }

        // WebRTC unavailable (offline camera, factory init failed,
        // or signaling already in progress) — fall straight through
        // to the ExoPlayer MP4/HLS path.
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
        val baseUrl = container.getApiBaseUrl().ifBlank { return false }
        val token = container.prefsManager.token
        return try {
            // Prefer the warmed-up client. takeWarmWebRtcClient
            // atomically removes it from the warm cache (one-shot).
            val client = container.takeWarmWebRtcClient() ?: WebRtcClient(
                context = this,
                okHttpClient = container.okHttpClient,
                baseUrl = baseUrl,
                token = token,
            ).also { it.init() }
            webRtcClient = client
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

        // Show the WebRTC surface + standalone fullscreen button,
        // hide the ExoPlayer view (its controller UI is irrelevant
        // while WebRTC is rendering).
        binding.playerView.visibility = View.GONE
        binding.surfaceRenderer.visibility = View.VISIBLE
        binding.btnWebRtcFullscreen.visibility = View.VISIBLE
        binding.progressVideo.visibility = View.VISIBLE
        binding.tvVideoError.visibility = View.GONE

        // Fetch ICE config (cached after first call). The list may
        // be empty on LAN — host candidates are enough there.
        lifecycleScope.launch {
            val iceConfig = cachedIceConfig ?: try {
                val token = container.prefsManager.token ?: ""
                container.getRepository().getIceConfig(token).also {
                    cachedIceConfig = it
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "ICE config fetch failed: ${e.message}")
                null
            }
            // Convert to PeerConnection.IceServer list expected by
            // WebRtcClient. Empty list is OK — host candidates will
            // be gathered automatically.
            val iceServers = iceConfig?.ice_servers?.map { srv ->
                PeerConnection.IceServer.builder(srv.urls).apply {
                    srv.username?.let { setUsername(it) }
                    srv.credential?.let { setPassword(it) }
                }.createIceServer()
            } ?: emptyList()

            client.startStream(
                cameraId = cam.id,
                surfaceRenderer = binding.surfaceRenderer,
                iceServers = iceServers,
                listener = object : WebRtcClient.Listener {
                    override fun onConnected() {
                        webRtcInProgress = false
                        binding.progressVideo.visibility = View.GONE
                        binding.tvVideoError.visibility = View.GONE
                        android.util.Log.d(TAG, "WebRTC connected")
                    }

                    override fun onError(reason: String) {
                        webRtcInProgress = false
                        android.util.Log.w(TAG, "WebRTC failed: $reason — falling back to MP4")
                        // Hide WebRTC surface, show ExoPlayer surface
                        // and let preparePlayback() set up the player.
                        binding.surfaceRenderer.visibility = View.GONE
                        binding.btnWebRtcFullscreen.visibility = View.GONE
                        binding.playerView.visibility = View.VISIBLE
                        startMp4Playback(cam)
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
            binding.btnWebRtcFullscreen.visibility = View.GONE
            return
        }
        binding.tvVideoError.visibility = View.GONE
        // MP4 (fMP4 stream via home-api proxy) is the primary
        // ExoPlayer transport — HLS Init() on go2rtc can 404 on
        // cold streams. See the inline-playback comment in the
        // previous CameraAdapter for the full rationale.
        preparePlayback(mp4Url, hlsUrl, useMp4 = true)
    }

    private fun preparePlayback(mp4Url: String, hlsUrl: String, useMp4: Boolean) {
        releasePlayer()
        val url = if (useMp4) mp4Url else hlsUrl
        if (url.isBlank()) {
            binding.tvVideoError.visibility = View.VISIBLE
            return
        }

        binding.progressVideo.visibility = View.VISIBLE
        binding.tvVideoError.visibility = View.GONE

        val token = container.prefsManager.token
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            // Cloudflare Tunnel from China can be very slow (TTFB
            // 1.4s+, 10s+ timeouts). Generous timeouts let the
            // stream establish before ExoPlayer gives up.
            setConnectTimeoutMs(30_000)
            setReadTimeoutMs(60_000)
            setUserAgent("HomeDatacenter/1.5")
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
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(3_000)
                        .setMaxOffsetMs(10_000)
                        .setMinOffsetMs(1_000)
                        .build()
                )
                .build()
            HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }

        // Low-latency load control so the first frame appears in
        // ~1-2s instead of 5-10s. Tuned to satisfy ExoPlayer's
        // buffer-duration invariants (see DefaultLoadControl).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 2_000,
                /* maxBufferMs= */ 5_000,
                /* bufferForPlaybackMs= */ 1_000,
                /* bufferForPlaybackAfterRebufferMs= */ 1_500,
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
                        binding.tvVideoError.visibility = View.GONE
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
                    // Failover: MP4 → HLS → give up.
                    if (useMp4 && hlsUrl.isNotBlank() && !triedHls) {
                        preparePlayback(mp4Url, hlsUrl, useMp4 = false)
                    } else {
                        releasePlayer()
                        binding.tvVideoError.visibility = View.VISIBLE
                        binding.progressVideo.visibility = View.GONE
                    }
                }
            })
            prepare()
        }
        player = newPlayer
        fullscreenHelper?.onPlayerChanged(newPlayer)
    }

    private fun releasePlayer() {
        // Release both ExoPlayer and the WebRTC PeerConnection so the
        // next startPlayback() can re-establish either one cleanly.
        try { webRtcClient?.release() } catch (_: Exception) {}
        webRtcInProgress = false
        val old = player
        player = null
        binding.playerView.player = null
        old?.release()
        triedWebRtc = false
        triedMp4 = false
        triedHls = false
        // Hide the WebRTC-only overlay so it doesn't linger when the
        // user taps reload (which switches back to playerView during
        // the next WebRTC attempt's setup phase).
        binding.btnWebRtcFullscreen.visibility = View.GONE
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
        val hlsUrl = camera.stream?.hlsUrl?.trim().orEmpty()
        if (hlsUrl.isNotEmpty()) return resolveAbsoluteUrl(hlsUrl)

        val streamName = camera.stream?.streamName?.trim().orEmpty()
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
    }
}
