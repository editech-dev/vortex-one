package com.editech.services.firewall

import android.content.Context
import android.util.Log // Added Log import
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
        private const val ACTION_UPDATE_STATE = "com.editech.services.firewall.ACTION_UPDATE_STATE"
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_STATE = "state"
        
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
    
    // Concurrency control for initial state load
    private val stateLoadLatch = java.util.concurrent.CountDownLatch(1)
    
    // Database (lazy initialization)
    private val database: FirewallDatabase by lazy {
        FirewallDatabase.getInstance(context)
    }

    // Bandwidth preferences
    private val bandwidthPrefs by lazy {
        context.getSharedPreferences("bandwidth_limits", Context.MODE_PRIVATE)
    }
    
    private var logCounter = 0
    
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_STATE) {
                val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
                val stateName = intent.getStringExtra(EXTRA_STATE) ?: return
                try {
                    val state = FirewallState.valueOf(stateName)
                    appStateCache[pkg] = state
                    // Force reload rules as they might have changed too
                    rulesCache.remove(pkg)
                    Log.d(TAG, "Sync: Updated state for $pkg to $state")
                } catch (e: Exception) {
                    Log.e(TAG, "Sync: Invalid state $stateName")
                }
            }
        }
    }

    init {
        // Load persisted state on init
        loadPersistedState()
        restoreBandwidthLimits()
        
        // Register receiver for cross-process updates
        val filter = IntentFilter(ACTION_UPDATE_STATE)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        context.registerReceiver(stateReceiver, filter, flags)
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
        // Wait for cache to load if called from a background thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            try {
                // Wait up to 2 seconds to not block indefinitely if something goes wrong
                stateLoadLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Interrupted waiting for state cache")
            }
        }
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
        
        // Broadcast change to other processes (e.g., Virtual Process)
        val intent = Intent(ACTION_UPDATE_STATE).apply {
            putExtra(EXTRA_PKG, packageName)
            putExtra(EXTRA_STATE, state.name)
            setPackage(context.packageName) // Restrict to own app
        }
        context.sendBroadcast(intent)
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
            FirewallState.MONITORING -> {
                // Even in monitoring mode, check if threat blocking rules exist
                checkThreatRules(packageName, address, port)
            }
            FirewallState.BLOCKING_ALL -> true
            FirewallState.BLOCKING_PORTS -> {
                checkPortRules(packageName, port) || checkThreatRules(packageName, address, port)
            }
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
                RuleType.BLOCK_ENDPOINT -> { /* Ignore endpoint rules for port checks */ }
                RuleType.BLOCK_LOCAL_NETWORK -> { /* Handled by checkThreatRules */ }
                RuleType.BLOCK_ADB_ACCESS -> { /* Handled by checkThreatRules */ }
            }
        }
        return false
    }

    // ====================
    // THREAT DETECTION
    // ====================

    /**
     * Classify a connection as a potential threat based on destination IP and port
     * @return ThreatType if this is a suspicious connection, null if normal
     */
    fun classifyThreat(address: InetAddress, port: Int): ThreatType? {
        val ip = address.hostAddress ?: return null

        // Never classify Tor local proxy or Tor virtual IPs (127.192.0.0/10) as a threat
        if (ip.startsWith("127.192.") || (address.isLoopbackAddress && (port == 9150 || port == 9151 || port == 5453))) {
            return null
        }

        // SSDP Multicast or UPnP / P2P port mapping ports (1900, 5351, 39900, 39901, 49152) -> LOCAL_NETWORK threat
        if (address.isMulticastAddress || ip == "239.255.255.250" || port == 1900 || port == 5351 || port == 39900 || port == 39901 || port == 49152) {
            return ThreatType.LOCAL_NETWORK
        }

        // ADB access detection: common ADB ports (5555, 5037)
        if (port == 5555 || port == 5037) {
            return ThreatType.ADB_ACCESS
        }

        // Localhost probe (exclude DNS on port 53)
        if (address.isLoopbackAddress && port != 53) {
            return ThreatType.LOCALHOST_PROBE
        }

        // Local/private network detection
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) {
            return ThreatType.LOCAL_NETWORK
        }

        // Manual check for private ranges (backup for older APIs)
        if (isPrivateIp(ip)) {
            return ThreatType.LOCAL_NETWORK
        }

        return null
    }

    private fun isPrivateIp(ip: String): Boolean {
        return ip.startsWith("10.") ||
               ip.startsWith("192.168.") ||
               ip.startsWith("169.254.") ||
               ip.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*"))
    }

    /**
     * Check if a connection should be blocked based on threat rules
     */
    private fun checkThreatRules(packageName: String, address: InetAddress, port: Int): Boolean {
        val threat = classifyThreat(address, port) ?: return false
        val rules = rulesCache[packageName] ?: loadRulesForPackage(packageName)

        for (rule in rules) {
            if (!rule.enabled) continue
            when {
                threat == ThreatType.ADB_ACCESS && rule.ruleType == RuleType.BLOCK_ADB_ACCESS -> return true
                threat == ThreatType.LOCAL_NETWORK && rule.ruleType == RuleType.BLOCK_LOCAL_NETWORK -> return true
                threat == ThreatType.LOCALHOST_PROBE && rule.ruleType == RuleType.BLOCK_LOCAL_NETWORK -> return true
            }
        }
        return false
    }

    /**
     * Get threat-tagged connection logs for an app
     */
    fun getThreatLogs(packageName: String, limit: Int = 20): List<ConnectionLog> {
        val db = database ?: return emptyList()
        return db.logDao().getThreatLogs(packageName, limit)
            .map { it.toModel() }
    }

    /**
     * Get Tor connection logs for an app
     */
    fun getTorLogs(packageName: String, limit: Int = 20): List<ConnectionLog> {
        val db = database ?: return emptyList()
        return db.logDao().getTorLogs(packageName, limit)
            .map { it.toModel() }
    }

    /**
     * Get Tor statistics for an app (success count, failure count)
     */
    fun getTorStats(packageName: String): Pair<Int, Int> {
        val db = database ?: return Pair(0, 0)
        val s = db.logDao().getTorSuccessCount(packageName)
        val f = db.logDao().getTorFailureCount(packageName)
        return Pair(s, f)
    }

    /**
     * Check if a threat blocking rule exists for an app
     */
    fun isThreatBlocked(packageName: String, threatType: ThreatType): Boolean {
        val rules = rulesCache[packageName] ?: loadRulesForPackage(packageName)
        val ruleType = when (threatType) {
            ThreatType.ADB_ACCESS -> RuleType.BLOCK_ADB_ACCESS
            ThreatType.LOCAL_NETWORK, ThreatType.LOCALHOST_PROBE -> RuleType.BLOCK_LOCAL_NETWORK
        }
        return rules.any { it.ruleType == ruleType && it.enabled }
    }

    /**
     * Toggle threat blocking for an app
     */
    fun setThreatBlocking(packageName: String, threatType: ThreatType, enabled: Boolean) {
        val ruleType = when (threatType) {
            ThreatType.ADB_ACCESS -> RuleType.BLOCK_ADB_ACCESS
            ThreatType.LOCAL_NETWORK, ThreatType.LOCALHOST_PROBE -> RuleType.BLOCK_LOCAL_NETWORK
        }

        if (enabled) {
            // Add rule if it doesn't exist
            if (!isThreatBlocked(packageName, threatType)) {
                addRule(FirewallRule(
                    packageName = packageName,
                    ruleType = ruleType
                ))
            }
        } else {
            // Remove the rule
            val rules = getRulesForPackage(packageName)
            val ruleToRemove = rules.find { it.ruleType == ruleType }
            ruleToRemove?.let { removeRule(it.id, packageName) }
        }
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
    // ENDPOINT BLOCKING
    // ====================

    /**
     * Check if a specific URL should be blocked based on rules
     * Called from NetworkConnectionMonitor
     */
    fun shouldBlockEndpoint(packageName: String, url: String): Boolean {
        if (!isEnabled(packageName)) return false
        val state = getState(packageName)
        if (state == FirewallState.BLOCKING_ALL) return true
        
        // Check rules
        val rules = rulesCache[packageName] ?: loadRulesForPackage(packageName)
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.ruleType == RuleType.BLOCK_ENDPOINT) {
                // strict blocking: if rule endpoint is contained in the URL or equals
                val blockedEndpoint = rule.endpoint ?: continue
                if (url.contains(blockedEndpoint, ignoreCase = true)) {
                    Log.d(TAG, "Blocking URL $url due to rule: $blockedEndpoint")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Add an endpoint blocking rule
     */
    fun addBlockEndpointRule(packageName: String, endpoint: String) {
        // Check if exists
        val rules = getRulesForPackage(packageName)
        if (rules.any { it.ruleType == RuleType.BLOCK_ENDPOINT && it.endpoint == endpoint }) {
            return
        }

        val rule = FirewallRule(
            packageName = packageName,
            ruleType = RuleType.BLOCK_ENDPOINT,
            endpoint = endpoint
        )
        addRule(rule)
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
        failureReason: String? = null,
        method: String? = null,
        path: String? = null,
        overrideHostname: String? = null
    ) {
        val hostname = overrideHostname ?: dnsCache["$packageName|$ip"] ?: dnsCache["*|$ip"]
        
        val log = ConnectionLog(
            packageName = packageName,
            destinationIp = ip,
            destinationPort = port,
            hostname = hostname,
            protocol = protocol,
            wasBlocked = blocked,
            status = status,
            failureReason = failureReason,
            method = method,
            path = path
        )
        
        dbExecutor.execute {
            try {
                database.logDao().insert(log.toEntity())
                logCounter++
                if (logCounter >= 50) {
                    logCounter = 0
                    // Auto-prune logs older than 7 days AND trim total logs to max 500 records
                    val cutoff = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
                    database.logDao().deleteOldLogs(cutoff)
                    database.logDao().trimLogsToMax(500)
                }
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
        dnsCache["$packageName|$ip"] = hostname
        dnsCache["*|$ip"] = hostname
        Log.d(TAG, "DNS: $hostname -> $ip (from $packageName)")
    }
    
    /**
     * Get recent connection logs
     */
    fun getRecentLogs(packageName: String? = null, limit: Int = 20): List<ConnectionLog> {
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
     * Get list of unique endpoints (URLs) used by an app
     */
    fun getUsedEndpoints(packageName: String): List<String> {
        return try {
            database.logDao().getDistinctEndpoints(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get used endpoints: ${e.message}")
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
            } finally {
                stateLoadLatch.countDown()
            }
        }
    }

    // ====================
    // BANDWIDTH LIMITS
    // ====================

    private fun restoreBandwidthLimits() {
         try {
            val allMap = bandwidthPrefs.all
            for ((key, value) in allMap) {
                if (value is Long && key.contains("_")) {
                    // key format: packageName_up or packageName_down
                    val isUpload = key.endsWith("_up")
                    val isDownload = key.endsWith("_down")
                    
                    if (isUpload || isDownload) {
                        val packageName = key.substringBeforeLast("_")
                        
                        // We need both up and down to set limit, so we might set them multiple times, which is fine
                        val up = bandwidthPrefs.getLong("${packageName}_up", 0)
                        val down = bandwidthPrefs.getLong("${packageName}_down", 0)
                        
                        if (up > 0 || down > 0) {
                            BandwidthManager.setLimit(packageName, up, down)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun setBandwidthLimit(packageName: String, uploadBytesPerSec: Long, downloadBytesPerSec: Long) {
        // Update runtime manager
        BandwidthManager.setLimit(packageName, uploadBytesPerSec, downloadBytesPerSec)
        
        // Persist
        bandwidthPrefs.edit().apply {
            putLong("${packageName}_up", uploadBytesPerSec)
            putLong("${packageName}_down", downloadBytesPerSec)
            apply()
        }
    }
    
    fun getBandwidthLimit(packageName: String): Pair<Long, Long> {
        // Read from manager as source of truth for runtime, or prefs if not loaded yet
        return BandwidthManager.getLimit(packageName) ?: run {
            val up = bandwidthPrefs.getLong("${packageName}_up", 0)
            val down = bandwidthPrefs.getLong("${packageName}_down", 0)
            Pair(up, down)
        }
    }
}
