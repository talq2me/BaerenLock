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
     * Adds a package to the blacklist.
     */
    fun addToBlacklist(context: Context, packageName: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val blacklist = prefs.getStringSet(PACKAGES_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (blacklist.add(packageName)) {
                prefs.edit().putStringSet(PACKAGES_KEY, blacklist).apply()
                Log.d(TAG, "Added $packageName to blacklist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding to blacklist", e)
        }
    }

    /**
     * Removes a package from the blacklist.
     */
    fun removeFromBlacklist(context: Context, packageName: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val blacklist = prefs.getStringSet(PACKAGES_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (blacklist.remove(packageName)) {
                prefs.edit().putStringSet(PACKAGES_KEY, blacklist).apply()
                Log.d(TAG, "Removed $packageName from blacklist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing from blacklist", e)
        }
    }

    /**
     * Clears all entries from the blacklist.
     */
    fun clearAll(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(PACKAGES_KEY).apply()
            Log.d(TAG, "Cleared all blacklist entries")
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
    
}
