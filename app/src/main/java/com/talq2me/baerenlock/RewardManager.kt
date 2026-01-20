package com.talq2me.baerenlock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.SharedPreferences
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Import LocalBroadcastManager
import android.app.usage.UsageStatsManager
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.content.ComponentName
import com.talq2me.baerenlock.LauncherActivity
import java.util.Calendar
import android.os.Build
import android.app.AppOpsManager

object RewardManager {
    private const val TAG = "RewardManager"

    // Delegate to RewardStorage for current minutes
    var currentRewardMinutes: Int
        get() = RewardStorage.getCurrentRewardMinutes()
        set(value) = RewardStorage.setCurrentRewardMinutes(value)
    
    private var rewardTimer: Handler? = null
    private var rewardRunnable: Runnable? = null
    private var usageTracker: RewardUsageTracker? = null
    private var rewardSessionActive: Boolean = false
    private var lastRewardDecrementTime: Long = 0 // Track when we last decremented reward time
    private var rewardTimeStartTime: Long = 0 // Track when reward time session started
    private var rewardTimeStartMinutes: Int = 0 // Track how many minutes we started with (for usage-based tracking)
    private var lastUsageCheckTime: Long = 0 // Track when we last checked usage stats
    private var lastPeriodicSaveLogTime: Long = 0 // Track when we last logged a periodic save
    
    // Store last reward session data for report generation
    var lastRewardSessions: List<RewardUsageTracker.AppUsageSession>? = null
    var lastRewardSummary: RewardUsageTracker.RewardSessionSummary? = null

    // Delegate to RewardAppsManager for app lists
    // Note: These return mutable sets for backward compatibility, but changes should go through RewardAppsManager
    val allowedApps: MutableSet<String>
        get() = RewardAppsManager.getAllowedAppsList().toMutableSet()
    
    val rewardEligibleApps: MutableSet<String>
        get() = RewardAppsManager.getRewardEligibleApps().toMutableSet()
    
    // Helper to get rewardEligibleApps for internal use (more efficient)
    private val rewardEligibleAppsSet: Set<String>
        get() = RewardAppsManager.getRewardEligibleApps()
    
    // Access to memory allowed apps and essential system packages
    val memoryAllowedApps: Set<String>
        get() = RewardAppsManager.memoryAllowedApps
    
    val essentialSystemPackages: Set<String>
        get() = RewardAppsManager.essentialSystemPackages

    fun grantAccess(context: Context, pkg: String, minutes: Int) {
        RewardAppsManager.grantTemporaryAccess(pkg, context)
        
        // Start tracking reward session usage
        startRewardSessionTracking(context)

        rewardRunnable?.let { rewardTimer?.removeCallbacks(it) }
        rewardTimer = Handler(Looper.getMainLooper())
        rewardRunnable = Runnable {
            Log.d(TAG, "🚫 Reward time expired for $pkg - removing from allowed apps")
            RewardAppsManager.revokeTemporaryAccess(pkg, context)
            Log.d(TAG, "Updated allowed apps")

            // 🚫 Try to kill the app's background processes
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(pkg)

            // ✅ Return to launcher/home
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
        rewardTimer?.postDelayed(rewardRunnable!!, minutes * 60 * 1000L)
    }

    fun isAllowed(pkg: String): Boolean {
        return RewardAppsManager.isAllowed(pkg, currentRewardMinutes)
    }

    fun addToWhitelist(pkg: String, context: Context) {
        RewardAppsManager.addToWhitelist(pkg, context)
    }

    fun removeFromWhitelist(pkg: String, context: Context) {
        RewardAppsManager.removeFromWhitelist(pkg, context)
    }

    fun saveAllowedApps(context: Context) {
        RewardAppsManager.saveAllowedApps(context)
    }

    fun loadAllowedApps(context: Context) {
        RewardAppsManager.loadAllowedApps(context)
        RewardAppsManager.loadRewardEligibleApps(context)
    }

    fun killUnauthorizedBackgroundApps(context: Context) {
        try {
            // Check if aggressive cleanup is enabled
            val aggressiveCleanup = SettingsManager.readAggressiveCleanup(context)

            if (!aggressiveCleanup) {
                Log.d(TAG, "Aggressive cleanup disabled, skipping background app cleanup")
                return
            }

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val packageManager = context.packageManager

            // Get the current foreground app to prevent killing it
            var foregroundPackageName: String? = null
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val time = System.currentTimeMillis()
            val usageEvents = usageStatsManager.queryEvents(time - 1000 * 10, time)
            val event = android.app.usage.UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    foregroundPackageName = event.packageName
                }
            }

            // Get the default launcher package to prevent killing it
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultLauncherPackage = resolveInfo?.activityInfo?.packageName

            val doNotKillList = mutableSetOf<String>()
            foregroundPackageName?.let { doNotKillList.add(it) }
            defaultLauncherPackage?.let { doNotKillList.add(it) }
            doNotKillList.addAll(memoryAllowedApps)
            doNotKillList.addAll(essentialSystemPackages)

            // Get running app processes
            val runningProcesses = activityManager.runningAppProcesses ?: return

            var killedCount = 0
            for (process in runningProcesses) {
                val packageName = process.processName

                // Skip apps that are explicitly whitelisted or are critical system components
                // (No need to log this - it's expected behavior and happens frequently)
                if (doNotKillList.contains(packageName) ||
                    packageName.startsWith("com.android.") ||
                    packageName.startsWith("android.")) {
                    continue
                }

                // Kill unauthorized background processes
                try {
                    Log.d(TAG, "Killing unauthorized background app: $packageName")
                    activityManager.killBackgroundProcesses(packageName)
                    killedCount++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to kill process $packageName: ${e.message}")
                }
            }

            if (killedCount > 0) {
                Log.d(TAG, "Killed $killedCount unauthorized background apps")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error killing background apps", e)
        }
    }

