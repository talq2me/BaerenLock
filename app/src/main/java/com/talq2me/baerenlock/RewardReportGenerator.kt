package com.talq2me.baerenlock

import java.text.SimpleDateFormat
import java.util.*

/**
 * Generates reward time usage reports
 */
class RewardReportGenerator {
    
    /**
     * Generates a text report of reward time app usage
     */
    fun generateReport(
        sessions: List<RewardUsageTracker.AppUsageSession>,
        summary: RewardUsageTracker.RewardSessionSummary,
        childName: String = "Child"
    ): String {
        val dateFormat = SimpleDateFormat("MMMM dd, yyyy 'at' HH:mm", Locale.getDefault())
        val startDate = Date(summary.startTime)
        val endDate = Date(summary.endTime)
        
        return buildString {
            appendLine("🎮 REWARD TIME USAGE REPORT")
            appendLine("=".repeat(50))
            appendLine("Child: $childName")
            appendLine("Date: ${SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(startDate)}")
            appendLine("Session Start: ${dateFormat.format(startDate)}")
            appendLine("Session End: ${dateFormat.format(endDate)}")
            appendLine("Total Reward Time: ${summary.formattedTotalTime}")
            appendLine()
            
            if (sessions.isEmpty()) {
                appendLine("No apps were used during this reward session.")
                appendLine()
            } else {
                appendLine("📱 APP USAGE SUMMARY")
                appendLine("-".repeat(30))
                appendLine("Total Apps Used: ${summary.uniqueApps}")
                appendLine()
                
                // Group sessions by app and calculate total time per app
                val appTotals = sessions.groupBy { it.packageName }
                    .mapValues { (_, sessions) ->
                        sessions.sumOf { it.durationSeconds }
                    }
                    .toList()
                    .sortedByDescending { it.second }
                
                appendLine("📊 APPS BY USAGE TIME")
                appendLine("-".repeat(30))
                appTotals.forEachIndexed { index, (packageName, totalSeconds) ->
                    val appName = sessions.firstOrNull { it.packageName == packageName }?.appName ?: packageName
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    val formattedTime = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
                    val percentage = if (summary.totalTimeSeconds > 0) {
                        "%.1f".format((totalSeconds * 100.0) / summary.totalTimeSeconds)
                    } else {
                        "0.0"
                    }
                    appendLine("${index + 1}. $appName: $formattedTime ($percentage%)")
                }
                appendLine()
                
                appendLine("📝 DETAILED SESSION LOG")
                appendLine("-".repeat(30))
                sessions.forEachIndexed { index, session ->
                    val sessionStart = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(session.startTime))
                    val sessionEnd = session.endTime?.let {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                    } ?: "Ongoing"
                    appendLine("Session ${index + 1}: ${session.appName}")
                    appendLine("  Time: $sessionStart - $sessionEnd")
                    appendLine("  Duration: ${session.formattedDuration}")
                    appendLine()
                }
            }
            
            appendLine("💡 SUMMARY")
            appendLine("-".repeat(30))
            if (summary.totalTimeSeconds > 0) {
                appendLine("Total reward time used: ${summary.formattedTotalTime}")
                appendLine("Number of apps used: ${summary.uniqueApps}")
                if (summary.uniqueApps > 0) {
                    val avgTimePerApp = summary.totalTimeSeconds / summary.uniqueApps
                    val avgMinutes = avgTimePerApp / 60
                    val avgSeconds = avgTimePerApp % 60
                    val avgFormatted = if (avgMinutes > 0) "${avgMinutes}m ${avgSeconds}s" else "${avgSeconds}s"
                    appendLine("Average time per app: $avgFormatted")
                }
            } else {
                appendLine("No reward time was used.")
            }
        }
    }
}

