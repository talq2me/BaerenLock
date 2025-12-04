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

    var currentRewardMinutes: Int = 0
    private var rewardTimer: Handler? = null
    private var rewardRunnable: Runnable? = null
    private var usageTracker: RewardUsageTracker? = null
    private var rewardSessionActive: Boolean = false
    private var lastRewardDecrementTime: Long = 0 // Track when we last decremented reward time
    private var rewardTimeStartTime: Long = 0 // Track when reward time session started
    private var rewardTimeStartMinutes: Int = 0 // Track how many minutes we started with (for usage-based tracking)
    private var lastUsageCheckTime: Long = 0 // Track when we last checked usage stats
    
    // Store last reward session data for report generation
    var lastRewardSessions: List<RewardUsageTracker.AppUsageSession>? = null
    var lastRewardSummary: RewardUsageTracker.RewardSessionSummary? = null

    // Fixed set of apps allowed to run in the background (memory control)
    private val memoryAllowedApps = setOf(
        "com.talq2me.baerenlock",
        "com.talq2me.baerened",
        "com.nianticlabs.pokemongo" // Pokemon GO is always allowed in memory
    )

    // Essential system packages that should never be killed
    private val essentialSystemPackages = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "android",
        "com.android.phone",
        "com.android.settings",
        "com.android.providers.settings",
        "com.android.providers.downloads",
        "com.android.providers.media",
        "com.android.providers.calendar",
        "com.android.providers.contacts",
        "com.android.packageinstaller", // For app installation/permissions
        "com.google.android.packageinstaller", // Google's package installer
        "com.android.vending", // Google Play Store
        "com.google.android.settings.intelligence" // Google-specific settings intelligence
    )

    val allowedApps = mutableSetOf(
        "com.talq2me.baerened", // BaerenEd app (will be part of memoryAllowedApps too)
        "com.talq2me.baerenlock" // BaerenLock launcher (will be part of memoryAllowedApps too)
    )
    private val temporaryApps = mutableSetOf<String>()
    val rewardEligibleApps = mutableSetOf<String>() // New set to store user-configured reward apps

    fun grantAccess(context: Context, pkg: String, minutes: Int) {
        allowedApps.add(pkg)
        temporaryApps.add(pkg)
        saveAllowedApps(context)
        
        // Start tracking reward session usage
        startRewardSessionTracking(context)

        rewardRunnable?.let { rewardTimer?.removeCallbacks(it) }
        rewardTimer = Handler(Looper.getMainLooper())
        rewardRunnable = Runnable {
            Log.d("RewardManager", "🚫 Reward time expired for $pkg - removing from allowed apps")
            allowedApps.remove(pkg)
            temporaryApps.remove(pkg)
            saveAllowedApps(context)
            Log.d("RewardManager", "Updated allowed apps: $allowedApps")

            // 🚫 Try to kill the app's background processes
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)

            // ✅ Return to launcher/home
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            intent.addCategory(android.content.Intent.CATEGORY_HOME)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
        rewardTimer?.postDelayed(rewardRunnable!!, minutes * 60 * 1000L)
    }

    fun isAllowed(pkg: String): Boolean {
        // An app is allowed if it's permanently whitelisted, temporarily allowed, OR
        // if it's a reward-eligible app AND reward minutes are currently active.
        val allowed = allowedApps.contains(pkg) ||
                      temporaryApps.contains(pkg) ||
                      (rewardEligibleApps.contains(pkg) && currentRewardMinutes > 0)
        return allowed
    }

    fun addToWhitelist(pkg: String, context: Context) {
        allowedApps.add(pkg)
        saveAllowedApps(context)
        // Automatically remove from blacklist when whitelisted
        removeFromBlacklist(pkg, context)
    }

    fun removeFromWhitelist(pkg: String, context: Context) {
        allowedApps.remove(pkg)
        saveAllowedApps(context)
    }

    private fun removeFromBlacklist(pkg: String, context: Context) {
        try {
            val prefs = context.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
            val blacklist = prefs.getStringSet("packages", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (blacklist.remove(pkg)) {
                prefs.edit().putStringSet("packages", blacklist).apply()
                Log.d("RewardManager", "Removed $pkg from blacklist (added to whitelist)")
            }
        } catch (e: Exception) {
            Log.e("RewardManager", "Error removing from blacklist", e)
        }
    }

    fun saveAllowedApps(context: Context) {
        val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
        // Only save permanent apps, not temporary reward apps
        val permanentApps = allowedApps.filter { !temporaryApps.contains(it) }.toSet()
        prefs.edit().putStringSet("allowed", permanentApps).apply()
    }

    fun loadAllowedApps(context: Context) {
        val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
        val stored = prefs.getStringSet("allowed", null)
        if (stored != null) {
            allowedApps.clear()
            allowedApps.addAll(stored)
            // Don't reload temporary apps from storage
        }

        // Always add PokemonGo if it's installed (for launcher display)
        if (isPackageInstalled(context, "com.nianticlabs.pokemongo")) {
            allowedApps.add("com.nianticlabs.pokemongo")
        }

        // Also add Baeren (web app) if it's installed (for launcher display)
        if (isPackageInstalled(context, "com.talq2me.baeren")) {
            allowedApps.add("com.talq2me.baeren")
        }

        // Load user-configured reward-eligible apps
        val rewardPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedRewardApps = rewardPrefs.getStringSet("reward_apps", emptySet()) ?: emptySet()
        rewardEligibleApps.clear()
        rewardEligibleApps.addAll(savedRewardApps)
        Log.d("RewardManager", "Loaded reward eligible apps: $rewardEligibleApps")
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun killUnauthorizedBackgroundApps(context: Context) {
        try {
            // Check if aggressive cleanup is enabled
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val aggressiveCleanup = prefs.getBoolean("aggressive_cleanup", true)

            if (!aggressiveCleanup) {
                Log.d("RewardManager", "Aggressive cleanup disabled, skipping background app cleanup")
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
                if (doNotKillList.contains(packageName) ||
                    packageName.startsWith("com.android.") ||
                    packageName.startsWith("android.")) {
                    Log.d("RewardManager", "Skipping whitelisted/system app from killing: $packageName")
                    continue
                }

                // Kill unauthorized background processes
                try {
                    Log.d("RewardManager", "Killing unauthorized background app: $packageName")
                    activityManager.killBackgroundProcesses(packageName)
                    killedCount++
                } catch (e: Exception) {
                    Log.w("RewardManager", "Failed to kill process $packageName: ${e.message}")
                }
            }

            if (killedCount > 0) {
                Log.d("RewardManager", "Killed $killedCount unauthorized background apps")
            }

        } catch (e: Exception) {
            Log.e("RewardManager", "Error killing background apps", e)
        }
    }

    fun killUnauthorizedBackgroundAppsWithCount(context: Context): Int {
        try {
            // Check if aggressive cleanup is enabled
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val aggressiveCleanup = prefs.getBoolean("aggressive_cleanup", true)

            if (!aggressiveCleanup) {
                Log.d("RewardManager", "Aggressive cleanup disabled, skipping background app cleanup")
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
                    Log.d("RewardManager", "Killing unauthorized background app: $packageName")
                    activityManager.killBackgroundProcesses(packageName)
                    killedCount++
                } catch (e: Exception) {
                    Log.w("RewardManager", "Failed to kill process $packageName: ${e.message}")
                }
            }

            if (killedCount > 0) {
                Log.d("RewardManager", "Killed $killedCount unauthorized background apps")
            }

            return killedCount

        } catch (e: Exception) {
            Log.e("RewardManager", "Error killing background apps", e)
            return 0
        }
    }

    fun isBackgroundAppAllowed(packageName: String): Boolean {
        // Allow essential system packages and our memory-whitelisted apps
        return essentialSystemPackages.contains(packageName) ||
               packageName.startsWith("com.android.") ||
               packageName.startsWith("android.") ||
               memoryAllowedApps.contains(packageName) // Check against the fixed memory whitelist
               // isAllowed(packageName) // isAllowed is for launcher display, not background memory control
    }

    fun addPokemonGoIfInstalled(context: Context) {
        // This function adds PokemonGo to the launcher's 'allowedApps' (if it's not a reward app)
        // It does not affect memoryAllowedApps as that's a fixed set.
        if (isPackageInstalled(context, "com.nianticlabs.pokemongo")) {
            allowedApps.add("com.nianticlabs.pokemongo")
            Log.d("RewardManager", "PokemonGo is installed and added to allowed apps for launcher display")
        }
    }

    fun getAllowedAppsList(): Set<String> {
        // This returns the set of apps that should be visible in the launcher's whitelist
        // It includes permanently allowed apps and currently active temporary reward apps.
        return allowedApps.toSet()
    }

    fun refreshRewardEligibleApps(context: Context) {
        val rewardPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedRewardApps = rewardPrefs.getStringSet("reward_apps", emptySet()) ?: emptySet()
        val oldRewardApps = rewardEligibleApps.toSet()
        rewardEligibleApps.clear()
        rewardEligibleApps.addAll(savedRewardApps)
        
        // Remove newly added reward apps from blacklist
        savedRewardApps.forEach { pkg ->
            if (!oldRewardApps.contains(pkg)) {
                removeFromBlacklist(pkg, context)
            }
        }
        
        Log.d("RewardManager", "Refreshed reward eligible apps: $rewardEligibleApps")
    }

    fun saveRewardMinutes(context: Context) {
        val prefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt("current_reward_minutes", currentRewardMinutes)
        // Save today's date to track daily reset
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        editor.putLong("last_reward_date", today)
        editor.apply()
        Log.d("RewardManager", "Saved reward minutes to SharedPreferences: $currentRewardMinutes, date: $today")
    }

    fun loadRewardMinutes(context: Context) {
        val prefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)
        
        // Check if we need to reset for a new day
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val lastRewardDate = prefs.getLong("last_reward_date", 0L)
        
        // Reset to 0 if it's a new day (including first time load when lastRewardDate is 0L)
        if (lastRewardDate != today) {
            // It's a new day - reset reward minutes to 0
            Log.d("RewardManager", "New day detected (last: $lastRewardDate, today: $today). Resetting reward minutes to 0.")
            currentRewardMinutes = 0
            saveRewardMinutes(context) // This will also update the date
        } else {
            // Same day - load the saved minutes
            currentRewardMinutes = prefs.getInt("current_reward_minutes", 0)
            Log.d("RewardManager", "Loaded reward minutes from SharedPreferences: $currentRewardMinutes (same day)")
        }
        
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
            
            Log.d("RewardManager", "Updated start minutes: added $addedMinutes, actual usage was $actualUsageMinutes, new start: $rewardTimeStartMinutes minutes")
        } else {
            // No active session, just set the start values
            rewardTimeStartMinutes = currentRewardMinutes
            Log.d("RewardManager", "Set start minutes for new session: $rewardTimeStartMinutes minutes")
        }
    }

    /**
     * Checks if a transaction ID has already been processed.
     * This prevents double-counting when both Intent and Broadcast are received.
     */
    fun isTransactionProcessed(context: Context, transactionId: Long): Boolean {
        val prefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)
        val processedIds = prefs.getStringSet("processed_transaction_ids", mutableSetOf()) ?: mutableSetOf()
        return processedIds.contains(transactionId.toString())
    }

    /**
     * Marks a transaction ID as processed to prevent double-counting.
     * Also cleans up old transaction IDs (older than 24 hours) to prevent unbounded growth.
     */
    fun markTransactionProcessed(context: Context, transactionId: Long) {
        val prefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)
        val processedIds = mutableSetOf<String>()
        processedIds.addAll(prefs.getStringSet("processed_transaction_ids", mutableSetOf()) ?: mutableSetOf())
        
        // Add the new transaction ID
        processedIds.add(transactionId.toString())
        
        // Clean up old transaction IDs (older than 24 hours)
        val currentTime = System.currentTimeMillis()
        val oneDayInMillis = 24 * 60 * 60 * 1000L
        val cleanedIds = processedIds.filter { id ->
            val idTime = id.toLongOrNull() ?: 0L
            currentTime - idTime < oneDayInMillis
        }.toSet()
        
        prefs.edit()
            .putStringSet("processed_transaction_ids", cleanedIds)
            .apply()
        
        Log.d("RewardManager", "Marked transaction ID $transactionId as processed. Total tracked: ${cleanedIds.size}")
    }

    // Track the last known foreground app (updated by AppBlockerService or our own checks)
    private var lastKnownForegroundApp: String? = null
    private var lastForegroundAppUpdateTime: Long = 0
    
    /**
     * Updates the last known foreground app. Can be called by AppBlockerService or internally.
     */
    fun updateForegroundApp(packageName: String?) {
        if (packageName != null) {
            lastKnownForegroundApp = packageName
            lastForegroundAppUpdateTime = System.currentTimeMillis()
            Log.d("RewardManager", "Updated last known foreground app: $packageName")
            
            // Track app usage if reward session is active
            if (rewardSessionActive && currentRewardMinutes > 0) {
                usageTracker?.onAppForeground(packageName)
                Log.d("RewardManager", "Tracking foreground app during reward time: $packageName")
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
            Log.d("RewardManager", "Started reward session tracking")
        } else {
            Log.d("RewardManager", "Reward session tracking already active, skipping start")
        }
    }
    
    /**
     * Ends reward session tracking and returns the usage data
     */
    fun endRewardSessionTracking(): Pair<List<RewardUsageTracker.AppUsageSession>, RewardUsageTracker.RewardSessionSummary>? {
        Log.d("RewardManager", "endRewardSessionTracking called. rewardSessionActive=$rewardSessionActive, usageTracker=${usageTracker != null}")
        if (rewardSessionActive && usageTracker != null) {
            val sessions = usageTracker!!.endRewardSession()
            val summary = usageTracker!!.getSessionSummary()
            
            Log.d("RewardManager", "Ended reward session. Sessions: ${sessions.size}, Total time: ${summary.totalTimeSeconds}s, Unique apps: ${summary.uniqueApps}")
            sessions.forEach { session ->
                Log.d("RewardManager", "  - ${session.appName} (${session.packageName}): ${session.formattedDuration}")
            }
            
            // Store for report generation
            lastRewardSessions = sessions
            lastRewardSummary = summary
            
            rewardSessionActive = false
            usageTracker = null
            Log.d("RewardManager", "Stored usage data in lastRewardSessions and lastRewardSummary")
            return Pair(sessions, summary)
        } else {
            Log.w("RewardManager", "Cannot end reward session tracking: rewardSessionActive=$rewardSessionActive, usageTracker=${usageTracker != null}")
        }
        return null
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
            Log.w("RewardManager", "Error checking UsageStats permission: ${e.message}")
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
        if (lastKnownForegroundApp != null && (now - lastForegroundAppUpdateTime) < 5000) {
            if (rewardEligibleApps.contains(lastKnownForegroundApp)) {
                Log.d("RewardManager", "Reward app is in foreground (from cached state): $lastKnownForegroundApp")
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
                if (lastForegroundApp != null && rewardEligibleApps.contains(lastForegroundApp)) {
                    Log.d("RewardManager", "Reward app is in foreground (via UsageStats): $lastForegroundApp")
                    return true
                }
            } catch (e: Exception) {
                Log.w("RewardManager", "Error using UsageStatsManager: ${e.message}")
            }
        } else {
            Log.w("RewardManager", "UsageStats permission not granted - cannot reliably detect foreground apps")
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
                        
                        if (rewardEligibleApps.contains(pkgName)) {
                            Log.d("RewardManager", "Reward app is in foreground (via ActivityManager): $pkgName")
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("RewardManager", "Error checking foreground app via ActivityManager: ${e.message}")
        }
        
        Log.d("RewardManager", "No reward app detected in foreground")
        return false
    }

    /**
     * Starts the reward timer to decrement minutes and update storage.
     * Uses UsageStatsManager to track actual app usage time for accuracy.
     * Falls back to timer-based tracking if UsageStats permission is not available.
     */
    fun startRewardTimer(context: Context) {
        Log.d("RewardManager", "startRewardTimer called. Current minutes: $currentRewardMinutes, timer already running: ${rewardRunnable != null}")
        
        // If timer is already running and we have reward minutes, don't restart it
        if (rewardRunnable != null && currentRewardMinutes > 0) {
            Log.d("RewardManager", "Timer already running with $currentRewardMinutes minutes, skipping restart")
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
            Log.d("RewardManager", "Initialized reward timer tracking for new session. Start time: $currentTime, Start minutes: $rewardTimeStartMinutes")
        } else {
            Log.d("RewardManager", "Timer already running, continuing existing session. Last decrement: $lastRewardDecrementTime")
        }
        
        // Always remove any existing callbacks to prevent duplicate timers
        rewardRunnable?.let { rewardTimer?.removeCallbacks(it) }
        rewardTimer = Handler(Looper.getMainLooper())

        rewardRunnable = object : Runnable {
            override fun run() {
                if (currentRewardMinutes > 0) {
                    val now = System.currentTimeMillis()
                    
                    // Try to use UsageStatsManager for accurate tracking (if permission available)
                    if (hasUsageStatsPermission(context)) {
                        // Only calculate usage if enough time has passed since timer started
                        // This prevents counting historical usage data when timer first starts
                        val timeSinceStart = now - rewardTimeStartTime
                        val minimumTimeForUsageCheck = 30 * 1000L // Wait at least 30 seconds before checking UsageStats
                        
                        if (timeSinceStart >= minimumTimeForUsageCheck) {
                            val actualUsageMinutes = getActualRewardAppUsageMinutes(context, rewardTimeStartTime, now)
                            val expectedMinutesUsed = rewardTimeStartMinutes - currentRewardMinutes
                            val actualMinutesUsed = actualUsageMinutes
                            
                            Log.d("RewardManager", "UsageStats check: timeSinceStart=${timeSinceStart/1000}s, actualUsage=$actualMinutesUsed min, expected=$expectedMinutesUsed min, current=$currentRewardMinutes min, start=$rewardTimeStartMinutes min")
                            
                            // Safety check: Don't allow UsageStats to reduce minutes more than the time that has actually passed
                            // This prevents historical usage data from incorrectly reducing minutes
                            val maxPossibleUsageMinutes = ((timeSinceStart / (60 * 1000L)) + 1).toInt() // Round up to be safe
                            val safeActualUsage = actualMinutesUsed.coerceAtMost(maxPossibleUsageMinutes)
                            
                            if (actualMinutesUsed > maxPossibleUsageMinutes) {
                                Log.w("RewardManager", "UsageStats returned $actualMinutesUsed minutes but only ${timeSinceStart/1000}s (${maxPossibleUsageMinutes} max) have passed. Using safe value: $safeActualUsage")
                            }
                            
                            // Update current minutes based on actual usage (using safe value)
                            val newCurrentMinutes = rewardTimeStartMinutes - safeActualUsage
                            if (newCurrentMinutes != currentRewardMinutes) {
                                val difference = currentRewardMinutes - newCurrentMinutes
                                if (difference > 0) {
                                    Log.d("RewardManager", "UsageStats: Actual usage shows $safeActualUsage minutes used (expected: $expectedMinutesUsed). Adjusting from $currentRewardMinutes to $newCurrentMinutes minutes")
                                }
                                currentRewardMinutes = newCurrentMinutes.coerceAtLeast(0)
                                saveRewardMinutes(context)
                                lastUsageCheckTime = now
                            }
                        } else {
                            // Too soon to check UsageStats, just log and continue
                            Log.d("RewardManager", "UsageStats check skipped: only ${timeSinceStart/1000}s since timer started (need ${minimumTimeForUsageCheck/1000}s). Current minutes: $currentRewardMinutes")
                        }
                    } else {
                        // Fallback to timer-based tracking if UsageStats not available
                        val timeSinceLastDecrement = now - lastRewardDecrementTime
                        val oneMinuteInMillis = 60 * 1000L
                        
                        // Only decrement if a reward app is actually in the foreground
                        // AND at least 1 minute has elapsed since last decrement
                        if (isRewardAppInForeground(context)) {
                            if (timeSinceLastDecrement >= oneMinuteInMillis) {
                                // Calculate how many full minutes have elapsed
                                val minutesToDecrement = (timeSinceLastDecrement / oneMinuteInMillis).toInt()
                                currentRewardMinutes -= minutesToDecrement
                                if (currentRewardMinutes < 0) currentRewardMinutes = 0
                                
                                // Update the last decrement time to account for the time we just decremented
                                lastRewardDecrementTime += (minutesToDecrement * oneMinuteInMillis)
                                
                                saveRewardMinutes(context)
                                Log.d("RewardManager", "Reward app in foreground - decremented $minutesToDecrement minute(s). Remaining: $currentRewardMinutes minutes")
                            } else {
                                // Not enough time has passed yet, but app is in foreground
                                val secondsRemaining = ((oneMinuteInMillis - timeSinceLastDecrement) / 1000).toInt()
                                Log.d("RewardManager", "Reward app in foreground - $secondsRemaining seconds until next decrement (remaining: $currentRewardMinutes minutes)")
                            }
                        } else {
                            // No reward app in foreground - don't decrement, but update last decrement time
                            // to prevent accumulating time when app is not in foreground
                            lastRewardDecrementTime = now
                            Log.d("RewardManager", "No reward app in foreground - skipping decrement (remaining: $currentRewardMinutes minutes)")
                        }
                    }

                    if (currentRewardMinutes == 0) {
                        // Reward time is up, remove temporary apps
                        allowedApps.removeAll(temporaryApps)
                        temporaryApps.clear()
                        saveAllowedApps(context)
                        Log.d("RewardManager", "Reward time expired. Temporary apps removed. Killing reward apps.")

                        // End reward session tracking and send broadcast with usage data
                        val usageData = endRewardSessionTracking()
                        Log.d("RewardManager", "Reward time expired. Usage data: ${if (usageData != null) "available (${usageData.first.size} sessions)" else "null"}")
                        
                        val intent = Intent(LauncherActivity.ACTION_REWARD_EXPIRED).apply {
                            putExtra("has_usage_data", usageData != null)
                        }
                        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                        Log.d("RewardManager", "Sent ACTION_REWARD_EXPIRED broadcast.")
                        
                        // Also send a separate broadcast for report generation
                        if (usageData != null) {
                            Log.d("RewardManager", "Usage data available: ${usageData.first.size} sessions, total time: ${usageData.second.totalTimeSeconds}s")
                            val reportIntent = Intent("com.talq2me.baerenlock.ACTION_GENERATE_REWARD_REPORT")
                            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(reportIntent)
                            Log.d("RewardManager", "Sent ACTION_GENERATE_REWARD_REPORT broadcast via LocalBroadcastManager")
                            
                            // Also start MainActivity to ensure it's running to receive the broadcast
                            // This is a fallback in case MainActivity isn't running
                            try {
                                val mainActivityIntent = Intent(context, com.talq2me.baerenlock.MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra("trigger_report_upload", true)
                                }
                                context.startActivity(mainActivityIntent)
                                Log.d("RewardManager", "Started MainActivity to ensure report upload can happen")
                            } catch (e: Exception) {
                                Log.e("RewardManager", "Failed to start MainActivity for report upload: ${e.message}", e)
                            }
                        } else {
                            Log.w("RewardManager", "No usage data available - skipping report generation")
                        }

                        // Kill reward-eligible apps that were granted access
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        for (pkg in rewardEligibleApps) {
                            try {
                                am.killBackgroundProcesses(pkg)
                                Log.d("RewardManager", "Killed reward-eligible app: $pkg")
                            } catch (e: Exception) {
                                Log.w("RewardManager", "Failed to kill reward-eligible app $pkg: ${e.message}")
                            }
                        }

                        // Force return to BaerenLock launcher
                        try {
                            // First, try to go home using the HOME intent (most reliable)
                            val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                                addCategory(android.content.Intent.CATEGORY_HOME)
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or 
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            context.startActivity(homeIntent)
                            Log.d("RewardManager", "Launched HOME intent to return to BaerenLock launcher")
                            
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
                                    Log.d("RewardManager", "Launched LauncherActivity directly as backup")
                                } catch (e: Exception) {
                                    Log.e("RewardManager", "Failed to start launcher directly: ${e.message}", e)
                                }
                            }, 200) // 200ms delay to let HOME intent process first
                            
                        } catch (e: Exception) {
                            Log.e("RewardManager", "Failed to return to launcher after reward expiration: ${e.message}", e)
                        }

                        // Stop the timer since reward time is 0
                        rewardTimer?.removeCallbacks(this)
                        rewardRunnable = null
                        lastRewardDecrementTime = 0L
                        rewardTimeStartTime = 0L
                        rewardTimeStartMinutes = 0
                        lastUsageCheckTime = 0L
                        Log.d("RewardManager", "Reward timer stopped as minutes reached 0.")
                        
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
                    Log.d("RewardManager", "Reward timer stopped.")
                }
            }
        }

        // Start the runnable immediately to process the current state and then schedule for future
        rewardTimer?.post(rewardRunnable!!)
        Log.d("RewardManager", "Reward timer initiated. First run scheduled immediately.")
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
            Log.d("RewardManager", "UsageStats query skipped: time range too small (${timeRange/1000}s), returning 0")
            return 0
        }
        
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            var totalUsageMillis = 0L
            
            Log.d("RewardManager", "Querying UsageStats from ${java.util.Date(startTime)} to ${java.util.Date(endTime)} (${timeRange/1000}s range)")
            
            // Query usage stats for each reward-eligible app
            for (packageName in rewardEligibleApps) {
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
                                Log.d("RewardManager", "UsageStats for $packageName: ${stat.totalTimeInForeground / 1000} seconds")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("RewardManager", "Error querying usage stats for $packageName: ${e.message}")
                }
            }
            
            // Convert to minutes (round down)
            val usageMinutes = (totalUsageMillis / (60 * 1000)).toInt()
            Log.d("RewardManager", "Total actual usage: $usageMinutes minutes (${totalUsageMillis / 1000} seconds) over ${timeRange/1000}s period")
            return usageMinutes
        } catch (e: Exception) {
            Log.e("RewardManager", "Error getting actual usage from UsageStatsManager: ${e.message}", e)
            return 0
        }
    }
}