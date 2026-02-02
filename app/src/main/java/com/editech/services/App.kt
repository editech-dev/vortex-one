package com.editech.services

import android.app.Application
import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Clase Application custom para OpenContainer-TV
 * Inicializa el motor de virtualización BlackBox (REAL)
 */
class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Inicializar BlackBox Core con configuración
        BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
            override fun getHostPackageName(): String {
                return packageName
            }

            override fun isEnableDaemonService(): Boolean {
                return true
            }

            override fun requestInstallPackage(file: File, userId: Int): Boolean {
                // Permitir instalación de paquetes
                return false
            }
        })
    }

    override fun onCreate() {
        super.onCreate()
        
        // Fix for WebView causing crash in multi-process environment (BlackBox vs Main)
        // https://crbug.com/558377
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val processName = android.app.Application.getProcessName()
            if (processName != packageName) {
                // If we are in a secondary process (like :black), use a suffix
                // Actually, the log says :black OWNS the lock on the default.
                // So we should try to give THIS process (main) a suffix if needed,
                // or just ensure unique suffixes for non-main processes.
                android.webkit.WebView.setDataDirectorySuffix(processName)
            }
        }

        // Inicializar BlackBox después de attachBaseContext
        BlackBoxCore.get().doCreate()
        
        // Initialize Unity Ads (Background Thread)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                com.editech.services.utils.AdManager.initialize(this@App, BuildConfig.DEBUG)
            } catch (e: Exception) {
                android.util.Log.e("App", "Failed to init ads", e)
            }
        }
    }
}