    fun killUnauthorizedBackgroundAppsWithCount(context: Context): Int {
        try {
            // Check if aggressive cleanup is enabled
            val aggressiveCleanup = SettingsManager.readAggressiveCleanup(context)

            if (!aggressiveCleanup) {
                Log.d(TAG, "Aggressive cleanup disabled, skipping background app cleanup")
                return 0
            }

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager

            // Get running app processes
            val runningProcesses = activityManager.runningAppProcesses ?: return 0

            var killedCount = 0
            for (process in runningProcesses) {
                val packageName = process.processName

                // Skip apps explicitly allowed in memory (BaerenLock, BaerenEd, PokemonGo)
                if (memoryAllowedApps.contains(packageName)) {
                    continue
                }

                // Skip essential system processes
                if (essentialSystemPackages.contains(packageName) ||
                    packageName.startsWith("com.android.") ||
                    packageName.startsWith("android.")) {
                    continue
                }

                // Skip apps from the 'allowedApps' (launcher whitelist/reward apps) which are handled separately
                // if (isAllowed(packageName)) {
                //    continue
                // }

                // Kill unauthorized background processes
                try {
                    Log.d(TAG, "Killing unauthorized background app: $packageName")
                    activityManager.killBackgroundProcesses(packageName)
                    killedCount++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to kill process $packageName: ${e.message}")
                }
            }

            if (killedCount > 0) {
                Log.d(TAG, "Killed $killedCount unauthorized background apps")
            }

            return killedCount

        } catch (e: Exception) {
            Log.e(TAG, "Error killing background apps", e)
            return 0
        }
    }

    fun isBackgroundAppAllowed(packageName: String): Boolean {
        return RewardAppsManager.isBackgroundAppAllowed(packageName)
    }

    fun addPokemonGoIfInstalled(context: Context) {
        RewardAppsManager.addPokemonGoIfInstalled(context)
    }

    fun getAllowedAppsList(): Set<String> {
        return RewardAppsManager.getAllowedAppsList()
    }

    fun refreshRewardEligibleApps(context: Context) {
        RewardAppsManager.refreshRewardEligibleApps(context)
    }

    fun saveRewardMinutes(context: Context) {
        RewardStorage.saveRewardMinutes(context)
    }

    fun loadRewardMinutes(context: Context) {
        val wasLoaded = RewardStorage.loadRewardMinutes(context)
        if (currentRewardMinutes > 0) {
            startRewardTimer(context)
        }
    }

