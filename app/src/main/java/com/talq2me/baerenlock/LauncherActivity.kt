package com.talq2me.baerenlock

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class LauncherActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var healthCheckRunnable: Runnable? = null
    private var rewardRunnable: Runnable? = null
    private lateinit var appGrid: GridLayout
    private var accessibilityBanner: Button? = null
    private var lastDisplayedRewardMinutes: Int = -1 // Track last displayed value to avoid unnecessary updates
    private lateinit var prefs: SharedPreferences
    private var rewardMinutesTextView: TextView? = null
    private var backgroundImageView: ImageView? = null
    private var cloudToggleButton: ImageButton? = null

    companion object {
        private const val TAG = "LauncherActivity"
        const val ACTION_REWARD_EXPIRED = "com.talq2me.baerenlock.ACTION_REWARD_EXPIRED"
    }

    private val rewardExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REWARD_EXPIRED) {
                Log.d(TAG, "Received ACTION_REWARD_EXPIRED broadcast. Refreshing UI.")
                updateRewardMinutesDisplay()
            } else if (intent?.action == RewardTimeReceiver.ACTION_REWARD_TIME_UPDATED) {
                Log.d(TAG, "Received ACTION_REWARD_TIME_UPDATED broadcast. Refreshing UI.")
                RewardManager.loadRewardMinutes(this@LauncherActivity)
                updateRewardMinutesDisplay()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called - initializing app")
        
        prefs = getSharedPreferences("com.talq2me.baerenlock.prefs", Context.MODE_PRIVATE)
        
        // CRITICAL: Check if reset is needed FIRST, before any syncing
        // This ensures we don't sync stale data that's about to be reset
        Log.d(TAG, "Checking if daily reset is needed before syncing")
        SettingsManager.checkAndTriggerResetIfNeeded(this)
        
        // Preload settings from Supabase on startup (this also ensures device record exists)
        SettingsManager.preloadSettings(this)

        // CRITICAL: Check cloud profile before ensureDeviceRecord, so we don't overwrite cloud with old local value
        Log.d(TAG, "Checking profile from cloud before ensuring device record")
        val profileChanged = SettingsManager.checkAndApplyProfileFromCloud(this)
        if (profileChanged) {
            Log.d(TAG, "Profile changed during onCreate, will refresh UI after initialization")
        }
        
        // Request overlay permission if not granted
        maybeRequestOverlayPermission()
        
        // Perform health check on startup to detect issues immediately
        Log.d(TAG, "Performing initial health check on startup")
        performHealthCheck()
        
        // Note: Device record is already ensured by preloadSettings() above
        // Health check also ensures device record, so no need for another call here

        // Ensure we have a profile - if checkAndApplyProfileFromCloud failed, use default
        var userProfile = readProfile()
        if (userProfile == null) {
            Log.w(TAG, "Profile is null after checkAndApplyProfileFromCloud, defaulting to AM")
            userProfile = "AM"
            writeProfile(userProfile)
        }

        backgroundImageView = createDailyBackgroundImageView(userProfile)

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val root = FrameLayout(this).apply {
            addView(backgroundImageView)
            addView(contentLayout)
        }

        accessibilityBanner = Button(this).apply {
            setBackgroundColor(0xFFE57373.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            // Banner text and click listener will be set by updateHealthBanner()
        }
        contentLayout.addView(accessibilityBanner, 0)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener {
                showPinPrompt(onSuccess = {
                    showSettingsMenu()
                })
            }
        }
        topBar.addView(settingsButton)

        // Cloud sync toggle button
        cloudToggleButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_cloud)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setPadding(16, 16, 16, 16)
            updateCloudToggleState()
            setOnClickListener {
                toggleCloudSync()
            }
        }
        topBar.addView(cloudToggleButton)

        val versionTextView = TextView(this).apply {
            text = getVersionLabel()
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        topBar.addView(versionTextView)

        rewardMinutesTextView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER_VERTICAL
            text = "Reward: 0 min"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        topBar.addView(rewardMinutesTextView)

        val breakGlassButton = ImageButton(this).apply {
            setImageResource(R.drawable.exit_launcher)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener {
                showPinPrompt(onSuccess = {
                    exitLauncher()
                })
            }
        }
        topBar.addView(breakGlassButton)

        val webAppButton = Button(this).apply {
            text = "Baeren"
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, MainActivity::class.java))
            }
        }

        appGrid = GridLayout(this).apply {
            val displayMetrics = resources.displayMetrics
            val isLandscape = displayMetrics.widthPixels > displayMetrics.heightPixels
            columnCount = if (isLandscape) 8 else 5
            useDefaultMargins = false
        }

        contentLayout.addView(topBar)
        contentLayout.addView(webAppButton)
        contentLayout.addView(appGrid)

        setContentView(root)

        if (userProfile == null) {
            getOrCreateProfile()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        RewardManager.loadAllowedApps(this)
        RewardManager.loadRewardMinutes(this)

        // Check for reward minutes from intent
        processIncomingRewardMinutes(intent)

        refreshIcons(appGrid)
        startRewardDisplayUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent called - checking for reward minutes")
        // Process reward minutes when activity is already running
        processIncomingRewardMinutes(intent)
    }

    override fun onResume() {
        super.onResume()
        
        // Check if we're the default launcher (check on resume in case user changed it)
        ensureDefaultLauncher()
        
        // Perform health check to detect accessibility/permission issues
        performHealthCheck()
        
        // Start periodic health checks (every 5 minutes)
        startPeriodicHealthChecks()
        
        // CRITICAL: Check if reset is needed FIRST, before any syncing
        Log.d(TAG, "Checking if daily reset is needed before syncing (onResume)")
        SettingsManager.checkAndTriggerResetIfNeeded(this)
        
        // CRITICAL: Check for profile changes from cloud BEFORE refreshing UI
        // This ensures the UI displays the correct profile after sync
        val profileChanged = SettingsManager.checkAndApplyProfileFromCloud(this)
        
        // Refresh background image in case it was cleared from memory or profile changed
        refreshBackgroundImage()
        
        // Banner will be updated by performHealthCheck() which calls updateHealthBanner()

        // Download user_data from cloud for current profile (to get accurate reward minutes)
        // This happens after reset check, so we download the reset data if reset was triggered
        SettingsManager.downloadUserDataFromCloud(this)
        
        // Update cloud toggle state
        updateCloudToggleState()
        
        // CRITICAL: Load reward minutes BEFORE refreshing icons
        // This ensures the launcher shows reward apps when banked_mins > 0
        RewardManager.loadRewardMinutes(this)
        if (RewardManager.currentRewardMinutes > 0) {
            RewardManager.startRewardTimer(this)
        }

        // Check for reward minutes from intent (in case we missed it in onCreate/onNewIntent)
        processIncomingRewardMinutes(intent)

        // Refresh icons AFTER loading reward minutes to ensure reward apps show when banked_mins > 0
        refreshIcons(appGrid)

        startRewardDisplayUpdate()

        val filter = IntentFilter().apply {
            addAction(ACTION_REWARD_EXPIRED)
            addAction(RewardTimeReceiver.ACTION_REWARD_TIME_UPDATED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            rewardExpiredReceiver, filter
        )
    }

    override fun onPause() {
        super.onPause()
        stopRewardDisplayUpdate()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(rewardExpiredReceiver)
        // Keep health checks running even when paused (launcher might be in background)
        // Health checks are important for monitoring device health
    }

    private fun startRewardDisplayUpdate() {
        rewardRunnable?.let { handler.removeCallbacks(it) }
        rewardRunnable = object : Runnable {
            override fun run() {
                updateRewardMinutesDisplay()
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(rewardRunnable!!)
    }

    private fun stopRewardDisplayUpdate() {
        rewardRunnable?.let { handler.removeCallbacks(it) }
        rewardRunnable = null
    }

    private fun updateRewardMinutesDisplay() {
        val minutes = RewardManager.currentRewardMinutes
        runOnUiThread {
            // Only update text if it changed (avoid unnecessary UI updates)
            if (minutes != lastDisplayedRewardMinutes) {
                rewardMinutesTextView?.text = "Reward: $minutes min"
                
                // CRITICAL: Refresh icons when reward minutes cross the zero threshold
                // This ensures reward apps appear/disappear when banked_mins changes
                val crossedZeroThreshold = (lastDisplayedRewardMinutes == 0 && minutes > 0) ||
                                         (lastDisplayedRewardMinutes > 0 && minutes == 0)
                if (crossedZeroThreshold) {
                    Log.d(TAG, "Reward minutes crossed zero threshold ($lastDisplayedRewardMinutes -> $minutes), refreshing icons")
                    refreshIcons(appGrid)
                }
                
                lastDisplayedRewardMinutes = minutes
            }
            // Don't refresh icons every second - only refresh when apps list actually changes
            // refreshIcons() is expensive (rebuilds entire grid) and doesn't need to run every second
        }
    }

    private fun maybeRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission not granted, requesting permission")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
    
    private fun ensureDefaultLauncher() {
        val devicePolicyManager = DevicePolicyManager.getInstance(this)
        if (devicePolicyManager.isDeviceOwnerActive()) {
            try {
                val componentName = ComponentName(this, LauncherActivity::class.java)
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
                
                // Set this app as the default launcher (requires device owner)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.addPersistentPreferredActivity(
                        adminComponent,
                        IntentFilter(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addCategory(Intent.CATEGORY_DEFAULT)
                        },
                        componentName
                    )
                    Log.d(TAG, "Set BaerenLock as default launcher (device owner)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set default launcher: ${e.message}", e)
            }
        } else {
            // If not device owner, check if we're the default launcher using a more reliable method
            val isDefaultLauncher = isDefaultLauncher()
            
            if (!isDefaultLauncher) {
                Log.d(TAG, "Not set as default launcher")
                // Show prompt (but allow it to be shown again if user dismisses and comes back)
                val prefs = getSharedPreferences("com.talq2me.baerenlock.prefs", Context.MODE_PRIVATE)
                val lastPromptTime = prefs.getLong("launcher_prompt_last_shown", 0)
                val currentTime = System.currentTimeMillis()
                // Show prompt if never shown, or if last shown more than 1 hour ago
                if (lastPromptTime == 0L || (currentTime - lastPromptTime) > 3600000) {
                    AlertDialog.Builder(this)
                        .setTitle("Set BaerenLock as Home")
                        .setMessage("BaerenLock needs to be set as your default launcher to work properly. When you press the home button, it should open BaerenLock.\n\nPlease go to Settings and select BaerenLock as your Home app.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            try {
                                // Try the direct home settings intent first
                                val settingsIntent = Intent(Settings.ACTION_HOME_SETTINGS)
                                startActivity(settingsIntent)
                            } catch (e: Exception) {
                                // Fallback to general app settings
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:$packageName")
                                    }
                                    startActivity(intent)
                                } catch (e2: Exception) {
                                    Log.e(TAG, "Failed to open settings", e2)
                                }
                            }
                        }
                        .setNegativeButton("Later", null)
                        .setCancelable(false)
                        .show()
                    prefs.edit().putLong("launcher_prompt_last_shown", currentTime).apply()
                }
            } else {
                Log.d(TAG, "BaerenLock is set as default launcher")
                // Clear the prompt flag if we're now the default
                val prefs = getSharedPreferences("com.talq2me.baerenlock.prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("launcher_prompt_last_shown", 0).apply()
            }
        }
    }
    
    private fun isDefaultLauncher(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            
            // Resolve the HOME intent to see which launcher is currently default
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            
            if (resolveInfo != null) {
                val defaultPackage = resolveInfo.activityInfo.packageName
                val isDefault = defaultPackage == packageName
                Log.d(TAG, "Default launcher check: current=$defaultPackage, ourPackage=$packageName, isDefault=$isDefault")
                return isDefault
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking default launcher: ${e.message}", e)
            false
        }
    }

    private fun getVersionLabel(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val name = packageInfo.versionName ?: packageInfo.longVersionCode.toString()
            "v$name"
        } catch (e: Exception) {
            "v?"
        }
    }

    private fun readProfile(): String? {
        return ProfileManager.readProfile(this)
    }

    private fun writeProfile(newProfile: String) {
        ProfileManager.writeProfile(this, newProfile)
    }

    private fun getOrCreateProfile(): String? {
        readProfile()?.let { return it }

        val profileIds = arrayOf("AM", "BM", "TE")
        val profiles = arrayOf("Profile AM", "Profile BM", "Profile TE")
        AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setCancelable(false)
            .setItems(profiles) { _, which ->
                val selectedProfile = profileIds.getOrElse(which) { "AM" }
                writeProfile(selectedProfile)
                finishAffinity()
                startActivity(Intent(this, LauncherActivity::class.java))
            }
            .show()
        return null
    }

    private fun readPin(): String? {
        return SettingsManager.readPin(this)
    }

    private fun writePin(newPin: String) {
        SettingsManager.writePin(this, newPin)
    }

    private fun readEmail(): String? {
        return SettingsManager.readEmail(this)
    }

    private fun writeEmail(newEmail: String) {
        SettingsManager.writeEmail(this, newEmail)
    }

    private fun refreshIcons(container: ViewGroup) {
        container.removeAllViews()
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val allowedApps = apps.filter { RewardManager.isAllowed(it.activityInfo.packageName) }

        if (container is GridLayout) {
            val displayMetrics = resources.displayMetrics
            container.columnCount = if (displayMetrics.widthPixels > displayMetrics.heightPixels) 8 else 5
        }

        allowedApps.forEach { ri ->
            val appLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 150
                    height = 180
                    setMargins(4, 4, 4, 4)
                }
            }
            val icon = ImageView(this).apply {
                setImageDrawable(ri.loadIcon(pm))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(120, 120)
                setOnClickListener {
                    val pkgName = ri.activityInfo.packageName
                    // Launch the app normally (or do nothing if it's BaerenLock since we're already in it)
                    if (pkgName != packageName) {
                        packageManager.getLaunchIntentForPackage(pkgName)?.let { startActivity(it) }
                    }
                }
            }
            val label = TextView(this).apply {
                text = ri.loadLabel(pm)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 10f
                maxLines = 1
            }
            appLayout.addView(icon)
            appLayout.addView(label)
            container.addView(appLayout)
        }
    }

    private fun showPinPrompt(onSuccess: () -> Unit) {
        val storedPin = readPin() ?: "1234"
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 50; rightMargin = 50
            }
        }
        val container = FrameLayout(this)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Enter PIN")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == storedPin) onSuccess() else Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return ServiceHealthMonitor.isAccessibilityServiceEnabled(this)
    }
    
    private fun performHealthCheck(): ServiceHealthMonitor.HealthCheckResult {
        // Always perform a fresh check - don't rely on cached data
        val baseResult = ServiceHealthMonitor.performHealthCheck(this)
        
        Log.d(TAG, "performHealthCheck: accessibility=${baseResult.accessibilityStatus}, overlay=${baseResult.overlayPermissionStatus}, battery=${baseResult.batteryOptimizationStatus}")
        
        // Check if service is actually receiving events (more reliable than just checking if enabled)
        // This detects the case where accessibility is enabled but service isn't working after crash
        // However, don't mark accessibility as unhealthy on fresh installs (when there's no health check data yet)
        var result = baseResult
        val serviceReceivingEvents = checkIfServiceReceivingEvents()
        if (baseResult.accessibilityStatus == ServiceHealthMonitor.HealthStatus.HEALTHY && !serviceReceivingEvents) {
            // Only mark as unhealthy if we have health check data (not a fresh install)
            // This prevents false positives on fresh installs
            val prefs = getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
            val lastServiceHealthCheck = prefs.getLong("last_service_health_check", 0)
            if (lastServiceHealthCheck > 0) {
                // We have health check data, so this is a real issue
                Log.w(TAG, "Accessibility service is enabled but not receiving events")
                result = ServiceHealthMonitor.HealthCheckResult(
                    accessibilityStatus = ServiceHealthMonitor.HealthStatus.DISABLED,
                    usageStatsStatus = baseResult.usageStatsStatus,
                    defaultLauncherStatus = baseResult.defaultLauncherStatus,
                    overlayPermissionStatus = baseResult.overlayPermissionStatus,
                    batteryOptimizationStatus = baseResult.batteryOptimizationStatus,
                    accessibilityServiceName = baseResult.accessibilityServiceName,
                    lastCheckTime = baseResult.lastCheckTime
                )
            } else {
                // Fresh install - don't mark accessibility as unhealthy based on event receipt
                Log.d(TAG, "Fresh install detected - not marking accessibility as unhealthy based on event receipt")
            }
        }
        
        // Store health check result for reporting
        storeHealthCheckResult(result)
        
        // Sync health check to cloud
        val healthStatus = if (result.isHealthy()) "healthy" else "unhealthy"
        val healthIssues = if (result.hasIssues()) result.getIssueDescription() else null
        SettingsManager.syncHealthCheckToCloudAsync(this, healthStatus, healthIssues)
        
        // Also ensure device record exists (in case it wasn't created during startup)
        CloudSyncManager.ensureDeviceRecordAsync(this)
        
        // Update health banner with the BASE result (before the accessibility event check modification)
        // This ensures the banner shows the actual permission status, not a modified status
        updateHealthBanner(baseResult)
        
        return result
    }
    
    /**
     * Starts periodic health checks that run every 5 minutes
     */
    private fun startPeriodicHealthChecks() {
        // Stop any existing periodic health check
        stopPeriodicHealthChecks()
        
        healthCheckRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "Running periodic health check")
                performHealthCheck()
                // Schedule next check in 5 minutes
                handler.postDelayed(this, 5 * 60 * 1000L)
            }
        }
        // Start first check after 5 minutes
        handler.postDelayed(healthCheckRunnable!!, 5 * 60 * 1000L)
        Log.d(TAG, "Started periodic health checks (every 5 minutes)")
    }
    
    /**
     * Stops periodic health checks
     */
    private fun stopPeriodicHealthChecks() {
        healthCheckRunnable?.let {
            handler.removeCallbacks(it)
            healthCheckRunnable = null
            Log.d(TAG, "Stopped periodic health checks")
        }
    }
    
    
    /**
     * Checks if the accessibility service is actually receiving events.
     * Returns true if service is receiving events (within last 5 minutes), false otherwise.
     * If there's no data (fresh install), returns false to be conservative.
     */
    private fun checkIfServiceReceivingEvents(): Boolean {
        return try {
            val prefs = getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
            val lastServiceHealthCheck = prefs.getLong("last_service_health_check", 0)
            val now = System.currentTimeMillis()
            
            // If no health check data or it's stale (older than 5 minutes), assume service is NOT receiving events
            // This is conservative - after fresh install or if service stopped, we should detect it as unhealthy
            if (lastServiceHealthCheck == 0L || (now - lastServiceHealthCheck) > 300000) {
                // No recent data - assume service is not receiving events (conservative approach)
                return false
            }
            
            // We have recent data - check if service was receiving events
            val serviceReceivingEvents = prefs.getBoolean("service_receiving_events", false)
            serviceReceivingEvents
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is receiving events", e)
            // On error, assume service is NOT receiving events (conservative approach)
            false
        }
    }
    
    private fun storeHealthCheckResult(result: ServiceHealthMonitor.HealthCheckResult) {
        try {
            val prefs = getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("last_health_check_time", result.lastCheckTime.toString())
                putString("accessibility_status", result.accessibilityStatus.name)
                putString("usage_stats_status", result.usageStatsStatus.name)
                putString("default_launcher_status", result.defaultLauncherStatus.name)
                putString("overlay_permission_status", result.overlayPermissionStatus.name)
                putString("battery_optimization_status", result.batteryOptimizationStatus.name)
                putString("health_issues", if (result.hasIssues()) result.getIssueDescription() else "")
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error storing health check result", e)
        }
    }
    
    /**
     * Gets the last stored health check result for reporting
     */
    fun getLastHealthCheckResult(): ServiceHealthMonitor.HealthCheckResult? {
        return try {
            val prefs = getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
            val accessibilityStatus = ServiceHealthMonitor.HealthStatus.valueOf(
                prefs.getString("accessibility_status", ServiceHealthMonitor.HealthStatus.ERROR.name) ?: ServiceHealthMonitor.HealthStatus.ERROR.name
            )
            val usageStatsStatus = ServiceHealthMonitor.HealthStatus.valueOf(
                prefs.getString("usage_stats_status", ServiceHealthMonitor.HealthStatus.ERROR.name) ?: ServiceHealthMonitor.HealthStatus.ERROR.name
            )
            val lastCheckTime = prefs.getString("last_health_check_time", "0")?.toLongOrNull() ?: 0L
            var finalAccessibilityStatus = accessibilityStatus
            
            // Check if service is actually receiving events (more reliable indicator)
            val serviceReceivingEvents = prefs.getBoolean("service_receiving_events", true)
            if (accessibilityStatus == ServiceHealthMonitor.HealthStatus.HEALTHY && !serviceReceivingEvents) {
                // Service is enabled but not receiving events - this is the problem case
                finalAccessibilityStatus = ServiceHealthMonitor.HealthStatus.DISABLED
            }
            
            // Get new status fields (default to HEALTHY if not stored, for backward compatibility)
            val defaultLauncherStatus = ServiceHealthMonitor.HealthStatus.valueOf(
                prefs.getString("default_launcher_status", ServiceHealthMonitor.HealthStatus.HEALTHY.name) ?: ServiceHealthMonitor.HealthStatus.HEALTHY.name
            )
            val overlayPermissionStatus = ServiceHealthMonitor.HealthStatus.valueOf(
                prefs.getString("overlay_permission_status", ServiceHealthMonitor.HealthStatus.HEALTHY.name) ?: ServiceHealthMonitor.HealthStatus.HEALTHY.name
            )
            val batteryOptimizationStatus = ServiceHealthMonitor.HealthStatus.valueOf(
                prefs.getString("battery_optimization_status", ServiceHealthMonitor.HealthStatus.HEALTHY.name) ?: ServiceHealthMonitor.HealthStatus.HEALTHY.name
            )
            
            ServiceHealthMonitor.HealthCheckResult(
                accessibilityStatus = finalAccessibilityStatus,
                usageStatsStatus = usageStatsStatus,
                defaultLauncherStatus = defaultLauncherStatus,
                overlayPermissionStatus = overlayPermissionStatus,
                batteryOptimizationStatus = batteryOptimizationStatus,
                lastCheckTime = lastCheckTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading health check result", e)
            null
        }
    }
    
    /**
     * Gets a human-readable health report for parent monitoring
     */
    fun getHealthReport(): String {
        val result = getLastHealthCheckResult() ?: return "Unable to check health status"
        if (result.isHealthy()) {
            return "All systems operational"
        }
        return result.getIssueDescription()
    }

    private fun showSettingsMenu() {
        val options = arrayOf("App Whitelist", "Reward Apps", "Blocked Apps", "Change PIN", "Change Profile", "Change Parent Email", "Add Reward Time")
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, WhitelistSettingsActivity::class.java))
                    1 -> startActivity(Intent(this, RewardAppsSettingsActivity::class.java))
                    2 -> startActivity(Intent(this, BlackListSettingsActivity::class.java))
                    3 -> showChangePinDialog()
                    4 -> showChangeProfileDialog()
                    5 -> showChangeEmailDialog()
                    6 -> showAddRewardTimeDialog()
                }
            }
            .show()
    }

    private fun showChangeProfileDialog() {
        val profileIds = arrayOf("AM", "BM", "TE")
        val profiles = arrayOf("Profile AM", "Profile BM", "Profile TE")
        val currentProfile = readProfile()
        AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setItems(profiles) { _, which ->
                val selectedProfile = profileIds.getOrElse(which) { "AM" }
                if (currentProfile != selectedProfile) {
                    writeProfile(selectedProfile)
                    // Download user_data from cloud for the new profile
                    SettingsManager.downloadUserDataFromCloud(this)
                    // Reload reward minutes after profile change
                    RewardManager.loadRewardMinutes(this)
                    finishAffinity()
                    startActivity(Intent(this, LauncherActivity::class.java))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangePinDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val newPinInput = EditText(this).apply {
            hint = "Enter new PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(newPinInput)

        val confirmPinInput = EditText(this).apply {
            hint = "Confirm new PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(confirmPinInput)

        AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(dialogLayout)
            .setPositiveButton("Save") { _, _ ->
                val newPin = newPinInput.text.toString()
                val confirmPin = confirmPinInput.text.toString()

                if (newPin.isNotEmpty() && newPin == confirmPin) {
                    writePin(newPin)
                    Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PINs do not match or are empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangeEmailDialog() {
        val currentEmail = readEmail() ?: ""
        val input = EditText(this).apply {
            hint = "Parent Email Address"
            setText(currentEmail)
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 20)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Change Parent Email")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newEmail = input.text.toString().trim()
                if (newEmail.isNotEmpty() && android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                    writeEmail(newEmail)
                    Toast.makeText(this, "Email saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddRewardTimeDialog() {
        val input = EditText(this).apply {
            hint = "Enter reward minutes"
            inputType = InputType.TYPE_CLASS_NUMBER
            requestFocus()
        }

        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 20)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Reward Time")
            .setMessage("Enter the number of reward minutes to add:")
            .setView(container)
            .setPositiveButton("Add", null) // Set to null initially to prevent auto-dismiss
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val minutesText = input.text.toString().trim()
                val minutes = minutesText.toIntOrNull()
                
                if (minutes != null && minutes > 0) {
                    // Load current reward minutes
                    RewardManager.loadRewardMinutes(this)
                    
                    // Add the new reward minutes
                    RewardManager.currentRewardMinutes += minutes
                    RewardManager.saveRewardMinutes(this)
                    
                    // Update start minutes for usage-based tracking
                    RewardManager.updateStartMinutesForNewRewardTime(this, minutes)
                    
                    // Start timer if not already running
                    if (RewardManager.currentRewardMinutes > 0) {
                        RewardManager.startRewardTimer(this)
                    }
                    
                    // Update the display
                    updateRewardMinutesDisplay()
                    
                    // Refresh icons to show reward apps
                    refreshIcons(appGrid)
                    
                    Toast.makeText(this, "Added $minutes reward minutes. Total: ${RewardManager.currentRewardMinutes} minutes", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Manually added $minutes reward minutes. Total: ${RewardManager.currentRewardMinutes} minutes")
                    
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Please enter a valid number of minutes (greater than 0)", Toast.LENGTH_SHORT).show()
                }
            }
            
            // Allow Enter key to submit
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || 
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    positiveButton.performClick()
                    true
                } else {
                    false
                }
            }
        }

        dialog.show()
        
        // Show keyboard automatically
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun createDailyBackgroundImageView(userProfile: String?): ImageView {
        return ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Set dark grey as fallback background color (will show if image fails to load)
            setBackgroundColor(Color.parseColor("#2D2D2D")) // Dark grey

            if (userProfile == null) {
                setBackgroundColor(Color.parseColor("#2D2D2D"))
                return@apply
            }

            // Convert AM/BM to A/B for resource file names (resources are still named bg_a_ and bg_b_)
            // TE and other profiles use bg_a_ as fallback
            val prefix = when (userProfile) {
                "AM" -> "bg_a_"
                "BM" -> "bg_b_"
                else -> "bg_a_"
            }
            val fields = R.drawable::class.java.fields
            // Only use the _orig.jpg files, not the XML files
            val drawables = fields.filter { 
                it.name.startsWith(prefix) && it.name.endsWith("_orig")
            }

            if (drawables.isNotEmpty()) {
                try {
                    val randomDrawableId = drawables.random().getInt(null)
                    setImageResource(randomDrawableId)
                    Log.d(TAG, "Set background image: ${drawables.find { it.getInt(null) == randomDrawableId }?.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set background image: ${e.message}", e)
                    // Fallback to dark grey if image loading fails
                    setBackgroundColor(Color.parseColor("#2D2D2D"))
                }
            } else {
                // Fallback to dark grey if no matching images are found
                Log.w(TAG, "No background images found for profile: $userProfile")
                setBackgroundColor(Color.parseColor("#2D2D2D"))
            }
        }
    }

    private fun refreshBackgroundImage() {
        val userProfile = readProfile()
        Log.d(TAG, "refreshBackgroundImage called, userProfile: $userProfile, backgroundImageView is null: ${backgroundImageView == null}")
        
        if (backgroundImageView == null) {
            Log.w(TAG, "backgroundImageView is null, cannot refresh")
            return
        }
        
        val imageView = backgroundImageView!!
        // Always ensure dark grey background is set as fallback
        imageView.setBackgroundColor(Color.parseColor("#2D2D2D"))
        
        if (userProfile == null) {
            Log.d(TAG, "No user profile, using dark grey background")
            return
        }

        // Always reload the background image when returning to launcher
        // This ensures it's displayed even if it was cleared from memory
        Log.d(TAG, "Refreshing background image for profile: $userProfile")
        
        // Convert AM/BM to A/B for resource file names (resources are still named bg_a_ and bg_b_)
        // TE and other profiles use bg_a_ as fallback
        val prefix = when (userProfile) {
            "AM" -> "bg_a_"
            "BM" -> "bg_b_"
            else -> "bg_a_"
        }
        val fields = R.drawable::class.java.fields
        // Only use the _orig.jpg files, not the XML files
        val drawables = fields.filter { 
            it.name.startsWith(prefix) && it.name.endsWith("_orig")
        }

        Log.d(TAG, "Found ${drawables.size} drawables with prefix: $prefix (filtered for _orig files)")

        if (drawables.isNotEmpty()) {
            try {
                val randomDrawableId = drawables.random().getInt(null)
                val drawableName = drawables.find { it.getInt(null) == randomDrawableId }?.name
                Log.d(TAG, "Setting background image resource: $drawableName (id: $randomDrawableId)")
                
                // Clear any existing image first to force reload
                imageView.setImageDrawable(null)
                
                // Set the new image
                imageView.setImageResource(randomDrawableId)
                
                // Force a layout update
                imageView.invalidate()
                imageView.requestLayout()
                
                Log.d(TAG, "Successfully refreshed background image: $drawableName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh background image: ${e.message}", e)
                e.printStackTrace()
                // Image will fail to load, dark grey background will show through
                imageView.setImageDrawable(null)
            }
        } else {
            // No images found, ensure image is cleared so background shows
            Log.w(TAG, "No background images found for profile: $userProfile")
            imageView.setImageDrawable(null)
        }
    }

    private fun exitLauncher() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // Create and start a chooser, forcing the user to select a launcher.
        val chooser = Intent.createChooser(homeIntent, "Select Home App")
        startActivity(chooser)
    }

    private fun updateAccessibilityBanner(container: ViewGroup) {
        updateHealthBanner()
    }
    
    /**
     * Updates the health check banner to show any missing permissions or settings.
     * The banner will navigate to the appropriate settings screen when clicked.
     * @param result Optional health check result. If not provided, will perform a fresh check.
     */
    private fun updateHealthBanner(result: ServiceHealthMonitor.HealthCheckResult? = null) {
        // Always perform a fresh health check to ensure we have the latest status
        // Don't use cached data as permissions may have changed
        val healthResult = result ?: ServiceHealthMonitor.performHealthCheck(this)
        val banner = accessibilityBanner ?: return
        
        Log.d(TAG, "updateHealthBanner: accessibility=${healthResult.accessibilityStatus}, overlay=${healthResult.overlayPermissionStatus}, battery=${healthResult.batteryOptimizationStatus}")
        
        if (healthResult.isHealthy()) {
            banner.visibility = View.GONE
            return
        }
        
        // Determine which issue to show
        // CRITICAL: UsageStats is now prioritized first because without it, reward timer won't work at all!
        // Priority: UsageStats -> Overlay -> Accessibility -> Battery -> Launcher
        val issue: String
        val intent: Intent
        
        when {
            healthResult.usageStatsStatus == ServiceHealthMonitor.HealthStatus.DISABLED -> {
                issue = "Enable Usage Stats (Required for Reward Timer)"
                intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            }
            healthResult.overlayPermissionStatus != ServiceHealthMonitor.HealthStatus.HEALTHY -> {
                issue = "Enable Display Over Other Apps"
                intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            }
            healthResult.accessibilityStatus != ServiceHealthMonitor.HealthStatus.HEALTHY -> {
                issue = "Enable Protection (Accessibility Service)"
                intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            healthResult.batteryOptimizationStatus != ServiceHealthMonitor.HealthStatus.HEALTHY -> {
                issue = "Disable Battery Optimization"
                intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            }
            healthResult.defaultLauncherStatus != ServiceHealthMonitor.HealthStatus.HEALTHY -> {
                issue = "Set BaerenLock as Default Launcher"
                intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                // For launcher, we'll use a chooser
                banner.setOnClickListener {
                    val chooser = Intent.createChooser(intent, "Select Home App")
                    startActivity(chooser)
                }
                banner.text = "⚠️ $issue"
                banner.visibility = View.VISIBLE
                return
            }
            else -> {
                // Multiple issues or unknown issue
                issue = healthResult.getIssueDescription()
                // Default to accessibility settings if we can't determine
                intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        }
        
        banner.text = "⚠️ $issue"
        banner.setOnClickListener {
            startActivity(intent)
        }
        banner.visibility = View.VISIBLE
    }

    /**
     * Processes incoming reward minutes from Intent.
     * This handles reward time sent via Intent from BaerenEd.
     * Uses transaction ID to prevent double-counting if both Intent and Broadcast are received.
     */
    private fun processIncomingRewardMinutes(intent: Intent?) {
        if (intent == null) {
            Log.d(TAG, "processIncomingRewardMinutes: intent is null")
            return
        }
        
        // Log all extras for debugging
        val extras = intent.extras
        if (extras != null) {
            Log.d(TAG, "processIncomingRewardMinutes: Intent has ${extras.size()} extras")
            for (key in extras.keySet()) {
                Log.d(TAG, "  Extra: $key = ${extras.get(key)}")
            }
        } else {
            Log.d(TAG, "processIncomingRewardMinutes: Intent has no extras")
        }
        
        val incomingRewardMinutes = intent.getIntExtra("reward_minutes", 0)
        val transactionId = intent.getLongExtra("reward_transaction_id", 0L)
        
        Log.d(TAG, "processIncomingRewardMinutes: rewardMinutes=$incomingRewardMinutes, transactionId=$transactionId")
        
        if (incomingRewardMinutes > 0 && transactionId > 0) {
            // Check if we've already processed this transaction
            if (RewardManager.isTransactionProcessed(this, transactionId)) {
                Log.d(TAG, "Transaction ID $transactionId already processed, skipping to prevent double-counting")
                intent.removeExtra("reward_minutes")
                intent.removeExtra("reward_transaction_id")
                return
            }
            
            Log.d(TAG, "Processing $incomingRewardMinutes reward minutes from Intent (transaction ID: $transactionId)")
            RewardManager.loadRewardMinutes(this)
            val previousMinutes = RewardManager.currentRewardMinutes
            RewardManager.currentRewardMinutes += incomingRewardMinutes
            RewardManager.saveRewardMinutes(this)
            
            // Update start minutes for usage-based tracking
            RewardManager.updateStartMinutesForNewRewardTime(this, incomingRewardMinutes)
            
            // Mark this transaction as processed
            RewardManager.markTransactionProcessed(this, transactionId)
            
            intent.removeExtra("reward_minutes")
            intent.removeExtra("reward_transaction_id")
            updateRewardMinutesDisplay()
            
            Log.d(TAG, "Successfully added $incomingRewardMinutes minutes from Intent. Previous: $previousMinutes, New total: ${RewardManager.currentRewardMinutes} minutes")
            
            // Start timer if not already running
            if (RewardManager.currentRewardMinutes > 0) {
                RewardManager.startRewardTimer(this)
            }
        } else if (incomingRewardMinutes > 0 && transactionId == 0L) {
            // Legacy support: if no transaction ID, process it but log a warning
            Log.w(TAG, "Received reward minutes without transaction ID (legacy format). Processing anyway.")
            RewardManager.loadRewardMinutes(this)
            val previousMinutes = RewardManager.currentRewardMinutes
            RewardManager.currentRewardMinutes += incomingRewardMinutes
            RewardManager.saveRewardMinutes(this)
            
            // Update start minutes for usage-based tracking
            RewardManager.updateStartMinutesForNewRewardTime(this, incomingRewardMinutes)
            
            intent.removeExtra("reward_minutes")
            updateRewardMinutesDisplay()
            
            Log.d(TAG, "Legacy format: Added $incomingRewardMinutes minutes. Previous: $previousMinutes, New total: ${RewardManager.currentRewardMinutes} minutes")
            
            if (RewardManager.currentRewardMinutes > 0) {
                RewardManager.startRewardTimer(this)
            }
        } else {
            Log.d(TAG, "No valid reward minutes in Intent (rewardMinutes=$incomingRewardMinutes, transactionId=$transactionId)")
        }
    }

    private fun updateCloudToggleState() {
        val isEnabled = SettingsManager.isCloudStorageEnabled(this)
        cloudToggleButton?.let { button ->
            // Set background color: green when on, grey when off
            val color = if (isEnabled) {
                Color.parseColor("#4CAF50") // Green
            } else {
                Color.parseColor("#9E9E9E") // Grey
            }
            button.setBackgroundColor(color)
            button.contentDescription = if (isEnabled) "Cloud sync ON" else "Cloud sync OFF"
        }
    }

    private fun toggleCloudSync() {
        val currentState = SettingsManager.isCloudStorageEnabled(this)
        val newState = !currentState
        SettingsManager.setCloudStorageEnabled(this, newState)
        updateCloudToggleState()
        
        if (newState) {
            // Cloud sync enabled - trigger immediate sync
            Toast.makeText(this, "Cloud sync enabled - syncing...", Toast.LENGTH_SHORT).show()
            SettingsManager.downloadUserDataFromCloud(this)
            // Also trigger a sync of local data to cloud
            val profile = readProfile() ?: "AM"
            SettingsManager.syncAppListsToCloudAsync(this)
            // Reload reward minutes after sync
            Handler(Looper.getMainLooper()).postDelayed({
                RewardManager.loadRewardMinutes(this)
                updateRewardMinutesDisplay()
            }, 2000) // Wait 2 seconds for sync to complete
        } else {
            Toast.makeText(this, "Cloud sync disabled", Toast.LENGTH_SHORT).show()
        }
    }

}
