package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages reward minutes for ONLINE-ONLY mode.
 * No local persistence of reward minutes - cloud (Supabase user_data.banked_mins) is the single source of truth.
 * In-memory cache only; callers must fetch from cloud on load and push to cloud on change.
 */
object RewardStorage {
    private const val TAG = "RewardStorage"
    private const val PREFS_NAME = "reward_prefs"
    private const val KEY_PROCESSED_TRANSACTION_IDS = "processed_transaction_ids"
    
    // In-memory cache only (no persistence). Populated from cloud fetch; pushed to cloud on change.
    @Volatile
    private var currentRewardMinutes: Int = 0

    /** Reward time expiry in America/Toronto (yyyy-MM-dd HH:mm:ss.SSS). Null = no expiry (unlimited by time). */
    @Volatile
    private var rewardTimeExpiry: String? = null

    /** Minutes left in active session from last [af_get_reward_time_state] poll (server clock). Null = not yet fetched. */
    @Volatile
    private var serverSessionMinsRemaining: Int? = null

    fun getRewardTimeExpiry(): String? = rewardTimeExpiry
    fun setRewardTimeExpiry(expiry: String?) {
        rewardTimeExpiry = expiry
    }

    fun getServerSessionMinsRemaining(): Int? = serverSessionMinsRemaining

    fun setServerSessionMinsRemaining(minutes: Int?) {
        serverSessionMinsRemaining = minutes
    }
    
    /**
     * Gets the current reward minutes from memory (populated by cloud fetch).
     */
    fun getCurrentRewardMinutes(): Int {
        return currentRewardMinutes
    }
    
    /**
     * Gets the current reward minutes. In online-only mode this is the same as getCurrentRewardMinutes()
     * (no separate storage read).
     */
    fun getCurrentRewardMinutesFromStorage(context: Context): Int {
        return currentRewardMinutes
    }
    
    /**
     * Sets the current reward minutes in memory (does not persist).
     * Caller must push to cloud via saveRewardMinutesToCloud() when value changes.
     */
    fun setCurrentRewardMinutes(minutes: Int) {
        currentRewardMinutes = minutes
    }

    /**
     * Resets reward minutes in memory to 0. Caller must push reset to cloud (e.g. via daily reset flow).
     */
    fun resetRewardMinutesLocal(context: Context) {
        currentRewardMinutes = 0
        rewardTimeExpiry = null
        serverSessionMinsRemaining = null
        Log.d(TAG, "Reset reward minutes in memory to 0 (online-only: no local persistence)")
    }
    
    /**
     * Legacy no-op in dumb-UI mode.
     * Reward state is authoritative in DB via `af_reward_time_*` RPCs (use/pause/expire/add), then read back.
     */
    fun saveRewardMinutes(context: Context, updateLastUpdated: Boolean = true, rewardTimeExpiryOptional: String? = null) {
        Log.d(TAG, "saveRewardMinutes: no-op (reward state managed by af_reward_time_* RPCs)")
    }
    
    /**
     * No-op in online-only mode. Reward minutes are loaded by fetching user_data from cloud
     * (e.g. DbUserDataRefresh / downloadUserDataFromCloud). Returns false so callers know
     * no local load occurred.
     */
    fun loadRewardMinutes(context: Context): Boolean {
        Log.d(TAG, "loadRewardMinutes: no-op in online-only mode (use cloud fetch)")
        return false
    }
    
    /**
     * Adds reward minutes to the current total, sets expiry to now_est + new total, and pushes to cloud.
     */
    fun addRewardMinutes(context: Context, minutes: Int) {
        currentRewardMinutes += minutes
        rewardTimeExpiry = null
        saveRewardMinutes(context)
        Log.d(TAG, "Added $minutes reward minutes. New total: $currentRewardMinutes (pushed to cloud)")
    }
    
    /**
     * Checks if a transaction ID has already been processed (dedup for Intent + Broadcast).
     * Stored in prefs as operational state only, not synced content.
     */
    fun isTransactionProcessed(context: Context, transactionId: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val processedIds = prefs.getStringSet(KEY_PROCESSED_TRANSACTION_IDS, mutableSetOf()) ?: mutableSetOf()
        return processedIds.contains(transactionId.toString())
    }

    /**
     * Marks a transaction ID as processed. Cleans up IDs older than 24 hours.
     */
    fun markTransactionProcessed(context: Context, transactionId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val processedIds = mutableSetOf<String>()
        processedIds.addAll(prefs.getStringSet(KEY_PROCESSED_TRANSACTION_IDS, mutableSetOf()) ?: mutableSetOf())
        processedIds.add(transactionId.toString())
        val currentTime = System.currentTimeMillis()
        val oneDayInMillis = 24 * 60 * 60 * 1000L
        val cleanedIds = processedIds.filter { id ->
            val idTime = id.toLongOrNull() ?: 0L
            currentTime - idTime < oneDayInMillis
        }.toSet()
        prefs.edit().putStringSet(KEY_PROCESSED_TRANSACTION_IDS, cleanedIds).apply()
        Log.d(TAG, "Marked transaction ID $transactionId as processed. Total tracked: ${cleanedIds.size}")
    }
}
