package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages blacklist operations (apps that should always be blocked)
 * Centralizes blacklist storage and access
 */
object BlacklistManager {
    private const val TAG = "BlacklistManager"
    private const val PREFS_NAME = "blacklist_prefs"
    private const val PACKAGES_KEY = "packages"

    /**
     * Gets the current blacklist of package names
     */
    fun getBlacklist(context: Context): Set<String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getStringSet(PACKAGES_KEY, emptySet()) ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting blacklist", e)
            emptySet()
        }
    }

    /**
     * Adds a package to the blacklist
     * Also updates timestamp for cloud sync comparison
     */
    fun addToBlacklist(context: Context, packageName: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val blacklist = prefs.getStringSet(PACKAGES_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (blacklist.add(packageName)) {
                val timestamp = CloudSyncManager.generateESTTimestamp()
                prefs.edit().putStringSet(PACKAGES_KEY, blacklist).apply()
                settingsPrefs.edit().putString("app_lists_timestamp", timestamp).apply()
                Log.d(TAG, "Added $packageName to blacklist, timestamp: $timestamp")
                
                // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
                val profile = ProfileManager.getCurrentProfile(context)
                updateLastUpdatedTimestamp(context, profile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding to blacklist", e)
        }
    }

    /**
     * Removes a package from the blacklist
     * Also updates timestamp for cloud sync comparison
     */
    fun removeFromBlacklist(context: Context, packageName: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val blacklist = prefs.getStringSet(PACKAGES_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (blacklist.remove(packageName)) {
                val timestamp = CloudSyncManager.generateESTTimestamp()
                prefs.edit().putStringSet(PACKAGES_KEY, blacklist).apply()
                settingsPrefs.edit().putString("app_lists_timestamp", timestamp).apply()
                Log.d(TAG, "Removed $packageName from blacklist, timestamp: $timestamp")
                
                // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
                val profile = ProfileManager.getCurrentProfile(context)
                updateLastUpdatedTimestamp(context, profile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing from blacklist", e)
        }
    }

    /**
     * Clears all entries from the blacklist
     * Also updates timestamp for cloud sync comparison
     */
    fun clearAll(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val timestamp = CloudSyncManager.generateESTTimestamp()
            prefs.edit().remove(PACKAGES_KEY).apply()
            settingsPrefs.edit().putString("app_lists_timestamp", timestamp).apply()
            Log.d(TAG, "Cleared all blacklist entries, timestamp: $timestamp")
            
            // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
            val profile = ProfileManager.getCurrentProfile(context)
            updateLastUpdatedTimestamp(context, profile)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing blacklist", e)
        }
    }

    /**
     * Checks if a package is in the blacklist
     */
    fun isBlacklisted(context: Context, packageName: String): Boolean {
        return getBlacklist(context).contains(packageName)
    }
    
    /**
     * Updates last_updated timestamp in settings prefs to trigger cloud sync
     * This is called whenever settings that should sync to cloud are changed (as per Daily Reset Logic)
     */
    private fun updateLastUpdatedTimestamp(context: Context, profile: String) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val timestamp = CloudSyncManager.generateESTTimestamp()
        val key = "${profile}_last_updated_timestamp"
        prefs.edit().putString(key, timestamp).apply()
        Log.d(TAG, "Updated last_updated timestamp for profile $profile: $timestamp")
    }
}
