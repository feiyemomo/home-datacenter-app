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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val b = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlertViewHolder(b)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AlertViewHolder(private val binding: ItemAlertBinding) : RecyclerView.ViewHolder(binding.root) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var thumbnailJob: Job? = null

        fun bind(alert: Alert) {
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

            // Tap thumbnail → snapshot modal (if available) or row click fallback
            binding.thumbnailContainer.setOnClickListener {
                if (alert.hasSnapshot) onSnapshotClick?.invoke(alert)
                else onRowClick?.invoke(alert)
            }
            // "查看录像" chip → jump to camera's recordings
            binding.chipClip.setOnClickListener { onJumpCamera?.invoke(alert) }
            // Tap row body → row click handler
            binding.root.setOnClickListener { onRowClick?.invoke(alert) }

            loadThumbnail(alert)
        }

        private fun loadThumbnail(alert: Alert) {
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

            val url = buildThumbnailUrl(alert.id)
            if (url.isEmpty()) return

            binding.progressThumbnail.visibility = View.VISIBLE
            thumbnailJob?.cancel()
            thumbnailJob = scope.launch {
                try {
                    val client = okHttpClient ?: OkHttpClient()
                    val req = Request.Builder().url(url).apply {
                        if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                    }.build()
                    val bitmap = client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                    }
                    withContext(Dispatchers.Main) {
                        binding.progressThumbnail.visibility = View.GONE
                        if (bitmap != null) binding.ivThumbnail.setImageBitmap(bitmap)
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
