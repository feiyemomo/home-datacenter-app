package com.homedatacenter.app.ui.logs

import android.content.res.ColorStateList
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
import com.homedatacenter.app.databinding.ItemLogSectionHeaderBinding
import com.homedatacenter.app.databinding.ItemServiceLogBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.6.39: Adapter for the service logs list with two-section
 * collapsible display.
 *
 * Logs are split into:
 *   - "待处理日志" (critical/pending level): always visible
 *   - "所有日志" (all levels including critical): collapsed by default,
 *     expandable via header tap. Critical logs are highlighted
 *     with a red icon tint.
 *
 * v1.8.14: Added "核查并删除" (verify and delete) action on
 * critical log entries. The user taps the button to confirm
 * the offline event has been handled, then the entry is deleted.
 * Uses a callback [onVerifyDelete] to trigger the API call from
 * the fragment.
 *
 * Uses a sealed [LogListItem] to represent both section headers
 * and individual log entries in the same RecyclerView.
 */
class ServiceLogAdapter(
    private val onHeaderClick: (LogListItem.Section) -> Unit,
    private val onVerifyDelete: ((SystemLog) -> Unit)? = null,
) : ListAdapter<LogListItem, RecyclerView.ViewHolder>(DiffCallback()) {

    @Volatile
    private var cameraMap: Map<Long, Camera> = emptyMap()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is LogListItem.Header -> TYPE_HEADER
        is LogListItem.LogEntry -> TYPE_LOG
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                ItemLogSectionHeaderBinding.inflate(inflater, parent, false)
            )
            else -> LogViewHolder(
                ItemServiceLogBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is LogListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is LogListItem.LogEntry -> (holder as LogViewHolder).bind(item.log)
        }
    }

    fun updateCameraMap(map: Map<Long, Camera>) {
        cameraMap = map
        notifyItemRangeChanged(0, itemCount)
    }

    // --- Header ViewHolder ---

    inner class HeaderViewHolder(
        private val binding: ItemLogSectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val item = getItem(bindingAdapterPosition)
                if (item is LogListItem.Header) {
                    onHeaderClick(item.section)
                }
            }
        }

        fun bind(header: LogListItem.Header) {
            binding.tvSectionTitle.text = header.title
            binding.tvSectionCount.text = "${header.count} 条"
            // Rotate arrow: 0° = expanded, -90° = collapsed
            binding.ivExpandArrow.rotation = if (header.collapsed) -90f else 0f
        }
    }

    // --- Log ViewHolder (unchanged from v1.6.37) ---

    inner class LogViewHolder(private val binding: ItemServiceLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun bind(log: SystemLog) {
            binding.ivIcon.setImageResource(iconForEventType(log.event_type))
            binding.ivIcon.imageTintList = ColorStateList.valueOf(colorForLevel(log.level))
            binding.tvMessage.text = log.message.ifBlank { log.event_type }
            binding.tvTime.text = timeFormat.format(Date(log.ts * 1000L))
            bindCameraStatus(log)

            // v1.8.14: show "核查并删除" button for critical logs
            // (camera/device offline). The user reviews the log and
            // taps to confirm the issue has been handled, then the
            // entry is deleted.
            val isCritical = log.level == SystemLogLevel.CRITICAL
            binding.btnVerifyDelete.visibility = if (isCritical) View.VISIBLE else View.GONE
            binding.btnVerifyDelete.setOnClickListener {
                onVerifyDelete?.invoke(log)
            }
        }

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
                tvStatus.visibility = View.GONE
                return
            }
            val ctx = binding.root.context
            val isOnline = camera.isOnline
            val statusText = if (isOnline) "在线" else "离线"
            val statusColor = ContextCompat.getColor(
                ctx, if (isOnline) R.color.online else R.color.error
            )
            tvStatus.text = "当前状态：$statusText"
            tvStatus.setTextColor(statusColor)
            tvStatus.visibility = View.VISIBLE
        }

        private fun parseCameraId(payload: String): Long? {
            if (payload.isBlank()) return null
            return try {
                JSONObject(payload).optLong("camera_id", -1L).takeIf { it > 0 }
            } catch (_: Exception) { null }
        }

        private fun iconForEventType(eventType: String): Int = when {
            eventType.startsWith("device.") -> R.drawable.ic_devices
            eventType.startsWith("camera.") -> R.drawable.ic_camera
            eventType.startsWith("user.") -> R.drawable.ic_admin_users
            else -> R.drawable.ic_history
        }

        private fun colorForLevel(level: String): Int {
            val ctx = binding.root.context
            return when (level) {
                SystemLogLevel.CRITICAL -> ContextCompat.getColor(ctx, R.color.error)
                SystemLogLevel.NORMAL -> ContextCompat.getColor(ctx, R.color.primary)
                else -> ContextCompat.getColor(ctx, R.color.text_secondary)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<LogListItem>() {
        override fun areItemsTheSame(oldItem: LogListItem, newItem: LogListItem): Boolean =
            when {
                oldItem is LogListItem.Header && newItem is LogListItem.Header ->
                    oldItem.section == newItem.section
                oldItem is LogListItem.LogEntry && newItem is LogListItem.LogEntry ->
                    oldItem.log.id == newItem.log.id
                else -> false
            }

        override fun areContentsTheSame(oldItem: LogListItem, newItem: LogListItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_LOG = 1
    }
}

/**
 * v1.6.39: Sealed class representing items in the service logs list.
 * Either a section header or an individual log entry.
 */
sealed class LogListItem {
    enum class Section { IMPORTANT, OTHER }

    data class Header(
        val section: Section,
        val title: String,
        val count: Int,
        val collapsed: Boolean,
    ) : LogListItem()

    data class LogEntry(val log: SystemLog) : LogListItem()
}
