package com.talq2me.baerenlock

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.Collections

/**
 * Manages all cloud synchronization operations for BaerenLock
 * Handles syncing settings, user data, app lists, reward minutes, and health checks to Supabase
 * 
 * CRITICAL REQUIREMENT: ALL sync operations MUST compare cloud and local timestamps before updating.
 * Never update cloud data without first checking if cloud timestamp is newer than local timestamp.
 * This prevents overwriting newer cloud data with older local data.
 * 
 * See CLOUD_SYNC_TIMESTAMP_REQUIREMENT.md for full documentation.
 */
object CloudSyncManager {
    private const val TAG = "CloudSyncManager"
    private const val LOCAL_PREFS_NAME = "settings"
    private val gson = Gson()
    private val client = OkHttpClient()
    
    // Track which profiles we've already triggered reset for in this session to prevent loops
    private val resetTriggeredProfiles = Collections.synchronizedSet(mutableSetOf<String>()) as MutableSet<String>
    
    // Track which profiles are currently being downloaded to prevent concurrent downloads
    private val downloadingProfiles = Collections.synchronizedSet(mutableSetOf<String>()) as MutableSet<String>
    
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
     * Gets the unique Android device ID (ANDROID_ID from Settings.Secure)
     * This is a unique identifier per device/app signing key combination
     */
    private fun getDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device ID", e)
            "unknown"
        }
    }
    
    /**
     * Gets the user-friendly device name
     * Uses Settings.Global.DEVICE_NAME on API 25+, falls back to Build.MANUFACTURER + Build.MODEL
     */
    private fun getDeviceName(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                // API 25+ supports Settings.Global.DEVICE_NAME
                val deviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                if (!deviceName.isNullOrBlank()) {
                    return deviceName
                }
            }
            // Fallback to manufacturer + model
            "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device name", e)
            "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        }
    }
    
    /**
     * Checks if Supabase is configured
     */
    fun isConfigured(context: Context): Boolean {
        val url = getSupabaseUrl(context)
        val key = getSupabaseKey(context)
        return url.isNotBlank() && key.isNotBlank()
    }

    /**
     * Loads settings from Supabase
     * Returns Pair of (SettingsData, cloudTimestamp) or null
     */
    suspend fun loadSettingsFromCloud(context: Context): Pair<SettingsManager.SettingsData, String?>? = withContext(Dispatchers.IO) {
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
                        // Read last_updated timestamp from settings table
                        val cloudTimestamp = settings["last_updated"] as? String
                        
                        // Note: profile, child_name, and reward_apps are NOT in settings table
                        val data = SettingsManager.SettingsData(
                            profile = null, // Not in cloud settings table - must be null
                            pin = pin,
                            parentEmail = parentEmail,
                            childName = null, // Not in cloud settings table - must be null
                            rewardApps = null, // Not in cloud settings table - must be null
                            aggressiveCleanup = aggressiveCleanup
                        )
                        Log.d(TAG, "Loaded settings from cloud: pin=${pin?.take(1)}..., email=$parentEmail, aggressiveCleanup=$aggressiveCleanup, timestamp=$cloudTimestamp")
                        return@withContext Pair(data, cloudTimestamp ?: "")
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
     * Saves settings to Supabase
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     */
    suspend fun saveSettingsToCloud(context: Context, data: SettingsManager.SettingsData): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping save")
            return@withContext false
        }

        try {
            // CRITICAL: Check cloud timestamp first before updating
            val cloudResult = loadSettingsFromCloud(context)
            if (cloudResult != null) {
                val (_, cloudTimestamp) = cloudResult
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val localTimestamp = prefs.getString("settings_timestamp", null)
                
                if (cloudTimestamp != null && localTimestamp != null) {
                    val cloudIsNewer = compareTimestamps(cloudTimestamp, localTimestamp) > 0
                    Log.d(TAG, "saveSettingsToCloud timestamp check: cloudIsNewer=$cloudIsNewer (cloud=$cloudTimestamp, local=$localTimestamp)")
                    if (cloudIsNewer) {
                        Log.d(TAG, "Cloud settings are newer, not updating cloud")
                        return@withContext true // Cloud is newer, don't overwrite
                    }
                } else if (cloudTimestamp != null && localTimestamp == null) {
                    Log.d(TAG, "Cloud has timestamp but local doesn't, not updating cloud")
                    return@withContext true // Cloud has timestamp, don't overwrite
                }
            }
            
            // Explicitly create a map with ONLY the fields that exist in the settings table
            val settingsMap = mutableMapOf<String, Any?>()
            
            if (data.pin != null) {
                settingsMap["pin"] = data.pin
            }
            if (data.parentEmail != null) {
                settingsMap["parent_email"] = data.parentEmail
            }
            if (data.aggressiveCleanup != null) {
                settingsMap["aggressive_cleanup"] = data.aggressiveCleanup
            }
            
            val validKeys = listOf("pin", "parent_email", "aggressive_cleanup")
            val cleanedMap = settingsMap.filterKeys { it in validKeys }
            
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
     * Syncs app lists (reward_apps, blacklisted_apps, white_listed_apps) to cloud user_data table
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     * Note: App lists share the user_data last_updated timestamp
     */
    suspend fun syncAppListsToCloud(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping app list sync")
            return@withContext false
        }

        try {
            // Get current profile (AM or BM format)
            val profile = ProfileManager.getCurrentProfile(context)
            
            // CRITICAL: Check cloud timestamp first before updating
            // App lists are part of user_data, so check user_data last_updated timestamp
            val url = "${getSupabaseUrl(context)}/rest/v1/user_data?profile=eq.$profile&select=last_updated"
            val checkRequest = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .build()
            
            val checkResponse = client.newCall(checkRequest).execute()
            if (checkResponse.isSuccessful) {
                val responseBody = checkResponse.body?.string() ?: "[]"
                checkResponse.close()
                
                if (responseBody != "[]" && responseBody != "{}") {
                    val dataList = gson.fromJson(responseBody, object : TypeToken<List<Map<String, Any>>>() {}.type) as? List<Map<String, Any>>
                    val userData = dataList?.firstOrNull()
                    val cloudTimestamp = userData?.get("last_updated") as? String
                    
                    if (cloudTimestamp != null) {
                        // Check if we have a local timestamp for app lists
                        // Since app lists are part of user_data, we can use the banked_mins_timestamp as a proxy
                        // or check when app lists were last modified locally
                        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
                        val localTimestamp = prefs.getString("app_lists_timestamp", null) ?: prefs.getString("banked_mins_timestamp", null)
                        
                        if (localTimestamp != null) {
                            val cloudIsNewer = compareTimestamps(cloudTimestamp, localTimestamp) > 0
                            Log.d(TAG, "syncAppListsToCloud timestamp check: cloudIsNewer=$cloudIsNewer (cloud=$cloudTimestamp, local=$localTimestamp)")
                            if (cloudIsNewer) {
                                Log.d(TAG, "Cloud user_data timestamp is newer, not updating app lists")
                                return@withContext true // Cloud is newer, don't overwrite
                            }
                        } else {
                            Log.d(TAG, "No local timestamp for app lists, not updating cloud")
                            return@withContext true // No local timestamp, don't overwrite cloud
                        }
                    }
                }
            }
            
            // Read app lists from local storage
            val rewardPrefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
            val whitelistPrefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
            
            val rewardAppsSet = rewardPrefs.getStringSet("reward_apps", emptySet()) ?: emptySet()
            val blacklistedAppsSet = BlacklistManager.getBlacklist(context)
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
            
            // Update timestamp when syncing app lists
            val timestamp = generateESTTimestamp()
            updateMap["last_updated"] = timestamp
            
            val json = gson.toJson(updateMap)
            val baseUrl = "${getSupabaseUrl(context)}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            // Update user_data for this profile
            val updateUrl = "$baseUrl?profile=eq.$profile"
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
                // Store local timestamp for app lists
                rewardPrefs.edit().putString("app_lists_timestamp", timestamp).apply()
                Log.d(TAG, "Successfully synced app lists to cloud for profile: $profile, timestamp: $timestamp")
                Log.d(TAG, "Synced data: reward_apps=${rewardAppsSet.size}, blacklisted=${blacklistedAppsSet.size}, whitelisted=${whiteListedAppsSet.size}")
                return@withContext true
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to sync app lists to cloud: ${patchResponse.code} - $errorBody")
                Log.e(TAG, "Profile: $profile, URL: $updateUrl, JSON: $json")
                patchResponse.close()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing app lists to cloud", e)
            return@withContext false
        }
    }

    /**
     * Syncs current reward minutes to cloud user_data table
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     */
    suspend fun syncRewardMinutesToCloud(context: Context, rewardMinutes: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping reward minutes sync")
            return@withContext false
        }

        try {
            // Get current profile (AM or BM format)
            val profile = ProfileManager.getCurrentProfile(context)
            
            // CRITICAL: Check cloud timestamp first before updating
            val url = "${getSupabaseUrl(context)}/rest/v1/user_data?profile=eq.$profile&select=banked_mins,last_updated"
            val checkRequest = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .build()
            
            val checkResponse = client.newCall(checkRequest).execute()
            if (checkResponse.isSuccessful) {
                val responseBody = checkResponse.body?.string() ?: "[]"
                checkResponse.close()
                
                if (responseBody != "[]" && responseBody != "{}") {
                    val dataList = gson.fromJson(responseBody, object : TypeToken<List<Map<String, Any>>>() {}.type) as? List<Map<String, Any>>
                    val userData = dataList?.firstOrNull()
                    val cloudTimestamp = userData?.get("last_updated") as? String
                    
                    if (cloudTimestamp != null) {
                        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        val localTimestamp = prefs.getString("banked_mins_timestamp", null)
                        
                        if (localTimestamp != null) {
                            val cloudIsNewer = compareTimestamps(cloudTimestamp, localTimestamp) > 0
                            Log.d(TAG, "syncRewardMinutesToCloud timestamp check: cloudIsNewer=$cloudIsNewer (cloud=$cloudTimestamp, local=$localTimestamp)")
                            if (cloudIsNewer) {
                                Log.d(TAG, "Cloud banked_mins timestamp is newer, not updating cloud")
                                return@withContext true // Cloud is newer, don't overwrite
                            }
                        } else {
                            Log.d(TAG, "No local timestamp for banked_mins, not updating cloud")
                            return@withContext true // No local timestamp, don't overwrite cloud
                        }
                    }
                }
            }
            
            // Generate timestamp in ISO 8601 format with EST timezone (same format as BaerenEd)
            val lastUpdated = generateESTTimestamp()
            
            // Update banked_mins AND last_updated timestamp in user_data table
            val updateMap = mapOf(
                "banked_mins" to rewardMinutes,
                "last_updated" to lastUpdated
            )
            
            val json = gson.toJson(updateMap)
            val baseUrl = "${getSupabaseUrl(context)}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            // Update user_data for this profile
            val updateUrl = "$baseUrl?profile=eq.$profile"
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
                Log.d(TAG, "Successfully synced reward minutes to cloud for profile: $profile, minutes: $rewardMinutes, timestamp: $lastUpdated")
                return@withContext true
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to sync reward minutes to cloud: ${patchResponse.code} - $errorBody")
                Log.e(TAG, "Profile: $profile, URL: $updateUrl, JSON: $json")
                patchResponse.close()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing reward minutes to cloud", e)
            return@withContext false
        }
    }
    
    /**
     * Downloads user_data from cloud for a specific profile
     * @param isRetry true if this is a retry after triggering a reset (prevents infinite loops)
     */
    suspend fun downloadUserDataFromCloud(context: Context, cloudProfile: String, isRetry: Boolean = false): Boolean = withContext(Dispatchers.IO) {
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
                                        Log.d(TAG, "Cloud last_reset is from different day, will trigger cloud reset for profile: $cloudProfile")
                                        needsCloudReset = true
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing last_reset timestamp: ${e.message}")
                                }
                            } else {
                                Log.d(TAG, "No last_reset in cloud, will trigger reset to initialize for profile: $cloudProfile")
                                needsCloudReset = true
                            }
                        }
                        
                        // Trigger cloud reset if needed
                        if (needsCloudReset && !resetTriggeredProfiles.contains(cloudProfile)) {
                            try {
                                resetTriggeredProfiles.add(cloudProfile)
                                
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
                                    delay(1000) // Wait 1 second for database trigger to complete
                                    downloadingProfiles.remove(cloudProfile)
                                    val retryResult = downloadUserDataFromCloud(context, cloudProfile, isRetry = true)
                                    if (retryResult) {
                                        return@withContext true
                                    } else {
                                        Log.w(TAG, "Retry download after reset failed, using current values")
                                    }
                                } else {
                                    val errorBody = resetResponse.body?.string() ?: "Unknown error"
                                    Log.w(TAG, "Failed to trigger cloud reset: ${resetResponse.code} - $errorBody")
                                    resetResponse.close()
                                    resetTriggeredProfiles.remove(cloudProfile)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error triggering cloud reset: ${e.message}")
                                resetTriggeredProfiles.remove(cloudProfile)
                            }
                        } else if (needsCloudReset && resetTriggeredProfiles.contains(cloudProfile)) {
                            Log.d(TAG, "Already triggered reset for profile: $cloudProfile in this session, skipping to prevent loop")
                        }
                        
                        // TIMESTAMP-BASED SYNC: Compare local banked_mins timestamp vs cloud last_updated timestamp
                        val rewardPrefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)
                        val currentLocalBankedMins = rewardPrefs.getInt("current_reward_minutes", 0)
                        val localBankedMinsTimestamp = rewardPrefs.getString("banked_mins_timestamp", null)
                        val cloudBankedMins = (userData["banked_mins"] as? Number)?.toInt() ?: 0
                        val cloudTimestamp = userData["last_updated"] as? String
                        
                        var bankedMinsToApply: Int
                        var shouldSyncLocalToCloud: Boolean
                        
                        if (localBankedMinsTimestamp.isNullOrEmpty() && currentLocalBankedMins == 0) {
                            // Fresh install - default to 0
                            bankedMinsToApply = 0
                            shouldSyncLocalToCloud = true
                            Log.d(TAG, "Fresh install detected - setting banked_mins to 0 (cloud had $cloudBankedMins, but ignoring on fresh install)")
                        } else if (cloudTimestamp.isNullOrEmpty()) {
                            bankedMinsToApply = currentLocalBankedMins
                            shouldSyncLocalToCloud = true
                            Log.d(TAG, "Keeping local banked_mins ($currentLocalBankedMins) - cloud has no timestamp")
                        } else {
                            // Both have timestamps - compare and use the newer one
                            try {
                                val localTime = if (!localBankedMinsTimestamp.isNullOrEmpty()) {
                                    parseTimestampForComparison(localBankedMinsTimestamp)
                                } else {
                                    0L
                                }
                                val cloudTime = parseTimestampForComparison(cloudTimestamp)
                                
                                Log.d(TAG, "Comparing timestamps - local: $localBankedMinsTimestamp ($localTime), cloud: $cloudTimestamp ($cloudTime)")
                                
                                if (cloudTime > localTime) {
                                    bankedMinsToApply = cloudBankedMins
                                    shouldSyncLocalToCloud = false
                                    Log.d(TAG, "Applying cloud banked_mins ($cloudBankedMins) - cloud timestamp is newer")
                                } else {
                                    bankedMinsToApply = currentLocalBankedMins
                                    shouldSyncLocalToCloud = true
                                    Log.d(TAG, "Keeping local banked_mins ($currentLocalBankedMins) - local timestamp is newer or equal")
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
                        editor.putLong("last_reward_date", todayMillis)
                        
                        if (!shouldSyncLocalToCloud && !cloudTimestamp.isNullOrEmpty()) {
                            editor.putString("banked_mins_timestamp", cloudTimestamp)
                            Log.d(TAG, "Updated local banked_mins timestamp to match cloud: $cloudTimestamp")
                        } else if (shouldSyncLocalToCloud && localBankedMinsTimestamp.isNullOrEmpty()) {
                            val estTimestamp = generateESTTimestamp()
                            editor.putString("banked_mins_timestamp", estTimestamp)
                            Log.d(TAG, "Set initial banked_mins_timestamp in EST ($estTimestamp)")
                        }
                        editor.apply()
                        
                        // Also update RewardManager's current value
                        // Note: currentRewardMinutes is a property (not a field), so we call the setter directly
                        RewardManager.currentRewardMinutes = bankedMinsToApply
                        Log.d(TAG, "Updated RewardManager.currentRewardMinutes to $bankedMinsToApply")
                        
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
            downloadingProfiles.remove(cloudProfile)
        }
    }

    /**
     * Syncs BaerenLock health check data to cloud devices table (per device, not per profile)
     * Health checks are device-specific since accessibility service status is per device
     */
    suspend fun syncHealthCheckToCloud(context: Context, healthStatus: String, healthIssues: String?): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping health check sync")
            return@withContext false
        }
        
        try {
            val deviceId = getDeviceId(context)
            val deviceName = getDeviceName(context)
            
            val updateMap = mutableMapOf<String, Any?>(
                "device_id" to deviceId,
                "device_name" to deviceName,
                "baerenlock_health_status" to healthStatus,
                "baerenlock_last_health_check" to generateESTTimestamp()
            )
            
            // Always include baerenlock_health_issues in the update map (null if no issues)
            // This ensures we can clear the field in the database when issues are resolved
            updateMap["baerenlock_health_issues"] = if (healthIssues != null && healthIssues.isNotBlank()) {
                healthIssues
            } else {
                null
            }
            
            // Use Gson with serializeNulls() enabled so that null values are included in JSON
            // This is necessary to clear the health_issues field in the database when issues are resolved
            val gsonWithNulls = GsonBuilder().serializeNulls().create()
            val json = gsonWithNulls.toJson(updateMap)
            val baseUrl = "${getSupabaseUrl(context)}/rest/v1/devices"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            // Update devices table for this device
            // Use "return=representation" to check if a row was actually updated
            val updateUrl = "$baseUrl?device_id=eq.$deviceId"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Prefer", "return=representation")
                .build()
            
            val patchResponse = client.newCall(patchRequest).execute()
            val patchResponseBody = patchResponse.body?.string() ?: "[]"
            patchResponse.close()
            
            if (patchResponse.isSuccessful && patchResponseBody != "[]" && patchResponseBody != "{}") {
                // Update succeeded - we got a row back
                Log.d(TAG, "Synced health check to cloud devices table: deviceId=$deviceId, status=$healthStatus, issues=$healthIssues")
                return@withContext true
            } else {
                // PATCH failed or no rows updated - try to insert
                Log.d(TAG, "Health check PATCH ${if (patchResponse.isSuccessful) "succeeded but no rows updated" else "failed (${patchResponse.code})"}, attempting to insert")
                
                // Get current profile to include in insert
                val profile = ProfileManager.getCurrentProfile(context)
                updateMap["active_profile"] = profile
                val insertJson = gsonWithNulls.toJson(updateMap)
                val insertRequestBody = insertJson.toRequestBody("application/json".toMediaType())
                val insertRequest = Request.Builder()
                    .url(baseUrl)
                    .post(insertRequestBody)
                    .addHeader("apikey", getSupabaseKey(context))
                    .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                    .addHeader("Prefer", "return=representation")
                    .build()
                
                val insertResponse = client.newCall(insertRequest).execute()
                val insertResponseBody = insertResponse.body?.string() ?: "[]"
                if (insertResponse.isSuccessful && insertResponseBody != "[]" && insertResponseBody != "{}") {
                    insertResponse.close()
                    Log.d(TAG, "Inserted health check in cloud devices table: deviceId=$deviceId, status=$healthStatus, issues=$healthIssues")
                    return@withContext true
                } else if (insertResponse.code == 409) {
                    // 409 = duplicate key - record already exists (another concurrent request created it)
                    // Retry with PATCH to update the existing record with health check data
                    insertResponse.close()
                    Log.d(TAG, "Device record already exists during health check insert (409), retrying with PATCH")
                    val retryPatchRequest = Request.Builder()
                        .url(updateUrl)
                        .patch(requestBody)
                        .addHeader("apikey", getSupabaseKey(context))
                        .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                        .addHeader("Prefer", "return=representation")
                        .build()
                    val retryPatchResponse = client.newCall(retryPatchRequest).execute()
                    val retryPatchBody = retryPatchResponse.body?.string() ?: "[]"
                    retryPatchResponse.close()
                    if (retryPatchResponse.isSuccessful && retryPatchBody != "[]" && retryPatchBody != "{}") {
                        Log.d(TAG, "Synced health check to cloud devices table after retry: deviceId=$deviceId, status=$healthStatus, issues=$healthIssues")
                        return@withContext true
                    }
                } else {
                    val errorBody = insertResponseBody
                    Log.e(TAG, "Failed to insert health check: ${insertResponse.code} - $errorBody")
                    Log.e(TAG, "Request URL: $baseUrl")
                    Log.e(TAG, "Request body: $insertJson")
                    insertResponse.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing health check to cloud", e)
        }
        false
    }
    
    /**
     * Syncs active profile to cloud devices table
     * This allows BaerenLock and BaerenEd to share the active profile on the same device
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     * @param forceUpdate If true, bypasses timestamp check and always updates (for user-initiated changes)
     */
    suspend fun syncActiveProfileToCloud(context: Context, profile: String, forceUpdate: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping active profile sync")
            return@withContext false
        }
        
        try {
            val deviceId = getDeviceId(context)
            val deviceName = getDeviceName(context)
            val localTimestamp = ProfileManager.getLocalProfileTimestamp(context)
            
            // CRITICAL: Check cloud timestamp first before updating (unless forced)
            val cloudProfileData = getActiveProfileFromCloud(context)
            if (cloudProfileData != null && !forceUpdate) {
                val cloudTimestamp = cloudProfileData.lastUpdated
                Log.d(TAG, "syncActiveProfileToCloud: local=$profile (timestamp=$localTimestamp), cloud=${cloudProfileData.profile} (timestamp=$cloudTimestamp)")
                
                // Compare timestamps to determine if we should update
                val shouldUpdate = if (cloudTimestamp != null && localTimestamp != null) {
                    val localTime = parseTimestampForComparison(localTimestamp)
                    val cloudTime = parseTimestampForComparison(cloudTimestamp)
                    val localIsNewer = localTime > cloudTime
                    Log.d(TAG, "syncActiveProfileToCloud timestamp comparison:")
                    Log.d(TAG, "  Local: $localTimestamp (parsed: $localTime)")
                    Log.d(TAG, "  Cloud: $cloudTimestamp (parsed: $cloudTime)")
                    Log.d(TAG, "  Local is newer: $localIsNewer")
                    localIsNewer
                } else if (localTimestamp != null) {
                    // Local has timestamp but cloud doesn't - update cloud
                    Log.d(TAG, "Local has timestamp but cloud doesn't, updating cloud")
                    true
                } else {
                    // No local timestamp - don't overwrite cloud
                    Log.d(TAG, "No local timestamp, not updating cloud")
                    false
                }
                
                if (!shouldUpdate) {
                    Log.d(TAG, "Cloud profile is newer or equal, not updating device record")
                    return@withContext true
                }
                
                // Also check if profiles match - if they're the same, no need to update
                if (cloudProfileData.profile == profile && cloudTimestamp != null && localTimestamp != null) {
                    Log.d(TAG, "Profiles match and timestamps exist, skipping update")
                    return@withContext true
                }
            } else if (forceUpdate) {
                Log.d(TAG, "syncActiveProfileToCloud: Force update requested, bypassing timestamp check")
            }
            
            val updateMap = mapOf(
                "device_id" to deviceId,
                "device_name" to deviceName,
                "active_profile" to profile // Store in AM/BM format
                // Note: last_updated will be set by database trigger to current time
            )
            
            val json = gson.toJson(updateMap)
            val baseUrl = "${getSupabaseUrl(context)}/rest/v1/devices"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            // Try to update existing device record first
            // Use "return=representation" to check if a row was actually updated
            val updateUrl = "$baseUrl?device_id=eq.$deviceId"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Prefer", "return=representation")
                .build()
            
            val patchResponse = client.newCall(patchRequest).execute()
            val patchResponseBody = patchResponse.body?.string() ?: "[]"
            patchResponse.close()
            
            if (patchResponse.isSuccessful && patchResponseBody != "[]" && patchResponseBody != "{}") {
                // Update succeeded - we got a row back
                Log.d(TAG, "Synced active profile to cloud devices table: deviceId=$deviceId, profile=$profile")
                return@withContext true
            } else {
                // PATCH failed or no rows updated - try to insert
                Log.d(TAG, "Active profile PATCH ${if (patchResponse.isSuccessful) "succeeded but no rows updated" else "failed (${patchResponse.code})"}, attempting to insert")
                
                val insertRequest = Request.Builder()
                    .url(baseUrl)
                    .post(requestBody)
                    .addHeader("apikey", getSupabaseKey(context))
                    .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                    .addHeader("Prefer", "return=representation")
                    .build()
                
                val insertResponse = client.newCall(insertRequest).execute()
                val insertResponseBody = insertResponse.body?.string() ?: "[]"
                if (insertResponse.isSuccessful && insertResponseBody != "[]" && insertResponseBody != "{}") {
                    insertResponse.close()
                    Log.d(TAG, "Inserted active profile in cloud devices table: deviceId=$deviceId, profile=$profile")
                    return@withContext true
                } else if (insertResponse.code == 409) {
                    // 409 = duplicate key - record already exists (another concurrent request created it)
                    // This is fine - the record exists, which is what we want
                    insertResponse.close()
                    Log.d(TAG, "Device record already exists during active profile sync (409), record is fine: deviceId=$deviceId")
                    return@withContext true
                } else {
                    val errorBody = insertResponseBody
                    Log.e(TAG, "Failed to insert active profile: ${insertResponse.code} - $errorBody")
                    Log.e(TAG, "Request URL: $baseUrl")
                    Log.e(TAG, "Request body: $json")
                    insertResponse.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing active profile to cloud", e)
        }
        false
    }
    
    /**
     * Data class to hold profile and timestamp information
     */
    data class ProfileWithTimestamp(
        val profile: String,
        val lastUpdated: String? // ISO timestamp string from database
    )
    
    /**
     * Gets the active profile and last_updated timestamp from cloud devices table for this device
     * Returns ProfileWithTimestamp or null if not found/error
     */
    suspend fun getActiveProfileFromCloud(context: Context): ProfileWithTimestamp? = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping active profile fetch")
            return@withContext null
        }
        
        try {
            val deviceId = getDeviceId(context)
            val url = "${getSupabaseUrl(context)}/rest/v1/devices?device_id=eq.$deviceId&select=active_profile,last_updated"
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
                    val devicesArray = gson.fromJson(responseBody, object : TypeToken<Array<Map<String, Any>>>() {}.type) as? Array<Map<String, Any>>
                    val device = devicesArray?.firstOrNull()
                    val activeProfile = device?.get("active_profile") as? String
                    val lastUpdated = device?.get("last_updated") as? String
                    if (activeProfile != null) {
                        Log.d(TAG, "Got active profile from cloud: $activeProfile, last_updated: $lastUpdated")
                        return@withContext ProfileWithTimestamp(activeProfile, lastUpdated)
                    }
                }
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to get active profile: ${response.code} - $errorBody")
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active profile from cloud", e)
        }
        null
    }
    
    /**
     * Async wrapper for syncActiveProfileToCloud
     * @param forceUpdate If true, bypasses timestamp check and always updates (for user-initiated changes)
     */
    fun syncActiveProfileToCloudAsync(context: Context, profile: String, forceUpdate: Boolean = false) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                syncActiveProfileToCloud(context, profile, forceUpdate)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync active profile to cloud: ${e.message}")
            }
        }
    }
    
    /**
     * Ensures device record exists in devices table on startup
     * This creates/updates the device record with current device info and profile
     * Only updates if local profile is newer than cloud, or if record doesn't exist
     * Should be called on app startup to initialize device record
     */
    suspend fun ensureDeviceRecord(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.w(TAG, "Supabase not configured - cannot create device record. Check BuildConfig.SUPABASE_URL and BuildConfig.SUPABASE_KEY")
            return@withContext false
        }
        
        try {
            val deviceId = getDeviceId(context)
            val deviceName = getDeviceName(context)
            val localProfile = ProfileManager.getCurrentProfile(context)
            val localTimestamp = ProfileManager.getLocalProfileTimestamp(context)
            
            Log.d(TAG, "Ensuring device record exists: deviceId=$deviceId, deviceName=$deviceName, profile=$localProfile, localTimestamp=$localTimestamp")
            
            // Check cloud profile and timestamp first
            val cloudProfileData = getActiveProfileFromCloud(context)
            if (cloudProfileData != null) {
                val cloudTimestamp = cloudProfileData.lastUpdated
                Log.d(TAG, "Cloud profile exists: ${cloudProfileData.profile}, timestamp=$cloudTimestamp")
                
                // Compare timestamps to see if we should update
                val shouldUpdate = if (cloudTimestamp != null && localTimestamp != null) {
                    val localTime = parseTimestampForComparison(localTimestamp)
                    val cloudTime = parseTimestampForComparison(cloudTimestamp)
                    val localIsNewer = localTime > cloudTime
                    Log.d(TAG, "ensureDeviceRecord timestamp comparison:")
                    Log.d(TAG, "  Local: $localTimestamp (parsed: $localTime)")
                    Log.d(TAG, "  Cloud: $cloudTimestamp (parsed: $cloudTime)")
                    Log.d(TAG, "  Local is newer: $localIsNewer")
                    localIsNewer
                } else if (localTimestamp != null) {
                    // Local has timestamp but cloud doesn't - update cloud
                    Log.d(TAG, "Local has timestamp but cloud doesn't, updating cloud")
                    true
                } else {
                    // No local timestamp - don't overwrite cloud
                    Log.d(TAG, "No local timestamp, not updating cloud")
                    false
                }
                
                if (!shouldUpdate) {
                    Log.d(TAG, "Cloud profile is newer or equal, not updating device record")
                    return@withContext true
                }
                
                // Also check if profiles differ - if they're the same, no need to update
                if (cloudProfileData.profile == localProfile && cloudTimestamp != null && localTimestamp != null) {
                    Log.d(TAG, "Profiles match and timestamps exist, skipping update")
                    return@withContext true
                }
            }
            
            val updateMap = mapOf(
                "device_id" to deviceId,
                "device_name" to deviceName,
                "active_profile" to localProfile
            )
            
            val json = gson.toJson(updateMap)
            val baseUrl = "${getSupabaseUrl(context)}/rest/v1/devices"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            Log.d(TAG, "Attempting to update device record: $json")
            
            // Try to update existing device record first
            // Use "return=representation" to check if a row was actually updated
            val updateUrl = "$baseUrl?device_id=eq.$deviceId"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Prefer", "return=representation")
                .build()
            
            val patchResponse = client.newCall(patchRequest).execute()
            val patchResponseBody = patchResponse.body?.string() ?: "[]"
            patchResponse.close()
            
            if (patchResponse.isSuccessful) {
                // Check if we actually got a row back (meaning update succeeded)
                if (patchResponseBody != "[]" && patchResponseBody != "{}") {
                    Log.d(TAG, "Successfully updated device record in cloud: deviceId=$deviceId, profile=$localProfile")
                    return@withContext true
                } else {
                    // PATCH returned 200 but no rows were updated - record doesn't exist, need to insert
                    Log.d(TAG, "PATCH succeeded but no rows updated (record doesn't exist), attempting to insert")
                }
            } else {
                // PATCH failed, try insert
                Log.d(TAG, "PATCH failed (${patchResponse.code}), attempting to insert. Response: $patchResponseBody")
            }
            
            // No existing record, try to insert
            val insertRequest = Request.Builder()
                .url(baseUrl)
                .post(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Prefer", "return=representation")
                .build()
            
                val insertResponse = client.newCall(insertRequest).execute()
                val insertResponseBody = insertResponse.body?.string() ?: "[]"
                if (insertResponse.isSuccessful && insertResponseBody != "[]" && insertResponseBody != "{}") {
                    insertResponse.close()
                    Log.d(TAG, "Successfully created device record in cloud: deviceId=$deviceId, deviceName=$deviceName, profile=$localProfile")
                    return@withContext true
                } else if (insertResponse.code == 409) {
                    // 409 = duplicate key - record already exists (another concurrent request created it)
                    // This is fine - the record exists, which is what we want
                    insertResponse.close()
                    Log.d(TAG, "Device record already exists (created by concurrent request): deviceId=$deviceId")
                    return@withContext true
                } else {
                    val insertErrorBody = insertResponseBody
                    Log.e(TAG, "Failed to create device record: ${insertResponse.code} - $insertErrorBody")
                    Log.e(TAG, "Request URL: $baseUrl")
                    Log.e(TAG, "Request body: $json")
                    insertResponse.close()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring device record", e)
            e.printStackTrace()
        }
        false
    }
    
    /**
     * Async wrapper for ensureDeviceRecord
     */
    fun ensureDeviceRecordAsync(context: Context) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                ensureDeviceRecord(context)
            } catch (e: Exception) {
                Log.w(TAG, "Could not ensure device record: ${e.message}")
            }
        }
    }
    
    /**
     * Generates a timestamp in ISO 8601 format with EST timezone.
     */
    fun generateESTTimestamp(): String {
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
     * Compares two timestamps and returns:
     * - Positive value if timestamp1 is newer than timestamp2
     * - Negative value if timestamp1 is older than timestamp2
     * - Zero if they are equal
     */
    fun compareTimestamps(timestamp1: String, timestamp2: String): Int {
        val time1 = parseTimestampForComparison(timestamp1)
        val time2 = parseTimestampForComparison(timestamp2)
        return time1.compareTo(time2)
    }
    
    /**
     * Parses timestamp string to milliseconds for comparison
     * Handles both ISO 8601 format (from Supabase) and our EST format
     */
    fun parseTimestampForComparison(timestamp: String): Long {
        return try {
            // All timestamps are stored in EST, regardless of the offset suffix
            // Strip any offset or Z suffix and parse the base time as EST
            val baseTimestamp = when {
                timestamp.endsWith("Z") -> timestamp.substring(0, timestamp.length - 1)
                timestamp.matches(Regex(".*[+-]\\d{2}:\\d{2}$")) -> {
                    val timePartEnd = timestamp.indexOf('.')
                    val timeEndIndex = if (timePartEnd > 0) {
                        val millisEnd = timestamp.indexOfAny(charArrayOf('+', '-'), timePartEnd)
                        if (millisEnd > 0) millisEnd else timestamp.length
                    } else {
                        val secondsColon = timestamp.lastIndexOf(':')
                        if (secondsColon > 0) {
                            val offsetStart = timestamp.indexOfAny(charArrayOf('+', '-'), secondsColon)
                            if (offsetStart > 0) offsetStart else timestamp.length
                        } else {
                            timestamp.length
                        }
                    }
                    timestamp.substring(0, timeEndIndex)
                }
                else -> timestamp
            }
            
            // Parse the base time as EST (America/New_York timezone)
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/New_York")
            }
            
            val date = dateFormat.parse(baseTimestamp)
            val result = date?.time ?: 0L
            Log.d(TAG, "parseTimestampForComparison: $timestamp -> base=$baseTimestamp -> $result (parsed as EST)")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp for comparison: $timestamp", e)
            0L
        }
    }
    
    /**
     * Async wrapper for syncAppListsToCloud
     */
    fun syncAppListsToCloudAsync(context: Context) {
        val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settingsScope.launch {
            try {
                syncAppListsToCloud(context)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync app lists to cloud: ${e.message}")
            }
        }
    }
    
    /**
     * Async wrapper for syncRewardMinutesToCloud
     */
    fun syncRewardMinutesToCloudAsync(context: Context, rewardMinutes: Int) {
        val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settingsScope.launch {
            try {
                syncRewardMinutesToCloud(context, rewardMinutes)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync reward minutes to cloud: ${e.message}")
            }
        }
    }
    
    /**
     * Async wrapper for downloadUserDataFromCloud
     * Downloads user_data for the current profile
     */
    fun downloadUserDataFromCloudAsync(context: Context) {
        val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settingsScope.launch {
            try {
                val profile = ProfileManager.getCurrentProfile(context)
                downloadUserDataFromCloud(context, profile, isRetry = false)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading user_data from cloud: ${e.message}", e)
            }
        }
    }
    
    /**
     * Async wrapper for syncHealthCheckToCloud
     */
    fun syncHealthCheckToCloudAsync(context: Context, healthStatus: String, healthIssues: String?) {
        val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settingsScope.launch {
            try {
                syncHealthCheckToCloud(context, healthStatus, healthIssues)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync health check to cloud: ${e.message}")
            }
        }
    }
}
