package com.homedatacenter.app.ui.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.databinding.ItemUserBinding

/**
 * Adapter for the user list in [UsersActivity].
 *
 * Each row shows name + admin chip + user ID + created/updated
 * timestamps. The settings button on each row opens the edit dialog
 * (rename / toggle admin / delete).
 *
 * v1.8.21: replaced device_count with user ID in the meta line,
 * since device count is not actionable from this screen and the
 * user ID is needed for binding new devices.
 */
class UserListAdapter(
    private val onClick: (User) -> Unit,
) : ListAdapter<User, UserListAdapter.VH>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemUserBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.btnEdit.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
            }
        }

        fun bind(user: User) {
            binding.tvUserName.text = user.name
            binding.chipAdmin.visibility = if (user.isAdmin) View.VISIBLE else View.GONE

            binding.tvUserMeta.text = buildString {
                append("ID: ").append(user.id)
                if (!user.createdAt.isNullOrEmpty()) {
                    append(" · 注册: ").append(user.createdAt)
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: User, newItem: User): Boolean = oldItem == newItem
    }
}
