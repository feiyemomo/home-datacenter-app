package com.homedatacenter.app.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.data.model.SecurityWhitelistEntry
import com.homedatacenter.app.databinding.ItemWhitelistEntryBinding

class WhitelistAdapter(
    private val onDeleteClick: (SecurityWhitelistEntry) -> Unit
) : ListAdapter<SecurityWhitelistEntry, WhitelistAdapter.VH>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWhitelistEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemWhitelistEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SecurityWhitelistEntry) {
            binding.tvEntry.text = item.entry
            binding.tvNote.text = item.note.ifBlank { "无备注说明" }
            binding.tvTime.text = "添加时间: ${item.createdAt.take(19).replace('T', ' ')}"
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SecurityWhitelistEntry>() {
        override fun areItemsTheSame(oldItem: SecurityWhitelistEntry, newItem: SecurityWhitelistEntry): Boolean {
            return oldItem.id == newItem.id && oldItem.entry == newItem.entry
        }

        override fun areContentsTheSame(oldItem: SecurityWhitelistEntry, newItem: SecurityWhitelistEntry): Boolean {
            return oldItem == newItem
        }
    }
}
