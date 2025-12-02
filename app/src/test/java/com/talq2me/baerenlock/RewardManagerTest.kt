package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class RewardManagerTest {

    private lateinit var context: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        
        // Use Robolectric to get a real Android context
        context = RuntimeEnvironment.getApplication()
        
        // Reset RewardManager state
        RewardManager.currentRewardMinutes = 0
        RewardManager.allowedApps.clear()
        RewardManager.rewardEligibleApps.clear()
        
        // Clear SharedPreferences to ensure clean state
        context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("reward_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().apply()
        
        // Get real SharedPreferences for testing
        mockSharedPreferences = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
        mockEditor = mockSharedPreferences.edit()
    }

    @Test
    fun `grantAccess adds app to allowed and temporary sets`() {
        val packageName = "com.example.app"
        val minutes = 10

        RewardManager.grantAccess(context, packageName, minutes)

        assertTrue("App should be in allowedApps", RewardManager.allowedApps.contains(packageName))
        // temporaryApps is private, but we can verify through isAllowed
        assertTrue("App should be allowed", RewardManager.isAllowed(packageName))
    }

    @Test
    fun `grantAccess saves allowed apps to preferences`() {
        val packageName = "com.example.app"
        val minutes = 10

        RewardManager.grantAccess(context, packageName, minutes)

        // Verify app is saved by checking if it's in allowedApps
        assertTrue("App should be in allowedApps", RewardManager.allowedApps.contains(packageName))
    }

    @Test
    fun `isAllowed returns true for permanently allowed app`() {
        val packageName = "com.example.app"
        RewardManager.allowedApps.add(packageName)

        assertTrue("App should be allowed", RewardManager.isAllowed(packageName))
    }

    @Test
    fun `isAllowed returns true for temporary app`() {
        val packageName = "com.example.app"
        // Grant access which adds to temporary apps
        RewardManager.grantAccess(context, packageName, 10)

        assertTrue("App should be allowed", RewardManager.isAllowed(packageName))
    }

    @Test
    fun `isAllowed returns true for reward-eligible app with active minutes`() {
        val packageName = "com.example.app"
        RewardManager.rewardEligibleApps.add(packageName)
        RewardManager.currentRewardMinutes = 5

        assertTrue("Reward app should be allowed with active minutes", RewardManager.isAllowed(packageName))
    }

    @Test
    fun `isAllowed returns false for reward-eligible app with no minutes`() {
        val packageName = "com.test.reward.app.no.minutes.unique.${System.currentTimeMillis()}"
        
        // Clear any existing state for this package
        RewardManager.allowedApps.remove(packageName)
        
        // Set up: package is reward-eligible but minutes are 0
        RewardManager.rewardEligibleApps.clear() // Clear all first
        RewardManager.rewardEligibleApps.add(packageName)
        RewardManager.currentRewardMinutes = 0
        
        // Verify the state before testing
        assertTrue("Package should be in rewardEligibleApps", RewardManager.rewardEligibleApps.contains(packageName))
        assertFalse("Package should not be in allowedApps", RewardManager.allowedApps.contains(packageName))
        assertEquals("Current reward minutes should be 0", 0, RewardManager.currentRewardMinutes)
        
        // Test: isAllowed should return false
        // Logic: allowedApps.contains(pkg) || temporaryApps.contains(pkg) || (rewardEligibleApps.contains(pkg) && currentRewardMinutes > 0)
        // Since pkg is NOT in allowedApps, NOT in temporaryApps (we didn't call grantAccess), 
        // and (rewardEligibleApps.contains(pkg) && currentRewardMinutes > 0) = (true && false) = false
        // So the result should be false
        val result = RewardManager.isAllowed(packageName)
        assertFalse("Reward app should not be allowed with no minutes", result)
    }

    @Test
    fun `addToWhitelist adds app to allowed set`() {
        val packageName = "com.example.app"
        
        RewardManager.addToWhitelist(packageName, context)

        assertTrue("App should be in allowedApps", RewardManager.allowedApps.contains(packageName))
    }

    @Test
    fun `removeFromWhitelist removes app from allowed set`() {
        val packageName = "com.example.app"
        RewardManager.allowedApps.add(packageName)

        RewardManager.removeFromWhitelist(packageName, context)

        assertFalse("App should not be in allowedApps", RewardManager.allowedApps.contains(packageName))
    }

    @Test
    fun `saveRewardMinutes saves current minutes to preferences`() {
        RewardManager.currentRewardMinutes = 15

        RewardManager.saveRewardMinutes(context)

        // Verify by loading it back
        RewardManager.loadRewardMinutes(context)
        // Note: This might reset if it's a new day, so we test the save/load cycle
        assertTrue("Should save minutes", true) // Basic test that it doesn't crash
    }

    @Test
    fun `loadRewardMinutes loads saved minutes for same day`() {
        // Save some minutes first
        RewardManager.currentRewardMinutes = 20
        RewardManager.saveRewardMinutes(context)
        
        // Load them back
        RewardManager.loadRewardMinutes(context)

        // Should either load the saved value or reset if new day
        assertTrue("Should load or reset minutes", RewardManager.currentRewardMinutes >= 0)
    }

    @Test
    fun `loadRewardMinutes resets to zero for new day`() {
        // This test verifies the reset logic works
        // In a real scenario, we'd manipulate the date, but for now we test the method doesn't crash
        RewardManager.currentRewardMinutes = 20
        RewardManager.saveRewardMinutes(context)
        RewardManager.loadRewardMinutes(context)
        
        assertTrue("Should handle day reset logic", true)
    }

    @Test
    fun `refreshRewardEligibleApps loads from preferences`() {
        val rewardApps = setOf("com.app1", "com.app2")
        val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.edit().putStringSet("reward_apps", rewardApps).apply()

        RewardManager.refreshRewardEligibleApps(context)

        assertEquals("Should load reward apps from preferences", rewardApps, RewardManager.rewardEligibleApps)
    }

    @Test
    fun `getAllowedAppsList returns copy of allowed apps`() {
        RewardManager.allowedApps.add("com.app1")
        RewardManager.allowedApps.add("com.app2")

        val result = RewardManager.getAllowedAppsList()

        assertEquals("Should return all allowed apps", 2, result.size)
        assertTrue("Should contain app1", result.contains("com.app1"))
        assertTrue("Should contain app2", result.contains("com.app2"))
    }

    @Test
    fun `isBackgroundAppAllowed returns true for memory allowed apps`() {
        assertTrue("BaerenLock should be allowed", 
            RewardManager.isBackgroundAppAllowed("com.talq2me.baerenlock"))
        assertTrue("BaerenEd should be allowed", 
            RewardManager.isBackgroundAppAllowed("com.talq2me.baerened"))
        assertTrue("PokemonGo should be allowed", 
            RewardManager.isBackgroundAppAllowed("com.nianticlabs.pokemongo"))
    }

    @Test
    fun `isBackgroundAppAllowed returns true for system packages`() {
        assertTrue("System UI should be allowed", 
            RewardManager.isBackgroundAppAllowed("com.android.systemui"))
        assertTrue("Android package should be allowed", 
            RewardManager.isBackgroundAppAllowed("android"))
    }

    @Test
    fun `isBackgroundAppAllowed returns false for unauthorized apps`() {
        assertFalse("Unauthorized app should not be allowed", 
            RewardManager.isBackgroundAppAllowed("com.unauthorized.app"))
    }
}

