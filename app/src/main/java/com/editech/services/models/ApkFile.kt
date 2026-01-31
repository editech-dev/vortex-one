package com.editech.services.models

/**
 * Modelo de datos para un archivo APK encontrado en el almacenamiento
 */
data class ApkFile(
    val name: String,
    val path: String,
    val size: Long
)
