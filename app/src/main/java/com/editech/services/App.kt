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

        // Contener también el DNS del proceso anfitrión. Sin esto, el updater y
        // el SDK de anuncios resolvían por el DNS del sistema y sus dominios
        // aparecían en el DNS de la red doméstica. POLICY_DOH no bloquea nada:
        // sólo obliga a que la resolución salga por DoH y nunca por el resolver
        // del sistema. Tor corre en un proceso aparte y no le afecta.
        try {
            top.niunaijun.blackbox.core.NativeCore.enableNetworkGuard(
                top.niunaijun.blackbox.fake.service.libcore.OsStub.POLICY_DOH
            )
            android.util.Log.d("App", "Network guard enabled for host process")
        } catch (e: Throwable) {
            android.util.Log.e("App", "Could not enable host network guard", e)
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

        // Google Play Services debe estar siempre presente dentro del sandbox: sin
        // él, la comprobación de proveedor SSL que traen empaquetada YouTube/Prime/
        // etc. envenena el SSLSocketFactory de todo el proceso ("Attempted to use
        // SSL unpatched. Google Play Services needs update."), lo que también tumba
        // nuestro propio resolver DoH aunque no dependa de Google. Se instala solo
        // si falta — ya no hay control en Ajustes para desinstalarlo.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val wasInstalled = BlackBoxCore.get().isInstallGms(0)
                android.util.Log.i("App", "Estado de Google Play Services antes de reparar: instalado=$wasInstalled")
                // No basta con "isInstallGms() == true": el registro de paquetes
                // virtuales puede quedar marcado como instalado mientras los datos
                // reales ya no están (p.ej. tras una desinstalación manual anterior
                // que dejó el estado a medias). Reparar siempre desde cero es la
                // única forma de garantizar que los archivos y el registro coincidan.
                BlackBoxCore.get().uninstallGms(0)
                val result = BlackBoxCore.get().installGms(0)
                android.util.Log.i("App", "Reparación de Google Play Services: success=${result.success}, msg=${result.msg}, instalado=${BlackBoxCore.get().isInstallGms(0)}")
            } catch (e: Exception) {
                android.util.Log.w("App", "No se pudo reparar Google Play Services: ${e.message}", e)
            }
        }

        // Prime Video empaqueta su runtime de arranque (Ignite/Megablast, basado
        // en Lua) como assets/ignite-assets.tar dentro de su propia APK y lo
        // extrae a su carpeta files/ en el primer inicio. Esa extracción falla
        // en silencio dentro del sandbox — el motivo exacto no importa tanto
        // como el síntoma: files/lua queda vacío, la app no encuentra
        // lua/appBootstrap.js y entra en un crash-loop permanente. Se repara
        // extrayendo ese mismo .tar nosotros mismos si falta.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                com.editech.services.utils.PrimeVideoBootstrapFix.repairIfNeeded(0)
            } catch (e: Exception) {
                android.util.Log.w("App", "No se pudo reparar el bootstrap de Prime Video: ${e.message}", e)
            }
        }
    }
}
