package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Calendar

/**
 * Manages storage and persistence of reward minutes.
 * Handles saving/loading reward minutes, transaction processing, and daily resets.
 */
object RewardStorage {
    private const val TAG = "RewardStorage"
    private const val PREFS_NAME = "reward_prefs"
    private const val KEY_CURRENT_REWARD_MINUTES = "current_reward_minutes"
    private const val KEY_LAST_REWARD_DATE = "last_reward_date"
    private const val KEY_BANKED_MINS_TIMESTAMP = "banked_mins_timestamp"
    private const val KEY_PROCESSED_TRANSACTION_IDS = "processed_transaction_ids"
    
    // In-memory cache of current reward minutes
    @Volatile
    private var currentRewardMinutes: Int = 0
    
    /**
     * Gets the current reward minutes from memory
     */
    fun getCurrentRewardMinutes(): Int {
        return currentRewardMinutes
    }
    
    /**
     * Gets the current reward minutes directly from SharedPreferences.
     * Use this when you need to ensure you're reading the latest persisted value,
     * bypassing the in-memory cache (useful for avoiding race conditions).
     */
    fun getCurrentRewardMinutesFromStorage(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CURRENT_REWARD_MINUTES, 0)
    }
    
    /**
     * Sets the current reward minutes in memory (does not persist)
     */
    fun setCurrentRewardMinutes(minutes: Int) {
        currentRewardMinutes = minutes
    }
    
    /**
     * Saves reward minutes to local storage and syncs to cloud.
     * Also updates the timestamp for cloud synchronization.
     */
    fun saveRewardMinutes(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(KEY_CURRENT_REWARD_MINUTES, currentRewardMinutes)
        // Save today's date to track daily reset
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        editor.putLong(KEY_LAST_REWARD_DATE, today)
        
        // Generate and store timestamp for banked_mins (ISO 8601 format with EST timezone)
        val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
        val now = java.util.Date()
        val offsetMillis = estTimeZone.getOffset(now.time)
        val offsetHours = offsetMillis / (1000 * 60 * 60)
        val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
        val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        val timestamp = dateFormat.format(now) + offsetString
        editor.putString(KEY_BANKED_MINS_TIMESTAMP, timestamp)
        
        editor.apply()
        Log.d(TAG, "Saved reward minutes to SharedPreferences: $currentRewardMinutes, date: $today, timestamp: $timestamp")
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        val profile = ProfileManager.getCurrentProfile(context)
        updateLastUpdatedTimestamp(context, profile)
        
        // Sync to cloud database to keep it accurate (this will also update cloud timestamp)
        syncRewardMinutesToCloud(context)
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
    
    /**
     * Syncs current reward minutes to cloud user_data table
     * This ensures that if we sync from cloud, we get the accurate remaining reward time
     */
    private fun syncRewardMinutesToCloud(context: Context) {
        Log.d(TAG, "Syncing $currentRewardMinutes reward minutes to cloud...")
        // Use CloudSyncManager to sync asynchronously
        CloudSyncManager.syncRewardMinutesToCloudAsync(context, currentRewardMinutes)
    }

    /**
     * Loads reward minutes from local storage.
     * Resets to 0 if it's a new day.
     * Returns true if reward minutes were loaded (not reset).
     */
    fun loadRewardMinutes(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Check if we need to reset for a new day
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val lastRewardDate = prefs.getLong(KEY_LAST_REWARD_DATE, 0L)
        
        // Reset to 0 if it's a new day (including first time load when lastRewardDate is 0L)
        if (lastRewardDate != today) {
            // It's a new day - reset reward minutes to 0
            Log.d(TAG, "New day detected (last: $lastRewardDate, today: $today). Resetting reward minutes to 0.")
            currentRewardMinutes = 0
            saveRewardMinutes(context) // This will also update the date
            return false
        } else {
            // Same day - load the saved minutes
            currentRewardMinutes = prefs.getInt(KEY_CURRENT_REWARD_MINUTES, 0)
            Log.d(TAG, "Loaded reward minutes from SharedPreferences: $currentRewardMinutes (same day)")
            return true
        }
    }
    
    /**
     * Adds reward minutes to the current total
     */
    fun addRewardMinutes(context: Context, minutes: Int) {
        currentRewardMinutes += minutes
        saveRewardMinutes(context)
        Log.d(TAG, "Added $minutes reward minutes. New total: $currentRewardMinutes")
    }
    
    /**
     * Checks if a transaction ID has already been processed.
     * This prevents double-counting when both Intent and Broadcast are received.
     */
    fun isTransactionProcessed(context: Context, transactionId: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val processedIds = prefs.getStringSet(KEY_PROCESSED_TRANSACTION_IDS, mutableSetOf()) ?: mutableSetOf()
        return processedIds.contains(transactionId.toString())
    }

    /**
     * Marks a transaction ID as processed to prevent double-counting.
     * Also cleans up old transaction IDs (older than 24 hours) to prevent unbounded growth.
     */
    fun markTransactionProcessed(context: Context, transactionId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val processedIds = mutableSetOf<String>()
        processedIds.addAll(prefs.getStringSet(KEY_PROCESSED_TRANSACTION_IDS, mutableSetOf()) ?: mutableSetOf())
        
        // Add the new transaction ID
        processedIds.add(transactionId.toString())
        
        // Clean up old transaction IDs (older than 24 hours)
        val currentTime = System.currentTimeMillis()
        val oneDayInMillis = 24 * 60 * 60 * 1000L
        val cleanedIds = processedIds.filter { id ->
            val idTime = id.toLongOrNull() ?: 0L
            currentTime - idTime < oneDayInMillis
        }.toSet()
        
        prefs.edit()
            .putStringSet(KEY_PROCESSED_TRANSACTION_IDS, cleanedIds)
            .apply()
        
        Log.d(TAG, "Marked transaction ID $transactionId as processed. Total tracked: ${cleanedIds.size}")
    }
    
    /**
     * Gets the timestamp for banked_mins (for cloud sync comparison)
     */
    fun getBankedMinsTimestamp(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BANKED_MINS_TIMESTAMP, null)
    }
    
    /**
     * Sets the timestamp for banked_mins (used when applying cloud value)
     */
    fun setBankedMinsTimestamp(context: Context, timestamp: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BANKED_MINS_TIMESTAMP, timestamp).apply()
        Log.d(TAG, "Updated banked_mins timestamp: $timestamp")
    }
}
