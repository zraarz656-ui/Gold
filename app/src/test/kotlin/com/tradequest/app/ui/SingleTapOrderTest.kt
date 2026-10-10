package com.tradequest.app.ui

import com.tradequest.data.OrderRequest
import com.tradequest.data.OrderStatus
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * A single Buy tap must open exactly one position and leave the balance untouched (a market
 * order only opens a position; it never moves the realised balance). The displayed strip
 * must agree with the authoritative account: balance from the checkpoint and
 * equity = balance + floating P&L.
 */
@RunWith(RobolectricTestRunner::class)
class SingleTapOrderTest {

    private val h = TradingHarness()

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        h.setUp()
    }

    @After
    fun tearDown() = h.tearDown()

    @Test
    fun oneBuyTapOpensExactlyOnePositionAndKeepsTheBalance() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        val balanceBefore = vm.strip.value.balance
        assertEquals("season starts at $10,000", 10_000.0, balanceBefore, 0.01)

        h.onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        h.awaitUntil { h.live(1L).isNotEmpty() }

        val rows = h.live(1L)
        assertNull("submit must not report an error: ${vm.submitError.value}", vm.submitError.value)
        assertEquals("exactly one position from one tap", 1, rows.size)
        assertEquals(OrderStatus.OPEN, rows.single().status)

        assertEquals("balance must not move on a market buy", 10_000.0, vm.strip.value.balance, 0.01)

        val strip = vm.displayStrip.value
        assertEquals("displayed balance must equal the account balance", 10_000.0, strip.balance, 0.01)
        assertEquals(
            "equity must be balance + floating P&L",
            strip.balance + strip.floatingPnl,
            strip.equity,
            0.01,
        )
    }
}
