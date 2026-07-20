package com.homedatacenter.app.ui.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        private var thumbnail by mutableStateOf<Bitmap?>(null)
        private var thumbnailLoading by mutableStateOf(false)
        private var thumbnailError by mutableStateOf(false)

        init {
            binding.composeView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
        }

        fun bind(camera: Camera) {
            val cameraChanged = boundCamera?.id != camera.id
            if (cameraChanged) {
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
                        onClick = { onClick(camera) },
                    )
                }
            }
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
            return "${baseUrl.trimEnd('/')}/api/v1/cameras/$cameraId/frame"
        }

        fun recycle() {
            thumbnailJob?.cancel()
            thumbnailJob = null
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
