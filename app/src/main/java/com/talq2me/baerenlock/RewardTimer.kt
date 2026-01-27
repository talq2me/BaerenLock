package com.talq2me.baerenlock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.app.usage.UsageStatsManager
import android.app.ActivityManager
import android.app.AppOpsManager
import android.os.Build

/**
 * Manages the reward timer that decrements reward minutes based on actual app usage.
 * Handles timer start/stop, decrement logic, and usage tracking integration.
 */
object RewardTimer {
    private const val TAG = "RewardTimer"
    
    private var rewardTimer: Handler? = null
    private var rewardRunnable: Runnable? = null
    private var lastRewardDecrementTime: Long = 0
    private var rewardTimeStartTime: Long = 0
    private var rewardTimeStartMinutes: Int = 0
    private var lastUsageCheckTime: Long = 0
    
    // Callback interface for timer events
    interface TimerCallback {
        fun onRewardTimeExpired(context: Context)
        fun onRewardMinutesUpdated(context: Context, newMinutes: Int)
    }
    
    private var callback: TimerCallback? = null
    
    /**
     * Sets the callback for timer events
     */
    fun setCallback(callback: TimerCallback) {
        this.callback = callback
    }
    
    /**
     * Starts the reward timer to decrement minutes and update storage.
     * Uses UsageStatsManager to track actual app usage time for accuracy.
     * Falls back to timer-based tracking if UsageStats permission is not available.
     */
    fun startTimer(
        context: Context,
        currentMinutes: Int,
        rewardEligibleApps: Set<String>,
        isRewardAppInForeground: (Context) -> Boolean,
        getActualRewardAppUsageMinutes: (Context, Long, Long) -> Int
    ) {
        Log.d(TAG, "startTimer called. Current minutes: $currentMinutes, timer already running: ${rewardRunnable != null}")
        
        // If timer is already running and we have reward minutes, don't restart it
        if (rewardRunnable != null && currentMinutes > 0) {
            Log.d(TAG, "Timer already running with $currentMinutes minutes, skipping restart")
            return
        }
        
        // Initialize timing tracking if this is a new session (timer not running)
        val currentTime = System.currentTimeMillis()
        val isNewSession = (lastRewardDecrementTime == 0L || rewardRunnable == null)
        
        if (isNewSession) {
            lastRewardDecrementTime = currentTime
            rewardTimeStartTime = currentTime
            rewardTimeStartMinutes = currentMinutes
            lastUsageCheckTime = currentTime
            Log.d(TAG, "Initialized reward timer tracking for new session. Start time: $currentTime, Start minutes: $rewardTimeStartMinutes")
        } else {
            Log.d(TAG, "Timer already running, continuing existing session. Last decrement: $lastRewardDecrementTime")
        }
        
        // Always remove any existing callbacks to prevent duplicate timers
        rewardRunnable?.let { rewardTimer?.removeCallbacks(it) }
        rewardTimer = Handler(Looper.getMainLooper())

        rewardRunnable = object : Runnable {
            override fun run() {
                var currentRewardMinutes = RewardStorage.getCurrentRewardMinutes()
                
                if (currentRewardMinutes > 0) {
                    val now = System.currentTimeMillis()
                    val oneMinuteInMillis = 60 * 1000L
                    var shouldSave = false
                    var valueChanged = false // Track if value actually changed (for last_updated timestamp)
                    
                    // BULLETPROOF APPROACH: Use elapsed time since timer started as PRIMARY mechanism
                    // This ensures we always decrement correctly even if foreground detection fails
                    val timeSinceStart = now - rewardTimeStartTime
                    val elapsedMinutes = (timeSinceStart / oneMinuteInMillis).toInt()
                    val expectedMinutesRemaining = rewardTimeStartMinutes - elapsedMinutes
                    
                    // Try to use UsageStatsManager for validation/adjustment (if permission available)
                    if (hasUsageStatsPermission(context)) {
                        // Wait at least 30 seconds before checking UsageStats to avoid historical data
                        val minimumTimeForUsageCheck = 30 * 1000L
                        
                        if (timeSinceStart >= minimumTimeForUsageCheck) {
                            val actualUsageMinutes = getActualRewardAppUsageMinutes(context, rewardTimeStartTime, now)
                            val maxPossibleUsageMinutes = ((timeSinceStart / oneMinuteInMillis) + 1).toInt()
                            val safeActualUsage = actualUsageMinutes.coerceAtMost(maxPossibleUsageMinutes)
                            
                            // Use UsageStats value if available, but ensure we don't go below elapsed time
                            val usageBasedRemaining = rewardTimeStartMinutes - safeActualUsage
                            val timeBasedRemaining = expectedMinutesRemaining
                            
                            // Use the lower of the two (more conservative - ensures we don't give extra time)
                            val newCurrentMinutes = minOf(usageBasedRemaining, timeBasedRemaining).coerceAtLeast(0)
                            
                            if (newCurrentMinutes != currentRewardMinutes) {
                                Log.d(TAG, "UsageStats: elapsed=${timeSinceStart/1000}s, elapsedMinutes=$elapsedMinutes, actualUsage=$safeActualUsage min, expectedRemaining=$timeBasedRemaining, usageRemaining=$usageBasedRemaining, updating from $currentRewardMinutes to $newCurrentMinutes minutes")
                                currentRewardMinutes = newCurrentMinutes
                                RewardStorage.setCurrentRewardMinutes(currentRewardMinutes)
                                callback?.onRewardMinutesUpdated(context, currentRewardMinutes)
                                shouldSave = true
                                valueChanged = true
                                lastUsageCheckTime = now
                            } else {
                                // Even if value hasn't changed, save periodically (every minute) as safety net
                                val timeSinceLastSave = now - lastUsageCheckTime
                                if (timeSinceLastSave >= oneMinuteInMillis) {
                                    shouldSave = true
                                    valueChanged = false // Periodic save, value didn't change
                                    lastUsageCheckTime = now
                                    Log.d(TAG, "Periodic save (UsageStats): elapsed=${timeSinceStart/1000}s, current=$currentRewardMinutes minutes (unchanged but saving as safety)")
                                }
                            }
                        } else {
                            // Too soon for UsageStats - use elapsed time only, but still save periodically
                            val newCurrentMinutes = expectedMinutesRemaining.coerceAtLeast(0)
                            val previousMinutes = currentRewardMinutes
                            if (newCurrentMinutes != currentRewardMinutes) {
                                currentRewardMinutes = newCurrentMinutes
                                RewardStorage.setCurrentRewardMinutes(currentRewardMinutes)
                                callback?.onRewardMinutesUpdated(context, currentRewardMinutes)
                                shouldSave = true
                                valueChanged = true
                                Log.d(TAG, "Early timer: elapsed=${timeSinceStart/1000}s, updating from $previousMinutes to $newCurrentMinutes minutes")
                            }
                            // Still do periodic save even if value unchanged
                            val timeSinceLastSave = now - lastUsageCheckTime
                            if (timeSinceLastSave >= oneMinuteInMillis) {
                                shouldSave = true
                                valueChanged = false // Periodic save, value didn't change
                                lastUsageCheckTime = now
                                Log.d(TAG, "Periodic save (early timer): elapsed=${timeSinceStart/1000}s, current=$currentRewardMinutes minutes")
                            }
                        }
                    } else {
                        // Fallback: Timer-based tracking - BULLETPROOF: Use elapsed time as PRIMARY mechanism
                        // When reward time is active (timer running), decrement based on elapsed time
                        // The timer being active means reward apps were granted access, so we count the time
                        // This ensures we NEVER get out of sync even if foreground detection fails
                        val newCurrentMinutes = expectedMinutesRemaining.coerceAtLeast(0)
                        val previousMinutes = currentRewardMinutes
                        val isRewardAppActive = isRewardAppInForeground(context)
                        
                        // ALWAYS update based on elapsed time - this is bulletproof
                        // The timer running means reward time was granted, so we count elapsed time
                        if (newCurrentMinutes != currentRewardMinutes) {
                            currentRewardMinutes = newCurrentMinutes
                            RewardStorage.setCurrentRewardMinutes(currentRewardMinutes)
                            callback?.onRewardMinutesUpdated(context, currentRewardMinutes)
                            shouldSave = true
                            valueChanged = true
                            Log.d(TAG, "Timer-based: elapsed=${timeSinceStart/1000}s, elapsedMinutes=$elapsedMinutes, rewardAppActive=$isRewardAppActive, updating from $previousMinutes to $newCurrentMinutes minutes")
                        }
                        
                        // ALWAYS save every minute as a safety net to prevent sync issues
                        val timeSinceLastSave = now - lastRewardDecrementTime
                        if (timeSinceLastSave >= oneMinuteInMillis) {
                            shouldSave = true
                            // Don't set valueChanged=true here - this is a periodic save, value didn't change
                            lastRewardDecrementTime = now
                            Log.d(TAG, "Periodic save (timer-based): elapsed=${timeSinceStart/1000}s, current=$currentRewardMinutes minutes (saving every minute as safety net)")
                        }
                    }
                    
                    // Save to local storage if we need to
                    // Only update last_updated if value actually changed (as per Daily Reset Logic spec)
                    if (shouldSave) {
                        RewardStorage.saveRewardMinutes(context, updateLastUpdated = valueChanged)
                    }

                    if (currentRewardMinutes == 0) {
                        // Reward time is up
                        stopTimer()
                        callback?.onRewardTimeExpired(context)
                    } else {
                        // Schedule next check - more frequently if using UsageStats for accuracy
                        val checkInterval = if (hasUsageStatsPermission(context)) {
                            5 * 1000L // Check every 5 seconds when using UsageStats
                        } else {
                            10 * 1000L // Check every 10 seconds for timer-based fallback
                        }
                        rewardTimer?.postDelayed(this, checkInterval)
                    }
                } else {
                    // No reward time left, stop the timer
                    stopTimer()
                }
            }
        }

        // Start the runnable immediately to process the current state and then schedule for future
        rewardTimer?.post(rewardRunnable!!)
        Log.d(TAG, "Reward timer initiated. First run scheduled immediately.")
    }
    
