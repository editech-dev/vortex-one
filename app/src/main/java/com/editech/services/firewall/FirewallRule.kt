package com.editech.services.firewall

/**
 * Firewall rule for virtualized apps
 * Defines blocking/monitoring behavior per app
 */
data class FirewallRule(
    val id: Long = 0,
    val packageName: String,
    val ruleType: RuleType,
    val port: Int? = null,           // null = all ports
    val endpoint: String? = null,     // Specific URL or path
    val protocol: Protocol = Protocol.BOTH,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toEntity(): com.editech.services.firewall.database.FirewallRuleEntity {
        return com.editech.services.firewall.database.FirewallRuleEntity(
            packageName,
            ruleType.name,
            port ?: -1,
            protocol.name,
            enabled,
            createdAt,
            endpoint
        )
    }
}

enum class RuleType {
    BLOCK_ALL,            // Block all internet access
    BLOCK_PORT,           // Block specific port
    ALLOW_ONLY_PORT,      // Block all except this port
    BLOCK_ENDPOINT,       // Block specific URL endpoint
    BLOCK_LOCAL_NETWORK,  // Block all local/private IP access
    BLOCK_ADB_ACCESS      // Block ADB port access
}

enum class Protocol {
    TCP,
    UDP,
    BOTH
}

/**
 * State of firewall monitoring for an app
 */
enum class FirewallState {
    DISABLED,         // No monitoring (default)
    MONITORING,       // Only logging connections
    BLOCKING_ALL,     // Block all internet
    BLOCKING_PORTS    // Block specific ports
}

/**
 * Classification of detected network threats
 */
enum class ThreatType(val label: String, val icon: String) {
    ADB_ACCESS("ADB Access", "🔴"),
    LOCAL_NETWORK("Local Network Access", "🟡"),
    LOCALHOST_PROBE("Localhost Probe", "🟠");

    companion object {
        /** Parse from a THREAT: tag in failureReason */
        fun fromTag(tag: String?): ThreatType? {
            if (tag == null || !tag.startsWith("THREAT:")) return null
            return try {
                valueOf(tag.removePrefix("THREAT:"))
            } catch (e: Exception) {
                null
            }
        }

        fun toTag(type: ThreatType): String = "THREAT:${type.name}"
    }
}
