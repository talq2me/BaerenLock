package com.talq2me.baerenlock

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.hamcrest.Matchers.containsString

/**
 * Integration tests for Test Cases 8-9:
 * - Set a whitelist app and verify it shows in the launcher and is saved to user_data table
 * - Remove a whitelist app and verify it is removed from the launcher and user_data table
 * 
 * These tests automate:
 * - Opening whitelist settings
 * - Adding/removing apps from whitelist
 * - Verifying changes in database
 */
@RunWith(AndroidJUnit4::class)
class WhitelistAppDatabaseIntegrationTest {

    private lateinit var context: android.content.Context
    private lateinit var scenario: ActivityScenario<*>
    private var testAppPackage: String? = null
    private val originalWhitelist: MutableSet<String> = mutableSetOf()

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Skip test if Supabase is not configured
        if (!SupabaseTestHelper.isConfigured(context)) {
            org.junit.Assume.assumeTrue("Supabase not configured - skipping test", false)
        }
        
        // Get current profile (default to "AM" if not set)
        val profile = ProfileManager.readProfile(context) ?: "AM"
        
        // Save original whitelist
        originalWhitelist.addAll(SupabaseTestHelper.getWhitelistedApps(context, profile))
        
        // Find a test app that's not currently whitelisted
        // We'll use a common system app that should be available
        val testApps = listOf(
            "com.android.calculator2",  // Calculator
            "com.android.calendar",     // Calendar
            "com.android.contacts"      // Contacts
        )
        
        testAppPackage = testApps.firstOrNull { 
            !RewardManager.allowedApps.contains(it) 
        }
        
        if (testAppPackage == null) {
            // If all test apps are already whitelisted, we'll use the first one and remove it first
            testAppPackage = testApps.first()
            RewardManager.removeFromWhitelist(testAppPackage!!, context)
            Thread.sleep(1000) // Wait for sync
        }
    }

    @After
    fun tearDown() {
        // Restore original whitelist state
        val profile = ProfileManager.readProfile(context) ?: "AM"
        val currentWhitelist = SupabaseTestHelper.getWhitelistedApps(context, profile).toSet()
        
        // Remove test app if it was added
        testAppPackage?.let { pkg ->
            if (currentWhitelist.contains(pkg) && !originalWhitelist.contains(pkg)) {
                RewardManager.removeFromWhitelist(pkg, context)
            } else if (!currentWhitelist.contains(pkg) && originalWhitelist.contains(pkg)) {
                RewardManager.addToWhitelist(pkg, context)
            }
        }
        
        // Wait for sync
        Thread.sleep(2000)
        
        scenario.close()
    }

    @Test
    fun testAddWhitelistAppSavedToDatabase() {
        val profile = ProfileManager.readProfile(context) ?: "AM"
        val pkg = testAppPackage ?: return
        
        // Launch WhitelistSettingsActivity
        scenario = ActivityScenario.launch(WhitelistSettingsActivity::class.java)
        
        // Wait for activity to load
        Thread.sleep(2000)
        
        // Find the checkbox for our test app by looking for text containing the package name
        // The checkbox text format is "AppName (package.name)"
        onView(withText(containsString(pkg)))
            .check(matches(isDisplayed()))
            .perform(click()) // This should toggle it on if it was off
        
        // Wait for the save operation and database sync
        // RewardManager.addToWhitelist triggers async cloud sync
        val syncSuccess = SupabaseTestHelper.waitForDatabaseSync(
            context,
            maxWaitSeconds = 10,
            checkIntervalMs = 500
        ) {
            val dbWhitelist = SupabaseTestHelper.getWhitelistedApps(context, profile)
            dbWhitelist.contains(pkg)
        }
        
        // Verify app was saved to database
        assertTrue("Whitelist app should be synced to database within 10 seconds", syncSuccess)
        
        val dbWhitelist = SupabaseTestHelper.getWhitelistedApps(context, profile)
        assertTrue("App should be in database whitelist", dbWhitelist.contains(pkg))
    }

    @Test
    fun testRemoveWhitelistAppRemovedFromDatabase() {
        val profile = ProfileManager.readProfile(context) ?: "AM"
        val pkg = testAppPackage ?: return
        
        // First, ensure the app is whitelisted
        RewardManager.addToWhitelist(pkg, context)
        Thread.sleep(2000) // Wait for sync
        
        // Launch WhitelistSettingsActivity
        scenario = ActivityScenario.launch(WhitelistSettingsActivity::class.java)
        
        // Wait for activity to load
        Thread.sleep(2000)
        
        // Find the checkbox for our test app and uncheck it
        onView(withText(containsString(pkg)))
            .check(matches(isDisplayed()))
            .perform(click()) // This should toggle it off
        
        // Wait for the save operation and database sync
        val syncSuccess = SupabaseTestHelper.waitForDatabaseSync(
            context,
            maxWaitSeconds = 10,
            checkIntervalMs = 500
        ) {
            val dbWhitelist = SupabaseTestHelper.getWhitelistedApps(context, profile)
            !dbWhitelist.contains(pkg)
        }
        
        // Verify app was removed from database
        assertTrue("Whitelist app removal should be synced to database within 10 seconds", syncSuccess)
        
        val dbWhitelist = SupabaseTestHelper.getWhitelistedApps(context, profile)
        assertFalse("App should not be in database whitelist", dbWhitelist.contains(pkg))
    }
}
