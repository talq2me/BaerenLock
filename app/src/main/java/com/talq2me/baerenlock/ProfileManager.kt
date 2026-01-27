package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*

/**
 * Manages profile logic - uses AM/BM format consistently everywhere
 */
object ProfileManager {
    private const val TAG = "ProfileManager"
    private const val LOCAL_PREFS_NAME = "settings"
    private const val PROFILE_KEY = "profile"
    private const val PROFILE_TIMESTAMP_KEY = "profile_timestamp"

    /**
     * Reads profile from local storage (AM or BM)
     * Converts old format (A/B) to new format (AM/BM) if needed
     * Returns null if no profile is set
     */
    fun readProfile(context: Context): String? {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val profile = prefs.getString(PROFILE_KEY, null) ?: return null
        
        // Convert old format (A/B) to new format (AM/BM) if needed
        return when (profile) {
            "A" -> {
                // Migrate to new format
                prefs.edit().putString(PROFILE_KEY, "AM").apply()
                Log.d(TAG, "Migrated profile from old format 'A' to new format 'AM'")
                "AM"
            }
            "B" -> {
                // Migrate to new format
                prefs.edit().putString(PROFILE_KEY, "BM").apply()
                Log.d(TAG, "Migrated profile from old format 'B' to new format 'BM'")
                "BM"
            }
            else -> profile // Already in new format (AM/BM) or unknown format
        }
    }

    /**
     * Writes profile to local storage (AM or BM) and syncs to cloud devices table
     * Also stores a timestamp for comparison with cloud
     */
    fun writeProfile(context: Context, profile: String) {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = CloudSyncManager.generateESTTimestamp()
        prefs.edit().apply {
            putString(PROFILE_KEY, profile)
            putString(PROFILE_TIMESTAMP_KEY, timestamp)
            apply()
        }
        Log.d(TAG, "Profile written: $profile, timestamp: $timestamp")
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        updateLastUpdatedTimestamp(context, profile)
        
        // Sync to cloud devices table asynchronously
        // Force update since this is a user-initiated profile change
        CloudSyncManager.syncActiveProfileToCloudAsync(context, profile, forceUpdate = true)
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
    
    /**
     * Gets the local profile timestamp (when profile was last changed locally)
     */
    fun getLocalProfileTimestamp(context: Context): String? {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PROFILE_TIMESTAMP_KEY, null) ?: return null
        val normalized = normalizeTimestampToDbFormat(raw)
        if (normalized != raw) {
            prefs.edit().putString(PROFILE_TIMESTAMP_KEY, normalized).apply()
            Log.d(TAG, "Normalized profile_timestamp from '$raw' to '$normalized'")
        }
        return normalized
    }

    /**
     * Normalizes timestamps to DB format (yyyy-MM-dd HH:mm:ss.SSS, EST, no offset).
     */
    private fun normalizeTimestampToDbFormat(timestamp: String): String {
        val needsNormalize = timestamp.contains('T') || timestamp.endsWith("Z") || timestamp.matches(Regex(".*[+-]\\d{2}:\\d{2}$"))
        if (!needsNormalize) return timestamp

        val parsedMillis = CloudSyncManager.parseTimestampForComparison(timestamp)
        if (parsedMillis <= 0L) return timestamp

        val estZone = java.util.TimeZone.getTimeZone("America/New_York")
        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        df.timeZone = estZone
        return df.format(java.util.Date(parsedMillis))
    }

    /**
     * Gets the current profile (returns AM or BM directly, no conversion needed)
     */
    fun getCurrentProfile(context: Context): String {
        return readProfile(context) ?: "AM" // Default to AM if null
    }

    /**
     * Checks if a profile string is valid (AM, BM, or TE)
     */
    fun isValidProfile(profile: String?): Boolean {
        return profile == "AM" || profile == "BM" || profile == "TE"
    }
    
    /**
     * Legacy compatibility: toCloudProfile now just returns the profile as-is
     * @deprecated Profiles are now always in AM/BM format, no conversion needed
     */
    @Deprecated("Profiles are now always in AM/BM format, use getCurrentProfile() instead")
    fun toCloudProfile(profile: String?): String {
        return profile ?: "AM"
    }
    
    /**
     * Legacy compatibility: getCurrentCloudProfile now just returns the profile
     * @deprecated Use getCurrentProfile() instead
     */
    @Deprecated("Use getCurrentProfile() instead")
    fun getCurrentCloudProfile(context: Context): String {
        return getCurrentProfile(context)
    }
}
