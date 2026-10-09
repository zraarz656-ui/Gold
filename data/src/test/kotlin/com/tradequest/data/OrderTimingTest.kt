package com.tradequest.data

import com.tradequest.engine.AccountState
import com.tradequest.engine.Candle
import com.tradequest.engine.FillEngine
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
 * An order is placed at the current historical instant and can only be evaluated from
 * the *next* candle onward — never against the candle it was placed on.
 */
@RunWith(RobolectricTestRunner::class)
class OrderTimingTest {

    private val ts = 1_700_000_000_000L
    private val candle = Candle(ts, 2400.0, 2401.0, 2399.0, 2400.5, 100.0)

    @Test
    fun marketOrderPlacedNowDoesNotFillOnItsOwnCandle() {
        val order = Order(
            id = 1, side = Side.LONG, type = OrderType.MARKET, lots = 0.1,
            placedAtTs = ts,
        )
        val state = AccountState(balance = 10_000.0, orders = listOf(order))

        val result = FillEngine.processCandle(state, candle, emptyList())

        assertTrue(result.fills.isEmpty())
        assertEquals(1, result.state.orders.size)
        assertEquals(0, result.state.positions.size)
    }

    @Test
    fun marketOrderFillsOnTheNextCandle() {
        val order = Order(
            id = 1, side = Side.LONG, type = OrderType.MARKET, lots = 0.1,
            placedAtTs = ts,
        )
        val state = AccountState(balance = 10_000.0, orders = listOf(order))
        val next = Candle(ts + 60_000L, 2400.0, 2402.0, 2399.0, 2401.0, 100.0)

        val result = FillEngine.processCandle(state, next, emptyList())

        assertEquals(1, result.fills.size)
        assertEquals(1, result.state.positions.size)
        assertTrue(result.state.orders.isEmpty())
    }

    @Test
    fun orderPlacedViaRepositoryStartsAfterHistNow() = runTest {
        val db = TestDb.open()
        val start = ts
        db.candleDao().insertAll(TestDb.candles(5, start))
        val season = TestDb.seedSeason(db, start)
        val histNow = start + 2 * 60_000L

        TradingRepository(db).place(
            seasonId = season.id,
            request = OrderRequest(type = OrderType.MARKET, lots = 0.05),
            histNow = histNow,
        )
        val row = db.tradeOrderDao().live(season.id).single()
        assertEquals(histNow, row.openedAt)

        CatchUpProcessor(db, batchSize = 2).run(season.id, start + 60 * 60_000L)
        val state = AccountCheckpoint.decode(db.settingsDao().get(AccountCheckpoint.KEY), 0.0)
        assertTrue(state.positions.isEmpty() || state.positions.all { it.openedAtTs > histNow })
        db.close()
    }
}
