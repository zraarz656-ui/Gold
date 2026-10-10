package com.tradequest.data

import com.tradequest.engine.AccountState
import com.tradequest.engine.Candle
import com.tradequest.engine.FillEngine
import com.tradequest.engine.MarketCalendar
import com.tradequest.engine.Order
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Timing rules for order placement.
 *
 * With the market open a market order fills at once at the displayed price and is stamped
 * at the placement instant — it never waits for the next candle. Limit, stop, SL and TP
 * still wait for a later candle. With the market shut a market order is QUEUED and fills at
 * the first candle after the reopen. No fill is ever stamped before its placement.
 */
@RunWith(RobolectricTestRunner::class)
class OrderTimingTest {

    private val ts = 1_700_000_000_000L
    private val min = 60_000L
    private val candle = Candle(ts, 2400.0, 2401.0, 2399.0, 2400.5, 100.0)

    // Saturday noon UTC — inside the weekend shutdown.
    private val closedAt = 1_790_424_000_000L

    // The upcoming weekly reopen (Sunday 17:00 New York), derived from the calendar.
    private val reopen = MarketCalendar.nextOpen(closedAt)

    @Test
    fun marketOrderPlacedWhileOpenFillsAtTheAskAtOnce() = runTest {
        val db = TestDb.open()
        val start = ts - ts % min
        db.candleDao().insertAll(TestDb.candles(5, start))
        val season = TestDb.seedSeason(db, start)
        val placedAt = start + 2 * min

        TradingRepository(db).place(season.id, OrderRequest(OrderType.MARKET, 0.1, side = Side.LONG), placedAt)

        val rows = db.tradeOrderDao().live(season.id)
        assertEquals(1, rows.size)
        val position = rows.single()
        assertEquals(OrderStatus.OPEN, position.status)
        assertEquals(placedAt, position.openedAt)
        assertEquals(2400.30, position.entryPrice!!, 1e-9) // ask
        db.close()
    }

    @Test
    fun aMarketOrderPlacedWhileClosedIsQueuedThenFillsAtTheReopen() = runTest {
        val db = TestDb.open()
        db.candleDao().insertAll(TestDb.candles(5, reopen))
        val season = TestDb.seedSeason(db, reopen)
        assertTrue("precondition: the market is shut at placement", MarketCalendar.isClosed(closedAt))

        TradingRepository(db).place(season.id, OrderRequest(OrderType.MARKET, 0.1, side = Side.LONG), closedAt)

        val queued = db.tradeOrderDao().live(season.id).single()
        assertEquals(OrderStatus.QUEUED, queued.status)
        assertEquals(closedAt, queued.openedAt)

        // The reopen's first candle is the only thing that fills it.
        CatchUpProcessor(db, batchSize = 500).run(season.id, reopen + min)
        val state = AccountCheckpoint.decode(db.settingsDao().get(AccountCheckpoint.KEY), 0.0)
        assertEquals(1, state.positions.size)
        assertEquals(reopen, state.positions.single().openedAtTs)
        assertTrue(state.orders.isEmpty())
        db.close()
    }

    @Test
    fun aLimitOrderStillWaitsForTheNextCandle() = runTest {
        val order = Order(1, Side.LONG, OrderType.BUY_LIMIT, 0.1, price = 2400.0, placedAtTs = ts)
        val state = AccountState(balance = 10_000.0, orders = listOf(order))

        val same = FillEngine.processCandle(state, candle, emptyList())
        assertTrue("a limit order never fills on its own candle", same.fills.isEmpty())

        val next = Candle(ts + min, 2399.5, 2400.5, 2399.0, 2400.2, 100.0)
        val later = FillEngine.processCandle(same.state, next, emptyList())
        assertEquals(1, later.fills.size)
    }

    /**
     * The bug: a market order placed inside a candle filled with a time earlier than its
     * placement. A position id equals the order id that opened it, so every open position
     * must be stamped at or after its own order's placement. Checked for one pass and for
     * interrupted passes.
     */
    @Test
    fun noFillIsEverStampedBeforeItsPlacement() = runTest {
        val start = ts - ts % min
        // Halfway through the candle that opens at start + 4 min (market open).
        val marketPlacedAt = start + 4 * min + 30_000L
        val limitPlacedAt = start - min

        // One uninterrupted pass.
        val onePassDb = TestDb.open()
        onePassDb.candleDao().insertAll(TestDb.candles(20, start))
        val oneSeason = TestDb.seedSeason(onePassDb, start)
        val marketId = TradingRepository(onePassDb).place(
            oneSeason.id, OrderRequest(OrderType.MARKET, 0.1, side = Side.LONG), marketPlacedAt,
        )
        TradingRepository(onePassDb).place(
            oneSeason.id, OrderRequest(OrderType.BUY_LIMIT, 0.1, price = 2400.0), limitPlacedAt,
        )
        CatchUpProcessor(onePassDb, batchSize = 500).run(oneSeason.id, start + 20 * min)
        val onePass = openTimesById(onePassDb, oneSeason.id)
        onePassDb.close()

        // The same window split across interrupted passes.
        val db = TestDb.open()
        db.candleDao().insertAll(TestDb.candles(20, start))
        val season = TestDb.seedSeason(db, start)
        TradingRepository(db).place(season.id, OrderRequest(OrderType.MARKET, 0.1, side = Side.LONG), marketPlacedAt)
        TradingRepository(db).place(season.id, OrderRequest(OrderType.BUY_LIMIT, 0.1, price = 2400.0), limitPlacedAt)
        CatchUpProcessor(db, batchSize = 3).run(season.id, start + 7 * min)
        CatchUpProcessor(db, batchSize = 3).run(season.id, start + 20 * min)
        val interrupted = openTimesById(db, season.id)
        db.close()

        assertEquals("interrupted passes reproduce the one-pass result", onePass, interrupted)
        assertEquals(marketPlacedAt, onePass[marketId])
        assertEquals("the market order is stamped exactly at placement", marketPlacedAt, onePass[marketId])
        onePass.forEach { (id, openedAt) ->
            val placedAt = if (id == marketId) marketPlacedAt else limitPlacedAt
            assertTrue("position $id opened before its order was placed", openedAt >= placedAt)
        }
    }

    @Test
    fun reprocessingTheSameCandlesCreatesNoExtraFills() = runTest {
        val db = TestDb.open()
        val start = ts - ts % min
        db.candleDao().insertAll(TestDb.candles(20, start))
        val season = TestDb.seedSeason(db, start)
        val placedAt = start + 2 * min
        TradingRepository(db).place(season.id, OrderRequest(OrderType.MARKET, 0.1, side = Side.LONG), placedAt)
        val processor = CatchUpProcessor(db, batchSize = 5)

        val histNow = start + 20 * min
        processor.run(season.id, histNow)
        assertEquals(1, db.tradeOrderDao().bySeason(season.id).count { it.status == OrderStatus.OPEN })

        // A second run over the same window has nothing to fold in: no extra fills.
        val second = processor.run(season.id, histNow)
        assertTrue("no candle is replayed twice", second.isEmpty)
        assertEquals(1, db.tradeOrderDao().bySeason(season.id).count { it.status == OrderStatus.OPEN })
        db.close()
    }

    /** positionId -> openedAtTs for every open position in the persisted engine state. */
    private suspend fun openTimesById(db: TradeQuestDatabase, seasonId: Long): Map<Long, Long> {
        val state = AccountCheckpoint.decode(db.settingsDao().get(AccountCheckpoint.KEY), 0.0)
        return state.positions.associate { it.id to it.openedAtTs }
    }
}
