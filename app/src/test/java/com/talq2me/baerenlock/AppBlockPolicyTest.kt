package com.talq2me.baerenlock

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppBlockPolicyTest {

    private lateinit var context: Context
    private val ownPackage = "com.talq2me.baerenlock"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        RewardManager.currentRewardMinutes = 0
        RewardStorage.setRewardTimeExpiry(null)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().apply()
        RewardManager.refreshRewardEligibleApps(context)
    }

    @Test
    fun shouldNotBlockOwnPackage() {
        assertFalse(
            AppBlockPolicy.shouldBlock(context, ownPackage, ownPackage, false, false)
        )
    }

    @Test
    fun shouldBlockBlacklistedApp() {
        BlacklistManager.addToBlacklist(context, "com.blocked.app")
        assertTrue(
            AppBlockPolicy.shouldBlock(context, "com.blocked.app", ownPackage, false, false)
        )
    }

    @Test
    fun shouldAllowChromeOnBlacklistWhenJeLisActive() {
        BlacklistManager.addToBlacklist(context, "com.android.chrome")
        assertFalse(
            AppBlockPolicy.shouldBlock(
                context,
                "com.android.chrome",
                ownPackage,
                chromeJeLisActive = true,
                chromeFromBaerenEd = false
            )
        )
    }

    @Test
    fun shouldAllowRewardAppOnBlacklistDuringActiveSession() {
        val pkg = "com.android.chrome"
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("reward_apps", setOf(pkg))
            .apply()
        BlacklistManager.addToBlacklist(context, pkg)
        RewardManager.refreshRewardEligibleApps(context)
        RewardStorage.setRewardTimeExpiry("2099-01-01 12:00:00.000")
        RewardStorage.setServerSessionMinsRemaining(10)
        assertFalse(
            AppBlockPolicy.shouldBlock(context, pkg, ownPackage, false, false)
        )
    }

    @Test
    fun shouldBlockRewardAppWithNoActiveSession() {
        val pkg = "com.reward.app"
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("reward_apps", setOf(pkg))
            .apply()
        RewardManager.refreshRewardEligibleApps(context)
        RewardManager.currentRewardMinutes = 0
        RewardStorage.setRewardTimeExpiry(null)
        assertTrue(
            AppBlockPolicy.shouldBlock(context, pkg, ownPackage, false, false)
        )
    }
}
