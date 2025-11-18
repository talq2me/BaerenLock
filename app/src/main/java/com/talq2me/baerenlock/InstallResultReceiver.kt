package com.talq2me.baerenlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown"

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Update installed successfully.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Update install failed: $message", Toast.LENGTH_LONG).show()
        }
    }
}

