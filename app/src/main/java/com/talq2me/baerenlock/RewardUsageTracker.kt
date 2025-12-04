package com.talq2me.baerenlock

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tracks app usage during reward time sessions
 */
class RewardUsageTracker(private val context: Context) {
    
    data class AppUsageSession(
        val packageName: String,
        val appName: String,
        val startTime: Long,
        var endTime: Long? = null,
        var durationSeconds: Long = 0
    ) {
        fun updateDuration() {
            endTime?.let {
                durationSeconds = (it - startTime) / 1000
            } ?: run {
                // If endTime is null, calculate from current time
                durationSeconds = (System.currentTimeMillis() - startTime) / 1000
            }
        }
        
        val formattedDuration: String
            get() {
                val minutes = durationSeconds / 60
                val seconds = durationSeconds % 60
                return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
            }
    }
    
    private var currentSession: AppUsageSession? = null
    private val sessions = mutableListOf<AppUsageSession>()
    private var rewardStartTime: Long? = null
    private var rewardEndTime: Long? = null
    
    /**
     * Starts tracking a reward session
     */
    fun startRewardSession() {
        rewardStartTime = System.currentTimeMillis()
        sessions.clear()
        currentSession = null
        Log.d(TAG, "Started reward session tracking")
    }
    
    /**
     * Records when an app comes to foreground during reward time
     */
    fun onAppForeground(packageName: String) {
        // Only track if reward session is active
        if (rewardStartTime == null) {
            return
        }
        
        // If switching to a different app, end the current session
        if (currentSession != null && currentSession!!.packageName != packageName) {
            endCurrentSession()
        }
        
        // Start new session if we don't have one or if it's a different app
        if (currentSession == null || currentSession!!.packageName != packageName) {
            val appName = getAppName(packageName)
            currentSession = AppUsageSession(
                packageName = packageName,
                appName = appName,
                startTime = System.currentTimeMillis()
            )
            Log.d(TAG, "Started tracking app: $appName ($packageName)")
        }
    }
    
    /**
     * Records when an app goes to background during reward time
     */
    fun onAppBackground(packageName: String) {
        if (currentSession != null && currentSession!!.packageName == packageName) {
            endCurrentSession()
        }
    }
    
    /**
     * Ends the current tracking session
     */
    private fun endCurrentSession() {
        currentSession?.let { session ->
            session.endTime = System.currentTimeMillis()
            session.updateDuration()
            sessions.add(session)
            Log.d(TAG, "Ended tracking app: ${session.appName} - Duration: ${session.formattedDuration}")
            currentSession = null
        }
    }
    
    /**
     * Ends the reward session and returns all tracked usage
     */
    fun endRewardSession(): List<AppUsageSession> {
        rewardEndTime = System.currentTimeMillis()
        
        // End current session if active
        endCurrentSession()
        
        // Update all session durations to be accurate
        sessions.forEach { it.updateDuration() }
        
        Log.d(TAG, "Ended reward session. Total apps tracked: ${sessions.size}")
        if (sessions.isEmpty()) {
            Log.w(TAG, "No app usage sessions were tracked during reward time")
        } else {
            sessions.forEach { session ->
                Log.d(TAG, "  Session: ${session.appName} (${session.packageName}) - ${session.formattedDuration}")
            }
        }
        return sessions.toList()
    }
    
    /**
     * Gets the app name from package name
     */
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        } catch (e: Exception) {
            Log.w(TAG, "Error getting app name for $packageName: ${e.message}")
            packageName
        }
    }
    
    /**
     * Gets summary statistics for the reward session
     */
    fun getSessionSummary(): RewardSessionSummary {
        val allSessions = if (currentSession != null) {
            sessions + listOf(currentSession!!.apply { updateDuration() })
        } else {
            sessions
        }
        
        val totalTimeSeconds = allSessions.sumOf { it.durationSeconds }
        val appUsageMap = allSessions.groupBy { it.packageName }
            .mapValues { (_, sessions) -> sessions.sumOf { it.durationSeconds } }
            .toList()
            .sortedByDescending { it.second }
        
        Log.d(TAG, "Session summary: ${allSessions.size} total sessions, ${totalTimeSeconds}s total time, ${appUsageMap.size} unique apps")
        
        return RewardSessionSummary(
            startTime = rewardStartTime ?: 0L,
            endTime = rewardEndTime ?: System.currentTimeMillis(),
            totalTimeSeconds = totalTimeSeconds,
            uniqueApps = appUsageMap.size,
            appUsageMap = appUsageMap.toMap()
        )
    }
    
    data class RewardSessionSummary(
        val startTime: Long,
        val endTime: Long,
        val totalTimeSeconds: Long,
        val uniqueApps: Int,
        val appUsageMap: Map<String, Long> // packageName -> total seconds
    ) {
        val totalTimeMinutes: Int
            get() = (totalTimeSeconds / 60).toInt()
        
        val formattedTotalTime: String
            get() {
                val minutes = totalTimeSeconds / 60
                val seconds = totalTimeSeconds % 60
                return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
            }
    }
    
    companion object {
        private const val TAG = "RewardUsageTracker"
    }
}

