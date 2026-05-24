package com.talq2me.baerenlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.d(TAG, "Boot/package replaced - starting GuardianForegroundService")
        GuardianForegroundService.ensureRunning(context.applicationContext)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
