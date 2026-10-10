package com.tradequest.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Data panel aggregates: counts, range, and weekday-only gap detection. */
@RunWith(RobolectricTestRunner::class)
class DatabaseIntrospectorTest {

    private val min = 60_000L
    private val base = 1_700_000_000_000L // a Tuesday 22:13 UTC, mid-week

    @Test
    fun reportsCountsRangeAndNoGapsForAContiguousRun() = runTest {
        val db = TestDb.open()
        db.candleDao().insertAll(TestDb.candles(count = 100, startTs = base))
        db.newsDao().insertAll(
            listOf(NewsEntity(ts = base, title = "NFP", impact = com.tradequest.engine.Impact.HIGH)),
        )

        val s = DatabaseIntrospector.summary(db)
        assertEquals(100L, s.candleCount)
        assertEquals(base, s.firstTs)
        assertEquals(base + 99 * min, s.lastTs)
        assertEquals(1L, s.newsCount)
        assertEquals(0, s.gaps.size)
        assertEquals(0, DatabaseIntrospector.weekdayGaps(s).size)
        // Close stats mirror the inserted series (first/last exact; min/median/max ordered).
        val closes = TestDb.candles(count = 100, startTs = base).map { it.c }
        assertEquals(closes.first(), s.closeFirst, 0.0)
        assertEquals(closes.last(), s.closeLast, 0.0)
        assertEquals(closes.min(), s.closeMin, 0.0)
        assertEquals(closes.max(), s.closeMax, 0.0)
        // Even count: median is the mean of the two middle values.
        val sorted = closes.sorted()
        assertEquals((sorted[49] + sorted[50]) / 2.0, s.closeMedian, 0.0)
        db.close()
    }

    @Test
    fun flagsWeekdayGapsButNotWeekendGaps() = runTest {
        val db = TestDb.open()
        // Contiguous run, then a 2-hour hole in the middle of a weekday: reported.
        val first = TestDb.candles(count = 10, startTs = base)
        val second = TestDb.candles(count = 10, startTs = base + 10 * min + 120 * min)
        db.candleDao().insertAll(first + second)

        val s = DatabaseIntrospector.summary(db)
        assertEquals(1, s.gaps.size)
        assertEquals(121L, s.gaps.first().minutes)
        assertEquals(1, DatabaseIntrospector.weekdayGaps(s).size)
        db.close()
    }

    @Test
    fun weekendShutdownGapIsNotAWeekdayGap() = runTest {
        val db = TestDb.open()
        // Friday 22:30 UTC → Monday 01:00 UTC is a weekend shutdown (inside the Fri-17:00
        // to Sun-17:00 NY closure), not a data gap.
        val friday = 1_700_260_200_000L // 2023-11-17T22:30:00Z (Friday)
        val monday = 1_700_442_000_000L // 2023-11-20T01:00:00Z (Monday)
        db.candleDao().insertAll(
            listOf(
                Candle1m(friday, 1.0, 1.0, 1.0, 1.0, 1.0),
                Candle1m(monday, 1.0, 1.0, 1.0, 1.0, 1.0),
            ),
        )
        val s = DatabaseIntrospector.summary(db)
        assertEquals(1, s.gaps.size) // the table-level gap exists
        assertEquals(0, DatabaseIntrospector.weekdayGaps(s).size) // but it is the weekend
        db.close()
    }

    @Test
    fun emptyDatabaseReportsZeroes() = runTest {
        val db = TestDb.open()
        val s = DatabaseIntrospector.summary(db)
        assertEquals(0L, s.candleCount)
        assertEquals(0L, s.firstTs)
        assertEquals(0L, s.lastTs)
        assertEquals(0, s.gaps.size)
        db.close()
    }
}
