package com.homedatacenter.app.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemLogLevel
import com.homedatacenter.app.databinding.ItemRecentLogBinding

/**
 * Compact adapter for the Dashboard "最近日志" card. Renders the 5
 * most recent [SystemLog] entries as single-row items (icon +
 * message + relative timestamp). Icon mapping mirrors
 * [com.homedatacenter.app.ui.logs.ServiceLogAdapter] so log rows
 * look consistent across the dashboard preview and the full logs tab.
 *
 * v1.6.36: icon tint follows [SystemLog.level] (critical=red,
 * normal=primary, info=grey) so the dashboard preview surfaces
 * urgent events the same way the full logs tab does.
 *
 * Like [ServiceLogAdapter], this is a [ListAdapter] so the
 * WebSocket live-prepend path (which inserts one new item at the
 * top) only rebinds the shifted rows instead of redrawing the list.
 */
class RecentLogAdapter :
    ListAdapter<SystemLog, RecentLogAdapter.LogViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val b = ItemRecentLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(b)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(private val binding: ItemRecentLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(log: SystemLog) {
            binding.ivLogIcon.setImageResource(iconForEventType(log.event_type))
            binding.ivLogIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                colorForLevel(log.level)
            )
            binding.tvLogMessage.text = log.message.ifBlank { log.event_type }
            binding.tvLogTime.text = relativeTime(log.ts)
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

        /**
         * Compact Chinese relative-time formatter for the dashboard
         * preview: "刚刚" / "5分钟前" / "1小时前" / "昨天" / "3天前".
         * Keeps the row narrow so the message gets the width it
         * needs; the full timestamp is available on the logs tab.
         */
        private fun relativeTime(tsSeconds: Long): String {
            val now = System.currentTimeMillis() / 1000L
            val diff = (now - tsSeconds).coerceAtLeast(0)
            return when {
                diff < 60 -> "刚刚"
                diff < 3_600 -> "${diff / 60}分钟前"
                diff < 86_400 -> "${diff / 3_600}小时前"
                diff < 172_800 -> "昨天"
                else -> "${diff / 86_400}天前"
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
