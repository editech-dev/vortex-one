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
import com.editech.services.adapters.VirtualAppsAdapter
import com.editech.services.databinding.ActivityMainBinding
import com.editech.services.models.VirtualApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.editech.services.blackbox.BlackBoxStub
// TODO: Descomentar cuando BlackBox esté integrado
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupButtons()
        loadVirtualApps()
    }
    
    private fun setupRecyclerView() {
        adapter = VirtualAppsAdapter(
            apps = virtualApps,
            onAppClick = { app -> launchVirtualApp(app) },
            onAppLongClick = { app -> showUninstallDialog(app) }
        )
        
        binding.rvVirtualApps.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4) // 4 columnas para TV
            adapter = this@MainActivity.adapter
        }
    }
    
    private fun setupButtons() {
        binding.btnInstallApk.setOnClickListener {
            openFileScannerActivity()
        }
    }
    
    private fun openFileScannerActivity() {
        val intent = Intent(this, FileScannerActivity::class.java)
        startActivityForResult(intent, FileScannerActivity.REQUEST_CODE_SELECT_APK)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == FileScannerActivity.REQUEST_CODE_SELECT_APK && resultCode == Activity.RESULT_OK) {
            val apkPath = data?.getStringExtra(FileScannerActivity.EXTRA_APK_PATH)
            val apkName = data?.getStringExtra(FileScannerActivity.EXTRA_APK_NAME)
            
            if (apkPath != null) {
                installApk(apkPath, apkName ?: "APK")
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
                val result = BlackBoxStub.installPackageAsUser(apkPath, USER_ID)
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    
                    if (result.success) {
                        Toast.makeText(
                            this@MainActivity,
                            "✓ $apkName instalado correctamente",
                            Toast.LENGTH_LONG
                        ).show()
                        loadVirtualApps() // Recargar lista
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "✗ Error al instalar: ${result.msg}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "✗ Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
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
                val installedApps = BlackBoxStub.getInstalledApplications(0, USER_ID)
                val apps = mutableListOf<VirtualApp>()
                
                installedApps?.forEach { appInfo ->
                    // Filtrar aplicaciones del sistema (solo mostrar apps instaladas por el usuario)
                    if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                        val packageManager = packageManager
                        val icon = try {
                            BlackBoxStub.getInstalledPackageInfo(appInfo.packageName, 0, USER_ID)
                                ?.applicationInfo?.loadIcon(packageManager)
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
                }
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    virtualApps.clear()
                    virtualApps.addAll(apps)
                    adapter.updateApps(virtualApps)
                    
                    // Mostrar/ocultar empty state
                    if (virtualApps.isEmpty()) {
                        binding.layoutEmptyState.visibility = View.VISIBLE
                        binding.rvVirtualApps.visibility = View.GONE
                    } else {
                        binding.layoutEmptyState.visibility = View.GONE
                        binding.rvVirtualApps.visibility = View.VISIBLE
                    }
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
    
    /**
     * Lanza una aplicación virtual (Fase 5)
     */
    private fun launchVirtualApp(app: VirtualApp) {
        try {
            BlackBoxStub.launchApk(app.packageName, USER_ID)
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
                BlackBoxStub.uninstallPackage(app.packageName)
                
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
}