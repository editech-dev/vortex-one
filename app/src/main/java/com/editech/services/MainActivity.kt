package com.editech.services

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.editech.services.activities.FileScannerActivity
import com.editech.services.activities.FirewallActivity
import com.editech.services.activities.SystemAppsActivity
import com.editech.services.adapters.VirtualAppsAdapter
import com.editech.services.databinding.ActivityMainBinding
import com.editech.services.models.VirtualApp
import com.editech.services.utils.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
// import top.niunaijun.blackbox.BlackBoxCore
/**
 * MainActivity: Dashboard principal de OpenContainer-TV
 * Muestra las aplicaciones virtuales instaladas en una grilla navegable con control remoto
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VirtualAppsAdapter
    private val virtualApps = mutableListOf<VirtualApp>()
    
    companion object {
        private const val USER_ID = 0 // ID de usuario virtual de BlackBox
        private const val REQUEST_CODE_SELECT_SYSTEM_APP = 2002
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupButtons()
        setupSearch()
        loadVirtualApps()
        
        // Load ad
        // Load ad with smart delay
        CoroutineScope(Dispatchers.IO).launch {
            // Wait for SDK to initialize (poll for up to 20 seconds)
            var attempts = 0
            while (!com.editech.services.utils.AdManager.isSdkInitialized() && attempts < 20) {
                kotlinx.coroutines.delay(1000)
                attempts++
            }
            
            // Wait an extra second for safety
            kotlinx.coroutines.delay(1000)

            withContext(Dispatchers.Main) {
               // Load Interstitial
               com.editech.services.utils.AdManager.loadInterstitial(this@MainActivity)
               
               // Load Banner
               val bannerContainer = findViewById<android.widget.RelativeLayout>(R.id.bannerContainer)
               if (bannerContainer != null) {
                   android.util.Log.d("MainActivity", "Banner container found, loading ad...")
                   com.editech.services.utils.AdManager.loadBanner(this@MainActivity, bannerContainer)
               } else {
                   android.util.Log.e("MainActivity", "Banner container IS NULL!")
               }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Recargar aplicaciones para sincronizar badges de Tor y estado tras regresar de Ajustes/Firewall
        loadVirtualApps()
    }
    
    private fun setupRecyclerView() {
        adapter = VirtualAppsAdapter(
            apps = virtualApps,
            onAppClick = { app -> launchVirtualApp(app) },
            onAppLongClick = { app -> showUninstallDialog(app) }
        )
        
        binding.rvVirtualApps.apply {
            val spanCount = resources.getInteger(R.integer.grid_span_count)
            layoutManager = GridLayoutManager(this@MainActivity, spanCount)
            adapter = this@MainActivity.adapter
        }
    }
    
    private fun setupButtons() {
        binding.btnInstallApk.setOnClickListener {
            openFileScannerActivity()
        }
        
        binding.btnVirtualizeSystemApp.setOnClickListener {
            openSystemAppsActivity()
        }
        
        binding.btnFirewall.setOnClickListener {
            openFirewallActivity()
        }

        binding.btnSettings.setOnClickListener {
            openSettingsActivity()
        }

        binding.btnLanguage.setOnClickListener {
            showLanguagePicker()
        }
    }

    private fun openSettingsActivity() {
        val intent = Intent(this, com.editech.services.activities.SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun showLanguagePicker() {
        val options = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_english),
            getString(R.string.language_spanish)
        )
        val codes = arrayOf(LocaleHelper.LANG_SYSTEM, LocaleHelper.LANG_EN, LocaleHelper.LANG_ES)
        val current = LocaleHelper.getSavedLocale(this)
        val checked = codes.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.language_title))
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val chosen = codes[which]
                if (chosen != current) {
                    LocaleHelper.setLocale(this, chosen)
                    dialog.dismiss()
                    // Restart to apply new locale globally
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    private fun openFileScannerActivity() {
        val intent = Intent(this, FileScannerActivity::class.java)
        startActivityForResult(intent, FileScannerActivity.REQUEST_CODE_SELECT_APK)
    }
    
    private fun openSystemAppsActivity() {
        val intent = Intent(this, SystemAppsActivity::class.java)
        startActivityForResult(intent, REQUEST_CODE_SELECT_SYSTEM_APP)
    }
    
    private fun openFirewallActivity() {
        val intent = Intent(this, FirewallActivity::class.java)
        startActivity(intent)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // Handle APK file selection
        if (requestCode == FileScannerActivity.REQUEST_CODE_SELECT_APK && resultCode == Activity.RESULT_OK) {
            val apkPath = data?.getStringExtra(FileScannerActivity.EXTRA_APK_PATH)
            val apkName = data?.getStringExtra(FileScannerActivity.EXTRA_APK_NAME)
            
            if (apkPath != null) {
                showInstallWarningDialog(apkPath, apkName ?: "APK")
            }
        }
        
        // Handle system app selection for virtualization (PARALLEL SPACE feature)
        if (requestCode == REQUEST_CODE_SELECT_SYSTEM_APP && resultCode == Activity.RESULT_OK) {
            val packageName = data?.getStringExtra(SystemAppsActivity.EXTRA_SELECTED_APP_PACKAGE)
            val appName = data?.getStringExtra(SystemAppsActivity.EXTRA_SELECTED_APP_NAME)
            
            if (packageName != null && appName != null) {
                virtualizeSystemApp(packageName, appName)
            }
        }
    }

    /**
     * Muestra una advertencia antes de instalar el APK sobre posibles incompatibilidades
     */
    private fun showInstallWarningDialog(apkPath: String, apkName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_install_warning_title))
            .setMessage(getString(R.string.dialog_install_warning_message))
            .setPositiveButton(getString(R.string.action_continue)) { _, _ ->
                installApk(apkPath, apkName)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    /**
     * Instala un APK en el contenedor virtual usando BlackBox
     */
    private fun installApk(apkPath: String, apkName: String) {
        binding.progressBar.visibility = View.VISIBLE
        Toast.makeText(this, getString(R.string.toast_installing_apk, apkName), Toast.LENGTH_SHORT).show()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Step 1: Copy APK to app's private cache (guaranteed accessible location)
                val originalFile = java.io.File(apkPath)
                val safeDir = java.io.File(cacheDir, "safe_install")
                if (!safeDir.exists()) safeDir.mkdirs()
                val safeFile = java.io.File(safeDir, originalFile.name)
                originalFile.copyTo(safeFile, overwrite = true)
                
                android.util.Log.d("MainActivity", "APK copied to safe location: ${safeFile.absolutePath}")
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_preparing_install), Toast.LENGTH_SHORT).show()
                }

                // Step 2: Use BlackBox's standard File API (which uses FLAG_STORAGE internally)
                // This ensures all executors (copy, lib extraction, registration) run properly
                val result = BlackBoxCore.get().installPackageAsUser(safeFile, USER_ID)
                
                android.util.Log.d("MainActivity", "Install result: success=${result.success}, msg=${result.msg}")
                
                // Cleanup temp file
                try { safeFile.delete() } catch (e: Exception) {}
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    
                    if (result.success) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_install_success, apkName), Toast.LENGTH_SHORT).show()
                        loadVirtualApps()
                    } else {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(getString(R.string.dialog_install_error_title))
                            .setMessage(getString(R.string.dialog_install_error_message, result.msg))
                            .setPositiveButton(getString(R.string.action_ok), null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Install exception", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, getString(R.string.toast_error_generic, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Carga todas las aplicaciones instaladas en el contenedor virtual
     */
    private fun loadVirtualApps() {
        binding.progressBar.visibility = View.VISIBLE
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.d("MainActivity", "loadVirtualApps: calling getInstalledApplications for USER_ID=$USER_ID")
                val installedApps = BlackBoxCore.get().getInstalledApplications(0, USER_ID)
                android.util.Log.d("MainActivity", "loadVirtualApps: got ${installedApps?.size ?: 0} apps")
                val apps = mutableListOf<VirtualApp>()
                
                installedApps?.forEach { appInfo ->
                    // Ocultar servicios y paquetes de infraestructura de Google del launcher principal
                    if (top.niunaijun.blackbox.core.GmsCore.isGoogleAppOrService(appInfo.packageName)) {
                        return@forEach
                    }

                    val packageManager = packageManager
                    val icon = try {
                        appInfo.loadIcon(packageManager)
                    } catch (e: Exception) {
                        null
                    }
                    
                    val isTor = com.editech.services.tor.TorManager.isTorEnabled(appInfo.packageName)
                    apps.add(
                        VirtualApp(
                            packageName = appInfo.packageName,
                            name = appInfo.loadLabel(packageManager).toString(),
                            icon = icon,
                            userId = USER_ID,
                            isTorEnabled = isTor
                        )
                    )
                }
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    virtualApps.clear()
                    virtualApps.addAll(apps)
                    
                    // Filter initially (or just show all)
                    filterApps(binding.etSearch.text.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_load_apps_error, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }
    
    private fun filterApps(query: String) {
        val filteredList = if (query.isEmpty()) {
            virtualApps
        } else {
            virtualApps.filter { it.name.contains(query, ignoreCase = true) }
        }
        
        adapter.updateApps(filteredList)
        
        if (filteredList.isEmpty()) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.rvVirtualApps.visibility = View.GONE
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvVirtualApps.visibility = View.VISIBLE
        }
    }
    
    /**
     * Lanza una aplicación virtual (Fase 5)
     */
    private fun launchVirtualApp(app: VirtualApp) {
        try {
            BlackBoxCore.get().launchApk(app.packageName, USER_ID)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_launch_error, app.name, e.message), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Muestra diálogo de confirmación para desinstalar (Fase 5)
     */
    private fun showUninstallDialog(app: VirtualApp): Boolean {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_uninstall_title))
            .setMessage(getString(R.string.dialog_uninstall_message, app.name))
            .setPositiveButton(getString(R.string.action_uninstall)) { _, _ ->
                uninstallApp(app)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
        return true
    }
    
    /**
     * Desinstala una aplicación virtual (Fase 5)
     */
    private fun uninstallApp(app: VirtualApp) {
        binding.progressBar.visibility = View.VISIBLE
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BlackBoxCore.get().uninstallPackage(app.packageName)
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_uninstall_success, app.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    loadVirtualApps() // Recargar lista
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_uninstall_error, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    /**
     * Virtualiza una app del sistema dentro de BlackBox (PARALLEL SPACE feature)
     * Permite ejecutar apps ya instaladas en el contenedor virtual
     */
    private fun virtualizeSystemApp(packageName: String, appName: String) {
        binding.progressBar.visibility = View.VISIBLE
        Toast.makeText(this, getString(R.string.toast_virtualizing_app, appName), Toast.LENGTH_SHORT).show()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Instalar el APK del sistema en BlackBox usando el nombre del paquete
                // BlackBox obtiene el APK path automáticamente desde el sistema
                val result = BlackBoxCore.get().installPackageAsUser(packageName, USER_ID)
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    
                    if (result.success) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_virtualize_success, appName),
                            Toast.LENGTH_SHORT
                        ).show()
                        loadVirtualApps() // Recargar lista
                    } else {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(getString(R.string.dialog_virtualize_error_title))
                            .setMessage(getString(R.string.dialog_virtualize_error_message, appName, result.msg))
                            .setPositiveButton(getString(R.string.action_ok), null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_virtualize_error_generic, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}