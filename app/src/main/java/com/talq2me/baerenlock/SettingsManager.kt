package com.talq2me.baerenlock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SettingsManager {

    private const val TAG = "SettingsManager"
    private const val LOCAL_PREFS_NAME = "settings"
    private val gson = Gson()
    private val client = OkHttpClient()
    
    // Cache for settings to avoid repeated network calls
    private var cachedSettings: SettingsData? = null
    private val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track which profiles we've already triggered reset for in this session to prevent loops
    // Use synchronized set to prevent race conditions when multiple coroutines run simultaneously
    private val resetTriggeredProfiles = Collections.synchronizedSet(mutableSetOf<String>()) as MutableSet<String>
    
    // Track which profiles are currently being downloaded to prevent concurrent downloads
    private val downloadingProfiles = Collections.synchronizedSet(mutableSetOf<String>()) as MutableSet<String>

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
     * Gets Supabase URL from BuildConfig
     */
    private fun getSupabaseUrl(context: Context): String {
        return try {
            BuildConfig.SUPABASE_URL.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase URL from BuildConfig", e)
            ""
        }
    }

    /**
     * Gets Supabase API key from BuildConfig
     */
    private fun getSupabaseKey(context: Context): String {
        return try {
            BuildConfig.SUPABASE_KEY.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase key from BuildConfig", e)
            ""
        }
    }

    /**
     * Checks if Supabase is configured
     */
    private fun isConfigured(context: Context): Boolean {
        val url = getSupabaseUrl(context)
        val key = getSupabaseKey(context)
        return url.isNotBlank() && key.isNotBlank()
    }

    /**
     * Loads settings from Supabase (async)
     */
    private suspend fun loadSettingsFromCloud(context: Context): SettingsData? = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, returning null")
            return@withContext null
        }

        try {
            val url = "${getSupabaseUrl(context)}/rest/v1/settings?id=eq.1&select=*"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                response.close()

                if (responseBody != null && responseBody != "[]" && responseBody != "{}") {
                    val settingsArray = gson.fromJson(responseBody, object : TypeToken<Array<Map<String, Any>>>() {}.type) as? Array<Map<String, Any>>
                    if (settingsArray != null && settingsArray.isNotEmpty()) {
                        val settings = settingsArray[0]
                        val pin = settings["pin"] as? String
                        val parentEmail = settings["parent_email"] as? String
                        val aggressiveCleanup = settings["aggressive_cleanup"] as? Boolean
                        
                        // Note: profile, child_name, and reward_apps are NOT in settings table
                        // They should be stored locally or in user_data table per profile
                        // Create SettingsData with ONLY fields from settings table
                        // Explicitly set profile, childName, and rewardApps to null to ensure they're not cached
                        val data = SettingsData(
                            profile = null, // Not in cloud settings table - must be null
                            pin = pin,
                            parentEmail = parentEmail,
                            childName = null, // Not in cloud settings table - must be null
                            rewardApps = null, // Not in cloud settings table - must be null
                            aggressiveCleanup = aggressiveCleanup
                        )
                        cachedSettings = data
                        Log.d(TAG, "Loaded settings from cloud: pin=${pin?.take(1)}..., email=$parentEmail, aggressiveCleanup=$aggressiveCleanup")
                        return@withContext data
                    }
                }
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to load settings: ${response.code} - $errorBody")
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings from cloud", e)
        }
        null
    }

    /**
     * Saves settings to Supabase (async)
     */
    private suspend fun saveSettingsToCloud(context: Context, data: SettingsData): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping save")
            return@withContext false
        }

        try {
            // Explicitly create a map with ONLY the fields that exist in the settings table
            // Do NOT include: profile, child_name, reward_apps (they don't exist in settings table)
            val settingsMap = mutableMapOf<String, Any?>()
            
            // Only add fields that are in the settings table schema
            if (data.pin != null) {
                settingsMap["pin"] = data.pin
            }
            if (data.parentEmail != null) {
                settingsMap["parent_email"] = data.parentEmail
            }
            if (data.aggressiveCleanup != null) {
                settingsMap["aggressive_cleanup"] = data.aggressiveCleanup
            }
            
            // Ensure we never include profile, child_name, or reward_apps
            // (they should never be in this map, but being explicit)
            
            // Safety check: Remove any invalid keys that shouldn't be in settings table
            val validKeys = listOf("pin", "parent_email", "aggressive_cleanup")
            val cleanedMap = settingsMap.filterKeys { it in validKeys }
            
            // Verify no invalid keys are present
            val invalidKeys = cleanedMap.keys - validKeys
            if (invalidKeys.isNotEmpty()) {
                Log.e(TAG, "ERROR: Invalid keys found in settings map: $invalidKeys")
            }
            
            val json = gson.toJson(cleanedMap)
            Log.d(TAG, "Sending settings to cloud (JSON keys: ${cleanedMap.keys}): $json")
            
            val baseUrl = "${getSupabaseUrl(context)}/rest/v1/settings"
            val requestBody = json.toRequestBody("application/json".toMediaType())

            // Try to update existing settings, fallback to insert
            val updateUrl = "$baseUrl?id=eq.1"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Prefer", "return=minimal")
                .build()

            val patchResponse = client.newCall(patchRequest).execute()
            if (patchResponse.isSuccessful) {
                patchResponse.close()
                cachedSettings = data
                Log.d(TAG, "Updated settings in cloud")
                return@withContext true
            } else if (patchResponse.code == 404) {
                // No existing record, try to insert
                patchResponse.close()
                val insertRequest = Request.Builder()
                    .url(baseUrl)
                    .post(requestBody)
                    .addHeader("apikey", getSupabaseKey(context))
                    .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                val insertResponse = client.newCall(insertRequest).execute()
                if (insertResponse.isSuccessful) {
                    insertResponse.close()
                    cachedSettings = data
                    Log.d(TAG, "Inserted settings in cloud")
                    return@withContext true
                } else {
                    val errorBody = insertResponse.body?.string() ?: "Unknown error"
                    Log.e(TAG, "Failed to insert settings: ${insertResponse.code} - $errorBody")
                    insertResponse.close()
                }
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to update settings: ${patchResponse.code} - $errorBody")
                patchResponse.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings to cloud", e)
        }
        false
    }

    /**
     * Synchronously reads profile from local storage only
     * Note: Profile is NOT stored in cloud settings table, only in local storage
     */
    fun readProfile(context: Context): String? {
        // Profile is stored in local SharedPreferences only (not in cloud settings table)
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("profile", null)
    }

    /**
     * Synchronously writes profile to local storage only
     * Note: Profile is NOT stored in cloud settings table, only in local storage
     */
    fun writeProfile(context: Context, newProfile: String) {
        // Profile is stored in local SharedPreferences only
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("profile", newProfile).apply()
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
     */
    fun writeRewardApps(context: Context, newRewardApps: Set<String>) {
        // Reward apps are stored in local SharedPreferences
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("reward_apps", newRewardApps).apply()
        Log.d(TAG, "Saved ${newRewardApps.size} reward apps locally")
        
        // Sync to cloud user_data table asynchronously
        settingsScope.launch {
            try {
                val success = syncAppListsToCloud(context)
                if (success) {
                    Log.d(TAG, "Successfully synced reward apps to cloud")
                } else {
                    Log.w(TAG, "Failed to sync reward apps to cloud (check logs above for details)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception syncing reward apps to cloud: ${e.message}", e)
            }
        }
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
        settingsScope.launch {
            try {
                syncAppListsToCloud(context)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync app lists to cloud: ${e.message}")
            }
        }
    }

    /**
     * Syncs app lists (reward_apps, blacklisted_apps, white_listed_apps) to cloud user_data table
     * Converts local profile (A/B) to cloud profile format (AM/BM)
     */
    private suspend fun syncAppListsToCloud(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping app list sync")
            return@withContext false
        }

        try {
            // Get current profile and convert to cloud format (A -> AM, B -> BM)
            val localProfile = readProfile(context) ?: "A"
            val cloudProfile = when (localProfile) {
                "A" -> "AM"
                "B" -> "BM"
                else -> localProfile // If already AM/BM, use as-is
            }
            
            // Read app lists from local storage
            val rewardPrefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
            val blacklistPrefs = context.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
            val whitelistPrefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
            
            val rewardAppsSet = rewardPrefs.getStringSet("reward_apps", emptySet()) ?: emptySet()
            val blacklistedAppsSet = blacklistPrefs.getStringSet("packages", emptySet()) ?: emptySet()
            val whiteListedAppsSet = whitelistPrefs.getStringSet("allowed", emptySet()) ?: emptySet()
            
            // Convert Sets to JSON array strings
            val rewardAppsJson = if (rewardAppsSet.isNotEmpty()) {
                gson.toJson(rewardAppsSet.toList())
            } else {
                null
            }
            
            val blacklistedAppsJson = if (blacklistedAppsSet.isNotEmpty()) {
                gson.toJson(blacklistedAppsSet.toList())
            } else {
                null
            }
            
            val whiteListedAppsJson = if (whiteListedAppsSet.isNotEmpty()) {
                gson.toJson(whiteListedAppsSet.toList())
            } else {
                null
            }
            
            // Build update map - only include non-null values
            val updateMap = mutableMapOf<String, Any?>()
            rewardAppsJson?.let { updateMap["reward_apps"] = it }
            blacklistedAppsJson?.let { updateMap["blacklisted_apps"] = it }
            whiteListedAppsJson?.let { updateMap["white_listed_apps"] = it }
            
            // If no app lists to sync, skip
            if (updateMap.isEmpty()) {
                Log.d(TAG, "No app lists to sync to cloud")
                return@withContext true
            }
            
            val json = gson.toJson(updateMap)
            val baseUrl = "${getSupabaseUrl(context)}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            // Update user_data for this profile
            val updateUrl = "$baseUrl?profile=eq.$cloudProfile"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Prefer", "return=minimal")
                .build()
            
            val patchResponse = client.newCall(patchRequest).execute()
            if (patchResponse.isSuccessful) {
                patchResponse.close()
                Log.d(TAG, "Successfully synced app lists to cloud for profile: $cloudProfile")
                Log.d(TAG, "Synced data: reward_apps=${rewardAppsSet.size}, blacklisted=${blacklistedAppsSet.size}, whitelisted=${whiteListedAppsSet.size}")
                return@withContext true
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to sync app lists to cloud: ${patchResponse.code} - $errorBody")
                Log.e(TAG, "Profile: $cloudProfile, URL: $updateUrl, JSON: $json")
                patchResponse.close()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing app lists to cloud", e)
            return@withContext false
        }
    }

    /**
     * Syncs current reward minutes to cloud user_data table (async)
     * This ensures accurate reward time when syncing from cloud
     */
    fun syncRewardMinutesToCloudAsync(context: Context, rewardMinutes: Int) {
        settingsScope.launch {
            try {
                syncRewardMinutesToCloud(context, rewardMinutes)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync reward minutes to cloud: ${e.message}")
            }
        }
    }

    /**
     * Syncs current reward minutes to cloud user_data table
     * Updates the banked_mins column (or current_reward_minutes if we add that column)
     */
    private suspend fun syncRewardMinutesToCloud(context: Context, rewardMinutes: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping reward minutes sync")
            return@withContext false
        }

        try {
            // Get current profile and convert to cloud format (A -> AM, B -> BM)
            val localProfile = readProfile(context) ?: "A"
            val cloudProfile = when (localProfile) {
                "A" -> "AM"
                "B" -> "BM"
                else -> localProfile // If already AM/BM, use as-is
            }
            
            // Generate timestamp in ISO 8601 format with EST timezone (same format as BaerenEd)
            val lastUpdated = generateESTTimestamp()
            
            // Update banked_mins AND last_updated timestamp in user_data table
            // This ensures the timestamp reflects when reward time was last changed
            val updateMap = mapOf(
                "banked_mins" to rewardMinutes,
                "last_updated" to lastUpdated
            )
            
            val json = gson.toJson(updateMap)
            val baseUrl = "${getSupabaseUrl(context)}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            // Update user_data for this profile
            val updateUrl = "$baseUrl?profile=eq.$cloudProfile"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Prefer", "return=minimal")
                .build()
            
            val patchResponse = client.newCall(patchRequest).execute()
            if (patchResponse.isSuccessful) {
                patchResponse.close()
                Log.d(TAG, "Successfully synced reward minutes to cloud for profile: $cloudProfile, minutes: $rewardMinutes, timestamp: $lastUpdated")
                return@withContext true
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to sync reward minutes to cloud: ${patchResponse.code} - $errorBody")
                Log.e(TAG, "Profile: $cloudProfile, URL: $updateUrl, JSON: $json")
                patchResponse.close()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing reward minutes to cloud", e)
            return@withContext false
        }
    }
    
    /**
     * Downloads user_data from cloud for the current profile and applies it locally
     * This should be called when profile changes or on app startup
     */
    fun downloadUserDataFromCloud(context: Context) {
        settingsScope.launch {
            try {
                val localProfile = readProfile(context) ?: "A"
                val cloudProfile = when (localProfile) {
                    "A" -> "AM"
                    "B" -> "BM"
                    else -> localProfile
                }
                
                downloadUserDataFromCloud(context, cloudProfile, isRetry = false)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading user_data from cloud: ${e.message}", e)
            }
        }
    }
    
    /**
     * Downloads user_data from cloud for a specific profile
     * @param isRetry true if this is a retry after triggering a reset (prevents infinite loops)
     */
    private suspend fun downloadUserDataFromCloud(context: Context, cloudProfile: String, isRetry: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping user_data download")
            return@withContext false
        }

        // Prevent concurrent downloads for the same profile
        if (downloadingProfiles.contains(cloudProfile)) {
            Log.d(TAG, "Already downloading user_data for profile: $cloudProfile, skipping duplicate request")
            return@withContext false
        }
        
        downloadingProfiles.add(cloudProfile)
        
        try {
            val url = "${getSupabaseUrl(context)}/rest/v1/user_data?profile=eq.$cloudProfile&select=*"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                response.close()

                if (responseBody != "[]" && responseBody != "{}") {
                    val dataList = gson.fromJson(responseBody, object : TypeToken<List<Map<String, Any>>>() {}.type) as? List<Map<String, Any>>
                    val userData = dataList?.firstOrNull()
                    
                    if (userData != null) {
                        val today = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val todayMillis = today.timeInMillis
                        
                        // Only check for reset if this is not a retry (to prevent infinite loops)
                        var needsCloudReset = false
                        if (!isRetry) {
                            val lastReset = userData["last_reset"] as? String
                            if (lastReset != null) {
                                try {
                                    val resetDate = parseCloudTimestamp(lastReset)
                                    val resetCalendar = Calendar.getInstance().apply {
                                        timeInMillis = resetDate.time
                                        set(Calendar.HOUR_OF_DAY, 0)
                                        set(Calendar.MINUTE, 0)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    
                                    if (resetCalendar.timeInMillis != todayMillis) {
                                        // Different day - trigger cloud reset by doing a minimal update
                                        // The database trigger will reset daily progress fields
                                        Log.d(TAG, "Cloud last_reset is from different day, will trigger cloud reset for profile: $cloudProfile")
                                        needsCloudReset = true
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing last_reset timestamp: ${e.message}")
                                }
                            } else {
                                // No last_reset - might be first time, trigger reset to initialize
                                Log.d(TAG, "No last_reset in cloud, will trigger reset to initialize for profile: $cloudProfile")
                                needsCloudReset = true
                            }
                        }
                        
                        // Trigger cloud reset if needed (this will cause the database trigger to fire)
                        // Note: The database trigger resets required_tasks, practice_tasks, berries_earned
                        // but NOT banked_mins (reward minutes persist across days)
                        // Only trigger if we haven't already triggered for this profile in this session
                        if (needsCloudReset && !resetTriggeredProfiles.contains(cloudProfile)) {
                            try {
                                // Mark that we're triggering reset for this profile to prevent loops
                                resetTriggeredProfiles.add(cloudProfile)
                                
                                // Do a minimal update to trigger the database reset function
                                // The trigger will reset daily progress fields (but not banked_mins)
                                val resetUpdateMap = mapOf<String, Any?>(
                                    "last_updated" to null // Let database set this via trigger
                                )
                                val resetJson = gson.toJson(resetUpdateMap)
                                val resetRequestBody = resetJson.toRequestBody("application/json".toMediaType())
                                val resetPatchRequest = Request.Builder()
                                    .url("${getSupabaseUrl(context)}/rest/v1/user_data?profile=eq.$cloudProfile")
                                    .patch(resetRequestBody)
                                    .addHeader("apikey", getSupabaseKey(context))
                                    .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                                    .addHeader("Prefer", "return=minimal")
                                    .build()
                                
                                val resetResponse = client.newCall(resetPatchRequest).execute()
                                if (resetResponse.isSuccessful) {
                                    resetResponse.close()
                                    Log.d(TAG, "Triggered cloud reset for profile: $cloudProfile (database trigger will reset daily progress)")
                                    // Wait a bit for the trigger to complete, then re-download
                                    kotlinx.coroutines.delay(1000) // Wait 1 second for database trigger to complete
                                    // Re-download to get the updated values (mark as retry to prevent loop)
                                    // Remove from downloading set first so retry can proceed
                                    downloadingProfiles.remove(cloudProfile)
                                    val retryResult = downloadUserDataFromCloud(context, cloudProfile, isRetry = true)
                                    if (retryResult) {
                                        return@withContext true
                                    } else {
                                        // If retry failed, continue with current values
                                        Log.w(TAG, "Retry download after reset failed, using current values")
                                    }
                                } else {
                                    val errorBody = resetResponse.body?.string() ?: "Unknown error"
                                    Log.w(TAG, "Failed to trigger cloud reset: ${resetResponse.code} - $errorBody")
                                    resetResponse.close()
                                    // Remove from set so we can try again later
                                    resetTriggeredProfiles.remove(cloudProfile)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error triggering cloud reset: ${e.message}")
                                // Remove from set so we can try again later
                                resetTriggeredProfiles.remove(cloudProfile)
                            }
                        } else if (needsCloudReset && resetTriggeredProfiles.contains(cloudProfile)) {
                            // Already triggered reset for this profile - skip to prevent loop
                            Log.d(TAG, "Already triggered reset for profile: $cloudProfile in this session, skipping to prevent loop")
                            // Just use the current cloud values (they should be correct after reset)
                        }
                        
                        // TIMESTAMP-BASED SYNC: Compare local banked_mins timestamp vs cloud last_updated timestamp
                        // Apply whichever is newer (most recent timestamp wins)
                        val rewardPrefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)
                        val currentLocalBankedMins = rewardPrefs.getInt("current_reward_minutes", 0)
                        val localBankedMinsTimestamp = rewardPrefs.getString("banked_mins_timestamp", null)
                        val cloudBankedMins = (userData["banked_mins"] as? Number)?.toInt() ?: 0
                        val cloudTimestamp = userData["last_updated"] as? String
                        
                        var bankedMinsToApply: Int
                        var shouldSyncLocalToCloud: Boolean
                        
                        if (localBankedMinsTimestamp.isNullOrEmpty() && currentLocalBankedMins == 0) {
                            // No local timestamp and local is 0 - fresh install/reset
                            // On fresh install, ALWAYS default to 0 to prevent stale cloud data from being applied
                            // This ensures fresh installs start clean, even if cloud has old data
                            bankedMinsToApply = 0
                            shouldSyncLocalToCloud = true // Sync 0 to cloud to clear any stale data
                            Log.d(TAG, "Fresh install detected - setting banked_mins to 0 (cloud had $cloudBankedMins, but ignoring on fresh install to prevent stale data)")
                        } else if (cloudTimestamp.isNullOrEmpty()) {
                            // No cloud timestamp - keep local value and sync to cloud
                            bankedMinsToApply = currentLocalBankedMins
                            shouldSyncLocalToCloud = true
                            Log.d(TAG, "Keeping local banked_mins ($currentLocalBankedMins) - cloud has no timestamp")
                        } else {
                            // Both have timestamps - compare and use the newer one
                            try {
                                val localTime = if (!localBankedMinsTimestamp.isNullOrEmpty()) {
                                    parseTimestampForComparison(localBankedMinsTimestamp)
                                } else {
                                    // No local timestamp but local has value - treat as very old (0) to prefer cloud
                                    0L
                                }
                                val cloudTime = parseTimestampForComparison(cloudTimestamp)
                                
                                if (cloudTime > localTime) {
                                    // Cloud is newer - apply cloud value
                                    bankedMinsToApply = cloudBankedMins
                                    shouldSyncLocalToCloud = false
                                    Log.d(TAG, "Applying cloud banked_mins ($cloudBankedMins) - cloud timestamp ($cloudTimestamp) is newer than local ($localBankedMinsTimestamp)")
                                } else {
                                    // Local is newer or equal - keep local value and sync to cloud
                                    bankedMinsToApply = currentLocalBankedMins
                                    shouldSyncLocalToCloud = true
                                    Log.d(TAG, "Keeping local banked_mins ($currentLocalBankedMins) - local timestamp ($localBankedMinsTimestamp) is newer than or equal to cloud ($cloudTimestamp)")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error comparing timestamps for banked_mins, keeping local value", e)
                                bankedMinsToApply = currentLocalBankedMins
                                shouldSyncLocalToCloud = true
                            }
                        }
                        
                        // Update local reward minutes with the chosen value
                        val editor = rewardPrefs.edit()
                        editor.putInt("current_reward_minutes", bankedMinsToApply)
                        editor.putLong("last_reward_date", todayMillis) // Update date to today
                        
                        // If we applied cloud value, update local timestamp to match cloud timestamp
                        if (!shouldSyncLocalToCloud && !cloudTimestamp.isNullOrEmpty()) {
                            editor.putString("banked_mins_timestamp", cloudTimestamp)
                            Log.d(TAG, "Updated local banked_mins timestamp to match cloud: $cloudTimestamp")
                        } else if (shouldSyncLocalToCloud && localBankedMinsTimestamp.isNullOrEmpty()) {
                            // Fresh install - set a timestamp in EST to prevent cloud value from being applied again
                            val estTimestamp = generateESTTimestamp()
                            editor.putString("banked_mins_timestamp", estTimestamp)
                            Log.d(TAG, "Set initial banked_mins_timestamp in EST ($estTimestamp) to prevent cloud value from being applied again")
                        }
                        editor.apply()
                        
                        // Also update RewardManager's current value
                        try {
                            val rewardManagerClass = Class.forName("com.talq2me.baerenlock.RewardManager")
                            val currentMinutesField = rewardManagerClass.getDeclaredField("currentRewardMinutes")
                            currentMinutesField.isAccessible = true
                            currentMinutesField.set(null, bankedMinsToApply)
                            Log.d(TAG, "Updated RewardManager.currentRewardMinutes to $bankedMinsToApply (was: $currentLocalBankedMins, cloud: $cloudBankedMins)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not update RewardManager.currentRewardMinutes directly: ${e.message}")
                        }
                        
                        // If we kept local value and it differs from cloud, sync local to cloud
                        if (shouldSyncLocalToCloud && bankedMinsToApply != cloudBankedMins) {
                            Log.d(TAG, "Local banked_mins ($bankedMinsToApply) differs from cloud ($cloudBankedMins), syncing local to cloud")
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    syncRewardMinutesToCloud(context, bankedMinsToApply)
                                    Log.d(TAG, "Synced local banked_mins ($bankedMinsToApply) to cloud")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error syncing local banked_mins to cloud", e)
                                }
                            }
                        }
                        
                        Log.d(TAG, "Downloaded and applied banked_mins from cloud: $bankedMinsToApply (cloud: $cloudBankedMins, local was: $currentLocalBankedMins) for profile: $cloudProfile")
                        
                        // Send broadcast to update UI
                        val intent = Intent("com.talq2me.baerenlock.ACTION_REWARD_TIME_UPDATED")
                        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                        
                        return@withContext true
                    }
                }
                
                Log.d(TAG, "No user_data found in cloud for profile: $cloudProfile")
                return@withContext false
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to download user_data from cloud: ${response.code} - $errorBody")
                response.close()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading user_data from cloud", e)
            return@withContext false
        } finally {
            // Always remove from downloading set when done
            downloadingProfiles.remove(cloudProfile)
        }
    }
    
    /**
     * Generates a timestamp in ISO 8601 format with EST timezone.
     * This ensures all local timestamps use EST, matching cloud timestamps.
     */
    private fun generateESTTimestamp(): String {
        val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
        val now = java.util.Date()
        val offsetMillis = estTimeZone.getOffset(now.time)
        val offsetHours = offsetMillis / (1000 * 60 * 60)
        val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
        val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        return dateFormat.format(now) + offsetString
    }
    
    /**
     * Parses cloud timestamp (ISO 8601 format) to Date
     */
    private fun parseCloudTimestamp(timestamp: String): Date {
        return try {
            val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            isoFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            isoFormat.parse(timestamp.substringBefore(".")) ?: Date()
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing timestamp: $timestamp", e)
            Date()
        }
    }
    
    /**
     * Parses timestamp string to milliseconds for comparison
     * Handles both ISO 8601 format (from Supabase) and our EST format
     */
    private fun parseTimestampForComparison(timestamp: String): Long {
        return try {
            // Handle ISO 8601 formats with timezone
            // Formats: "2026-01-06T19:52:08.190Z", "2026-01-07T00:35:11.680263+00:00", "2026-01-06T19:35:11-05:00"
            
            // Check if it has timezone indicator
            val hasZ = timestamp.endsWith("Z")
            val hasOffset = timestamp.matches(Regex(".*[+-]\\d{2}:\\d{2}$"))
            
            val dateFormat = when {
                hasZ -> {
                    // UTC timezone (Z suffix)
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }
                }
                hasOffset -> {
                    // Has timezone offset (+05:00 or -05:00)
                    // Try with milliseconds first, fallback to manual parsing if needed
                    try {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.getDefault())
                    } catch (e: Exception) {
                        // Fallback to manual parsing if XXX not supported (API < 24)
                        // Find the last occurrence of either '+' or '-'
                        val lastPlusIndex = timestamp.lastIndexOf('+')
                        val lastMinusIndex = timestamp.lastIndexOf('-')
                        val delimiterIndex = if (lastPlusIndex > lastMinusIndex) lastPlusIndex else lastMinusIndex
                        val delimiter = if (lastPlusIndex > lastMinusIndex) '+' else '-'
                        val basePart = timestamp.substring(0, delimiterIndex)
                        val offsetPart = timestamp.substring(delimiterIndex + 1)
                        val offsetHours = offsetPart.substringBefore(":").toInt()
                        val offsetMinutes = offsetPart.substringAfter(":").toInt()
                        val offsetMillis = (offsetHours * 60 + offsetMinutes) * 60 * 1000L
                        val sign = if (delimiter == '+') 1 else -1
                        val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault()).parse(basePart)
                        return (parsed?.time ?: 0L) - (sign * offsetMillis)
                    }
                }
                else -> {
                    // No timezone - assume EST (our format)
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault()).apply {
                        timeZone = java.util.TimeZone.getTimeZone("America/New_York")
                    }
                }
            }
            
            val date = dateFormat.parse(timestamp)
            date?.time ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp for comparison: $timestamp", e)
            0L
        }
    }

    /**
     * Preloads settings from cloud or local storage (call this on app startup)
     */
    fun preloadSettings(context: Context) {
        settingsScope.launch {
            try {
                val cloudSettings = loadSettingsFromCloud(context)
                if (cloudSettings != null) {
                    Log.d(TAG, "Preloaded settings from cloud")
                } else {
                    // Fallback to local storage
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
}

