package com.homedatacenter.app.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.data.model.SecurityEventItem
import com.homedatacenter.app.databinding.ItemSecurityEventBinding

class SecurityEventAdapter : ListAdapter<SecurityEventItem, SecurityEventAdapter.VH>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSecurityEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemSecurityEventBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SecurityEventItem) {
            binding.tvEventType.text = item.type
            binding.tvIP.text = item.ip
            binding.tvTime.text = item.createdAt.take(19).replace('T', ' ')
            binding.tvDetail.text = item.detail
            binding.tvAction.text = "动作: ${item.action}"
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SecurityEventItem>() {
        override fun areItemsTheSame(oldItem: SecurityEventItem, newItem: SecurityEventItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SecurityEventItem, newItem: SecurityEventItem): Boolean {
            return oldItem == newItem
        }
    }
}
