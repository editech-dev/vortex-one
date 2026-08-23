package com.editech.services.net

import android.os.Build
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * CloudflareDnsResolver — High-performance, 100% leak-free DNS-over-HTTPS (DoH RFC 8484)
 * resolver using direct IP endpoints (Zero OS DNS recursion).
 *
 * Guarantees:
 *  - Direct IP connections (1.1.1.1, 1.0.0.1, 8.8.8.8, 8.8.4.4, 9.9.9.9) on port 443.
 *  - Custom SSLSocketFactory & HostnameVerifier so direct IP HTTPS requests succeed without DNS lookup.
 *  - Zero unencrypted UDP port 53 fallback.
 *  - High-speed LRU in-memory cache.
 */
object CloudflareDnsResolver {

    private const val TAG = "CloudflareDnsResolver"

    // Presupuesto total de la carrera: cubre UN intento lento (HTTP_TIMEOUT_MS)
    // más margen, no la suma de todos los servidores — al lanzarlos en paralelo,
    // que uno tarde ya no le come el tiempo a los demás.
    private const val RACE_TIMEOUT_MS = 4000L
    private const val HTTP_TIMEOUT_MS = 1500
    private const val CACHE_TTL_MS = 300_000L // 5 minutos: resolución correcta
    private const val NEGATIVE_CACHE_TTL_MS = 5_000L // fallo: no repetir la carrera de inmediato

    private data class DohServer(
        val ip: String,
        val hostHeader: String,
        val path: String
    )

    private val DOH_SERVERS = listOf(
        DohServer("1.1.1.1", "cloudflare-dns.com", "/dns-query"),
        DohServer("1.0.0.1", "cloudflare-dns.com", "/dns-query"),
        DohServer("8.8.8.8", "dns.google", "/dns-query"),
        DohServer("8.8.4.4", "dns.google", "/dns-query"),
        DohServer("9.9.9.9", "dns.quad9.net", "/dns-query")
    )

