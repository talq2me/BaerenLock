package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.talq2me.baerenlock.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages daily reset process and cloud sync for BaerenLock according to the Daily Reset Logic requirements.
 * 
 * BaerenLock syncs: reward_apps, blacklisted_apps, white_listed_apps, berries_earned, banked_mins
 * BaerenLock doesn't manage required_tasks, practice_tasks, checklist_items - those are managed by BaerenEd
 */
object DailyResetAndSyncManager {
    
    private const val TAG = "DailyResetAndSyncManager"
    private const val PREF_NAME = "settings"
    private const val KEY_LAST_UPDATED = "last_updated_timestamp" // Format: yyyy-MM-dd HH:mm:ss.SSS (EST)
    
    /**
     * Performs daily reset process followed by cloud sync.
     * This is the main entry point that should be called on main screen load/on focus.
     * 
     * @param context The application context
     * @param profile The profile to process (e.g., "AM" or "BM")
     */
    suspend fun dailyResetProcessAndSync(context: Context, profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting daily_reset_process() and cloud_sync() for profile: $profile")
        
        // Normalize any legacy local timestamps to EST DB format (no offset).
        normalizeAllTimestamps(context)
        
        // Step 1: Run daily_reset_process()
        dailyResetProcess(context, profile)
        
        // Step 2: Run cloud_sync()
        cloudSync(context, profile)
        
        Log.d(TAG, "Completed daily_reset_process() and cloud_sync() for profile: $profile")
    }
    
    /**
     * Daily reset process: only reset if DB last_reset for the profile is not from today (EST).
     * No local last_reset comparison - DB is the single source of truth.
     * - If we cannot fetch DB last_reset -> do not reset (proceed to cloud_sync only).
     * - If DB last_reset date (EST) is today -> do not reset.
     * - If DB last_reset date is not today -> reset DB (triggerCloudReset), then pull from DB (cloud_sync).
     */
    private suspend fun dailyResetProcess(context: Context, profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "daily_reset_process() started for profile: $profile (DB-only: notice → reset DB → pull)")
        
        val cloudLastReset = getCloudLastResetWithRetry(context, profile)
        
