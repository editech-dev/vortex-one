package com.editech.services.activities

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.editech.services.adapters.SystemAppsAdapter
import com.editech.services.databinding.ActivitySystemAppsBinding
import com.editech.services.models.SystemApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.editech.services.R

/**
 * Actividad que muestra las apps instaladas en el sistema
 * para virtualizarlas dentro de BlackBox (estilo Parallel Space)
 */
class SystemAppsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySystemAppsBinding
    private lateinit var adapter: SystemAppsAdapter
    
    companion object {
        const val EXTRA_SELECTED_APP_PACKAGE = "selected_app_package"
        const val EXTRA_SELECTED_APP_NAME = "selected_app_name"
        const val EXTRA_SELECTED_APP_PATH = "selected_app_path"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySystemAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupButtons()
        loadSystemApps()
    }
    
    private fun setupRecyclerView() {
        adapter = SystemAppsAdapter { systemApp ->
            onSystemAppSelected(systemApp)
        }
        
        binding.rvSystemApps.apply {
            val spanCount = resources.getInteger(R.integer.grid_span_count)
            layoutManager = GridLayoutManager(this@SystemAppsActivity, spanCount)
            adapter = this@SystemAppsActivity.adapter
        }
    }
    
    private fun setupButtons() {
        binding.btnCancel.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                finish()
            }
        }
    }
    
    /**
     * Carga todas las apps instaladas en el sistema (usuario, no sistema base)
     */
    private fun loadSystemApps() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Buscando aplicaciones instaladas..."
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pm = packageManager
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val systemApps = mutableListOf<SystemApp>()
                
                installedApps.forEach { appInfo ->
                    // Mostrar apps de usuario o apps del sistema actualizadas
                    if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                        
                        try {
                            val icon = appInfo.loadIcon(pm)
                            val name = appInfo.loadLabel(pm).toString()
                            val packageName = appInfo.packageName
                            val apkPath = appInfo.sourceDir // Path al APK instalado
                            
                            systemApps.add(
                                SystemApp(
                                    packageName = packageName,
                                    name = name,
                                    icon = icon,
                                    apkPath = apkPath
                                )
                            )
                        } catch (e: Exception) {
                            // Ignorar apps que no se puedan cargar
                        }
                    }
                }
                
                // Ordenar alfabéticamente
                systemApps.sortBy { it.name }
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    
                    if (systemApps.isEmpty()) {
                        binding.layoutEmptyState.visibility = View.VISIBLE
                        binding.rvSystemApps.visibility = View.GONE
                        binding.tvStatus.text = "No se encontraron aplicaciones"
                    } else {
                        binding.layoutEmptyState.visibility = View.GONE
                        binding.rvSystemApps.visibility = View.VISIBLE
                        binding.tvStatus.text = "${systemApps.size} aplicaciones encontradas"
                        adapter.submitList(systemApps)
                        
                        // Auto-focus en el primer item
                        binding.rvSystemApps.requestFocus()
                    }
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "Error: ${e.message}"
                }
            }
        }
    }
    
    /**
     * Callback cuando el usuario selecciona una app para virtualizar
     */
    private fun onSystemAppSelected(systemApp: SystemApp) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SELECTED_APP_PACKAGE, systemApp.packageName)
            putExtra(EXTRA_SELECTED_APP_NAME, systemApp.name)
            putExtra(EXTRA_SELECTED_APP_PATH, systemApp.apkPath)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
