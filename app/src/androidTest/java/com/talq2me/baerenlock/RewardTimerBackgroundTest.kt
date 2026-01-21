package com.talq2me.baerenlock

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Automated test for Test Case A: Reward Timer Decrements While App is in Background
 * 
 * This test verifies that the reward timer:
 * 1. Runs in the background (via AppBlockerService)
 * 2. Decrements time correctly when reward app is in foreground
 * 3. Updates the database every minute
 * 4. Blocks the reward app when time expires
 * 5. Works without BaerenLock in the foreground
 * 
 * Uses Chrome as the test app since it's available on emulators.
 */
@RunWith(AndroidJUnit4::class)
class RewardTimerBackgroundTest {

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private lateinit var originalProfile: String
    private var originalRewardApps: Set<String> = emptySet()
    private var originalBankedMins: Int = 0
    
    // Test configuration
    private val TEST_APP_PACKAGE = "com.android.chrome"
    private val TEST_APP_LABEL = "Chrome"
    private val TEST_DURATION_MINUTES = 2
    
    companion object {
        private const val TAG = "RewardTimerBackgroundTest"
    }

    @Before
    fun setUp() {
        // Initialize UI Automator
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Save original state
        originalProfile = ProfileManager.readProfile(context) ?: "AM"
        originalRewardApps = SettingsManager.readRewardApps(context).toSet()
        originalBankedMins = getBankedMinsFromDatabase()
        
        // Clear any existing reward time
        setBankedMinsInDatabase(0)
        
        // Make sure we're on the launcher
        device.pressHome()
        Thread.sleep(1000)
        
        android.util.Log.d(TAG, "Test setup complete. Original state: profile=$originalProfile, " +
                "rewardApps=$originalRewardApps, bankedMins=$originalBankedMins")
    }

    @After
    fun tearDown() {
        // Restore original state
        try {
            android.util.Log.d(TAG, "Restoring original state...")
            
            // Restore reward apps
            SettingsManager.writeRewardApps(context, originalRewardApps.toMutableSet())
            
            // Restore banked mins
            setBankedMinsInDatabase(originalBankedMins)
            
            // Return to home
            device.pressHome()
            Thread.sleep(500)
            
            android.util.Log.d(TAG, "Teardown complete")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error during teardown", e)
        }
    }

