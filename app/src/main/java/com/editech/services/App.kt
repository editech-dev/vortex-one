package com.editech.services

import android.app.Application
import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File

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
        // Inicializar BlackBox después de attachBaseContext
        BlackBoxCore.get().doCreate()
    }
}
