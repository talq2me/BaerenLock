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
 * Integration test for Test Case 10:
 * Set a blacklisted app and verify it is saved to the user_data table.
 * 
 * This test automates:
 * - Opening blacklist settings
 * - Adding an app to blacklist
 * - Verifying the change is saved to Supabase user_data table
 */
@RunWith(AndroidJUnit4::class)
class BlacklistAppDatabaseIntegrationTest {

    private lateinit var context: android.content.Context
    private lateinit var scenario: ActivityScenario<*>
    private var testAppPackage: String? = null
    private val originalBlacklist: MutableSet<String> = mutableSetOf()

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Skip test if Supabase is not configured
        if (!SupabaseTestHelper.isConfigured(context)) {
            org.junit.Assume.assumeTrue("Supabase not configured - skipping test", false)
        }
        
        // Get current profile (default to "AM" if not set)
        val profile = ProfileManager.readProfile(context) ?: "AM"
        
        // Save original blacklist
        originalBlacklist.addAll(SupabaseTestHelper.getBlacklistedApps(context, profile))
        
        // Find a test app that's not currently blacklisted
        val testApps = listOf(
            "com.android.calculator2",  // Calculator
            "com.android.calendar",     // Calendar
            "com.android.contacts"      // Contacts
        )
        
        testAppPackage = testApps.firstOrNull { 
            !BlacklistManager.getBlacklist(context).contains(it) 
        }
        
        if (testAppPackage == null) {
            // If all test apps are already blacklisted, use the first one
            testAppPackage = testApps.first()
        }
    }

    @After
    fun tearDown() {
        // Restore original blacklist state
        val profile = ProfileManager.readProfile(context) ?: "AM"
        val currentBlacklist = SupabaseTestHelper.getBlacklistedApps(context, profile).toSet()
        
        // Remove test app if it was added
        testAppPackage?.let { pkg ->
            if (currentBlacklist.contains(pkg) && !originalBlacklist.contains(pkg)) {
                BlacklistManager.removeFromBlacklist(context, pkg)
            } else if (!currentBlacklist.contains(pkg) && originalBlacklist.contains(pkg)) {
                BlacklistManager.addToBlacklist(context, pkg)
            }
        }
        
        // Wait for sync
        Thread.sleep(2000)
        
        scenario.close()
    }

    @Test
    fun testAddBlacklistAppSavedToDatabase() {
        val profile = ProfileManager.readProfile(context) ?: "AM"
        val pkg = testAppPackage ?: return
        
        // Launch BlackListSettingsActivity
        scenario = ActivityScenario.launch(BlackListSettingsActivity::class.java)
        
        // Wait for activity to load
        Thread.sleep(2000)
        
        // Find the checkbox for our test app by looking for text containing the package name
        onView(withText(containsString(pkg)))
            .check(matches(isDisplayed()))
            .perform(click()) // This should toggle it on if it was off
        
        // Wait for the save operation and database sync
        // BlacklistManager.addToBlacklist triggers async cloud sync
        val syncSuccess = SupabaseTestHelper.waitForDatabaseSync(
            context,
            maxWaitSeconds = 10,
            checkIntervalMs = 500
        ) {
            val dbBlacklist = SupabaseTestHelper.getBlacklistedApps(context, profile)
            dbBlacklist.contains(pkg)
        }
        
        // Verify app was saved to database
        assertTrue("Blacklist app should be synced to database within 10 seconds", syncSuccess)
        
        val dbBlacklist = SupabaseTestHelper.getBlacklistedApps(context, profile)
        assertTrue("App should be in database blacklist", dbBlacklist.contains(pkg))
    }
}
