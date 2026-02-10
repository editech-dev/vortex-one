package com.editech.services.firewall

/**
 * Connection log entry for virtualized apps
 * Records network activity when monitoring is enabled
 */
data class ConnectionLog(
    val id: Long = 0,
    val packageName: String,
    val destinationIp: String,
    val destinationPort: Int,
    val hostname: String? = null,     // Resolved via DNS hook
    val protocol: String = "TCP",
    val timestamp: Long = System.currentTimeMillis(),
    val wasBlocked: Boolean = false,
    val status: String = "UNKNOWN", // BLOCKED, ESTABLISHED, FAILED
    val failureReason: String? = null,
    val method: String? = null,     // GET, POST, etc.
    val path: String? = null,       // /api/v1/resource
    val bytesTransferred: Long = 0
) {
    /**
     * Human-readable destination string
     */
    fun getDisplayDestination(): String {
        return hostname?.let { "$it ($destinationIp):$destinationPort" }
            ?: "$destinationIp:$destinationPort"
    }
    
    /**
     * Check if this log matches a given hostname or IP
     */
    fun matchesDestination(query: String): Boolean {
        return destinationIp.contains(query, ignoreCase = true) ||
               hostname?.contains(query, ignoreCase = true) == true
    }

    fun toEntity(): com.editech.services.firewall.database.ConnectionLogEntity {
        return com.editech.services.firewall.database.ConnectionLogEntity(
            packageName,
            timestamp,
            destinationIp,
            destinationPort,
            protocol,
            wasBlocked,
            hostname,
            status,
            failureReason,
            method,
            path
        )
    }
}