        when {
            cloudLastReset == null -> {
                Log.d(TAG, "DB last_reset not available after retries, skipping reset; will pull from DB")
            }
            isTodayInEST(cloudLastReset) -> {
                Log.d(TAG, "DB last_reset is today: $cloudLastReset, no reset needed; will pull from DB")
            }
            else -> {
                Log.d(TAG, "DB last_reset is not today: $cloudLastReset, resetting DB then pulling from DB")
                CloudSyncManager.triggerCloudReset(context, profile)
            }
        }
    }
    
    /**
     * Cloud sync according to requirements:
     * - Compare local.profile.last_updated with cloud.profile.last_updated
     * - If equal or cloud not found -> do nothing
     * - If local is newer -> call update_cloud_with_local()
     * - If cloud is newer -> call update_local_with_cloud()
     */
    /**
     * ONLINE-ONLY: Always fetch from cloud and apply. No local/cloud timestamp merge.
     * Pushes to cloud happen when user/timer changes something (saveRewardMinutes, syncAppListsToCloud, etc.).
     */
    private suspend fun cloudSync(context: Context, profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "cloud_sync() started for profile: $profile (online-only: always fetch from cloud)")
        
        if (!CloudSyncManager.isConfigured(context)) {
            Log.d(TAG, "Cloud storage not configured, skipping cloud_sync()")
            return@withContext
        }
        updateLocalWithCloud(context, profile)
    }
    
    /**
     * Updates cloud with local data (all or nothing operation).
     * Syncs: reward_apps, blacklisted_apps, white_listed_apps, berries_earned, banked_mins,
     * parent_email, parent_pin, device.active_profile
     */
    private suspend fun updateCloudWithLocal(context: Context, profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "update_cloud_with_local() started for profile: $profile")
        
        try {
            // Sync app lists
            CloudSyncManager.syncAppListsToCloud(context)
            
            // Sync reward minutes (banked_mins) and reward_time_expiry
            // Skip timestamp check since cloud_sync() already determined local is newer
            val bankedMinutes = RewardStorage.getCurrentRewardMinutes()
            val rewardTimeExpiry = RewardStorage.getRewardTimeExpiry()
            CloudSyncManager.syncRewardMinutesToCloud(context, bankedMinutes, skipTimestampCheck = true, rewardTimeExpiry = rewardTimeExpiry)
            
            // Sync settings (parent_email, parent_pin)
            // Load current settings and create SettingsData for cloud sync
            val pin = SettingsManager.readPin(context)
            val email = SettingsManager.readEmail(context)
            val aggressiveCleanup = SettingsManager.readAggressiveCleanup(context)
            val currentSettings = SettingsManager.SettingsData(
                profile = null, // Not in settings table
                pin = pin,
                parentEmail = email,
                childName = null, // Not in settings table
                rewardApps = null, // Not in settings table
                aggressiveCleanup = aggressiveCleanup
            )
            CloudSyncManager.saveSettingsToCloud(context, currentSettings)
            
            // Sync active profile to devices table
            CloudSyncManager.syncActiveProfileToCloudAsync(context, profile, forceUpdate = true)
            
            Log.d(TAG, "update_cloud_with_local() completed successfully for profile: $profile")
        } catch (e: Exception) {
            Log.e(TAG, "Error in update_cloud_with_local()", e)
            throw e // Re-throw to allow retry later
        }
    }
    
    /**
     * Updates local with cloud data (all or nothing operation).
     * Syncs: reward_apps, blacklisted_apps, white_listed_apps, berries_earned, banked_mins,
     * parent_email, parent_pin, device.active_profile
     */
    private suspend fun updateLocalWithCloud(context: Context, profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "update_local_with_cloud() started for profile: $profile")
        
        try {
            // Download and apply app lists and reward minutes (banked_mins) from cloud
            // downloadUserDataFromCloud downloads everything from user_data table (app lists, reward minutes, etc.)
            CloudSyncManager.downloadUserDataFromCloud(context, profile)
            
            // Download and apply settings (parent_email, parent_pin)
            CloudSyncManager.loadSettingsFromCloud(context)
            
            // Download and apply active profile from devices table
            CloudSyncManager.getActiveProfileFromCloud(context)?.let { profileData: CloudSyncManager.ProfileWithTimestamp ->
                ProfileManager.writeProfile(context, profileData.profile)
            }
            
            // Update local last_updated timestamp from cloud
            val cloudLastUpdated = getCloudLastUpdated(context, profile)
            if (cloudLastUpdated != null) {
                setLocalLastUpdatedTimestamp(context, profile, cloudLastUpdated)
            }
            
            Log.d(TAG, "update_local_with_cloud() completed successfully for profile: $profile")
        } catch (e: Exception) {
            Log.e(TAG, "Error in update_local_with_cloud()", e)
            throw e // Re-throw to allow retry later
        }
    }
    
    /**
     * Checks if last_reset is today in EST (date part only).
     * user_data.last_reset is stored and returned in EST — parse as EST, do not convert.
     */
    private fun isTodayInEST(timestamp: String?): Boolean {
        if (timestamp.isNullOrEmpty()) return false
        val estZone = TimeZone.getTimeZone("America/Toronto")
        val resetDate = parseLastResetToDate(timestamp.trim()) ?: run {
            Log.e(TAG, "Could not parse last_reset (treat as not today): $timestamp")
            return false
        }
        val today = Calendar.getInstance(estZone)
        val resetCalendar = Calendar.getInstance(estZone).apply { time = resetDate }
        return today.get(Calendar.YEAR) == resetCalendar.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == resetCalendar.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Parses last_reset from user_data. The DB stores and returns last_reset in EST; parse as EST only.
     */
    private fun parseLastResetToDate(timestamp: String): Date? {
        val trimmed = timestamp.trim()
        val estZone = TimeZone.getTimeZone("America/Toronto")
        return try {
            if (!trimmed.contains("T")) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = estZone }.parse(trimmed)
                    ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = estZone }.parse(trimmed)
            } else {
                val normalized = trimmed.replace("T", " ").let { s ->
                    when {
                        s.endsWith("Z") -> s.dropLast(1)
                        s.contains("+") -> s.substringBefore("+").trim()
                        s.lastIndexOf("-") > 10 -> s.substring(0, s.lastIndexOf("-")).trim()
                        else -> s
                    }
                }
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = estZone }.parse(normalized)
                    ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = estZone }.parse(normalized)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseLastResetToDate failed: $timestamp", e)
            null
        }
    }
    
    /**
     * Gets cloud last_reset with retry (up to 3 times)
     */
    private suspend fun getCloudLastResetWithRetry(context: Context, profile: String, maxRetries: Int = 3): String? {
        for (attempt in 1..maxRetries) {
            try {
                val result = getCloudLastReset(context, profile)
                if (result != null) return result
                if (attempt < maxRetries) {
                    Log.d(TAG, "Cloud last_reset not available, retry $attempt/$maxRetries")
                    kotlinx.coroutines.delay(500) // Small delay before retry
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error getting cloud last_reset (attempt $attempt): ${e.message}")
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(500)
                }
            }
        }
        return null
    }
    
    /**
     * Gets cloud last_reset from Supabase
     */
    private suspend fun getCloudLastReset(context: Context, profile: String): String? = withContext(Dispatchers.IO) {
        if (!CloudSyncManager.isConfigured(context)) return@withContext null
        
        try {
            // Use CloudSyncManager's checkIfResetNeeded logic to get last_reset
            // But we need to query directly - let's use CloudSyncManager's pattern
            val url = try {
                val supabaseUrl = try {
                    BuildConfig.SUPABASE_URL.ifBlank { "" }
                } catch (e: Exception) {
                    ""
                }
                "$supabaseUrl/rest/v1/user_data?profile=eq.$profile&select=last_reset"
            } catch (e: Exception) {
                return@withContext null
            }
            
            val client = okhttp3.OkHttpClient()
            val supabaseKey = try {
                BuildConfig.SUPABASE_KEY.ifBlank { "" }
            } catch (e: Exception) {
                return@withContext null
            }
            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                response.close()
                
                val gson = Gson()
                val dataList: List<Map<String, String?>> = gson.fromJson(
                    responseBody,
                    object : TypeToken<List<Map<String, String?>>>() {}.type
                )
                
                val cloudLastReset = dataList.firstOrNull()?.get("last_reset")
                if (cloudLastReset != null) {
                    // Convert ISO timestamp to EST format (yyyy-MM-dd HH:mm:ss.SSS)
                    return@withContext convertFromISOTimestamp(cloudLastReset)
                }
            } else {
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cloud last_reset", e)
        }
        
        null
    }
    
    /**
     * Gets cloud last_updated from Supabase
     */
    private suspend fun getCloudLastUpdated(context: Context, profile: String): String? = withContext(Dispatchers.IO) {
        if (!CloudSyncManager.isConfigured(context)) return@withContext null
        
        try {
            val url = try {
                val supabaseUrl = try {
                    BuildConfig.SUPABASE_URL.ifBlank { "" }
                } catch (e: Exception) {
                    ""
                }
                "$supabaseUrl/rest/v1/user_data?profile=eq.$profile&select=last_updated"
            } catch (e: Exception) {
                return@withContext null
            }
            
            val client = okhttp3.OkHttpClient()
            val supabaseKey = try {
                BuildConfig.SUPABASE_KEY.ifBlank { "" }
            } catch (e: Exception) {
                return@withContext null
            }
            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                response.close()
                
                val gson = Gson()
                val dataList: List<Map<String, String?>> = gson.fromJson(
                    responseBody,
                    object : TypeToken<List<Map<String, String?>>>() {}.type
                )
                
                return@withContext dataList.firstOrNull()?.get("last_updated")
            } else {
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cloud last_updated", e)
        }
        
        null
    }
    
    /**
     * Gets local last_updated timestamp
     */
    private fun getLocalLastUpdatedTimestamp(context: Context, profile: String): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val key = "${profile}_$KEY_LAST_UPDATED"
        val timestamp = prefs.getString(key, null)
        return timestamp ?: "1970-01-01T00:00:00.000-05:00" // Very old timestamp if not found
    }
    
    /**
     * Sets local last_updated timestamp
     */
    private fun setLocalLastUpdatedTimestamp(context: Context, profile: String, timestamp: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val key = "${profile}_$KEY_LAST_UPDATED"
        prefs.edit().putString(key, timestamp).apply()
    }

    /**
     * Normalizes local timestamps to EST DB format (yyyy-MM-dd HH:mm:ss.SSS, no offset).
     */
    private fun normalizeAllTimestamps(context: Context) {
        val prefsNames = listOf(
            PREF_NAME,
            "reward_prefs",
            "reward_report_prefs",
            "health_prefs",
            "com.talq2me.baerenlock.prefs",
            "usage_data",
            "device_owner_setup",
            "device_restrictions",
            "blacklist_prefs",
            "whitelist_prefs"
        )
        prefsNames.forEach { name ->
            val targetPrefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            normalizeTimestampPrefs(targetPrefs, name)
        }
    }

    private fun normalizeTimestampPrefs(targetPrefs: SharedPreferences, prefsName: String) {
        val all = targetPrefs.all
        if (all.isEmpty()) return

        val estZone = TimeZone.getTimeZone("America/Toronto")
        val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).apply {
            timeZone = estZone
        }
        val editor = targetPrefs.edit()
        var changed = false

        all.forEach { (key, value) ->
            val raw = value as? String ?: return@forEach
            val isTimestampKey = key.contains("timestamp") || key.contains("last_updated") || key.contains("last_reset")
            if (!isTimestampKey) return@forEach

            val needsNormalize = raw.contains('T') || raw.endsWith("Z") || raw.matches(Regex(".*[+-]\\d{2}:\\d{2}$"))
            if (!needsNormalize) return@forEach

            val parsedMillis = parseISOTimestampAsEST(raw)
            if (parsedMillis <= 0L) return@forEach

            val normalized = outputFormat.format(Date(parsedMillis))
            if (normalized != raw) {
                editor.putString(key, normalized)
                changed = true
                Log.d(TAG, "Normalized $prefsName.$key from '$raw' to '$normalized'")
            }
        }

        if (changed) {
            editor.apply()
        }
    }
    
    /**
     * Compares two ISO timestamps (returns positive if first is newer, negative if second is newer, 0 if equal)
     */
    private fun compareTimestamps(timestamp1: String, timestamp2: String): Int {
        val time1 = parseISOTimestampAsEST(timestamp1)
        val time2 = parseISOTimestampAsEST(timestamp2)
        return time1.compareTo(time2)
    }
    
    /**
     * Parses timestamp as EST (milliseconds since epoch).
     * Accepts both DB format (yyyy-MM-dd HH:mm:ss.SSS) and ISO variants.
     */
    private fun parseISOTimestampAsEST(timestamp: String): Long {
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

            val estZone = TimeZone.getTimeZone("America/Toronto")
            val formats = listOf(
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss.SS",
                "yyyy-MM-dd HH:mm:ss"
            )
            for (pattern in formats) {
                try {
                    val df = SimpleDateFormat(pattern, Locale.getDefault())
                    df.timeZone = estZone
                    val parsed = df.parse(baseTimestamp)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {
                    // try next
                }
            }
            0L
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp: $timestamp", e)
            0L
        }
    }
    
    /**
     * System now() is UTC; we convert to America/Toronto. Format: yyyy-MM-dd HH:mm:ss.SSS.
     */
    private fun generateESTTimestampString(): String {
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        timestampFormat.timeZone = TimeZone.getTimeZone("America/Toronto")
        return timestampFormat.format(Date())
    }
    
    /**
     * Converts EST timestamp string to database format (no timezone suffix).
     */
    private fun convertToISOTimestamp(estTimestamp: String): String {
        return estTimestamp
    }
    
    /**
     * Converts ISO timestamp to EST format (yyyy-MM-dd HH:mm:ss.SSS)
     */
    private fun convertFromISOTimestamp(isoTimestamp: String): String {
        return try {
            var baseTimestamp = isoTimestamp
            if (baseTimestamp.endsWith("Z")) {
                baseTimestamp = baseTimestamp.substringBeforeLast('Z')
            } else if (baseTimestamp.matches(Regex(".*[+-]\\d{2}:\\d{2}$"))) {
                val lastPlus = baseTimestamp.lastIndexOf('+')
                val lastMinus = baseTimestamp.lastIndexOf('-')
                val offsetStart = if (lastPlus > lastMinus) lastPlus else lastMinus
                if (offsetStart > 10) {
                    baseTimestamp = baseTimestamp.substring(0, offsetStart)
                }
            }
            
            val parseFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())
            parseFormat.timeZone = TimeZone.getTimeZone("America/Toronto")
            val parsedDate = parseFormat.parse(baseTimestamp)
            
            if (parsedDate != null) {
                val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                outputFormat.timeZone = TimeZone.getTimeZone("America/Toronto")
                outputFormat.format(parsedDate)
            } else {
                generateESTTimestampString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting from ISO timestamp: $isoTimestamp", e)
            generateESTTimestampString()
        }
    }
}
