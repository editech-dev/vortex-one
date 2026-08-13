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

    private fun safeGetFile(provider: () -> File?): File? {
        return try {
            provider()
        } catch (t: Throwable) {
            Log.w(TAG, "Safe path resolution failed: ${t.message}")
            null
        }
    }

    /**
     * Garantiza que todas las rutas de almacenamiento internas y externas de la app virtual
     * estén creadas con permisos completos antes de su lanzamiento o tras limpiezas.
     */
    fun ensureAppStorageDirs(packageName: String, userId: Int = 0) {
        if (packageName.isBlank()) return
        try {
            safeGetFile { BEnvironment.getDataDir(packageName, userId) }?.mkdirs()
            safeGetFile { BEnvironment.getDataCacheDir(packageName, userId) }?.mkdirs()
            safeGetFile { BEnvironment.getDataFilesDir(packageName, userId) }?.mkdirs()
            safeGetFile { BEnvironment.getExternalDataDir(packageName, userId) }?.mkdirs()
            safeGetFile { BEnvironment.getExternalDataCacheDir(packageName, userId) }?.mkdirs()
            safeGetFile { BEnvironment.getExternalDataFilesDir(packageName, userId) }?.mkdirs()
            safeGetFile { File(BEnvironment.getExternalDataFilesDir(packageName, userId), "Documents") }?.mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure storage dirs for $packageName: ${e.message}")
        }
    }

    /**
     * Calcula el tamaño total de la memoria caché de una aplicación virtual.
     */
    fun getAppCacheSize(packageName: String, userId: Int = 0): Long {
        if (packageName.isBlank()) return 0L
        var size = 0L
        try {
            val internalCache = safeGetFile { BEnvironment.getDataCacheDir(packageName, userId) }
            val externalCache = safeGetFile { BEnvironment.getExternalDataCacheDir(packageName, userId) }
            val codeCache = safeGetFile { File(BEnvironment.getDataDir(packageName, userId), "code_cache") }
            val glideCache = safeGetFile { File(BEnvironment.getDataDir(packageName, userId), "app_glide_cache") }
            val picassoCache = safeGetFile { File(BEnvironment.getDataDir(packageName, userId), "app_picasso_cache") }

            if (internalCache != null) size += getDirectorySize(internalCache)
            if (externalCache != null) size += getDirectorySize(externalCache)
            if (codeCache != null) size += getDirectorySize(codeCache)
            if (glideCache != null) size += getDirectorySize(glideCache)
            if (picassoCache != null) size += getDirectorySize(picassoCache)
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
            val dataDir = safeGetFile { BEnvironment.getDataDir(packageName, userId) }
            val externalDir = safeGetFile { BEnvironment.getExternalDataDir(packageName, userId) }
            val deDir = safeGetFile { BEnvironment.getDeDataDir(packageName, userId) }

            if (dataDir != null) size += getDirectorySize(dataDir)
            if (externalDir != null) size += getDirectorySize(externalDir)
            if (deDir != null) size += getDirectorySize(deDir)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating data size for $packageName", e)
        }
        return size
    }

    /**
     * Elimina ÚNICAMENTE los archivos de memoria caché temporal de la aplicación virtual.
     * Protege explícitamente bases de datos (*.db), listas de canales y limpia metadatos desactualizados.
     */
    fun clearAppCache(packageName: String, userId: Int = 0): Long {
        if (packageName.isBlank()) return 0L
        val bytesBefore = getAppCacheSize(packageName, userId)
        try {
            // Stop process cleanly before clearing cache
            try {
                BlackBoxCore.get().stopPackage(packageName, userId)
            } catch (ignored: Throwable) {}

            val cacheTargets = listOfNotNull(
                safeGetFile { BEnvironment.getDataCacheDir(packageName, userId) },
                safeGetFile { BEnvironment.getExternalDataCacheDir(packageName, userId) },
                safeGetFile { File(BEnvironment.getDataDir(packageName, userId), "code_cache") },
                safeGetFile { File(BEnvironment.getDataDir(packageName, userId), "app_glide_cache") },
                safeGetFile { File(BEnvironment.getDataDir(packageName, userId), "app_picasso_cache") }
            )

            for (dir in cacheTargets) {
                deleteDirContents(dir)
            }

            // Clear stale metadata JSON cache files (masnew_*, column_data_*) while preserving SQLite .db databases
            val docsDir = safeGetFile { File(BEnvironment.getExternalDataFilesDir(packageName, userId), "Documents") }
            val dataFilesDir = safeGetFile { BEnvironment.getDataFilesDir(packageName, userId) }
            deleteNonDatabaseFiles(docsDir)
            deleteNonDatabaseFiles(dataFilesDir)

            // Provision directories again after cache clearing
            ensureAppStorageDirs(packageName, userId)
            Log.d(TAG, "Cleared cache for $packageName ($bytesBefore bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for $packageName", e)
        }
        return bytesBefore
    }

    /**
     * Restablece completamente los datos de la aplicación virtual (Reiniciar App).
     */
    fun clearAppData(packageName: String, userId: Int = 0): Boolean {
        if (packageName.isBlank()) return false
        return try {
            // 1. Detener el proceso de la app virtual
            try {
                BlackBoxCore.get().stopPackage(packageName, userId)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to stop package $packageName: ${t.message}")
            }

            // 2. Limpiar directorio interno de datos de la app virtual (preservando librerías nativas)
            val dataDir = safeGetFile { BEnvironment.getDataDir(packageName, userId) }
            if (dataDir != null && dataDir.exists()) {
                dataDir.listFiles()?.forEach { child ->
                    if (child.name != "lib") {
                        deleteRecursive(child)
                    }
                }
            }

            // 3. Limpiar directorio externo de datos
            val externalDir = safeGetFile { BEnvironment.getExternalDataDir(packageName, userId) }
            if (externalDir != null && externalDir.exists()) {
                deleteDirContents(externalDir)
            }

            // 4. Limpiar directorio DE (Device Encrypted) si existe
            val deDir = safeGetFile { BEnvironment.getDeDataDir(packageName, userId) }
            if (deDir != null && deDir.exists()) {
                deleteDirContents(deDir)
            }

            // 5. Re-crear estructura de directorios
            ensureAppStorageDirs(packageName, userId)

            Log.d(TAG, "Cleared all app data for $packageName (Reset app)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing app data for $packageName", e)
            false
        }
    }

    /**
     * Limpia la memoria caché de todas las aplicaciones virtualizadas especificadas.
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

    private fun deleteNonDatabaseFiles(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteNonDatabaseFiles(file)
            } else if (!file.name.endsWith(".db") && !file.name.endsWith(".db-journal") && !file.name.endsWith(".sqlite")) {
                file.delete()
            }
        }
    }

    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory) {
            fileOrDirectory.listFiles()?.forEach { deleteRecursive(it) }
        }
        fileOrDirectory.delete()
    }
}
