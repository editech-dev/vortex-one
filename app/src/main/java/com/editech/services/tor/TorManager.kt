package com.editech.services.tor

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Data class representing Tor exit node metadata.
 */
data class TorExitInfo(
    val ip: String,
    val countryCode: String? = null,
    val countryName: String? = null,
    val flagEmoji: String? = null
)

/**
 * TorManager — Singleton that tracks per-app Tor routing state and exit node identity.
 *
 * Responsibilities:
 *  - Persist which apps have Tor routing enabled (Multi-process safe SharedPreferences)
 *  - Start/stop TorService based on demand
 *  - Query & track live Tor Exit IP and Country Flag through SOCKS5 proxy
 *  - Expose fast-path @JvmStatic methods for OsStub and LauncherActivity reflection calls
 *  - Provide LiveData status & exit node info for UI (TorFragment & Splash Screen)
 */
object TorManager {

    private const val TAG = "TorManager"
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 9150          // 9150 avoids clash with Orbot (9050)
    const val TOR_DNS_PORT = 5453        // Local Tor DNS listener port
    private const val PROXY_CHECK_TIMEOUT_MS = 300

    enum class TorStatus { STOPPED, STARTING, RUNNING, ERROR }

    // In-memory map: packageName -> torEnabled (fast path for OsStub hook)
    private val torEnabledApps = ConcurrentHashMap<String, Boolean>()

    private val _status = MutableLiveData(TorStatus.STOPPED)
    val status: LiveData<TorStatus> get() = _status

    private val _exitInfo = MutableLiveData<TorExitInfo?>(null)
    val exitInfo: LiveData<TorExitInfo?> get() = _exitInfo

    @Volatile
    var currentExitInfo: TorExitInfo? = null
        private set

    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val executor = Executors.newCachedThreadPool()

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        prefs = appCtx.getSharedPreferences("tor_per_app", Context.MODE_PRIVATE)
        reloadPreferences()

        Log.d(TAG, "Initialized. Apps with Tor: ${torEnabledApps.filter { it.value }.keys}")

