package com.tradequest.data

import com.tradequest.engine.ClockEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The chart must never be able to read a candle from the future of the replayed clock. */
@RunWith(RobolectricTestRunner::class)
class CandleRepositoryTest {

    // Minute-aligned, like the real imported dataset.
    private val start = 1_700_000_000_000L - 1_700_000_000_000L % 60_000L

    @Test
    fun upToNeverReturnsACandleAfterHistNow() = runTest {
        val db = TestDb.open()
        db.candleDao().insertAll(TestDb.candles(100, start))
        val repo = CandleRepository(db)

        val histNow = start + 30 * 60_000L + 25_000L
        val window = repo.upTo(histNow)

        assertTrue(window.isNotEmpty())
        assertTrue("no future candle", window.all { it.ts <= histNow })
        assertEquals(ClockEngine.lastVisibleCandleTs(histNow), window.last().ts)
        db.close()
    }

    @Test
    fun latestClosedIsTheLastFullyClosedCandle() = runTest {
        val db = TestDb.open()
        db.candleDao().insertAll(TestDb.candles(50, start))
        val repo = CandleRepository(db)

        // Mid-way through the 10th minute: the 9th minute candle is the last closed one.
        val histNow = start + 10 * 60_000L + 12_000L
        val latest = repo.latestClosed(histNow)

        assertEquals(start + 9 * 60_000L, latest?.ts)
        assertTrue(latest!!.ts < histNow)
        db.close()
    }

    /**
     * The ticker is null until the first candle is published, so a collector's first emission
     * is a real tick. A 0 seed that had to be filtered could swallow the first boundary tick
     * after launch and leave `lastTick` stuck at "—".
     */
    @Test
    fun tickerIsNullUntilTheFirstPublishThenMonotonic() = runTest {
        val db = TestDb.open()
        val repo = CandleRepository(db)

        assertEquals(null, repo.ticker.value)

        repo.publish(start + 60_000L)
        assertEquals(start + 60_000L, repo.ticker.value)

        // An older/equal tick is ignored; a newer one advances.
        repo.publish(start)
        assertEquals(start + 60_000L, repo.ticker.value)
        repo.publish(start + 120_000L)
        assertEquals(start + 120_000L, repo.ticker.value)

        db.close()
    }
}
