package com.talq2me.baerenlock

import android.app.admin.DevicePolicyManager as DPM
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.os.UserManager
import androidx.annotation.RequiresApi
import android.net.ProxyInfo
import android.app.Activity

class DevicePolicyManager private constructor(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DPM
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

    companion object {
        private const val TAG = "DevicePolicyManager"
        private var instance: DevicePolicyManager? = null

        fun getInstance(context: Context): DevicePolicyManager {
            if (instance == null) {
                instance = DevicePolicyManager(context.applicationContext)
            }
            return instance!!
        }
    }

    // Device Owner Status
    fun isDeviceOwner(): Boolean {
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun isAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    fun isDeviceOwnerActive(): Boolean {
        return isDeviceOwner() && isAdminActive()
    }

    // Provisioning
    fun requestDeviceAdminActivation(): Boolean {
        if (!isAdminActive()) {
            val intent = Intent(DPM.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DPM.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(DPM.EXTRA_ADD_EXPLANATION, "BaerenEd needs device admin to provide parental controls")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return false
        }
        return true
    }

    fun setDeviceOwnerApp(): Boolean {
        return if (isDeviceOwner()) {
            Log.d(TAG, "Already device owner")
            true
        } else {
            Log.d(TAG, "Not device owner, cannot set as device owner")
            false
        }
    }

    // App Management
    fun enableApp(packageName: String) {
        try {
            dpm.enableSystemApp(adminComponent, packageName)
            Log.d(TAG, "Enabled system app: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable app: $packageName", e)
        }
    }

    fun disableApp(packageName: String) {
        try {
            dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), true)
            Log.d(TAG, "Disabled app: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable app: $packageName", e)
        }
    }

    fun blockAppUninstallation(packageName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.setUninstallBlocked(adminComponent, packageName, true)
                Log.d(TAG, "Blocked uninstallation for app: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to block uninstallation for app: $packageName", e)
        }
    }

    // Hardware Restrictions
    fun disableCamera() {
        try {
            dpm.setCameraDisabled(adminComponent, true)
            Log.d(TAG, "Camera disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable camera", e)
        }
    }

    fun enableCamera() {
        try {
            dpm.setCameraDisabled(adminComponent, false)
            Log.d(TAG, "Camera enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable camera", e)
        }
    }

    fun disableBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                dpm.setBluetoothContactSharingDisabled(adminComponent, true)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_BLUETOOTH)
                Log.d(TAG, "Bluetooth disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable bluetooth", e)
        }
    }

    fun enableBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_BLUETOOTH)
                dpm.setBluetoothContactSharingDisabled(adminComponent, false)
                Log.d(TAG, "Bluetooth enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable bluetooth", e)
        }
    }

    fun disableWifi() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)
                Log.d(TAG, "WiFi disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable WiFi", e)
        }
    }

    fun enableWifi() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)
                Log.d(TAG, "WiFi enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable WiFi", e)
        }
    }

    fun disableLocation() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_LOCATION)
                Log.d(TAG, "Location disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable location", e)
        }
    }

    fun enableLocation() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_LOCATION)
                Log.d(TAG, "Location enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable location", e)
        }
    }

    fun disableMicrophone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNMUTE_MICROPHONE)
                Log.d(TAG, "Microphone disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable microphone", e)
        }
    }

    fun enableMicrophone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNMUTE_MICROPHONE)
                Log.d(TAG, "Microphone enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable microphone", e)
        }
    }

    // Screen and UI Restrictions
    fun disableStatusBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setStatusBarDisabled(adminComponent, true)
                Log.d(TAG, "Status bar disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable status bar", e)
        }
    }

    fun enableStatusBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setStatusBarDisabled(adminComponent, false)
                Log.d(TAG, "Status bar enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable status bar", e)
        }
    }

    fun disableKeyguard() {
        try {
            dpm.setKeyguardDisabled(adminComponent, true)
            Log.d(TAG, "Keyguard disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable keyguard", e)
        }
    }

    fun enableKeyguard() {
        try {
            dpm.setKeyguardDisabled(adminComponent, false)
            Log.d(TAG, "Keyguard enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable keyguard", e)
        }
    }

    fun disableScreenCapture() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.setScreenCaptureDisabled(adminComponent, true)
                Log.d(TAG, "Screen capture disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable screen capture", e)
        }
    }

    fun enableScreenCapture() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.setScreenCaptureDisabled(adminComponent, false)
                Log.d(TAG, "Screen capture enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable screen capture", e)
        }
    }

    // Security Policies
    @Suppress("DEPRECATION")
    fun setPasswordQuality(quality: Int) {
        try {
            dpm.setPasswordQuality(adminComponent, quality)
            Log.d(TAG, "Password quality set to: $quality")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set password quality", e)
        }
    }

    @Suppress("DEPRECATION")
    fun setPasswordMinimumLength(length: Int) {
        try {
            dpm.setPasswordMinimumLength(adminComponent, length)
            Log.d(TAG, "Password minimum length set to: $length")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set password minimum length", e)
        }
    }

    fun lockNow() {
        try {
            dpm.lockNow()
            Log.d(TAG, "Device locked")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock device", e)
        }
    }

    fun wipeData(flags: Int) {
        try {
            dpm.wipeData(flags)
            Log.d(TAG, "Device data wiped with flags: $flags")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wipe device data", e)
        }
    }

    fun rebootDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.reboot(adminComponent)
                Log.d(TAG, "Device reboot initiated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reboot device", e)
        }
    }

    // Network Management
    fun setGlobalProxy(host: String, port: Int, exclusionList: List<String> = emptyList()) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                val exclusionString = exclusionList.joinToString(",")
                val proxyInfo = ProxyInfo.buildDirectProxy(host, port, exclusionList)
                dpm.setRecommendedGlobalProxy(adminComponent, proxyInfo)
                Log.d(TAG, "Global proxy set to $host:$port")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set global proxy", e)
        }
    }

    fun clearGlobalProxy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                dpm.setRecommendedGlobalProxy(adminComponent, null)
                Log.d(TAG, "Global proxy cleared")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear global proxy", e)
        }
    }

    // Kiosk Mode / Lock Task
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun startLockTask(packageName: String) {
        try {
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            if (context is Activity) {
                context.startLockTask()
                Log.d(TAG, "Lock task started for: $packageName")
            } else {
                Log.e(TAG, "Context is not an Activity, cannot start lock task.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start lock task", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun stopLockTask() {
        try {
            if (context is Activity) {
                context.stopLockTask()
                Log.d(TAG, "Lock task stopped")
            } else {
                Log.e(TAG, "Context is not an Activity, cannot stop lock task.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop lock task", e)
        }
    }

    // System Settings
    fun setTimeZone(timeZone: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.setTimeZone(adminComponent, timeZone)
                Log.d(TAG, "Time zone set to: $timeZone")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set time zone", e)
        }
    }

    fun setSystemSetting(setting: String, value: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.Global.putString(context.contentResolver, setting, value)
                Log.d(TAG, "System setting $setting set to: $value")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set system setting", e)
        }
    }

    // Account Management
    fun removeAccount(accountType: String, accountName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Changed from LOLLIPOP to M
                // dpm.removeAccount(adminComponent, accountType, accountName) // This method is not directly available for device owner apps
                Log.d(TAG, "Removing account functionality not directly supported by DevicePolicyManager for device owner. Consider using AccountManager with appropriate permissions.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove account", e)
        }
    }

    // Utility Methods
    fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "isDeviceOwner" to isDeviceOwner().toString(),
            "isAdminActive" to isAdminActive().toString(),
            "policyVersion" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // dpm.getPolicyVersion(adminComponent)?.toString() ?: "unknown" // This method is not directly available
                "Policy version retrieval not directly supported by DevicePolicyManager for device owner." // Placeholder
            } else "unknown"
        )
    }

    @Suppress("DEPRECATION")
    fun clearDeviceOwnerApp() {
        try {
            dpm.clearDeviceOwnerApp(context.packageName)
            Log.d(TAG, "Device owner cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear device owner", e)
        }
    }
}

