package com.editech.services.firewall

import android.util.Log

import top.niunaijun.blackbox.app.BActivityThread
import top.niunaijun.blackbox.utils.Slog
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException

/**
 * Network Connection Monitor for Firewall
 * 
 * This object provides static methods that can be called from hooks
 * to monitor and control network connections for virtualized apps.
 * 
 * Called by:
 * - IDnsResolverProxy for DNS resolution monitoring
 * - VPN PacketProcessor for actual connection monitoring
 */
object NetworkConnectionMonitor {
    private const val TAG = "NetworkConnectionMonitor"

    /**
     * Called when a DNS resolution is performed
     *
     * @param hostname The hostname being resolved
     * @param ips The resolved IP addresses
     */
    @JvmStatic
    fun onDnsResolution(hostname: String, ips: Array<String>) {
        val packageName = getCurrentPackageName() ?: return

        val manager = FirewallManager.getInstance()

        // Only log if monitoring is enabled for this app
        if (!manager.isEnabled(packageName)) return

        Slog.d(TAG, "DNS resolution: $hostname -> ${ips.joinToString(", ")}")

        // Register DNS mapping for each IP
        for (ip in ips) {
            manager.registerDnsResolution(packageName, hostname, ip)
        }
    }

    /**
     * Called when a socket connection is being established
     *
     * @param address The destination address
     * @param port The destination port
     * @return true if the connection should be blocked
     */
    /**
     * Check if a socket connection should be blocked
     * Does NOT log the connection
     */
    @JvmStatic
    fun shouldBlockSocket(address: InetAddress, port: Int): Boolean {
        val packageName = getCurrentPackageName() ?: return false
        val manager = FirewallManager.getInstance()
        
        if (!manager.isEnabled(packageName)) return false
        
        return manager.shouldBlock(packageName, address, port)
    }

    /**
     * Log a socket connection result
     */
    @JvmStatic
    fun logSocketConnection(address: InetAddress, port: Int, blocked: Boolean, status: String, failureReason: String?) {
        val packageName = getCurrentPackageName() ?: return
        val manager = FirewallManager.getInstance()
        
        if (!manager.isEnabled(packageName)) return

        val ip = address.hostAddress ?: return
        
        manager.logConnection(packageName, ip, port, "TCP", blocked, status, failureReason)
        
        if (blocked) {
            Slog.d(TAG, "BLOCKED: $packageName -> $ip:$port ${failureReason?.let { "($it)" } ?: ""}")
            Slog.d(TAG, "ALLOWED ($status): $packageName -> $ip:$port ${failureReason?.let { "($it)" } ?: ""}")
        }
    }

    /**
     * Log a URL connection (from OkHttp or URL hook)
     */
    @JvmStatic
    fun logUrlConnection(url: String, method: String, status: String, failureReason: String?) {
        val packageName = getCurrentPackageName() ?: return
        val manager = FirewallManager.getInstance()
        
        if (!manager.isEnabled(packageName)) return
        
        try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return
            val port = if (uri.port != -1) uri.port else (if (uri.scheme == "https") 443 else 80)
            val path = uri.path ?: "/"
            // Avoid DNS on main thread/hook if possible, but we need IP for consistency.
            // For now, use 0.0.0.0 or look up in cache if logging requires IP.
            // Better: Resolve async or assume hostname is enough if manager supports it.
            // FirewallManager currently expects IP.
            val ip = "0.0.0.0" // Placeholder, we rely on hostname
            
            manager.logConnection(
                packageName = packageName, 
                ip = ip, 
                port = port, 
                protocol = "TCP", 
                blocked = false, 
                status = status, 
                failureReason = failureReason,
                method = method,
                path = path,
                overrideHostname = host
            )
            
            Slog.d(TAG, "URL: $method $url [$status]")
        } catch (e: Exception) {
            Slog.e(TAG, "Failed to log URL: $url", e)
        }
    }

    /**
     * Legacy method - kept for potential other hooks, but OsStub will use new methods
     */
    @JvmStatic
    fun onSocketConnect(address: InetAddress, port: Int): Boolean {
        val blocked = shouldBlockSocket(address, port)
        // Legacy: Assume if not blocked it is just allowed (unknown status)
        val status = if (blocked) "BLOCKED" else "UNKNOWN"
        logSocketConnection(address, port, blocked, status, null)
        return blocked
    }

    /**
     * Called for UDP connections
     */
    @JvmStatic
    fun onDatagramConnect(address: InetAddress, port: Int): Boolean {
        val packageName = getCurrentPackageName() ?: return false

        val manager = FirewallManager.getInstance()

        if (!manager.isEnabled(packageName)) return false

        val ip = address.hostAddress ?: return false
        val blocked = manager.shouldBlock(packageName, address, port)

        manager.logConnection(packageName, ip, port, "UDP", blocked)

        if (blocked) {
            Slog.d(TAG, "BLOCKED UDP: $packageName -> $ip:$port")
        }

        return blocked
    }

    /**
     * Called when reading IP packet in VPN mode
     */
    @JvmStatic
    fun shouldBlockPacket(packageName: String, destIp: String, destPort: Int, protocol: String): Boolean {
        val manager = FirewallManager.getInstance()

        if (!manager.isEnabled(packageName)) return false

        return try {
            val address = InetAddress.getByName(destIp)
            val blocked = manager.shouldBlock(packageName, address, destPort)

            // Log the connection
            manager.logConnection(packageName, destIp, destPort, protocol, blocked)

            blocked
        } catch (e: UnknownHostException) {
            Slog.e(TAG, "Failed to parse IP: $destIp", e)
            false
        }
    }

    /**
     * Get current virtualized app package name
     */
    private fun getCurrentPackageName(): String? {
        return try {
            BActivityThread.getAppPackageName()
        } catch (e: Exception) {
            Slog.e(TAG, "Failed to get app package name", e)
            null
        }
    }

    /**
     * Throw exception if connection should be blocked (for use in hooks)
     */
    @JvmStatic
    @Throws(SocketException::class)
    fun checkAndThrowIfBlocked(address: InetAddress, port: Int) {
        if (onSocketConnect(address, port)) {
            throw SocketException("Connection blocked by firewall")
        }
    }
}
