package com.talq2me.baerenlock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SettingsManager {

    private const val TAG = "SettingsManager"
    private const val LOCAL_PREFS_NAME = "settings"
    private val gson = Gson() // Still used for local JSON parsing
    
    // Cache for settings to avoid repeated network calls
    private var cachedSettings: SettingsData? = null
    private val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Heavy Supabase I/O lives in [SupabaseInterface] (this object stays settings-shaped).

    data class SettingsData(
        val profile: String? = null,
        val pin: String? = null,
        val parentEmail: String? = null,
        val childName: String? = null,
        val rewardApps: Set<String>? = null,
        val aggressiveCleanup: Boolean? = null,
        val rewardAudioMonitorEnabled: Boolean? = null,
        val rewardAudioLoudnessThreshold: Int? = null
    )

    /**
     * Reads settings from local SharedPreferences as fallback
     */
    private fun loadSettingsFromLocal(context: Context): SettingsData {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        
        // Parse reward_apps from StringSet or JSON
        val rewardApps = try {
            val rewardAppsSet = prefs.getStringSet("reward_apps", emptySet())
            rewardAppsSet ?: emptySet()
        } catch (e: Exception) {
            // Try parsing from JSON string if stored that way
            try {
                val rewardAppsJson = prefs.getString("reward_apps", null)
                if (rewardAppsJson != null && rewardAppsJson.isNotBlank()) {
                    val list = gson.fromJson<List<String>>(rewardAppsJson, object : TypeToken<List<String>>() {}.type)
                    list?.toSet() ?: emptySet()
                } else {
                    emptySet()
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Error parsing reward apps from local storage", e2)
                emptySet()
            }
        }
        
        return SettingsData(
            profile = prefs.getString("profile", null),
            pin = prefs.getString("parent_pin", prefs.getString("pin", null)),
            parentEmail = prefs.getString("parent_email", null),
            childName = prefs.getString("child_name", null),
            rewardApps = if (rewardApps.isNotEmpty()) rewardApps else null,
            aggressiveCleanup = prefs.getBoolean("aggressive_cleanup", true),
            rewardAudioMonitorEnabled = prefs.getBoolean("reward_audio_monitor_enabled", true),
            rewardAudioLoudnessThreshold = prefs.getInt(
                "reward_audio_loudness_threshold",
                AudioMonitor.DEFAULT_THRESHOLD
            )
        )
    }

    /**
     * Saves settings to local SharedPreferences for offline persistence
     */
    private fun saveSettingsToLocal(context: Context, data: SettingsData) {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            data.profile?.let { putString("profile", it) }
            data.pin?.let { 
                putString("pin", it)
                putString("parent_pin", it) // Also save as parent_pin for backward compatibility
            }
            data.parentEmail?.let { putString("parent_email", it) }
            data.childName?.let { putString("child_name", it) }
            data.rewardApps?.let { putStringSet("reward_apps", it) }
            data.aggressiveCleanup?.let { putBoolean("aggressive_cleanup", it) }
            data.rewardAudioMonitorEnabled?.let { putBoolean("reward_audio_monitor_enabled", it) }
            data.rewardAudioLoudnessThreshold?.let { putInt("reward_audio_loudness_threshold", it) }
            apply()
        }
    }

    /**
     * Loads settings from Supabase (cloud-first). Falls back to local only if cloud is unavailable.
     */
    private suspend fun loadSettingsFromCloud(context: Context): SettingsData? {
        val cloudResult = SupabaseInterface.loadSettingsFromCloud(context) ?: return null
        val (cloudData, _) = cloudResult
        cachedSettings = cloudData
        saveSettingsToLocal(context, cloudData)
        return cloudData
    }

    /**
     * Saves settings to Supabase (delegates to SupabaseInterface)
     */
    private suspend fun saveSettingsToCloud(context: Context, data: SettingsData): Boolean {
        val result = SupabaseInterface.saveSettingsToCloud(context, data)
        if (result) {
                cachedSettings = data
        }
        return result
    }

    /**
     * Synchronously reads profile from local storage only
     * Note: Profile is NOT stored in cloud settings table, only in local storage
     * @deprecated Use ProfileManager.readProfile() instead
     */
    @Deprecated("Use ProfileManager.readProfile() instead", ReplaceWith("ProfileManager.readProfile(context)"))
    fun readProfile(context: Context): String? {
        return ProfileManager.readProfile(context)
    }

    /**
     * Synchronously writes profile to local storage only
     * Note: Profile is NOT stored in cloud settings table, only in local storage
     * @deprecated Use ProfileManager.writeProfile() instead
     */
    @Deprecated("Use ProfileManager.writeProfile() instead", ReplaceWith("ProfileManager.writeProfile(context, newProfile)"))
    fun writeProfile(context: Context, newProfile: String) {
        ProfileManager.writeProfile(context, newProfile)
    }

    /**
     * Synchronously reads PIN from cache, cloud, or local storage (in that order)
     */
    fun readPin(context: Context): String? {
        // Try cache first
        cachedSettings?.pin?.let { return it }

        // Try to load from cloud synchronously (with timeout)
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val cloudSuccess = AtomicReference<Boolean>(false)
        
        settingsScope.launch {
            try {
                val settings = loadSettingsFromCloud(context)
                if (settings != null) {
                    cachedSettings = settings
                    saveSettingsToLocal(context, settings)
                    result.set(settings.pin)
                    cloudSuccess.set(true)
                } else {
                    cloudSuccess.set(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading PIN from cloud, will try local storage: ${e.message}")
                cloudSuccess.set(false)
            } finally {
                latch.countDown()
            }
        }
        
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while reading PIN", e)
        }
        
        // If cloud load succeeded, return the result
        if (cloudSuccess.get() == true) {
            return result.get()
        }
        
        // Fallback to local storage
        Log.d(TAG, "Falling back to local storage for PIN")
        val localSettings = loadSettingsFromLocal(context)
        if (localSettings.pin != null) {
            cachedSettings = localSettings
            return localSettings.pin
        }
        
        return null
    }

    /**
     * Synchronously writes PIN to cache and local storage, then attempts cloud save
     */
    fun writePin(context: Context, newPin: String) {
        // Update cache immediately
        val updatedSettings = cachedSettings?.copy(pin = newPin) ?: SettingsData(pin = newPin)
        cachedSettings = updatedSettings
        
        // Save to local storage immediately for offline persistence
        saveSettingsToLocal(context, updatedSettings)
        
        // Attempt to save to cloud asynchronously
        settingsScope.launch {
            try {
                val currentSettings = cachedSettings ?: loadSettingsFromLocal(context)
                // Create a clean SettingsData with only fields that belong in settings table
                val cloudSettings = SettingsData(
                    profile = null, // Not in settings table
                    pin = newPin,
                    parentEmail = currentSettings.parentEmail,
                    childName = null, // Not in settings table
                    rewardApps = null, // Not in settings table
                    aggressiveCleanup = currentSettings.aggressiveCleanup
                )
                saveSettingsToCloud(context, cloudSettings)
            } catch (e: Exception) {
                Log.w(TAG, "Could not save PIN to cloud, saved locally: ${e.message}")
            }
        }
    }

    /**
     * Synchronously reads email from cache, cloud, or local storage (in that order)
     */
    fun readEmail(context: Context): String? {
        cachedSettings?.parentEmail?.let { return it }

        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val cloudSuccess = AtomicReference<Boolean>(false)
        
        settingsScope.launch {
            try {
                val settings = loadSettingsFromCloud(context)
                if (settings != null) {
                    cachedSettings = settings
                    saveSettingsToLocal(context, settings)
                    result.set(settings.parentEmail)
                    cloudSuccess.set(true)
                } else {
                    cloudSuccess.set(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading email from cloud, will try local storage: ${e.message}")
                cloudSuccess.set(false)
            } finally {
                latch.countDown()
            }
        }
        
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while reading email", e)
        }
        
        if (cloudSuccess.get() == true) {
            return result.get()
        }
        
        Log.d(TAG, "Falling back to local storage for email")
        val localSettings = loadSettingsFromLocal(context)
        if (localSettings.parentEmail != null) {
            cachedSettings = localSettings
            return localSettings.parentEmail
        }
        
        return null
    }

    /**
     * Synchronously writes email to cache and local storage, then attempts cloud save
     */
    fun writeEmail(context: Context, newEmail: String) {
        val updatedSettings = cachedSettings?.copy(parentEmail = newEmail) ?: SettingsData(parentEmail = newEmail)
        cachedSettings = updatedSettings
        saveSettingsToLocal(context, updatedSettings)
        
        settingsScope.launch {
            try {
                val currentSettings = cachedSettings ?: loadSettingsFromLocal(context)
                // Create a clean SettingsData with only fields that belong in settings table
                val cloudSettings = SettingsData(
                    profile = null, // Not in settings table
                    pin = currentSettings.pin,
                    parentEmail = newEmail,
                    childName = null, // Not in settings table
                    rewardApps = null, // Not in settings table
                    aggressiveCleanup = currentSettings.aggressiveCleanup
                )
                saveSettingsToCloud(context, cloudSettings)
            } catch (e: Exception) {
                Log.w(TAG, "Could not save email to cloud, saved locally: ${e.message}")
            }
        }
    }

    /**
     * Synchronously reads child name from local storage only
     * Note: child_name is NOT stored in cloud settings table, only in local storage
     */
    fun readChildName(context: Context): String? {
        // Child name is stored in local SharedPreferences only (not in cloud settings table)
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("child_name", null)
    }

    /**
     * Synchronously writes child name to local storage only
     * Note: child_name is NOT stored in cloud settings table, only in local storage
     */
    fun writeChildName(context: Context, newChildName: String) {
        // Child name is stored in local SharedPreferences only
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("child_name", newChildName).apply()
    }

    /**
     * Synchronously reads reward apps from local storage only
     * Note: reward_apps is NOT stored in cloud settings table, only in local storage
     * (Note: reward_apps may be synced via user_data table per profile in BaerenEd, but not via settings table)
     */
    fun readRewardApps(context: Context): Set<String> {
        // Reward apps are stored in local SharedPreferences only (not in cloud settings table)
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet("reward_apps", emptySet()) ?: emptySet()
    }

    /**
     * Synchronously writes reward apps to local storage, then syncs to cloud user_data table
     * Note: reward_apps is stored in user_data table per profile (AM/BM), not in settings table
     * Writes local cache and syncs to DB.
     */
    fun writeRewardApps(context: Context, newRewardApps: Set<String>) {
        // Reward apps are stored in local SharedPreferences
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putStringSet("reward_apps", newRewardApps)
            apply()
        }
        Log.d(TAG, "Saved ${newRewardApps.size} reward apps locally")
        
        // Sync to cloud user_data table asynchronously
        SupabaseInterface.syncAppListsToCloudAsync(context)
    }

    /**
     * Synchronously reads aggressive cleanup setting from cache, cloud, or local storage (in that order)
     */
    fun readAggressiveCleanup(context: Context): Boolean {
        cachedSettings?.aggressiveCleanup?.let { return it }

        val latch = CountDownLatch(1)
        val result = AtomicReference<Boolean>(true)
        val cloudSuccess = AtomicReference<Boolean>(false)
        
        settingsScope.launch {
            try {
                val settings = loadSettingsFromCloud(context)
                if (settings != null) {
                    cachedSettings = settings
                    saveSettingsToLocal(context, settings)
                    result.set(settings.aggressiveCleanup ?: true)
                    cloudSuccess.set(true)
                } else {
                    cloudSuccess.set(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading aggressive cleanup from cloud, will try local storage: ${e.message}")
                cloudSuccess.set(false)
            } finally {
                latch.countDown()
            }
        }
        
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while reading aggressive cleanup", e)
        }
        
        if (cloudSuccess.get() == true) {
            return result.get()
        }
        
        Log.d(TAG, "Falling back to local storage for aggressive cleanup")
        val localSettings = loadSettingsFromLocal(context)
        cachedSettings = localSettings
        return localSettings.aggressiveCleanup ?: true
    }

    /**
     * Synchronously writes aggressive cleanup setting to cache and local storage, then attempts cloud save
     */
    fun writeAggressiveCleanup(context: Context, enabled: Boolean) {
        val updatedSettings = cachedSettings?.copy(aggressiveCleanup = enabled) ?: SettingsData(aggressiveCleanup = enabled)
        cachedSettings = updatedSettings
        saveSettingsToLocal(context, updatedSettings)
        
        settingsScope.launch {
            try {
                val currentSettings = cachedSettings ?: loadSettingsFromLocal(context)
                // Create a clean SettingsData with only fields that belong in settings table
                val cloudSettings = SettingsData(
                    profile = null, // Not in settings table
                    pin = currentSettings.pin,
                    parentEmail = currentSettings.parentEmail,
                    childName = null, // Not in settings table
                    rewardApps = null, // Not in settings table
                    aggressiveCleanup = enabled
                )
                saveSettingsToCloud(context, cloudSettings)
            } catch (e: Exception) {
                Log.w(TAG, "Could not save aggressive cleanup to cloud, saved locally: ${e.message}")
            }
        }
    }

    /**
     * Public method to trigger app list sync to cloud (async)
     * Call this after updating reward apps, blacklisted apps, or whitelisted apps
     */
    fun syncAppListsToCloudAsync(context: Context) {
        SupabaseInterface.syncAppListsToCloudAsync(context)
    }

    /**
     * Fetches user_data for the current profile (`af_daily_reset` + `af_get_user_data` inside [SupabaseInterface.downloadUserDataFromCloud] by default).
     * For the launcher sequence (reset once then pull without a second reset RPC), call [DbUserDataRefresh.runDailyResetThenFetchUserData] from a coroutine.
     */
    fun downloadUserDataFromCloud(context: Context) {
        SupabaseInterface.downloadUserDataFromCloudAsync(context)
    }

    /**
     * Preloads settings from cloud or local storage (call this on app startup).
     * Also ensures device record exists in devices table.
     */
    fun preloadSettings(context: Context) {
        settingsScope.launch {
            try {
                // Ensure device record exists in devices table (should be done first)
                SupabaseInterface.ensureDeviceRecord(context)
                
                // Cloud-first settings load
                val settings = loadSettingsFromCloud(context)
                if (settings != null) {
                    Log.d(TAG, "Preloaded settings from cloud")
                } else {
                    // Fallback to local storage if cloud load failed
                    val localSettings = loadSettingsFromLocal(context)
                    if (localSettings.pin != null || localSettings.parentEmail != null) {
                        cachedSettings = localSettings
                        Log.d(TAG, "Preloaded settings from local storage (cloud unavailable)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error preloading settings, trying local storage: ${e.message}")
                // Fallback to local storage
                val localSettings = loadSettingsFromLocal(context)
                if (localSettings.pin != null || localSettings.parentEmail != null) {
                    cachedSettings = localSettings
                    Log.d(TAG, "Preloaded settings from local storage (error: ${e.message})")
                }
            }
        }
    }
    
    /**
     * Syncs BaerenLock health check data to cloud devices table (per device, not per profile) (async)
     * (Delegates to SupabaseInterface)
     */
    fun syncHealthCheckToCloudAsync(context: Context, healthStatus: String, healthIssues: String?) {
        SupabaseInterface.syncHealthCheckToCloudAsync(context, healthStatus, healthIssues)
    }
    
    /**
     * Suspend version: checks for profile changes from cloud and applies locally.
     * Call from a coroutine with Dispatchers.IO (e.g. withContext(Dispatchers.IO)). Do NOT call from main thread.
     * @return true if profile was changed, false otherwise
     */
    suspend fun checkAndApplyProfileFromCloudSuspend(context: Context): Boolean {
        return try {
            withTimeout(5000) {
                val cloudProfileData = SupabaseInterface.getActiveProfileFromCloud(context)
                if (cloudProfileData != null) {
                    val currentProfile = ProfileManager.readProfile(context)
                    if (cloudProfileData.profile != currentProfile) {
                        Log.d(TAG, "Applying cloud profile locally: $currentProfile -> ${cloudProfileData.profile}")
                        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                            .edit()
                            .putString("profile", cloudProfileData.profile)
                            .apply()
                        return@withTimeout true
                    }
                }
                return@withTimeout false
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Timeout checking profile from cloud: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking profile from cloud: ${e.message}")
            false
        }
    }

    /**
     * Blocking version. Do NOT call from main/UI thread (causes ANR). Prefer checkAndApplyProfileFromCloudSuspend from a coroutine.
     * @return true if profile was changed, false otherwise
     */
    fun checkAndApplyProfileFromCloud(context: Context): Boolean = runBlocking(Dispatchers.IO) {
        checkAndApplyProfileFromCloudSuspend(context)
    }

}

