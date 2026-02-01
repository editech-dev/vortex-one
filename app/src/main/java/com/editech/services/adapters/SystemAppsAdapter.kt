package com.editech.services.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.editech.services.databinding.ItemSystemAppBinding
import com.editech.services.models.SystemApp

/**
 * Adapter para mostrar apps del sistema disponibles para virtualizar
 */
class SystemAppsAdapter(
    private val onAppClick: (SystemApp) -> Unit
) : ListAdapter<SystemApp, SystemAppsAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSystemAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ViewHolder(
        private val binding: ItemSystemAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        init {
            binding.root.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onAppClick(getItem(position))
                    }
                }
            }
        }
        
        fun bind(systemApp: SystemApp) {
            binding.apply {
                tvAppName.text = systemApp.name
                tvPackageName.text = systemApp.packageName
                
                if (systemApp.icon != null) {
                    ivAppIcon.setImageDrawable(systemApp.icon)
                } else {
                    ivAppIcon.setImageDrawable(null)
                }
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<SystemApp>() {
        override fun areItemsTheSame(oldItem: SystemApp, newItem: SystemApp): Boolean {
            return oldItem.packageName == newItem.packageName
        }
        
        override fun areContentsTheSame(oldItem: SystemApp, newItem: SystemApp): Boolean {
            return oldItem == newItem
        }
    }
}
