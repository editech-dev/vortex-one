package com.editech.services.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.editech.services.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TorService — runs the Tor daemon as a foreground service.
 *
 * Uses the tor-android library (info.guardianproject:tor-android) which
 * bundles libtor.so natively. The daemon is controlled via OrbotService
 * intents and exposes a SOCKS5 proxy on 127.0.0.1:9150.
 */
class TorService : Service() {

    companion object {
        private const val TAG = "TorService"
        const val ACTION_START        = "com.editech.services.tor.START"
        const val ACTION_STOP         = "com.editech.services.tor.STOP"
        const val ACTION_NEW_IDENTITY = "com.editech.services.tor.NEW_IDENTITY"

        private const val NOTIFICATION_ID = 8472
        private const val CHANNEL_ID = "tor_service_channel"

        // Tor bootstrap check: poll every 2 s, up to 60 s
        private const val POLL_INTERVAL_MS = 2_000L
        private const val BOOTSTRAP_TIMEOUT_MS = 60_000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var bootstrapJob: Job? = null
    private var torProcess: Process? = null
    private var controlSocket: java.net.Socket? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Conectando a Tor…"))
        when (intent?.action) {
            ACTION_START        -> startTor()
            ACTION_STOP         -> stopTor()
            ACTION_NEW_IDENTITY -> sendNewIdentity()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTorProcess()
        TorManager.updateStatus(TorManager.TorStatus.STOPPED)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tor daemon management
    // ─────────────────────────────────────────────────────────────────────────

    private fun startTor() {
        if (TorManager.isProxyReachable()) {
            Log.d(TAG, "Tor SOCKS5 proxy is already reachable")
            TorManager.updateStatus(TorManager.TorStatus.RUNNING)
            updateNotification("Tor activo — tu tráfico está protegido")
            return
        }

        if (torProcess?.isAlive == true) {
            Log.d(TAG, "Tor process is already running, waiting for bootstrap")
            TorManager.updateStatus(TorManager.TorStatus.STARTING)
            updateNotification("Conectando a Tor…")
            if (bootstrapJob?.isActive != true) {
                serviceScope.launch { waitForBootstrap() }
            }
            return
        }

        TorManager.updateStatus(TorManager.TorStatus.STARTING)
        updateNotification("Conectando a Tor…")

        serviceScope.launch {
            try {
                // Prepare data directory for Tor
                val torDataDir = getDir("tor_data", MODE_PRIVATE)
                val torCacheDir = getDir("tor_cache", MODE_PRIVATE)

                // Write torrc config
                val torrc = java.io.File(torDataDir, "torrc")
                torrc.writeText(buildTorrc(torDataDir.absolutePath))

                // Locate the tor binary from the tor-android lib
                val torBinary = findTorBinary() ?: run {
                    Log.e(TAG, "Tor binary not found in native libs")
                    TorManager.updateStatus(TorManager.TorStatus.ERROR)
                    updateNotification("Error: Tor no disponible")
                    return@launch
                }
                torBinary.setExecutable(true, false)

                // Launch Tor process
                val pb = ProcessBuilder(
                    torBinary.absolutePath,
                    "-f", torrc.absolutePath,
                    "--DataDirectory", torDataDir.absolutePath,
                    "--CacheDirectory", torCacheDir.absolutePath
                )
                pb.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
                pb.redirectErrorStream(true)
                torProcess = pb.start()
                val pidStr = try {
                    val pidMethod = torProcess?.javaClass?.getMethod("pid")
                    pidMethod?.invoke(torProcess)?.toString() ?: "N/A"
                } catch (e: Throwable) {
                    "N/A"
                }
                Log.d(TAG, "Tor process started, PID=$pidStr")

                // Consume stdout (prevents deadlock)
                launch { consumeProcessOutput(torProcess!!) }

                // Poll until SOCKS5 proxy is reachable
                waitForBootstrap()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Tor", e)
                TorManager.updateStatus(TorManager.TorStatus.ERROR)
                updateNotification("Error iniciando Tor")
            }
        }
    }

    private fun stopTor() {
        stopTorProcess()
        TorManager.updateStatus(TorManager.TorStatus.STOPPED)
        updateNotification("Tor detenido")
        stopSelf()
    }

    private fun stopTorProcess() {
        bootstrapJob?.cancel()
        controlSocket?.runCatching { close() }
        controlSocket = null
        torProcess?.runCatching { destroy() }
        torProcess = null
    }

    private fun sendNewIdentity() {
        serviceScope.launch {
            try {
                // Connect to Tor control port (9151) and send NEWNYM
                val ctrl = java.net.Socket("127.0.0.1", 9151)
                val writer = java.io.PrintWriter(ctrl.getOutputStream(), true)
                val reader = java.io.BufferedReader(java.io.InputStreamReader(ctrl.getInputStream()))
                writer.println("AUTHENTICATE \"\"")
                reader.readLine() // 250 OK
                writer.println("SIGNAL NEWNYM")
                reader.readLine() // 250 OK
                ctrl.close()
                Log.d(TAG, "New identity signaled")
                updateNotification("Cambiando circuito Tor…")

                delay(1200)
                val info = TorManager.fetchTorExitInfoInternal(forceRefresh = true)
                if (info != null) {
                    updateNotification("${info.flagEmoji ?: "🧅"} Tor activo — ${info.countryName} (${info.ip})")
                } else {
                    updateNotification("Tor activo — tu tráfico está protegido")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to signal NEWNYM", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bootstrap detection
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun waitForBootstrap() {
        bootstrapJob = serviceScope.launch {
            val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (TorManager.isProxyReachable()) {
                    Log.d(TAG, "Tor SOCKS5 proxy ready at 127.0.0.1:${TorManager.SOCKS_PORT}")
                    TorManager.updateStatus(TorManager.TorStatus.RUNNING)
                    updateNotification("Tor activo — tu tráfico está protegido")
                    
                    // Asynchronously verify exit node IP and country
                    launch(Dispatchers.IO) {
                        val info = TorManager.fetchTorExitInfoInternal(forceRefresh = true)
                        Log.d(TAG, "Tor exit node verification: IP = ${info?.ip ?: "Failed to verify"}")
                        if (info != null) {
                            updateNotification("${info.flagEmoji ?: "🧅"} Tor activo — ${info.countryName} (${info.ip})")
                        }
                    }
                    return@launch
                }
                delay(POLL_INTERVAL_MS)
                if (torProcess?.isAlive != true) {
                    Log.e(TAG, "Tor process died immediately after start")
                    TorManager.updateStatus(TorManager.TorStatus.ERROR)
                    updateNotification("Error: Tor process died")
                    return@launch
                }
            }
            // Timeout
            Log.e(TAG, "Tor bootstrap timed out after ${BOOTSTRAP_TIMEOUT_MS / 1000}s")
            TorManager.updateStatus(TorManager.TorStatus.ERROR)
            updateNotification("Tor: tiempo de espera agotado")
        }
        bootstrapJob?.join()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a minimal torrc for the embedded daemon.
     * Uses port 9150 for SOCKS5 and 9151 for the control port.
     */
    private fun buildTorrc(dataDir: String): String = """
        SocksPort ${TorManager.SOCKS_PORT}
        DNSPort ${TorManager.TOR_DNS_PORT}
        AutomapHostsOnResolve 1
        VirtualAddrNetworkIPv4 127.192.0.0/10
        ControlPort 9151
        CookieAuthentication 0
        HashedControlPassword ""
        DataDirectory $dataDir
        Log notice stdout
        ClientOnly 1
        SafeSocks 1
        TestSocks 0
    """.trimIndent()

    /**
     * Locates the tor binary bundled by the tor-android library in the app's
     * native library directory. The library unpacks libtor.so; we need the
     * executable binary, which may have a different name depending on the lib version.
     */
    private fun findTorBinary(): java.io.File? {
        val nativeDir = applicationInfo.nativeLibraryDir
        val source = java.io.File(nativeDir, "libtor.so")
        if (source.exists()) {
            Log.d(TAG, "Using Tor binary directly from native library dir: ${source.absolutePath}")
            return source
        }
        Log.e(TAG, "libtor.so NOT found in $nativeDir")
        return null
    }

    private fun consumeProcessOutput(process: Process) {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                Log.d("TorDaemon", line)
            }
        } catch (e: Exception) {
            // Process ended
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tor Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Estado de la red Tor"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vortex Tor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
