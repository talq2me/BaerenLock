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
        
        // Sync to cloud devices table asynchronously
        // Force update since this is a user-initiated profile change
        CloudSyncManager.syncActiveProfileToCloudAsync(context, profile, forceUpdate = true)
    }
    
    /**
     * Gets the local profile timestamp (when profile was last changed locally)
     */
    fun getLocalProfileTimestamp(context: Context): String? {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PROFILE_TIMESTAMP_KEY, null)
    }

    /**
     * Gets the current profile (returns AM or BM directly, no conversion needed)
     */
    fun getCurrentProfile(context: Context): String {
        return readProfile(context) ?: "AM" // Default to AM if null
    }

    /**
     * Checks if a profile string is valid (AM or BM)
     */
    fun isValidProfile(profile: String?): Boolean {
        return profile == "AM" || profile == "BM"
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
