package com.editech.services.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.editech.services.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateManager — Handles version checking, APK downloading, and installation
 * directly from GitHub Releases without requiring external backends or databases.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/editech-dev/vortex-one/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 15000

    /**
     * Checks if a newer release is available on GitHub.
     */
    suspend fun checkForUpdates(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "VortexOne-App/${BuildConfig.VERSION_NAME}")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                return@withContext UpdateResult.Error("GitHub API returned HTTP $responseCode")
            }

            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)

            val tagName = json.optString("tag_name", "")
            val title = json.optString("name", tagName)
            val changelog = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")
            val cleanRemoteVersion = tagName.removePrefix("v").trim()
            val localVersion = BuildConfig.VERSION_NAME.removePrefix("v").trim()

            val isNewer = isNewerVersion(cleanRemoteVersion, localVersion)

            // Parse assets to find the appropriate APK for device architecture
            val assetsArray = json.optJSONArray("assets")
            var matchedUrl = ""
            var matchedName = ""
            var matchedSize = 0L

            if (assetsArray != null && assetsArray.length() > 0) {
                val supportedAbis = Build.SUPPORTED_ABIS ?: arrayOf("armeabi-v7a")
                val apkList = mutableListOf<ApkAsset>()

                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val name = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")
                    val size = asset.optLong("size", 0L)
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkList.add(ApkAsset(name, downloadUrl, size))
                    }
                }

                // 1. Try matching the primary device ABI
                val primaryAbi = supportedAbis.firstOrNull() ?: "armeabi-v7a"
                var selectedAsset = apkList.firstOrNull { it.name.contains(primaryAbi, ignoreCase = true) }

                // 2. Try matching any supported ABI
                if (selectedAsset == null) {
                    for (abi in supportedAbis) {
                        selectedAsset = apkList.firstOrNull { it.name.contains(abi, ignoreCase = true) }
                        if (selectedAsset != null) break
                    }
                }

                // 3. Fallback to universal APK
                if (selectedAsset == null) {
                    selectedAsset = apkList.firstOrNull { it.name.contains("universal", ignoreCase = true) }
                }

                // 4. Fallback to any available APK
                if (selectedAsset == null) {
                    selectedAsset = apkList.firstOrNull()
                }

                if (selectedAsset != null) {
                    matchedUrl = selectedAsset.downloadUrl
                    matchedName = selectedAsset.name
                    matchedSize = selectedAsset.size
                }
            }

            val releaseInfo = ReleaseInfo(
                tagName = tagName,
                versionName = cleanRemoteVersion,
                title = title,
                changelog = changelog,
                downloadUrl = matchedUrl,
                assetName = matchedName,
                sizeBytes = matchedSize,
                isNewer = isNewer,
                publishedAt = publishedAt
            )

            if (isNewer && matchedUrl.isNotEmpty()) {
                UpdateResult.UpdateAvailable(releaseInfo)
            } else {
                UpdateResult.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking updates: ${e.message}", e)
            UpdateResult.Error("Error al consultar actualizaciones: ${e.message}", e)
        }
    }

    /**
     * Compares two SemVer strings (e.g. "2.0.3" vs "2.0.2")
     * Returns true if remote is strictly greater than local.
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isBlank() || local.isBlank()) return false
        val cleanRemote = remote.removePrefix("v").trim()
        val cleanLocal = local.removePrefix("v").trim()

        val rParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLength) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    /**
     * Downloads the APK file into app's cache directory with progress reporting.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        assetName: String,
        onProgress: (percent: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            val outputFile = File(updatesDir, assetName.ifEmpty { "vortex_update.apk" })
            if (outputFile.exists()) outputFile.delete()

            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true

            // Follow HTTP redirects if needed (GitHub assets redirect to AWS S3)
            var currentConn = conn
            var redirectCount = 0
            while (currentConn.responseCode in 300..399 && redirectCount < 5) {
                val newUrl = currentConn.getHeaderField("Location")
                currentConn.disconnect()
                val nextUrl = URL(newUrl)
                currentConn = nextUrl.openConnection() as HttpURLConnection
                currentConn.connectTimeout = CONNECT_TIMEOUT_MS
                currentConn.readTimeout = 30000
                redirectCount++
            }

            val totalBytes = currentConn.contentLength.toLong()
            var bytesReadTotal = 0L

            currentConn.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesReadTotal += read
                        val percent = if (totalBytes > 0) {
                            ((bytesReadTotal * 100) / totalBytes).toInt()
                        } else {
                            -1
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(percent, bytesReadTotal, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            Result.success(outputFile)
        } catch (e: Throwable) {
            Log.e(TAG, "Download APK failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Launches Android's native package installer for the downloaded APK.
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install APK launch failed: ${e.message}", e)
        }
    }

    private data class ApkAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long
    )
}
