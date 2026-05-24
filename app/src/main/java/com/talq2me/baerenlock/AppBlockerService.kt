package com.talq2me.baerenlock

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Lightweight sensor: reports foreground app changes and Chrome JeLis hints to [GuardianForegroundService].
 */
class AppBlockerService : AccessibilityService() {

    private var lastPackage: String? = null
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private var guardianBinder: GuardianForegroundService.LocalBinder? = null
    private var guardianBound = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            guardianBinder?.heartbeat() ?: HeartbeatManager.touch()
            backgroundHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val guardianConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            guardianBinder = service as? GuardianForegroundService.LocalBinder
            guardianBound = guardianBinder != null
            Log.d(TAG, "Bound to GuardianForegroundService")
            HeartbeatManager.markHealthy(this@AppBlockerService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            guardianBinder = null
            guardianBound = false
            Log.w(TAG, "GuardianForegroundService disconnected")
        }
    }

    override fun onCreate() {
        super.onCreate()
        backgroundThread = HandlerThread("AppBlockerSensor").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        HeartbeatManager.touch()
        guardianBinder?.heartbeat()

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            return
        }

        val pkgName = event.packageName?.toString() ?: return

        if (pkgName == "com.android.chrome" || pkgName.contains("chrome", ignoreCase = true)) {
            if (lastPackage == "com.talq2me.baerened" || lastPackage == packageName) {
                guardianBinder?.reportChromeLaunchedFromBaerenEd()
            }
            val jeLis = detectChromeJeLis(event)
            if (jeLis) {
                guardianBinder?.reportChromeJeLis(true)
            }
        }

        lastPackage = pkgName
        guardianBinder?.reportForegroundApp(pkgName, GuardianContract.ForegroundSource.ACCESSIBILITY)
            ?: GuardianForegroundService.ensureRunning(this)
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility sensor connected")
        GuardianForegroundService.ensureRunning(this)
        bindService(
            Intent(this, GuardianForegroundService::class.java),
            guardianConnection,
            Context.BIND_AUTO_CREATE
        )
        backgroundHandler.post(heartbeatRunnable)
        HeartbeatManager.markHealthy(this)
    }

    override fun onDestroy() {
        backgroundHandler.removeCallbacks(heartbeatRunnable)
        if (guardianBound) {
            try {
                unbindService(guardianConnection)
            } catch (e: Exception) {
                Log.w(TAG, "unbind failed: ${e.message}")
            }
        }
        backgroundThread.quitSafely()
        super.onDestroy()
    }

    private fun detectChromeJeLis(event: AccessibilityEvent): Boolean {
        return try {
            val source = event.source ?: return false
            val urlNodes = source.findAccessibilityNodeInfosByText("jelis")
            if (urlNodes.isNotEmpty()) return true
            val windowText = source.text?.toString() ?: ""
            val contentDescription = source.contentDescription?.toString() ?: ""
            val combined = (windowText + " " + contentDescription).lowercase()
            if (combined.contains("jelis") || combined.contains("je lis")) return true
            val urlBarNodes = source.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
            urlBarNodes.any { node ->
                val urlText = node.text?.toString()?.lowercase() ?: ""
                urlText.contains("jelis") || urlText.contains("je-lis") || urlText.contains("je_lis")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chrome JeLis detect error: ${e.message}")
            false
        }
    }

    @Deprecated("Use BlacklistManager.getBlacklist() instead", ReplaceWith("BlacklistManager.getBlacklist(context)"))
    fun getBlacklist(): Set<String> = BlacklistManager.getBlacklist(this)

    @Deprecated("Use BlacklistManager.removeFromBlacklist() instead", ReplaceWith("BlacklistManager.removeFromBlacklist(context, pkgName)"))
    fun removeFromBlacklist(pkgName: String) {
        BlacklistManager.removeFromBlacklist(this, pkgName)
    }

    companion object {
        private const val TAG = "AppBlocker"
        private const val HEARTBEAT_MS = 3_000L
    }
}
