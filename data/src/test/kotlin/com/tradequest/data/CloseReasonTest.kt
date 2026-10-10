package com.tradequest.data

import com.tradequest.engine.AccountState
import com.tradequest.engine.Candle
import com.tradequest.engine.CloseReason
import com.tradequest.engine.FillEngine
import com.tradequest.engine.Order
import com.tradequest.engine.OrderType
import com.tradequest.engine.Position
import com.tradequest.engine.Side
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every closed trade records why it closed and, for a level-triggered close, the level that
 * fired. The reason is written to `trade_order` and rendered by the Closed trades list.
 */
@RunWith(RobolectricTestRunner::class)
class CloseReasonTest {

    private val ts = 1_700_000_000_000L
    private val min = 60_000L

    private fun stateWith(pos: Position) = AccountState(
        balance = 10_000.0,
        positions = listOf(pos),
        nextPositionId = pos.id + 1,
    )

    private fun longPosition(sl: Double? = null, tp: Double? = null) = Position(
        id = 1L, side = Side.LONG, lots = 0.10, entryPrice = 2400.0, openedAtTs = ts,
        sl = sl, tp = tp,
    )

    @Test
    fun aLongClosedBySlRecordsTheSlReasonAndTheStopLevel() {
        val pos = longPosition(sl = 2390.0)
        // Opens above the stop, then trades through it.
        val candle = Candle(ts + min, 2400.0, 2401.0, 2385.0, 2395.0, 100.0)
        val result = FillEngine.processCandle(stateWith(pos), candle, emptyList())

        val closed = result.closed.single()
        assertEquals(CloseReason.SL, closed.reason)
        assertEquals(2390.0, closed.triggerPrice!!, 1e-9)

        val row = TradeProjection.closedRows(1L, result.closed) { null }.single()
        assertEquals(CloseReason.SL, row.closeReason)
        assertEquals(2390.0, row.triggerPrice!!, 1e-9)
    }

    @Test
    fun aLongClosedByTpRecordsTheTpReasonAndTheTargetLevel() {
        val pos = longPosition(tp = 2410.0)
        val candle = Candle(ts + min, 2400.0, 2415.0, 2399.0, 2412.0, 100.0)
        val result = FillEngine.processCandle(stateWith(pos), candle, emptyList())

        val closed = result.closed.single()
        assertEquals(CloseReason.TP, closed.reason)
        assertEquals(2410.0, closed.triggerPrice!!, 1e-9)

        val row = TradeProjection.closedRows(1L, result.closed) { null }.single()
        assertEquals(CloseReason.TP, row.closeReason)
        assertEquals(2410.0, row.triggerPrice!!, 1e-9)
    }

    @Test
    fun aManualCloseRecordsTheManualReasonAndNoTrigger() = runTest {
        val db = TestDb.open()
        val start = ts - ts % min
        db.candleDao().insertAll(TestDb.candles(5, start))
        val season = TestDb.seedSeason(db, start)
        val repo = TradingRepository(db)
        repo.place(season.id, OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG), start)
        val positionId = db.tradeOrderDao().live(season.id).single().id

        repo.closePosition(season.id, positionId, exitPrice = 2401.0, closeTs = start + min)

        val row = db.tradeOrderDao().closed(season.id).single()
        assertEquals(CloseReason.MANUAL, row.closeReason)
        assertNull(row.triggerPrice)
        db.close()
    }

    @Test
    fun aHalfCloseRecordsThePartialReasonAndKeepsTheRemainderOpen() = runTest {
        val db = TestDb.open()
        val start = ts - ts % min
        db.candleDao().insertAll(TestDb.candles(5, start))
        val season = TestDb.seedSeason(db, start)
        val repo = TradingRepository(db)
        repo.place(season.id, OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG), start)
        val positionId = db.tradeOrderDao().live(season.id).single().id

        repo.closePosition(season.id, positionId, exitPrice = 2401.0, closeTs = start + min, lots = 0.05)

        val closed = db.tradeOrderDao().closed(season.id).single()
        assertEquals(CloseReason.PARTIAL, closed.closeReason)
        assertNull(closed.triggerPrice)
        assertEquals(0.05, closed.lots, 1e-9)
        val open = db.tradeOrderDao().live(season.id).single { it.status == OrderStatus.OPEN }
        assertEquals(0.05, open.lots, 1e-9)
        db.close()
    }

    @Test
    fun reasonsRenderWithTheExpectedShortLabels() {
        assertEquals("SL", CloseReason.SL.label())
        assertEquals("TP", CloseReason.TP.label())
        assertEquals("Stop-out", CloseReason.STOP_OUT.label())
        assertEquals("Manual", CloseReason.MANUAL.label())
        assertEquals("Half", CloseReason.PARTIAL.label())
    }
}
