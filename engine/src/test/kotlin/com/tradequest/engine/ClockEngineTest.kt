package com.tradequest.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

/** Unit helpers shared by the test suites. */
object TestSupport {

    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR

    /** UTC epoch ms for a New York local date-time. */
    fun ny(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, MarketTime.NEW_YORK).toInstant().toEpochMilli()

    /** UTC epoch ms for a UTC date-time. */
    fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    fun candle(ts: Long, o: Double, h: Double, l: Double, c: Double, v: Double = 1.0): Candle =
        Candle(ts, o, h, l, c, v)

    /** Build a contiguous run of 1-minute candles from a list of closes. */
    fun run(startTs: Long, closes: List<Double>): List<Candle> =
        closes.mapIndexed { i, c ->
            candle(startTs + i * MIN, c, c + 0.5, c - 0.5, c)
        }
}

class ClockEngineTest {

    private val MIN = TestSupport.MIN
    private val WEEK = 7 * 24 * 60 * MIN

    @Test
    fun `histNow lands in second week of dataset`() {
        val start = TestSupport.utc(2024, 1, 8, 0, 0)
        val rnd = Random(42)
        repeat(2000) {
            val realNow = start + rnd.nextLong(0, 400L * 24 * 60 * MIN)
            val offset = ClockEngine.computeOffset(realNow, start)
            val hist = ClockEngine.histNow(realNow, offset)
            assertTrue(
                hist >= start + WEEK && hist < start + 2 * WEEK,
                "hist $hist not in [${start + WEEK}, ${start + 2 * WEEK})",
            )
        }
    }

    @Test
    fun `offset is always a multiple of one week`() {
        val start = TestSupport.utc(2024, 3, 4, 10, 0)
        val rnd = Random(7)
        repeat(2000) {
            val realNow = start + rnd.nextLong(0, 500L * 24 * 60 * MIN)
            val offset = ClockEngine.computeOffset(realNow, start)
            assertEquals(0L, offset % WEEK, "offset not a multiple of a week")
            val hist = ClockEngine.histNow(realNow, offset)
            assertTrue(
                hist >= start + WEEK && hist < start + 2 * WEEK,
                "hist $hist not in the second week",
            )
        }
    }

    @Test
    fun `offset is non-negative for realistic realNow beyond the second week`() {
        val start = TestSupport.utc(2024, 3, 4, 10, 0)
        val rnd = Random(11)
        repeat(2000) {
            val realNow = start + 2 * WEEK + rnd.nextLong(0, 500L * 24 * 60 * MIN)
            assertTrue(ClockEngine.computeOffset(realNow, start) >= 0)
        }
    }

    @Test
    fun `weekday and time of day are preserved exactly`() {
        val start = TestSupport.utc(2024, 6, 3, 0, 0)
        val rnd = Random(99)
        repeat(1000) {
            val realNow = start + rnd.nextLong(0, 900L * 24 * 60 * MIN)
            val offset = ClockEngine.computeOffset(realNow, start)
            val hist = ClockEngine.histNow(realNow, offset)

            // Whole weeks preserve the UTC weekday and time of day.
            val realZ = Instant.ofEpochMilli(realNow).atZone(ZoneOffset.UTC)
            val histZ = Instant.ofEpochMilli(hist).atZone(ZoneOffset.UTC)
            assertEquals(realZ.dayOfWeek, histZ.dayOfWeek)
            assertEquals(realZ.toLocalTime(), histZ.toLocalTime())
        }
    }

    @Test
    fun `last visible candle is the prior completed minute`() {
        val t = TestSupport.utc(2024, 1, 10, 12, 0) + 23_000 // 12:00:23
        assertEquals(TestSupport.utc(2024, 1, 10, 11, 59), ClockEngine.lastVisibleCandleTs(t))
    }

    @Test
    fun `season is over once the last candle is passed`() {
        val last = TestSupport.utc(2024, 1, 10, 12, 0)
        assertFalse(ClockEngine.isSeasonOver(last - 5 * MIN, last))
        assertFalse(ClockEngine.isSeasonOver(last, last))
        assertTrue(ClockEngine.isSeasonOver(last + 2 * MIN, last))
    }
}
