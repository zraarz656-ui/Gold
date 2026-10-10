package com.tradequest.app.ui

import com.tradequest.data.Candle1m
import com.tradequest.data.OrderRequest
import com.tradequest.data.OrderStatus
import com.tradequest.engine.CloseReason
import com.tradequest.engine.FillEngine
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * A $3 stop on a 0.10 long: the position is closed by SL, the closed row carries the SL
 * reason and the stop price, and the account moves by the net P&L (-$30.70 = -$3.00 gross
 * - $0.70 round-trip commission). Balance, Equity and Day P&L must all agree:
 *   equity = balance + floating,   dayPnl = equity - day-start equity.
 */
@RunWith(RobolectricTestRunner::class)
class StopHitTest {

    private val h = TradingHarness()

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        h.setUp()
    }

    @After
    fun tearDown() = h.tearDown()

    @Test
    fun aThreeDollarStopClosesTheLongAtTheStopWithNetThirtyPointSeven() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 && vm.displayQuote.value.bid > 0.0 }

        val balanceAtOpen = vm.strip.value.balance
        val dayStartEquity = vm.strip.value.equity // flat, so day-start equity == equity now
        // Fills read the authoritative quote, not the smoothed display walk.
        val fillAsk = vm.quote.value.ask
        val sl = FillEngine.roundPrice(fillAsk - 3.0)

        h.onMain {
            vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG, sl = sl))
        }
        h.awaitUntil { h.live(1L).isNotEmpty() }
        assertEquals("one position from one tap", 1, h.live(1L).size)
        val entry = h.live(1L).single().entryPrice!!

        // Next replayed minute trades down through the stop: opens just above the stop and
        // dips below it, so the SL exit lands exactly on the stop price.
        runBlocking {
            h.db.candleDao().insertAll(
                listOf(Candle1m(h.openNow + h.min, entry + 1.0, entry + 2.0, sl - 5.0, sl - 2.0, 100.0)),
            )
        }
        h.clockMs = h.openNow + 2 * h.min
        h.onMain { vm.onResume() }
        h.awaitUntil { h.closed(1L).isNotEmpty() }

        val closed = h.closed(1L)
        println("STOP DIAG entry=$entry sl=$sl closed=$closed")
        assertEquals("the stop must close the position", 1, closed.size)
        val row = closed.single()
        assertEquals(OrderStatus.CLOSED, row.status)
        assertEquals("SL reason recorded", CloseReason.SL, row.closeReason)
        assertNotNull(row.triggerPrice)
        assertEquals("the stop level is recorded", sl, row.triggerPrice!!, 0.01)
        assertEquals("net P&L: -$3.00 gross - $0.70 commission", -30.70, row.pnl!!, 0.01)

        h.awaitUntil { vm.strip.value.balance < balanceAtOpen }
        val strip = vm.strip.value
        println("STOP DIAG strip=$strip")
        assertEquals("balance moved by the net P&L", balanceAtOpen - 30.70, strip.balance, 0.01)
        assertEquals("equity = balance + floating", strip.balance + strip.floatingPnl, strip.equity, 0.01)
        assertEquals("day P&L = equity - day-start equity", strip.equity - dayStartEquity, strip.dayPnl, 0.01)
    }
}

