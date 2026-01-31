package com.editech.services

import android.app.Application
import android.content.Context
// TODO: Descomentar cuando BlackBox esté integrado
// import top.niunaijun.blackbox.BlackBoxCore

/**
 * Clase Application custom para OpenContainer-TV
 * Inicializa el motor de virtualización BlackBox
 */
class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // TODO: Descomentar cuando BlackBox esté integrado manualmente
        // Inicializar BlackBox Core ANTES de que se cree el contexto de la aplicación
        // Esto es crítico para que BlackBox pueda interceptar llamadas del sistema
        // BlackBoxCore.get().doAttachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        // Aquí se pueden agregar inicializaciones adicionales si es necesario
    }
}
