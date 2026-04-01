package com.editech.services.firewall

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-app bandwidth throttling using Token Bucket algorithm.
 * 
 * Called from OsStub sendto/recvfrom hooks in the engine layer via reflection.
 * Must be thread-safe and allocation-free in the hot path.
 *
 * Usage:
 *   BandwidthManager.setLimit("com.example.app", uploadBps = 10_485_760, downloadBps = 10_485_760)
 *   val delayMs = BandwidthManager.consumeTx("com.example.app", bytesWritten)
 *   if (delayMs > 0) Thread.sleep(delayMs)
 */
object BandwidthManager {
    private const val TAG = "BandwidthManager"

    /**
     * Token bucket for one direction (TX or RX).
     * Refills at [rateBytesPerSec] tokens per second.
     * Bucket capacity = 1 second of burst.
     */
    class TokenBucket(var rateBytesPerSec: Long) {
        private val tokens = AtomicLong(rateBytesPerSec)
        private val lastRefill = AtomicLong(System.nanoTime())

        /**
         * Consume [bytes] tokens. Returns the delay in milliseconds
         * the caller should sleep to stay within the rate limit.
         * Returns 0 if no delay needed.
         */
        fun consume(bytes: Int): Long {
            if (rateBytesPerSec <= 0) return 0

            refill()

            val remaining = tokens.addAndGet(-bytes.toLong())
            if (remaining >= 0) return 0

            // Calculate how long to wait for tokens to refill
            val deficit = -remaining
            val delayMs = (deficit * 1000) / rateBytesPerSec
            return delayMs.coerceIn(1, 1000) // Cap at 1 second max delay
        }

        private fun refill() {
            val now = System.nanoTime()
            val last = lastRefill.get()
            val elapsedNanos = now - last

            if (elapsedNanos <= 0) return

            if (lastRefill.compareAndSet(last, now)) {
                val newTokens = (elapsedNanos * rateBytesPerSec) / 1_000_000_000L
                if (newTokens > 0) {
                    // updateAndGet is atomic (CAS loop) — avoids the non-atomic
                    // get()+set() race where concurrent threads could read the same
                    // current value and then both set the same result, effectively
                    // doubling the tokens and bypassing the bandwidth limit.
                    tokens.updateAndGet { current ->
                        (current + newTokens).coerceAtMost(rateBytesPerSec)
                    }
                }
            }
        }
    }

    data class AppLimit(
        val txBucket: TokenBucket,
        val rxBucket: TokenBucket,
        var uploadBytesPerSec: Long,
        var downloadBytesPerSec: Long
    )

    // Per-app limits: packageName -> AppLimit
    private val limits = ConcurrentHashMap<String, AppLimit>()

    // ========================
    // CONFIGURATION
    // ========================

    /**
     * Set bandwidth limits for an app. Pass 0 to disable a direction.
     * @param uploadBps max upload bytes per second (0 = unlimited)  
     * @param downloadBps max download bytes per second (0 = unlimited)
     */
    fun setLimit(packageName: String, uploadBps: Long, downloadBps: Long) {
        if (uploadBps <= 0 && downloadBps <= 0) {
            limits.remove(packageName)
            Log.d(TAG, "Removed bandwidth limits for $packageName")
            return
        }

        val existing = limits[packageName]
        if (existing != null) {
            existing.txBucket.rateBytesPerSec = uploadBps
            existing.rxBucket.rateBytesPerSec = downloadBps
            existing.uploadBytesPerSec = uploadBps
            existing.downloadBytesPerSec = downloadBps
        } else {
            limits[packageName] = AppLimit(
                txBucket = TokenBucket(uploadBps),
                rxBucket = TokenBucket(downloadBps),
                uploadBytesPerSec = uploadBps,
                downloadBytesPerSec = downloadBps
            )
        }
        Log.d(TAG, "Set limits for $packageName: ↑${formatSpeed(uploadBps)} ↓${formatSpeed(downloadBps)}")
    }

    /**
     * Get current limits for an app.
     * @return Pair(uploadBps, downloadBps) or null if no limit
     */
    fun getLimit(packageName: String): Pair<Long, Long>? {
        val limit = limits[packageName] ?: return null
        return Pair(limit.uploadBytesPerSec, limit.downloadBytesPerSec)
    }

    /**
     * Check if an app has bandwidth limits.
     */
    fun isLimited(packageName: String): Boolean {
        return limits.containsKey(packageName)
    }

    /**
     * Remove all limits for an app.
     */
    fun removeLimit(packageName: String) {
        limits.remove(packageName)
    }

    // ========================
    // HOT PATH (called from OsStub hooks via reflection)
    // ========================

    /**
     * Consume TX (upload) tokens. Called on every sendto().
     * @return delay in ms to sleep (0 = no delay)
     */
    @JvmStatic
    fun consumeTx(packageName: String, bytes: Int): Long {
        val limit = limits[packageName] ?: return 0
        if (limit.uploadBytesPerSec <= 0) return 0
        return limit.txBucket.consume(bytes)
    }

    /**
     * Consume RX (download) tokens. Called on every recvfrom().
     * @return delay in ms to sleep (0 = no delay)
     */
    @JvmStatic
    fun consumeRx(packageName: String, bytes: Int): Long {
        val limit = limits[packageName] ?: return 0
        if (limit.downloadBytesPerSec <= 0) return 0
        return limit.rxBucket.consume(bytes)
    }

    // ========================
    // UTILITIES
    // ========================

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec <= 0 -> "unlimited"
            bytesPerSec < 1024 -> "${bytesPerSec} B/s"
            bytesPerSec < 1_048_576 -> "${bytesPerSec / 1024} KB/s"
            else -> "${bytesPerSec / 1_048_576} MB/s"
        }
    }
}
