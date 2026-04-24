package com.talq2me.baerenlock

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.util.concurrent.TimeUnit

/**
 * BaerenLock Supabase REST/RPC client (OkHttp): settings row, `user_data` fetch/patch, reward-time RPCs,
 * app list columns, device row, `af_daily_reset`, etc. Same role as BaerenEd’s [com.talq2me.baerened.SupabaseInterface]—the
 * network layer for Postgres—not an optional “cloud sync” toggle. (Mirrors BaerenEd’s `SupabaseInterface` in package `com.talq2me.baerened`.)
 */
object SupabaseInterface {
    private const val TAG = "SupabaseInterface"
    private const val LOCAL_PREFS_NAME = "settings"
    private val gson = Gson()
    private val gsonSerializeNulls = GsonBuilder().serializeNulls().create()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    
    // Shared coroutine scope for all async operations - prevents memory leaks
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track which profiles are currently being downloaded to prevent concurrent downloads
    private val downloadingProfiles = Collections.synchronizedSet(mutableSetOf<String>()) as MutableSet<String>
    
    // Track which devices are currently ensuring device record to prevent concurrent calls
    private val ensuringDevices = Collections.synchronizedSet(mutableSetOf<String>()) as MutableSet<String>
    
    /**
     * Checks if the device has network connectivity
     * Returns true if connected to internet, false otherwise
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }
    
    /**
     * Gets Supabase URL from BuildConfig
     */
    fun getSupabaseUrl(context: Context): String {
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
    fun getSupabaseKey(context: Context): String {
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
     * Cloud state needed for reward-time control.
     */
    data class RewardTimeState(
        val bankedMins: Int,
        val rewardTimeExpiry: String?
    )

    /**
     * Reads banked_mins and reward_time_expiry for the current profile via [af_get_reward_time_state]
     * (small payload; used for dumb-UI polling without pulling the full user_data row).
     */
    suspend fun fetchRewardTimeState(context: Context): RewardTimeState? = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext null
        return@withContext try {
            val profile = ProfileManager.getCurrentProfile(context)
            val body = callRpcReturningBody(context, "af_get_reward_time_state", mapOf("p_profile" to profile))
            val row = parseJsonObjectBody(body)
            row ?: return@withContext RewardTimeState(0, null)
            val mins = valueAsInt(row["banked_mins"])
            val expiry = valueAsString(row["reward_time_expiry"])
            RewardTimeState(mins, expiry)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching reward time state", e)
            null
        }
    }

    private suspend fun callRewardRpc(
        context: Context,
        functionName: String,
        minutes: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext false
        return@withContext try {
            val profile = ProfileManager.getCurrentProfile(context)
            val payload = if (minutes != null) {
                mapOf("p_profile" to profile, "p_minutes" to minutes)
            } else {
                mapOf("p_profile" to profile)
            }
            val json = gson.toJson(payload)
            val request = Request.Builder()
                .url("${getSupabaseUrl(context)}/rest/v1/rpc/$functionName")
                .post(json.toRequestBody("application/json".toMediaType()))
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            if (!ok) {
                val err = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "RPC $functionName failed: ${response.code} - $err")
            }
            response.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Error calling RPC $functionName", e)
            false
        }
    }

    private suspend fun callRpcReturningBody(
        context: Context,
        functionName: String,
        payload: Map<String, Any?> = emptyMap()
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext null
        return@withContext try {
            val requestBody = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${getSupabaseUrl(context)}/rest/v1/rpc/$functionName")
                .post(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                Log.e(TAG, "RPC $functionName failed: ${response.code} - ${body ?: "Unknown error"}")
                response.close()
                return@withContext null
            }
            response.close()
            body
        } catch (e: Exception) {
            Log.e(TAG, "Error calling RPC $functionName", e)
            null
        }
    }