    /**
     * Updates the start minutes when new reward time is added.
     * This ensures usage-based tracking works correctly when reward time is added mid-session.
     */
    fun updateStartMinutesForNewRewardTime(context: Context, addedMinutes: Int) {
        if (rewardTimeStartTime > 0 && rewardTimeStartMinutes > 0) {
            // We're in an active session, adjust the start minutes
            // Calculate actual usage so far
            val now = System.currentTimeMillis()
            val actualUsageMinutes = if (hasUsageStatsPermission(context)) {
                getActualRewardAppUsageMinutes(context, rewardTimeStartTime, now)
            } else {
                // Fallback: estimate based on elapsed time and current minutes
                rewardTimeStartMinutes - currentRewardMinutes
            }
            
            // Reset start time and adjust start minutes
            rewardTimeStartTime = now
            rewardTimeStartMinutes = currentRewardMinutes
            lastRewardDecrementTime = now
            lastUsageCheckTime = now
            
            Log.d(TAG, "Updated start minutes: added $addedMinutes, actual usage was $actualUsageMinutes, new start: $rewardTimeStartMinutes minutes")
        } else {
            // No active session, just set the start values
            rewardTimeStartMinutes = currentRewardMinutes
            Log.d(TAG, "Set start minutes for new session: $rewardTimeStartMinutes minutes")
        }
    }

    /**
     * Checks if a transaction ID has already been processed.
     * This prevents double-counting when both Intent and Broadcast are received.
     */
    fun isTransactionProcessed(context: Context, transactionId: Long): Boolean {
        return RewardStorage.isTransactionProcessed(context, transactionId)
    }

    /**
     * Marks a transaction ID as processed to prevent double-counting.
     * Also cleans up old transaction IDs (older than 24 hours) to prevent unbounded growth.
     */
    fun markTransactionProcessed(context: Context, transactionId: Long) {
        RewardStorage.markTransactionProcessed(context, transactionId)
    }

    // Track the last known foreground app (updated by AppBlockerService or our own checks)
    private var lastKnownForegroundApp: String? = null
    private var lastForegroundAppUpdateTime: Long = 0
    
    /**
     * Updates the last known foreground app. Can be called by AppBlockerService or internally.
     */
    fun updateForegroundApp(packageName: String?) {
        if (packageName != null) {
            // Only log if the package name actually changed
            val packageChanged = lastKnownForegroundApp != packageName
            if (packageChanged) {
                Log.d(TAG, "Updated last known foreground app: $lastKnownForegroundApp -> $packageName")
            }
            
            lastKnownForegroundApp = packageName
            lastForegroundAppUpdateTime = System.currentTimeMillis()
            
            // Track app usage if reward session is active
            if (rewardSessionActive && currentRewardMinutes > 0) {
                usageTracker?.onAppForeground(packageName)
                if (packageChanged) {
                    Log.d(TAG, "Tracking foreground app during reward time: $packageName")
                }
            }
        }
    }
    
    /**
     * Starts tracking reward session usage
     */
    fun startRewardSessionTracking(context: Context) {
        if (!rewardSessionActive) {
            usageTracker = RewardUsageTracker(context)
            usageTracker?.startRewardSession()
            rewardSessionActive = true
            Log.d(TAG, "Started reward session tracking")
        } else {
            Log.d(TAG, "Reward session tracking already active, skipping start")
        }
    }
    
    /**
     * Ends reward session tracking and returns the usage data
     */
    fun endRewardSessionTracking(): Pair<List<RewardUsageTracker.AppUsageSession>, RewardUsageTracker.RewardSessionSummary>? {
        Log.d(TAG, "endRewardSessionTracking called. rewardSessionActive=$rewardSessionActive, usageTracker=${usageTracker != null}")
        if (rewardSessionActive && usageTracker != null) {
            val sessions = usageTracker!!.endRewardSession()
            val summary = usageTracker!!.getSessionSummary()
            
            Log.d(TAG, "Ended reward session. Sessions: ${sessions.size}, Total time: ${summary.totalTimeSeconds}s, Unique apps: ${summary.uniqueApps}")
            sessions.forEach { session ->
                Log.d(TAG, "  - ${session.appName} (${session.packageName}): ${session.formattedDuration}")
            }
            
            // Store for report generation
            lastRewardSessions = sessions
            lastRewardSummary = summary
            
            rewardSessionActive = false
            usageTracker = null
            Log.d(TAG, "Stored usage data in lastRewardSessions and lastRewardSummary")
            return Pair(sessions, summary)
        } else {
            Log.w(TAG, "Cannot end reward session tracking: rewardSessionActive=$rewardSessionActive, usageTracker=${usageTracker != null}")
        }
        return null
    }
    
