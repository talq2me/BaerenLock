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
import android.os.Looper
import android.widget.Toast

class AppBlockerService : AccessibilityService() {

    private var lastPackage: String? = null
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private val periodicCheck = object : Runnable {
        override fun run() {
            checkForegroundApp()
            // Schedule next check - ensure it always continues
            backgroundHandler.postDelayed(this, 2000) // Check every 2 seconds
        }
    }

    private val usageCheck = object : Runnable {
        override fun run() {
            checkUsageStats()
            // Schedule next check - ensure it always continues
            backgroundHandler.postDelayed(this, 2000) // Check every 2 seconds
        }
    }

    private val backgroundCleanupCheck = object : Runnable {
        override fun run() {
            cleanupUnauthorizedBackgroundApps()
            backgroundHandler.postDelayed(this, 30000) // Check every 30 seconds
        }
    }

    private lateinit var devicePolicyManager: com.talq2me.baerenlock.DevicePolicyManager
    private var chromeJeLisUrl: String? = null // Track if Chrome is viewing JeLis
    private var chromeLaunchedFromBaerenEd: Boolean = false // Track if Chrome was launched from BaerenEd
    
    // Track last event time to detect if service is not receiving events
    private var lastEventTime: Long = 0
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            checkServiceHealth()
            backgroundHandler.postDelayed(this, 60000) // Check every minute
        }
    }

    private val CHANNEL_ID = "AppBlockerServiceChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        Log.d("AppBlocker", "AppBlockerService onCreate() - service starting")
        createNotificationChannel()

        backgroundThread = HandlerThread("AppBlockerBackground").apply {
            start()
        }
        backgroundHandler = Handler(backgroundThread.looper)
        lastEventTime = System.currentTimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Update last event time to indicate service is receiving events
        lastEventTime = System.currentTimeMillis()
        
        // Only process relevant event types to reduce overhead
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && 
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return
        }

        val pkgName = event.packageName?.toString() ?: return

        // Check if Chrome is being launched - if so, check if it came from BaerenEd
        if (pkgName == "com.android.chrome" || pkgName.contains("chrome", ignoreCase = true)) {
            // Check if Chrome was launched from BaerenEd (check lastPackage BEFORE updating it)
            if (lastPackage == "com.talq2me.baerened" || lastPackage == packageName) {
                chromeLaunchedFromBaerenEd = true
                Log.d("AppBlocker", "Chrome launched from BaerenEd - allowing for JeLis")
                // Set a timeout - if Chrome stays open, we'll keep checking for JeLis content
                Handler(Looper.getMainLooper()).postDelayed({
                    chromeLaunchedFromBaerenEd = false
                }, 30000) // Allow for 30 seconds after launch
            }
            // Also check Chrome content for JeLis
            checkChromeUrl(event)
        }

        // Update RewardManager with the current foreground app (for accurate reward time counting)
        RewardManager.updateForegroundApp(pkgName)
        
        lastPackage = pkgName

        // Check if this app should be blocked
        if (shouldBlockApp(pkgName)) {
            Log.d("AppBlocker", "🚫 BLOCKING app: $pkgName")
            
            // Show toast with package name
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Blocked: $pkgName", Toast.LENGTH_SHORT).show()
            }

            // Use device owner capabilities if available for stronger blocking
            if (devicePolicyManager.isDeviceOwnerActive()) {
                devicePolicyManager.disableApp(pkgName)
            }

            returnToLauncher()
            return
        }

        // App is allowed, do nothing
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

        // Start periodic foreground app checks (runs every 2 seconds)
        backgroundHandler.post(periodicCheck)
        Log.d("AppBlocker", "Started periodic foreground app check (every 2 seconds)")
        
        // Start UsageStats polling (more reliable than ActivityManager)
        if (!hasUsageStatsPermission()) {
            Log.d("AppBlocker", "USAGESTATS: UsageStats permission NOT granted. Prompting user.")
            promptForUsageAccess()
        } else {
            Log.d("AppBlocker", "USAGESTATS: UsageStats permission granted. Starting usage check (every 2 seconds).")
            backgroundHandler.post(usageCheck)
        }

        // Start background app cleanup
        backgroundHandler.post(backgroundCleanupCheck)
        
        // Start health check monitoring
        lastEventTime = System.currentTimeMillis()
        backgroundHandler.postDelayed(healthCheckRunnable, 60000) // Start after 1 minute
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundHandler.removeCallbacks(periodicCheck)
        backgroundHandler.removeCallbacks(usageCheck)
        backgroundHandler.removeCallbacks(backgroundCleanupCheck)
        backgroundHandler.removeCallbacks(healthCheckRunnable)
        backgroundThread.quitSafely()
        stopForeground(true)
    }
    
    /**
     * Checks if the service is actually receiving events.
     * If no events have been received in the last 2 minutes, the service may not be working properly.
     */
    private fun checkServiceHealth() {
        try {
            val timeSinceLastEvent = System.currentTimeMillis() - lastEventTime
            // If no events received in 2 minutes, log a warning
            // This could indicate the service is enabled but not receiving events
            if (timeSinceLastEvent > 120000) { // 2 minutes
                Log.w("AppBlocker", "Service health check: No accessibility events received in ${timeSinceLastEvent / 1000}s")
                // Store health status for reporting
                storeServiceHealthStatus(false)
            } else {
                // Service is healthy - receiving events
                storeServiceHealthStatus(true)
            }
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error in service health check", e)
        }
    }
    
    private fun storeServiceHealthStatus(isHealthy: Boolean) {
        try {
            val prefs = getSharedPreferences("health_prefs", MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("service_receiving_events", isHealthy)
                putLong("last_service_health_check", System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error storing service health status", e)
        }
    }

    private fun checkForegroundApp() {
        try {
            // Try UsageStats first if available (most reliable)
            if (hasUsageStatsPermission()) {
                // UsageStats is handled by checkUsageStats() which runs separately
                // This method serves as a fallback when UsageStats permission is not granted
                return
            }
            
            // Fallback to ActivityManager (less reliable, deprecated, but better than nothing)
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses
            
            if (processes != null) {
                for (process in processes) {
                    if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        val pkgName = process.processName

                        // Update RewardManager with the current foreground app (for accurate reward time counting)
                        RewardManager.updateForegroundApp(pkgName)

                        // Check if this app should be blocked
                        if (shouldBlockApp(pkgName)) {
                            Log.d("AppBlocker", "🚫 BLOCKING app: $pkgName (via ActivityManager)")
                            
                            // Show toast with package name
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this@AppBlockerService, "Blocked: $pkgName", Toast.LENGTH_SHORT).show()
                            }

                            // Use device owner capabilities if available for stronger blocking
                            if (devicePolicyManager.isDeviceOwnerActive()) {
                                devicePolicyManager.disableApp(pkgName)
                            }

                            returnToLauncher()
                            return
                        }
                        // App is allowed, continue checking
                        return
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error in periodic check", e)
        }
    }

    /**
     * Checks if Chrome is currently viewing a JeLis URL by examining accessibility event content.
     */
    private fun checkChromeUrl(event: AccessibilityEvent) {
        try {
            // Try to get URL from window content
            val source = event.source
            if (source != null) {
                // Search for "jelis" or "je lis" in the content
                val urlNodes = source.findAccessibilityNodeInfosByText("jelis")
                if (urlNodes.isEmpty()) {
                    source.findAccessibilityNodeInfosByText("je lis")
                }
                if (urlNodes.isNotEmpty()) {
                    chromeJeLisUrl = "jelis_detected"
                    Log.d("AppBlocker", "Chrome is viewing JeLis (found in nodes) - allowing access")
                    return
                }
                
                // Also check the window title/content for JeLis indicators
                val windowText = source.text?.toString() ?: ""
                val contentDescription = source.contentDescription?.toString() ?: ""
                val combinedText = (windowText + " " + contentDescription).lowercase()
                
                if (combinedText.contains("jelis") || combinedText.contains("je lis")) {
                    chromeJeLisUrl = "jelis_detected"
                    Log.d("AppBlocker", "Chrome is viewing JeLis (detected in text) - allowing access")
                    return
                }
                
                // Check URL bar - look for common JeLis URL patterns
                val urlBarNodes = source.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                urlBarNodes.forEach { node ->
                    val urlText = node.text?.toString()?.lowercase() ?: ""
                    if (urlText.contains("jelis") || urlText.contains("je-lis") || urlText.contains("je_lis")) {
                        chromeJeLisUrl = "jelis_detected"
                        Log.d("AppBlocker", "Chrome URL bar contains JeLis - allowing access")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AppBlocker", "Error checking Chrome URL: ${e.message}")
        }
    }

    /**
     * Determines if an app should be blocked.
     * Simple opt-in blocking model:
     * - Block if explicitly in blacklist (except Chrome for JeLis)
     * - Block if reward app with 0 minutes
     * - Otherwise allow (everything else is allowed if you can get to it)
     */
    private fun shouldBlockApp(pkgName: String): Boolean {
        // Never block our own app
        if (pkgName == packageName) {
            return false
        }

        // Get the blacklist using BlacklistManager
        val blacklist = BlacklistManager.getBlacklist(this)

        // Check if app is in blacklist
        if (blacklist.contains(pkgName)) {
            // Special case: Chrome - allow if viewing JeLis or launched from BaerenEd
            if (pkgName == "com.android.chrome" || pkgName == "com.chrome.browser" || pkgName.contains("chrome", ignoreCase = true)) {
                if (chromeJeLisUrl != null || chromeLaunchedFromBaerenEd) {
                    return false
                }
            }
            return true
        }

        // Block if it's a reward-eligible app with 0 minutes (expired reward)
        // IMPORTANT: Read directly from storage to avoid race conditions with in-memory cache
        val isRewardApp = RewardManager.rewardEligibleApps.contains(pkgName)
        if (isRewardApp) {
            // Read directly from SharedPreferences to get the most up-to-date value
            val storedRewardMinutes = RewardStorage.getCurrentRewardMinutesFromStorage(this)
            val hasRewardMinutes = storedRewardMinutes > 0
            if (!hasRewardMinutes) {
                Log.d("AppBlocker", "🚫 Blocking reward app $pkgName: storedRewardMinutes=$storedRewardMinutes (reward time expired)")
                return true
            }
        }

        // Everything else is allowed (not blocked)
        return false
    }

    // Blacklist operations are now handled by BlacklistManager
    // These methods are kept for backward compatibility but delegate to BlacklistManager
    @Deprecated("Use BlacklistManager.getBlacklist() instead", ReplaceWith("BlacklistManager.getBlacklist(context)"))
    fun getBlacklist(): Set<String> {
        return BlacklistManager.getBlacklist(this)
    }

    @Deprecated("Use BlacklistManager.removeFromBlacklist() instead", ReplaceWith("BlacklistManager.removeFromBlacklist(context, pkgName)"))
    fun removeFromBlacklist(pkgName: String) {
        BlacklistManager.removeFromBlacklist(this, pkgName)
    }

    private fun returnToLauncher() {
        try {
            // First, try to go home using the HOME intent (most reliable)
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(homeIntent)
            
            // Also try to start our launcher directly as backup
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val launcherIntent = Intent(this, LauncherActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(launcherIntent)
                } catch (e: Exception) {
                    Log.e("AppBlocker", "Failed to start launcher directly: ${e.message}", e)
                }
            }, 100)
        } catch (e: Exception) {
            Log.e("AppBlocker", "Failed to return to launcher: ${e.message}", e)
        }
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
            // Query only last 5 seconds for real-time blocking (more efficient than 15 minutes)
            val begin = end - 5000 // last 5 seconds
            val events = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            var lastForeground: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastForeground = event.packageName
                }
            }
            val pkgName = lastForeground ?: return
            
            // Update RewardManager with the current foreground app (for accurate reward time counting)
            RewardManager.updateForegroundApp(pkgName)
            
            // Check if this app should be blocked
            if (shouldBlockApp(pkgName)) {
                Log.d("AppBlocker", "🚫 BLOCKING app: $pkgName (via UsageStats, rewardMinutes=0)")
                
                // Show toast with package name
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AppBlockerService, "Blocked: $pkgName", Toast.LENGTH_SHORT).show()
                }

                // Use device owner capabilities if available for stronger blocking
                if (devicePolicyManager.isDeviceOwnerActive()) {
                    devicePolicyManager.disableApp(pkgName)
                }

                returnToLauncher()
                return
            }
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error in checkUsageStats", e)
        }
    }

    private fun cleanupUnauthorizedBackgroundApps() {
        try {
            // Use RewardManager to kill unauthorized background apps
            RewardManager.killUnauthorizedBackgroundApps(this)
        } catch (e: Exception) {
            Log.e("AppBlocker", "Error during background app cleanup", e)
        }
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

}
