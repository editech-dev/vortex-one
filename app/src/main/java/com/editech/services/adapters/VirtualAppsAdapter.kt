package com.editech.services.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.editech.services.databinding.ItemVirtualAppBinding
import com.editech.services.models.VirtualApp

/**
 * Adapter para mostrar aplicaciones virtuales en la grilla del Dashboard
 */
class VirtualAppsAdapter(
    private var apps: List<VirtualApp>,
    private val onAppClick: (VirtualApp) -> Unit,
    private val onAppLongClick: (VirtualApp) -> Boolean
) : RecyclerView.Adapter<VirtualAppsAdapter.VirtualAppViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VirtualAppViewHolder {
        val binding = ItemVirtualAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VirtualAppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VirtualAppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount(): Int = apps.size

    fun updateApps(newApps: List<VirtualApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    inner class VirtualAppViewHolder(
        private val binding: ItemVirtualAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: VirtualApp) {
            binding.tvAppName.text = app.name
            binding.ivAppIcon.setImageDrawable(app.icon)

            // Click para lanzar la app
            binding.root.setOnClickListener {
                onAppClick(app)
            }

            // Long click para desinstalar
            binding.root.setOnLongClickListener {
                onAppLongClick(app)
            }
        }
    }
}
