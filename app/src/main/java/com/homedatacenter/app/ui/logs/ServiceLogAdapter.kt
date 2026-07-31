package com.homedatacenter.app.ui.logs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemLogLevel
import com.homedatacenter.app.databinding.ItemServiceLogBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for system service log rows. Maps each [SystemLog] event
 * type to an icon (device / camera / user) and renders the message
 * + formatted timestamp.
 *
 * v1.6.36: the icon is tinted by [SystemLog.level] so urgent events
 * stand out at a glance:
 *   - critical (camera/device offline) -> red icon
 *   - normal   (user login, online)    -> primary (orange) icon
 *   - info     (status_changed)        -> grey icon (text_secondary)
 *
 * v1.6.37: for camera-related logs, a subtitle shows the camera's
 * CURRENT status (在线/离线) fetched from [cameraMap]. This lets the
 * user see at a glance whether a camera that went offline (critical
 * log) has recovered — without navigating to the Cameras tab. The
 * subtitle is updated when [updateCameraMap] is called (e.g. when
 * the fragment refreshes the camera list after a WS event).
 *
 * The adapter is a [ListAdapter] so it diffs by log id and only
 * rebinds changed rows — important for the WebSocket live-prepend
 * path, which inserts a single new item at the top while the rest
 * of the list stays unchanged.
 */
class ServiceLogAdapter :
    ListAdapter<SystemLog, ServiceLogAdapter.LogViewHolder>(DiffCallback()) {

    /**
     * v1.6.37: live camera snapshot keyed by camera id. Updated by
     * [updateCameraMap] from the fragment after each /api/v1/cameras
     * fetch. Read by [LogViewHolder.bind] to render the "当前状态："
     * subtitle on camera-related log rows.
     *
     * @Volatile so the background fetch thread can write while the
     * UI thread reads in bind() without synchronization — a stale
     * read just shows the previous status for one frame.
     */
    @Volatile
    private var cameraMap: Map<Long, Camera> = emptyMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val b = ItemServiceLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(b)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Replace the camera snapshot and re-bind any visible rows that
     * show a camera-status subtitle so the displayed status is fresh.
     * Called by the fragment after a successful /api/v1/cameras fetch.
     *
     * Cheap: notifyItemRangeChanged only touches visible rows, not
     * the whole list. The diff payload is unused because the log
     * row itself hasn't changed — only the camera status subtitle.
     */
    fun updateCameraMap(map: Map<Long, Camera>) {
        cameraMap = map
        // Re-bind visible rows so the subtitle picks up the new
        // status. Without this, a camera that just came back online
        // would still show "当前状态：离线" until the user scrolls.
        notifyItemRangeChanged(0, itemCount)
    }

    inner class LogViewHolder(private val binding: ItemServiceLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun bind(log: SystemLog) {
            binding.ivIcon.setImageResource(iconForEventType(log.event_type))
            binding.ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                colorForLevel(log.level)
            )
            binding.tvMessage.text = log.message.ifBlank { log.event_type }
            binding.tvTime.text = timeFormat.format(Date(log.ts * 1000L))
            bindCameraStatus(log)
        }

        /**
         * v1.6.37: For camera-related logs, resolve the camera_id
         * from the event payload and show the camera's CURRENT
         * status as a subtitle. This answers the user's question
         * "this camera went offline 10 minutes ago — is it back now?"
         * without requiring a tab switch.
         *
         * Shown for ALL camera.* logs (not just critical) because the
         * current status is useful context for online/status_changed
         * events too. Hidden when:
         *   - the log isn't camera-related
         *   - the payload can't be parsed (no camera_id)
         *   - the camera isn't in [cameraMap] (deleted camera, or
         *     the fragment hasn't fetched the list yet)
         *
         * The subtitle text is "当前状态：在线" / "当前状态：离线"
         * with a colored status word so the user can scan the list
         * visually — green for online, red for offline.
         */
        private fun bindCameraStatus(log: SystemLog) {
            val tvStatus = binding.tvCameraStatus
            if (!log.event_type.startsWith("camera.")) {
                tvStatus.visibility = View.GONE
                return
            }
            val cameraId = parseCameraId(log.payload)
            if (cameraId == null) {
                tvStatus.visibility = View.GONE
                return
            }
            val camera = cameraMap[cameraId]
            if (camera == null) {
                // Camera may have been deleted, or the fragment hasn't
                // fetched the list yet. Hide rather than show "unknown"
                // to avoid clutter — the log message itself already has
                // the camera name.
                tvStatus.visibility = View.GONE
                return
            }
            val ctx = binding.root.context
            val isOnline = camera.isOnline
            val statusText = if (isOnline) "在线" else "离线"
            val statusColor = ContextCompat.getColor(
                ctx,
                if (isOnline) R.color.online else R.color.error
            )
            // Build "当前状态：在线" with the status word colored.
            // Using text + span would be cleaner, but a single
            // TextView with a colored prefix is simpler and the row
            // is already compact enough.
            tvStatus.text = "当前状态：$statusText"
            tvStatus.setTextColor(statusColor)
            tvStatus.visibility = View.VISIBLE
        }

        /**
         * Parse camera_id from the raw event payload JSON. The
         * backend's CameraStatusPayload JSON has shape:
         *   {"camera_id": 3, "status": "offline", "host": "...", "ts": ...}
         * Returns null if the payload isn't valid JSON or lacks
         * the camera_id field.
         */
        private fun parseCameraId(payload: String): Long? {
            if (payload.isBlank()) return null
            return try {
                JSONObject(payload).optLong("camera_id", -1L)
                    .takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Pick an icon based on the log's event_type. The backend
         * groups events by `category.event` (e.g. `device.status`,
         * `camera.online`, `user.login`). Falls back to the history
         * icon for unknown types.
         */
        private fun iconForEventType(eventType: String): Int {
            return when {
                eventType.startsWith("device.") -> R.drawable.ic_devices
                eventType.startsWith("camera.") -> R.drawable.ic_camera
                eventType.startsWith("user.") -> R.drawable.ic_admin_users
                else -> R.drawable.ic_history
            }
        }

        /**
         * v1.6.36: tint color for the icon by severity level.
         * critical -> red (error), normal -> primary (orange),
         * info / unknown -> text_secondary (grey).
         */
        private fun colorForLevel(level: String): Int {
            val ctx = binding.root.context
            return when (level) {
                SystemLogLevel.CRITICAL -> ContextCompat.getColor(ctx, R.color.error)
                SystemLogLevel.NORMAL -> ContextCompat.getColor(ctx, R.color.primary)
                else -> ContextCompat.getColor(ctx, R.color.text_secondary)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SystemLog>() {
        override fun areItemsTheSame(oldItem: SystemLog, newItem: SystemLog) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SystemLog, newItem: SystemLog) =
            oldItem == newItem
    }
}
