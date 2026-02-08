package com.editech.services.utils

import android.content.Context
import android.util.Log

/**
 * Bridge between the app module and Bcore's FirewallManager
 * Uses reflection to avoid direct module dependency issues
 */
object FirewallBridge {
    private const val TAG = "FirewallBridge"
    private const val FIREWALL_MANAGER_CLASS = "top.niunaijun.blackbox.core.firewall.FirewallManager"
    private const val FIREWALL_STATE_CLASS = "top.niunaijun.blackbox.core.firewall.FirewallState"
    private const val CONNECTION_LOG_CLASS = "top.niunaijun.blackbox.core.firewall.ConnectionLog"

    private var managerInstance: Any? = null
    private var managerClass: Class<*>? = null
    private var stateClass: Class<*>? = null

    /**
     * Initialize the bridge - call once at app startup
     */
    fun init(context: Context): Boolean {
        return try {
            managerClass = Class.forName(FIREWALL_MANAGER_CLASS)
            stateClass = Class.forName(FIREWALL_STATE_CLASS)
            
            // Get singleton instance via getInstance(context)
            val getInstanceMethod = managerClass!!.getMethod("getInstance", Context::class.java)
            managerInstance = getInstanceMethod.invoke(null, context)
            
            Log.d(TAG, "FirewallBridge initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FirewallBridge: ${e.message}")
            false
        }
    }

    /**
     * Set firewall state for an app
     * @param state 0=DISABLED, 1=MONITORING, 2=BLOCKING_ALL
     */
    fun setState(packageName: String, stateOrdinal: Int): Boolean {
        return try {
            val manager = getManager() ?: return false
            val stateEnum = getStateEnum(stateOrdinal) ?: return false
            
            val setStateMethod = managerClass!!.getMethod("setState", String::class.java, stateClass)
            setStateMethod.invoke(manager, packageName, stateEnum)
            
            Log.d(TAG, "Set state for $packageName to ordinal $stateOrdinal")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set state: ${e.message}")
            false
        }
    }

    /**
     * Get current firewall state for an app
     * @return state ordinal (0=DISABLED, 1=MONITORING, 2=BLOCKING_ALL) or 0 if error
     */
    fun getState(packageName: String): Int {
        return try {
            val manager = getManager() ?: return 0
            
            val getStateMethod = managerClass!!.getMethod("getState", String::class.java)
            val stateEnum = getStateMethod.invoke(manager, packageName)
            
            // Get ordinal from enum
            val ordinalMethod = stateEnum?.javaClass?.getMethod("ordinal")
            ordinalMethod?.invoke(stateEnum) as? Int ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get state: ${e.message}")
            0
        }
    }

    /**
     * Get recent connection logs
     * Returns list of maps with log data for UI display
     */
    fun getRecentLogs(packageName: String? = null, limit: Int = 100): List<Map<String, Any?>> {
        return try {
            val manager = getManager() ?: return emptyList()
            
            val getLogsMethod = managerClass!!.getMethod(
                "getRecentLogs",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            
            @Suppress("UNCHECKED_CAST")
            val logs = getLogsMethod.invoke(manager, packageName, limit) as? List<*> ?: return emptyList()
            
            // Convert ConnectionLog objects to maps for UI
            logs.mapNotNull { log ->
                if (log == null) return@mapNotNull null
                try {
                    val logClass = log.javaClass
                    mapOf(
                        "packageName" to logClass.getMethod("getPackageName").invoke(log),
                        "destinationIp" to logClass.getMethod("getDestinationIp").invoke(log),
                        "destinationPort" to logClass.getMethod("getDestinationPort").invoke(log),
                        "hostname" to logClass.getMethod("getHostname").invoke(log),
                        "protocol" to logClass.getMethod("getProtocol").invoke(log),
                        "timestamp" to logClass.getMethod("getTimestamp").invoke(log),
                        "wasBlocked" to logClass.getMethod("getWasBlocked").invoke(log)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse log: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get logs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Check if firewall is enabled for an app
     */
    fun isEnabled(packageName: String): Boolean {
        return try {
            val manager = getManager() ?: return false
            val isEnabledMethod = managerClass!!.getMethod("isEnabled", String::class.java)
            isEnabledMethod.invoke(manager, packageName) as? Boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check isEnabled: ${e.message}")
            false
        }
    }

    private fun getManager(): Any? {
        if (managerInstance == null) {
            Log.e(TAG, "FirewallBridge not initialized!")
        }
        return managerInstance
    }

    private fun getStateEnum(ordinal: Int): Any? {
        return try {
            val values = stateClass?.getMethod("values")?.invoke(null) as? Array<*>
            values?.getOrNull(ordinal)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get state enum: ${e.message}")
            null
        }
    }
}
