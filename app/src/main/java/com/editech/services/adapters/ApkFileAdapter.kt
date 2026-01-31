package com.editech.services.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.editech.services.databinding.ItemApkFileBinding
import com.editech.services.models.ApkFile
import kotlin.math.pow

/**
 * Adapter para mostrar la lista de archivos APK encontrados
 */
class ApkFileAdapter(
    private val apkFiles: List<ApkFile>,
    private val onApkClick: (ApkFile) -> Unit
) : RecyclerView.Adapter<ApkFileAdapter.ApkFileViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApkFileViewHolder {
        val binding = ItemApkFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ApkFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ApkFileViewHolder, position: Int) {
        holder.bind(apkFiles[position])
    }

    override fun getItemCount(): Int = apkFiles.size

    inner class ApkFileViewHolder(
        private val binding: ItemApkFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(apkFile: ApkFile) {
            binding.tvApkName.text = "${apkFile.name}.apk"
            binding.tvApkPath.text = apkFile.path.substringBeforeLast("/")
            binding.tvApkSize.text = formatFileSize(apkFile.size)

            binding.root.setOnClickListener {
                onApkClick(apkFile)
            }
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
            val pre = "KMGTPE"[exp - 1]
            return String.format("%.1f %sB", bytes / 1024.0.pow(exp.toDouble()), pre)
        }
    }
}
