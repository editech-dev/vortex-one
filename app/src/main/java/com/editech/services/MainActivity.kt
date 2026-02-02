package com.editech.services

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.editech.services.activities.FileScannerActivity
import com.editech.services.activities.SystemAppsActivity
import com.editech.services.adapters.VirtualAppsAdapter
import com.editech.services.databinding.ActivityMainBinding
import com.editech.services.models.VirtualApp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupButtons()
        setupSearch()
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
    }
    
    private fun openFileScannerActivity() {
        val intent = Intent(this, FileScannerActivity::class.java)
        startActivityForResult(intent, FileScannerActivity.REQUEST_CODE_SELECT_APK)
    }
    
    private fun openSystemAppsActivity() {
        val intent = Intent(this, SystemAppsActivity::class.java)
        startActivityForResult(intent, REQUEST_CODE_SELECT_SYSTEM_APP)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // Handle APK file selection
        if (requestCode == FileScannerActivity.REQUEST_CODE_SELECT_APK && resultCode == Activity.RESULT_OK) {
            val apkPath = data?.getStringExtra(FileScannerActivity.EXTRA_APK_PATH)
            val apkName = data?.getStringExtra(FileScannerActivity.EXTRA_APK_NAME)
            
            if (apkPath != null) {
                installApk(apkPath, apkName ?: "APK")
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
     * Instala un APK en el contenedor virtual usando BlackBox
     */
    private fun installApk(apkPath: String, apkName: String) {
        binding.progressBar.visibility = View.VISIBLE
        Toast.makeText(this, "Instalando $apkName...", Toast.LENGTH_SHORT).show()
        
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
                    Toast.makeText(this@MainActivity, "Preparando instalación...", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@MainActivity, "✓ $apkName instalado correctamente", Toast.LENGTH_LONG).show()
                        loadVirtualApps()
                    } else {
                        Toast.makeText(this@MainActivity, "✗ Error al instalar: ${result.msg}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Install exception", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "✗ Error: ${e.message}", Toast.LENGTH_LONG).show()
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
                    // Mostrar TODAS las apps virtualizadas (incluyendo apps del sistema clonadas)
                    val packageManager = packageManager
                    val icon = try {
                        appInfo.loadIcon(packageManager)
                    } catch (e: Exception) {
                        null
                    }
                    
                    apps.add(
                        VirtualApp(
                            packageName = appInfo.packageName,
                            name = appInfo.loadLabel(packageManager).toString(),
                            icon = icon,
                            userId = USER_ID
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
                        "Error al cargar apps: ${e.message}",
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
            Toast.makeText(this, "Error al lanzar ${app.name}: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Muestra diálogo de confirmación para desinstalar (Fase 5)
     */
    private fun showUninstallDialog(app: VirtualApp): Boolean {
        AlertDialog.Builder(this)
            .setTitle("Desinstalar aplicación")
            .setMessage("¿Deseas desinstalar ${app.name}?")
            .setPositiveButton("Desinstalar") { _, _ ->
                uninstallApp(app)
            }
            .setNegativeButton("Cancelar", null)
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
                        "✓ ${app.name} desinstalado",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadVirtualApps() // Recargar lista
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Error al desinstalar: ${e.message}",
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
        Toast.makeText(this, "Virtualizando $appName...", Toast.LENGTH_SHORT).show()
        
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
                            "✓ $appName ahora corre virtualizado",
                            Toast.LENGTH_LONG
                        ).show()
                        loadVirtualApps() // Recargar lista
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "✗ Error: ${result.msg}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "✗ Error al virtualizar: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}