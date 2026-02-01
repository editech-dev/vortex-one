package com.editech.services.models

import android.graphics.drawable.Drawable

/**
 * Modelo para una app instalada en el sistema
 * que puede ser virtualizada en BlackBox
 */
data class SystemApp(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val apkPath: String // Path al APK: /data/app/xxx/base.apk
)
