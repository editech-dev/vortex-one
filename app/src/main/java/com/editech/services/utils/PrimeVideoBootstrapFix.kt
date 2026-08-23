package com.editech.services.utils

import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Repara el arranque de Prime Video dentro del sandbox.
 *
 * Prime Video (com.amazon.amazonvideo.livingroom) extrae su propio runtime de
 * UI (Ignite/Megablast) desde `assets/ignite-assets.tar`, empaquetado dentro
 * de su APK, hacia su carpeta `files/` la primera vez que arranca. Esa
 * extracción no ocurre dentro de este motor de virtualización — el resultado
 * es que `files/lua`, `files/fonts`, `files/shaders` y `files/bin/certs`
 * quedan vacíos, y la app crashea de inmediato al intentar cargar
 * `lua/appBootstrap.js`, en un bucle permanente.
 *
 * Verificado en dispositivo: extraer ese mismo .tar directamente en `files/`
 * resuelve el crash-loop sin tocar nada del propio motor.
 */
object PrimeVideoBootstrapFix {
    private const val TAG = "PrimeVideoBootstrapFix"
    private const val PACKAGE_NAME = "com.amazon.amazonvideo.livingroom"
    private const val BUNDLED_ASSET = "assets/ignite-assets.tar"
    private const val BOOTSTRAP_MARKER = "lua/appBootstrap.js"

    fun repairIfNeeded(userId: Int) {
        val app = try {
            BlackBoxCore.get().getInstalledApplications(0, userId)
                ?.firstOrNull { it.packageName == PACKAGE_NAME }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar apps instaladas: ${e.message}")
            null
        } ?: return // Prime Video no está instalado en el sandbox; nada que reparar.

        val filesDir = try {
            BEnvironment.getDataFilesDir(PACKAGE_NAME, userId)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo resolver files/ de Prime Video: ${e.message}")
            return
        } ?: return

        if (File(filesDir, BOOTSTRAP_MARKER).exists()) {
            return // Ya extraído — nada que hacer.
        }

        val apkPath = app.sourceDir
        if (apkPath.isNullOrBlank() || !File(apkPath).exists()) {
            Log.w(TAG, "APK de Prime Video no localizable en $apkPath")
            return
        }

        try {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(BUNDLED_ASSET)
                if (entry == null) {
                    Log.w(TAG, "Esta versión de Prime Video no trae $BUNDLED_ASSET; nada que extraer")
                    return
                }
                // El .tar viene comprimido con gzip dentro del propio asset (el
                // "tar" de Android lo destapa solo; tar es un formato tan viejo
                // que muchas implementaciones auto-detectan el magic de gzip).
                zip.getInputStream(entry).use { rawStream ->
                    GZIPInputStream(rawStream).use { tarStream ->
                        val count = TarExtractor.extract(tarStream, filesDir)
                        Log.i(TAG, "Bootstrap de Prime Video reparado: $count archivos extraídos a $filesDir")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falló la extracción del bootstrap de Prime Video: ${e.message}", e)
        }
    }
}
