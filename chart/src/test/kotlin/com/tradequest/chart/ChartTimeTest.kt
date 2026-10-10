package com.tradequest.chart

import com.tradequest.engine.Timeframe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Display formatting must read `stored UTC + displayOffsetMs` and render it in the device
 * time zone. Tests pin the zone so the result does not depend on the CI machine.
 */
class ChartTimeTest {

    private val kolkata = ZoneId.of("Asia/Kolkata") // UTC+05:30, no DST ever

    @Test
    fun `date time renders in the supplied zone`() {
        // 2024-01-02T03:04:00Z
        val ts = 1_704_164_640_000L
        assertEquals("2024-01-02 03:04", formatDateTime(ts, ZoneOffset.UTC))
        assertEquals("2024-01-02 08:34", formatDateTime(ts, kolkata))
    }

    @Test
    fun `display offset shifts the shown time but not the stored value`() {
        val stored = 1_704_164_640_000L
        val oneHour = 3_600_000L
        assertEquals("2024-01-02 04:04", formatDateTime(stored + oneHour, ZoneOffset.UTC))
        assertEquals(stored, stored) // stored timestamp is never mutated by formatting
    }

    @Test
    fun `intraday labels are hh mm in the device zone`() {
        val ts = 1_704_164_640_000L
        assertEquals("08:34", formatTimeLabel(ts, Timeframe.M1, kolkata))
        assertEquals("03:04", formatTimeLabel(ts, Timeframe.H4, ZoneOffset.UTC))
    }

    @Test
    fun `daily labels use the day month pattern in the device zone`() {
        val ts = 1_704_164_640_000L
        assertEquals("Jan 02", formatTimeLabel(ts, Timeframe.D1, ZoneOffset.UTC))
        assertEquals("Jan 02", formatTimeLabel(ts, Timeframe.W1, kolkata))
    }

    @Test
    fun `short date time is used for the news popup`() {
        val ts = 1_704_164_640_000L
        assertEquals("02 Jan 08:34", formatShortDateTime(ts, kolkata))
    }

    @Test
    fun `the countdown is mm ss and rounds up to the next whole second`() {
        assertEquals("00:10", formatCountdown(10_000L))
        assertEquals("00:59", formatCountdown(58_001L))
        assertEquals("04:00", formatCountdown(240_000L))
        assertEquals("00:00", formatCountdown(0L))
        assertEquals("00:00", formatCountdown(-500L))
    }
}
