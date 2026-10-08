package com.tradequest.engine

import com.tradequest.engine.TestSupport.candle
import com.tradequest.engine.TestSupport.ny
import com.tradequest.engine.TestSupport.utc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AggregatorTest {

    private val MIN = TestSupport.MIN
    private val HOUR = 60 * MIN

    @Test
    fun `m15 aggregates a quarter hour`() {
        val start = utc(2024, 1, 8, 0, 0)
        val m1 = (0 until 40).map { i ->
            candle(start + i * MIN, 100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i, 2.0)
        }
        val m15 = Aggregator.aggregate(m1, Timeframe.M15)

        assertEquals(3, m15.size) // 00:00, 00:15, 00:30
        assertEquals(start, m15[0].ts)
        assertEquals(100.0, m15[0].o)
        assertEquals(101.0 + 14, m15[0].h)
        assertEquals(99.0, m15[0].l)
        assertEquals(100.5 + 14, m15[0].c)
        assertEquals(30.0, m15[0].v)
        assertEquals(start + 15 * MIN, m15[1].ts)
        assertEquals(start + 30 * MIN, m15[2].ts)
    }

    @Test
    fun `h1 aggregates a full hour`() {
        val start = utc(2024, 1, 8, 3, 0)
        val m1 = (0 until 60).map { i -> candle(start + i * MIN, 50.0, 50.0 + i, 40.0, 45.0) }
        val h1 = Aggregator.aggregate(m1, Timeframe.H1)

        assertEquals(1, h1.size)
        assertEquals(start, h1[0].ts)
        assertEquals(50.0, h1[0].o)
        assertEquals(50.0 + 59, h1[0].h)
        assertEquals(40.0, h1[0].l)
        assertEquals(45.0, h1[0].c)
        assertEquals(60.0, h1[0].v)
    }

    @Test
    fun `d1 bucket is anchored at 1700 new york`() {
        val beforeCut = ny(2024, 1, 10, 16, 55)
        val atCut = ny(2024, 1, 10, 17, 0)
        val m1 = listOf(
            candle(beforeCut, 100.0, 101.0, 99.0, 100.0),
            candle(atCut, 100.0, 102.0, 98.0, 101.0),
        )
        val d1 = Aggregator.aggregate(m1, Timeframe.D1)

        assertEquals(2, d1.size)
        assertEquals(ny(2024, 1, 9, 17, 0), d1[0].ts)
        assertEquals(ny(2024, 1, 10, 17, 0), d1[1].ts)
    }

    @Test
    fun `h4 buckets start at 1700 2100 0100 0500 0900 1300`() {
        val expected = listOf(17, 21, 1, 5, 9, 13)
        val dayStart = ny(2024, 1, 10, 17, 0)
        for (h in expected) {
            val day = if (h in listOf(1, 5, 9, 13)) 11 else 10
            val ts = ny(2024, 1, day, h, 0)
            assertEquals(dayStart, MarketCalendar.dayStart(ts), "day for $h")
        }

        val m1 = listOf(
            candle(ny(2024, 1, 10, 17, 30), 1.0, 1.0, 1.0, 1.0),
            candle(ny(2024, 1, 10, 21, 30), 1.0, 1.0, 1.0, 1.0),
            candle(ny(2024, 1, 11, 1, 30), 1.0, 1.0, 1.0, 1.0),
            candle(ny(2024, 1, 11, 5, 30), 1.0, 1.0, 1.0, 1.0),
            candle(ny(2024, 1, 11, 9, 30), 1.0, 1.0, 1.0, 1.0),
            candle(ny(2024, 1, 11, 13, 30), 1.0, 1.0, 1.0, 1.0),
        )
        val h4 = Aggregator.aggregate(m1, Timeframe.H4)
        assertEquals(6, h4.size)
        assertEquals(listOf(17, 21, 1, 5, 9, 13), h4.map { c ->
            java.time.Instant.ofEpochMilli(c.ts).atZone(MarketTime.NEW_YORK).hour
        })
    }

    @Test
    fun `weekend gap produces no empty buckets`() {
        val start = ny(2024, 1, 12, 22, 0) // Friday evening
        val closes = (0 until 30).map { 100.0 + it * 0.01 }
        val friday = TestSupport.run(start, closes)
        // Market closed over the weekend; resume Sunday 18:00 NY.
        val sundayStart = ny(2024, 1, 14, 18, 0)
        val sunday = TestSupport.run(sundayStart, closes)

        val h1 = Aggregator.aggregate(friday + sunday, Timeframe.H1)
        assertEquals(2, h1.size)
        assertEquals(MarketTime.floorTo(start, HOUR), h1[0].ts)
        assertEquals(sundayStart, h1[1].ts)
        assertTrue(h1.all { it.v > 0.0 })
    }

    @Test
    fun `d1 respects dst transition in march`() {
        // Before DST (EST): 17:00 NY == 22:00 UTC.
        val winter = utc(2024, 3, 5, 22, 30)
        assertEquals(utc(2024, 3, 5, 22, 0), MarketCalendar.dayStart(winter))
        // After DST starts (EDT): 17:00 NY == 21:00 UTC.
        val summer = utc(2024, 3, 12, 21, 30)
        assertEquals(utc(2024, 3, 12, 21, 0), MarketCalendar.dayStart(summer))
    }

    @Test
    fun `d1 respects dst transition in november`() {
        // Before DST ends (EDT): 17:00 NY == 21:00 UTC.
        val dst = utc(2024, 10, 29, 21, 30)
        assertEquals(utc(2024, 10, 29, 21, 0), MarketCalendar.dayStart(dst))
        // After DST ends (EST): 17:00 NY == 22:00 UTC.
        val std = utc(2024, 11, 5, 22, 30)
        assertEquals(utc(2024, 11, 5, 22, 0), MarketCalendar.dayStart(std))
    }

    @Test
    fun `w1 opens sunday 1700 new york`() {
        val weekStart = ny(2024, 1, 7, 17, 0) // Sunday
        assertEquals(weekStart, MarketCalendar.weekStart(ny(2024, 1, 8, 10, 0)))
        assertEquals(weekStart, MarketCalendar.weekStart(ny(2024, 1, 12, 15, 0)))
        assertEquals(ny(2023, 12, 31, 17, 0), MarketCalendar.weekStart(ny(2024, 1, 7, 16, 0)))
        assertTrue(MarketCalendar.weekStart(ny(2024, 1, 7, 17, 0)) == weekStart)
    }

    @Test
    fun `incremental equals full re-aggregation for every timeframe`() {
        val start = utc(2024, 1, 8, 0, 0)
        val m1 = (0 until 600).map { i ->
            val base = 2000.0 + kotlin.math.sin(i / 7.0) * 5
            candle(start + i * MIN, base, base + 1.0, base - 1.0, base + 0.2, i.toDouble())
        }
        for (tf in Timeframe.entries) {
            val full = Aggregator.aggregate(m1, tf)
            var inc = emptyList<Candle>()
            for (c in m1) inc = Aggregator.updateForming(inc, c, tf)
            assertEquals(full, inc, "mismatch for $tf")
        }
    }
}