    /**
     * Performs a single timer check iteration - called from AppBlockerService background thread.
     * This allows the reward timer to run even when BaerenLock is in the background.
     */
    fun performTimerCheck(context: Context) {
        // Only check if we have reward minutes
        if (currentRewardMinutes <= 0) {
            // No reward time - ensure timer state is reset
            if (rewardTimeStartTime != 0L) {
                Log.d(TAG, "No reward minutes, resetting timer state")
                rewardTimeStartTime = 0L
                rewardTimeStartMinutes = 0
                lastUsageCheckTime = 0L
                lastPeriodicSaveLogTime = 0L
                lastRewardDecrementTime = 0L
            }
            return
        }
        
        val now = System.currentTimeMillis()
        val oneMinuteInMillis = 60 * 1000L
        var shouldSave = false
        
        // Initialize timer state if this is the first check
        if (rewardTimeStartTime == 0L) {
            rewardTimeStartTime = now
            rewardTimeStartMinutes = currentRewardMinutes
            lastUsageCheckTime = now
            lastRewardDecrementTime = now
            Log.d(TAG, "Initialized reward timer state: startTime=$now, startMinutes=$rewardTimeStartMinutes")
            
            // Start reward session tracking for usage reporting
            startRewardSessionTracking(context)
        }
        
        // Use UsageStatsManager to track actual usage time (only counts time when reward apps are in foreground)
        val timeSinceStart = now - rewardTimeStartTime
        
        if (hasUsageStatsPermission(context)) {
            // Wait at least 30 seconds before checking UsageStats to avoid historical data
            val minimumTimeForUsageCheck = 30 * 1000L
            
            if (timeSinceStart >= minimumTimeForUsageCheck) {
                val actualUsageMinutes = getActualRewardAppUsageMinutes(context, rewardTimeStartTime, now)
                
                // Use ONLY actual usage time (which only counts time when reward apps are in foreground)
                val usageBasedRemaining = rewardTimeStartMinutes - actualUsageMinutes
                val newCurrentMinutes = usageBasedRemaining.coerceAtLeast(0)
                
                if (newCurrentMinutes != currentRewardMinutes) {
                    Log.d(TAG, "UsageStats: actualUsage=$actualUsageMinutes min, updating from $currentRewardMinutes to $newCurrentMinutes minutes")
                    currentRewardMinutes = newCurrentMinutes
                    shouldSave = true
                    lastUsageCheckTime = now
                    
                    // Notify LauncherActivity if reward time changed
                    notifyRewardTimeChanged(context)
                } else {
                    // Even if value hasn't changed, save periodically (every minute) as safety net
                    val timeSinceLastSave = now - lastUsageCheckTime
                    if (timeSinceLastSave >= oneMinuteInMillis) {
                        shouldSave = true
                        lastUsageCheckTime = now
                        // Only log periodic saves every 5 minutes to reduce log spam
                        if (now - lastPeriodicSaveLogTime >= 5 * oneMinuteInMillis) {
                            Log.d(TAG, "Periodic save (UsageStats): current=$currentRewardMinutes minutes")
                            lastPeriodicSaveLogTime = now
                        }
                    }
                }
            } else {
                // Too soon for UsageStats - don't decrement yet (prevents counting time on launcher)
                // Just save periodically to ensure state is saved
                val timeSinceLastSave = now - lastUsageCheckTime
                if (timeSinceLastSave >= oneMinuteInMillis) {
                    shouldSave = true
                    lastUsageCheckTime = now
                }
            }
        } else {
            // CRITICAL: Without UsageStats permission, we can't track time accurately
            // Log this warning periodically (every 30 seconds) to avoid log spam
            val timeSinceLastSave = now - lastUsageCheckTime
            if (timeSinceLastSave >= 30 * 1000L) {
                Log.e(TAG, "⚠️ CRITICAL: UsageStats permission NOT granted! Reward timer cannot decrement. Grant permission in Settings > Apps > BaerenLock > Permissions > Usage access")
                lastUsageCheckTime = now
            }
            // Don't decrement without UsageStats permission since we can't determine foreground state accurately
        }
        
        // Save to local storage and cloud if we need to
        if (shouldSave) {
            saveRewardMinutes(context)
        }
        
        // Check if reward time has expired
        if (currentRewardMinutes == 0) {
            handleRewardTimeExpired(context)
        }
    }
    
