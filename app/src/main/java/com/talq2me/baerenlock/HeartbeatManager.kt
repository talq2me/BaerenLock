package com.talq2me.baerenlock

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks accessibility service liveness via periodic heartbeats.
 */
object HeartbeatManager {
    private const val TAG = "HeartbeatManager"
    private const val PREFS_NAME = "health_prefs"
    private const val KEY_RECEIVING_EVENTS = "service_receiving_events"
    private const val KEY_LAST_HEALTH_CHECK = "last_service_health_check"

    private val lastHeartbeatMs = AtomicLong(0L)

    fun touch() {
        val now = System.currentTimeMillis()
        lastHeartbeatMs.set(now)
    }

    fun getLastHeartbeatMs(): Long = lastHeartbeatMs.get()

    fun isStale(thresholdMs: Long = 15_000L): Boolean {
        val last = lastHeartbeatMs.get()
        if (last == 0L) return true
        return System.currentTimeMillis() - last > thresholdMs
    }

    fun persistHealthStatus(context: Context, isHealthy: Boolean) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putBoolean(KEY_RECEIVING_EVENTS, isHealthy)
                putLong(KEY_LAST_HEALTH_CHECK, System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting health status", e)
        }
    }

    fun markHealthy(context: Context) {
        touch()
        persistHealthStatus(context, true)
    }

    fun markStale(context: Context) {
        persistHealthStatus(context, false)
    }
}
