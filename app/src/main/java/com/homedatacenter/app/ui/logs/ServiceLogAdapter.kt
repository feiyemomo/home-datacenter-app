package com.homedatacenter.app.ui.logs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemLogLevel
import com.homedatacenter.app.databinding.ItemServiceLogBinding
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
 * The adapter is a [ListAdapter] so it diffs by log id and only
 * rebinds changed rows — important for the WebSocket live-prepend
 * path, which inserts a single new item at the top while the rest
 * of the list stays unchanged.
 */
class ServiceLogAdapter :
    ListAdapter<SystemLog, ServiceLogAdapter.LogViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val b = ItemServiceLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(b)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
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
