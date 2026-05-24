package com.talq2me.baerenlock

import android.content.Context
import android.util.Log

/**
 * Pure blocking policy used by [GuardianForegroundService] for enforcement decisions.
 */
object AppBlockPolicy {
    private const val TAG = "AppBlockPolicy"

    fun shouldBlock(
        context: Context,
        pkgName: String,
        ownPackageName: String,
        chromeJeLisActive: Boolean,
        chromeFromBaerenEd: Boolean
    ): Boolean {
        if (pkgName == ownPackageName) {
            return false
        }

        val isRewardApp = RewardManager.rewardEligibleApps.contains(pkgName)
        if (isRewardApp) {
            if (!RewardManager.isRewardSessionActive()) {
                Log.d(TAG, "Blocking reward app $pkgName: no active reward session")
                return true
            }
            val effectiveRewardMinutes = RewardManager.getEffectiveRewardMinutes(context)
            if (effectiveRewardMinutes <= 0) {
                Log.d(
                    TAG,
                    "Blocking reward app $pkgName: effectiveRewardMinutes=$effectiveRewardMinutes"
                )
                return true
            }
            // Active reward session: allow even if the app is also on the blacklist (e.g. Chrome).
            return false
        }

        val blacklist = BlacklistManager.getBlacklist(context)
        if (blacklist.contains(pkgName)) {
            if (pkgName == "com.android.chrome" ||
                pkgName == "com.chrome.browser" ||
                pkgName.contains("chrome", ignoreCase = true)
            ) {
                if (chromeJeLisActive || chromeFromBaerenEd) {
                    return false
                }
            }
            return true
        }

        return false
    }
}
