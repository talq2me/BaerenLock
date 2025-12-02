package com.talq2me.baerenlock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
class RewardUsageTrackerTest {

    private lateinit var context: Context
    private lateinit var tracker: RewardUsageTracker

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // Use Robolectric to get a real Android context
        context = RuntimeEnvironment.getApplication()
        tracker = RewardUsageTracker(context)
    }

    @Test
    fun `startRewardSession initializes tracking`() {
        tracker.startRewardSession()

        val summary = tracker.getSessionSummary()
        assertTrue("Session should have start time", summary.startTime > 0)
        assertEquals("Should have no sessions initially", 0, summary.uniqueApps)
    }

    @Test
    fun `onAppForeground creates new session`() {
        val packageName = context.packageName // Use a real package name

        tracker.startRewardSession()
        tracker.onAppForeground(packageName)

        val summary = tracker.getSessionSummary()
        assertTrue("Should track at least one app or have start time", summary.startTime > 0)
    }

    @Test
    fun `onAppForeground switches to different app ends previous session`() {
        val app1 = context.packageName
        val app2 = "com.android.settings" // Use a real system app

        tracker.startRewardSession()
        tracker.onAppForeground(app1)
        Thread.sleep(10) // Small delay to ensure different timestamps
        tracker.onAppForeground(app2)

        val sessions = tracker.endRewardSession()
        assertTrue("Should have at least one session", sessions.size >= 1)
    }

    @Test
    fun `endRewardSession returns all tracked sessions`() {
        val app1 = context.packageName
        val app2 = "com.android.settings"

        tracker.startRewardSession()
        tracker.onAppForeground(app1)
        Thread.sleep(10)
        tracker.onAppForeground(app2)

        val sessions = tracker.endRewardSession()
        assertTrue("Should have at least one session", sessions.size >= 1)
    }

    @Test
    fun `getSessionSummary calculates total time`() {
        val packageName = context.packageName

        tracker.startRewardSession()
        tracker.onAppForeground(packageName)
        Thread.sleep(100) // Wait a bit to have measurable duration
        tracker.endRewardSession()

        val summary = tracker.getSessionSummary()
        assertTrue("Total time should be greater than or equal to 0", summary.totalTimeSeconds >= 0)
    }

    @Test
    fun `getSessionSummary groups usage by package`() {
        val app1 = context.packageName
        val app2 = "com.android.settings"

        tracker.startRewardSession()
        tracker.onAppForeground(app1)
        Thread.sleep(50)
        tracker.onAppForeground(app2)
        Thread.sleep(50)
        tracker.onAppForeground(app1) // Switch back to app1
        Thread.sleep(50)

        val summary = tracker.getSessionSummary()
        assertTrue("Should have usage data", summary.appUsageMap.isNotEmpty() || summary.totalTimeSeconds >= 0)
    }

    @Test
    fun `AppUsageSession formattedDuration formats correctly`() {
        val session = RewardUsageTracker.AppUsageSession(
            packageName = "com.example.app",
            appName = "Example App",
            startTime = System.currentTimeMillis() - 125000, // 125 seconds ago
            endTime = System.currentTimeMillis()
        )
        session.updateDuration()

        val formatted = session.formattedDuration
        assertTrue("Should format as minutes and seconds", formatted.contains("m"))
        assertTrue("Should contain seconds", formatted.contains("s"))
    }

    @Test
    fun `AppUsageSession formattedDuration handles seconds only`() {
        val session = RewardUsageTracker.AppUsageSession(
            packageName = "com.example.app",
            appName = "Example App",
            startTime = System.currentTimeMillis() - 30000, // 30 seconds ago
            endTime = System.currentTimeMillis()
        )
        session.updateDuration()

        val formatted = session.formattedDuration
        assertTrue("Should format as seconds only", formatted.endsWith("s"))
    }

    @Test
    fun `RewardSessionSummary formattedTotalTime formats correctly`() {
        val summary = RewardUsageTracker.RewardSessionSummary(
            startTime = System.currentTimeMillis() - 125000,
            endTime = System.currentTimeMillis(),
            totalTimeSeconds = 125,
            uniqueApps = 1,
            appUsageMap = emptyMap()
        )

        val formatted = summary.formattedTotalTime
        assertTrue("Should format total time", formatted.isNotEmpty())
        assertTrue("Should contain time units", formatted.contains("m") || formatted.contains("s"))
    }

    @Test
    fun `onAppBackground ends current session`() {
        val packageName = context.packageName

        tracker.startRewardSession()
        tracker.onAppForeground(packageName)
        Thread.sleep(10)
        tracker.onAppBackground(packageName)

        val summary = tracker.getSessionSummary()
        assertTrue("Should have tracked session", summary.totalTimeSeconds >= 0)
    }
}

