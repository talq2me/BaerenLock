package com.talq2me.baerenlock

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi

class KioskModeManager private constructor(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

    companion object {
        private const val TAG = "KioskModeManager"
        private var instance: KioskModeManager? = null

        fun getInstance(context: Context): KioskModeManager {
            if (instance == null) {
                instance = KioskModeManager(context.applicationContext)
            }
            return instance!!
        }
    }

    fun isDeviceOwner(): Boolean {
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun canUseKioskMode(): Boolean {
        return isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun startKioskMode(allowedPackages: Array<String> = arrayOf(context.packageName)) {
        try {
            if (!canUseKioskMode()) {
                Log.w(TAG, "Cannot start kiosk mode - not device owner or unsupported Android version")
                Toast.makeText(context, "Kiosk mode requires device owner privileges", Toast.LENGTH_SHORT).show()
                return
            }

            // Set allowed packages for lock task mode
            dpm.setLockTaskPackages(adminComponent, allowedPackages)

            // Start lock task mode
            if (context is Activity) {
                context.startLockTask()
                Log.d(TAG, "Kiosk mode started with packages: ${allowedPackages.joinToString()}")
                Toast.makeText(context, "Kiosk mode activated", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Cannot start kiosk mode - context is not an Activity")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting kiosk mode", e)
            Toast.makeText(context, "Failed to start kiosk mode", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun stopKioskMode() {
        try {
            if (context is Activity) {
                context.stopLockTask()
                Log.d(TAG, "Kiosk mode stopped")
                Toast.makeText(context, "Kiosk mode deactivated", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Cannot stop kiosk mode - context is not an Activity")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping kiosk mode", e)
            Toast.makeText(context, "Failed to stop kiosk mode", Toast.LENGTH_SHORT).show()
        }
    }

    fun isInLockTaskMode(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                activityManager.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
            } catch (e: Exception) {
                Log.e(TAG, "Error checking lock task mode", e)
                false
            }
        } else {
            false
        }
    }

    fun getAllowedPackages(): Array<String>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.getLockTaskPackages(adminComponent)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting allowed packages", e)
            null
        }
    }

    fun setAllowedPackages(packages: Array<String>) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.setLockTaskPackages(adminComponent, packages)
                Log.d(TAG, "Set allowed packages for kiosk mode: ${packages.joinToString()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting allowed packages", e)
        }
    }

    fun configureBaerenEdKioskMode() {
        try {
            if (!canUseKioskMode()) {
                Log.w(TAG, "Cannot configure kiosk mode - not device owner")
                return
            }

            // Configure BaerenEd as the primary kiosk app
            // Only allow BaerenEd and essential system apps
            val allowedPackages = arrayOf(
                context.packageName, // BaerenEd itself
                "com.android.systemui", // System UI
                "com.android.settings" // Settings (for PIN entry)
            )

            setAllowedPackages(allowedPackages)

            // Apply additional restrictions for kiosk mode
            val restrictionsManager = RestrictionsManager.getInstance(context)
            restrictionsManager.enableRestriction("disable_status_bar")
            restrictionsManager.enableRestriction("disable_keyguard")
            restrictionsManager.enableRestriction("disable_camera")
            restrictionsManager.enableRestriction("disable_screen_capture")

            Log.d(TAG, "BaerenEd kiosk mode configured")

        } catch (e: Exception) {
            Log.e(TAG, "Error configuring BaerenEd kiosk mode", e)
        }
    }

    fun configureEducationalKioskMode() {
        try {
            if (!canUseKioskMode()) {
                Log.w(TAG, "Cannot configure educational kiosk mode - not device owner")
                return
            }

            // Allow educational apps along with BaerenEd
            val allowedPackages = arrayOf(
                context.packageName, // BaerenEd
                "com.android.systemui",
                "com.google.android.apps.seekh", // Read Along (educational app mentioned in original code)
                // Add other educational apps as needed
            )

            setAllowedPackages(allowedPackages)

            // Apply moderate restrictions for educational use
            val restrictionsManager = RestrictionsManager.getInstance(context)
            restrictionsManager.enableRestriction("disable_camera")
            restrictionsManager.enableRestriction("disable_screen_capture")
            restrictionsManager.disableRestriction("disable_status_bar") // Allow some navigation

            Log.d(TAG, "Educational kiosk mode configured")

        } catch (e: Exception) {
            Log.e(TAG, "Error configuring educational kiosk mode", e)
        }
    }

    fun configureRewardKioskMode() {
        try {
            if (!canUseKioskMode()) {
                Log.w(TAG, "Cannot configure reward kiosk mode - not device owner")
                return
            }

            // This mode allows reward apps for limited time
            // We'll need to dynamically manage the allowed packages

            // Start with basic restrictions but allow flexibility
            val restrictionsManager = RestrictionsManager.getInstance(context)
            restrictionsManager.disableRestriction("disable_status_bar")
            restrictionsManager.enableRestriction("disable_camera")
            restrictionsManager.enableRestriction("disable_screen_capture")

            Log.d(TAG, "Reward kiosk mode configured")

        } catch (e: Exception) {
            Log.e(TAG, "Error configuring reward kiosk mode", e)
        }
    }

    fun enterKioskModeForRewardApp(rewardAppPackage: String, durationMinutes: Int) {
        try {
            if (!canUseKioskMode()) {
                Log.w(TAG, "Cannot enter reward kiosk mode - not device owner")
                return
            }

            // Allow BaerenEd and the reward app
            val allowedPackages = arrayOf(
                context.packageName,
                "com.android.systemui",
                rewardAppPackage
            )

            setAllowedPackages(allowedPackages)

            // Start kiosk mode
            if (context is Activity) {
                context.startLockTask()
            }

            // Set a timer to exit kiosk mode after the reward period
            // Note: In a real implementation, you'd want to handle this more robustly
            // with a background service or alarm

            Log.d(TAG, "Entered kiosk mode for reward app: $rewardAppPackage for $durationMinutes minutes")

        } catch (e: Exception) {
            Log.e(TAG, "Error entering reward kiosk mode", e)
        }
    }

    fun exitKioskMode() {
        try {
            if (isInLockTaskMode()) {
                if (context is Activity) {
                    context.stopLockTask()
                }

                // Restore normal allowed packages (just BaerenEd)
                setAllowedPackages(arrayOf(context.packageName, "com.android.systemui"))

                Log.d(TAG, "Exited kiosk mode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exiting kiosk mode", e)
        }
    }

    fun getKioskModeStatus(): Map<String, Any> {
        return mapOf(
            "isInLockTaskMode" to isInLockTaskMode(),
            "canUseKioskMode" to canUseKioskMode(),
            "isDeviceOwner" to isDeviceOwner(),
            "allowedPackages" to (getAllowedPackages()?.joinToString() ?: "none"),
            "androidVersion" to Build.VERSION.SDK_INT
        )
    }
}

