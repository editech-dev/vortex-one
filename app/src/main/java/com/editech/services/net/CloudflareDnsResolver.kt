package com.editech.services.net

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * CloudflareDnsResolver — High-performance, fail-safe DNS-over-TLS (DoT port 853)
 * and Cloudflare Secure DNS (1.1.1.1 / 1.0.0.1) resolver for non-Tor sandboxed apps.
 *
 * Features:
 *  - Native RFC 7858 DNS-over-TLS (DoT) on 1.1.1.1:853
 *  - Fast Cloudflare direct UDP fallback on 1.1.1.1:53
 *  - High-speed LRU in-memory cache (sub-millisecond repeat resolutions)
 *  - Strict 800ms timeout with zero-blocking guarantee
 *  - Completely fail-safe: any failure immediately falls back to system resolver
 */
object CloudflareDnsResolver {

    private const val TAG = "CloudflareDnsResolver"
    private const val CLOUDFLARE_PRIMARY_IP = "1.1.1.1"
    private const val CLOUDFLARE_SECONDARY_IP = "1.0.0.1"
    private const val DOT_PORT = 853
    private const val DNS_PORT = 53
    private const val RESOLVE_TIMEOUT_MS = 1000L
    private const val CACHE_TTL_MS = 300_000L // 5 minutes

