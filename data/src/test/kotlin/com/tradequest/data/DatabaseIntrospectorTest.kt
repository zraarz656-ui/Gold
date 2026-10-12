package com.tradequest.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Data panel aggregates: counts, range, and gap classification (normal vs unexpected). */
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
        assertEquals(0, s.unexpectedGaps.size)
        assertEquals(0, s.normalGapCount)
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
    fun shortWeekdayHoleIsUnexpected() {
        // 2026-09-07 19:28 → 23:00 UTC, a 212-minute Labor Day hole: not the daily break
        // (too long) and not a full weekend window.
        val g = DatabaseIntrospector.classify(1_788_809_280_000L, 1_788_822_000_000L)
        assertEquals(GapKind.UNEXPECTED, g.kind)
        assertEquals(212L, g.minutes)
    }

    @Test
    fun dailyRolloverBreakIsNormal() {
        // 2026-07-01 21:59 → 23:00 UTC: a ~61-minute New York-rollover break.
        val g = DatabaseIntrospector.classify(1_782_943_140_000L, 1_782_946_800_000L)
        assertEquals(GapKind.DAILY_BREAK, g.kind)
        assertEquals(61L, g.minutes)
    }

    @Test
    fun weekendShutdownIsNormal() {
        // 2026-07-10 21:59 → 2026-07-12 23:00 UTC: Friday rollover to Sunday re-open.
        val g = DatabaseIntrospector.classify(1_783_720_740_000L, 1_783_897_200_000L)
        assertEquals(GapKind.WEEKEND, g.kind)
    }

    @Test
    fun holidayLengthenedWeekendIsUnexpected() {
        // 2026-07-03 17:58 → 2026-07-05 23:00 UTC: the weekend plus the July-4 close.
        val g = DatabaseIntrospector.classify(1_783_101_480_000L, 1_783_292_400_000L)
        assertEquals(GapKind.UNEXPECTED, g.kind)
        assertEquals(3182L, g.minutes)
    }

    @Test
    fun longSep25HoleIsUnexpected() {
        // 2026-09-25 00:59 → 2026-09-27 23:00 UTC, a 70-hour hole.
        val g = DatabaseIntrospector.classify(1_790_297_940_000L, 1_790_550_000_000L)
        assertEquals(GapKind.UNEXPECTED, g.kind)
        assertEquals(4201L, g.minutes)
        assertEquals("70h 01m", g.length)
    }

    @Test
    fun summarySplitsNormalAndUnexpectedGaps() = runTest {
        val db = TestDb.open()
        // A 61-minute daily break, then a real 4-hour hole: one of each.
        val a = TestDb.candles(count = 10, startTs = base)
        val b = TestDb.candles(count = 10, startTs = base + 10 * min + 60 * min)
        val c = TestDb.candles(count = 10, startTs = base + 80 * min + 240 * min)
        db.candleDao().insertAll(a + b + c)

        val s = DatabaseIntrospector.summary(db)
        assertEquals(1, s.normalGapCount)
        assertEquals(1, s.unexpectedGaps.size)
        assertEquals(241L, s.unexpectedGaps.first().minutes)
        db.close()
    }

    @Test
    fun emptyDatabaseReportsZeroes() = runTest {
        val db = TestDb.open()
        val s = DatabaseIntrospector.summary(db)
        assertEquals(0L, s.candleCount)
        assertEquals(0L, s.firstTs)
        assertEquals(0L, s.lastTs)
        assertEquals(0, s.unexpectedGaps.size)
        assertEquals(0, s.normalGapCount)
        db.close()
    }
}
