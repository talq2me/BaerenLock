package com.talq2me.baerenlock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.os.Handler
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.app.usage.UsageEvents
import android.provider.Settings
import android.os.Build
import com.talq2me.baerenlock.RewardManager
import com.talq2me.baerenlock.DevicePolicyManager
import com.talq2me.baerenlock.LauncherActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.os.HandlerThread

class AppBlockerService : AccessibilityService() {

    private var lastPackage: String? = null
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private val periodicCheck = object : Runnable {
        override fun run() {
            checkForegroundApp()
            backgroundHandler.postDelayed(this, 2000) // Check every 2 seconds
        }
    }

    private val usageCheck = object : Runnable {
        override fun run() {
            checkUsageStats()
            backgroundHandler.postDelayed(this, 2000)
        }
    }

    private val backgroundCleanupCheck = object : Runnable {
        override fun run() {
            cleanupUnauthorizedBackgroundApps()
            backgroundHandler.postDelayed(this, 30000) // Check every 30 seconds
        }
    }

    private lateinit var devicePolicyManager: com.talq2me.baerenlock.DevicePolicyManager
    private var blockedPackages = mutableSetOf<String>()

    private val CHANNEL_ID = "AppBlockerServiceChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        backgroundThread = HandlerThread("AppBlockerBackground").apply {
            start()
        }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Log all events for debugging
        Log.d("AppBlocker", "Event received: type=${event.eventType}, package=${event.packageName}, class=${event.className}")
        
        // Listen to more event types to catch all app switches
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && 
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return
        }

        val pkgName = event.packageName?.toString() ?: return

        // Only block if it's a reward-eligible app and reward minutes are 0
        if (RewardManager.rewardEligibleApps.contains(pkgName) && RewardManager.currentRewardMinutes <= 0) {
            Log.d("AppBlocker", "🚫 BLOCKING expired reward app: $pkgName - returning to launcher")
            lastPackage = pkgName

            // Use device owner capabilities if available for stronger blocking
            if (devicePolicyManager.isDeviceOwnerActive()) {
                Log.d("AppBlocker", "Using device owner to disable app: $pkgName")
                devicePolicyManager.disableApp(pkgName)
            }

            returnToLauncher()
            return
        }

        // For all other apps (system, non-reward, or reward with time remaining), do nothing.
        return
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AppBlocker", "Accessibility service connected and ready to block apps")

        // Start foreground service
        startForeground(NOTIFICATION_ID, getNotification())

        // Initialize Device Policy Manager
        devicePolicyManager = com.talq2me.baerenlock.DevicePolicyManager.getInstance(this)

        // Load blocked packages from settings
        loadBlockedPackages()

        backgroundHandler.post(periodicCheck)
        // Start UsageStats polling
        if (!hasUsageStatsPermission()) {
            Log.d("AppBlocker", "USAGESTATS: UsageStats permission NOT granted. Prompting user.")
            promptForUsageAccess()
        } else {
            Log.d("AppBlocker", "USAGESTATS: UsageStats permission granted. Starting usage check.")
            backgroundHandler.post(usageCheck)
        }

        // Start background app cleanup
        backgroundHandler.post(backgroundCleanupCheck)

        // Ensure RewardManager's timer is started if there are reward minutes
        // Removed as timer management is now centralized in LauncherActivity.onResume()
        // if (RewardManager.currentRewardMinutes > 0) {
        //     Log.d("AppBlocker", "onServiceConnected: Reward minutes present (${RewardManager.currentRewardMinutes} min). Starting RewardManager timer.")
        //     RewardManager.startRewardTimer(this)
        // } else {
        //     Log.d("AppBlocker", "onServiceConnected: No reward minutes present. Not starting RewardManager timer.")
        // }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundHandler.removeCallbacks(periodicCheck)
        backgroundHandler.removeCallbacks(usageCheck)
        backgroundHandler.removeCallbacks(backgroundCleanupCheck)
        backgroundThread.quitSafely()
        stopForeground(true)
    }

    private fun checkForegroundApp() {
        try {
            //Log.d("AppBlocker", "Periodic check running...")
            
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses
            
            if (processes != null) {
                for (process in processes) {
                    if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        val pkgName = process.processName
                        Log.d("AppBlocker", "Foreground process: $pkgName")

                        // Only block if it's a reward-eligible app and reward minutes are 0
                        if (RewardManager.rewardEligibleApps.contains(pkgName) && RewardManager.currentRewardMinutes <= 0) {
                            Log.d("AppBlocker", "🚫 PERIODIC CHECK - BLOCKING expired reward app: $pkgName - returning to launcher")

                            // Use device owner capabilities if available for stronger blocking
                            if (devicePolicyManager.isDeviceOwnerActive()) {
                                Log.d("AppBlocker", "Using device owner to disable app: $pkgName")
                                devicePolicyManager.disableApp(pkgName)
                            }

                            returnToLauncher()
                            return
                        }
                        // For all other apps, do nothing
                        return
                    }
                }
            } else {
                Log.d("AppBlocker", "No running processes found")
            }
            
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error in periodic check", e)
        }
    }

    private fun returnToLauncher() {
        Log.d("AppBlocker", "Returning to launcher...")
        val intent = Intent(this, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun hasUsageStatsPermission(): Boolean {
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
            } else {
                appOps.checkOpNoThrow(
                    "android:get_usage_stats",
                    android.os.Process.myUid(),
                    packageName
                )
            }
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            return false
        }
    }

    private fun promptForUsageAccess() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun checkUsageStats() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - (15 * 60 * 1000) // last 15 minutes
            val events = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            var lastForeground: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                Log.d("AppBlocker", "USAGESTATS: Raw Event - Type: ${event.eventType}, Package: ${event.packageName}, Class: ${event.className}, Timestamp: ${event.timeStamp}")
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastForeground = event.packageName
                    Log.d("AppBlocker", "USAGESTATS: ACTIVITY_RESUMED: $lastForeground")
                }
            }
            val pkgName = lastForeground ?: run {
                Log.d("AppBlocker", "USAGESTATS: No foreground app detected (check recent 15min)")
                return
            }
            Log.d("AppBlocker", "USAGESTATS: Last foreground app: $pkgName")
            
            // Only block if it's a reward-eligible app and reward minutes are 0
            if (RewardManager.rewardEligibleApps.contains(pkgName) && RewardManager.currentRewardMinutes <= 0) {
                Log.d("AppBlocker", "USAGESTATS - BLOCKING expired reward app: $pkgName - returning to launcher")

                // Use device owner capabilities if available for stronger blocking
                if (devicePolicyManager.isDeviceOwnerActive()) {
                    Log.d("AppBlocker", "Using device owner to disable app: $pkgName")
                    devicePolicyManager.disableApp(pkgName)
                }

                returnToLauncher()
                return
            }
            // For all other apps, do nothing
            return
        } catch (e: Exception) {
            Log.e("AppBlocker", "USAGESTATS: Error in checkUsageStats", e)
        }
    }

    private fun cleanupUnauthorizedBackgroundApps() {
        try {
            Log.d("AppBlocker", "Running background app cleanup...")

            // Use RewardManager to kill unauthorized background apps
            com.talq2me.baerenlock.RewardManager.killUnauthorizedBackgroundApps(this)

            // Also check for any blocked packages that might be running
            if (blockedPackages.isNotEmpty()) {
                val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                for (blockedPackage in blockedPackages) {
                    try {
                        Log.d("AppBlocker", "Force killing blocked package: $blockedPackage")
                        activityManager.killBackgroundProcesses(blockedPackage)
                    } catch (e: Exception) {
                        Log.w("AppBlocker", "Failed to kill blocked package $blockedPackage: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("AppBlocker", "Error during background app cleanup", e)
        }
    }

    private fun loadBlockedPackages() {
        try {
            val prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE)
            val blockedSet = prefs.getStringSet("packages", emptySet())
            blockedPackages.clear()
            blockedPackages.addAll(blockedSet ?: emptySet())
            Log.d("AppBlocker", "Loaded ${blockedPackages.size} blocked packages: $blockedPackages")
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error loading blocked packages", e)
        }
    }

    fun addBlockedPackage(packageName: String) {
        try {
            blockedPackages.add(packageName)
            val prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE)
            prefs.edit().putStringSet("packages", blockedPackages).apply()

            // Use device owner to disable if available
            if (devicePolicyManager.isDeviceOwnerActive()) {
                devicePolicyManager.disableApp(packageName)
            }

            Log.d("AppBlocker", "Added blocked package: $packageName")
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error adding blocked package", e)
        }
    }

    fun removeBlockedPackage(packageName: String) {
        try {
            blockedPackages.remove(packageName)
            val prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE)
            prefs.edit().putStringSet("packages", blockedPackages).apply()

            // Use device owner to enable if available
            if (devicePolicyManager.isDeviceOwnerActive()) {
                devicePolicyManager.enableApp(packageName)
            }

            Log.d("AppBlocker", "Removed blocked package: $packageName")
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error removing blocked package", e)
        }
    }

    fun getBlockedPackages(): Set<String> {
        return blockedPackages.toSet()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "App Blocker Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            serviceChannel.lightColor = Color.BLUE
            serviceChannel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun getNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("App Blocker Running")
            .setContentText("Monitoring apps to ensure child safety.")
            .setSmallIcon(R.mipmap.ic_launcher) // Use your app's launcher icon
            .setOngoing(true)
            .build()
    }

    fun clearAllBlockedPackages() {
        try {
            // Re-enable all blocked apps first
            if (devicePolicyManager.isDeviceOwnerActive()) {
                blockedPackages.forEach { packageName ->
                    devicePolicyManager.enableApp(packageName)
                }
            }

            blockedPackages.clear()
            val prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE)
            prefs.edit().clear().apply()

            Log.d("AppBlocker", "Cleared all blocked packages")
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error clearing blocked packages", e)
        }
    }
}