    /**
     * Handles reward time expiration - clears temporary apps, kills reward apps, returns to launcher
     */
    private fun handleRewardTimeExpired(context: Context) {
        Log.d(TAG, "Reward time expired. Temporary apps removed. Killing reward apps.")
        
        // Reward time is up, remove temporary apps
        RewardAppsManager.clearTemporaryApps(context)
        
        // End reward session tracking and send broadcast with usage data
        val usageData = endRewardSessionTracking()
        Log.d(TAG, "Reward time expired. Usage data: ${if (usageData != null) "available (${usageData.first.size} sessions)" else "null"}")
        
        // Notify launcher
        val intent = Intent(LauncherActivity.ACTION_REWARD_EXPIRED).apply {
            putExtra("has_usage_data", usageData != null)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        Log.d(TAG, "Sent ACTION_REWARD_EXPIRED broadcast.")
        
        // Reset timer state
        rewardTimeStartTime = 0L
        rewardTimeStartMinutes = 0
        lastUsageCheckTime = 0L
        lastPeriodicSaveLogTime = 0L
        lastRewardDecrementTime = 0L
        
        // Force return to BaerenLock launcher
        returnToLauncher(context)
        
        // Kill reward-eligible apps after a delay
        Handler(Looper.getMainLooper()).postDelayed({
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (pkg in rewardEligibleAppsSet) {
                try {
                    am.killBackgroundProcesses(pkg)
                    Log.d(TAG, "Killed reward-eligible app: $pkg")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to kill reward-eligible app $pkg: ${e.message}")
                }
            }
            Log.d(TAG, "Reward apps killed. AppBlockerService will block them if they try to restart.")
        }, 500)
    }
    
    /**
     * Returns to launcher
     */
    private fun returnToLauncher(context: Context) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(homeIntent)
            Log.d(TAG, "Launched HOME intent to return to BaerenLock launcher")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to return to launcher: ${e.message}", e)
        }
    }
    
    /**
     * Notifies LauncherActivity that reward time changed (to update UI)
     */
    private fun notifyRewardTimeChanged(context: Context) {
        val intent = Intent(RewardTimeReceiver.ACTION_REWARD_TIME_UPDATED)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
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
    
    /**
     * Checks if a reward-eligible app is currently in the foreground.
     * Uses multiple methods with fallbacks for reliability.
     */
    private fun isRewardAppInForeground(context: Context): Boolean {
        // Method 1: Check last known foreground app (updated by AppBlockerService or previous checks)
        // Use it if it was updated recently (within last 5 seconds)
        val now = System.currentTimeMillis()
        val rewardEligibleAppsSet = rewardEligibleAppsSet
        
        if (lastKnownForegroundApp != null && (now - lastForegroundAppUpdateTime) < 5000) {
            if (rewardEligibleAppsSet.contains(lastKnownForegroundApp)) {
                Log.d(TAG, "Reward app is in foreground (from cached state): $lastKnownForegroundApp")
                return true
            }
        }
        
        // Method 2: Try UsageStatsManager (requires permission)
        if (hasUsageStatsPermission(context)) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val time = System.currentTimeMillis()
                val usageEvents = usm.queryEvents(time - 10000, time) // Last 10 seconds
                val event = android.app.usage.UsageEvents.Event()
                
                var lastForegroundApp: String? = null
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                        lastForegroundApp = event.packageName
                    }
                }
                
                // Update our cached state
                if (lastForegroundApp != null) {
                    updateForegroundApp(lastForegroundApp)
                }
                
                // Check if the foreground app is a reward-eligible app
                if (lastForegroundApp != null && rewardEligibleAppsSet.contains(lastForegroundApp)) {
                    Log.d(TAG, "Reward app is in foreground (via UsageStats): $lastForegroundApp")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error using UsageStatsManager: ${e.message}")
            }
        } else {
            Log.w(TAG, "UsageStats permission not granted - cannot reliably detect foreground apps")
        }
        
        // Method 3: Fallback to ActivityManager (less reliable but doesn't require special permission)
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = am.runningAppProcesses
            if (runningProcesses != null) {
                for (process in runningProcesses) {
                    if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        val pkgName = process.processName
                        // Update our cached state
                        updateForegroundApp(pkgName)
                        
                        if (rewardEligibleAppsSet.contains(pkgName)) {
                            Log.d(TAG, "Reward app is in foreground (via ActivityManager): $pkgName")
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking foreground app via ActivityManager: ${e.message}")
        }
        
        Log.d(TAG, "No reward app detected in foreground")
        return false
    }

    /**
     * Starts the reward timer to decrement minutes and update storage.
     * Uses UsageStatsManager to track actual app usage time for accuracy.
     * Falls back to timer-based tracking if UsageStats permission is not available.
     */
    fun startRewardTimer(context: Context) {
        val hasUsageStats = hasUsageStatsPermission(context)
        Log.d(TAG, "startRewardTimer called. Current minutes: $currentRewardMinutes, timer already running: ${rewardRunnable != null}, UsageStats permission: $hasUsageStats")
        
        if (!hasUsageStats) {
            Log.e(TAG, "⚠️ CRITICAL: Starting reward timer but UsageStats permission is NOT granted! Timer will NOT decrement time. Grant permission in Settings > Apps > BaerenLock > Permissions > Usage access")
        }
        
        // If timer is already running and we have reward minutes, don't restart it
        if (rewardRunnable != null && currentRewardMinutes > 0) {
            Log.d(TAG, "Timer already running with $currentRewardMinutes minutes, skipping restart")
            return
        }
        
        // Initialize timing tracking if this is a new session (timer not running)
        val currentTime = System.currentTimeMillis()
        val isNewSession = (lastRewardDecrementTime == 0L || rewardRunnable == null)
        
        if (isNewSession) {
            lastRewardDecrementTime = currentTime
            rewardTimeStartTime = currentTime
            rewardTimeStartMinutes = currentRewardMinutes
            lastUsageCheckTime = currentTime
            Log.d(TAG, "Initialized reward timer tracking for new session. Start time: $currentTime, Start minutes: $rewardTimeStartMinutes")
            
            // Start reward session tracking for usage reporting
            startRewardSessionTracking(context)
        } else {
            Log.d(TAG, "Timer already running, continuing existing session. Last decrement: $lastRewardDecrementTime")
        }
        
        // Always remove any existing callbacks to prevent duplicate timers
        rewardRunnable?.let { rewardTimer?.removeCallbacks(it) }
        rewardTimer = Handler(Looper.getMainLooper())

        rewardRunnable = object : Runnable {
            override fun run() {
                if (currentRewardMinutes > 0) {
                    val now = System.currentTimeMillis()
                    val oneMinuteInMillis = 60 * 1000L
                    var shouldSave = false
                    
                    // Use UsageStatsManager to track actual usage time (only counts time when reward apps are in foreground)
                    // This is the correct approach since getActualRewardAppUsageMinutes already only counts foreground time
                    val timeSinceStart = now - rewardTimeStartTime
                    
                    if (hasUsageStatsPermission(context)) {
                        // Wait at least 30 seconds before checking UsageStats to avoid historical data
                        val minimumTimeForUsageCheck = 30 * 1000L
                        
                        if (timeSinceStart >= minimumTimeForUsageCheck) {
                            val actualUsageMinutes = getActualRewardAppUsageMinutes(context, rewardTimeStartTime, now)
                            
                            // Use ONLY actual usage time (which only counts time when reward apps are in foreground)
                            val usageBasedRemaining = rewardTimeStartMinutes - actualUsageMinutes
                            val newCurrentMinutes = usageBasedRemaining.coerceAtLeast(0)
                            
                            if (newCurrentMinutes != currentRewardMinutes) {
                                Log.d(TAG, "UsageStats: actualUsage=$actualUsageMinutes min, updating from $currentRewardMinutes to $newCurrentMinutes minutes")
                                currentRewardMinutes = newCurrentMinutes
                                shouldSave = true
                                lastUsageCheckTime = now
                            } else {
                                // Even if value hasn't changed, save periodically (every minute) as safety net
                                val timeSinceLastSave = now - lastUsageCheckTime
                                if (timeSinceLastSave >= oneMinuteInMillis) {
                                    shouldSave = true
                                    lastUsageCheckTime = now
                                    // Only log periodic saves every 5 minutes to reduce log spam
                                    if (now - lastPeriodicSaveLogTime >= 5 * oneMinuteInMillis) {
                                        Log.d(TAG, "Periodic save (UsageStats): current=$currentRewardMinutes minutes")
                                        lastPeriodicSaveLogTime = now
                                    }
                                }
                            }
                        } else {
                            // Too soon for UsageStats - don't decrement yet (prevents counting time on launcher)
                            // Just save periodically to ensure state is saved
                            val timeSinceLastSave = now - lastUsageCheckTime
                            if (timeSinceLastSave >= oneMinuteInMillis) {
                                shouldSave = true
                                lastUsageCheckTime = now
                            }
                        }
                    } else {
                        // CRITICAL: Without UsageStats permission, we can't track time accurately
                        // Log this warning periodically (every 30 seconds) to avoid log spam
                        val timeSinceLastSave = now - lastUsageCheckTime
                        if (timeSinceLastSave >= 30 * 1000L) {
                            Log.e(TAG, "⚠️ CRITICAL: UsageStats permission NOT granted! Reward timer cannot decrement. Grant permission in Settings > Apps > BaerenLock > Permissions > Usage access")
                            lastUsageCheckTime = now
                        }
                        // Don't decrement without UsageStats permission since we can't determine foreground state accurately
                    }
                    
                    // Save to local storage and cloud if we need to
                    if (shouldSave) {
                        saveRewardMinutes(context)
                    }

                    if (currentRewardMinutes == 0) {
                        // Reward time is up, remove temporary apps
                        RewardAppsManager.clearTemporaryApps(context)
                        Log.d(TAG, "Reward time expired. Temporary apps removed. Killing reward apps.")

                        // End reward session tracking and send broadcast with usage data
                        val usageData = endRewardSessionTracking()
                        Log.d(TAG, "Reward time expired. Usage data: ${if (usageData != null) "available (${usageData.first.size} sessions)" else "null"}")
                        
                        val intent = Intent(LauncherActivity.ACTION_REWARD_EXPIRED).apply {
                            putExtra("has_usage_data", usageData != null)
                        }
                        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                        Log.d(TAG, "Sent ACTION_REWARD_EXPIRED broadcast.")
                        
                        // Also send a separate broadcast for report generation
                        if (usageData != null) {
                            Log.d(TAG, "Usage data available: ${usageData.first.size} sessions, total time: ${usageData.second.totalTimeSeconds}s")
                            val reportIntent = Intent("com.talq2me.baerenlock.ACTION_GENERATE_REWARD_REPORT")
                            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(reportIntent)
                            Log.d(TAG, "Sent ACTION_GENERATE_REWARD_REPORT broadcast via LocalBroadcastManager")
                            
                            // Also start MainActivity to ensure it's running to receive the broadcast
                            // This is a fallback in case MainActivity isn't running
                            try {
                                val mainActivityIntent = Intent(context, com.talq2me.baerenlock.MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra("trigger_report_upload", true)
                                }
                                context.startActivity(mainActivityIntent)
                                Log.d(TAG, "Started MainActivity to ensure report upload can happen")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start MainActivity for report upload: ${e.message}", e)
                            }
                        } else {
                            Log.w(TAG, "No usage data available - skipping report generation")
                        }

                        // Force return to BaerenLock launcher FIRST (moves reward apps to background)
                        try {
                            // First, try to go home using the HOME intent (most reliable)
                            val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                                addCategory(android.content.Intent.CATEGORY_HOME)
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or 
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            context.startActivity(homeIntent)
                            Log.d(TAG, "Launched HOME intent to return to BaerenLock launcher")
                            
                            // Also try to start our launcher directly as backup (after a short delay)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    val launcherIntent = android.content.Intent(context, com.talq2me.baerenlock.LauncherActivity::class.java).apply {
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    }
                                    context.startActivity(launcherIntent)
                                    Log.d(TAG, "Launched LauncherActivity directly as backup")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start launcher directly: ${e.message}", e)
                                }
                            }, 200) // 200ms delay to let HOME intent process first
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to return to launcher after reward expiration: ${e.message}", e)
                        }
                        
                        // Kill reward-eligible apps AFTER returning to launcher (gives time for apps to move to background)
                        // Use a delay to ensure the HOME intent has processed and apps are in background
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            
                            for (pkg in rewardEligibleAppsSet) {
                                try {
                                    // Kill background processes (apps should now be in background after HOME intent)
                                    am.killBackgroundProcesses(pkg)
                                    Log.d(TAG, "Killed reward-eligible app: $pkg")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to kill reward-eligible app $pkg: ${e.message}")
                                }
                            }
                            
                            // AppBlockerService will block these apps if they try to restart (since currentRewardMinutes == 0)
                            Log.d(TAG, "Reward apps killed. AppBlockerService will block them if they try to restart.")
                        }, 500) // 500ms delay to ensure HOME intent processed and apps moved to background

                        // Stop the timer since reward time is 0
                        rewardTimer?.removeCallbacks(this)
                        rewardRunnable = null
                        lastRewardDecrementTime = 0L
                        rewardTimeStartTime = 0L
                        rewardTimeStartMinutes = 0
                        lastUsageCheckTime = 0L
                        lastPeriodicSaveLogTime = 0L
                        Log.d(TAG, "Reward timer stopped as minutes reached 0.")
                        
                        // Trigger report generation if we have usage data
                        if (usageData != null && context is android.app.Activity) {
                            (context as? MainActivity)?.generateAndUploadRewardReport(usageData.first, usageData.second)
                        }

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
                    rewardTimer?.removeCallbacks(this)
                    rewardRunnable = null
                    lastRewardDecrementTime = 0L
                    rewardTimeStartTime = 0L
                    rewardTimeStartMinutes = 0
                    lastUsageCheckTime = 0L
                    lastPeriodicSaveLogTime = 0L
                    Log.d(TAG, "Reward timer stopped.")
                }
            }
        }

        // Start the runnable immediately to process the current state and then schedule for future
        rewardTimer?.post(rewardRunnable!!)
        Log.d(TAG, "Reward timer initiated. First run scheduled immediately.")
    }

    /**
     * Gets the actual usage time (in minutes) for reward-eligible apps using UsageStatsManager.
     * This provides accurate tracking that isn't affected by timer delays or thread priorities.
     */
    private fun getActualRewardAppUsageMinutes(context: Context, startTime: Long, endTime: Long): Int {
        if (!hasUsageStatsPermission(context)) {
            return 0
        }
        
        // Safety check: if the time range is too small (less than 30 seconds), return 0
        // This prevents counting historical usage when timer first starts
        val timeRange = endTime - startTime
        if (timeRange < 30 * 1000L) {
            Log.d(TAG, "UsageStats query skipped: time range too small (${timeRange/1000}s), returning 0")
            return 0
        }
        
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            var totalUsageMillis = 0L
            
            // Query usage stats for each reward-eligible app
            for (packageName in rewardEligibleAppsSet) {
                try {
                    val usageStats = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_BEST,
                        startTime,
                        endTime
                    )
                    
                    if (usageStats != null) {
                        for (stat in usageStats) {
                            if (stat.packageName == packageName) {
                                totalUsageMillis += stat.totalTimeInForeground
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying usage stats for $packageName: ${e.message}")
                }
            }
            
            // Convert to minutes (round down)
            val usageMinutes = (totalUsageMillis / (60 * 1000)).toInt()
            // Only log detailed stats every 5 minutes to reduce log spam (queries happen every 5 seconds)
            val minutesSinceStart = (timeRange / (60 * 1000L)).toInt()
            if (minutesSinceStart > 0 && minutesSinceStart % 5 == 0) {
                Log.d(TAG, "UsageStats query: ${minutesSinceStart}min elapsed, $usageMinutes minutes used (${totalUsageMillis / 1000}s) over ${timeRange/1000}s period")
            }
            return usageMinutes
        } catch (e: Exception) {
            Log.e(TAG, "Error getting actual usage from UsageStatsManager: ${e.message}", e)
            return 0
        }
    }
}