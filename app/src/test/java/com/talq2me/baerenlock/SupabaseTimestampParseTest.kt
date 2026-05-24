package com.talq2me.baerenlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class SupabaseTimestampParseTest {

    private val toronto = ZoneId.of("America/Toronto")
    private val wallFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @Test
    fun `toronto wall clock parses as America Toronto not UTC`() {
        val zdt = ZonedDateTime.of(2026, 5, 23, 10, 26, 0, 0, toronto)
        val wall = zdt.format(wallFormatter)
        val expectedMs = zdt.toInstant().toEpochMilli()
        assertEquals(expectedMs, SupabaseInterface.parseTimestampForComparison(wall))
    }

    @Test
    fun `UTC Z is not reinterpreted as Toronto wall clock`() {
        val utcInstant = "2026-05-23T14:26:00.000Z"
        val correctMs = Instant.parse(utcInstant).toEpochMilli()
        val wrongAsTorontoMs = SupabaseInterface.parseTimestampForComparison(
            "2026-05-23 14:26:00.000"
        )
        assertEquals(correctMs, SupabaseInterface.parseTimestampForComparison(utcInstant))
        assertTrue(
            "Stripping Z and treating digits as Toronto inflates expiry",
            wrongAsTorontoMs > correctMs + 3 * 60 * 60 * 1000L
        )
    }

    @Test
    fun `offset suffix is honored`() {
        val withOffset = "2026-01-21T14:40:53.024-05:00"
        val expected = Instant.parse(withOffset).toEpochMilli()
        assertEquals(expected, SupabaseInterface.parseTimestampForComparison(withOffset))
    }

    @Test
    fun `twenty six minutes from now wall clock yields about twenty six minutes remaining`() {
        val nowToronto = ZonedDateTime.now(toronto)
        val expiryToronto = nowToronto.plusMinutes(26)
        val wall = expiryToronto.format(wallFormatter)
        val remainingMs = SupabaseInterface.parseTimestampForComparison(wall) - System.currentTimeMillis()
        val remainingMin = ((remainingMs + 59_999L) / 60_000L).toInt()
        assertTrue(remainingMin in 25..27)
    }
}
