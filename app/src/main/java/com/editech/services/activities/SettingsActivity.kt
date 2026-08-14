package com.editech.services.activities

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RelativeLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.editech.services.R
import com.editech.services.adapters.SettingsAppsAdapter
import com.editech.services.databinding.ActivitySettingsBinding
import com.editech.services.models.VirtualApp
import com.editech.services.utils.AppStorageManager
import com.editech.services.utils.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Actividad de Configuración y Mantenimiento de almacenamiento de Apps Virtuales.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adapter: SettingsAppsAdapter
    private val virtualApps = mutableListOf<VirtualApp>()

    companion object {
        private const val USER_ID = 0
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        setupVersionInfo()
        loadBanner()
        loadVirtualApps()
    }

    private fun setupVersionInfo() {
        val versionName = try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: com.editech.services.BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            com.editech.services.BuildConfig.VERSION_NAME
        }
        binding.tvVersionInfo.text = getString(R.string.app_version_credit, versionName)
    }

    private fun loadBanner() {
        val bannerContainer = findViewById<RelativeLayout>(R.id.bannerContainer)
        if (bannerContainer != null) {
            com.editech.services.utils.AdManager.loadBanner(this, bannerContainer)
        }
    }

    private fun setupRecyclerView() {
        adapter = SettingsAppsAdapter(
            apps = virtualApps,
            onClearCache = { app -> handleClearCache(app) },
            onClearData = { app -> showClearDataConfirmation(app) }
        )

        binding.rvSettingsApps.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = this@SettingsActivity.adapter
            isFocusable = true
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnClearAllCache.setOnClickListener {
            handleClearAllCache()
        }
    }

    private fun loadVirtualApps() {
        binding.progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val installedApps = BlackBoxCore.get().getInstalledApplications(0, USER_ID)
                val apps = mutableListOf<VirtualApp>()

                installedApps?.forEach { appInfo ->
                    val pm = packageManager
                    val icon = try {
                        appInfo.loadIcon(pm)
                    } catch (e: Exception) {
                        null
                    }

                    apps.add(
                        VirtualApp(
                            packageName = appInfo.packageName,
                            name = appInfo.loadLabel(pm).toString(),
                            icon = icon,
                            userId = USER_ID
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    virtualApps.clear()
                    virtualApps.addAll(apps)
                    adapter.updateApps(virtualApps)

                    if (virtualApps.isEmpty()) {
                        binding.layoutEmptyState.visibility = View.VISIBLE
                        binding.rvSettingsApps.visibility = View.GONE
                    } else {
                        binding.layoutEmptyState.visibility = View.GONE
                        binding.rvSettingsApps.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.toast_load_apps_error, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun handleClearCache(app: VirtualApp) {
        binding.progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val freedBytes = AppStorageManager.clearAppCache(app.packageName, app.userId)
            val freedText = AppStorageManager.formatFileSize(freedBytes)

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.toast_cache_cleared, freedText, app.name),
                    Toast.LENGTH_SHORT
                ).show()
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun showClearDataConfirmation(app: VirtualApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_clear_data_title, app.name))
            .setMessage(getString(R.string.dialog_clear_data_msg, app.name))
            .setPositiveButton(getString(R.string.action_clear_data)) { _, _ ->
                handleClearData(app)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun handleClearData(app: VirtualApp) {
        binding.progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val success = AppStorageManager.clearAppData(app.packageName, app.userId)

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (success) {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.toast_data_cleared, app.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.toast_clear_error, app.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun handleClearAllCache() {
        if (virtualApps.isEmpty()) return

        binding.progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val pkgList = virtualApps.map { it.packageName }
            val (count, freedBytes) = AppStorageManager.clearAllAppsCache(pkgList, USER_ID)
            val freedText = AppStorageManager.formatFileSize(freedBytes)

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.toast_all_cache_cleared, freedText, count),
                    Toast.LENGTH_LONG
                ).show()
                adapter.notifyDataSetChanged()
            }
        }
    }
}
