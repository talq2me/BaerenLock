package com.talq2me.baeren

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AppMonitorService : AccessibilityService() {

    // Replace with your main app package
    private val allowedPackages = setOf("com.talq2me.baeren")

    // Update dynamically when granting a reward
    companion object {
        var rewardedPackage: String? = null
        var rewardEndTime: Long = 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Log and monitor
        Log.d("AppMonitor", "Foreground app: $packageName")

        val now = System.currentTimeMillis()
        if (packageName == rewardedPackage && now > rewardEndTime) {
            // Time’s up — bring your app back
            val intent = packageManager.getLaunchIntentForPackage("com.talq2me.baeren")
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageNames = null // Monitor all apps
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }
}
