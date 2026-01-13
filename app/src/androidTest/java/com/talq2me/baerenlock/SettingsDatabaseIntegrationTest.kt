package com.talq2me.baerenlock

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/**
 * Integration test for Test Case 4:
 * Change settings for email and verify changes are reflected in settings table of db.
 * 
 * This test automates:
 * - Opening settings
 * - Changing email address
 * - Verifying the change is saved to Supabase settings table
 */
@RunWith(AndroidJUnit4::class)
class SettingsDatabaseIntegrationTest {

    private lateinit var context: android.content.Context
    private lateinit var scenario: ActivityScenario<*>
    private val testEmail = "test@example.com"
    private val originalEmail: String?

    init {
        // Get original email before any test runs
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        originalEmail = SettingsManager.readEmail(appContext)
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Skip test if Supabase is not configured
        if (!SupabaseTestHelper.isConfigured(context)) {
            org.junit.Assume.assumeTrue("Supabase not configured - skipping test", false)
        }
    }

    @After
    fun tearDown() {
        // Restore original email if it existed
        originalEmail?.let {
            SettingsManager.writeEmail(context, it)
            // Wait for sync
            Thread.sleep(2000)
        }
        
        scenario.close()
    }

    @Test
    fun testEmailChangeReflectedInDatabase() {
        // Launch MainActivity first
        scenario = ActivityScenario.launch(MainActivity::class.java)
        
        // Wait for activity to be ready
        Thread.sleep(1000)
        
        // Navigate to Settings - we need to find a way to open settings
        // Since MainActivity might have a settings button or we can launch SettingsActivity directly
        // Let's try launching SettingsActivity directly
        scenario.close()
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        
        // Wait for activity to load
        Thread.sleep(1000)
        
        // Find the email EditText - it's the first EditText in the layout
        // Since SettingsActivity creates UI programmatically, we'll use a more general matcher
        onView(Matchers.instanceOf(android.widget.EditText::class.java))
            .perform(clearText(), typeText(testEmail))
        
        // Click the "Save Email" button
        onView(withText("Save Email"))
            .perform(click())
        
        // Wait for the save operation and database sync
        // SettingsManager.writeEmail triggers async cloud sync
        val syncSuccess = SupabaseTestHelper.waitForDatabaseSync(
            context,
            maxWaitSeconds = 10,
            checkIntervalMs = 500
        ) {
            val dbEmail = SupabaseTestHelper.getParentEmail(context)
            dbEmail == testEmail
        }
        
        // Verify email was saved to database
        assertTrue("Email should be synced to database within 10 seconds", syncSuccess)
        
        val dbEmail = SupabaseTestHelper.getParentEmail(context)
        assertEquals("Email in database should match entered email", testEmail, dbEmail)
    }
}
