package com.editech.services.firewall

import android.content.Context
import android.util.Log // Added Log import
import com.editech.services.firewall.database.FirewallDatabase
import com.editech.services.firewall.database.FirewallRuleEntity
// Removed ConnectionLogEntityAddress import
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Central Firewall Manager for virtualized apps
 * Manages firewall state, rules, and connection logging per app
 * 
 * On-demand activation: Firewall only active when user enables it for specific app
 */
class FirewallManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "FirewallManager"
        
        @Volatile
        private var instance: FirewallManager? = null
        
        fun getInstance(context: Context? = null): FirewallManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val ctx = context ?: try {
                         BlackBoxCore.getContext() 
                    } catch (e: Exception) {
                        null
                    }
                    if (ctx == null) throw IllegalStateException("Context required for FirewallManager")
                    FirewallManager(ctx.applicationContext).also { instance = it }
                }
            }
        }
    }
    
    // Executor for database operations
    private val dbExecutor = Executors.newSingleThreadExecutor()
    
    // In-memory cache of firewall state per app (packageName -> FirewallState)
    private val appStateCache = ConcurrentHashMap<String, FirewallState>()
    
    // In-memory cache of rules per app (packageName -> List<FirewallRule>)
    private val rulesCache = ConcurrentHashMap<String, List<FirewallRule>>()
    
    // DNS resolution cache (IP -> hostname)
    private val dnsCache = ConcurrentHashMap<String, String>()
    
    // Database (lazy initialization)
    private val database: FirewallDatabase by lazy {
        FirewallDatabase.getInstance(context)
    }
    
    init {
        // Load persisted state on init
        loadPersistedState()
    }
    
    // ====================
    // FIREWALL STATE
    // ====================
    
    /**
     * Check if monitoring/blocking is enabled for an app
     * This is the fast-path check called from hooks
     */
    fun isEnabled(packageName: String): Boolean {
        return appStateCache[packageName] != FirewallState.DISABLED &&
               appStateCache[packageName] != null
    }
    
    /**
     * Get current firewall state for an app
     */
    fun getState(packageName: String): FirewallState {
        return appStateCache[packageName] ?: FirewallState.DISABLED
    }
    
    /**
     * Set firewall state for an app
     */
    fun setState(packageName: String, state: FirewallState) {
        Log.d(TAG, "Setting firewall state for $packageName: $state")
        appStateCache[packageName] = state
        
        // Persist state change
        dbExecutor.execute {
            try {
                database.ruleDao().setAppState(packageName, state.name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist state: ${e.message}")
            }
        }
    }
    
    /**
     * Enable monitoring for an app (logs connections without blocking)
     */
    fun enableMonitoring(packageName: String) {
        setState(packageName, FirewallState.MONITORING)
    }
    
    /**
     * Block all internet access for an app
     */
    fun blockAllInternet(packageName: String) {
        setState(packageName, FirewallState.BLOCKING_ALL)
    }
    
    /**
     * Disable firewall for an app
     */
    fun disable(packageName: String) {
        setState(packageName, FirewallState.DISABLED)
        rulesCache.remove(packageName)
    }
    
    /**
     * Get all apps with firewall enabled
     */
    fun getEnabledApps(): List<String> {
        return appStateCache.filter { it.value != FirewallState.DISABLED }.keys.toList()
    }
    
    // ====================
    // CONNECTION BLOCKING
    // ====================
    
    /**
     * Check if a connection should be blocked
     * Called from SocketImplProxy on every connection attempt
     * 
     * @return true if connection should be blocked
     */
    fun shouldBlock(packageName: String, address: InetAddress, port: Int): Boolean {
        val state = appStateCache[packageName] ?: return false
        
        return when (state) {
            FirewallState.DISABLED -> false
            FirewallState.MONITORING -> false
            FirewallState.BLOCKING_ALL -> true
            FirewallState.BLOCKING_PORTS -> checkPortRules(packageName, port)
        }
    }
    
    /**
     * Check if a specific port should be blocked based on rules
     */
    private fun checkPortRules(packageName: String, port: Int): Boolean {
        val rules = rulesCache[packageName] ?: loadRulesForPackage(packageName)
        
        for (rule in rules) {
            if (!rule.enabled) continue
            
            when (rule.ruleType) {
                RuleType.BLOCK_PORT -> {
                    if (rule.port == port) return true
                }
                RuleType.ALLOW_ONLY_PORT -> {
                    if (rule.port != port) return true
                }
                RuleType.BLOCK_ALL -> return true
            }
        }
        return false
    }
    
    // ====================
    // PORT RULES
    // ====================
    
    /**
     * Add a port blocking rule
     */
    fun addBlockPortRule(packageName: String, port: Int, protocol: Protocol = Protocol.BOTH) {
        val rule = FirewallRule(
            packageName = packageName,
            ruleType = RuleType.BLOCK_PORT,
            port = port,
            protocol = protocol
        )
        addRule(rule)
        
        // Ensure state is BLOCKING_PORTS
        if (getState(packageName) != FirewallState.BLOCKING_ALL) {
            setState(packageName, FirewallState.BLOCKING_PORTS)
        }
    }
    
    /**
     * Add any firewall rule
     */
    fun addRule(rule: FirewallRule) {
        dbExecutor.execute {
            try {
                database.ruleDao().insert(rule.toEntity())
                refreshRulesCache(rule.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add rule: ${e.message}")
            }
        }
    }
    
    /**
     * Remove a rule by ID
     */
    fun removeRule(ruleId: Long, packageName: String) {
        dbExecutor.execute {
            try {
                database.ruleDao().deleteById(ruleId)
                refreshRulesCache(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove rule: ${e.message}")
            }
        }
    }
    
    /**
     * Get rules for an app
     */
    fun getRulesForPackage(packageName: String): List<FirewallRule> {
        return rulesCache[packageName] ?: loadRulesForPackage(packageName)
    }
    
    private fun loadRulesForPackage(packageName: String): List<FirewallRule> {
        return try {
            val entities = database.ruleDao().getRulesForPackage(packageName)
            val rules = entities.map { it.toModel() }
            rulesCache[packageName] = rules
            rules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rules: ${e.message}")
            emptyList()
        }
    }
    
    private fun refreshRulesCache(packageName: String) {
        rulesCache.remove(packageName)
        loadRulesForPackage(packageName)
    }
    
    // ====================
    // CONNECTION LOGGING
    // ====================
    
    /**
     * Log a connection attempt
     */
    fun logConnection(
        packageName: String,
        ip: String,
        port: Int,
        protocol: String = "TCP",
        blocked: Boolean = false,
        status: String = "UNKNOWN",
        failureReason: String? = null
    ) {
        val hostname = dnsCache[ip]
        
        val log = ConnectionLog(
            packageName = packageName,
            destinationIp = ip,
            destinationPort = port,
            hostname = hostname,
            protocol = protocol,
            wasBlocked = blocked,
            status = status,
            failureReason = failureReason
        )
        
        dbExecutor.execute {
            try {
                database.logDao().insert(log.toEntity())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log connection: ${e.message}")
            }
        }
        
        Log.d(TAG, "Connection: $packageName -> ${hostname ?: ip}:$port [$status] ${failureReason?.let { "($it)" } ?: ""}")
    }
    
    /**
     * Register DNS resolution for IP to hostname mapping
     */
    fun registerDnsResolution(packageName: String, hostname: String, ip: String) {
        dnsCache[ip] = hostname
        Log.d(TAG, "DNS: $hostname -> $ip (from $packageName)")
    }
    
    /**
     * Get recent connection logs
     */
    fun getRecentLogs(packageName: String? = null, limit: Int = 100): List<ConnectionLog> {
        return try {
            val entities = if (packageName != null) {
                database.logDao().getLogsForApp(packageName, limit)
            } else {
                database.logDao().getRecentLogs(limit)
            }
            entities.map { it.toModel() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get logs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get list of unique ports used by an app
     */
    fun getUsedPorts(packageName: String): List<Pair<Int, String>> {
        return try {
            val ports = database.logDao().getDistinctPorts(packageName)
            // PortInfo is a static inner class in Java, in Kotlin we access it via dot notation if visible
            // We need to make sure PortInfo is visible or use a different return type.
            // Since ConnectionLogDao is Java, PortInfo is ConnectionLogDao.PortInfo
            ports.map { Pair(it.destinationPort, it.protocol) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get used ports: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Clear old logs (retention policy)
     */
    fun clearOldLogs(daysToKeep: Int = 7) {
        dbExecutor.execute {
            try {
                val cutoff = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
                database.logDao().deleteOldLogs(cutoff)
                Log.d(TAG, "Cleared logs older than $daysToKeep days")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear old logs: ${e.message}")
            }
        }
    }
    
    // ====================
    // PERSISTENCE
    // ====================
    
    private fun loadPersistedState() {
        dbExecutor.execute {
            try {
                val states = database.ruleDao().getAllAppStates()
                for (stateEntity in states) {
                    try {
                        appStateCache[stateEntity.packageName] = FirewallState.valueOf(stateEntity.state)
                    } catch (e: Exception) {
                        // Invalid state, ignore
                    }
                }
                Log.d(TAG, "Loaded ${appStateCache.size} app states from database")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load persisted state: ${e.message}")
            }
        }
    }
}
