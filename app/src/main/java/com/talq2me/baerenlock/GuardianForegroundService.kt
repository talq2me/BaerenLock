package com.talq2me.baerenlock

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import android.Manifest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central controller: reward timing, app enforcement, watchdog, optional audio monitoring.
 */
class GuardianForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun reportForegroundApp(pkg: String, source: GuardianContract.ForegroundSource) {
            this@GuardianForegroundService.reportForegroundApp(pkg, source)
        }

        fun reportChromeJeLis(active: Boolean) {
            chromeJeLisActive = active
        }

        fun reportChromeLaunchedFromBaerenEd() {
            chromeLaunchedFromBaerenEd = true
            Handler(Looper.getMainLooper()).postDelayed({
                chromeLaunchedFromBaerenEd = false
            }, 30_000)
        }

        fun heartbeat() {
            HeartbeatManager.markHealthy(this@GuardianForegroundService)
        }
    }

    private val binder = LocalBinder()
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rewardCheckInFlight = AtomicBoolean(false)

    private lateinit var devicePolicyManager: DevicePolicyManager
    private var audioMonitor: AudioMonitor? = null

    private var lastPackage: String? = null
    @Volatile
    private var chromeJeLisActive: Boolean = false
    @Volatile
    private var chromeLaunchedFromBaerenEd: Boolean = false

    @Volatile
    private var audioMonitorEnabled: Boolean = true

    @Volatile
    private var audioLoudnessThreshold: Int = AudioMonitor.DEFAULT_THRESHOLD

    private var foregroundStarted = false
    private var accessibilityStaleNotified = false
    private var pendingAudioMonitorStart: Runnable? = null

    private val usageCheck = object : Runnable {
        override fun run() {
            pollUsageStats()
            backgroundHandler.postDelayed(this, USAGE_POLL_MS)
        }
    }

    private val activityManagerCheck = object : Runnable {
        override fun run() {
            pollActivityManagerForeground()
            backgroundHandler.postDelayed(this, USAGE_POLL_MS)
        }
    }

    private val localExpiryCheck = object : Runnable {
        override fun run() {
            checkLocalExpiry()
            backgroundHandler.postDelayed(this, LOCAL_EXPIRY_MS)
        }
    }

    private val rewardTimerCheck = object : Runnable {
        override fun run() {
            val shouldCheck =
                RewardManager.isRewardSessionActive() || RewardManager.currentRewardMinutes > 0
            if (shouldCheck) {
                checkAndUpdateRewardTimeAsync()
                backgroundHandler.postDelayed(this, REWARD_CLOUD_ACTIVE_MS)
            } else {
                backgroundHandler.postDelayed(this, REWARD_CLOUD_IDLE_MS)
            }
        }
    }

    private val backgroundCleanupCheck = object : Runnable {
        override fun run() {
            try {
                RewardManager.killUnauthorizedBackgroundApps(this@GuardianForegroundService)
            } catch (e: Exception) {
                Log.e(TAG, "Background cleanup error", e)
            }
            backgroundHandler.postDelayed(this, CLEANUP_MS)
        }
    }

    private val heartbeatWatchdog = object : Runnable {
        override fun run() {
            if (HeartbeatManager.isStale(HEARTBEAT_STALE_MS)) {
                if (!accessibilityStaleNotified) {
                    accessibilityStaleNotified = true
                    Log.w(TAG, "Accessibility heartbeat stale")
                    HeartbeatManager.markStale(this@GuardianForegroundService)
                    LocalBroadcastManager.getInstance(this@GuardianForegroundService)
                        .sendBroadcast(Intent(GuardianContract.ACTION_ACCESSIBILITY_STALE))
                }
            } else {
                accessibilityStaleNotified = false
            }
            backgroundHandler.postDelayed(this, HEARTBEAT_WATCHDOG_MS)
        }
    }

    private val settingsRefresh = object : Runnable {
        override fun run() {
            refreshSettingsFromCloudAsync()
            val delay = if (RewardManager.isRewardSessionActive()) SETTINGS_ACTIVE_MS else SETTINGS_IDLE_MS
            backgroundHandler.postDelayed(this, delay)
        }
    }

    private val audioSessionCheck = object : Runnable {
        override fun run() {
            syncAudioMonitorState()
            backgroundHandler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate")
        devicePolicyManager = DevicePolicyManager.getInstance(this)
        createNotificationChannel()
        startForegroundImmediately()
        backgroundThread = HandlerThread("GuardianBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
        audioMonitor = AudioMonitor(this) { pauseRewardForLoudness() }
        restoreState()
        startLoops()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundImmediately()
        when (intent?.action) {
            GuardianContract.ACTION_USE_REWARD -> serviceScope.launch { handleUseReward() }
            GuardianContract.ACTION_PAUSE_REWARD -> serviceScope.launch { handlePauseReward() }
            GuardianContract.ACTION_ENSURE_RUNNING -> { /* already running */ }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved - restarting guardian")
        ensureRunning(applicationContext)
    }

    override fun onDestroy() {
        instance = null
        backgroundHandler.removeCallbacks(usageCheck)
        backgroundHandler.removeCallbacks(activityManagerCheck)
        backgroundHandler.removeCallbacks(localExpiryCheck)
        backgroundHandler.removeCallbacks(rewardTimerCheck)
        backgroundHandler.removeCallbacks(backgroundCleanupCheck)
        backgroundHandler.removeCallbacks(heartbeatWatchdog)
        backgroundHandler.removeCallbacks(settingsRefresh)
        backgroundHandler.removeCallbacks(audioSessionCheck)
        pendingAudioMonitorStart?.let { backgroundHandler.removeCallbacks(it) }
        pendingAudioMonitorStart = null
        audioMonitor?.stop()
        serviceScope.cancel()
        backgroundThread.quitSafely()
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun startForegroundImmediately() {
        if (foregroundStarted) {
            updateNotification()
            return
        }
        promoteForeground(includeMicrophone = false)
        foregroundStarted = true
    }

    /**
     * Manifest allows specialUse|microphone, but we only declare microphone at runtime when
     * RECORD_AUDIO is granted — otherwise startForeground crashes on API 34+.
     */
    private fun promoteForeground(includeMicrophone: Boolean) {
        if (!areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled for app — FGS runs but shade icon is hidden")
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (includeMicrophone && hasRecordAudioPermission()) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun areNotificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val nm = getSystemService(NotificationManager::class.java)
        return nm.areNotificationsEnabled()
    }

    private fun restoreState() {
        RewardManager.loadAllowedApps(this)
        serviceScope.launch {
            if (SupabaseInterface.isConfigured(this@GuardianForegroundService)) {
                SupabaseInterface.fetchRewardTimeState(this@GuardianForegroundService)?.let { state ->
                    RewardManager.applyCloudRewardTimeState(state)
                }
                refreshSettingsFromCloud()
            }
        }
    }

    private fun startLoops() {
        if (hasUsageStatsPermission()) {
            backgroundHandler.post(usageCheck)
        } else {
            backgroundHandler.post(activityManagerCheck)
        }
        backgroundHandler.post(localExpiryCheck)
        backgroundHandler.post(rewardTimerCheck)
        backgroundHandler.post(backgroundCleanupCheck)
        backgroundHandler.post(heartbeatWatchdog)
        backgroundHandler.post(settingsRefresh)
        backgroundHandler.post(audioSessionCheck)
    }

    fun reportForegroundApp(pkg: String, source: GuardianContract.ForegroundSource) {
        lastPackage = pkg
        RewardManager.updateForegroundApp(pkg)
        evaluateAndEnforce(pkg, source.name)
    }

    private fun evaluateAndEnforce(pkg: String, sourceLabel: String) {
        if (AppBlockPolicy.shouldBlock(
                this,
                pkg,
                packageName,
                chromeJeLisActive,
                chromeLaunchedFromBaerenEd
            )
        ) {
            Log.d(TAG, "BLOCKING $pkg (source=$sourceLabel)")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Blocked: $pkg", Toast.LENGTH_SHORT).show()
            }
            if (devicePolicyManager.isDeviceOwnerActive()) {
                devicePolicyManager.disableApp(pkg)
            }
            returnToLauncher()
        }
    }

    private fun pollUsageStats() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - 5000
            val events = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            var lastForeground: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastForeground = event.packageName
                }
            }
            val pkg = lastForeground ?: lastPackage ?: return
            reportForegroundApp(pkg, GuardianContract.ForegroundSource.USAGE_STATS)
        } catch (e: Exception) {
            Log.e(TAG, "pollUsageStats error", e)
        }
    }

    private fun pollActivityManagerForeground() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses ?: return
            for (process in processes) {
                if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    reportForegroundApp(
                        process.processName,
                        GuardianContract.ForegroundSource.ACTIVITY_MANAGER
                    )
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pollActivityManagerForeground error", e)
        }
    }

    private fun checkLocalExpiry() {
        val expiry = RewardStorage.getRewardTimeExpiry()
        if (!expiry.isNullOrBlank() && SupabaseInterface.isAfterRewardTimeExpiry(expiry)) {
            Log.d(TAG, "Local expiry passed; running timer check")
            checkAndUpdateRewardTimeAsync()
        }
        updateNotification()
        syncAudioMonitorState()
    }

    private fun checkAndUpdateRewardTimeAsync() {
        if (!rewardCheckInFlight.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                withTimeoutOrNull(20_000L) {
                    RewardManager.performTimerCheckSuspend(this@GuardianForegroundService)
                } ?: Log.w(TAG, "Reward check timed out")
            } catch (e: Exception) {
                Log.e(TAG, "Reward check error", e)
            } finally {
                rewardCheckInFlight.set(false)
                updateNotification()
                syncAudioMonitorState()
            }
        }
    }

    private fun refreshSettingsFromCloudAsync() {
        serviceScope.launch { refreshSettingsFromCloud() }
    }

    private suspend fun refreshSettingsFromCloud() {
        if (!SupabaseInterface.isConfigured(this)) return
        val result = SupabaseInterface.loadSettingsFromCloud(this) ?: return
        val data = result.first
        audioMonitorEnabled = data.rewardAudioMonitorEnabled ?: true
        audioLoudnessThreshold = (data.rewardAudioLoudnessThreshold ?: AudioMonitor.DEFAULT_THRESHOLD)
            .coerceIn(0, 100)
        audioMonitor?.enabled = audioMonitorEnabled
        audioMonitor?.thresholdPercent = audioLoudnessThreshold
    }

    private fun syncAudioMonitorState() {
        val monitor = audioMonitor ?: return
        monitor.enabled = audioMonitorEnabled
        monitor.thresholdPercent = audioLoudnessThreshold
        val sessionActive = RewardManager.isRewardSessionActive()
        val shouldRun = audioMonitorEnabled && sessionActive
        val micPerm = hasRecordAudioPermission()
        val useMicrophoneFgs = shouldRun && micPerm
        if (foregroundStarted) {
            promoteForeground(includeMicrophone = useMicrophoneFgs)
        }
        if (shouldRun) {
            if (!monitor.isRunning() && pendingAudioMonitorStart == null) {
                Log.d(
                    TAG,
                    "syncAudioMonitor: scheduling monitor start (threshold=$audioLoudnessThreshold, " +
                        "micPerm=$micPerm, micFgs=$useMicrophoneFgs, " +
                        "serverRemaining=${RewardStorage.getServerSessionMinsRemaining()})"
                )
                val startTask = Runnable {
                    pendingAudioMonitorStart = null
                    if (audioMonitorEnabled && RewardManager.isRewardSessionActive()) {
                        audioMonitor?.start()
                    }
                }
                pendingAudioMonitorStart = startTask
                // Let startForeground(microphone) apply before first AudioRecord.read on API 34+.
                backgroundHandler.postDelayed(startTask, 250)
            }
        } else {
            pendingAudioMonitorStart?.let { backgroundHandler.removeCallbacks(it) }
            pendingAudioMonitorStart = null
            if (monitor.isRunning()) {
                Log.d(
                    TAG,
                    "syncAudioMonitor: stopping monitor (enabled=$audioMonitorEnabled, session=$sessionActive)"
                )
                monitor.stop()
            }
        }
    }

    private fun pauseRewardForLoudness() {
        serviceScope.launch {
            val localRemaining = RewardManager.getDisplayRewardMinutes(this@GuardianForegroundService)
            val ok = SupabaseInterface.pauseRewardTime(this@GuardianForegroundService)
            if (ok) {
                RewardManager.applyPauseRewardFromRpcSuccess(this@GuardianForegroundService, localRemaining)
                Toast.makeText(
                    this@GuardianForegroundService,
                    "Reward paused: loud noise detected",
                    Toast.LENGTH_LONG
                ).show()
            }
            syncAudioMonitorState()
            updateNotification()
        }
    }

    private suspend fun handleUseReward() {
        val preState = SupabaseInterface.fetchRewardTimeState(this)
        val preBanked = preState?.bankedMins ?: 0
        Log.d(
            TAG,
            "handleUseReward: preBanked=$preBanked, preExpiry=${preState?.rewardTimeExpiry}"
        )
        if (preBanked <= 0) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@GuardianForegroundService,
                    "No banked reward time on server",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        val ok = SupabaseInterface.useRewardTime(this)
        if (!ok) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@GuardianForegroundService, "Could not start reward session", Toast.LENGTH_SHORT).show()
            }
            return
        }
        repeat(20) { attempt ->
            val state = SupabaseInterface.fetchRewardTimeState(this)
            if (state != null) {
                RewardManager.applyCloudRewardTimeState(state)
                if (state.rewardSessionActive) {
                    val remaining = state.rewardMinsRemaining
                    Log.d(
                        TAG,
                        "handleUseReward: confirmed expiry=${state.rewardTimeExpiry}, " +
                            "serverRemaining=$remaining, preBanked=$preBanked"
                    )
                    if (remaining > preBanked + 2) {
                        Log.e(
                            TAG,
                            "handleUseReward: remaining ($remaining) >> banked used ($preBanked); " +
                                "check DB banked_mins / reward_time_expiry and redeploy af_reward_time_* SQL"
                        )
                    }
                    RewardManager.performTimerCheckSuspend(this)
                    RewardManager.refreshRewardEligibleApps(this)
                    withContext(Dispatchers.Main) { updateNotification() }
                    syncAudioMonitorState()
                    return
                }
            }
            if (attempt < 19) delay(250)
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(this@GuardianForegroundService, "Could not confirm reward session from server", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun handlePauseReward() {
        val localRemaining = RewardManager.getDisplayRewardMinutes(this)
        val ok = SupabaseInterface.pauseRewardTime(this)
        if (!ok) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@GuardianForegroundService, "Could not pause reward session", Toast.LENGTH_SHORT).show()
            }
            return
        }
        RewardManager.applyPauseRewardFromRpcSuccess(this, localRemaining)
        RewardManager.refreshRewardEligibleApps(this)
        syncAudioMonitorState()
        updateNotification()
    }

    private fun returnToLauncher() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(homeIntent)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val launcherIntent = Intent(this, LauncherActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                    startActivity(launcherIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start launcher: ${e.message}", e)
                }
            }, 100)
        } catch (e: Exception) {
            Log.e(TAG, "returnToLauncher failed: ${e.message}", e)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    "android:get_usage_stats",
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    "android:get_usage_stats",
                    android.os.Process.myUid(),
                    packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tablet Rules",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows when BaerenLock is monitoring the tablet"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val active = RewardManager.isRewardSessionActive()
        val body = if (active) {
            val mins = RewardManager.getDisplayRewardMinutes(this)
            "Reward time — $mins min left"
        } else {
            "Monitoring tablet apps"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tablet Rules Active")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val useMic = audioMonitor?.isRunning() == true && hasRecordAudioPermission()
        promoteForeground(includeMicrophone = useMic)
    }

    companion object {
        private const val TAG = "GuardianFGS"
        private const val CHANNEL_ID = "guardian_status_v2"
        private const val NOTIFICATION_ID = 1001

        private const val USAGE_POLL_MS = 2_000L
        private const val LOCAL_EXPIRY_MS = 15_000L
        private const val REWARD_CLOUD_ACTIVE_MS = 60_000L
        private const val REWARD_CLOUD_IDLE_MS = 5 * 60_000L
        private const val CLEANUP_MS = 30_000L
        private const val HEARTBEAT_WATCHDOG_MS = 7_000L
        private const val HEARTBEAT_STALE_MS = 15_000L
        private const val SETTINGS_ACTIVE_MS = 60_000L
        private const val SETTINGS_IDLE_MS = 5 * 60_000L

        @Volatile
        var instance: GuardianForegroundService? = null

        fun ensureRunning(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, GuardianForegroundService::class.java).apply {
                action = GuardianContract.ACTION_ENSURE_RUNNING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun requestUseReward(context: Context) {
            val intent = Intent(context.applicationContext, GuardianForegroundService::class.java).apply {
                action = GuardianContract.ACTION_USE_REWARD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun requestPauseReward(context: Context) {
            val intent = Intent(context.applicationContext, GuardianForegroundService::class.java).apply {
                action = GuardianContract.ACTION_PAUSE_REWARD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }
    }
}
