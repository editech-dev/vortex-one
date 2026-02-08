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
            createdAt
        )
    }
}

enum class RuleType {
    BLOCK_ALL,        // Block all internet access
    BLOCK_PORT,       // Block specific port
    ALLOW_ONLY_PORT   // Block all except this port
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
