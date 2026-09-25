package com.homedatacenter.app.ui.vision

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.data.model.VisionPerson
import com.homedatacenter.app.databinding.ItemFamilyFaceBinding

class FamilyFaceAdapter(
    private val onDeleteClick: (VisionPerson) -> Unit
) : ListAdapter<VisionPerson, FamilyFaceAdapter.ViewHolder>(DIFF_CALLBACK) {

    class ViewHolder(val binding: ItemFamilyFaceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFamilyFaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            tvPersonName.text = item.name
            tvAvatarChar.text = item.name.firstOrNull()?.uppercaseChar()?.toString() ?: "👤"
            btnDeleteFace.setOnClickListener {
                onDeleteClick(item)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<VisionPerson>() {
            override fun areItemsTheSame(oldItem: VisionPerson, newItem: VisionPerson): Boolean {
                return oldItem.name == newItem.name
            }

            override fun areContentsTheSame(oldItem: VisionPerson, newItem: VisionPerson): Boolean {
                return oldItem == newItem
            }
        }
    }
}
