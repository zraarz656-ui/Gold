package com.tradequest.data

import com.tradequest.engine.Candle
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structural checks must accept a hand-built realistic series and reject each way a
 * generated placeholder goes wrong. Times are UTC; the weekend window is the New York
 * 17:00 rollover, so helper literals below were chosen to land on the right side of it.
 */
class DatasetPlausibilityTest {

    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        java.time.ZonedDateTime.of(y, mo, d, h, mi, 0, 0, java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()

    private fun candle(t: Long, c: Double, pad: Double = 0.5) =
        Candle(t, c, c + pad, c - pad, c, 100.0)

    /**
     * Three UTC weeks with (a) different candle counts per week, (b) a 45-minute weekday
     * gap in each of the first two weeks, and (c) a > 40 h closure over the weekend that
     * starts on a closed minute.
     */
    private fun plausible(): List<Candle> = listOf(
        candle(ts(2023, 11, 6, 10, 0), 2000.0),
        candle(ts(2023, 11, 6, 10, 45), 2001.0),
        candle(ts(2023, 11, 13, 9, 0), 2002.0),
        candle(ts(2023, 11, 13, 10, 0), 2003.0),
        candle(ts(2023, 11, 14, 9, 0), 2004.0),
        candle(ts(2023, 11, 15, 9, 0), 2003.0),
        candle(ts(2023, 11, 17, 22, 30), 2002.0), // Friday 18:30 NY — market closed
        candle(ts(2023, 11, 19, 23, 0), 2005.0),  // Sunday 18:00 NY — market open
        candle(ts(2023, 11, 20, 8, 0), 2006.0),
        candle(ts(2023, 11, 20, 9, 0), 2007.0),
    )

    private fun reason(result: PlausibilityResult): String {
        assertTrue("expected a failure", result is PlausibilityResult.Fail)
        return (result as PlausibilityResult.Fail).reason
    }

    @Test
    fun acceptsRealisticSeries() {
        assertTrue(DatasetPlausibility.check(plausible()) is PlausibilityResult.Pass)
    }

    @Test
    fun rejectsIdenticalWeekCounts() {
        // 5 candles in each of four Monday-UTC weeks: a machine stitch.
        val candles = ArrayList<Candle>()
        var p = 2000.0
        for (w in 0 until 4) for (i in 0 until 5) {
            candles += candle(ts(2023, 11, 6, 9, 0) + (w * 7L + i) * 86_400_000L, p)
            p += 0.1
        }
        assertTrue(reason(DatasetPlausibility.check(candles)).contains("same candle count"))
    }

    @Test
    fun rejectsMissingWeekendClosure() {
        val full = plausible().filterNot { it.ts >= ts(2023, 11, 17, 22, 30) && it.ts < ts(2023, 11, 19, 23, 0) }
        assertTrue(reason(DatasetPlausibility.check(full)).contains("weekend closure"))
    }

    @Test
    fun rejectsNoWeekdayGap() {
        // Two weeks of consecutive minutes at 09:00, but with varying counts so only the
        // weekday-gap check can fail; keep a weekend closure so that check passes.
        val candles = ArrayList<Candle>()
        var p = 2000.0
        for (i in 0 until 5) { candles += candle(ts(2023, 11, 6, 9, 0) + i * 60_000L, p); p += 0.1 }
        for (i in 0 until 7) { candles += candle(ts(2023, 11, 13, 9, 0) + i * 60_000L, p); p += 0.1 }
        candles += candle(ts(2023, 11, 17, 22, 30), p)
        candles += candle(ts(2023, 11, 19, 23, 0), p)
        assertTrue(reason(DatasetPlausibility.check(candles)).contains("weekday gap"))
    }

    @Test
    fun rejectsOhlcOutOfOrder() {
        val bad = plausible().toMutableList()
        bad[1] = Candle(bad[1].ts, 2001.0, 2000.0, 2002.0, 2001.0, 100.0) // high < low
        assertTrue(reason(DatasetPlausibility.check(bad)).contains("OHLC"))
    }

    @Test
    fun rejectsMedianCloseOutOfBand() {
        val low = plausible().map { it.copy(o = 50.0, h = 50.5, l = 49.5, c = 50.0) }
        assertTrue(reason(DatasetPlausibility.check(low)).contains("median close"))
    }
}
