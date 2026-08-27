package com.talq2me.baerenlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManifestTest {

    @Test
    fun manifestJson_containsExpectedReleaseUrlFormat() {
        val json = """
            {
                "package": "com.talq2me.baerened",
                "latestVersionCode": 164,
                "apkUrl": "https://github.com/talq2me/BaerenEd-Android-App/releases/download/v164/app-release.apk"
            }
        """.trimIndent()
        assertTrue(json.contains("\"package\": \"com.talq2me.baerened\""))
        assertTrue(json.contains("\"latestVersionCode\": 164"))
        assertEquals(
            "https://github.com/talq2me/BaerenEd-Android-App/releases/download/v164/app-release.apk",
            extractJsonString(json, "apkUrl")
        )
    }

    @Test
    fun baerenLockManifestJson_matchesReleaseUrlPattern() {
        val json = """
            {
                "package": "com.talq2me.baerenlock",
                "latestVersionCode": 73,
                "apkUrl": "https://github.com/talq2me/BaerenLock/releases/download/v73/app-release.apk"
            }
        """.trimIndent()
        assertTrue(json.contains("releases/download/v73/app-release.apk"))
        assertEquals("com.talq2me.baerenlock", extractJsonString(json, "package"))
    }

    private fun extractJsonString(json: String, key: String): String {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Missing key $key")
    }
}
