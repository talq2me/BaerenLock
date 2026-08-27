package com.talq2me.baerenlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives PackageInstaller session results from [AppUpdateManager].
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: "unknown"
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Install success for $targetPackage")
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                Log.w(TAG, "Install requires user action for $targetPackage — device owner silent install may have failed")
            else ->
                Log.e(TAG, "Install failed for $targetPackage: status=$status message=$message")
        }
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }
}
