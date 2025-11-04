package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class RestrictionsManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("device_restrictions", Context.MODE_PRIVATE)
    private val devicePolicyManager = DevicePolicyManager.getInstance(context)

    companion object {
        private const val TAG = "RestrictionsManager"
        private var instance: RestrictionsManager? = null

        fun getInstance(context: Context): RestrictionsManager {
            if (instance == null) {
                instance = RestrictionsManager(context.applicationContext)
            }
            return instance!!
        }
    }

    fun isRestrictionEnabled(restrictionKey: String): Boolean {
        return prefs.getBoolean(restrictionKey, false)
    }

    fun enableRestriction(restrictionKey: String) {
        try {
            prefs.edit().putBoolean(restrictionKey, true).apply()

            when (restrictionKey) {
                "disable_camera" -> devicePolicyManager.disableCamera()
                "disable_microphone" -> devicePolicyManager.disableMicrophone()
                "disable_bluetooth" -> devicePolicyManager.disableBluetooth()
                "disable_wifi" -> devicePolicyManager.disableWifi()
                "disable_location" -> devicePolicyManager.disableLocation()
                "disable_screen_capture" -> devicePolicyManager.disableScreenCapture()
                "disable_status_bar" -> devicePolicyManager.disableStatusBar()
                "disable_keyguard" -> devicePolicyManager.disableKeyguard()
                "disable_data_roaming" -> devicePolicyManager.setSystemSetting("data_roaming", "0")
                "disable_usb_file_transfer" -> devicePolicyManager.setSystemSetting("usb_mass_storage_enabled", "false")
                "disable_nfc" -> devicePolicyManager.setSystemSetting("nfc_payment_default", "false")
                "disable_factory_reset" -> devicePolicyManager.setSystemSetting("factory_reset_protection", "true")
                "disable_developer_options" -> devicePolicyManager.setSystemSetting("development_settings_enabled", "false")
                "disable_unknown_sources" -> devicePolicyManager.setSystemSetting("install_non_market_apps", "false")
                "disable_verify_apps" -> devicePolicyManager.setSystemSetting("package_verifier_enable", "false")
                "disable_play_store" -> devicePolicyManager.setSystemSetting("play_store_enabled", "false")
                "disable_google_apps" -> devicePolicyManager.setSystemSetting("google_services_enabled", "false")
                "disable_voice_assistants" -> devicePolicyManager.setSystemSetting("voice_assistants_enabled", "false")
                "disable_printing" -> devicePolicyManager.setSystemSetting("printing_enabled", "false")
                "disable_safe_boot" -> devicePolicyManager.setSystemSetting("safe_boot", "true")
            }

            Log.d(TAG, "Enabled restriction: $restrictionKey")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable restriction: $restrictionKey", e)
        }
    }

    fun disableRestriction(restrictionKey: String) {
        try {
            prefs.edit().putBoolean(restrictionKey, false).apply()

            when (restrictionKey) {
                "disable_camera" -> devicePolicyManager.enableCamera()
                "disable_microphone" -> devicePolicyManager.enableMicrophone()
                "disable_bluetooth" -> devicePolicyManager.enableBluetooth()
                "disable_wifi" -> devicePolicyManager.enableWifi()
                "disable_location" -> devicePolicyManager.enableLocation()
                "disable_screen_capture" -> devicePolicyManager.enableScreenCapture()
                "disable_status_bar" -> devicePolicyManager.enableStatusBar()
                "disable_keyguard" -> devicePolicyManager.enableKeyguard()
                "disable_data_roaming" -> devicePolicyManager.setSystemSetting("data_roaming", "1")
                "disable_usb_file_transfer" -> devicePolicyManager.setSystemSetting("usb_mass_storage_enabled", "true")
                "disable_nfc" -> devicePolicyManager.setSystemSetting("nfc_payment_default", "true")
                "disable_factory_reset" -> devicePolicyManager.setSystemSetting("factory_reset_protection", "false")
                "disable_developer_options" -> devicePolicyManager.setSystemSetting("development_settings_enabled", "true")
                "disable_unknown_sources" -> devicePolicyManager.setSystemSetting("install_non_market_apps", "true")
                "disable_verify_apps" -> devicePolicyManager.setSystemSetting("package_verifier_enable", "true")
                "disable_play_store" -> devicePolicyManager.setSystemSetting("play_store_enabled", "true")
                "disable_google_apps" -> devicePolicyManager.setSystemSetting("google_services_enabled", "true")
                "disable_voice_assistants" -> devicePolicyManager.setSystemSetting("voice_assistants_enabled", "true")
                "disable_printing" -> devicePolicyManager.setSystemSetting("printing_enabled", "true")
                "disable_safe_boot" -> devicePolicyManager.setSystemSetting("safe_boot", "false")
            }

            Log.d(TAG, "Disabled restriction: $restrictionKey")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable restriction: $restrictionKey", e)
        }
    }

    fun getAllRestrictions(): Map<String, Boolean> {
        val restrictions = mutableMapOf<String, Boolean>()

        val keys = listOf(
            "disable_camera", "disable_microphone", "disable_bluetooth", "disable_wifi",
            "disable_location", "disable_screen_capture", "disable_status_bar", "disable_keyguard",
            "disable_data_roaming", "disable_usb_file_transfer", "disable_nfc",
            "disable_factory_reset", "disable_developer_options", "disable_unknown_sources",
            "disable_verify_apps", "disable_play_store", "disable_google_apps",
            "disable_voice_assistants", "disable_printing", "disable_safe_boot"
        )

        keys.forEach { key ->
            restrictions[key] = isRestrictionEnabled(key)
        }

        return restrictions
    }

    fun resetAllRestrictions() {
        try {
            val allRestrictions = getAllRestrictions()
            allRestrictions.forEach { (key, enabled) ->
                if (enabled) {
                    disableRestriction(key)
                }
            }
            Log.d(TAG, "Reset all restrictions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset all restrictions", e)
        }
    }

    fun applyRestrictionsFromProfile(profile: String) {
        try {
            // Define different restriction profiles for different children or scenarios
            when (profile) {
                "strict" -> {
                    enableRestriction("disable_camera")
                    enableRestriction("disable_microphone")
                    enableRestriction("disable_bluetooth")
                    enableRestriction("disable_location")
                    enableRestriction("disable_screen_capture")
                    enableRestriction("disable_status_bar")
                }
                "moderate" -> {
                    enableRestriction("disable_camera")
                    enableRestriction("disable_screen_capture")
                    enableRestriction("disable_location")
                }
                "lenient" -> {
                    // Minimal restrictions
                    disableRestriction("disable_camera")
                    disableRestriction("disable_microphone")
                    disableRestriction("disable_bluetooth")
                    disableRestriction("disable_wifi")
                    disableRestriction("disable_location")
                }
            }
            Log.d(TAG, "Applied restrictions for profile: $profile")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply restrictions from profile", e)
        }
    }
}

