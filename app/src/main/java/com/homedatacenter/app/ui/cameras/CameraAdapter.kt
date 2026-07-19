package com.homedatacenter.app.ui.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
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
import com.homedatacenter.app.util.ExoPlayerRendererFactory
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

    // LRU thumbnail cache shared across all view holders. Without
    // this, scrolling the camera list re-fetches the snapshot JPEG
    // on every bind (each scroll triggers onBindViewHolder →
    // loadThumbnail → HTTP GET through Cloudflare Tunnel → go2rtc
    // RTSP keyframe → JPEG encode). On a slow Cloudflare Tunnel from
    // China (TTFB 1.4s+, frequent 10s timeouts) this makes the
    // camera list feel broken. Cache 16 snapshots (~16 × 50KB =
    // 800KB max memory) — enough for a typical camera fleet, small
    // enough to not pressure the heap.
    private val thumbnailCache = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean {
            return size > 16
        }
    }

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
                thumbnailError = false
                boundCamera = camera
                // Check cache first — if we have a cached snapshot
                // for this camera, show it immediately and skip the
                // HTTP fetch. This makes scroll-back instant instead
                // of re-downloading every thumbnail on each bind.
                val cached = thumbnailCache[camera.id]
                if (cached != null) {
                    thumbnail = cached
                    thumbnailLoading = false
                } else {
                    thumbnail = null
                    loadThumbnail(camera)
                }
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
            android.util.Log.i(
                TAG,
                "startInlinePlayback: camera='${camera.name}' id=${camera.id} " +
                    "mp4Url=${if (mp4Url.isBlank()) "(empty)" else mp4Url.take(80)} " +
                    "hlsUrl=${if (hlsUrl.isBlank()) "(empty)" else hlsUrl.take(80)}",
            )
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
            // MP4 (fMP4 stream via home-api proxy) is the primary
            // transport. The home-api /api/v1/cameras/:id/stream.mp4
            // endpoint proxies go2rtc's stream.mp4 — a continuous
            // fMP4 stream with chunked transfer encoding. ExoPlayer's
            // ProgressiveMediaSource handles it natively.
            //
            // We do NOT use HLS as primary anymore because go2rtc's
            // HLS Init() helper (internal/hls/session.go) waits only
            // 3 seconds (60 × 50ms) for the consumer to produce a
            // second packet before returning nil → handlerInit
            // responds 404. On cold streams or transcoded HEVC→H.264
            // paths the second packet can take longer than 3s, and
            // ExoPlayer does not retry the init.mp4 request — it
            // surfaces the 404 as a Source error. hls.js retries
            // transparently, but ExoPlayer does not, so MP4-first
            // is the robust choice. HLS remains as fallback in case
            // the MP4 proxy is unavailable.
            preparePlayback(camera, mp4Url, hlsUrl, useMp4 = true)
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
                // Cloudflare Tunnel from China can be very slow and
                // unreliable (tested: TTFB 1.4s average, 10s+ timeouts
                // on ~1/3 of requests). Use generous timeouts so the
                // stream has a chance to establish and the first moof/
                // mdat has time to arrive after go2rtc's HEVC→H.264
                // transcode produces a keyframe. The previous 8s/15s
                // was too short — ExoPlayer would give up before the
                // tunnel delivered the first byte on a slow request.
                setConnectTimeoutMs(30_000)
                setReadTimeoutMs(60_000)
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
            android.util.Log.i(
                TAG,
                "preparePlayback: camera='${camera.name}' useMp4=$useMp4 url=${url.take(100)}",
            )
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

            // Renderers: delegate to ExoPlayerRendererFactory, which
            // enables software-decoder fallback AND on emulators filters
            // out the broken c2.goldfish.h264.decoder (it initializes
            // cleanly but fails on every frame with "decoder function
            // returned error but continuing for this codec", which
            // surfaces as a frozen spinner with no onPlayerError).
            // On real devices the factory keeps the default
            // MediaCodecSelector so hardware decoders are still preferred.
            val renderersFactory = ExoPlayerRendererFactory.create(itemView.context)

            val newPlayer = ExoPlayer.Builder(itemView.context, renderersFactory)
                .setLoadControl(loadControl)
                .build()
            // CRITICAL: attach the surface BEFORE prepare(). ExoPlayer
            // creates the MediaCodec during prepare() — if no surface is
            // attached at that point, the codec initializes in no-surface
            // mode. Later surface attachment then fails with
            // `setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)`
            // on software decoders (c2.android.avc.decoder), and 98% of
            // decoded buffer transfers stay "unfetched" — the surface
            // never consumes frames, state reaches READY but no video
            // appears. This matches the working RecordingsDialog pattern
            // where StyledPlayerView exists in XML and the player is set
            // before the codec is created.
            binding.playerView.player = newPlayer
            binding.playerView.visibility = View.VISIBLE
            // Route audio through the media stream (not notification) and
            // request AudioFocus so playback pauses other apps' audio.
            // Without this the player would still play sound, but at the
            // system default volume and without ducking other audio
            // sources — bad UX when the user has music playing. The
            // handleAudioFocus=true flag automatically pauses playback
            // when another app takes focus (e.g. phone call) and resumes
            // when focus returns.
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
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val stateName = when (state) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN($state)"
                        }
                        android.util.Log.i(
                            TAG,
                            "onPlaybackStateChanged: camera='${camera.name}' state=$stateName useMp4=$useMp4",
                        )
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
                        // Failover: MP4 → HLS, then HLS → give up.
                        // MP4 is primary (see startInlinePlayback comment);
                        // HLS is the fallback for cases where the MP4
                        // proxy returns 5xx or the fMP4 stream stalls.
                        if (useMp4 && hlsUrl.isNotBlank() && !triedHls) {
                            android.util.Log.i(
                                TAG,
                                "Falling back from MP4 to HLS for '${camera.name}'",
                            )
                            preparePlayback(camera, mp4Url, hlsUrl, useMp4 = false)
                        } else {
                            stopInlinePlayback()
                        }
                    }
                })
                prepare()
                android.util.Log.i(
                    TAG,
                    "ExoPlayer prepared: camera='${camera.name}' useMp4=$useMp4, waiting for first frame...",
                )
            }
            player = newPlayer
            activePlayers.add(newPlayer)
        }

        private fun stopInlinePlayback() {
            val oldPlayer = player
            player = null
            isPlaying = false
            playerLoading = false
            // Detach surface and hide the player view so the Compose
            // card (thumbnail / play button) becomes visible again.
            binding.playerView.player = null
            binding.playerView.visibility = View.GONE
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
                        // Store successful fetches in the LRU cache
                        // so subsequent binds (scroll-back, config
                        // change) don't re-hit the network.
                        if (bitmap != null) {
                            thumbnailCache[camera.id] = bitmap
                        }
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