    @Test
    fun testRewardTimerDecrementsInBackground() {
        android.util.Log.d(TAG, "Starting testRewardTimerDecrementsInBackground")
        
        // ===== SETUP PHASE =====
        android.util.Log.d(TAG, "Phase 1: Setup - Setting Chrome as reward app")
        
        // Set Chrome as a reward app
        val rewardApps = mutableSetOf(TEST_APP_PACKAGE)
        SettingsManager.writeRewardApps(context, rewardApps)
        RewardAppsManager.refreshRewardEligibleApps(context)
        
        // Grant test duration minutes
        android.util.Log.d(TAG, "Phase 1: Setup - Granting $TEST_DURATION_MINUTES minutes reward time")
        setBankedMinsInDatabase(TEST_DURATION_MINUTES)
        
        // Verify setup - should now have the reward minutes
        val initialMins = getBankedMinsFromDatabase()
        android.util.Log.d(TAG, "Phase 1: Verification - Read banked_mins=$initialMins from storage (expected $TEST_DURATION_MINUTES)")
        assertEquals("Initial banked_mins should be $TEST_DURATION_MINUTES", 
            TEST_DURATION_MINUTES, initialMins)
        
        android.util.Log.d(TAG, "Phase 1: Complete - Setup successful, banked_mins=$initialMins")
        
        // ===== LAUNCHER PHASE =====
        android.util.Log.d(TAG, "Phase 2: Launcher - Opening BaerenLock launcher")
        
        // Explicitly launch BaerenLock's LauncherActivity
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.talq2me.baerenlock")
        launchIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        Thread.sleep(2000) // Initial wait
        
        // Wait for launcher to be fully resumed (dismiss any permission dialogs)
        android.util.Log.d(TAG, "Phase 2: Waiting for launcher to be fully resumed...")
        var waitCount = 0
        while (waitCount < 15) { // Max 15 seconds
            val currentPkg = device.currentPackageName
            android.util.Log.d(TAG, "Phase 2: Wait iteration $waitCount: current package = $currentPkg")
            
            if (currentPkg.contains("settings")) {
                // Settings dialog is open, dismiss it
                android.util.Log.d(TAG, "Phase 2: Settings dialog detected, pressing back...")
                device.pressBack()
                Thread.sleep(2000) // Wait for back to process
            } else if (currentPkg.contains("baerenlock")) {
                // We're on BaerenLock, check if it's actually visible (not paused)
                android.util.Log.d(TAG, "Phase 2: BaerenLock is active!")
                Thread.sleep(2000) // Give it time to fully render
                break
            } else {
                // Some other package, wait a bit
                Thread.sleep(1000)
            }
            waitCount++
        }
        
        // Final check - ensure we're on BaerenLock
        val finalCheckPkg = device.currentPackageName
        android.util.Log.d(TAG, "Phase 2: Final package check: $finalCheckPkg")
        if (!finalCheckPkg.contains("baerenlock")) {
            android.util.Log.w(TAG, "Phase 2: Still not on BaerenLock after waiting, forcing launch...")
            val retryIntent = context.packageManager.getLaunchIntentForPackage("com.talq2me.baerenlock")
            retryIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(retryIntent)
            Thread.sleep(3000)
            
            // Try dismissing Settings again if needed
            if (device.currentPackageName.contains("settings")) {
                device.pressBack()
                Thread.sleep(2000)
            }
        }
        
        // Verify we're on the launcher
        val currentPackage = device.currentPackageName
        android.util.Log.d(TAG, "Phase 2: Current package: $currentPackage")
        assertTrue("Should be on BaerenLock launcher, but on $currentPackage", 
            currentPackage.contains("baerenlock"))
        
        // Debug: Check RewardManager state
        val debugMins = RewardManager.currentRewardMinutes
        android.util.Log.d(TAG, "Phase 2: RewardManager.currentRewardMinutes = $debugMins")
        
        // NOW send broadcast to refresh (launcher is ready)
        android.util.Log.d(TAG, "Phase 2: Sending refresh broadcast to launcher...")
        val refreshIntent = android.content.Intent(RewardTimeReceiver.ACTION_REWARD_TIME_UPDATED)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(refreshIntent)
        Thread.sleep(2000) // Give time for broadcast to be processed and UI to update
        
        // Start the timer for continuous updates
        RewardManager.startRewardTimer(context)
        Thread.sleep(1000)
        
        // Verify Chrome appears in launcher (because banked_mins > 0)
        // Wait a bit more for launcher to finish rendering icons
        Thread.sleep(2000)
        
        var chromeIcon = device.wait(Until.findObject(By.text(TEST_APP_LABEL)), 5000)
        if (chromeIcon == null) {
            android.util.Log.w(TAG, "Phase 2: Chrome not found by text '$TEST_APP_LABEL', trying by package name...")
            // Try to find by package name as fallback
            chromeIcon = device.wait(Until.findObject(By.pkg(TEST_APP_PACKAGE)), 3000)
            if (chromeIcon != null) {
                android.util.Log.d(TAG, "Phase 2: Found Chrome by package name instead")
            } else {
                android.util.Log.e(TAG, "Phase 2: Chrome icon not found by text or package! RewardManager minutes: $debugMins")
            }
        }
        assertNotNull("Chrome should appear in launcher when reward time > 0 (current=$debugMins)", chromeIcon)
        
        // Verify the icon is actually clickable
        val isClickable = chromeIcon?.isClickable == true
        android.util.Log.d(TAG, "Phase 2: Chrome icon found, clickable=$isClickable")
        if (!isClickable) {
            android.util.Log.w(TAG, "Phase 2: Chrome icon is not clickable! This might cause launch to fail.")
        }
        
        android.util.Log.d(TAG, "Phase 2: Complete - Chrome visible in launcher")
        
        // ===== FOREGROUND PHASE 1 - First Minute =====
        android.util.Log.d(TAG, "Phase 3: Test - Launching Chrome")
        
        // Launch Chrome
        android.util.Log.d(TAG, "Phase 3: Clicking Chrome icon...")
        chromeIcon?.click()
        Thread.sleep(5000) // Wait longer for Chrome to fully start
        
        // Check if Chrome launched - retry if needed
        var chromePackage = device.currentPackageName
        android.util.Log.d(TAG, "Phase 3: After click, current package: $chromePackage")
        
        if (!chromePackage.contains("chrome")) {
            android.util.Log.w(TAG, "Phase 3: Chrome didn't launch, trying again...")
            // Try clicking again
            val retryIcon = device.wait(Until.findObject(By.text(TEST_APP_LABEL)), 3000)
            retryIcon?.click()
            Thread.sleep(5000)
            chromePackage = device.currentPackageName
            android.util.Log.d(TAG, "Phase 3: After retry, current package: $chromePackage")
        }
        
        // Verify Chrome is in foreground
        assertTrue("Chrome should be in foreground, but $chromePackage is", 
            chromePackage.contains("chrome"))
        
        android.util.Log.d(TAG, "Phase 3: Chrome launched, package=$chromePackage")
        
        // Wait for first minute to pass (with progress logging)
        android.util.Log.d(TAG, "Phase 3: Waiting for first minute to elapse...")
        waitWithProgress(60000, "First minute") { elapsed ->
            if (elapsed % 15000L == 0L) {
                val currentMins = getBankedMinsFromDatabase()
                android.util.Log.d(TAG, "  ${elapsed/1000}s elapsed, banked_mins=$currentMins")
            }
        }
        
        // Check database after 1 minute
        val minsAfterOne = getBankedMinsFromDatabase()
        android.util.Log.d(TAG, "Phase 3: After 1 minute - banked_mins=$minsAfterOne (expected: ${TEST_DURATION_MINUTES - 1})")
        
        // Verify time decremented (with tolerance)
        assertTrue("Timer should have decremented after 1 minute. Expected ${TEST_DURATION_MINUTES - 1}, got $minsAfterOne", 
            minsAfterOne >= TEST_DURATION_MINUTES - 2 && minsAfterOne <= TEST_DURATION_MINUTES - 1)
        
        // ===== FOREGROUND PHASE 2 - Second Minute =====
        android.util.Log.d(TAG, "Phase 4: Test - Waiting for second minute (time to expire)")
        
        // Verify Chrome is still in foreground
        val stillChrome = device.currentPackageName.contains("chrome")
        assertTrue("Chrome should still be in foreground", stillChrome)
        
        // Wait for second minute (timer should expire)
        android.util.Log.d(TAG, "Phase 4: Waiting for second minute to elapse...")
        waitWithProgress(65000, "Second minute") { elapsed ->
            if (elapsed % 15000L == 0L) {
                val currentMins = getBankedMinsFromDatabase()
                val currentPkg = device.currentPackageName
                android.util.Log.d(TAG, "  ${elapsed/1000}s elapsed, banked_mins=$currentMins, package=$currentPkg")
            }
        }
        
        // Check final state
        val finalMins = getBankedMinsFromDatabase()
        android.util.Log.d(TAG, "Phase 4: After 2 minutes - banked_mins=$finalMins (expected: 0)")
        
        // Verify time expired
        assertEquals("Timer should reach 0 after 2 minutes", 0, finalMins)
        
        // ===== BLOCKING VERIFICATION PHASE =====
        android.util.Log.d(TAG, "Phase 5: Verification - Checking if Chrome is blocked")
        
        // Should have returned to launcher (Chrome should be killed/blocked)
        Thread.sleep(2000)
        val finalPackage = device.currentPackageName
        android.util.Log.d(TAG, "Phase 5: Current package after expiration: $finalPackage")
        
        // Could be on launcher or in the process of returning to it
        val onLauncher = finalPackage.contains("baerenlock")
        if (!onLauncher) {
            // Give it a bit more time, then launch BaerenLock
            Thread.sleep(2000)
            val returnIntent = context.packageManager.getLaunchIntentForPackage("com.talq2me.baerenlock")
            returnIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(returnIntent)
            Thread.sleep(2000)
        }
        
        // Ensure we're on BaerenLock launcher to check if Chrome is blocked
        val verifyIntent = context.packageManager.getLaunchIntentForPackage("com.talq2me.baerenlock")
        verifyIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(verifyIntent)
        Thread.sleep(2000)
        
        val chromeIconAfter = device.wait(Until.findObject(By.text(TEST_APP_LABEL)), 3000)
        assertNull("Chrome should NOT appear in launcher when banked_mins = 0", chromeIconAfter)
        
        android.util.Log.d(TAG, "Phase 5: Complete - Chrome successfully blocked")
        
        // ===== TEST COMPLETE =====
        android.util.Log.d(TAG, "✅ TEST PASSED - All phases completed successfully!")
        android.util.Log.d(TAG, "Summary:")
        android.util.Log.d(TAG, "  - Initial time: $TEST_DURATION_MINUTES minutes")
        android.util.Log.d(TAG, "  - After 1 min: $minsAfterOne minutes")
        android.util.Log.d(TAG, "  - After 2 min: $finalMins minutes")
        android.util.Log.d(TAG, "  - Chrome blocked: ${chromeIconAfter == null}")
    }
    
