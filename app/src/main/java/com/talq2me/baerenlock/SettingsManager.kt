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
    private const val KEY_USE_CLOUD = "use_cloud_storage"
    private val gson = Gson() // Still used for local JSON parsing
    
    // Cache for settings to avoid repeated network calls
    private var cachedSettings: SettingsData? = null
    private val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Cloud sync state moved to CloudSyncManager

    data class SettingsData(
        val profile: String? = null,
        val pin: String? = null,
        val parentEmail: String? = null,
        val childName: String? = null,
        val rewardApps: Set<String>? = null,
        val aggressiveCleanup: Boolean? = null
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
            aggressiveCleanup = prefs.getBoolean("aggressive_cleanup", true)
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
            apply()
        }
    }

    /**
     * Loads settings from Supabase with timestamp comparison
     * Compares cloud vs local timestamps and uses the newer one
     */
    private suspend fun loadSettingsFromCloud(context: Context): SettingsData? {
        val cloudResult = CloudSyncManager.loadSettingsFromCloud(context)
        if (cloudResult == null) {
            return null
        }
        
        val (cloudData, cloudTimestamp) = cloudResult
        
        // Get local settings and timestamp
        val localData = loadSettingsFromLocal(context)
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val localTimestamp = prefs.getString("settings_timestamp", null)
        
        // Compare timestamps and use the newer one
        var settingsToUse: SettingsData
        var shouldSyncLocalToCloud: Boolean
        
        if (cloudTimestamp.isNullOrEmpty() && localTimestamp.isNullOrEmpty()) {
            // No timestamps - use cloud if it has data, otherwise local
            settingsToUse = if (cloudData.pin != null || cloudData.parentEmail != null) {
                cloudData
            } else {
                localData
            }
            shouldSyncLocalToCloud = settingsToUse == localData
            Log.d(TAG, "No timestamps available - using ${if (shouldSyncLocalToCloud) "local" else "cloud"} settings")
        } else if (cloudTimestamp.isNullOrEmpty()) {
            // Cloud has no timestamp - use local
            settingsToUse = localData
            shouldSyncLocalToCloud = true
            Log.d(TAG, "Cloud has no timestamp - using local settings")
        } else if (localTimestamp.isNullOrEmpty()) {
            // Local has no timestamp - use cloud
            settingsToUse = cloudData
            shouldSyncLocalToCloud = false
            Log.d(TAG, "Local has no timestamp - using cloud settings")
        } else {
            // Both have timestamps - compare and use the newer one
            try {
                val localTime = CloudSyncManager.parseTimestampForComparison(localTimestamp)
                val cloudTime = CloudSyncManager.parseTimestampForComparison(cloudTimestamp)
                
                Log.d(TAG, "Comparing settings timestamps - local: $localTimestamp ($localTime), cloud: $cloudTimestamp ($cloudTime)")
                
                if (cloudTime > localTime) {
                    settingsToUse = cloudData
                    shouldSyncLocalToCloud = false
                    Log.d(TAG, "Using cloud settings - cloud timestamp is newer")
                } else {
                    settingsToUse = localData
                    shouldSyncLocalToCloud = true
                    Log.d(TAG, "Using local settings - local timestamp is newer or equal")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error comparing settings timestamps, using local value", e)
                settingsToUse = localData
                shouldSyncLocalToCloud = true
            }
        }
        
        // Update cache and local storage with the chosen settings
        cachedSettings = settingsToUse
        saveSettingsToLocal(context, settingsToUse)
        
        // Update local timestamp if we used cloud settings
        if (!shouldSyncLocalToCloud && !cloudTimestamp.isNullOrEmpty()) {
            prefs.edit().putString("settings_timestamp", cloudTimestamp).apply()
            Log.d(TAG, "Updated local settings timestamp to match cloud: $cloudTimestamp")
        } else if (shouldSyncLocalToCloud && localTimestamp.isNullOrEmpty()) {
            val estTimestamp = CloudSyncManager.generateESTTimestamp()
            prefs.edit().putString("settings_timestamp", estTimestamp).apply()
            Log.d(TAG, "Set initial settings timestamp in EST ($estTimestamp)")
        }
        
        // If we kept local value and it differs from cloud, sync local to cloud
        if (shouldSyncLocalToCloud && settingsToUse != cloudData) {
            Log.d(TAG, "Local settings differ from cloud, syncing local to cloud")
            try {
                saveSettingsToCloud(context, settingsToUse)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync local settings to cloud: ${e.message}")
            }
        }
        
        return settingsToUse
    }

    /**
     * Saves settings to Supabase (delegates to CloudSyncManager)
     */
    private suspend fun saveSettingsToCloud(context: Context, data: SettingsData): Boolean {
        val result = CloudSyncManager.saveSettingsToCloud(context, data)
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
        
        // Update local timestamp
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val estTimestamp = CloudSyncManager.generateESTTimestamp()
        prefs.edit().putString("settings_timestamp", estTimestamp).apply()
        Log.d(TAG, "Updated local settings timestamp after PIN change: $estTimestamp")
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        val profile = ProfileManager.getCurrentProfile(context)
        updateLastUpdatedTimestamp(context, profile)
        
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
        
        // Update local timestamp
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val estTimestamp = CloudSyncManager.generateESTTimestamp()
        prefs.edit().putString("settings_timestamp", estTimestamp).apply()
        Log.d(TAG, "Updated local settings timestamp after email change: $estTimestamp")
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        val profile = ProfileManager.getCurrentProfile(context)
        updateLastUpdatedTimestamp(context, profile)
        
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
     * Also stores timestamp for comparison with cloud
     */
    fun writeRewardApps(context: Context, newRewardApps: Set<String>) {
        // Reward apps are stored in local SharedPreferences
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = CloudSyncManager.generateESTTimestamp()
        prefs.edit().apply {
            putStringSet("reward_apps", newRewardApps)
            putString("app_lists_timestamp", timestamp)
            apply()
        }
        Log.d(TAG, "Saved ${newRewardApps.size} reward apps locally, timestamp: $timestamp")
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        val profile = ProfileManager.getCurrentProfile(context)
        updateLastUpdatedTimestamp(context, profile)
        
        // Sync to cloud user_data table asynchronously
        CloudSyncManager.syncAppListsToCloudAsync(context)
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
        
        // Update local timestamp
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val estTimestamp = CloudSyncManager.generateESTTimestamp()
        prefs.edit().putString("settings_timestamp", estTimestamp).apply()
        Log.d(TAG, "Updated local settings timestamp after aggressive cleanup change: $estTimestamp")
        
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
        CloudSyncManager.syncAppListsToCloudAsync(context)
    }

    /**
     * Syncs current reward minutes to cloud user_data table (async)
     * This ensures accurate reward time when syncing from cloud
     * (Delegates to CloudSyncManager)
     */
    fun syncRewardMinutesToCloudAsync(context: Context, rewardMinutes: Int) {
        CloudSyncManager.syncRewardMinutesToCloudAsync(context, rewardMinutes)
    }
    
    /**
     * Downloads user_data from cloud for the current profile and applies it locally
     * This should be called when profile changes or on app startup
     */
    /**
     * Checks if cloud storage is enabled
     * Defaults to true (enabled) for new installations
     */
    fun isCloudStorageEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_USE_CLOUD, true)
    }

    /**
     * Enables or disables cloud storage
     */
    fun setCloudStorageEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_CLOUD, enabled).apply()
        Log.d(TAG, "Cloud storage ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Checks if daily reset is needed and triggers it if necessary
     * This should be called FIRST before any syncing operations
     * This function is async and returns immediately - the reset happens in the background
     */
    fun checkAndTriggerResetIfNeeded(context: Context) {
        settingsScope.launch {
            try {
                val profile = ProfileManager.getCurrentProfile(context)
                val needsReset = CloudSyncManager.checkIfResetNeeded(context, profile)
                if (needsReset) {
                    Log.d(TAG, "Reset needed for profile: $profile, performing local reset first then cloud reset")
                    // CRITICAL: Clear local banked_mins (and set last_reset/last_updated) before triggering cloud reset.
                    // Otherwise a later downloadUserDataFromCloud can see "local newer" and push stale banked_mins to cloud.
                    DailyResetAndSyncManager.performLocalResetOnly(context, profile)
                    val success = CloudSyncManager.triggerCloudReset(context, profile)
                    if (success) {
                        Log.d(TAG, "Successfully triggered cloud reset for profile: $profile")
                    } else {
                        Log.w(TAG, "Failed to trigger cloud reset for profile: $profile")
                    }
                } else {
                    Log.d(TAG, "No reset needed for profile: $profile")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking/triggering reset: ${e.message}", e)
            }
        }
    }
    
    /**
     * Downloads user_data from cloud for the current profile and applies it locally
     * This should be called AFTER checkAndTriggerResetIfNeeded() on app startup
     * (Delegates to CloudSyncManager)
     */
    fun downloadUserDataFromCloud(context: Context) {
        CloudSyncManager.downloadUserDataFromCloudAsync(context)
    }
    
    /**
     * Runs reset-if-needed (with local reset first) then download, then invokes onComplete on the main thread.
     * Use this on LauncherActivity onResume so download runs only after any reset has cleared local banked_mins,
     * preventing stale local values from being pushed to the cloud.
     */
    fun runResetThenDownload(context: Context, onComplete: (() -> Unit)? = null) {
        settingsScope.launch {
            try {
                val profile = ProfileManager.getCurrentProfile(context)
                val needsReset = CloudSyncManager.checkIfResetNeeded(context, profile)
                if (needsReset) {
                    Log.d(TAG, "Reset needed for profile: $profile, performing local reset first then cloud reset")
                    DailyResetAndSyncManager.performLocalResetOnly(context, profile)
                    CloudSyncManager.triggerCloudReset(context, profile)
                }
                CloudSyncManager.downloadUserDataFromCloud(context, profile, isRetry = false)
            } catch (e: Exception) {
                Log.e(TAG, "Error in runResetThenDownload: ${e.message}", e)
            } finally {
                onComplete?.let { cb ->
                    try {
                        withContext(Dispatchers.Main) { cb() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in runResetThenDownload onComplete: ${e.message}", e)
                    }
                }
            }
        }
    }

    /**
     * Preloads settings from cloud or local storage with timestamp comparison (call this on app startup)
     * Compares cloud vs local timestamps and uses the newer one
     * Also ensures device record exists in devices table
     */
    fun preloadSettings(context: Context) {
        settingsScope.launch {
            try {
                // Ensure device record exists in devices table (should be done first)
                CloudSyncManager.ensureDeviceRecord(context)
                
                // loadSettingsFromCloud now handles timestamp comparison internally
                val settings = loadSettingsFromCloud(context)
                if (settings != null) {
                    Log.d(TAG, "Preloaded settings (timestamp comparison completed, using newer source)")
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
     * (Delegates to CloudSyncManager)
     */
    fun syncHealthCheckToCloudAsync(context: Context, healthStatus: String, healthIssues: String?) {
        CloudSyncManager.syncHealthCheckToCloudAsync(context, healthStatus, healthIssues)
    }
    
    /**
     * Checks for profile changes from cloud devices table and applies them locally if different
     * This allows BaerenLock and BaerenEd to sync the active profile between apps
     * Should be called on app startup/resume, BEFORE ensureDeviceRecord
     * This version is synchronous with a timeout to ensure it completes before other code runs
     * @return true if profile was changed, false otherwise
     */
    fun checkAndApplyProfileFromCloud(context: Context): Boolean {
        // Use runBlocking with a timeout to ensure this completes before ensureDeviceRecord runs
        return runBlocking {
            try {
                withTimeout(5000) { // 5 second timeout
                    val cloudProfileData = CloudSyncManager.getActiveProfileFromCloud(context)
                    if (cloudProfileData != null) {
                        val currentProfile = ProfileManager.readProfile(context)
                        val localTimestamp = ProfileManager.getLocalProfileTimestamp(context)
                        val cloudTimestamp = cloudProfileData.lastUpdated
                        
                        Log.d(TAG, "Profile check: local=$currentProfile (timestamp=$localTimestamp), cloud=${cloudProfileData.profile} (timestamp=$cloudTimestamp)")
                        
                        // Compare timestamps to determine which is newer
                        val shouldApplyCloud = if (cloudTimestamp != null && localTimestamp != null) {
                            // Both timestamps exist - compare them
                            try {
                                val localTime = CloudSyncManager.parseTimestampForComparison(localTimestamp)
                                val cloudTime = CloudSyncManager.parseTimestampForComparison(cloudTimestamp)
                                val cloudIsNewer = cloudTime > localTime
                                Log.d(TAG, "checkAndApplyProfileFromCloud timestamp comparison:")
                                Log.d(TAG, "  Local: $localTimestamp (parsed: $localTime)")
                                Log.d(TAG, "  Cloud: $cloudTimestamp (parsed: $cloudTime)")
                                Log.d(TAG, "  Cloud is newer: $cloudIsNewer")
                                cloudIsNewer
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing timestamps for comparison", e)
                                // On error, default to applying cloud (safer)
                                true
                            }
                        } else if (cloudTimestamp != null) {
                            // Only cloud has timestamp - apply cloud
                            Log.d(TAG, "No local timestamp, applying cloud profile")
                            true
                        } else if (localTimestamp != null) {
                            // Only local has timestamp - keep local (don't apply cloud)
                            Log.d(TAG, "No cloud timestamp, keeping local profile")
                            false
                        } else {
                            // Neither has timestamp - apply cloud if profiles differ (cloud is source of truth when no timestamps)
                            val profilesDiffer = cloudProfileData.profile != currentProfile
                            Log.d(TAG, "Neither has timestamp, profiles differ: $profilesDiffer, will ${if (profilesDiffer) "apply cloud" else "keep local"}")
                            profilesDiffer
                        }
                        
                        if (shouldApplyCloud && cloudProfileData.profile != currentProfile) {
                            Log.d(TAG, "Profile changed in cloud: $currentProfile -> ${cloudProfileData.profile}, applying locally")
                            // Write profile WITHOUT syncing to cloud (we already have the cloud value)
                            // CRITICAL: Always use the cloud's timestamp - never generate a new one
                            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                            prefs.edit().apply {
                                putString("profile", cloudProfileData.profile)
                                if (cloudTimestamp != null) {
                                    putString("profile_timestamp", cloudTimestamp)
                                    Log.d(TAG, "Set local profile_timestamp to match cloud: $cloudTimestamp")
                                } else {
                                    // Remove local timestamp if cloud doesn't have one (shouldn't happen, but be safe)
                                    remove("profile_timestamp")
                                    Log.w(TAG, "Cloud has no timestamp, removed local profile_timestamp")
                                }
                                apply()
                            }
                            Log.d(TAG, "Applied cloud profile to local storage: ${cloudProfileData.profile} with cloud timestamp: $cloudTimestamp")
                            return@withTimeout true // Profile was changed
                        } else if (!shouldApplyCloud && cloudProfileData.profile != currentProfile && localTimestamp != null) {
                            // Only sync local to cloud if local has a timestamp (proving it was set locally)
                            // If neither has timestamp, we already applied cloud above, so don't sync local
                            Log.d(TAG, "Local profile is newer (has timestamp), will sync local to cloud: $currentProfile")
                            // Local is newer - sync to cloud
                            currentProfile?.let { profile ->
                                CloudSyncManager.syncActiveProfileToCloudAsync(context, profile)
                            }
                        } else if (!shouldApplyCloud && cloudProfileData.profile != currentProfile && localTimestamp == null) {
                            // Profiles differ but neither has timestamp - this shouldn't happen, but if it does, apply cloud
                            Log.w(TAG, "Profiles differ but neither has timestamp - applying cloud as source of truth: ${cloudProfileData.profile}")
                            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                            prefs.edit().apply {
                                putString("profile", cloudProfileData.profile)
                                apply()
                            }
                            return@withTimeout true
                        }
                    }
                    return@withTimeout false // Profile was not changed
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Timeout checking profile from cloud: ${e.message}")
                false
            } catch (e: Exception) {
                Log.w(TAG, "Error checking profile from cloud: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Updates last_updated timestamp in settings prefs to trigger cloud sync
     * This is called whenever settings that should sync to cloud are changed (as per Daily Reset Logic)
     */
    private fun updateLastUpdatedTimestamp(context: Context, profile: String) {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = CloudSyncManager.generateESTTimestamp()
        val key = "${profile}_last_updated_timestamp"
        prefs.edit().putString(key, timestamp).apply()
        Log.d(TAG, "Updated last_updated timestamp for profile $profile: $timestamp")
    }
}

