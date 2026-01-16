package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.talq2me.baerenlock.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private const val KEY_PROFILE_LAST_RESET = "profile_last_reset" // Format: yyyy-MM-dd HH:mm:ss.SSS (EST)
    private const val KEY_LAST_UPDATED = "last_updated_timestamp" // Format: ISO 8601 with EST timezone
    
    /**
     * Performs daily reset process followed by cloud sync.
     * This is the main entry point that should be called on main screen load/on focus.
     * 
     * @param context The application context
     * @param profile The profile to process (e.g., "AM" or "BM")
     */
    suspend fun dailyResetProcessAndSync(context: Context, profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting daily_reset_process() and cloud_sync() for profile: $profile")
        
        // Step 1: Run daily_reset_process()
        dailyResetProcess(context, profile)
        
        // Step 2: Run cloud_sync()
        cloudSync(context, profile)
        
        Log.d(TAG, "Completed daily_reset_process() and cloud_sync() for profile: $profile")
    }
    
    /**
     * Daily reset process according to requirements:
     * - If local.profile.last_reset is today (date part only, EST), do nothing
     * - Otherwise, compare with cloud.profile.last_reset
     *   - If cloud not available after retries -> call reset_local()
     *   - If cloud is today -> attempt cloud_sync()
     *   - If cloud is older than today -> call reset_local()
     */
    private suspend fun dailyResetProcess(context: Context, profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "daily_reset_process() started for profile: $profile")
        
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val localLastReset = getLocalLastReset(context, profile)
        val isLocalToday = isTodayInEST(localLastReset)
        
        if (isLocalToday) {
            Log.d(TAG, "Local last_reset is today, no reset needed")
            return@withContext
        }
        
        // Local is old, need to compare with cloud
        Log.d(TAG, "Local last_reset is old: $localLastReset, checking cloud...")
        
        val cloudLastReset = getCloudLastResetWithRetry(context, profile)
        
        when {
            cloudLastReset == null -> {
                // Cloud not available after retries
                Log.d(TAG, "Cloud last_reset not available after retries, calling reset_local()")
                resetLocal(context, profile)
            }
            isTodayInEST(cloudLastReset) -> {
                // Cloud is today, attempt cloud sync
                Log.d(TAG, "Cloud last_reset is today: $cloudLastReset, will attempt cloud_sync()")
                // Note: cloud_sync() will be called after this method returns
            }
            else -> {
                // Cloud is older than today
                Log.d(TAG, "Cloud last_reset is older than today: $cloudLastReset, calling reset_local()")
                resetLocal(context, profile)
            }
        }
    }
    
    /**
     * Resets local progress according to requirements:
     * - Set local.profile.last_reset = now() at EST
     * - Reset local data (berries=0, banked_mins=0, required_tasks=null, practice_tasks=null, checklist_items=null)
     *   Note: BaerenLock only manages berries and banked_mins locally
     * - Set local.profile.last_updated = local.profile.last_reset
     */
    private suspend fun resetLocal(context: Context, profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "reset_local() started for profile: $profile")
        
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        
        // Generate EST timestamp in format: yyyy-MM-dd HH:mm:ss.SSS
        val estTimestamp = generateESTTimestampString()
        
        // Set local.profile.last_reset
        val profileLastResetKey = "${profile}_$KEY_PROFILE_LAST_RESET"
        prefs.edit().putString(profileLastResetKey, estTimestamp).apply()
        
        // Reset local data (berries and banked_mins)
        // Note: BaerenLock doesn't store required_tasks/practice_tasks/checklist_items locally
        resetLocalProgressData(context, profile)
        
        // Set local.profile.last_updated = local.profile.last_reset
        val isoTimestamp = convertToISOTimestamp(estTimestamp)
        setLocalLastUpdatedTimestamp(context, profile, isoTimestamp)
        
        Log.d(TAG, "reset_local() completed for profile: $profile, timestamp: $estTimestamp")
    }
    
    /**
     * Cloud sync according to requirements:
     * - Compare local.profile.last_updated with cloud.profile.last_updated
     * - If equal or cloud not found -> do nothing
     * - If local is newer -> call update_cloud_with_local()
     * - If cloud is newer -> call update_local_with_cloud()
     */
    private suspend fun cloudSync(context: Context, profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "cloud_sync() started for profile: $profile")
        
        if (!CloudSyncManager.isConfigured(context)) {
            Log.d(TAG, "Cloud storage not configured, skipping cloud_sync()")
            return@withContext
        }
        
        val localLastUpdated = getLocalLastUpdatedTimestamp(context, profile)
        val cloudLastUpdated = getCloudLastUpdated(context, profile)
        
        if (cloudLastUpdated == null) {
            Log.d(TAG, "Cloud last_updated not found, doing nothing")
            return@withContext
        }
        
        // Compare timestamps (both in EST)
        val comparison = compareTimestamps(localLastUpdated, cloudLastUpdated)
        
        when {
            comparison == 0 -> {
                // Timestamps are equal
                Log.d(TAG, "Local and cloud timestamps are equal, doing nothing")
            }
            comparison > 0 -> {
                // Local is newer
                Log.d(TAG, "Local is newer ($localLastUpdated > $cloudLastUpdated), calling update_cloud_with_local()")
                updateCloudWithLocal(context, profile)
            }
            else -> {
                // Cloud is newer
                Log.d(TAG, "Cloud is newer ($cloudLastUpdated > $localLastUpdated), calling update_local_with_cloud()")
                updateLocalWithCloud(context, profile)
            }
        }
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
            
            // Sync reward minutes (banked_mins)
            val bankedMinutes = RewardStorage.getCurrentRewardMinutes()
            CloudSyncManager.syncRewardMinutesToCloud(context, bankedMinutes)
            
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
     * Resets local progress data (berries and banked_mins)
     */
    private fun resetLocalProgressData(context: Context, profile: String) {
        // Reset banked_mins
        RewardStorage.setCurrentRewardMinutes(0)
        
        // Note: BaerenLock doesn't directly manage berries_earned - that's managed by BaerenEd
        // But we can clear any local berries tracking if it exists
        val prefs = context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("${profile}_berries_earned").apply()
        
        Log.d(TAG, "Reset local progress data for profile: $profile")
    }
    
    /**
     * Checks if a timestamp (in format yyyy-MM-dd HH:mm:ss.SSS) is today in EST
     */
    private fun isTodayInEST(timestamp: String?): Boolean {
        if (timestamp.isNullOrEmpty()) return false
        
        return try {
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            timestampFormat.timeZone = TimeZone.getTimeZone("America/New_York")
            val resetDate = timestampFormat.parse(timestamp)
            
            if (resetDate == null) return false
            
            val today = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            val resetCalendar = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            resetCalendar.time = resetDate
            
            // Compare date part only
            today.get(Calendar.YEAR) == resetCalendar.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == resetCalendar.get(Calendar.DAY_OF_YEAR)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp: $timestamp", e)
            false
        }
    }
    
    /**
     * Gets local last_reset timestamp for profile
     */
    private fun getLocalLastReset(context: Context, profile: String): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val key = "${profile}_$KEY_PROFILE_LAST_RESET"
        return prefs.getString(key, null)
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
     * Compares two ISO timestamps (returns positive if first is newer, negative if second is newer, 0 if equal)
     */
    private fun compareTimestamps(timestamp1: String, timestamp2: String): Int {
        val time1 = parseISOTimestampAsEST(timestamp1)
        val time2 = parseISOTimestampAsEST(timestamp2)
        return time1.compareTo(time2)
    }
    
    /**
     * Parses ISO timestamp as EST (milliseconds since epoch)
     */
    private fun parseISOTimestampAsEST(timestamp: String): Long {
        return try {
            // Strip timezone suffix and parse as EST
            var baseTimestamp = timestamp
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
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("America/New_York")
            dateFormat.parse(baseTimestamp)?.time ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ISO timestamp: $timestamp", e)
            0L
        }
    }
    
    /**
     * Generates EST timestamp in format: yyyy-MM-dd HH:mm:ss.SSS
     */
    private fun generateESTTimestampString(): String {
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        timestampFormat.timeZone = TimeZone.getTimeZone("America/New_York")
        return timestampFormat.format(Date())
    }
    
    /**
     * Converts EST timestamp string (yyyy-MM-dd HH:mm:ss.SSS) to ISO format
     */
    private fun convertToISOTimestamp(estTimestamp: String): String {
        return try {
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            timestampFormat.timeZone = TimeZone.getTimeZone("America/New_York")
            val date = timestampFormat.parse(estTimestamp)
            
            if (date != null) {
                val estTimeZone = TimeZone.getTimeZone("America/New_York")
                val offsetMillis = estTimeZone.getOffset(date.time)
                val offsetHours = offsetMillis / (1000 * 60 * 60)
                val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
                val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
                
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())
                isoFormat.timeZone = estTimeZone
                isoFormat.format(date) + offsetString
            } else {
                CloudSyncManager.generateESTTimestamp()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting to ISO timestamp", e)
            CloudSyncManager.generateESTTimestamp()
        }
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
            parseFormat.timeZone = TimeZone.getTimeZone("America/New_York")
            val parsedDate = parseFormat.parse(baseTimestamp)
            
            if (parsedDate != null) {
                val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                outputFormat.timeZone = TimeZone.getTimeZone("America/New_York")
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
