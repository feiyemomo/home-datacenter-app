package com.homedatacenter.app.ui.devices

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Device
import com.homedatacenter.app.databinding.ItemDeviceBinding
import com.homedatacenter.app.util.AnimationHelper

/**
 * Adapter for the device list.
 *
 * Each row shows one of three states, derived from [Device.isRevoked] and
 * the live online-device-id set supplied by [onlineDeviceIds]:
 *   - 已吊销  → red dot, revoke button disabled
 *   - 在线    → green dot (device.id in onlineDeviceIds)
 *   - 离线    → amber dot (device not revoked but not in onlineDeviceIds)
 *
 * The onlineDeviceIds set is populated by /api/v1/system/status (which
 * returns `online_device_ids`) and refreshed whenever DevicesFragment
 * resumes or DashboardFragment receives a WS device.status event. We
 * accept an immutable snapshot here rather than observing a Flow —
 * [submitList] + a fresh [onlineDeviceIds] on each render is enough.
 */
class DeviceAdapter(
    private val onRevokeClick: (Device) -> Unit
) : ListAdapter<Device, DeviceAdapter.DeviceViewHolder>(DiffCallback()) {

    /** Snapshot of online device ids from SystemStatus. Empty when unknown. */
    var onlineDeviceIds: Set<Long> = emptySet()
        set(value) {
            field = value
            // Force a rebind so the status dot updates even if the Device
            // payload itself did not change.
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
        AnimationHelper.slideInBottom(holder.itemView, 80L)
    }

    inner class DeviceViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: Device) {
            binding.tvDeviceName.text = device.deviceName

            // Three-state visualization:
            //   已吊销 > 在线 > 离线
            val statusText: String
            val dotRes: Int
            if (device.isRevoked) {
                statusText = itemView.context.getString(R.string.device_revoked)
                dotRes = R.drawable.circle_offline
            } else if (device.id in onlineDeviceIds) {
                statusText = itemView.context.getString(R.string.status_online)
                dotRes = R.drawable.circle_online
            } else {
                statusText = itemView.context.getString(R.string.status_offline)
                dotRes = R.drawable.circle_warning
            }
            binding.viewStatus.setBackgroundResource(dotRes)

            val info = buildString {
                append("ID: ${device.id}")
                if (!device.lastLoginAt.isNullOrEmpty()) {
                    append("  |  ${device.lastLoginAt.take(16)}")
                }
                if (!device.lastIp.isNullOrEmpty()) {
                    append("  |  ${device.lastIp}")
                }
                append("  |  $statusText")
            }
            binding.tvDeviceInfo.text = info

            binding.btnRevoke.isEnabled = !device.isRevoked
            binding.btnRevoke.alpha = if (device.isRevoked) 0.5f else 1f
            binding.btnRevoke.setOnClickListener { onRevokeClick(device) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Device>() {
        override fun areItemsTheSame(oldItem: Device, newItem: Device) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Device, newItem: Device) = oldItem == newItem
    }
}