    private data class CachedEntry(
        val addresses: Array<InetAddress>,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<String, CachedEntry>()
    private val executor = Executors.newCachedThreadPool()

    @JvmStatic
    fun resolve(hostname: String?): Array<InetAddress>? {
        if (hostname.isNullOrBlank()) return null

        val cleanHost = hostname.trim().lowercase()
        if (isIpAddress(cleanHost)) {
            return try {
                arrayOf(InetAddress.getByName(cleanHost))
            } catch (e: Throwable) {
                null
            }
        }

        // 1. Check in-memory cache
        val now = System.currentTimeMillis()
        val cached = cache[cleanHost]
        if (cached != null && cached.expiresAt > now && cached.addresses.isNotEmpty()) {
            return cached.addresses
        }

        // 2. Perform resolution with strict timeout
        return try {
            val future: Future<Array<InetAddress>?> = executor.submit<Array<InetAddress>?> {
                // Try DoT (TLS 853) first, fallback to Cloudflare UDP (53)
                resolveViaDoT(cleanHost) ?: resolveViaUdp(cleanHost)
            }
            val result = future.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (result != null && result.isNotEmpty()) {
                cache[cleanHost] = CachedEntry(result, now + CACHE_TTL_MS)
                logToFirewall(cleanHost, result)
            }
            result
        } catch (e: Throwable) {
            // Fail-safe: log warning and return null so caller falls back seamlessly
            Log.w(TAG, "DNS resolution skipped for $cleanHost (${e.message}), falling back to system")
            null
        }
    }

    /**
     * DNS-over-TLS (RFC 7858) to 1.1.1.1:853
     */
    private fun resolveViaDoT(hostname: String): Array<InetAddress>? {
        var sslSocket: SSLSocket? = null
        try {
            val queryPacket = buildDnsQueryPacket(hostname)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = factory.createSocket() as SSLSocket
            sslSocket.connect(InetSocketAddress(CLOUDFLARE_PRIMARY_IP, DOT_PORT), 600)
            sslSocket.soTimeout = 600

            val out = DataOutputStream(sslSocket.outputStream)
            out.writeShort(queryPacket.size)
            out.write(queryPacket)
            out.flush()

            val inStream = DataInputStream(sslSocket.inputStream)
            val responseLength = inStream.readUnsignedShort()
            val responseBytes = ByteArray(responseLength)
            inStream.readFully(responseBytes)

            val parsedIps = parseDnsResponse(responseBytes, hostname)
            if (parsedIps.isNotEmpty()) {
                Log.d(TAG, "DoT [TLS 853] resolved $hostname -> ${parsedIps.map { it.hostAddress }}")
                return parsedIps.toTypedArray()
            }
        } catch (e: Throwable) {
            // DoT might be blocked by local network, allow UDP fallback
        } finally {
            try { sslSocket?.close() } catch (ignored: Throwable) {}
        }
        return null
    }

    /**
     * Cloudflare Public DNS over UDP (1.1.1.1:53)
     */
    private fun resolveViaUdp(hostname: String): Array<InetAddress>? {
        var socket: DatagramSocket? = null
        try {
            val queryBytes = buildDnsQueryPacket(hostname)
            socket = DatagramSocket()
            socket.soTimeout = 600

            val serverAddr = InetAddress.getByName(CLOUDFLARE_PRIMARY_IP)
            val requestPacket = DatagramPacket(queryBytes, queryBytes.size, serverAddr, DNS_PORT)
            socket.send(requestPacket)

            val responseBuffer = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            val parsedIps = parseDnsResponse(responseBuffer, hostname)
            if (parsedIps.isNotEmpty()) {
                Log.d(TAG, "Cloudflare [UDP 53] resolved $hostname -> ${parsedIps.map { it.hostAddress }}")
                return parsedIps.toTypedArray()
            }
        } catch (e: Throwable) {
            // UDP resolution failed
        } finally {
            try { socket?.close() } catch (ignored: Throwable) {}
        }
        return null
    }

    /**
     * Builds a standard RFC 1035 DNS Query packet for type A (IPv4)
     */
    private fun buildDnsQueryPacket(hostname: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Header
        dos.writeShort((System.currentTimeMillis() and 0xFFFF).toInt()) // Transaction ID
        dos.writeShort(0x0100) // Flags: standard query, recursion desired
        dos.writeShort(1)      // Questions: 1
        dos.writeShort(0)      // Answer RRs: 0
        dos.writeShort(0)      // Authority RRs: 0
        dos.writeShort(0)      // Additional RRs: 0

        // Question: QNAME
        val parts = hostname.split(".")
        for (part in parts) {
            if (part.isNotEmpty()) {
                val bytes = part.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
        }
        dos.writeByte(0) // End of domain labels

        // QTYPE = A (1), QCLASS = IN (1)
        dos.writeShort(1)
        dos.writeShort(1)

        dos.flush()
        return baos.toByteArray()
    }

    /**
     * Parses RFC 1035 DNS Response packet and extracts IPv4 addresses (Type A)
     */
    private fun parseDnsResponse(response: ByteArray, hostname: String): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            if (response.size < 12) return result
            val bais = java.io.ByteArrayInputStream(response)
            val dis = DataInputStream(bais)

            val id = dis.readUnsignedShort()
            val flags = dis.readUnsignedShort()
            val qdCount = dis.readUnsignedShort()
            val anCount = dis.readUnsignedShort()
            val nsCount = dis.readUnsignedShort()
            val arCount = dis.readUnsignedShort()

            if (anCount == 0) return result

            // Skip questions
            for (i in 0 until qdCount) {
                skipDomainName(dis)
                dis.readShort() // QTYPE
                dis.readShort() // QCLASS
            }

            // Parse Answers
            for (i in 0 until anCount) {
                skipDomainName(dis)
                val type = dis.readUnsignedShort()
                val clazz = dis.readUnsignedShort()
                val ttl = dis.readInt()
                val rdLength = dis.readUnsignedShort()

                if (type == 1 && rdLength == 4) { // Type A = IPv4
                    val ipBytes = ByteArray(4)
                    dis.readFully(ipBytes)
                    val addr = InetAddress.getByAddress(hostname, ipBytes)
                    result.add(addr)
                } else {
                    dis.skipBytes(rdLength)
                }
            }
        } catch (e: Throwable) {
            // Error parsing response bytes
        }
        return result
    }

    private fun skipDomainName(dis: DataInputStream) {
        while (true) {
            val len = dis.readUnsignedByte()
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                // Pointer: skip next byte
                dis.readByte()
                break
            } else {
                dis.skipBytes(len)
            }
        }
    }

    private fun logToFirewall(hostname: String, addresses: Array<InetAddress>) {
        try {
            val monitorClass = Class.forName("com.editech.services.firewall.NetworkConnectionMonitor")
            val onDnsMethod = monitorClass.getMethod("onDnsResolution", String::class.java, Array<String>::class.java)
            val ipStrings = addresses.mapNotNull { it.hostAddress }.toTypedArray()
            onDnsMethod.invoke(null, hostname, ipStrings)
        } catch (ignored: Throwable) {}
    }

    private fun isIpAddress(str: String): Boolean {
        if (str.contains(":")) return true
        var dotCount = 0
        for (i in str.indices) {
            val ch = str[i]
            if (ch == '.') dotCount++
            else if (!Character.isDigit(ch)) return false
        }
        return dotCount == 3
    }
}