    /**
     * TLS contra el proveedor de DoH, validado de verdad.
     *
     * Nos conectamos a la IP literal (1.1.1.1, 8.8.8.8…) para no depender de una
     * resolución previa, pero eso hace que la verificación por defecto compare el
     * certificado contra la IP y falle. La respuesta anterior a ese problema era
     * aceptar cualquier certificado y cualquier hostname, lo que dejaba el canal
     * DoH abierto a un MITM en la ruta: quien lo interceptara podía devolver la IP
     * que quisiera para cualquier dominio. La respuesta correcta es fijar el SNI y
     * verificar contra el nombre real del proveedor, conservando la validación de
     * cadena contra las CAs del sistema.
     */
    private class SniSocketFactory(
        private val delegate: SSLSocketFactory,
        private val sniHost: String
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        private fun applySni(socket: Socket): Socket {
            if (socket is SSLSocket) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        val params = socket.sslParameters
                        params.serverNames = listOf(SNIHostName(sniHost))
                        socket.sslParameters = params
                    } catch (ignored: Throwable) {}
                } else {
                    // Android < 7 no expone SNIHostName; el SNI se fija con el
                    // setHostname() de OpenSSLSocketImpl.
                    try {
                        socket.javaClass.getMethod("setHostname", String::class.java)
                            .invoke(socket, sniHost)
                    } catch (ignored: Throwable) {}
                }
            }
            return socket
        }

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            applySni(delegate.createSocket(s, sniHost, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            applySni(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            applySni(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            applySni(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            applySni(delegate.createSocket(address, port, localAddress, localPort))
    }

    private val sslFactories = ConcurrentHashMap<String, SSLSocketFactory>()

    private fun sslFactoryFor(host: String): SSLSocketFactory =
        sslFactories.getOrPut(host) {
            SniSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), host)
        }

    /** Verifica el certificado contra el nombre del proveedor, no contra la IP. */
    private fun verifierFor(host: String) = HostnameVerifier { _, session ->
        HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
    }

    private data class CachedEntry(
        val addresses: Array<InetAddress>?,   // null = fallo reciente, cacheado en negativo
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

        // 1. Check in-memory cache (positivo o negativo, según expiresAt/addresses)
        val now = System.currentTimeMillis()
        val cached = cache[cleanHost]
        if (cached != null && cached.expiresAt > now) {
            return cached.addresses
        }

        // 2. Carrera en paralelo contra todos los servidores DoH: un solo hop
        // lento ya no consume el presupuesto de tiempo de los demás.
        val result = resolveRacing(cleanHost)
        val ttl = if (result != null && result.isNotEmpty()) CACHE_TTL_MS else NEGATIVE_CACHE_TTL_MS
        cache[cleanHost] = CachedEntry(result, now + ttl)
        if (result != null && result.isNotEmpty()) {
            logToFirewall(cleanHost, result)
        } else {
            Log.w(TAG, "DoH direct-IP resolution failed for $cleanHost (todos los servidores)")
        }
        return result
    }

    /**
     * Lanza los servidores DoH en paralelo y devuelve la primera respuesta
     * válida. Antes se probaban en serie dentro de un único timeout de 2.5 s
     * total: si el primero tardaba, ni se llegaba a intentar el resto. En
     * paralelo, que uno vaya lento no le resta tiempo a los otros cuatro.
     */
    private fun resolveRacing(hostname: String): Array<InetAddress>? {
        val queryBytes = buildDnsQueryPacket(hostname)
        val completion = ExecutorCompletionService<Array<InetAddress>?>(executor)
        val futures = DOH_SERVERS.map { server ->
            completion.submit(java.util.concurrent.Callable { queryServer(server, hostname, queryBytes) })
        }

        val deadline = System.currentTimeMillis() + RACE_TIMEOUT_MS
        var result: Array<InetAddress>? = null
        try {
            for (i in futures.indices) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val finished = try {
                    completion.poll(remaining, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: break
                val r = try { finished.get() } catch (e: Throwable) { null }
                if (r != null && r.isNotEmpty()) {
                    result = r
                    break
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }

        if (result != null) return result

        // Último recurso: la API JSON de Google, con su propio timeout corto.
        return resolveViaGoogleJsonDirect(hostname)
    }

    /** Una consulta DNS-over-HTTPS (RFC 8484) contra un único servidor DoH. */
    private fun queryServer(server: DohServer, hostname: String, queryBytes: ByteArray): Array<InetAddress>? {
        var conn: HttpsURLConnection? = null
        return try {
            val url = URL("https://${server.ip}${server.path}")
            conn = url.openConnection() as HttpsURLConnection
            conn.sslSocketFactory = sslFactoryFor(server.hostHeader)
            conn.hostnameVerifier = verifierFor(server.hostHeader)
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.setRequestProperty("Host", server.hostHeader)
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.setRequestProperty("User-Agent", "VortexOne-DoH/2.0")

            // Send wire-format DNS query
            conn.outputStream.use { it.write(queryBytes) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseBytes = conn.inputStream.use { it.readBytes() }
                val ips = parseDnsResponsePacket(responseBytes, hostname)
                if (ips != null && ips.isNotEmpty()) {
                    Log.d(TAG, "Direct DoH (${server.ip}) resolved $hostname -> ${ips.map { it.hostAddress }}")
                    ips
                } else null
            } else null
        } catch (e: Throwable) {
            Log.w(TAG, "DoH ${server.ip} (${server.hostHeader}) falló para $hostname: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    /**
     * Fallback: Google DoH JSON API using direct IP 8.8.8.8 with Host: dns.google
     */
    private fun resolveViaGoogleJsonDirect(hostname: String): Array<InetAddress>? {
        var conn: HttpsURLConnection? = null
        try {
            val url = URL("https://8.8.8.8/resolve?name=$hostname&type=A")
            conn = url.openConnection() as HttpsURLConnection
            conn.sslSocketFactory = sslFactoryFor("dns.google")
            conn.hostnameVerifier = verifierFor("dns.google")
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Host", "dns.google")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "VortexOne-DoH/2.0")

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().readText()
                val ips = mutableListOf<InetAddress>()
                val pattern = Regex("\"data\":\\s*\"([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)\"")
                pattern.findAll(jsonStr).forEach { match ->
                    val ipStr = match.groupValues[1]
                    try {
                        ips.add(InetAddress.getByName(ipStr))
                    } catch (ignored: Throwable) {}
                }
                if (ips.isNotEmpty()) {
                    Log.d(TAG, "Google DoH JSON (8.8.8.8) resolved $hostname -> ${ips.map { it.hostAddress }}")
                    return ips.toTypedArray()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Google DoH JSON failed for $hostname: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
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
     * Parses RFC 1035 wire-format DNS Response packet to extract IPv4 addresses
     */
    private fun parseDnsResponsePacket(data: ByteArray, originalHost: String): Array<InetAddress>? {
        if (data.size < 12) return null
        return try {
            val dis = DataInputStream(java.io.ByteArrayInputStream(data))
            val id = dis.readUnsignedShort()
            val flags = dis.readUnsignedShort()
            val qdCount = dis.readUnsignedShort()
            val anCount = dis.readUnsignedShort()
            val nsCount = dis.readUnsignedShort()
            val arCount = dis.readUnsignedShort()

            if (anCount == 0) return null

            // Skip questions
            for (i in 0 until qdCount) {
                skipDnsName(dis, data)
                dis.readUnsignedShort() // QTYPE
                dis.readUnsignedShort() // QCLASS
            }

            val addresses = mutableListOf<InetAddress>()
            // Parse answers
            for (i in 0 until anCount) {
                skipDnsName(dis, data)
                val type = dis.readUnsignedShort()
                val clazz = dis.readUnsignedShort()
                val ttl = dis.readInt()
                val rdLength = dis.readUnsignedShort()

                if (type == 1 && rdLength == 4) { // TYPE A (IPv4)
                    val ipBytes = ByteArray(4)
                    dis.readFully(ipBytes)
                    val addr = InetAddress.getByAddress(originalHost, ipBytes)
                    addresses.add(addr)
                } else {
                    dis.skipBytes(rdLength)
                }
            }

            if (addresses.isNotEmpty()) addresses.toTypedArray() else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun skipDnsName(dis: DataInputStream, rawData: ByteArray) {
        while (true) {
            val len = dis.readUnsignedByte()
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                // Compression pointer: 1 extra byte
                dis.readUnsignedByte()
                break
            } else {
                dis.skipBytes(len)
            }
        }
    }

    private fun isIpAddress(str: String): Boolean {
        if (str.isEmpty()) return true
        if (str.contains(":")) return true
        val parts = str.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } ?: false
        }
    }

    private fun logToFirewall(hostname: String, addresses: Array<InetAddress>) {
        try {
            val monitorClass = Class.forName("com.editech.services.firewall.NetworkConnectionMonitor")
            val method = monitorClass.getMethod(
                "logTorConnection",
                String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java
            )
            val ipStr = addresses.firstOrNull()?.hostAddress ?: "0.0.0.0"
            method.invoke(null, ipStr, 443, false, "RESOLVED", hostname, "DoH/HTTPS")
        } catch (ignored: Throwable) {}
    }
}
