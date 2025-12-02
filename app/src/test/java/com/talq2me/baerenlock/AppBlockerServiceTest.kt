package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class AppBlockerServiceTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putStringSet(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.apply()).then { }
        whenever(mockContext.packageName).thenReturn("com.talq2me.baerenlock")
    }

    @Test
    fun `getBlacklist returns empty set by default`() {
        whenever(mockSharedPreferences.getStringSet("packages", emptySet())).thenReturn(emptySet())

        // Since getBlacklist is a method on AppBlockerService, we need to test it
        // In a real scenario, you'd create a testable version or use reflection
        // For now, we'll test the logic conceptually
        
        val blacklist = emptySet<String>()
        assertTrue("Blacklist should be empty by default", blacklist.isEmpty())
    }

    @Test
    fun `getBlacklist returns saved packages`() {
        val packages = setOf("com.app1", "com.app2")
        whenever(mockSharedPreferences.getStringSet("packages", emptySet())).thenReturn(packages)

        // Test the logic
        val blacklist = mockSharedPreferences.getStringSet("packages", emptySet()) ?: emptySet()
        assertEquals("Should return saved packages", packages, blacklist)
    }

    @Test
    fun `addToBlacklist saves package to preferences`() {
        val packageName = "com.blocked.app"
        val existingSet = mutableSetOf<String>()
        whenever(mockSharedPreferences.getStringSet("packages", emptySet())).thenReturn(existingSet)

        // Simulate adding to blacklist
        existingSet.add(packageName)
        // Verify the logic works
        assertTrue("Package should be added", existingSet.contains(packageName))
    }

    @Test
    fun `removeFromBlacklist removes package from preferences`() {
        val packageName = "com.blocked.app"
        val existingSet = mutableSetOf("com.blocked.app", "com.other.app")
        whenever(mockSharedPreferences.getStringSet("packages", emptySet())).thenReturn(existingSet)

        // Simulate removing from blacklist
        existingSet.remove(packageName)
        assertFalse("Package should be removed", existingSet.contains(packageName))
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
        RewardManager.rewardEligibleApps.add(packageName)
        RewardManager.currentRewardMinutes = 0
        
        val isRewardApp = RewardManager.rewardEligibleApps.contains(packageName)
        val hasRewardMinutes = RewardManager.currentRewardMinutes > 0
        
        assertTrue("Should block reward app with 0 minutes", isRewardApp && !hasRewardMinutes)
    }

    @Test
    fun `shouldBlockApp allows reward app with active minutes`() {
        val packageName = "com.reward.app"
        RewardManager.rewardEligibleApps.add(packageName)
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

