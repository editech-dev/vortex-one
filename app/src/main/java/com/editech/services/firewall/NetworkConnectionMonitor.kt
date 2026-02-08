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
    fun logSocketConnection(address: InetAddress, port: Int, blocked: Boolean, status: String) {
        val packageName = getCurrentPackageName() ?: return
        val manager = FirewallManager.getInstance()
        
        if (!manager.isEnabled(packageName)) return

        val ip = address.hostAddress ?: return
        
        manager.logConnection(packageName, ip, port, "TCP", blocked, status)
        
        if (blocked) {
            Slog.d(TAG, "BLOCKED: $packageName -> $ip:$port")
        } else {
            Slog.d(TAG, "ALLOWED ($status): $packageName -> $ip:$port")
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
        logSocketConnection(address, port, blocked, status)
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
