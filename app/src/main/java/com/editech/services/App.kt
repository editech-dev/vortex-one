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
        // Apply user-selected language before any resource is inflated
        val localeContext = com.editech.services.utils.LocaleHelper.applyLocale(base)
        super.attachBaseContext(localeContext)
        // Inicializar BlackBox Core con configuración
        BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
            override fun getHostPackageName(): String {
                return packageName
            }

            override fun isEnableDaemonService(): Boolean {
                return true
            }

            override fun requestInstallPackage(file: File, userId: Int): Boolean {
                if (!file.exists()) {
                    android.util.Log.w("App", "requestInstallPackage: file does not exist: ${file.absolutePath}")
                    return false
                }

                try {
                    val packageInfo = BlackBoxCore.getPackageManager().getPackageArchiveInfo(file.absolutePath, 0)
                    if (packageInfo == null) {
                        android.util.Log.e("App", "requestInstallPackage: failed to parse package info for ${file.name}")
                        return false
                    }

                    val targetPkg = packageInfo.packageName
                    val hostPkg = packageName

                    // Prevent modifying or replacing host package
                    if (targetPkg == hostPkg) {
                        android.util.Log.w("App", "requestInstallPackage: blocked attempt to install host package $targetPkg")
                        return false
                    }

                    // Versioning check: if package already exists in virtual space, ensure new version >= installed version
                    val existingPkgInfo = try {
                        BlackBoxCore.getBPackageManager().getPackageInfo(targetPkg, 0, userId)
                    } catch (e: Exception) {
                        null
                    }
                    if (existingPkgInfo != null) {
                        val currentVersionCode: Int = existingPkgInfo.versionCode
                        val newVersionCode: Int = packageInfo.versionCode
                        android.util.Log.d("App", "requestInstallPackage: current versionCode=$currentVersionCode, new versionCode=$newVersionCode for $targetPkg")
                        if (newVersionCode < currentVersionCode) {
                            android.util.Log.w("App", "requestInstallPackage: rejected downgrade from $currentVersionCode to $newVersionCode for $targetPkg")
                            return false
                        }
                    }

                    // Check ABI compatibility
                    if (!top.niunaijun.blackbox.utils.AbiUtils.isSupport(file)) {
                        android.util.Log.e("App", "requestInstallPackage: unsupported ABI for ${file.name}")
                        return false
                    }

                    // Perform installation into BlackBox virtual container
                    val result = BlackBoxCore.get().installPackageAsUser(file, userId)
                    android.util.Log.i("App", "requestInstallPackage: installed ${targetPkg} v${packageInfo.versionName} (${packageInfo.versionCode}) result: success=${result.success}, msg=${result.msg}")
                    return result.success
                } catch (e: Exception) {
                    android.util.Log.e("App", "requestInstallPackage exception", e)
                    return false
                }
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
        try {
            BlackBoxCore.get().doCreate()
            android.util.Log.d("App", "BlackBoxCore.doCreate() success")
        } catch (e: Exception) {
            android.util.Log.e("App", "BlackBoxCore.doCreate() failed", e)
        }

        // Inicializar TorManager (per-app Tor routing)
        try {
            com.editech.services.tor.TorManager.init(this)
            android.util.Log.d("App", "TorManager.init() success")
        } catch (e: Exception) {
            android.util.Log.e("App", "TorManager.init() failed", e)
        }
        
        // Initialize Unity Ads (Background Thread)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                com.editech.services.utils.AdManager.initialize(this@App, BuildConfig.DEBUG)
            } catch (e: Exception) {
                android.util.Log.e("App", "Failed to init ads", e)
            }
        }

        // Provision storage directories for installed virtual apps (Background Thread)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val installed = BlackBoxCore.get().getInstalledApplications(0, 0)
                for (app in installed) {
                    com.editech.services.utils.AppStorageManager.ensureAppStorageDirs(app.packageName, 0)
                }
            } catch (e: Exception) {
                android.util.Log.w("App", "Failed to provision virtual storage dirs: ${e.message}")
            }
        }
    }
}