    private suspend fun callRpcNoBody(
        context: Context,
        functionName: String,
        payload: Map<String, Any?> = emptyMap(),
        serializeNulls: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext false
        return@withContext try {
            val json = if (serializeNulls) gsonSerializeNulls.toJson(payload) else gson.toJson(payload)
            val requestBody = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${getSupabaseUrl(context)}/rest/v1/rpc/$functionName")
                .post(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            if (!ok) {
                val err = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "RPC $functionName failed: ${response.code} - $err")
            }
            response.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Error calling RPC $functionName", e)
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonObjectBody(body: String?): Map<String, Any?>? {
        if (body.isNullOrBlank() || body == "null" || body == "{}" || body == "[]") return null
        return try {
            parseAsMapOrFirstArrayObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse RPC object payload: $body", e)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAsMapOrFirstArrayObject(rawBody: String): Map<String, Any?>? {
        val trimmed = rawBody.trim()

        // Common case: direct JSON object payload.
        val asMap = gson.fromJson(trimmed, object : TypeToken<Map<String, Any?>>() {}.type) as? Map<String, Any?>
        if (!asMap.isNullOrEmpty()) return asMap

        // Some RPC gateways can return a one-item array for object payloads.
        val asList = gson.fromJson(trimmed, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
        if (!asList.isNullOrEmpty()) return asList.first()

        // Some payloads can be a JSON string containing an object; unwrap and parse once more.
        val unwrapped = runCatching { gson.fromJson(trimmed, String::class.java) }.getOrNull()
        if (!unwrapped.isNullOrBlank() && unwrapped != trimmed) {
            val unwrappedMap = gson.fromJson(unwrapped, object : TypeToken<Map<String, Any?>>() {}.type) as? Map<String, Any?>
            if (!unwrappedMap.isNullOrEmpty()) return unwrappedMap
        }
        return null
    }

    private fun valueAsInt(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun valueAsString(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value
            else -> value.toString()
        }
    }

    suspend fun useRewardTime(context: Context): Boolean = callRewardRpc(context, "af_reward_time_use")

    suspend fun pauseRewardTime(context: Context): Boolean = callRewardRpc(context, "af_reward_time_pause")

    suspend fun expireRewards(context: Context): Boolean = callRewardRpc(context, "af_reward_time_expire")

    suspend fun addRewardTime(context: Context, minutes: Int): Boolean = callRewardRpc(context, "af_reward_time_add", minutes)

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
            val responseBody = callRpcReturningBody(context, "af_get_settings_row")
            val settings = parseJsonObjectBody(responseBody)
            if (settings != null) {
                val pin = settings["pin"] as? String
                val parentEmail = settings["parent_email"] as? String
                val aggressiveCleanup = settings["aggressive_cleanup"] as? Boolean
                val cloudTimestamp = settings["last_updated"] as? String
                val data = SettingsManager.SettingsData(
                    profile = null,
                    pin = pin,
                    parentEmail = parentEmail,
                    childName = null,
                    rewardApps = null,
                    aggressiveCleanup = aggressiveCleanup
                )
                Log.d(TAG, "Loaded settings from cloud: pin=${pin?.take(1)}..., email=$parentEmail, aggressiveCleanup=$aggressiveCleanup, timestamp=$cloudTimestamp")
                return@withContext Pair(data, cloudTimestamp ?: "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings from cloud: ${e.message}", e)
        }
        null
    }

    /**
     * Saves settings to Supabase.
     * Dumb-UI mode: no timestamp conflict gate; user-initiated values are written directly.
     */
    suspend fun saveSettingsToCloud(context: Context, data: SettingsManager.SettingsData): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping save")
            return@withContext false
        }

        try {
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
            
            Log.d(TAG, "Sending settings to cloud (keys: ${cleanedMap.keys}) via af_upsert_settings_row")
            val ok = callRpcNoBody(
                context,
                "af_upsert_settings_row",
                mapOf(
                    "p_parent_email" to cleanedMap["parent_email"],
                    "p_pin" to cleanedMap["pin"],
                    "p_aggressive_cleanup" to cleanedMap["aggressive_cleanup"]
                )
            )
            if (ok) {
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings to cloud", e)
        }
        false
    }

    /**
     * Syncs app lists (reward_apps, blacklisted_apps, white_listed_apps) to cloud user_data table.
     * Dumb-UI mode: no timestamp conflict gate; current local selection is written directly.
     */
    suspend fun syncAppListsToCloud(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping app list sync")
            return@withContext false
        }

        try {
            // Get current profile (AM or BM format)
            val profile = ProfileManager.getCurrentProfile(context)
            
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
            val ok = callRpcNoBody(
                context,
                "af_upsert_user_data_columns",
                mapOf("p_profile" to profile, "p_columns" to updateMap)
            )
            if (ok) {
                Log.d(TAG, "Successfully synced app lists to cloud for profile: $profile")
                Log.d(TAG, "Synced data: reward_apps=${rewardAppsSet.size}, blacklisted=${blacklistedAppsSet.size}, whitelisted=${whiteListedAppsSet.size}")
                return@withContext true
            } else {
                Log.e(TAG, "Failed to sync app lists to cloud via af_upsert_user_data_columns for profile: $profile")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing app lists to cloud", e)
            return@withContext false
        }
    }

    /**
     * Fetches app lists from user_data for the profile (DB-only, no daily reset).
     * Used when opening app list settings so the UI shows current DB state.
     */
    data class AppListsFromCloud(
        val whiteListed: Set<String>,
        val rewardApps: Set<String>,
        val blacklisted: Set<String>
    )

    suspend fun fetchAppListsFromCloud(context: Context, profile: String): AppListsFromCloud? = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext null
        try {
            val body = callRpcReturningBody(context, "af_get_user_data", mapOf("p_profile" to profile))
            val row = parseJsonObjectBody(body) ?: return@withContext AppListsFromCloud(emptySet(), emptySet(), emptySet())
            val whiteListed = parseJsonArrayToSet(row["white_listed_apps"]) ?: emptySet()
            val rewardApps = parseJsonArrayToSet(row["reward_apps"]) ?: emptySet()
            val blacklisted = parseJsonArrayToSet(row["blacklisted_apps"]) ?: emptySet()
            AppListsFromCloud(whiteListed, rewardApps, blacklisted)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching app lists from cloud", e)
            null
        }
    }

    /**
     * Saves a single app list column to user_data and updates last_updated. Then applies to local prefs.
     * Used when exiting app list settings (DB is source of truth).
     */
    suspend fun patchAppListToCloud(context: Context, profile: String, columnName: String, jsonValue: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext false
        try {
            val updateMap = mapOf(columnName to jsonValue)
            val ok = callRpcNoBody(
                context,
                "af_upsert_user_data_columns",
                mapOf("p_profile" to profile, "p_columns" to updateMap)
            )
            if (!ok) {
                return@withContext false
            }
            // Apply to local prefs so the app uses the new list immediately
            val set = parseJsonArrayToSet(jsonValue) ?: emptySet<String>()
            when (columnName) {
                "white_listed_apps" -> {
                    context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE).edit().putStringSet("allowed", set).apply()
                    RewardAppsManager.loadAllowedApps(context)
                }
                "reward_apps" -> {
                    context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE).edit().putStringSet("reward_apps", set).apply()
                    RewardAppsManager.loadRewardEligibleApps(context)
                }
                "blacklisted_apps" -> {
                    context.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE).edit().putStringSet("packages", set).apply()
                }
            }
            Log.d(TAG, "Saved $columnName to cloud and local for profile: $profile")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error patching app list to cloud", e)
            false
        }
    }

    /**
     * Legacy helper. Reward state should be driven by reward-time RPCs (`af_reward_time_*`) in dumb-UI mode.
     */
    suspend fun syncRewardMinutesToCloud(context: Context, rewardMinutes: Int, skipTimestampCheck: Boolean = false, rewardTimeExpiry: String? = null): Boolean = withContext(Dispatchers.IO) {
        Log.w(
            TAG,
            "syncRewardMinutesToCloud is legacy/no-op in dumb-UI mode; use af_reward_time_* RPCs. " +
                "args: rewardMinutes=$rewardMinutes, skipTimestampCheck=$skipTimestampCheck, rewardTimeExpiry=$rewardTimeExpiry"
        )
        true
    }
    
    /**
     * Calls Postgres `af_daily_reset(p_profile)`; no-op in SQL when reset is not needed.
     * Call this **before** reading user_data when showing DB-backed UI.
     */
    suspend fun runAfDailyResetRpc(context: Context, cloudProfile: String): Boolean = invokeAfDailyReset(context, cloudProfile)

    private suspend fun invokeAfDailyReset(context: Context, cloudProfile: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext false
        return@withContext try {
            val url = "${getSupabaseUrl(context)}/rest/v1/rpc/af_daily_reset"
            val body = """{"p_profile":"$cloudProfile"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.close()
                Log.d(TAG, "af_daily_reset($cloudProfile) invoked successfully")
                true
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.w(TAG, "af_daily_reset($cloudProfile) failed: ${response.code} - $errorBody")
                response.close()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "invokeAfDailyReset($cloudProfile) error: ${e.message}")
            false
        }
    }

    /**
     * Downloads user_data from cloud for a specific profile.
     * When [invokeDailyResetRpc] is true, runs `af_daily_reset` first (Postgres decides if work is needed).
     */
    suspend fun downloadUserDataFromCloud(
        context: Context,
        cloudProfile: String,
        invokeDailyResetRpc: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping user_data download")
            return@withContext false
        }

        if (invokeDailyResetRpc) {
            invokeAfDailyReset(context, cloudProfile)
        }

        // Prevent concurrent downloads for the same profile
        if (downloadingProfiles.contains(cloudProfile)) {
            Log.d(TAG, "Already downloading user_data for profile: $cloudProfile, skipping duplicate request")
            return@withContext false
        }
        
        downloadingProfiles.add(cloudProfile)
        
        try {
            val responseBody = callRpcReturningBody(context, "af_get_user_data", mapOf("p_profile" to cloudProfile))
            val userData = parseJsonObjectBody(responseBody)
            if (userData != null) {
                val cloudBankedMins = valueAsInt(userData["banked_mins"])
                RewardStorage.setCurrentRewardMinutes(cloudBankedMins)
                RewardManager.currentRewardMinutes = cloudBankedMins
                val cloudRewardTimeExpiry = valueAsString(userData["reward_time_expiry"])
                RewardStorage.setRewardTimeExpiry(cloudRewardTimeExpiry)
                Log.d(TAG, "Applied banked_mins from cloud to in-memory: $cloudBankedMins, reward_time_expiry: $cloudRewardTimeExpiry for profile: $cloudProfile (online-only, no local persistence)")
                
                // Apply app lists from cloud to local prefs (cache overwritten by cloud; no local source of truth)
                applyAppListsFromUserData(context, userData)
                
                // Send broadcast to update UI
                val intent = Intent("com.talq2me.baerenlock.ACTION_REWARD_TIME_UPDATED")
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                
                return@withContext true
            }
            
            Log.d(TAG, "No user_data found in cloud for profile: $cloudProfile")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading user_data from cloud: ${e.message}", e)
            return@withContext false
        } finally {
            downloadingProfiles.remove(cloudProfile)
        }
    }

    /**
     * Applies app lists from user_data (reward_apps, blacklisted_apps, white_listed_apps) to local prefs.
     * ONLINE-ONLY: prefs are cache only, overwritten by cloud; cloud is source of truth.
     */
    private fun applyAppListsFromUserData(context: Context, userData: Map<String, Any?>) {
        try {
            val rewardApps = parseJsonArrayToSet(userData["reward_apps"])
            val blacklisted = parseJsonArrayToSet(userData["blacklisted_apps"])
            val whiteListed = parseJsonArrayToSet(userData["white_listed_apps"])
            if (rewardApps != null) {
                val settingsPrefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
                settingsPrefs.edit().putStringSet("reward_apps", rewardApps).apply()
                Log.d(TAG, "Applied reward_apps from cloud: ${rewardApps.size} apps")
            }
            if (blacklisted != null) {
                val prefs = context.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
                prefs.edit().putStringSet("packages", blacklisted).apply()
                Log.d(TAG, "Applied blacklisted_apps from cloud: ${blacklisted.size} apps")
            }
            if (whiteListed != null) {
                val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
                prefs.edit().putStringSet("allowed", whiteListed).apply()
                Log.d(TAG, "Applied white_listed_apps from cloud: ${whiteListed.size} apps")
            }
            RewardAppsManager.loadAllowedApps(context)
            RewardAppsManager.loadRewardEligibleApps(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying app lists from user_data", e)
        }
    }

    private fun parseJsonArrayToSet(value: Any?): Set<String>? {
        if (value == null) return null
        return when (value) {
            is String -> try {
                val list = gson.fromJson(value, object : TypeToken<List<String>>() {}.type) as? List<String>
                list?.toSet()
            } catch (_: Exception) { null }
            is List<*> -> value.mapNotNull { it?.toString() }.toSet()
            else -> null
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
            val profile = ProfileManager.getCurrentProfile(context)
            val hcTs = generateESTTimestamp()
            val issuesOrNull = healthIssues?.takeIf { it.isNotBlank() }
            val ok = callRpcNoBody(
                context,
                "af_upsert_device",
                mapOf(
                    "p_device_id" to deviceId,
                    "p_device_name" to deviceName,
                    "p_active_profile" to profile,
                    "p_baerenlock_health_status" to healthStatus,
                    "p_baerenlock_health_issues" to issuesOrNull,
                    "p_baerenlock_last_health_check" to hcTs,
                    "p_apply_baerenlock_health" to true
                ),
                serializeNulls = true
            )
            if (ok) {
                Log.d(TAG, "Synced health check via af_upsert_device: deviceId=$deviceId, status=$healthStatus, issues=$healthIssues")
            }
            return@withContext ok
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing health check to cloud: ${e.message}", e)
        }
        false
    }
    
    /**
     * Syncs active profile to cloud devices table.
     * This allows BaerenLock and BaerenEd to share the active profile on the same device.
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
            
            if (forceUpdate) {
                Log.d(TAG, "syncActiveProfileToCloud: Force update requested")
            }
            
            val ok = callRpcNoBody(
                context,
                "af_upsert_device",
                mapOf(
                    "p_device_id" to deviceId,
                    "p_device_name" to deviceName,
                    "p_active_profile" to profile,
                    "p_last_updated" to localTimestamp
                )
            )
            if (ok) {
                Log.d(TAG, "Synced active profile to cloud devices table via af_upsert_device: deviceId=$deviceId, profile=$profile")
                return@withContext true
            }
            Log.e(TAG, "Failed to sync active profile via af_upsert_device: deviceId=$deviceId, profile=$profile")
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
        
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "No network connectivity, skipping active profile fetch")
            return@withContext null
        }
        
        try {
            val deviceId = getDeviceId(context)
            val responseBody = callRpcReturningBody(context, "af_get_device_row", mapOf("p_device_id" to deviceId))
            val device = parseJsonObjectBody(responseBody)
            val activeProfile = device?.get("active_profile") as? String
            val lastUpdated = device?.get("last_updated") as? String
            if (activeProfile != null) {
                Log.d(TAG, "Got active profile from cloud: $activeProfile, last_updated: $lastUpdated")
                return@withContext ProfileWithTimestamp(activeProfile, lastUpdated)
            }
        } catch (e: java.net.UnknownHostException) {
            // Network connectivity issue - log at debug level since this is expected when offline
            Log.d(TAG, "No network connectivity (UnknownHostException), skipping active profile fetch: ${e.message}")
        } catch (e: Exception) {
            // Other errors - log at error level
            Log.e(TAG, "Error getting active profile from cloud: ${e.message}", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}, stack trace: ${e.stackTrace.joinToString("\n")}")
        }
        null
    }
    
    /**
     * Async wrapper for syncActiveProfileToCloud
     * @param forceUpdate If true, bypasses timestamp check and always updates (for user-initiated changes)
     */
    fun syncActiveProfileToCloudAsync(context: Context, profile: String, forceUpdate: Boolean = false) {
        syncScope.launch {
            try {
                syncActiveProfileToCloud(context, profile, forceUpdate)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync active profile to cloud: ${e.message}")
            }
        }
    }
    
    /**
     * Ensures device record exists in devices table on startup.
     * Dumb-UI mode: upserts current local device/profile state directly.
     */
    suspend fun ensureDeviceRecord(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.w(TAG, "Supabase not configured - cannot create device record. Check BuildConfig.SUPABASE_URL and BuildConfig.SUPABASE_KEY")
            return@withContext false
        }
        
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "No network connectivity, skipping device record ensure")
            return@withContext false
        }
        
        val deviceId = getDeviceId(context)
        
        // Prevent concurrent calls for the same device
        if (ensuringDevices.contains(deviceId)) {
            Log.d(TAG, "Already ensuring device record for device: $deviceId, skipping duplicate request")
            return@withContext false
        }
        
        ensuringDevices.add(deviceId)
        
        try {
            val deviceName = getDeviceName(context)
            val localProfile = ProfileManager.getCurrentProfile(context)
            val localTimestamp = ProfileManager.getLocalProfileTimestamp(context)
            
            Log.d(TAG, "Ensuring device record exists: deviceId=$deviceId, deviceName=$deviceName, profile=$localProfile, localTimestamp=$localTimestamp")
            
            val ok = callRpcNoBody(
                context,
                "af_upsert_device",
                mapOf(
                    "p_device_id" to deviceId,
                    "p_device_name" to deviceName,
                    "p_active_profile" to localProfile,
                    "p_last_updated" to localTimestamp
                )
            )
            if (ok) {
                Log.d(TAG, "Successfully ensured device record via af_upsert_device: deviceId=$deviceId, profile=$localProfile")
                ensuringDevices.remove(deviceId)
                return@withContext true
            }
            Log.e(TAG, "Failed to ensure device record via af_upsert_device: deviceId=$deviceId, profile=$localProfile")
        } catch (e: java.net.UnknownHostException) {
            // Network connectivity issue - log at debug level since this is expected when offline
            Log.d(TAG, "No network connectivity (UnknownHostException), skipping device record ensure: ${e.message}")
        } catch (e: Exception) {
            // Other errors - log at error level
            Log.e(TAG, "Error ensuring device record: ${e.message}", e)
            e.printStackTrace()
        } finally {
            // Always remove from ensuring set, even if there was an error
            ensuringDevices.remove(deviceId)
        }
        false
    }
    
    /**
     * Async wrapper for ensureDeviceRecord
     */
    fun ensureDeviceRecordAsync(context: Context) {
        syncScope.launch {
            try {
                ensureDeviceRecord(context)
            } catch (e: Exception) {
                Log.w(TAG, "Could not ensure device record: ${e.message}")
            }
        }
    }
    
    /**
     * Generates EST timestamp in database format (no offset).
     * System now() is UTC; we convert to America/Toronto for the DB. Format: yyyy-MM-dd HH:mm:ss.SSS.
     */
    fun generateESTTimestamp(): String {
        val estTimeZone = java.util.TimeZone.getTimeZone("America/Toronto")
        val now = java.util.Date()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        return dateFormat.format(now)
    }

    /**
     * Computes reward_time_expiry as now (America/Toronto) + minutes. Format: yyyy-MM-dd HH:mm:ss.SSS.
     */
    fun computeRewardTimeExpiryEst(context: Context, minutesFromNow: Int): String {
        val estTimeZone = java.util.TimeZone.getTimeZone("America/Toronto")
        val cal = java.util.Calendar.getInstance(estTimeZone)
        cal.add(java.util.Calendar.MINUTE, minutesFromNow)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        return dateFormat.format(cal.time)
    }

    /**
     * Returns true if current time (EST) is after the given expiry string (EST). If expiry is null, returns false.
     */
    fun isAfterRewardTimeExpiry(expiryEst: String?): Boolean {
        if (expiryEst.isNullOrBlank()) return false
        return try {
            parseTimestampForComparison(expiryEst) < System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse reward_time_expiry: $expiryEst", e)
            false
        }
    }
    
    /**
     * Parses timestamp string to milliseconds for comparison (EST, DB format).
     */
    fun parseTimestampForComparison(timestamp: String): Long {
        return try {
            var baseTimestamp = when {
                timestamp.endsWith("Z") -> timestamp.substringBeforeLast('Z')
                timestamp.matches(Regex(".*[+-]\\d{2}:\\d{2}$")) -> {
                    val offsetStart = timestamp.lastIndexOfAny(charArrayOf('+', '-'))
                    if (offsetStart > 10) timestamp.substring(0, offsetStart) else timestamp
                }
                else -> timestamp
            }
            baseTimestamp = baseTimestamp.replace('T', ' ')

            val estZone = java.util.TimeZone.getTimeZone("America/Toronto")
            val formats = listOf(
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss.SS",
                "yyyy-MM-dd HH:mm:ss"
            )
            for (pattern in formats) {
                try {
                    val df = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
                    df.timeZone = estZone
                    val parsed = df.parse(baseTimestamp)
                    if (parsed != null) {
                        return parsed.time
                    }
                } catch (_: Exception) {
                    // try next
                }
            }
            0L
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp for comparison: $timestamp", e)
            0L
        }
    }
    
    /**
     * Async wrapper for syncAppListsToCloud
     */
    fun syncAppListsToCloudAsync(context: Context) {
        syncScope.launch {
            try {
                syncAppListsToCloud(context)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync app lists to cloud: ${e.message}")
            }
        }
    }
    
    /**
     * Async wrapper for syncRewardMinutesToCloud.
     * @param skipTimestampCheck When true (online-only mode), always push to cloud without comparing timestamps.
     */
    fun syncRewardMinutesToCloudAsync(context: Context, rewardMinutes: Int, skipTimestampCheck: Boolean = false, rewardTimeExpiry: String? = null) {
        syncScope.launch {
            try {
                syncRewardMinutesToCloud(context, rewardMinutes, skipTimestampCheck, rewardTimeExpiry)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync reward minutes to cloud: ${e.message}")
            }
        }
    }

    /**
     * Like [syncRewardMinutesToCloudAsync] but reads [RewardStorage.getRewardTimeExpiry] when the coroutine runs.
     * Enqueued saves must not capture expiry at enqueue time — after pause, a stale job could otherwise PATCH the old
     * [reward_time_expiry] back and undo [af_reward_time_pause].
     */
    fun syncRewardMinutesToCloudAsyncReadExpiryAtExecution(context: Context, rewardMinutes: Int, skipTimestampCheck: Boolean = false) {
        syncScope.launch {
            try {
                val expiry = RewardStorage.getRewardTimeExpiry()
                syncRewardMinutesToCloud(context, rewardMinutes, skipTimestampCheck, rewardTimeExpiry = expiry)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync reward minutes to cloud (deferred expiry read): ${e.message}")
            }
        }
    }
    
    /**
     * Async wrapper for downloadUserDataFromCloud
     * Downloads user_data for the current profile
     */
    fun downloadUserDataFromCloudAsync(context: Context) {
        syncScope.launch {
            try {
                val profile = ProfileManager.getCurrentProfile(context)
                downloadUserDataFromCloud(context, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading user_data from cloud: ${e.message}", e)
            }
        }
    }
    
    /**
     * Async wrapper for syncHealthCheckToCloud
     */
    fun syncHealthCheckToCloudAsync(context: Context, healthStatus: String, healthIssues: String?) {
        syncScope.launch {
            try {
                syncHealthCheckToCloud(context, healthStatus, healthIssues)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync health check to cloud: ${e.message}")
            }
        }
    }
    
    /**
     * Cancels all ongoing coroutines in the sync scope
     * Should be called when the app is being destroyed to prevent memory leaks
     */
    fun cancelAllSyncOperations() {
        syncScope.cancel()
        Log.d(TAG, "Cancelled all sync operations")
    }
}
