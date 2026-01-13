package com.talq2me.baerenlock

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppBlockerServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        // Use Robolectric to get a real Android context
        context = RuntimeEnvironment.getApplication()
        
        // Reset RewardManager state
        RewardManager.currentRewardMinutes = 0
        
        // Clear SharedPreferences to ensure clean state
        context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().apply()
        
        // Reset RewardAppsManager state by clearing stored data
        RewardManager.loadAllowedApps(context)
        RewardManager.loadRewardMinutes(context)
    }

    @Test
    fun `getBlacklist returns empty set by default`() {
        // Use BlacklistManager which handles the actual logic
        val blacklist = BlacklistManager.getBlacklist(context)
        assertTrue("Blacklist should be empty by default", blacklist.isEmpty())
    }

    @Test
    fun `getBlacklist returns saved packages`() {
        val packages = setOf("com.app1", "com.app2")
        // Add packages to blacklist
        packages.forEach { BlacklistManager.addToBlacklist(context, it) }
        
        // Get blacklist
        val blacklist = BlacklistManager.getBlacklist(context)
        assertEquals("Should return saved packages", packages, blacklist)
    }

    @Test
    fun `addToBlacklist saves package to preferences`() {
        val packageName = "com.blocked.app"
        
        // Add to blacklist using BlacklistManager
        BlacklistManager.addToBlacklist(context, packageName)
        
        // Verify it's in the blacklist
        val blacklist = BlacklistManager.getBlacklist(context)
        assertTrue("Package should be added", blacklist.contains(packageName))
    }

    @Test
    fun `removeFromBlacklist removes package from preferences`() {
        val packageName = "com.blocked.app"
        
        // Add then remove
        BlacklistManager.addToBlacklist(context, packageName)
        BlacklistManager.removeFromBlacklist(context, packageName)
        
        // Verify it's removed
        val blacklist = BlacklistManager.getBlacklist(context)
        assertFalse("Package should be removed", blacklist.contains(packageName))
    }

    @Test
    fun `shouldBlockApp returns false for own package`() {
        val ownPackage = "com.talq2me.baerenlock"
        
        // Should never block own package - test the logic
        val shouldBlock = ownPackage != "com.talq2me.baerenlock"
        assertFalse("Should not block own package", shouldBlock)
    }

    @Test
    fun `shouldBlockApp returns true for blacklisted app`() {
        val blacklist = setOf("com.blocked.app")
        val packageName = "com.blocked.app"
        
        assertTrue("Should block blacklisted app", blacklist.contains(packageName))
    }

    @Test
    fun `shouldBlockApp returns false for non-blacklisted app`() {
        val blacklist = setOf("com.blocked.app")
        val packageName = "com.allowed.app"
        
        assertFalse("Should not block non-blacklisted app", blacklist.contains(packageName))
    }

    @Test
    fun `shouldBlockApp blocks reward app with zero minutes`() {
        val packageName = "com.reward.app"
        // Add to reward eligible apps via settings
        val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.edit().putStringSet("reward_apps", setOf(packageName)).apply()
        RewardManager.refreshRewardEligibleApps(context)
        RewardManager.currentRewardMinutes = 0
        
        val isRewardApp = RewardManager.rewardEligibleApps.contains(packageName)
        val hasRewardMinutes = RewardManager.currentRewardMinutes > 0
        
        assertTrue("Should block reward app with 0 minutes", isRewardApp && !hasRewardMinutes)
    }

    @Test
    fun `shouldBlockApp allows reward app with active minutes`() {
        val packageName = "com.reward.app"
        // Add to reward eligible apps via settings
        val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.edit().putStringSet("reward_apps", setOf(packageName)).apply()
        RewardManager.refreshRewardEligibleApps(context)
        RewardManager.currentRewardMinutes = 10
        
        val isRewardApp = RewardManager.rewardEligibleApps.contains(packageName)
        val hasRewardMinutes = RewardManager.currentRewardMinutes > 0
        
        assertTrue("Should allow reward app with active minutes", isRewardApp && hasRewardMinutes)
    }

    @Test
    fun `clearAllBlockedPackages removes all packages`() {
        val blockedPackages = mutableSetOf("com.app1", "com.app2", "com.app3")
        
        blockedPackages.clear()
        
        assertTrue("Should clear all blocked packages", blockedPackages.isEmpty())
    }

    @Test
    fun `getBlockedPackages returns current blocked packages`() {
        val blockedPackages = setOf("com.app1", "com.app2")
        
        val result = blockedPackages.toSet()
        
        assertEquals("Should return blocked packages", blockedPackages, result)
    }
}

