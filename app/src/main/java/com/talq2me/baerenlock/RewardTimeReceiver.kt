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
        if (intent.action == ACTION_ADD_REWARD_TIME) {
            val rewardMinutes = intent.getIntExtra(EXTRA_REWARD_MINUTES, 0)
            if (rewardMinutes > 0) {
                Log.d(TAG, "Received $rewardMinutes reward minutes via broadcast from BaerenEd")
                
                // Load current reward minutes
                RewardManager.loadRewardMinutes(context)
                
                // Add the new reward minutes
                RewardManager.currentRewardMinutes += rewardMinutes
                RewardManager.saveRewardMinutes(context)
                
                // Start timer if not already running
                if (RewardManager.currentRewardMinutes > 0) {
                    RewardManager.startRewardTimer(context)
                }
                
                Log.d(TAG, "Successfully added $rewardMinutes minutes. Total: ${RewardManager.currentRewardMinutes} minutes")
                
                // Send local broadcast to update UI
                val localIntent = Intent(ACTION_REWARD_TIME_UPDATED)
                LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent)
            } else {
                Log.w(TAG, "Received broadcast with invalid reward minutes: $rewardMinutes")
            }
        }
    }
}

