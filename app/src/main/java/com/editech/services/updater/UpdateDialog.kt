package com.editech.services.updater

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Toast
import com.editech.services.R
import com.editech.services.databinding.DialogUpdateAvailableBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * UpdateDialog — TV-optimized modal dialog displaying release changelog,
 * file size, and interactive downloading with progress bar.
 */
class UpdateDialog(
    private val activity: Activity,
    private val release: ReleaseInfo
) {

    private val dialog: Dialog = Dialog(activity)
    private val binding: DialogUpdateAvailableBinding

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogUpdateAvailableBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        setupViews()
    }

    private fun setupViews() {
        binding.tvNewVersionTag.text = release.tagName.ifEmpty { "v${release.versionName}" }

        val sizeMb = if (release.sizeBytes > 0) {
            String.format(Locale.US, "%.1f MB", release.sizeBytes / (1024.0 * 1024.0))
        } else {
            "~13 MB"
        }
        val abiName = Build.SUPPORTED_ABIS?.firstOrNull() ?: "ARM"
        binding.tvUpdateMeta.text = activity.getString(R.string.update_meta_format, sizeMb, abiName)

        val cleanChangelog = release.changelog
            .replace("#", "")
            .replace("*", "•")
            .trim()
        binding.tvChangelogBody.text = cleanChangelog.ifEmpty {
            activity.getString(R.string.update_changelog_default)
        }

        binding.btnCancelUpdate.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnStartUpdate.setOnClickListener {
            startDownload()
        }

        // Focus primary update button for D-Pad remotes
        binding.btnStartUpdate.requestFocus()
    }

    private fun startDownload() {
        binding.layoutButtons.visibility = View.GONE
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.pbDownloadProgress.progress = 0
        binding.tvDownloadPercent.text = "0%"

        CoroutineScope(Dispatchers.Main).launch {
            val result = UpdateManager.downloadApk(
                context = activity,
                downloadUrl = release.downloadUrl,
                assetName = release.assetName,
                onProgress = { percent, bytesRead, totalBytes ->
                    if (percent >= 0) {
                        binding.pbDownloadProgress.isIndeterminate = false
                        binding.pbDownloadProgress.progress = percent
                        binding.tvDownloadPercent.text = "$percent%"
                    } else {
                        binding.pbDownloadProgress.isIndeterminate = true
                        val mbRead = bytesRead / (1024.0 * 1024.0)
                        binding.tvDownloadPercent.text = String.format(Locale.US, "%.1f MB", mbRead)
                    }
                }
            )

            if (result.isSuccess) {
                val apkFile = result.getOrNull()
                if (apkFile != null && apkFile.exists()) {
                    dialog.dismiss()
                    UpdateManager.installApk(activity, apkFile)
                } else {
                    restoreErrorState(activity.getString(R.string.update_error_file_missing))
                }
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Error desconocido"
                restoreErrorState(activity.getString(R.string.update_download_failed, errorMsg))
            }
        }
    }

    private fun restoreErrorState(errorMsg: String) {
        binding.layoutDownloadProgress.visibility = View.GONE
        binding.layoutButtons.visibility = View.VISIBLE
        Toast.makeText(activity, errorMsg, Toast.LENGTH_LONG).show()
        binding.btnStartUpdate.requestFocus()
    }

    fun show() {
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }
}
