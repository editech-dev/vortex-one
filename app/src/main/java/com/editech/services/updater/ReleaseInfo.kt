package com.editech.services.updater

/**
 * ReleaseInfo holds parsed GitHub release metadata.
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val isNewer: Boolean,
    val publishedAt: String = ""
)

sealed class UpdateResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateResult()
    data class UpToDate(val currentVersion: String) : UpdateResult()
    data class Error(val message: String, val throwable: Throwable? = null) : UpdateResult()
}
