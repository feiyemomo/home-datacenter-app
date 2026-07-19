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

class DeviceAdapter(
    private val onRevokeClick: (Device) -> Unit
) : ListAdapter<Device, DeviceAdapter.DeviceViewHolder>(DiffCallback()) {

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

            val statusText = if (device.isRevoked) {
                binding.viewStatus.setBackgroundResource(R.drawable.circle_offline)
                itemView.context.getString(R.string.device_revoked)
            } else {
                binding.viewStatus.setBackgroundResource(R.drawable.circle_online)
                itemView.context.getString(R.string.status_online)
            }

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
