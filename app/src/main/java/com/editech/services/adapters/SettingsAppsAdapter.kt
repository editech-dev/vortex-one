package com.editech.services.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.editech.services.R
import com.editech.services.databinding.ItemSettingsAppBinding
import com.editech.services.models.VirtualApp
import com.editech.services.utils.AppStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsAppsAdapter(
    private var apps: List<VirtualApp>,
    private val onClearCache: (VirtualApp) -> Unit,
    private val onClearData: (VirtualApp) -> Unit
) : RecyclerView.Adapter<SettingsAppsAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSettingsAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSettingsAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        val context = holder.itemView.context

        holder.binding.tvAppName.text = app.name
        holder.binding.tvPackageName.text = app.packageName

        if (app.icon != null) {
            holder.binding.ivAppIcon.setImageDrawable(app.icon)
        } else {
            holder.binding.ivAppIcon.setImageResource(R.drawable.ic_apps_grid)
        }

        // Calculate storage info asynchronously
        holder.binding.tvStorageInfo.text = context.getString(R.string.label_cache_size, "...")
        CoroutineScope(Dispatchers.IO).launch {
            val cacheBytes = AppStorageManager.getAppCacheSize(app.packageName, app.userId)
            val dataBytes = AppStorageManager.getAppDataSize(app.packageName, app.userId)
            val cacheText = AppStorageManager.formatFileSize(cacheBytes)
            val dataText = AppStorageManager.formatFileSize(dataBytes)

            withContext(Dispatchers.Main) {
                holder.binding.tvStorageInfo.text = "${context.getString(R.string.label_cache_size, cacheText)} | ${context.getString(R.string.label_data_size, dataText)}"
            }
        }

        holder.binding.btnClearCache.setOnClickListener {
            onClearCache(app)
        }

        holder.binding.btnClearData.setOnClickListener {
            onClearData(app)
        }
    }

    override fun getItemCount(): Int = apps.size

    fun updateApps(newApps: List<VirtualApp>) {
        this.apps = newApps
        notifyDataSetChanged()
    }
}
