package com.homedatacenter.app.ui.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.databinding.ItemCameraBinding
import com.homedatacenter.app.util.AnimationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class CameraAdapter(
    private val onRecordingsClick: (Camera) -> Unit,
    private val onAlertsClick: (Camera) -> Unit,
    private val baseUrl: String? = null,
    private val token: String? = null,
    private val okHttpClient: OkHttpClient? = null,
) : ListAdapter<Camera, CameraAdapter.CameraViewHolder>(DiffCallback()) {

    private val activePlayers = mutableSetOf<ExoPlayer>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CameraViewHolder {
        val binding = ItemCameraBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CameraViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CameraViewHolder, position: Int) {
        holder.bind(getItem(position))
        AnimationHelper.slideInBottom(holder.itemView, 80L)
    }

    override fun onViewRecycled(holder: CameraViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: CameraViewHolder) {
        holder.releasePlayer()
        super.onViewDetachedFromWindow(holder)
    }

    fun releaseAllPlayers() {
        activePlayers.toList().forEach { it.release() }
        activePlayers.clear()
    }

    inner class CameraViewHolder(
        private val binding: ItemCameraBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var thumbnailJob: Job? = null
        private var boundCamera: Camera? = null
        private var player: ExoPlayer? = null
        // Track which source we've already tried so the error handler
        // can fail over from MP4 → HLS exactly once per playback session.
        private var triedMp4 = false
        private var triedHls = false

        private var thumbnail by mutableStateOf<Bitmap?>(null)
        private var thumbnailLoading by mutableStateOf(false)
        private var thumbnailError by mutableStateOf(false)
        private var isPlaying by mutableStateOf(false)
        private var playerLoading by mutableStateOf(false)

        init {
            binding.composeView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
        }

        fun bind(camera: Camera) {
            val cameraChanged = boundCamera?.id != camera.id
            if (cameraChanged) {
                releasePlayer()
                thumbnailJob?.cancel()
                thumbnail = null
                thumbnailError = false
                boundCamera = camera
                loadThumbnail(camera)
            } else {
                boundCamera = camera
            }

            binding.composeView.setContent {
                MaterialTheme {
                    CameraCard(
                        camera = camera,
                        thumbnail = thumbnail,
                        thumbnailLoading = thumbnailLoading,
                        thumbnailError = thumbnailError,
                        isPlaying = isPlaying,
                        playerLoading = playerLoading,
                        player = player,
                        onPlay = { startInlinePlayback(camera) },
                        onStop = ::stopInlinePlayback,
                        onRecordings = { onRecordingsClick(camera) },
                        onAlerts = { onAlertsClick(camera) },
                    )
                }
            }
        }

        private fun startInlinePlayback(camera: Camera) {
            if (isPlaying) return
            val mp4Url = resolveMp4Url(camera)
            val hlsUrl = resolveHlsUrl(camera)
            if (hlsUrl.isBlank() && mp4Url.isBlank()) {
                android.util.Log.w(
                    TAG,
                    "No stream URL for camera '${camera.name}' (stream=${camera.stream})",
                )
                thumbnailError = true
                return
            }

            releasePlayer()
            isPlaying = true
            playerLoading = true
            triedMp4 = false
            triedHls = false
            // HLS is the primary transport: it is the path go2rtc's
            // stream.m3u8 produces natively and ExoPlayer's HlsMediaSource
            // handles it robustly (incremental moof parsing, adaptive
            // segments). The MP4 (fMP4) endpoint at /stream.mp4 is kept
            // as a fallback because ExoPlayer's ProgressiveMediaSource is
            // designed for finite files and may stall on the indefinite
            // fMP4 stream that go2rtc emits.
            preparePlayback(camera, mp4Url, hlsUrl, useMp4 = false)
        }

        /**
         * Builds the ExoPlayer + MediaSource for the chosen transport and
         * wires the error listener that performs HLS → MP4 failover.
         *
         * HLS is preferred for live streams because ExoPlayer handles
         * its incremental moof parsing natively. The low-latency load
         * control below cuts live sync from the default 5-10s to ~3s.
         * Backend Preheat (see registry.go) warms go2rtc's RTSP source
         * at camera registration time so the first segment is ready
         * before the user taps play.
         */
        private fun preparePlayback(
            camera: Camera,
            mp4Url: String,
            hlsUrl: String,
            useMp4: Boolean,
        ) {
            releasePlayer()
            playerLoading = true

            val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
                setConnectTimeoutMs(8_000)
                setReadTimeoutMs(15_000)
                setUserAgent("HomeDatacenter/1.3")
                if (!token.isNullOrEmpty()) {
                    setDefaultRequestProperties(
                        mapOf(
                            "Authorization" to "Bearer $token",
                            "Cookie" to "home_token=$token",
                        ),
                    )
                }
            }

            val url = if (useMp4) mp4Url else hlsUrl
            if (url.isBlank()) {
                android.util.Log.w(TAG, "preparePlayback: empty URL (useMp4=$useMp4)")
                stopInlinePlayback()
                return
            }
            val mediaSource = if (useMp4) {
                triedMp4 = true
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            } else {
                triedHls = true
                // Live configuration: target ~3s end-to-end latency. ExoPlayer
                // uses these values to decide when to seek back to the live
                // edge and how much buffer to maintain. Lower than 3s causes
                // underruns on mobile networks; higher feels laggy.
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

            // Low-latency load control: short initial buffering so the
            // first frame appears in ~1-2s instead of 5-10s. Tuned for
            // a steady HLS feed from go2rtc (small segments, frequent
            // discontinuities handled by the HlsMediaSource).
            //
            // ExoPlayer constraints (see DefaultLoadControl.assertGreaterOrEqual):
            //   minBufferMs      >= bufferForPlaybackMs
            //   minBufferMs      >= bufferForPlaybackAfterRebufferMs
            //   maxBufferMs      >= minBufferMs
            // We pick 2000/5000/1000/1500 to satisfy all of them while
            // keeping first-frame latency low.
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

            val newPlayer = ExoPlayer.Builder(itemView.context)
                .setLoadControl(loadControl)
                .build().apply {
                    setMediaSource(mediaSource)
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            playerLoading = state == Player.STATE_BUFFERING
                            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                                playerLoading = false
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            android.util.Log.e(
                                TAG,
                                "Playback error for '${camera.name}' (useMp4=$useMp4): ${error.message}",
                                error,
                            )
                            // Failover: HLS → MP4, then MP4 → give up.
                            if (!useMp4 && mp4Url.isNotBlank() && !triedMp4) {
                                android.util.Log.i(
                                    TAG,
                                    "Falling back from HLS to MP4 for '${camera.name}'",
                                )
                                preparePlayback(camera, mp4Url, hlsUrl, useMp4 = true)
                            } else {
                                stopInlinePlayback()
                            }
                        }
                    })
                    prepare()
                }
            player = newPlayer
            activePlayers.add(newPlayer)
        }

        private fun stopInlinePlayback() {
            val oldPlayer = player
            player = null
            isPlaying = false
            playerLoading = false
            if (oldPlayer != null) {
                activePlayers.remove(oldPlayer)
                oldPlayer.release()
            }
        }

        fun releasePlayer() {
            stopInlinePlayback()
        }

        private fun resolveMp4Url(camera: Camera): String {
            // Preferred: the new home-api proxy that streams fragmented MP4
            // straight from go2rtc. We hit our own /api/v1/cameras/:id/stream.mp4
            // (no need to know the go2rtc base URL on the client). ExoPlayer
            // consumes the infinite fMP4 stream via ProgressiveMediaSource.
            if (baseUrl.isNullOrBlank()) return ""
            val base = baseUrl.trimEnd('/')
            return "$base/api/v1/cameras/${camera.id}/stream.mp4"
        }

        private fun resolveHlsUrl(camera: Camera): String {
            val hlsUrl = camera.stream?.hlsUrl?.trim().orEmpty()
            if (hlsUrl.isNotEmpty()) return resolveAbsoluteUrl(hlsUrl)

            val streamName = camera.stream?.streamName?.trim().orEmpty()
            if (streamName.isEmpty() || baseUrl.isNullOrBlank()) return ""
            val base = baseUrl.trimEnd('/')
            return "$base/api/stream.m3u8?src=${Uri.encode(streamName)}&mp4="
        }

        private fun resolveAbsoluteUrl(url: String): String {
            if (url.startsWith("http://") || url.startsWith("https://")) return url
            if (baseUrl.isNullOrBlank()) return url
            val origin = baseUrl.trimEnd('/')
            return if (url.startsWith('/')) "$origin$url" else "$origin/$url"
        }

        private fun loadThumbnail(camera: Camera) {
            val snapshotUrl = buildSnapshotUrl(camera.id)
            if (snapshotUrl.isEmpty()) {
                thumbnailLoading = false
                thumbnailError = true
                return
            }

            thumbnailLoading = true
            thumbnailError = false
            thumbnailJob = scope.launch {
                val bitmap = fetchScreenshot(snapshotUrl)
                withContext(Dispatchers.Main) {
                    if (boundCamera?.id == camera.id && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        thumbnail = bitmap
                        thumbnailLoading = false
                        thumbnailError = bitmap == null
                    }
                }
            }
        }

        private suspend fun fetchScreenshot(url: String): Bitmap? = withContext(Dispatchers.IO) {
            val client = okHttpClient ?: OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (!token.isNullOrEmpty()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.w(TAG, "Frame HTTP ${response.code} for $url")
                        return@use null
                    }
                    response.body?.byteStream()?.use(BitmapFactory::decodeStream)
                }
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Frame fetch failed for $url: ${error.message}")
                null
            }
        }

        private fun buildSnapshotUrl(cameraId: Long): String {
            if (baseUrl.isNullOrBlank()) return ""
            return "${baseUrl.trimEnd('/')}/api/v1/cameras/$cameraId/frame"
        }

        fun recycle() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            releasePlayer()
            boundCamera = null
            thumbnail = null
            thumbnailLoading = false
            thumbnailError = false
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Camera>() {
        override fun areItemsTheSame(oldItem: Camera, newItem: Camera): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Camera, newItem: Camera): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TAG = "CameraAdapter"
    }
}
