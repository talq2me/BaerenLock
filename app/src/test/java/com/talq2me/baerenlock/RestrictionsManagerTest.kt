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

@RunWith(RobolectricTestRunner::class)
class RestrictionsManagerTest {

    private lateinit var context: Context
    private lateinit var restrictionsManager: RestrictionsManager

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        
        // Use Robolectric to get a real Android context
        context = RuntimeEnvironment.getApplication()
        
        // Reset singleton instance for testing
        try {
            val field = RestrictionsManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (e: Exception) {
            // Ignore if reflection fails
        }
        
        // Also reset DevicePolicyManager singleton
        try {
            val dpmField = DevicePolicyManager::class.java.getDeclaredField("instance")
            dpmField.isAccessible = true
            dpmField.set(null, null)
        } catch (e: Exception) {
            // Ignore if reflection fails
        }

        restrictionsManager = RestrictionsManager.getInstance(context)
    }

    @Test
    fun `isRestrictionEnabled returns false by default`() {
        val result = restrictionsManager.isRestrictionEnabled("disable_camera")

        assertFalse("Restriction should be disabled by default", result)
    }

    @Test
    fun `isRestrictionEnabled returns true when enabled`() {
        restrictionsManager.enableRestriction("disable_camera")
        
        val result = restrictionsManager.isRestrictionEnabled("disable_camera")

        assertTrue("Restriction should be enabled", result)
    }

    @Test
    fun `enableRestriction saves to preferences`() {
        restrictionsManager.enableRestriction("disable_camera")
        
        val result = restrictionsManager.isRestrictionEnabled("disable_camera")
        assertTrue("Restriction should be enabled after calling enableRestriction", result)
    }

    @Test
    fun `disableRestriction saves to preferences`() {
        restrictionsManager.enableRestriction("disable_camera")
        restrictionsManager.disableRestriction("disable_camera")
        
        val result = restrictionsManager.isRestrictionEnabled("disable_camera")
        assertFalse("Restriction should be disabled after calling disableRestriction", result)
    }

    @Test
    fun `getAllRestrictions returns map of all restrictions`() {
        val restrictions = restrictionsManager.getAllRestrictions()

        assertNotNull("Restrictions map should not be null", restrictions)
        assertTrue("Should contain disable_camera", restrictions.containsKey("disable_camera"))
        assertTrue("Should contain disable_microphone", restrictions.containsKey("disable_microphone"))
        assertTrue("Should contain disable_bluetooth", restrictions.containsKey("disable_bluetooth"))
        assertTrue("Should contain disable_wifi", restrictions.containsKey("disable_wifi"))
        assertTrue("Should contain disable_location", restrictions.containsKey("disable_location"))
    }

    @Test
    fun `resetAllRestrictions disables all enabled restrictions`() {
        // Enable some restrictions first
        restrictionsManager.enableRestriction("disable_camera")
        restrictionsManager.enableRestriction("disable_bluetooth")
        
        // Verify they're enabled
        assertTrue("Camera should be enabled", restrictionsManager.isRestrictionEnabled("disable_camera"))
        assertTrue("Bluetooth should be enabled", restrictionsManager.isRestrictionEnabled("disable_bluetooth"))

        restrictionsManager.resetAllRestrictions()

        // Verify they're now disabled
        assertFalse("Camera should be disabled after reset", restrictionsManager.isRestrictionEnabled("disable_camera"))
        assertFalse("Bluetooth should be disabled after reset", restrictionsManager.isRestrictionEnabled("disable_bluetooth"))
    }

    @Test
    fun `applyRestrictionsFromProfile strict enables multiple restrictions`() {
        restrictionsManager.applyRestrictionsFromProfile("strict")

        // Verify multiple restrictions are enabled
        assertTrue("Camera should be disabled in strict mode", restrictionsManager.isRestrictionEnabled("disable_camera"))
        assertTrue("Microphone should be disabled in strict mode", restrictionsManager.isRestrictionEnabled("disable_microphone"))
    }

    @Test
    fun `applyRestrictionsFromProfile moderate enables fewer restrictions`() {
        restrictionsManager.applyRestrictionsFromProfile("moderate")

        // Verify some restrictions are enabled
        assertTrue("Camera should be disabled in moderate mode", restrictionsManager.isRestrictionEnabled("disable_camera"))
    }

    @Test
    fun `applyRestrictionsFromProfile lenient disables restrictions`() {
        // First enable some restrictions
        restrictionsManager.enableRestriction("disable_camera")
        restrictionsManager.enableRestriction("disable_microphone")
        
        restrictionsManager.applyRestrictionsFromProfile("lenient")

        // Verify restrictions are disabled in lenient mode
        assertFalse("Camera should be enabled in lenient mode", restrictionsManager.isRestrictionEnabled("disable_camera"))
        assertFalse("Microphone should be enabled in lenient mode", restrictionsManager.isRestrictionEnabled("disable_microphone"))
    }

    @Test
    fun `getAllRestrictions returns correct state for each restriction`() {
        restrictionsManager.enableRestriction("disable_camera")
        restrictionsManager.enableRestriction("disable_bluetooth")
        // Microphone remains disabled

        val restrictions = restrictionsManager.getAllRestrictions()

        assertTrue("Camera should be disabled", restrictions["disable_camera"] == true)
        assertFalse("Microphone should be enabled", restrictions["disable_microphone"] == true)
        assertTrue("Bluetooth should be disabled", restrictions["disable_bluetooth"] == true)
    }
}