        // Auto-start or sync status if running in main process and any app has Tor enabled
        val processName = getCurrentProcessName(appCtx)
        if (processName == context.packageName) {
            val anyEnabled = torEnabledApps.any { it.value }
            if (anyEnabled) {
                if (isProxyReachable()) {
                    updateStatus(TorStatus.RUNNING)
                    refreshExitInfoAsync()
                } else {
                    startService()
                }
            }
        }
    }

    private fun reloadPreferences() {
        try {
            val p = prefs ?: appContext?.getSharedPreferences("tor_per_app", Context.MODE_PRIVATE)
            p?.all?.forEach { (k, v) ->
                if (v is Boolean) torEnabledApps[k] = v
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reload preferences: ${e.message}")
        }
    }

    private fun getCurrentProcessName(context: Context): String? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            try {
                val pid = android.os.Process.myPid()
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.runningAppProcesses?.find { it.pid == pid }?.processName
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Sinks current proxy connectivity state into LiveData and returns it.
     */
    @JvmStatic
    fun checkCurrentStatus(): TorStatus {
        val isReachable = isProxyReachable()
        val current = _status.value
        val newStatus = when {
            isReachable -> TorStatus.RUNNING
            current == TorStatus.RUNNING -> TorStatus.STOPPED
            else -> current ?: TorStatus.STOPPED
        }
        if (newStatus != current) {
            updateStatus(newStatus)
        }
        if (isReachable && currentExitInfo == null) {
            refreshExitInfoAsync()
        }
        return newStatus
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-app state
    // ─────────────────────────────────────────────────────────────────────────

    fun isTorEnabled(packageName: String): Boolean {
        val cached = torEnabledApps[packageName]
        if (cached != null) return cached
        reloadPreferences()
        return torEnabledApps[packageName] == true
    }

    fun setTorEnabled(packageName: String, enabled: Boolean) {
        torEnabledApps[packageName] = enabled
        prefs?.edit()?.putBoolean(packageName, enabled)?.apply()
        Log.d(TAG, "Tor ${if (enabled) "enabled" else "disabled"} for $packageName")

        // Stop virtual package so it restarts with fresh DNS/socket state
        try {
            top.niunaijun.blackbox.BlackBoxCore.get().stopPackage(packageName, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not stop package $packageName on Tor state change: ${t.message}")
        }

        val anyEnabled = torEnabledApps.any { it.value }
        val current = _status.value
        when {
            enabled && (current == TorStatus.STOPPED || current == TorStatus.ERROR) -> startService()
            !anyEnabled && current != TorStatus.STOPPED -> stopService()
        }
    }

    fun getTorEnabledApps(): List<String> =
        torEnabledApps.filter { it.value }.keys.toList()

    // ─────────────────────────────────────────────────────────────────────────
    // Fast-path static methods — called via reflection from OsStub / UI
    // ─────────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun isTorEnabledForPackage(packageName: String): Boolean {
        val cached = torEnabledApps[packageName]
        if (cached != null) return cached
        reloadPreferences()
        return torEnabledApps[packageName] == true
    }

    @JvmStatic
    fun getTorExitIp(): String? = currentExitInfo?.ip

    @JvmStatic
    fun getTorExitCountry(): String? = currentExitInfo?.countryName

    @JvmStatic
    fun getTorExitFlag(): String? = currentExitInfo?.flagEmoji

    @JvmStatic
    fun fetchTorExitInfoSync(): TorExitInfo? {
        return try {
            val future = executor.submit<TorExitInfo?> { fetchTorExitInfoInternal(false) }
            future.get(5000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            currentExitInfo
        }
    }

    /**
     * Fast check: can we reach the Tor SOCKS5 proxy at 127.0.0.1:9150?
     * Safe to call from both main thread and background threads.
     */
    @JvmStatic
    fun isProxyReachable(): Boolean {
        return if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val executorService = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val future = executorService.submit(java.util.concurrent.Callable { performProxyPing() })
                val result = future.get(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                executorService.shutdown()
                result
            } catch (e: Exception) {
                executorService.shutdownNow()
                false
            }
        } else {
            performProxyPing()
        }
    }

    private fun performProxyPing(): Boolean = try {
        val sock = java.net.Socket()
        sock.connect(
            java.net.InetSocketAddress(SOCKS_HOST, SOCKS_PORT),
            PROXY_CHECK_TIMEOUT_MS
        )
        sock.close()
        true
    } catch (e: Exception) {
        false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle & Identity change
    // ─────────────────────────────────────────────────────────────────────────

    fun startService() {
        val ctx = appContext ?: return
        updateStatus(TorStatus.STARTING)
        val intent = Intent(ctx, TorService::class.java).apply {
            action = TorService.ACTION_START
        }
        ctx.startForegroundService(intent)
        Log.d(TAG, "TorService start requested")
    }

    fun stopService() {
        val ctx = appContext ?: return
        val intent = Intent(ctx, TorService::class.java).apply {
            action = TorService.ACTION_STOP
        }
        ctx.startService(intent)
        currentExitInfo = null
        _exitInfo.postValue(null)
        Log.d(TAG, "TorService stop requested")
    }

    fun requestNewIdentity(onComplete: ((TorExitInfo?) -> Unit)? = null) {
        val ctx = appContext ?: return
        val intent = Intent(ctx, TorService::class.java).apply {
            action = TorService.ACTION_NEW_IDENTITY
        }
        ctx.startService(intent)
        Log.d(TAG, "New identity requested")

        // Trigger asynchronous refresh of exit node information after circuit rebuild
        coroutineScope.launch {
            try {
                // Wait briefly for Tor to cycle circuits
                kotlinx.coroutines.delay(1500)
                val newInfo = fetchTorExitInfoInternal(forceRefresh = true)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(newInfo)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed during new identity refresh: ${e.message}")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(currentExitInfo)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Verification & Geolocation
    // ─────────────────────────────────────────────────────────────────────────

    fun refreshExitInfoAsync() {
        coroutineScope.launch {
            fetchTorExitInfoInternal(false)
        }
    }

    /**
     * Queries Tor exit node verification and geolocation through SOCKS5 proxy.
     */
    fun fetchTorExitInfoInternal(forceRefresh: Boolean = false): TorExitInfo? {
        if (!forceRefresh && currentExitInfo != null) {
            return currentExitInfo
        }

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SOCKS_HOST, SOCKS_PORT))

        // Step 1: Verify Tor and get Exit IP
        var exitIp: String? = null
        for (attempt in 1..4) {
            try {
                val url = URL("https://check.torproject.org/api/ip")
                val conn = url.openConnection(proxy) as HttpsURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "VortexOne/2.0")

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                if (response.contains("\"IsTor\":true")) {
                    val match = Regex("\"IP\":\"([^\"]+)\"").find(response)
                    exitIp = match?.groupValues?.get(1)
                    if (exitIp != null) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tor verification attempt $attempt/4 failed: ${e.message}")
                if (attempt < 4) {
                    try { Thread.sleep(1000) } catch (ignored: Exception) {}
                }
            }
        }

        if (exitIp == null) {
            return null
        }

        // Step 2: Query Geolocation through Tor SOCKS5 proxy
        var countryCode: String? = null
        var countryName: String? = null

        // Try free IP geolocation services through Tor SOCKS5
        try {
            val geoUrl = URL("https://ipwho.is/$exitIp")
            val geoConn = geoUrl.openConnection(proxy) as HttpsURLConnection
            geoConn.connectTimeout = 7000
            geoConn.readTimeout = 7000
            geoConn.requestMethod = "GET"
            geoConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            val geoResponse = geoConn.inputStream.bufferedReader().readText()
            geoConn.disconnect()

            val ccMatch = Regex("\"country_code\":\"([^\"]+)\"").find(geoResponse)
            val cnMatch = Regex("\"country\":\"([^\"]+)\"").find(geoResponse)
            countryCode = ccMatch?.groupValues?.get(1)
            countryName = cnMatch?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.d(TAG, "Primary geo lookup failed: ${e.message}, trying fallback")
        }

        if (countryCode == null) {
            try {
                val fbUrl = URL("http://ip-api.com/json/$exitIp")
                val fbConn = fbUrl.openConnection(proxy) as java.net.HttpURLConnection
                fbConn.connectTimeout = 7000
                fbConn.readTimeout = 7000
                fbConn.requestMethod = "GET"
                fbConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                val fbResponse = fbConn.inputStream.bufferedReader().readText()
                fbConn.disconnect()

                val ccMatch = Regex("\"countryCode\":\"([^\"]+)\"").find(fbResponse)
                val cnMatch = Regex("\"country\":\"([^\"]+)\"").find(fbResponse)
                countryCode = ccMatch?.groupValues?.get(1)
                countryName = cnMatch?.groupValues?.get(1)
            } catch (e2: Exception) {
                Log.d(TAG, "Secondary geo lookup failed: ${e2.message}")
            }
        }

        if (countryCode == null) {
            try {
                val fbUrl = URL("https://freeipapi.com/api/json/$exitIp")
                val fbConn = fbUrl.openConnection(proxy) as HttpsURLConnection
                fbConn.connectTimeout = 7000
                fbConn.readTimeout = 7000
                fbConn.requestMethod = "GET"
                fbConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                val fbResponse = fbConn.inputStream.bufferedReader().readText()
                fbConn.disconnect()

                val ccMatch = Regex("\"countryCode\":\"([^\"]+)\"").find(fbResponse)
                val cnMatch = Regex("\"countryName\":\"([^\"]+)\"").find(fbResponse)
                countryCode = ccMatch?.groupValues?.get(1)
                countryName = cnMatch?.groupValues?.get(1)
            } catch (ignored: Exception) {}
        }

        val flagEmoji = countryCodeToFlagEmoji(countryCode)
        val info = TorExitInfo(
            ip = exitIp,
            countryCode = countryCode,
            countryName = countryName ?: countryCode ?: "Tor Node",
            flagEmoji = flagEmoji
        )

        currentExitInfo = info
        _exitInfo.postValue(info)
        Log.d(TAG, "Tor Exit Verified: ${info.flagEmoji} ${info.countryName} (${info.ip})")
        return info
    }

    /**
     * Converts ISO 3166-1 alpha-2 country code (e.g. "US", "DE", "NL", "CH") to flag emoji.
     */
    fun countryCodeToFlagEmoji(countryCode: String?): String {
        if (countryCode.isNullOrBlank() || countryCode.length != 2) return "🧅"
        val upper = countryCode.uppercase()
        val c1 = upper[0]
        val c2 = upper[1]
        if (c1 !in 'A'..'Z' || c2 !in 'A'..'Z') return "🧅"
        val firstChar = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    @JvmStatic
    fun verifyTorConnection(): String? = fetchTorExitInfoSync()?.ip

    // ─────────────────────────────────────────────────────────────────────────
    // Status updates — called by TorService
    // ─────────────────────────────────────────────────────────────────────────

    fun updateStatus(status: TorStatus) {
        _status.postValue(status)
        Log.d(TAG, "Status -> $status")
        if (status == TorStatus.RUNNING && currentExitInfo == null) {
            refreshExitInfoAsync()
        }
    }
}
