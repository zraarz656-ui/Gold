package com.tradequest.data

import com.tradequest.engine.OrderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Catch-up must be deterministic and crash-safe. */
@RunWith(RobolectricTestRunner::class)
class CatchUpProcessorTest {

    private val start = 1_700_000_000_000L

    @Test
    fun onePassAndInterruptedPassesProduceIdenticalState() = runTest {
        val onePass = runScenario(batchSize = 1_000)
        val interrupted = runScenario(batchSize = 1)

        assertEquals(onePass.lastProcessedTs, interrupted.lastProcessedTs)
        assertEquals(onePass.checkpoint, interrupted.checkpoint)
        assertEquals(onePass.rows, interrupted.rows)
        assertEquals(onePass.closed, interrupted.closed)
    }

    @Test
    fun neverProcessesACandleAtOrAfterHistNow() = runTest {
        val db = TestDb.open()
        val candles = TestDb.candles(20, start)
        db.candleDao().insertAll(candles)
        val season = TestDb.seedSeason(db, start)

        val histNow = start + 10 * 60_000L + 30_000L
        val result = CatchUpProcessor(db, batchSize = 4).run(season.id, histNow)

        assertTrue(result.toTs < histNow)
        assertTrue(db.seasonDao().byId(season.id)!!.lastProcessedTs < histNow)
        // Exactly the candles strictly before histNow were folded in.
        assertEquals(11, result.processedCandles)
        db.close()
    }

    @Test
    fun rerunAfterCheckpointIsANoOp() = runTest {
        val db = TestDb.open()
        db.candleDao().insertAll(TestDb.candles(10, start))
        val season = TestDb.seedSeason(db, start)
        val histNow = start + 20 * 60_000L

        val first = CatchUpProcessor(db, batchSize = 3).run(season.id, histNow)
        assertTrue(first.processedCandles > 0)
        val second = CatchUpProcessor(db, batchSize = 3).run(season.id, histNow)
        assertTrue(second.isEmpty)
        db.close()
    }

    @Test
    fun restartingTheProcessMidwayProducesTheSameStateAsOnePass() = runTest {
        // One uninterrupted pass over the whole window.
        val onePassDb = TestDb.open()
        onePassDb.candleDao().insertAll(TestDb.candles(40, start))
        val onePassSeason = TestDb.seedSeason(onePassDb, start)
        TradingRepository(onePassDb).place(onePassSeason.id, OrderRequest(OrderType.MARKET, 0.2), start - 60_000L)
        val fullHistNow = start + 60 * 60_000L
        CatchUpProcessor(onePassDb, batchSize = 7).run(onePassSeason.id, fullHistNow)
        val expected = stateOf(onePassDb, onePassSeason.id)
        onePassDb.close()

        // The same work, split across two "process lifetimes" that each rebuild the
        // processor from the database (nothing is carried in memory).
        val db = TestDb.open()
        db.candleDao().insertAll(TestDb.candles(40, start))
        val season = TestDb.seedSeason(db, start)
        TradingRepository(db).place(season.id, OrderRequest(OrderType.MARKET, 0.2), start - 60_000L)
        val half = start + 20 * 60_000L
        CatchUpProcessor(db, batchSize = 7).run(season.id, half)
        CatchUpProcessor(db, batchSize = 7).run(season.id, fullHistNow)
        val actual = stateOf(db, season.id)
        db.close()

        assertEquals(expected, actual)
    }

    private suspend fun stateOf(db: TradeQuestDatabase, seasonId: Long): Snapshot = Snapshot(
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

    private suspend fun runScenario(batchSize: Int): Snapshot {
        val db = TestDb.open()
        db.candleDao().insertAll(TestDb.candles(30, start))
        val season = TestDb.seedSeason(db, start)

        // A market order placed before the first candle fills on the first candle.
        TradingRepository(db).place(
            seasonId = season.id,
            request = OrderRequest(type = OrderType.MARKET, lots = 0.1),
            histNow = start - 60_000L,
        )

        val histNow = start + 60 * 60_000L
        CatchUpProcessor(db, batchSize = batchSize).run(season.id, histNow)

        val snapshot = Snapshot(
            lastProcessedTs = db.seasonDao().byId(season.id)!!.lastProcessedTs,
            checkpoint = db.settingsDao().get(AccountCheckpoint.KEY) ?: "",
            rows = db.tradeOrderDao().bySeason(season.id).sortedBy { it.id },
            closed = db.tradeOrderDao().closed(season.id),
        )
        db.close()
        return snapshot
    }
}