    @Test
    fun testTimerOnlyCountsForegroundTime() {
        android.util.Log.d(TAG, "Starting testTimerOnlyCountsForegroundTime")
        
        // Setup: Grant 3 minutes, set Chrome as reward app
        val rewardApps = mutableSetOf(TEST_APP_PACKAGE)
        SettingsManager.writeRewardApps(context, rewardApps)
        RewardAppsManager.refreshRewardEligibleApps(context)
        setBankedMinsInDatabase(3)
        
        // Open launcher FIRST (before sending broadcast)
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.talq2me.baerenlock")
        launchIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        Thread.sleep(2000) // Initial wait
        
        // Wait for launcher to be fully resumed (dismiss any permission dialogs)
        android.util.Log.d(TAG, "Waiting for launcher to be fully resumed...")
        var waitCount = 0
        while (waitCount < 15) { // Max 15 seconds
            val currentPkg = device.currentPackageName
            android.util.Log.d(TAG, "  Wait iteration $waitCount: current package = $currentPkg")
            
            if (currentPkg.contains("settings")) {
                // Settings dialog is open, dismiss it
                android.util.Log.d(TAG, "  Settings dialog detected, pressing back...")
                device.pressBack()
                Thread.sleep(2000) // Wait for back to process
            } else if (currentPkg.contains("baerenlock")) {
                // We're on BaerenLock, check if it's actually visible (not paused)
                android.util.Log.d(TAG, "  BaerenLock is active!")
                Thread.sleep(2000) // Give it time to fully render
                break
            } else {
                // Some other package, wait a bit
                Thread.sleep(1000)
            }
            waitCount++
        }
        
        // Final check - ensure we're on BaerenLock
        val finalPkg = device.currentPackageName
        android.util.Log.d(TAG, "Final package after waiting: $finalPkg")
        if (!finalPkg.contains("baerenlock")) {
            android.util.Log.w(TAG, "Still not on BaerenLock after waiting, forcing launch...")
            // Force launch again and wait
            val retryIntent = context.packageManager.getLaunchIntentForPackage("com.talq2me.baerenlock")
            retryIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(retryIntent)
            Thread.sleep(3000)
            
            // Try dismissing Settings again if needed
            if (device.currentPackageName.contains("settings")) {
                device.pressBack()
                Thread.sleep(2000)
            }
        }
        
        // Debug: Check RewardManager state
        val currentMins = RewardManager.currentRewardMinutes
        android.util.Log.d(TAG, "RewardManager.currentRewardMinutes = $currentMins")
        
        // NOW trigger refresh (launcher is running and ready to receive)
        android.util.Log.d(TAG, "Sending refresh broadcast to launcher...")
        val refreshIntent = android.content.Intent(RewardTimeReceiver.ACTION_REWARD_TIME_UPDATED)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(refreshIntent)
        Thread.sleep(2000) // Give time for broadcast to be processed and UI to update
        
        // Also start the timer to ensure continuous updates
        RewardManager.startRewardTimer(context)
        Thread.sleep(1000)
        
        // Wait for launcher to finish rendering
        Thread.sleep(2000)
        
        // Launch Chrome
        var chromeIcon = device.wait(Until.findObject(By.text(TEST_APP_LABEL)), 5000)
        if (chromeIcon == null) {
            android.util.Log.w(TAG, "Chrome not found by text '$TEST_APP_LABEL', trying by package name...")
            chromeIcon = device.wait(Until.findObject(By.pkg(TEST_APP_PACKAGE)), 3000)
            if (chromeIcon == null) {
                android.util.Log.e(TAG, "Chrome icon not found! Current reward minutes: $currentMins")
                // Try to dump what apps are visible
                val allApps = device.findObjects(By.clickable(true))
                android.util.Log.d(TAG, "Visible clickable elements: ${allApps.size}")
            }
        }
        assertNotNull("Chrome should be in launcher (banked_mins=$currentMins)", chromeIcon)
        
        android.util.Log.d(TAG, "Clicking Chrome icon (clickable=${chromeIcon?.isClickable})...")
        chromeIcon?.click()
        Thread.sleep(5000) // Wait longer for Chrome to start
        
        // Verify Chrome actually launched
        var chromePackage = device.currentPackageName
        android.util.Log.d(TAG, "After click, current package: $chromePackage")
        
        if (!chromePackage.contains("chrome")) {
            android.util.Log.w(TAG, "Chrome didn't launch, trying again...")
            val retryIcon = device.wait(Until.findObject(By.text(TEST_APP_LABEL)), 3000)
            retryIcon?.click()
            Thread.sleep(5000)
            chromePackage = device.currentPackageName
            android.util.Log.d(TAG, "After retry, current package: $chromePackage")
        }
        
        assertTrue("Chrome should be in foreground, but $chromePackage is", 
            chromePackage.contains("chrome"))
        
        android.util.Log.d(TAG, "Chrome launched successfully, waiting 1 minute...")
        
        // Use Chrome for 1 minute
        waitWithProgress(60000, "Chrome usage") { elapsed ->
            if (elapsed % 15000L == 0L) {
                android.util.Log.d(TAG, "  ${elapsed/1000}s in Chrome")
            }
        }
        
        val minsAfterChrome = getBankedMinsFromDatabase()
        android.util.Log.d(TAG, "After 1 min Chrome: banked_mins=$minsAfterChrome (expected ~2)")
        
        // Return to launcher
        val returnIntent = context.packageManager.getLaunchIntentForPackage("com.talq2me.baerenlock")
        returnIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(returnIntent)
        Thread.sleep(2000)
        
        // Wait 2 minutes on launcher (time should NOT decrement)
        android.util.Log.d(TAG, "On launcher, waiting 2 minutes (time should NOT decrement)...")
        waitWithProgress(120000, "Launcher time") { elapsed ->
            if (elapsed % 30000L == 0L) {
                val currentMins = getBankedMinsFromDatabase()
                android.util.Log.d(TAG, "  ${elapsed/1000}s on launcher, banked_mins=$currentMins (should still be ~2)")
            }
        }
        
        val minsAfterLauncher = getBankedMinsFromDatabase()
        android.util.Log.d(TAG, "After 2 min on launcher: banked_mins=$minsAfterLauncher (expected ~2, same as before)")
        
        // Verify time did NOT decrease significantly while on launcher
        assertTrue("Time should not decrease much on launcher. Before: $minsAfterChrome, After: $minsAfterLauncher",
            Math.abs(minsAfterChrome - minsAfterLauncher) <= 1)
        
        android.util.Log.d(TAG, "✅ TEST PASSED - Launcher time not counted!")
    }

