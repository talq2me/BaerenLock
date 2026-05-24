package com.talq2me.baerenlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HeartbeatManagerTest {

    @Before
    fun reset() {
        HeartbeatManager.touch()
    }

    @Test
    fun isStaleWhenNeverTouched() {
        // After touch in @Before, not stale
        assertFalse(HeartbeatManager.isStale(15_000L))
    }

    @Test
    fun isStaleAfterThreshold() {
        HeartbeatManager.touch()
        val field = HeartbeatManager::class.java.getDeclaredField("lastHeartbeatMs")
        field.isAccessible = true
        val atomic = field.get(null) as java.util.concurrent.atomic.AtomicLong
        atomic.set(System.currentTimeMillis() - 20_000L)
        assertTrue(HeartbeatManager.isStale(15_000L))
    }
}
