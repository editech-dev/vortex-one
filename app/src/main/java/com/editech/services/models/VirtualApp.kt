package com.editech.services.models

import android.graphics.drawable.Drawable

/**
 * Modelo de datos para una aplicación virtual instalada en BlackBox
 */
data class VirtualApp(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val versionName: String = "",
    val versionCode: Int = 0,
    val userId: Int = 0
)
