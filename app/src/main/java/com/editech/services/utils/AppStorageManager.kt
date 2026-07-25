package com.editech.services.utils

import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.File
import java.text.DecimalFormat

/**
 * Gestor de almacenamiento seguro para aplicaciones virtualizadas en BlackBox.
 * Garantiza que solo se borren los datos y la memoria caché pertenecientes a la app virtual seleccionada,
 * sin afectar las bases de datos SQLite del sistema ni la aplicación host.
 */
object AppStorageManager {

    private const val TAG = "AppStorageManager"

    /**
     * Formatea una cantidad de bytes en una cadena legible (B, KB, MB, GB).
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val formatted = DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble()))
        return "$formatted ${units[digitGroups]}"
    }

    /**
     * Calcula el tamaño total de la memoria caché de una aplicación virtual.
     */
    fun getAppCacheSize(packageName: String, userId: Int = 0): Long {
        if (packageName.isBlank()) return 0L
        var size = 0L
        try {
            val internalCache = BEnvironment.getDataCacheDir(packageName, userId)
            val externalCache = BEnvironment.getExternalDataCacheDir(packageName, userId)
            val codeCache = File(BEnvironment.getDataDir(packageName, userId), "code_cache")

            size += getDirectorySize(internalCache)
            size += getDirectorySize(externalCache)
            size += getDirectorySize(codeCache)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating cache size for $packageName", e)
        }
        return size
    }

    /**
     * Calcula el tamaño total de datos de usuario de una aplicación virtual (excluyendo la APK instalada).
     */
    fun getAppDataSize(packageName: String, userId: Int = 0): Long {
        if (packageName.isBlank()) return 0L
        var size = 0L
        try {
            val dataDir = BEnvironment.getDataDir(packageName, userId)
            val externalDir = BEnvironment.getExternalDataDir(packageName, userId)
            val deDir = BEnvironment.getDeDataDir(packageName, userId)

            size += getDirectorySize(dataDir)
            size += getDirectorySize(externalDir)
            size += getDirectorySize(deDir)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating data size for $packageName", e)
        }
        return size
    }

    /**
     * Elimina ÚNICAMENTE los archivos y carpetas dentro de la caché de la aplicación virtual.
     * Preserva SharedPreferences, bases de datos (databases/), archivos (files/) y configuraciones.
     * Retorna la cantidad de bytes liberados.
     */
    fun clearAppCache(packageName: String, userId: Int = 0): Long {
        if (packageName.isBlank()) return 0L
        val bytesBefore = getAppCacheSize(packageName, userId)
        try {
            deleteDirContents(BEnvironment.getDataCacheDir(packageName, userId))
            deleteDirContents(BEnvironment.getExternalDataCacheDir(packageName, userId))
            deleteDirContents(File(BEnvironment.getDataDir(packageName, userId), "code_cache"))
            Log.d(TAG, "Cleared cache for $packageName ($bytesBefore bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for $packageName", e)
        }
        return bytesBefore
    }

    /**
     * Restablece completamente los datos de la aplicación virtual (Reiniciar App).
     * 1. Detiene la ejecución del proceso de la app virtual si está corriendo.
     * 2. Limpia los datos de usuario (databases/, shared_prefs/, files/, cache/).
     * 3. No toca la APK instalada (base.apk) ni las bases de datos SQLite del sistema/host.
     */
    fun clearAppData(packageName: String, userId: Int = 0): Boolean {
        if (packageName.isBlank()) return false
        return try {
            // 1. Detener el proceso de la app virtual
            BlackBoxCore.get().stopPackage(packageName, userId)

            // 2. Limpiar directorio interno de datos de la app virtual
            val dataDir = BEnvironment.getDataDir(packageName, userId)
            if (dataDir.exists()) {
                dataDir.listFiles()?.forEach { child ->
                    // Conservar la carpeta de librerías si existe
                    if (child.name != "lib") {
                        deleteRecursive(child)
                    }
                }
            }

            // 3. Limpiar directorio externo de datos
            val externalDir = BEnvironment.getExternalDataDir(packageName, userId)
            if (externalDir.exists()) {
                deleteDirContents(externalDir)
            }

            // 4. Limpiar directorio DE (Device Encrypted) si existe
            val deDir = BEnvironment.getDeDataDir(packageName, userId)
            if (deDir.exists()) {
                deleteDirContents(deDir)
            }

            Log.d(TAG, "Cleared all app data for $packageName (Reset app)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing app data for $packageName", e)
            false
        }
    }

    /**
     * Limpia la memoria caché de todas las aplicaciones virtualizadas especificadas.
     * Retorna una pareja de (cantidad de apps limpiadas, bytes totales liberados).
     */
    fun clearAllAppsCache(packageNames: List<String>, userId: Int = 0): Pair<Int, Long> {
        var totalFreed = 0L
        var count = 0
        for (pkg in packageNames) {
            val freed = clearAppCache(pkg, userId)
            if (freed > 0) {
                totalFreed += freed
                count++
            }
        }
        return Pair(count, totalFreed)
    }

    private fun getDirectorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            size += if (file.isDirectory) getDirectorySize(file) else file.length()
        }
        return size
    }

    private fun deleteDirContents(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { deleteRecursive(it) }
    }

    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory) {
            fileOrDirectory.listFiles()?.forEach { deleteRecursive(it) }
        }
        fileOrDirectory.delete()
    }
}
