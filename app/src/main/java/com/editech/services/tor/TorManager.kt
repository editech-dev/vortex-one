package com.editech.services.tor

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentHashMap

/**
 * TorManager — Singleton that tracks per-app Tor routing state.
 *
 * Responsibilities:
 *  - Persist which apps have Tor routing enabled (SharedPreferences)
 *  - Start/stop TorService based on demand
 *  - Expose fast-path @JvmStatic methods for OsStub reflection calls
 *  - Provide LiveData status for UI (TorFragment)
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

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var appContext: Context

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("tor_per_app", Context.MODE_PRIVATE)
        // Restore persisted state
        prefs.all.forEach { (k, v) ->
            if (v is Boolean) torEnabledApps[k] = v
        }
        Log.d(TAG, "Initialized. Apps with Tor: ${torEnabledApps.filter { it.value }.keys}")

        // Auto-start or sync status if running in main process and any app has Tor enabled
        val processName = getCurrentProcessName(appContext)
        if (processName == context.packageName) {
            val anyEnabled = torEnabledApps.any { it.value }
            if (anyEnabled) {
                if (isProxyReachable()) {
                    updateStatus(TorStatus.RUNNING)
                } else {
                    startService()
                }
            }
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
        return newStatus
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-app state
    // ─────────────────────────────────────────────────────────────────────────

    fun isTorEnabled(packageName: String): Boolean =
        torEnabledApps[packageName] == true

    fun setTorEnabled(packageName: String, enabled: Boolean) {
        torEnabledApps[packageName] = enabled
        prefs.edit().putBoolean(packageName, enabled).apply()
        Log.d(TAG, "Tor ${if (enabled) "enabled" else "disabled"} for $packageName")

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
    // Fast-path static methods — called via reflection from OsStub (engine)
    // ─────────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun isTorEnabledForPackage(packageName: String): Boolean =
        torEnabledApps[packageName] == true

    /**
     * Fast check: can we reach the Tor SOCKS5 proxy at 127.0.0.1:9150?
     * Called from OsStub before each Tor-routed connection and from UI status checks.
     * Uses a short timeout (300 ms) to minimize hook latency.
     * Safe to call from both main thread and background threads.
     */
    @JvmStatic
    fun isProxyReachable(): Boolean {
        return if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit(java.util.concurrent.Callable { performProxyPing() })
                val result = future.get(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                executor.shutdown()
                result
            } catch (e: Exception) {
                executor.shutdownNow()
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
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun startService() {
        if (!::appContext.isInitialized) {
            Log.e(TAG, "TorManager not initialized — cannot start service")
            return
        }
        updateStatus(TorStatus.STARTING)
        val intent = Intent(appContext, TorService::class.java).apply {
            action = TorService.ACTION_START
        }
        appContext.startForegroundService(intent)
        Log.d(TAG, "TorService start requested")
    }

    fun stopService() {
        if (!::appContext.isInitialized) return
        val intent = Intent(appContext, TorService::class.java).apply {
            action = TorService.ACTION_STOP
        }
        appContext.startService(intent)
        Log.d(TAG, "TorService stop requested")
    }

    fun requestNewIdentity() {
        if (!::appContext.isInitialized) return
        val intent = Intent(appContext, TorService::class.java).apply {
            action = TorService.ACTION_NEW_IDENTITY
        }
        appContext.startService(intent)
        Log.d(TAG, "New identity requested")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status updates — called by TorService
    // ─────────────────────────────────────────────────────────────────────────

    fun updateStatus(status: TorStatus) {
        _status.postValue(status)
        Log.d(TAG, "Status -> $status")
    }
}
