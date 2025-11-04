package com.talq2me.baerenlock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.widget.Toast

class DeviceOwnerSetupManager private constructor(private val context: Context) {

    private val customDpm = com.talq2me.baerenlock.DevicePolicyManager.getInstance(context)
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
    private val prefs: SharedPreferences = context.getSharedPreferences("device_owner_setup", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "DeviceOwnerSetupManager"
        private var instance: DeviceOwnerSetupManager? = null

        fun getInstance(context: Context): DeviceOwnerSetupManager {
            if (instance == null) {
                instance = DeviceOwnerSetupManager(context.applicationContext)
            }
            return instance!!
        }
    }

    fun isDeviceOwnerSetup(): Boolean {
        return customDpm.isDeviceOwner()
    }

    fun isDeviceAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    fun isDeviceOwnerOrAdminActive(): Boolean {
        return isDeviceOwnerSetup() || isDeviceAdminActive()
    }

    fun requestDeviceAdminActivation(): Boolean {
        if (!isDeviceAdminActive()) {
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "BaerenEd needs device admin privileges for parental controls")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return false
        }
        return true
    }

    fun setupDeviceOwner(): Boolean {
        return if (isDeviceOwnerSetup()) {
            Log.d(TAG, "Already device owner")
            true
        } else {
            Log.d(TAG, "Not device owner, cannot setup without provisioning")
            false
        }
    }

    fun applyInitialRestrictions() {
        try {
            if (isDeviceOwnerSetup()) {
                // Apply basic restrictions for child safety

                // Disable camera for privacy
                customDpm.disableCamera()

                // Disable screen capture for security
                customDpm.disableScreenCapture()

                // Disable location services (can be enabled for specific apps later)
                customDpm.disableLocation()

                // Set password requirements (optional)
                customDpm.setPasswordQuality(android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX)
                customDpm.setPasswordMinimumLength(6)

                Log.d(TAG, "Applied initial device owner restrictions")
            } else {
                Log.w(TAG, "Cannot apply restrictions - not device owner")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying initial restrictions", e)
        }
    }

    fun configureKioskMode() {
        try {
            if (isDeviceOwnerSetup()) {

                // Set this app as the only allowed app in lock task mode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    customDpm.startLockTask(context.packageName)
                    Log.d(TAG, "Configured kiosk mode for BaerenEd")
                }
            } else {
                Log.w(TAG, "Cannot configure kiosk mode - not device owner")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring kiosk mode", e)
        }
    }

    fun setupParentalControls() {
        try {
            if (isDeviceOwnerSetup()) {
                // Configure comprehensive parental controls

                // Disable potentially dangerous features
                customDpm.disableCamera()
                customDpm.disableScreenCapture()

                // Disable installation of unknown sources
                customDpm.setSystemSetting("install_non_market_apps", "0")

                // Disable developer options
                customDpm.setSystemSetting("development_settings_enabled", "0")

                // Disable factory reset (protect against kids resetting device)
                // Note: Factory reset protection is usually managed via Google Account and not a direct DPM setting this way.
                // This might require a different approach or be limited by Android version.
                // customDevicePolicyManager.setGlobalSetting(adminComponent, "factory_reset_protection", "1") // This might not work as intended.
                Log.d(TAG, "Applied comprehensive parental controls")
            } else {
                Log.w(TAG, "Cannot setup parental controls - not device owner")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up parental controls", e)
        }
    }

    fun getSetupStatus(): Map<String, Any> {
        return mapOf(
            "isDeviceOwner" to isDeviceOwnerSetup(),
            "isDeviceAdmin" to isDeviceAdminActive(),
            "canApplyRestrictions" to isDeviceOwnerSetup(),
            "androidVersion" to Build.VERSION.SDK_INT,
            "packageName" to context.packageName
        )
    }

    fun generateProvisioningCommand(): String {
        return "adb shell dpm set-device-owner ${context.packageName}/.DeviceAdminReceiver"
    }

    fun generateProvisioningQrCode(): String {
        // In a real implementation, this would generate a proper QR code for NFC provisioning
        // For now, return the ADB command as a placeholder
        return generateProvisioningCommand()
    }

    fun resetDeviceOwner() {
        try {
            if (isDeviceOwnerSetup()) {
                // Note: This is a dangerous operation and typically requires factory reset
                // to undo device owner provisioning
                Log.w(TAG, "Device owner reset requested - this typically requires factory reset")
                Toast.makeText(context, "Device owner reset requires factory reset to complete", Toast.LENGTH_LONG).show()
            } else {
                Log.d(TAG, "Not device owner, nothing to reset")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting device owner", e)
        }
    }

    private fun setSecureSetting(setting: String, value: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setSecureSetting(adminComponent, setting, value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set secure setting $setting", e)
        }
    }

    private fun setGlobalSetting(setting: String, value: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setGlobalSetting(adminComponent, setting, value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set global setting $setting", e)
        }
    }
}
