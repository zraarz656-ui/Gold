package com.tradequest.data

import com.tradequest.engine.AccountState
import com.tradequest.engine.Candle
import com.tradequest.engine.CloseReason
import com.tradequest.engine.FillEngine
import com.tradequest.engine.OrderType
import com.tradequest.engine.Position
import com.tradequest.engine.Side
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Each closed row carries a P&L breakdown: gross, commission and net, obeying
 * `(close - entry) x lots x 100 - $7 x lots` (side-aware). Hand-computed per reason.
 */
@RunWith(RobolectricTestRunner::class)
class PnlBreakdownTest {

    private val ts = 1_700_000_000_000L
    private val min = 60_000L

    private fun stateWith(pos: Position) =
        AccountState(balance = 10_000.0, positions = listOf(pos), nextPositionId = pos.id + 1)

    private fun longAt(sl: Double? = null, tp: Double? = null, lots: Double = 0.10) = Position(
        id = 1L, side = Side.LONG, lots = lots, entryPrice = 2400.0, openedAtTs = ts, sl = sl, tp = tp,
    )

    private fun assertBreakdown(row: TradeOrder?, entry: Double, exit: Double, lots: Double, sign: Int) {
        val gross = (exit - entry) * 100.0 * lots * sign
        val commission = 7.0 * lots
        assertEquals("gross", gross, row!!.grossPnl!!, 1e-9)
        assertEquals("commission", commission, row.fees!!, 1e-9)
        assertEquals("net", gross - commission, row.pnl!!, 1e-9)
    }

    @Test
    fun slLongBreakdown() {
        // Entry 2400, SL 2390, 0.10 lots -> gross -100, commission 0.70, net -100.70.
        val candle = Candle(ts + min, 2400.0, 2401.0, 2385.0, 2395.0, 100.0)
        val result = FillEngine.processCandle(stateWith(longAt(sl = 2390.0)), candle, emptyList())
        assertEquals(CloseReason.SL, result.closed.single().reason)
        val row = TradeProjection.closedRows(1L, result.closed) { null }.single()
        assertBreakdown(row, entry = 2400.0, exit = 2390.0, lots = 0.10, sign = 1)
        assertEquals(-100.70, row.pnl!!, 1e-9)
    }

    @Test
    fun tpLongBreakdown() {
        // Entry 2400, TP 2410, 0.10 lots -> gross +100, commission 0.70, net +99.30.
        val candle = Candle(ts + min, 2400.0, 2415.0, 2399.0, 2412.0, 100.0)
        val result = FillEngine.processCandle(stateWith(longAt(tp = 2410.0)), candle, emptyList())
        assertEquals(CloseReason.TP, result.closed.single().reason)
        val row = TradeProjection.closedRows(1L, result.closed) { null }.single()
        assertBreakdown(row, entry = 2400.0, exit = 2410.0, lots = 0.10, sign = 1)
        assertEquals(99.30, row.pnl!!, 1e-9)
    }

    @Test
    fun manualCloseBreakdown() = runTest {
        // Entry at the ask 2400.30, manually closed at 2405.00, 0.10 lots.
        val db = TestDb.open()
        val start = ts - ts % min
        db.candleDao().insertAll(TestDb.candles(5, start))
        val season = TestDb.seedSeason(db, start)
        val repo = TradingRepository(db)
        repo.place(season.id, OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG), start)
        val entry = db.tradeOrderDao().live(season.id).single().entryPrice!!
        val positionId = db.tradeOrderDao().live(season.id).single().id

        repo.closePosition(season.id, positionId, exitPrice = 2405.0, closeTs = start + min)

        val row = db.tradeOrderDao().closed(season.id).single()
        assertEquals(CloseReason.MANUAL, row.closeReason)
        val gross = (2405.0 - entry) * 100.0 * 0.10
        assertEquals(gross, row.grossPnl!!, 1e-9)
        assertEquals(0.70, row.fees!!, 1e-9)
        assertEquals(gross - 0.70, row.pnl!!, 1e-9)
        db.close()
    }

    @Test
    fun halfCloseBreakdown() = runTest {
        // Half of a 0.10 long closed at 2405.00 -> 0.05 lots, commission 0.35.
        val db = TestDb.open()
        val start = ts - ts % min
        db.candleDao().insertAll(TestDb.candles(5, start))
        val season = TestDb.seedSeason(db, start)
        val repo = TradingRepository(db)
        repo.place(season.id, OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG), start)
        val entry = db.tradeOrderDao().live(season.id).single().entryPrice!!
        val positionId = db.tradeOrderDao().live(season.id).single().id

        repo.closePosition(season.id, positionId, exitPrice = 2405.0, closeTs = start + min, lots = 0.05)

        val row = db.tradeOrderDao().closed(season.id).single()
        assertEquals(CloseReason.PARTIAL, row.closeReason)
        val gross = (2405.0 - entry) * 100.0 * 0.05
        assertEquals(gross, row.grossPnl!!, 1e-9)
        assertEquals(0.35, row.fees!!, 1e-9)
        assertEquals(gross - 0.35, row.pnl!!, 1e-9)
        db.close()
    }
}
