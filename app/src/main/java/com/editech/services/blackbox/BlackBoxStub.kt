package com.editech.services.blackbox

/**
 * STUB TEMPORAL de BlackBox
 * Este archivo permite que el proyecto compile sin la librería BlackBox integrada
 * 
 * INSTRUCCIONES PARA INTEGRAR BLACKBOX:
 * 1. Descargar BlackBox desde: https://github.com/FBlackBox/BlackBox
 * 2. Opción A: Agregar como submódulo Git
 * 3. Opción B: Descargar el AAR y agregarlo en app/libs/
 * 4. Descomentar la dependencia en build.gradle.kts
 * 5. Eliminar este archivo (BlackBoxStub.kt)
 * 6. Descomentar las importaciones y código en App.kt y MainActivity.kt
 */

object BlackBoxStub {
    
    data class InstallResult(
        val success: Boolean,
        val msg: String = ""
    )
    
    fun installPackageAsUser(apkPath: String, userId: Int): InstallResult {
        // Stub: Simula instalación fallida
        return InstallResult(
            success = false,
            msg = "BlackBox no está integrado. Ver instrucciones en BlackBoxStub.kt"
        )
    }
    
    fun getInstalledApplications(flags: Int, userId: Int): List<android.content.pm.ApplicationInfo>? {
        // Stub: Retorna lista vacía
        return emptyList()
    }
    
    fun getInstalledPackageInfo(packageName: String, flags: Int, userId: Int): android.content.pm.PackageInfo? {
        // Stub: Retorna null
        return null
    }
    
    fun launchApk(packageName: String, userId: Int) {
        // Stub: No hace nada
    }
    
    fun uninstallPackage(packageName: String) {
        // Stub: No hace nada
    }
}