    // ===== HELPER METHODS =====

    /**
     * Gets banked_mins from RewardStorage (reads from SharedPreferences/database)
     */
    private fun getBankedMinsFromDatabase(): Int {
        return try {
            // Use RewardStorage to get the value from storage
            // This reads from SharedPreferences which is synced with the database
            val mins = RewardStorage.getCurrentRewardMinutesFromStorage(context)
            android.util.Log.d(TAG, "Read banked_mins=$mins from storage")
            mins
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error reading banked_mins", e)
            0
        }
    }

    /**
     * Sets banked_mins using BaerenLock's built-in methods
     */
    private fun setBankedMinsInDatabase(minutes: Int) {
        try {
            // Use RewardStorage's built-in methods instead of direct database access
            RewardStorage.setCurrentRewardMinutes(minutes)
            RewardStorage.saveRewardMinutes(context)
            
            // Wait a bit for the save to complete
            Thread.sleep(500)
            
            // Also update RewardManager's in-memory cache
            RewardManager.loadRewardMinutes(context)
            
            android.util.Log.d(TAG, "Set banked_mins=$minutes for profile $originalProfile")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting banked_mins", e)
        }
    }

    /**
     * Waits for a duration with progress callbacks
     */
    private fun waitWithProgress(durationMs: Long, phaseName: String, onProgress: (elapsed: Long) -> Unit) {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + durationMs
        
        while (System.currentTimeMillis() < endTime) {
            val elapsed = System.currentTimeMillis() - startTime
            onProgress(elapsed)
            Thread.sleep(1000) // Check every second
        }
        
        android.util.Log.d(TAG, "$phaseName complete (${durationMs/1000}s)")
    }
}
