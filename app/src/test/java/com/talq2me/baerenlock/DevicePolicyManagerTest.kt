package com.talq2me.baerenlock

import android.app.admin.DevicePolicyManager as AndroidDPM
import android.content.Context
import android.os.Build
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class DevicePolicyManagerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockDpm: AndroidDPM

    private lateinit var devicePolicyManager: DevicePolicyManager

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(mockContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(mockDpm)
        whenever(mockContext.packageName).thenReturn("com.talq2me.baerenlock")
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        
        // Reset singleton instance for testing
        try {
            val field = DevicePolicyManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (e: Exception) {
            // Ignore if reflection fails
        }

        devicePolicyManager = DevicePolicyManager.getInstance(mockContext)
    }

    @Test
    fun `isDeviceOwner returns false when not device owner`() {
        whenever(mockDpm.isDeviceOwnerApp("com.talq2me.baerenlock")).thenReturn(false)

        val result = devicePolicyManager.isDeviceOwner()

        assertFalse("Should not be device owner", result)
    }

    @Test
    fun `isDeviceOwner returns true when device owner`() {
        whenever(mockDpm.isDeviceOwnerApp("com.talq2me.baerenlock")).thenReturn(true)

        val result = devicePolicyManager.isDeviceOwner()

        assertTrue("Should be device owner", result)
    }

    @Test
    fun `isAdminActive returns false when admin not active`() {
        whenever(mockDpm.isAdminActive(any<android.content.ComponentName>())).thenReturn(false)

        val result = devicePolicyManager.isAdminActive()

        assertFalse("Admin should not be active", result)
    }

    @Test
    fun `isAdminActive returns true when admin active`() {
        whenever(mockDpm.isAdminActive(any<android.content.ComponentName>())).thenReturn(true)

        val result = devicePolicyManager.isAdminActive()

        assertTrue("Admin should be active", result)
    }

    @Test
    fun `isDeviceOwnerActive returns true only when both conditions met`() {
        whenever(mockDpm.isDeviceOwnerApp("com.talq2me.baerenlock")).thenReturn(true)
        whenever(mockDpm.isAdminActive(any<android.content.ComponentName>())).thenReturn(true)

        val result = devicePolicyManager.isDeviceOwnerActive()

        assertTrue("Should be device owner and admin active", result)
    }

    @Test
    fun `isDeviceOwnerActive returns false when only device owner`() {
        whenever(mockDpm.isDeviceOwnerApp("com.talq2me.baerenlock")).thenReturn(true)
        whenever(mockDpm.isAdminActive(any<android.content.ComponentName>())).thenReturn(false)

        val result = devicePolicyManager.isDeviceOwnerActive()

        assertFalse("Should not be active if admin not active", result)
    }

    @Test
    fun `isDeviceOwnerActive returns false when only admin active`() {
        whenever(mockDpm.isDeviceOwnerApp("com.talq2me.baerenlock")).thenReturn(false)
        whenever(mockDpm.isAdminActive(any<android.content.ComponentName>())).thenReturn(true)

        val result = devicePolicyManager.isDeviceOwnerActive()

        assertFalse("Should not be active if not device owner", result)
    }

    @Test
    fun `getInstance returns same instance`() {
        val instance1 = DevicePolicyManager.getInstance(mockContext)
        val instance2 = DevicePolicyManager.getInstance(mockContext)

        assertSame("Should return same instance", instance1, instance2)
    }

    @Test
    fun `getDeviceInfo returns status information`() {
        whenever(mockDpm.isDeviceOwnerApp("com.talq2me.baerenlock")).thenReturn(true)
        whenever(mockDpm.isAdminActive(any<android.content.ComponentName>())).thenReturn(true)

        val info = devicePolicyManager.getDeviceInfo()

        assertNotNull("Device info should not be null", info)
        assertTrue("Should contain isDeviceOwner", info.containsKey("isDeviceOwner"))
        assertTrue("Should contain isAdminActive", info.containsKey("isAdminActive"))
        assertEquals("Should indicate device owner", "true", info["isDeviceOwner"])
        assertEquals("Should indicate admin active", "true", info["isAdminActive"])
    }

    @Test
    fun `setDeviceOwnerApp returns true when already device owner`() {
        whenever(mockDpm.isDeviceOwnerApp("com.talq2me.baerenlock")).thenReturn(true)

        val result = devicePolicyManager.setDeviceOwnerApp()

        assertTrue("Should return true when already device owner", result)
    }

    @Test
    fun `setDeviceOwnerApp returns false when not device owner`() {
        whenever(mockDpm.isDeviceOwnerApp("com.talq2me.baerenlock")).thenReturn(false)

        val result = devicePolicyManager.setDeviceOwnerApp()

        assertFalse("Should return false when not device owner", result)
    }
}

