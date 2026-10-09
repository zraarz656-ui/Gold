package com.tradequest.engine

import com.tradequest.engine.TestSupport.MIN
import com.tradequest.engine.TestSupport.candle
import com.tradequest.engine.TestSupport.ny
import com.tradequest.engine.TestSupport.utc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FillEngineTest {

    /** A mid-session UTC instant: normal spread, no news, no rollover. */
    private val T = utc(2024, 1, 10, 12, 0)

    private fun state(
        balance: Double,
        ts: Long = T,
        positions: List<Position> = emptyList(),
        orders: List<Order> = emptyList(),
        dayStartEquity: Double = balance,
    ) = AccountState(
        balance = balance,
        orders = orders,
        positions = positions,
        dayTs = MarketCalendar.dayStart(ts),
        dayStartEquity = dayStartEquity,
        nextPositionId = 100,
    )

    private fun pos(
        id: Long,
        side: Side,
        entry: Double,
        lots: Double = 1.0,
        sl: Double? = null,
        tp: Double? = null,
        trail: Double? = null,
    ) = Position(id, side, lots, entry, T - MIN, sl, tp, trail)

    private val noNews = emptyList<NewsEvent>()

    // --------------------------------------------------------------- market fills

    @Test
    fun `market buy fills at ask on the next candle not the placing candle`() {
        val order = Order(1, Side.LONG, OrderType.MARKET, 1.0, placedAtTs = T)
        var s = FillEngine.placeOrder(state(10_000.0), order)

        val c0 = candle(T, 2000.0, 2001.0, 1999.0, 2000.0)
        val c1 = candle(T + MIN, 2000.0, 2001.0, 1999.0, 2000.5)

        val r0 = FillEngine.processCandle(s, c0, noNews)
        assertTrue(r0.fills.isEmpty(), "order must not fill on the placing candle")
        s = r0.state

        val r1 = FillEngine.processCandle(s, c1, noNews)
        assertEquals(1, r1.fills.size)
        assertEquals(2000.30, r1.fills[0].price, 1e-9) // open + normal spread
        assertEquals(1, r1.state.positions.size)
    }

    @Test
    fun `market sell fills at bid on the next candle`() {
        val order = Order(2, Side.SHORT, OrderType.MARKET, 1.0, placedAtTs = T)
        var s = FillEngine.placeOrder(state(10_000.0), order)
        s = FillEngine.processCandle(s, candle(T, 2000.0, 2001.0, 1999.0, 2000.0), noNews).state
        val r = FillEngine.processCandle(s, candle(T + MIN, 2000.0, 2001.0, 1999.0, 2000.0), noNews)
        assertEquals(2000.00, r.fills[0].price, 1e-9) // bid, no spread added
    }

    // --------------------------------------------------------------- SL / TP

    @Test
    fun `long SL hit`() {
        val p = pos(1, Side.LONG, entry = 2000.0, sl = 1990.0, tp = 2100.0)
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 1995.0, 1996.0, 1989.0, 1990.0), noNews)
        assertEquals(1, r.closed.size)
        val c = r.closed[0]
        assertEquals(CloseReason.SL, c.reason)
        assertEquals(1990.0, c.exitPrice, 1e-9)
        assertEquals(-1000.0, c.grossPnl, 1e-9)
        assertEquals(-1007.0, c.netPnl, 1e-9)
        assertTrue(r.state.positions.isEmpty())
    }

    @Test
    fun `short SL hit`() {
        val p = pos(1, Side.SHORT, entry = 2000.0, sl = 2010.0, tp = 1900.0)
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 2005.0, 2011.0, 2004.0, 2006.0), noNews)
        val c = r.closed[0]
        assertEquals(CloseReason.SL, c.reason)
        assertEquals(2010.0, c.exitPrice, 1e-9)
        assertEquals(-1000.0, c.grossPnl, 1e-9)
    }

    @Test
    fun `long TP hit`() {
        val p = pos(1, Side.LONG, entry = 2000.0, sl = 1900.0, tp = 2050.0)
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 2010.0, 2051.0, 2009.0, 2045.0), noNews)
        val c = r.closed[0]
        assertEquals(CloseReason.TP, c.reason)
        assertEquals(2050.0, c.exitPrice, 1e-9)
        assertEquals(5000.0, c.grossPnl, 1e-9)
    }

    @Test
    fun `short TP hit and spread gates the trigger`() {
        val p = pos(1, Side.SHORT, entry = 2000.0, sl = 2100.0, tp = 1950.0)
        // low 1949.9 needs +0.30 spread -> 1950.2, so it does NOT reach the 1950 target.
        val miss = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 1960.0, 1965.0, 1949.9, 1955.0), noNews)
        assertTrue(miss.closed.isEmpty(), "short TP must account for the spread")

        // low 1949.6 -> 1949.9 <= 1950, so it fills at the exact target.
        val hit = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 1960.0, 1965.0, 1949.6, 1955.0), noNews)
        assertEquals(CloseReason.TP, hit.closed[0].reason)
        assertEquals(1950.0, hit.closed[0].exitPrice, 1e-9)
    }

    @Test
    fun `SL and TP in same candle SL wins`() {
        val p = pos(1, Side.LONG, entry = 2000.0, sl = 1990.0, tp = 2010.0)
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 2000.0, 2011.0, 1989.0, 2000.0), noNews)
        assertEquals(1, r.closed.size)
        assertEquals(CloseReason.SL, r.closed[0].reason)
        assertEquals(1990.0, r.closed[0].exitPrice, 1e-9)
    }

    @Test
    fun `gap rule candle opens below a long SL fills at the open`() {
        val p = pos(1, Side.LONG, entry = 2000.0, sl = 1990.0)
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 1985.0, 1986.0, 1980.0, 1982.0), noNews)
        assertEquals(1985.0, r.closed[0].exitPrice, 1e-9)
        assertEquals(-1500.0, r.closed[0].grossPnl, 1e-9)
    }

    // --------------------------------------------------------------- pending orders

    @Test
    fun `buy limit triggers and fills at the exact price`() {
        val o = Order(1, Side.LONG, OrderType.BUY_LIMIT, 1.0, price = 1990.0, placedAtTs = T - MIN)
        val r = FillEngine.processCandle(state(10_000.0, orders = listOf(o)), candle(T, 1995.0, 1996.0, 1989.0, 1993.0), noNews)
        assertEquals(1, r.fills.size)
        assertEquals(1990.0, r.fills[0].price, 1e-9)
        assertEquals("PENDING", r.fills[0].reason)
    }

    @Test
    fun `buy stop triggers and fills at the exact price`() {
        val o = Order(1, Side.LONG, OrderType.BUY_STOP, 1.0, price = 1995.0, placedAtTs = T - MIN)
        val r = FillEngine.processCandle(state(10_000.0, orders = listOf(o)), candle(T, 1990.0, 1996.0, 1989.0, 1994.0), noNews)
        assertEquals(1995.0, r.fills[0].price, 1e-9)
    }

    @Test
    fun `sell limit triggers and fills at the exact price`() {
        val o = Order(1, Side.SHORT, OrderType.SELL_LIMIT, 1.0, price = 1995.0, placedAtTs = T - MIN)
        val r = FillEngine.processCandle(state(10_000.0, orders = listOf(o)), candle(T, 1990.0, 1996.0, 1989.0, 1993.0), noNews)
        assertEquals(1995.0, r.fills[0].price, 1e-9)
    }

    @Test
    fun `sell stop triggers and fills at the exact price`() {
        val o = Order(1, Side.SHORT, OrderType.SELL_STOP, 1.0, price = 1990.0, placedAtTs = T - MIN)
        val r = FillEngine.processCandle(state(10_000.0, orders = listOf(o)), candle(T, 1995.0, 1996.0, 1989.0, 1993.0), noNews)
        assertEquals(1990.0, r.fills[0].price, 1e-9)
    }

    @Test
    fun `gap rule pending order opening past trigger fills at the open`() {
        val o = Order(1, Side.LONG, OrderType.BUY_STOP, 1.0, price = 1995.0, placedAtTs = T - MIN)
        // Opens at 2005, already above the 1995 stop -> fills at open + spread.
        val r = FillEngine.processCandle(state(10_000.0, orders = listOf(o)), candle(T, 2005.0, 2006.0, 2004.0, 2005.0), noNews)
        assertEquals("GAP", r.fills[0].reason)
        assertEquals(2005.30, r.fills[0].price, 1e-9)
    }

    @Test
    fun `pending order that fills and hits its SL in the same candle closes on SL`() {
        val o = Order(1, Side.LONG, OrderType.BUY_LIMIT, 1.0, price = 1990.0, sl = 1985.0, placedAtTs = T - MIN)
        val r = FillEngine.processCandle(state(10_000.0, orders = listOf(o)), candle(T, 1995.0, 1996.0, 1980.0, 1984.0), noNews)
        assertEquals(1, r.fills.size)
        assertEquals(1990.0, r.fills[0].price, 1e-9)
        assertEquals(1, r.closed.size)
        assertEquals(CloseReason.SL, r.closed[0].reason)
        assertEquals(1985.0, r.closed[0].exitPrice, 1e-9)
    }

    // --------------------------------------------------------------- spread & slippage

    @Test
    fun `spread widens near high impact news and near rollover`() {
        val news = listOf(NewsEvent(T, "NFP", Impact.HIGH))
        assertEquals(0.80, FillEngine.spreadAt(T, news), 1e-9)
        assertEquals(0.80, FillEngine.spreadAt(T + 4 * MIN, news), 1e-9)
        assertEquals(0.30, FillEngine.spreadAt(T + 6 * MIN, news), 1e-9)
        assertEquals(0.30, FillEngine.spreadAt(T, listOf(NewsEvent(T, "x", Impact.LOW))), 1e-9)

        assertEquals(0.60, FillEngine.spreadAt(ny(2024, 1, 10, 17, 3), noNews), 1e-9)
        assertEquals(0.60, FillEngine.spreadAt(ny(2024, 1, 10, 16, 57), noNews), 1e-9)
        assertEquals(0.30, FillEngine.spreadAt(ny(2024, 1, 10, 17, 6), noNews), 1e-9)

        val both = listOf(NewsEvent(ny(2024, 1, 10, 17, 0), "FOMC", Impact.HIGH))
        assertEquals(0.80, FillEngine.spreadAt(ny(2024, 1, 10, 17, 2), both), 1e-9)
    }

    @Test
    fun `news slippage is deterministic and within bounds`() {
        val news = listOf(NewsEvent(T, "NFP", Impact.HIGH))
        val o = Order(7, Side.LONG, OrderType.BUY_STOP, 1.0, price = 1990.0, placedAtTs = T - MIN)
        val c = candle(T, 1985.0, 1995.0, 1984.0, 1994.0)

        val a = FillEngine.processCandle(state(10_000.0, orders = listOf(o)), c, news)
        val b = FillEngine.processCandle(state(10_000.0, orders = listOf(o)), c, news)
        assertEquals(a.fills, b.fills, "slippage must be deterministic")
        assertEquals(1, a.fills.size)
        val fill = a.fills[0].price
        assertTrue(fill >= 1990.0 + FillEngine.SLIP_MIN - 1e-9, "fill below min slippage: $fill")
        assertTrue(fill <= 1990.0 + FillEngine.SLIP_MAX + 1e-9, "fill above max slippage: $fill")
        assertTrue(fill <= c.h, "fill must stay inside the candle range")
    }

    @Test
    fun `news slippage on a stop loss is capped to the candle range`() {
        val news = listOf(NewsEvent(T, "NFP", Impact.HIGH))
        // Short SL at 2010, but the candle high is only 2010.30 -> slippage capped at 0.30.
        val p = pos(1, Side.SHORT, entry = 2000.0, sl = 2010.0)
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 2005.0, 2010.30, 2004.0, 2006.0), news)
        val fill = r.closed[0].exitPrice
        assertTrue(fill >= 2010.0, "fill slipped the wrong way: $fill")
        assertTrue(fill <= 2010.30 + 1e-9, "fill escaped the candle high: $fill")
    }

    @Test
    fun `no slippage outside the news window`() {
        val o = Order(7, Side.LONG, OrderType.BUY_STOP, 1.0, price = 1990.0, placedAtTs = T - MIN)
        val c = candle(T, 1985.0, 1995.0, 1984.0, 1994.0)
        val news = listOf(NewsEvent(T + 10 * MIN, "later", Impact.HIGH))
        val r = FillEngine.processCandle(state(10_000.0, orders = listOf(o)), c, news)
        assertEquals(1990.0, r.fills[0].price, 1e-9)
    }

    // --------------------------------------------------------------- trailing stops

    @Test
    fun `trailing stop moves only in the profit direction and triggers next candle`() {
        val p = pos(1, Side.LONG, entry = 2000.0, sl = 1990.0, trail = 10.0)
        val s = state(10_000.0, positions = listOf(p))

        // Candle 1: high 2020 -> candidate 2010, stored for the next candle.
        val r1 = FillEngine.processCandle(s, candle(T, 2000.0, 2020.0, 1999.0, 2015.0), noNews)
        assertEquals(1, r1.state.positions.size)
        assertEquals(1990.0, r1.state.positions[0].sl!!, 1e-9, "stop must not move within the same candle")
        assertEquals(2010.0, r1.state.positions[0].pendingTrail!!, 1e-9)

        // Candle 2: opens 2015, dips to 2000 -> the raised stop at 2010 is now active.
        val r2 = FillEngine.processCandle(r1.state, candle(T + MIN, 2015.0, 2016.0, 2000.0, 2001.0), noNews)
        assertEquals(1, r2.closed.size)
        assertEquals(CloseReason.SL, r2.closed[0].reason)
        assertEquals(2010.0, r2.closed[0].exitPrice, 1e-9)
    }

    @Test
    fun `trailing stop never loosens in the wrong direction`() {
        val p = pos(1, Side.LONG, entry = 2000.0, sl = 1990.0, trail = 10.0)
        // High 1995 -> candidate 1985, worse than the current 1990 stop.
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 2000.0, 1995.0, 1994.0, 1995.0), noNews)
        assertEquals(1990.0, r.state.positions[0].sl!!, 1e-9)
        assertEquals(null, r.state.positions[0].pendingTrail)
    }

    // --------------------------------------------------------------- margin & daily limit

    @Test
    fun `stop-out closes the biggest loser first`() {
        val p1 = pos(1, Side.LONG, entry = 1820.0, lots = 0.5)
        val p2 = pos(2, Side.LONG, entry = 1810.0, lots = 0.5)
        val r = FillEngine.processCandle(state(2_000.0, positions = listOf(p1, p2)), candle(T, 1800.0, 1801.0, 1800.0, 1800.0), noNews)

        assertEquals(1, r.closed.size, "only the biggest loser should be stopped out")
        assertEquals(1L, r.closed[0].positionId)
        assertEquals(CloseReason.STOP_OUT, r.closed[0].reason)
        assertEquals(listOf(2L), r.state.positions.map { it.id })
        assertTrue(r.events.any { it.type == EventType.STOP_OUT })
    }

    @Test
    fun `margin warning is emitted when the level is between 50 and 100 percent`() {
        val p = pos(1, Side.LONG, entry = 1810.0, lots = 1.0)
        val r = FillEngine.processCandle(state(2_000.0, positions = listOf(p)), candle(T, 1800.0, 1801.0, 1800.0, 1800.0), noNews)
        assertTrue(r.events.any { it.type == EventType.MARGIN_WARNING }, "expected a margin warning")
        assertTrue(r.state.positions.isNotEmpty())
    }

    @Test
    fun `daily loss limit blocks new orders but not existing positions and resets next day`() {
        val dayA = ny(2024, 1, 10, 18, 0)
        val p = pos(1, Side.LONG, entry = 2000.0, lots = 1.0)
        var s = state(10_000.0, ts = dayA, positions = listOf(p))

        // Worst-case equity 10000 - 410 = 9590 <= 9700 -> limit hit.
        val r = FillEngine.processCandle(s, candle(dayA, 2000.0, 2001.0, 1995.9, 2000.0), noNews)
        assertTrue(r.events.any { it.type == EventType.DAILY_LIMIT })
        assertTrue(r.state.dailyBlocked)
        assertEquals(1, r.state.positions.size, "existing positions must keep running")

        // New orders are rejected while blocked.
        val blocked = FillEngine.placeOrder(r.state, Order(9, Side.LONG, OrderType.MARKET, 1.0, placedAtTs = dayA))
        assertTrue(blocked.orders.isEmpty())

        // Next day, at the rollover, the budget resets.
        val dayB = ny(2024, 1, 11, 18, 0)
        val r2 = FillEngine.processCandle(r.state, candle(dayB, 2000.0, 2001.0, 1999.5, 2000.0), noNews)
        assertFalse(r2.state.dailyBlocked, "daily block must reset at the new FX day")
        val allowed = FillEngine.placeOrder(r2.state, Order(9, Side.LONG, OrderType.MARKET, 1.0, placedAtTs = dayB))
        assertEquals(1, allowed.orders.size)
    }

    // --------------------------------------------------------------- accounting & purity

    @Test
    fun `commission and pnl math one dollar move on one lot`() {
        val p = pos(1, Side.LONG, entry = 2000.0, tp = 2001.0)
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 2000.0, 2002.0, 1999.0, 2001.5), noNews)
        val c = r.closed[0]
        assertEquals(100.0, c.grossPnl, 1e-9)   // 1.00 * 100 oz
        assertEquals(7.0, c.commission, 1e-9)
        assertEquals(93.0, c.netPnl, 1e-9)
        assertEquals(10_093.0, r.state.balance, 1e-9)
    }

    @Test
    fun `processing the same candles twice yields identical results`() {
        val p = pos(1, Side.LONG, entry = 2000.0, sl = 1990.0, tp = 2030.0, trail = 8.0)
        val o = Order(5, Side.LONG, OrderType.BUY_LIMIT, 1.0, price = 1995.0, placedAtTs = T - MIN)
        val initial = state(10_000.0, positions = listOf(p), orders = listOf(o))
        val news = listOf(NewsEvent(T + 2 * MIN, "NFP", Impact.HIGH))
        val candles = listOf(
            candle(T, 2000.0, 2005.0, 1996.0, 2001.0),
            candle(T + MIN, 2001.0, 2006.0, 1994.0, 1995.0),
            candle(T + 2 * MIN, 1995.0, 2004.0, 1990.0, 2003.0),
            candle(T + 3 * MIN, 2003.0, 2035.0, 2002.0, 2031.0),
        )

        fun run(): List<ProcessResult> {
            var s = initial
            return candles.map { c -> FillEngine.processCandle(s, c, news).also { s = it.state } }
        }

        assertEquals(run(), run(), "engine must be deterministic")
    }

    @Test
    fun `equity snapshot at close is reported`() {
        val p = pos(1, Side.LONG, entry = 2000.0, lots = 1.0)
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(T, 2000.0, 2001.0, 1999.0, 2000.5), noNews)
        assertNotNull(r.equityAtClose)
        assertEquals(10_050.0, r.equityAtClose, 1e-9) // +0.5 * 100 oz
    }

    // --------------------------------------------------------------- close timestamps

    @Test
    fun `SL close carries the timestamp of the candle that hit it`() {
        val p = pos(1, Side.LONG, entry = 2000.0, sl = 1995.0)
        val hitTs = T + 3 * MIN
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(hitTs, 2000.0, 2001.0, 1994.0, 1994.5), noNews)
        assertEquals(1, r.closed.size)
        assertEquals(CloseReason.SL, r.closed[0].reason)
        assertEquals(hitTs, r.closed[0].closeTs)
    }

    @Test
    fun `TP close carries the timestamp of the candle that hit it`() {
        val p = pos(1, Side.LONG, entry = 2000.0, tp = 2005.0)
        val hitTs = T + 7 * MIN
        val r = FillEngine.processCandle(state(10_000.0, positions = listOf(p)), candle(hitTs, 2000.0, 2006.0, 1999.0, 2004.0), noNews)
        assertEquals(1, r.closed.size)
        assertEquals(CloseReason.TP, r.closed[0].reason)
        assertEquals(hitTs, r.closed[0].closeTs)
    }

    @Test
    fun `manual close carries the supplied candle timestamp`() {
        val p = pos(1, Side.LONG, entry = 2000.0)
        val closeTs = T + 11 * MIN
        val result = FillEngine.closePosition(state(10_000.0, positions = listOf(p)), 1, 2003.0, closeTs)
        assertNotNull(result)
        assertEquals(CloseReason.MANUAL, result!!.second.reason)
        assertEquals(closeTs, result.second.closeTs)
    }

    @Test
    fun `stop-out close carries the timestamp of the candle that stopped it out`() {
        val p = pos(1, Side.LONG, entry = 2000.0, lots = 1.0)
        val stopTs = T + 5 * MIN
        // Worst-case equity 100 - 1000 = -900 at 10000% below zero -> level below the 50% stop-out.
        val r = FillEngine.processCandle(state(100.0, positions = listOf(p)), candle(stopTs, 2000.0, 2000.0, 1990.0, 1990.0), noNews)
        assertEquals(1, r.closed.size)
        assertEquals(CloseReason.STOP_OUT, r.closed[0].reason)
        assertEquals(stopTs, r.closed[0].closeTs)
    }
}
