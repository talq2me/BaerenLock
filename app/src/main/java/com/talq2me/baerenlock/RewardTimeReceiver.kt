package com.talq2me.baerenlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * BroadcastReceiver that receives reward time from BaerenEd.
 * This provides a fallback mechanism when Intent delivery via HOME action fails.
 */
class RewardTimeReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "RewardTimeReceiver"
        const val ACTION_ADD_REWARD_TIME = "com.talq2me.baerenlock.ACTION_ADD_REWARD_TIME"
        const val EXTRA_REWARD_MINUTES = "reward_minutes"
        const val ACTION_REWARD_TIME_UPDATED = "com.talq2me.baerenlock.ACTION_REWARD_TIME_UPDATED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called with action: ${intent.action}")
        if (intent.action == ACTION_ADD_REWARD_TIME) {
            val rewardMinutes = intent.getIntExtra(EXTRA_REWARD_MINUTES, 0)
            val transactionId = intent.getLongExtra("reward_transaction_id", 0L)
            
            Log.d(TAG, "Received broadcast: rewardMinutes=$rewardMinutes, transactionId=$transactionId")
            
            if (rewardMinutes > 0 && transactionId > 0) {
                // Check if we've already processed this transaction (e.g., via Intent)
                if (RewardManager.isTransactionProcessed(context, transactionId)) {
                    Log.d(TAG, "Transaction ID $transactionId already processed via Intent, skipping broadcast to prevent double-counting")
                    return
                }
                
                Log.d(TAG, "Processing $rewardMinutes reward minutes via broadcast from BaerenEd (transaction ID: $transactionId)")
                
                // Load current reward minutes
                RewardManager.loadRewardMinutes(context)
                val previousMinutes = RewardManager.currentRewardMinutes
                
                // Add the new reward minutes
                RewardManager.currentRewardMinutes += rewardMinutes
                RewardManager.saveRewardMinutes(context)
                
                // Update start minutes for usage-based tracking
                RewardManager.updateStartMinutesForNewRewardTime(context, rewardMinutes)
                
                // Mark this transaction as processed
                RewardManager.markTransactionProcessed(context, transactionId)
                
                // Start timer if not already running
                if (RewardManager.currentRewardMinutes > 0) {
                    RewardManager.startRewardTimer(context)
                }
                
                Log.d(TAG, "Successfully added $rewardMinutes minutes. Previous: $previousMinutes, New total: ${RewardManager.currentRewardMinutes} minutes")
                
                // Send local broadcast to update UI
                val localIntent = Intent(ACTION_REWARD_TIME_UPDATED)
                LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent)
            } else if (rewardMinutes > 0 && transactionId == 0L) {
                // Legacy support: if no transaction ID, process it but log a warning
                Log.w(TAG, "Received reward minutes without transaction ID (legacy format). Processing anyway.")
                RewardManager.loadRewardMinutes(context)
                val previousMinutes = RewardManager.currentRewardMinutes
                RewardManager.currentRewardMinutes += rewardMinutes
                RewardManager.saveRewardMinutes(context)
                
                // Update start minutes for usage-based tracking
                RewardManager.updateStartMinutesForNewRewardTime(context, rewardMinutes)
                
                if (RewardManager.currentRewardMinutes > 0) {
                    RewardManager.startRewardTimer(context)
                }
                
                Log.d(TAG, "Legacy format: Added $rewardMinutes minutes. Previous: $previousMinutes, New total: ${RewardManager.currentRewardMinutes} minutes")
                
                val localIntent = Intent(ACTION_REWARD_TIME_UPDATED)
                LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent)
            } else {
                Log.w(TAG, "Received broadcast with invalid reward minutes: $rewardMinutes or transaction ID: $transactionId")
            }
        } else {
            Log.w(TAG, "Received broadcast with unexpected action: ${intent.action}")
        }
    }
}

