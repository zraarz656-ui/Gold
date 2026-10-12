package com.tradequest.data

import com.tradequest.engine.ClockEngine
import com.tradequest.engine.OrderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Debug time-travel only shifts the season's clock offset; running the normal catch-up
 * afterwards must reproduce the state of replaying the same candles in one pass.
 */
@RunWith(RobolectricTestRunner::class)
class SeasonTimeTravelTest {

    // Minute-aligned so candle timestamps line up with `lastVisibleCandleTs`.
    private val start = 1_700_000_040_000L
    private val duration = 300L * 60_000L // five hours of replay
    private val now = start + duration

    /** A season whose replayed clock starts exactly at [start] using the test's real clock. */
    private fun seasonAtStart(id: Long = 0) = Season(
        id = id,
        startedAt = now,
        offsetMs = now - start,
        startBalance = 10_000.0,
        status = SeasonStatus.ACTIVE,
        lastProcessedTs = start - 60_000L,
    )

    @Test
    fun timeTravelThenCatchUpMatchesOnePass() = runTest {
        // One uninterrupted pass straight to the final clock.
        val onePassDb = TestDb.open()
        onePassDb.candleDao().insertAll(TestDb.candles(1440, start))
        val onePassId = onePassDb.seasonDao().insert(seasonAtStart())
        TradingRepository(onePassDb).place(onePassId, OrderRequest(OrderType.MARKET, 0.2), start - 60_000L)
        CatchUpProcessor(onePassDb, batchSize = 60).run(onePassId, now)
        val expected = snapshotOf(onePassDb, onePassId)
        onePassDb.close()

        // The same window, reached by stepping the clock forward one hop at a time. Each
        // hop only moves the offset; catch-up replays the newly visible candles.
        val db = TestDb.open()
        db.candleDao().insertAll(TestDb.candles(1440, start))
        val seasonId = db.seasonDao().insert(seasonAtStart())
        TradingRepository(db).place(seasonId, OrderRequest(OrderType.MARKET, 0.2), start - 60_000L)

        val repository = SeasonRepository(db) { now }
        var hops = 0
        while (repository.histNow(db.seasonDao().byId(seasonId)!!) < now) {
            val updated = repository.timeTravel(seasonId, 60)
            assertNotNull("time travel should still advance", updated)
            CatchUpProcessor(db, batchSize = 7).run(seasonId, repository.histNow(updated!!))
            hops++
        }
        assertEquals("expected one hop per hour", 5, hops)
        val actual = snapshotOf(db, seasonId)
        db.close()

        assertEquals(expected, actual)
    }

    @Test
    fun timeTravelNeverMovesPastTheLastCandle() = runTest {
        val candles = TestDb.candles(120, start) // last candle is start + 119 min
        val db = TestDb.open()
        db.candleDao().insertAll(candles)
        val seasonId = db.seasonDao().insert(seasonAtStart())
        val lastCandleTs = candles.last().ts

        // A huge hop overshoots the dataset, so the clock must clamp to the last candle.
        val repository = SeasonRepository(db) { now }
        val updated = repository.timeTravel(seasonId, 100_000L)!!

        val histNow = repository.histNow(updated)
        assertEquals(lastCandleTs, ClockEngine.lastVisibleCandleTs(histNow))

        // Nothing left to advance into -> null, and the clock is unchanged.
        assertNull(repository.timeTravel(seasonId, 60))
        assertEquals(histNow, repository.histNow(db.seasonDao().byId(seasonId)!!))
        db.close()
    }

    @Test
    fun timeTravelDoesNotTouchCandlesOrTrades() = runTest {
        val db = TestDb.open()
        val candles = TestDb.candles(120, start)
        db.candleDao().insertAll(candles)
        val seasonId = db.seasonDao().insert(seasonAtStart())
        val repository = SeasonRepository(db) { now }

        val before = db.candleDao().count()
        val updated = repository.timeTravel(seasonId, 10)!!
        // Only the offset changed on the same season row; no candle or trade data is written.
        assertEquals(db.candleDao().count(), before)
        assertTrue(db.tradeOrderDao().bySeason(seasonId).isEmpty())
        assertEquals(seasonId, updated.id)
        db.close()
    }

    private suspend fun snapshotOf(db: TradeQuestDatabase, seasonId: Long): Snapshot = Snapshot(
        lastProcessedTs = db.seasonDao().byId(seasonId)!!.lastProcessedTs,
        checkpoint = db.settingsDao().get(AccountCheckpoint.KEY) ?: "",
        rows = db.tradeOrderDao().bySeason(seasonId).sortedBy { it.id },
        closed = db.tradeOrderDao().closed(seasonId),
    )

    private data class Snapshot(
        val lastProcessedTs: Long,
        val checkpoint: String,
        val rows: List<TradeOrder>,
        val closed: List<TradeOrder>,
    )
}