    /**
     * Stops the reward timer
     */
    fun stopTimer() {
        rewardRunnable?.let { rewardTimer?.removeCallbacks(it) }
        rewardRunnable = null
        lastRewardDecrementTime = 0L
        rewardTimeStartTime = 0L
        rewardTimeStartMinutes = 0
        lastUsageCheckTime = 0L
        Log.d(TAG, "Reward timer stopped.")
    }
    
    /**
     * Checks if the timer is currently running
     */
    fun isTimerRunning(): Boolean {
        return rewardRunnable != null
    }
    
    /**
     * Updates the start minutes when new reward time is added.
     * This ensures usage-based tracking works correctly when reward time is added mid-session.
     */
    fun updateStartMinutesForNewRewardTime(addedMinutes: Int, currentMinutes: Int, hasUsageStatsPermission: (Context) -> Boolean, getActualRewardAppUsageMinutes: (Context, Long, Long) -> Int, context: Context) {
        if (rewardTimeStartTime > 0 && rewardTimeStartMinutes > 0) {
            // We're in an active session, adjust the start minutes
            // Calculate actual usage so far
            val now = System.currentTimeMillis()
            val actualUsageMinutes = if (hasUsageStatsPermission(context)) {
                getActualRewardAppUsageMinutes(context, rewardTimeStartTime, now)
            } else {
                // Fallback: estimate based on elapsed time and current minutes
                rewardTimeStartMinutes - currentMinutes
            }
            
            // Reset start time and adjust start minutes
            rewardTimeStartTime = now
            rewardTimeStartMinutes = currentMinutes
            lastRewardDecrementTime = now
            lastUsageCheckTime = now
            
            Log.d(TAG, "Updated start minutes: added $addedMinutes, actual usage was $actualUsageMinutes, new start: $rewardTimeStartMinutes minutes")
        } else {
            // No active session, just set the start values
            rewardTimeStartMinutes = currentMinutes
            Log.d(TAG, "Set start minutes for new session: $rewardTimeStartMinutes minutes")
        }
    }
    
    /**
     * Checks if UsageStats permission is granted.
     */
    private fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    "android:get_usage_stats",
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "Error checking UsageStats permission: ${e.message}")
            false
        }
    }
}
