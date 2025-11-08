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

object RewardManager {

    var currentRewardMinutes: Int = 0
    private var rewardTimer: Handler? = null
    private var rewardRunnable: Runnable? = null

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
    }

    fun removeFromWhitelist(pkg: String, context: Context) {
        allowedApps.remove(pkg)
        saveAllowedApps(context)
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
        rewardEligibleApps.clear()
        rewardEligibleApps.addAll(savedRewardApps)
        Log.d("RewardManager", "Refreshed reward eligible apps: $rewardEligibleApps")
    }

    fun saveRewardMinutes(context: Context) {
        val prefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("current_reward_minutes", currentRewardMinutes).apply()
        Log.d("RewardManager", "Saved reward minutes to SharedPreferences: $currentRewardMinutes")
    }

    fun loadRewardMinutes(context: Context) {
        val prefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)
        currentRewardMinutes = prefs.getInt("current_reward_minutes", 0)
        Log.d("RewardManager", "Loaded reward minutes from SharedPreferences: $currentRewardMinutes")
        if (currentRewardMinutes > 0) {
            startRewardTimer(context)
        }
    }

    /**
     * Starts the reward timer to decrement minutes and update storage.
     */
    fun startRewardTimer(context: Context) {
        Log.d("RewardManager", "startRewardTimer called. Current minutes: $currentRewardMinutes")
        
        // Always remove any existing callbacks to prevent duplicate timers
        rewardRunnable?.let { rewardTimer?.removeCallbacks(it) }
        rewardTimer = Handler(Looper.getMainLooper())

        rewardRunnable = object : Runnable {
            override fun run() {
                if (currentRewardMinutes > 0) {
                    currentRewardMinutes -= 1 // Decrement every minute
                    if (currentRewardMinutes < 0) currentRewardMinutes = 0
                    saveRewardMinutes(context)
                    Log.d("RewardManager", "Reward minutes decremented to: $currentRewardMinutes. Rescheduling timer.")

                    if (currentRewardMinutes == 0) {
                        // Reward time is up, remove temporary apps
                        allowedApps.removeAll(temporaryApps)
                        temporaryApps.clear()
                        saveAllowedApps(context)
                        Log.d("RewardManager", "Reward time expired. Temporary apps removed. Killing reward apps.")

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

                        // Send broadcast to LauncherActivity to refresh UI
                        val intent = Intent(LauncherActivity.ACTION_REWARD_EXPIRED)
                        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                        Log.d("RewardManager", "Sent ACTION_REWARD_EXPIRED broadcast.")

                        // Stop the timer since reward time is 0
                        rewardTimer?.removeCallbacks(this)
                        rewardRunnable = null
                        Log.d("RewardManager", "Reward timer stopped as minutes reached 0.")

                    } else {
                        // Schedule next decrement for 1 minute
                        rewardTimer?.postDelayed(this, 1 * 60 * 1000L)
                    }
                } else {
                    // No reward time left, stop the timer
                    rewardTimer?.removeCallbacks(this)
                    rewardRunnable = null
                    Log.d("RewardManager", "Reward timer stopped.")
                }
            }
        }

        // Start the runnable immediately to process the current state and then schedule for future
        rewardTimer?.post(rewardRunnable!!)
        Log.d("RewardManager", "Reward timer initiated. First run scheduled immediately.")
    }
}