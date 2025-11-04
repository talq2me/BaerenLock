package com.talq2me.baerenlock

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

object RewardManager {
    val allowedApps = mutableSetOf(
        "com.talq2me.baerened", // BaerenEd app (will be part of memoryAllowedApps too)
        "com.talq2me.baerenlock" // BaerenLock launcher (will be part of memoryAllowedApps too)
    )
    private val temporaryApps = mutableSetOf<String>()
    private var timer: Handler? = null
    private var runnable: Runnable? = null

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
        "com.android.providers.contacts"
    )

    fun grantAccess(context: Context, pkg: String, minutes: Int) {
        allowedApps.add(pkg)
        temporaryApps.add(pkg)
        saveAllowedApps(context)

        runnable?.let { timer?.removeCallbacks(it) }
        timer = Handler(Looper.getMainLooper())
        runnable = Runnable {
            Log.d("RewardManager", "🚫 Reward time expired for $pkg - removing from allowed apps")
            allowedApps.remove(pkg)
            temporaryApps.remove(pkg)
            saveAllowedApps(context)
            Log.d("RewardManager", "Updated allowed apps: $allowedApps")

            // 🚫 Try to kill the app's background processes
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)

            // ✅ Return to launcher/home
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
        timer?.postDelayed(runnable!!, minutes * 60 * 1000L)
    }

    fun isAllowed(pkg: String): Boolean {
        val allowed = allowedApps.contains(pkg)
        Log.d("RewardManager", "Checking if $pkg is allowed: $allowed (current allowed apps: $allowedApps)")
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

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager

            // Get running app processes
            val runningProcesses = activityManager.runningAppProcesses ?: return

            var killedCount = 0
            for (process in runningProcesses) {
                val packageName = process.processName

                // Skip apps explicitly allowed in memory (BaerenLock, BaerenEd, PokemonGo)
                if (memoryAllowedApps.contains(packageName)) {
                    continue
                }

                // Skip essential system processes (e.g., system UI, launcher, Android OS)
                if (essentialSystemPackages.contains(packageName) ||
                    packageName.startsWith("com.android.") ||
                    packageName.startsWith("android.")) {
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

    /**
     * Checks for pending reward time from BaerenEd and processes it
     */
    fun checkForPendingRewardTime(context: Context): Boolean {
        val pendingReward = getPendingRewardData(context)
        if (pendingReward != null) {
            val (minutes, timestamp) = pendingReward
            Log.d("RewardManager", "Found pending reward: $minutes minutes from ${java.util.Date(timestamp)}")

            // Clear the pending data since we're about to use it
            clearPendingRewardData(context)

            // Grant access to reward apps for the specified time
            grantRewardTime(context, minutes)
            return true
        }
        return false
    }

    /**
     * Gets pending reward data from shared file
     */
    private fun getPendingRewardData(context: Context): Pair<Int, Long>? {
        return try {
            val sharedFile = getSharedRewardFile(context)
            if (!sharedFile.exists()) return null

            val content = sharedFile.readText()
            val lines = content.lines()
            if (lines.size >= 2) {
                val rewardMinutes = lines[0].toIntOrNull() ?: 0
                val timestamp = lines[1].toLongOrNull() ?: 0L

                if (rewardMinutes > 0 && timestamp > 0) {
                    // Check if data is not too old (more than 24 hours)
                    val currentTime = System.currentTimeMillis()
                    val oneDayInMillis = 24 * 60 * 60 * 1000L
                    if (currentTime - timestamp < oneDayInMillis) {
                        return Pair(rewardMinutes, timestamp)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e("RewardManager", "Error reading shared reward file", e)
            null
        }
    }

    /**
     * Clears pending reward data from shared file
     */
    private fun clearPendingRewardData(context: Context) {
        try {
            val sharedFile = getSharedRewardFile(context)
            if (sharedFile.exists()) {
                sharedFile.delete()
            }
        } catch (e: Exception) {
            Log.e("RewardManager", "Error clearing shared reward file", e)
        }
    }

    /**
     * Gets the shared reward file that both apps can access
     */
    private fun getSharedRewardFile(context: Context): File {
        // Use external files directory if available, otherwise use internal
        val externalDir = context.getExternalFilesDir(null)
        val sharedDir = if (externalDir != null) {
            File(externalDir, "shared")
        } else {
            File(context.filesDir, "shared")
        }

        if (!sharedDir.exists()) {
            sharedDir.mkdirs()
        }

        return File(sharedDir, "baeren_reward_data.txt")
    }

    /**
     * Grants reward time to all reward-eligible apps
     */
    private fun grantRewardTime(context: Context, minutes: Int) {
        // Grant access to reward apps (you can customize which apps get reward time)
        val rewardPackages = listOf(
            "com.nianticlabs.pokemongo",  // Pokemon GO
            "com.roblox.client",          // Roblox
            "com.mojang.minecraftpe",     // Minecraft
            // Add other reward-eligible apps here
        )

        for (pkg in rewardPackages) {
            if (isPackageInstalled(context, pkg)) {
                Log.d("RewardManager", "Granting $minutes minutes of reward time to $pkg")
                grantAccess(context, pkg, minutes)
            }
        }
    }
}