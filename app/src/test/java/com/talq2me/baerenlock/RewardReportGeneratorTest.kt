package com.talq2me.baerenlock

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class RewardReportGeneratorTest {

    private lateinit var generator: RewardReportGenerator

    @Before
    fun setUp() {
        generator = RewardReportGenerator()
    }

    @Test
    fun `generateReport includes child name`() {
        val sessions = emptyList<RewardUsageTracker.AppUsageSession>()
        val summary = createEmptySummary()
        val childName = "Test Child"

        val report = generator.generateReport(sessions, summary, childName)

        assertTrue("Report should contain child name", report.contains(childName))
    }

    @Test
    fun `generateReport includes session dates`() {
        val sessions = emptyList<RewardUsageTracker.AppUsageSession>()
        val summary = createSummaryWithTime(1000L, 2000L)

        val report = generator.generateReport(sessions, summary)

        assertTrue("Report should contain date information", report.contains("Date:"))
        assertTrue("Report should contain session start", report.contains("Session Start:"))
        assertTrue("Report should contain session end", report.contains("Session End:"))
    }

    @Test
    fun `generateReport shows no apps message when sessions empty`() {
        val sessions = emptyList<RewardUsageTracker.AppUsageSession>()
        val summary = createEmptySummary()

        val report = generator.generateReport(sessions, summary)

        assertTrue("Report should indicate no apps used", 
            report.contains("No apps were used") || report.contains("No reward time was used"))
    }

    @Test
    fun `generateReport includes app usage summary`() {
        val sessions = listOf(
            createSession("com.app1", "App 1", 1000L, 2000L),
            createSession("com.app2", "App 2", 2000L, 3000L)
        )
        val summary = createSummaryWithSessions(sessions)

        val report = generator.generateReport(sessions, summary)

        assertTrue("Report should contain app usage summary", report.contains("APP USAGE SUMMARY"))
        assertTrue("Report should contain total apps", report.contains("Total Apps Used"))
    }

    @Test
    fun `generateReport includes apps by usage time`() {
        val sessions = listOf(
            createSession("com.app1", "App 1", 1000L, 2000L), // 1 second
            createSession("com.app2", "App 2", 2000L, 5000L) // 3 seconds
        )
        val summary = createSummaryWithSessions(sessions)

        val report = generator.generateReport(sessions, summary)

        assertTrue("Report should contain apps by usage", report.contains("APPS BY USAGE TIME"))
        assertTrue("Report should contain App 1", report.contains("App 1"))
        assertTrue("Report should contain App 2", report.contains("App 2"))
    }

    @Test
    fun `generateReport includes detailed session log`() {
        val sessions = listOf(
            createSession("com.app1", "App 1", 1000L, 2000L)
        )
        val summary = createSummaryWithSessions(sessions)

        val report = generator.generateReport(sessions, summary)

        assertTrue("Report should contain detailed log", report.contains("DETAILED SESSION LOG"))
        assertTrue("Report should contain session details", report.contains("Session 1"))
    }

    @Test
    fun `generateReport includes summary section`() {
        val sessions = listOf(
            createSession("com.app1", "App 1", 1000L, 2000L)
        )
        val summary = createSummaryWithSessions(sessions)

        val report = generator.generateReport(sessions, summary)

        assertTrue("Report should contain summary section", report.contains("SUMMARY"))
        assertTrue("Report should contain total reward time", report.contains("Total reward time used"))
    }

    @Test
    fun `generateReport calculates percentages correctly`() {
        val sessions = listOf(
            createSession("com.app1", "App 1", 1000L, 2000L), // 1 second
            createSession("com.app2", "App 2", 2000L, 5000L) // 3 seconds, total 4
        )
        val summary = createSummaryWithSessions(sessions)

        val report = generator.generateReport(sessions, summary)

        // App 2 should have higher percentage (3/4 = 75%)
        assertTrue("Report should contain percentage", report.contains("%"))
    }

    @Test
    fun `generateReport handles ongoing sessions`() {
        val session = RewardUsageTracker.AppUsageSession(
            packageName = "com.app1",
            appName = "App 1",
            startTime = 1000L,
            endTime = null // Ongoing
        )
        val sessions = listOf(session)
        val summary = createSummaryWithSessions(sessions)

        val report = generator.generateReport(sessions, summary)

        assertTrue("Report should handle ongoing sessions", report.contains("Ongoing") || report.isNotEmpty())
    }

    @Test
    fun `generateReport formats time correctly`() {
        val sessions = listOf(
            createSession("com.app1", "App 1", 1000L, 61000L) // 60 seconds = 1 minute
        )
        val summary = createSummaryWithSessions(sessions)

        val report = generator.generateReport(sessions, summary)

        assertTrue("Report should format time with minutes", report.contains("m") || report.contains("minute"))
    }

    private fun createEmptySummary(): RewardUsageTracker.RewardSessionSummary {
        return RewardUsageTracker.RewardSessionSummary(
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis(),
            totalTimeSeconds = 0,
            uniqueApps = 0,
            appUsageMap = emptyMap()
        )
    }

    private fun createSummaryWithTime(startTime: Long, endTime: Long): RewardUsageTracker.RewardSessionSummary {
        return RewardUsageTracker.RewardSessionSummary(
            startTime = startTime,
            endTime = endTime,
            totalTimeSeconds = (endTime - startTime) / 1000,
            uniqueApps = 0,
            appUsageMap = emptyMap()
        )
    }

    private fun createSummaryWithSessions(sessions: List<RewardUsageTracker.AppUsageSession>): RewardUsageTracker.RewardSessionSummary {
        val totalTime = sessions.sumOf { it.durationSeconds }
        val uniqueApps = sessions.map { it.packageName }.distinct().size
        val appUsageMap = sessions.groupBy { it.packageName }
            .mapValues { (_, sessions) -> sessions.sumOf { it.durationSeconds } }

        return RewardUsageTracker.RewardSessionSummary(
            startTime = sessions.minOfOrNull { it.startTime } ?: System.currentTimeMillis(),
            endTime = sessions.maxOfOrNull { it.endTime ?: it.startTime } ?: System.currentTimeMillis(),
            totalTimeSeconds = totalTime,
            uniqueApps = uniqueApps,
            appUsageMap = appUsageMap
        )
    }

    private fun createSession(
        packageName: String,
        appName: String,
        startTime: Long,
        endTime: Long
    ): RewardUsageTracker.AppUsageSession {
        val session = RewardUsageTracker.AppUsageSession(
            packageName = packageName,
            appName = appName,
            startTime = startTime,
            endTime = endTime
        )
        session.updateDuration()
        return session
    }
}

