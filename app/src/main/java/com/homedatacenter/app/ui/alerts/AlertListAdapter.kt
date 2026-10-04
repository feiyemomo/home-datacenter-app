package com.homedatacenter.app.ui.alerts

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.databinding.ItemAlertBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for alert list rows. Used by both the dashboard's recent
 * alerts preview and the standalone AlertsFragment.
 *
 * Interactions (v1.5.2 redesign):
 * - Tap the thumbnail → open the full-res snapshot modal ([onSnapshotClick])
 *   when the alert has a snapshot; otherwise fall back to [onRowClick].
 * - Tap the "查看录像" chip → jump to the camera's recordings tab
 *   ([onJumpCamera]).
 * - Tap the row body → invoke [onRowClick] (no longer expands a
 *   details section — the dropdown was removed).
 */
class AlertListAdapter(
    private val baseUrl: String?,
    private val token: String?,
    private val okHttpClient: OkHttpClient?,
    private val onSnapshotClick: ((Alert) -> Unit)? = null,
    private val onJumpCamera: ((Alert) -> Unit)? = null,
    private val onRowClick: ((Alert) -> Unit)? = null,
) : ListAdapter<Alert, AlertListAdapter.AlertViewHolder>(DiffCallback()) {

    companion object {
        // v1.13.23: Shared LRU cache for alert thumbnails (up to 64 items ~2-3MB).
        // Eliminates repeated Base64 / JPEG decoding on scroll and renders instantly.
        private val alertThumbnailCache = object : LinkedHashMap<String, android.graphics.Bitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean {
                return size > 64
            }
        }
    }

    override fun onViewRecycled(holder: AlertViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val b = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlertViewHolder(b)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AlertViewHolder(private val binding: ItemAlertBinding) : RecyclerView.ViewHolder(binding.root) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var thumbnailJob: Job? = null
        private var boundAlertId: String? = null

        fun recycle() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            boundAlertId = null
        }

        fun bind(alert: Alert) {
            boundAlertId = alert.id
            binding.tvLabel.text = formatLabel(alert.label)
            binding.tvConfidence.text = "${(alert.confidence * 100).toInt()}%"
            binding.tvCamera.text = alert.cameraName.ifEmpty {
                alert.cameraSlug.ifEmpty { itemView.context.getString(R.string.live_detection_camera_unknown) }
            }

            val date = Date((alert.startTime * 1000).toLong())
            binding.tvTime.text = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(date)

            val zones = alert.zones
            binding.tvZones.text = if (zones.isNotEmpty()) zones.joinToString(", ") else "—"

            // "查看录像" chip visibility: only when there's a clip to play.
            binding.chipClip.visibility = if (alert.hasClip) View.VISIBLE else View.GONE
            // Play overlay on the thumbnail: shown when a clip is available.
            binding.btnPlay.visibility = if (alert.hasClip) View.VISIBLE else View.GONE

            // Tap thumbnail → always open snapshot modal
            val openSnapshot = {
                onSnapshotClick?.invoke(alert)
            }
            binding.thumbnailContainer.setOnClickListener { openSnapshot() }
            binding.thumbnailCard.setOnClickListener { openSnapshot() }
            binding.ivThumbnail.setOnClickListener { openSnapshot() }
            binding.btnPlay.setOnClickListener { openSnapshot() }
            // "查看录像" chip → jump to camera's recordings
            binding.chipClip.setOnClickListener { onJumpCamera?.invoke(alert) }
            // Tap row body → row click handler
            binding.root.setOnClickListener { onRowClick?.invoke(alert) }

            loadThumbnail(alert)
        }

        private fun loadThumbnail(alert: Alert) {
            thumbnailJob?.cancel()

            // 1. Instant cache hit check (0ms UI thread cost)
            val cached = synchronized(alertThumbnailCache) { alertThumbnailCache[alert.id] }
            if (cached != null) {
                binding.progressThumbnail.visibility = View.GONE
                binding.ivThumbnail.setImageBitmap(cached)
                return
            }

            binding.ivThumbnail.setImageDrawable(null)

            // 2. Base64 thumbnail async decode (Dispatchers.Default off main thread)
            if (alert.thumbnail.isNotEmpty()) {
                binding.progressThumbnail.visibility = View.VISIBLE
                thumbnailJob = scope.launch {
                    val bitmap = withContext(Dispatchers.Default) {
                        try {
                            val bytes = Base64.decode(alert.thumbnail, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (boundAlertId == alert.id) {
                        binding.progressThumbnail.visibility = View.GONE
                        if (bitmap != null) {
                            synchronized(alertThumbnailCache) { alertThumbnailCache[alert.id] = bitmap }
                            binding.ivThumbnail.setImageBitmap(bitmap)
                        }
                    }
                }
                return
            }

            // 3. Network fetch fallback (Dispatchers.IO)
            val url = buildThumbnailUrl(alert.id)
            if (url.isEmpty()) {
                binding.progressThumbnail.visibility = View.GONE
                return
            }

            binding.progressThumbnail.visibility = View.VISIBLE
            thumbnailJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val client = okHttpClient ?: OkHttpClient()
                        val req = Request.Builder().url(url).apply {
                            if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                        }.build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (boundAlertId == alert.id) {
                    binding.progressThumbnail.visibility = View.GONE
                    if (bitmap != null) {
                        synchronized(alertThumbnailCache) { alertThumbnailCache[alert.id] = bitmap }
                        binding.ivThumbnail.setImageBitmap(bitmap)
                    }
                }
            }
        }

        private fun buildThumbnailUrl(alertId: String): String {
            if (baseUrl.isNullOrBlank()) return ""
            val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return "${base}api/v1/cameras/alerts/$alertId/thumbnail"
        }

        /** Translate a backend detection label to a localized display string. */
        private fun formatLabel(label: String): String {
            val ctx = itemView.context
            val res = when (label.lowercase(Locale.getDefault())) {
                "person" -> R.string.detection_label_person
                "car" -> R.string.detection_label_car
                "truck" -> R.string.detection_label_truck
                "bus" -> R.string.detection_label_bus
                "bicycle" -> R.string.detection_label_bicycle
                "motorcycle" -> R.string.detection_label_motorcycle
                "dog" -> R.string.detection_label_dog
                "cat" -> R.string.detection_label_cat
                "bird" -> R.string.detection_label_bird
                else -> 0
            }
            return if (res != 0) ctx.getString(res) else label
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Alert>() {
        override fun areItemsTheSame(oldItem: Alert, newItem: Alert) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Alert, newItem: Alert) = oldItem == newItem
    }
}
