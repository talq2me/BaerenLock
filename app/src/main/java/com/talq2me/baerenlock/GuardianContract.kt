package com.talq2me.baerenlock

/**
 * Intent actions and broadcast constants for [GuardianForegroundService].
 */
object GuardianContract {
    const val ACTION_ENSURE_RUNNING = "com.talq2me.baerenlock.action.GUARDIAN_ENSURE_RUNNING"
    const val ACTION_USE_REWARD = "com.talq2me.baerenlock.action.GUARDIAN_USE_REWARD"
    const val ACTION_PAUSE_REWARD = "com.talq2me.baerenlock.action.GUARDIAN_PAUSE_REWARD"
    const val ACTION_ACCESSIBILITY_STALE = "com.talq2me.baerenlock.ACTION_ACCESSIBILITY_STALE"

    enum class ForegroundSource {
        ACCESSIBILITY,
        USAGE_STATS,
        ACTIVITY_MANAGER
    }
}
