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
 * Interactions:
 * - Tap the thumbnail → open the full-res snapshot modal ([onSnapshotClick]).
 * - Tap the row body → toggle the expandable details section (event_id,
 *   frame_time, all zones, large preview thumbnail, jump-to-camera button).
 */
class AlertListAdapter(
    private val baseUrl: String?,
    private val token: String?,
    private val okHttpClient: OkHttpClient?,
    private val onSnapshotClick: ((Alert) -> Unit)? = null,
    private val onJumpCamera: ((Alert) -> Unit)? = null,
    private val onRowClick: ((Alert) -> Unit)? = null,
) : ListAdapter<Alert, AlertListAdapter.AlertViewHolder>(DiffCallback()) {

    private val expandedIds = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val b = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlertViewHolder(b)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun resetExpanded() {
        expandedIds.clear()
    }

    inner class AlertViewHolder(private val binding: ItemAlertBinding) : RecyclerView.ViewHolder(binding.root) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var thumbnailJob: Job? = null
        private var largePreviewJob: Job? = null

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

            // Badges
            binding.chipClip.visibility = if (alert.hasClip) View.VISIBLE else View.GONE
            binding.chipSnapshot.visibility = if (alert.hasSnapshot) View.VISIBLE else View.GONE
            binding.btnPlay.visibility = if (alert.hasClip) View.VISIBLE else View.GONE

            // Expandable section metadata
            binding.tvEventId.text = alert.id
            binding.tvFrameTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
            binding.tvCameraFull.text = buildString {
                append(alert.cameraName.ifEmpty { alert.cameraSlug.ifEmpty { "—" } })
                if (alert.cameraId != null && alert.cameraId > 0) append(" (ID: ${alert.cameraId})")
            }
            binding.tvZonesFull.text = if (zones.isNotEmpty()) zones.joinToString(", ") else itemView.context.getString(R.string.alert_no_zones)

            // Tap thumbnail → snapshot modal
            binding.thumbnailContainer.setOnClickListener {
                if (alert.hasSnapshot) onSnapshotClick?.invoke(alert)
                else onRowClick?.invoke(alert)
            }
            // Tap large preview → snapshot modal
            binding.ivLargePreview.setOnClickListener { onSnapshotClick?.invoke(alert) }
            // Jump-to-camera button
            binding.btnJumpCamera.setOnClickListener { onJumpCamera?.invoke(alert) }
            // "截图" chip → open the snapshot modal directly (no row expansion needed)
            binding.chipSnapshot.setOnClickListener {
                if (alert.hasSnapshot) onSnapshotClick?.invoke(alert)
            }
            // "录像" chip → jump to that camera's tab so the user can open
            // the recordings dialog from there. We don't have a direct
            // "play alert clip" endpoint, so routing the user to the
            // camera page is the closest useful action.
            binding.chipClip.setOnClickListener { onJumpCamera?.invoke(alert) }
            // Chevron expand button — same as tapping the row body, but
            // gives the user an explicit, visible tap target.
            binding.btnExpand.setOnClickListener { toggleExpanded(alert.id) }
            // Tap row (not on thumbnail/chip/btnExpand) → toggle expanded section
            binding.root.setOnClickListener {
                toggleExpanded(alert.id)
            }

            loadThumbnail(alert)

            // Restore expanded state for this item
            val isExpanded = expandedIds.contains(alert.id)
            applyExpandedState(isExpanded, alert, forceReload = false)
        }

        private fun toggleExpanded(alertId: String) {
            val nowExpanded = !expandedIds.contains(alertId)
            if (nowExpanded) expandedIds.add(alertId) else expandedIds.remove(alertId)
            applyExpandedState(nowExpanded, getItem(bindingAdapterPosition), forceReload = true)
        }

        private fun applyExpandedState(expanded: Boolean, alert: Alert, forceReload: Boolean) {
            binding.expandableDetails.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.btnExpand.rotation = if (expanded) 180f else 0f
            if (expanded && (forceReload || binding.ivLargePreview.drawable == null)) {
                loadLargePreview(alert)
            }
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

        private fun loadLargePreview(alert: Alert) {
            val url = buildSnapshotUrl(alert.id)
            if (url.isEmpty() || !alert.hasSnapshot) {
                binding.ivLargePreview.visibility = View.GONE
                binding.progressLarge.visibility = View.GONE
                binding.tvLargeError.visibility = View.GONE
                binding.tvHintView.visibility = View.GONE
                return
            }
            binding.progressLarge.visibility = View.VISIBLE
            binding.ivLargePreview.visibility = View.GONE
            binding.tvLargeError.visibility = View.GONE
            binding.tvHintView.visibility = View.GONE

            largePreviewJob?.cancel()
            largePreviewJob = scope.launch {
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
                        binding.progressLarge.visibility = View.GONE
                        if (bitmap != null) {
                            binding.ivLargePreview.setImageBitmap(bitmap)
                            binding.ivLargePreview.visibility = View.VISIBLE
                            binding.tvHintView.visibility = View.VISIBLE
                        } else {
                            binding.tvLargeError.visibility = View.VISIBLE
                            binding.tvLargeError.text = itemView.context.getString(R.string.weather_failed)
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.progressLarge.visibility = View.GONE
                        binding.tvLargeError.visibility = View.VISIBLE
                        binding.tvLargeError.text = itemView.context.getString(R.string.weather_failed)
                    }
                }
            }
        }

        private fun buildThumbnailUrl(alertId: String): String {
            if (baseUrl.isNullOrBlank()) return ""
            val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return "${base}api/v1/cameras/alerts/$alertId/thumbnail"
        }

        private fun buildSnapshotUrl(alertId: String): String {
            if (baseUrl.isNullOrBlank()) return ""
            val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return "${base}api/v1/cameras/alerts/$alertId/snapshot"
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
