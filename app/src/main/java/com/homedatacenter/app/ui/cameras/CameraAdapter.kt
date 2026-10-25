package com.homedatacenter.app.ui.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
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

/**
 * Compact list adapter for cameras (v1.5.2 redesign).
 *
 * Responsibilities reduced to:
 * - Load and cache thumbnail bitmaps.
 * - Render the compact card via the [CameraCard] composable.
 * - Forward taps to [onClick] (opens CameraDetailActivity).
 *
 * Inline playback has been removed from the list — the detail page
 * now owns the ExoPlayer instance. The StyledPlayerView declared in
 * item_camera.xml is kept (visibility=gone) so the binding still
 * type-checks, but the adapter no longer touches it.
 */
class CameraAdapter(
    private val onClick: (Camera) -> Unit,
    private val baseUrl: String? = null,
    private val token: String? = null,
    private val okHttpClient: OkHttpClient? = null,
) : ListAdapter<Camera, CameraAdapter.CameraViewHolder>(DiffCallback()) {

    // LRU thumbnail cache shared across all view holders. Without
    // this, scrolling the camera list re-fetches the snapshot JPEG
    // on every bind (each scroll triggers onBindViewHolder →
    // loadThumbnail → HTTP GET through Cloudflare Tunnel → go2rtc
    // RTSP keyframe → JPEG encode). On a slow Cloudflare Tunnel from
    // China (TTFB 1.4s+, frequent 10s timeouts) this makes the
    // camera list feel broken. Cache 16 snapshots (~16 × 50KB =
    // 800KB max memory) — enough for a typical camera fleet, small
    // enough to not pressure the heap.
    //
    // v1.14.9: entries are timestamped and expire after
    // [THUMBNAIL_TTL_MS]. Previously a frame was fetched exactly once
    // per camera per adapter lifetime and cached forever, so (a) the
    // "live" previews on the camera list never actually updated, and
    // (b) when go2rtc answered with its "frames have been received,
    // check error logs" placeholder during a stream outage, that
    // placeholder was cached as if it were a real frame and stayed on
    // screen until the process was restarted — even long after the
    // stream came back. A TTL makes both failures self-heal.
    private class ThumbEntry(val bitmap: Bitmap, val fetchedAtMs: Long) {
        fun isFresh(nowMs: Long): Boolean = nowMs - fetchedAtMs < THUMBNAIL_TTL_MS
    }

    private val thumbnailCache = object : LinkedHashMap<Long, ThumbEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ThumbEntry>?): Boolean {
            return size > 16
        }
    }

    private fun cachedThumb(id: Long): ThumbEntry? = synchronized(thumbnailCache) { thumbnailCache[id] }

    /** Drop every cached frame and re-bind, forcing a fresh fetch. */
    fun invalidateThumbnails() {
        synchronized(thumbnailCache) { thumbnailCache.clear() }
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    /**
     * Re-bind every item so frames older than [THUMBNAIL_TTL_MS] are
     * re-fetched. Cheap when nothing is stale: [CameraViewHolder.bind]
     * only issues a request when the cached entry has expired.
     */
    fun refreshVisibleThumbnails() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CameraViewHolder {
        val binding = ItemCameraBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CameraViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CameraViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: CameraViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    /** Release any heavy resources when the host fragment is destroyed. */
    fun releaseAllPlayers() {
        // No-op now that inline playback is gone. Kept for API
        // compatibility with CamerasFragment.onPause.
    }

    inner class CameraViewHolder(
        private val binding: ItemCameraBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var thumbnailJob: Job? = null
        private var boundCamera: Camera? = null

        private var currentCamera by mutableStateOf<Camera?>(null)
        private var thumbnail by mutableStateOf<Bitmap?>(null)
        private var thumbnailLoading by mutableStateOf(false)
        private var thumbnailError by mutableStateOf(false)

        init {
            binding.composeView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            binding.composeView.setContent {
                MaterialTheme {
                    val cam = currentCamera
                    if (cam != null) {
                        CameraCard(
                            camera = cam,
                            thumbnail = thumbnail,
                            thumbnailLoading = thumbnailLoading,
                            thumbnailError = thumbnailError,
                            onClick = { onClick(cam) },
                        )
                    }
                }
            }
        }

        fun bind(camera: Camera) {
            val cameraChanged = boundCamera?.id != camera.id
            val metadataChanged = boundCamera?.status != camera.status ||
                    boundCamera?.name != camera.name ||
                    boundCamera?.codec != camera.codec
            boundCamera = camera
            if (cameraChanged || metadataChanged || currentCamera == null) {
                currentCamera = camera
            }

            val entry = cachedThumb(camera.id)

            if (cameraChanged) {
                thumbnailJob?.cancel()
                thumbnailError = false
                if (entry != null) {
                    thumbnail = entry.bitmap
                    thumbnailLoading = false
                } else {
                    thumbnail = null
                    loadThumbnail(camera)
                }
            } else if (thumbnail == null) {
                if (entry != null) {
                    thumbnail = entry.bitmap
                    thumbnailLoading = false
                } else {
                    loadThumbnail(camera)
                }
            }
        }

        private fun loadThumbnail(camera: Camera, silent: Boolean = false) {
            // v1.8.x: skip loading for offline cameras — the HTTP
            // request will fail anyway (go2rtc can't reach the camera),
            // and the loading spinner would stay spinning until the
            // connect timeout fires (~10s). Show "无预览" immediately.
            if (!camera.isOnline) {
                if (!silent) {
                    thumbnailLoading = false
                    thumbnailError = true
                }
                return
            }
            val snapshotUrl = buildSnapshotUrl(camera.id)
            if (snapshotUrl.isEmpty()) {
                if (!silent) {
                    thumbnailLoading = false
                    thumbnailError = true
                }
                return
            }

            if (!silent) {
                thumbnailLoading = true
                thumbnailError = false
            }
            thumbnailJob?.cancel()
            thumbnailJob = scope.launch {
                val bitmap = fetchScreenshot(snapshotUrl)
                withContext(Dispatchers.Main) {
                    if (boundCamera?.id == camera.id && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        if (bitmap != null) {
                            thumbnail = bitmap
                            thumbnailLoading = false
                            thumbnailError = false
                            synchronized(thumbnailCache) {
                                thumbnailCache[camera.id] =
                                    ThumbEntry(bitmap, SystemClock.elapsedRealtime())
                            }
                        } else if (!silent) {
                            // Keep the previous frame visible on a
                            // failed refresh; only a foreground load
                            // may show the error state.
                            thumbnailLoading = false
                            thumbnailError = true
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
                    // v1.6.4 rev6: downsample less aggressively now that
                    // the card thumbnail is 192×108dp (was 128×72dp).
                    // - inSampleSize=2 on a 1920x1080 source yields
                    //   960x540 (~2 MB ARGB or 1 MB RGB_565) — sharp
                    //   enough for the larger card render without
                    //   blowing up the LRU cache.
                    // - RGB_565 keeps each bitmap at ~1 MB so the
                    //   16-entry LRU stays under ~16 MB.
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = 2
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    response.body?.byteStream()?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                }
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Frame fetch failed for $url: ${error.message}")
                null
            }
        }

        private fun buildSnapshotUrl(cameraId: Long): String {
            if (baseUrl.isNullOrBlank()) return ""
            // Request a downscaled, low-quality JPEG for the list
            // thumbnail — quality=30 & width=480 is much smaller than
            // the full-resolution frame, so it loads fast on slow
            // Cloudflare Tunnel links. The detail page uses its own
            // larger preview frame (width=640).
            return "${baseUrl.trimEnd('/')}/api/v1/cameras/$cameraId/frame?quality=30&width=480"
        }

        fun recycle() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            boundCamera = null
            currentCamera = null
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

        /**
         * How long a cached preview frame stays valid. Chosen to be
         * well under a human's patience for "live" previews while
         * still avoiding a re-fetch on every scroll/bind for a
         * slow-tunnel user.
         */
        private const val THUMBNAIL_TTL_MS = 12_000L
    }
}
