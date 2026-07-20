package com.homedatacenter.app.ui.cameras

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.databinding.DialogAlertsBinding
import com.homedatacenter.app.databinding.ItemAlertBinding
import com.homedatacenter.app.util.ExoPlayerRendererFactory
import com.homedatacenter.app.util.PlayerFullscreenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertsDialog(
    context: Context,
    private val camera: Camera,
    private val container: AppContainer
) : Dialog(context, R.style.FullScreenDialog) {

    private lateinit var binding: DialogAlertsBinding
    private lateinit var adapter: AlertAdapter
    private val baseUrl = container.getApiBaseUrl()
    private val token = container.prefsManager.token
    private val okHttpClient = container.okHttpClient
    private var player: ExoPlayer? = null
    private var fullscreenHelper: PlayerFullscreenHelper? = null

    init {
        binding = DialogAlertsBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        setupRecyclerView()
        loadAlerts()
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.toolbar.title = "${camera.name} - 报警"
    }

    private fun setupRecyclerView() {
        adapter = AlertAdapter(
            baseUrl = baseUrl,
            token = token,
            okHttpClient = okHttpClient,
            onPlayAlert = { alert -> playAlert(alert) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
    }

    private fun loadAlerts() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.tvEmpty.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val authHeader = if (!token.isNullOrEmpty()) "Bearer $token" else ""
                android.util.Log.d("AlertsDialog",
                    "Fetching alerts (camera_id=${camera.id}, name='${camera.name}')")
                val resp = container.getApi().listAlerts(authHeader, limit = 50)
                android.util.Log.d("AlertsDialog",
                    "API response: code=${resp.code}, message='${resp.message}', data=${resp.data}")

                val allAlerts = if (resp.isSuccess) {
                    val decoded = resp.decodeData<AlertListData>()
                    android.util.Log.d("AlertsDialog",
                        "Decoded ${decoded?.alerts?.size ?: 0} alerts (total=${decoded?.total})")
                    decoded?.alerts ?: emptyList()
                } else {
                    android.util.Log.w("AlertsDialog",
                        "API returned error: code=${resp.code}, message='${resp.message}'")
                    emptyList()
                }

                // Filter alerts for this camera. The backend annotates alerts with
                // camera_id via LookupByFrigateSlug — if the slug doesn't match,
                // camera_id is null/0. Try multiple matching strategies.
                val filtered = filterAlertsForCamera(allAlerts, allAlerts)

                withContext(Dispatchers.Main) {
                    adapter.submitList(filtered)
                    binding.progressBar.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                    if (filtered.isEmpty()) {
                        binding.tvEmpty.text = if (resp.isSuccess) {
                            if (allAlerts.isEmpty()) "暂无报警事件"
                            else "未找到此摄像头的报警（共 ${allAlerts.size} 条）"
                        } else {
                            "加载失败: ${resp.message}"
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AlertsDialog", "Exception loading alerts: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.text = "加载失败: ${e.message}"
                }
            }
        }
    }

    // Try to match alerts to this camera using multiple strategies.
    // 1. Match by camera_id (most reliable when backend maps correctly)
    // 2. Match by camera_slug against the camera's slugified stream name
    // 3. If nothing matches but there are alerts, show all (so the user sees something)
    private fun filterAlertsForCamera(
        alerts: List<Alert>,
        allAlerts: List<Alert>
    ): List<Alert> {
        if (alerts.isEmpty()) return emptyList()

        // Strategy 1: match by camera_id
        val byId = alerts.filter { it.cameraId != null && it.cameraId == camera.id }
        if (byId.isNotEmpty()) {
            android.util.Log.d("AlertsDialog", "Matched ${byId.size} alerts by camera_id")
            return byId
        }

        // Strategy 2: match by camera_slug against slugified stream name
        val streamName = camera.stream?.streamName?.trim() ?: ""
        if (streamName.isNotEmpty()) {
            val slug = slugify(streamName)
            val bySlug = alerts.filter {
                it.cameraSlug.equals(slug, ignoreCase = true) ||
                it.cameraSlug.equals(streamName, ignoreCase = true) ||
                it.cameraName.equals(camera.name, ignoreCase = true)
            }
            if (bySlug.isNotEmpty()) {
                android.util.Log.d("AlertsDialog", "Matched ${bySlug.size} alerts by slug/name")
                return bySlug
            }
        }

        // Strategy 3: if alerts exist but none match, return all — better than showing nothing.
        // The user can see cameraName in each item to identify which camera it belongs to.
        android.util.Log.d("AlertsDialog",
            "No alerts matched camera ${camera.id}; showing all ${allAlerts.size}")
        return allAlerts
    }

    private fun slugify(name: String): String {
        val cn = mapOf(
            "前门" to "front_door", "后门" to "back_door",
            "客厅" to "living_room", "卧室" to "bedroom",
            "厨房" to "kitchen", "院子" to "yard", "车库" to "garage"
        )
        cn[name]?.let { return it }
        return name.lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
    }

    private fun playAlert(alert: Alert) {
        // The :recId path param is a unix timestamp; backend buckets it to the
        // containing minute and concatenates 10s Frigate segments into one MP4.
        val recId = alert.startTime.toLong()
        val url = buildAlertPlayUrl(recId)
        if (url.isEmpty()) return

        android.util.Log.d("AlertsDialog", "Playing alert clip from $url")

        binding.videoContainer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        player?.release()
        // Delegate to ExoPlayerRendererFactory: on emulators it filters
        // out the broken goldfish decoders, on real devices it just
        // enables decoder fallback. See util/ExoPlayerRendererFactory.kt.
        val renderersFactory = ExoPlayerRendererFactory.create(context)
        player = ExoPlayer.Builder(context, renderersFactory).build().apply {
            // Route audio through the media stream and let ExoPlayer
            // manage AudioFocus — pauses on phone call, resumes after.
            setAudioAttributes(
                com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
                setConnectTimeoutMs(15000)
                setReadTimeoutMs(60000)
                if (!token.isNullOrEmpty()) {
                    setDefaultRequestProperties(mutableMapOf(
                        "Authorization" to "Bearer $token",
                        "Cookie" to "home_token=$token"
                    ))
                }
            }
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))
            setMediaSource(mediaSource)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.progressPlayer.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("AlertsDialog", "Player error: ${error.message}")
                }
            })
            prepare()
        }
        binding.playerView.player = player
        binding.btnBack.setOnClickListener {
            player?.release()
            player = null
            fullscreenHelper?.release()
            fullscreenHelper = null
            binding.btnPlaybackSpeed.visibility = View.GONE
            binding.btnFullscreen.visibility = View.GONE
            binding.videoContainer.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }

        // Attach fullscreen + speed button handlers. Same pattern as
        // RecordingsDialog: fullscreen rotates the host Activity to
        // landscape + hides toolbar/back button; speed button opens
        // a popup with 0.5x / 1x / 1.5x / 2x options.
        if (fullscreenHelper == null) {
            val helper = PlayerFullscreenHelper(
                playerView = binding.playerView,
                hostView = binding.root,
                hideOnFullscreen = listOf(binding.toolbar, binding.btnBack),
                speedButton = binding.btnPlaybackSpeed,
                fullscreenButton = binding.btnFullscreen,
            )
            helper.attach()
            fullscreenHelper = helper
        }
        fullscreenHelper?.onPlayerChanged(player)
        binding.btnPlaybackSpeed.visibility = View.VISIBLE
        binding.btnFullscreen.visibility = View.VISIBLE
    }

    private fun buildAlertPlayUrl(recId: Long): String {
        if (baseUrl.isNullOrBlank()) return ""
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "${base}api/v1/cameras/${camera.id}/recordings/$recId/file"
    }

    override fun dismiss() {
        player?.release()
        player = null
        fullscreenHelper?.release()
        fullscreenHelper = null
        super.dismiss()
    }

    class AlertAdapter(
        private val baseUrl: String?,
        private val token: String?,
        private val okHttpClient: OkHttpClient?,
        private val onPlayAlert: (Alert) -> Unit
    ) : ListAdapter<Alert, AlertAdapter.AlertViewHolder>(DiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
            val b = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return AlertViewHolder(b)
        }

        override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class AlertViewHolder(private val binding: ItemAlertBinding) : RecyclerView.ViewHolder(binding.root) {
            private var thumbnailJob: Job? = null

            fun bind(alert: Alert) {
                binding.tvLabel.text = alert.label
                binding.tvConfidence.text = "${(alert.confidence * 100).toInt()}%"
                binding.tvCamera.text = alert.cameraName.ifEmpty { alert.cameraSlug.ifEmpty { "Unknown" } }

                val date = Date((alert.startTime * 1000).toLong())
                binding.tvTime.text = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(date)

                val zones = alert.zones
                binding.tvZones.text = if (zones.isNotEmpty()) zones.joinToString(", ") else "—"

                // Thumbnail: try base64 first, then URL endpoint
                loadThumbnail(alert)

                binding.btnPlay.setOnClickListener {
                    if (alert.hasClip) onPlayAlert(alert)
                }
                if (!alert.hasClip) {
                    binding.btnPlay.visibility = View.GONE
                }
            }

            private fun loadThumbnail(alert: Alert) {
                // Try base64 thumbnail first
                if (alert.thumbnail.isNotEmpty()) {
                    try {
                        val bytes = Base64.decode(alert.thumbnail, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            binding.ivThumbnail.setImageBitmap(bitmap)
                            return
                        }
                    } catch (_: Exception) {
                    }
                }

                // Fall back to URL endpoint
                val url = buildThumbnailUrl(alert.id)
                if (url.isEmpty()) return

                binding.progressThumbnail.visibility = View.VISIBLE
                thumbnailJob?.cancel()
                thumbnailJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val client = okHttpClient ?: OkHttpClient()
                        val req = Request.Builder().url(url).apply {
                            if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                        }.build()
                        val bitmap = client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                android.util.Log.w("AlertsDialog", "Thumbnail HTTP ${resp.code} for $url")
                                return@use null
                            }
                            resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                        }
                        withContext(Dispatchers.Main) {
                            binding.progressThumbnail.visibility = View.GONE
                            if (bitmap != null) {
                                binding.ivThumbnail.setImageBitmap(bitmap)
                            }
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            binding.progressThumbnail.visibility = View.GONE
                        }
                    }
                }
            }

            private fun buildThumbnailUrl(alertId: String): String {
                if (baseUrl.isNullOrBlank()) return ""
                val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                return "${base}api/v1/cameras/alerts/$alertId/thumbnail"
            }
        }

        class DiffCallback : DiffUtil.ItemCallback<Alert>() {
            override fun areItemsTheSame(oldItem: Alert, newItem: Alert) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Alert, newItem: Alert) = oldItem == newItem
        }
    }
}
