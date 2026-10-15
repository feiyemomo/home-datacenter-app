package com.homedatacenter.app.ui.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.data.model.BanEntry
import com.homedatacenter.app.databinding.ItemBannedIpBinding

/**
 * Adapter for presenting banned IP entries in [SecurityDefenseActivity].
 */
class BannedIpAdapter(
    private val onUnbanClick: (BanEntry) -> Unit
) : ListAdapter<BanEntry, BannedIpAdapter.VH>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBannedIpBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemBannedIpBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.btnUnban.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onUnbanClick(getItem(pos))
                }
            }
        }

        fun bind(entry: BanEntry) {
            binding.tvIpAddress.text = entry.ip
            binding.tvReason.text = if (entry.reason.isNotBlank()) {
                "原因: ${entry.reason}"
            } else {
                "原因: 触发入侵防御频率阈值或异常访问"
            }

            if (entry.permanent) {
                binding.tvPermanentBadge.visibility = View.VISIBLE
                binding.tvTimeInfo.text = "封禁时间: ${formatTime(entry.bannedAt)} | 永久有效"
            } else {
                binding.tvPermanentBadge.visibility = View.GONE
                val expStr = if (entry.expiresAt.isNotBlank()) formatTime(entry.expiresAt) else "即将到期"
                binding.tvTimeInfo.text = "封禁: ${formatTime(entry.bannedAt)} · 解封预计: $expStr"
            }
        }

        private fun formatTime(isoStr: String): String {
            if (isoStr.isBlank()) return "--"
            return try {
                // Remove millisecond/timezone noise for clean UI presentation (e.g. 2026-10-15T22:30:00Z -> 10-15 22:30)
                val clean = isoStr.replace("T", " ")
                if (clean.length >= 16) {
                    clean.substring(clean.indexOf("-") + 1, 16)
                } else {
                    clean
                }
            } catch (_: Exception) {
                isoStr
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<BanEntry>() {
        override fun areItemsTheSame(oldItem: BanEntry, newItem: BanEntry): Boolean =
            oldItem.ip == newItem.ip

        override fun areContentsTheSame(oldItem: BanEntry, newItem: BanEntry): Boolean =
            oldItem == newItem
    }
}
