package com.tradequest.app.ui

import com.tradequest.data.OrderRequest
import com.tradequest.engine.FillEngine
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A long is closed at the bid and a short at the ask. The price written to the closed row is
 * exactly the price that produced the P&L, so `closePrice` and `pnl` always reconcile.
 */
@RunWith(RobolectricTestRunner::class)
class PriceSideTest {

    private val h = TradingHarness()

    @Before fun setUp() = h.setUp()
    @After fun tearDown() = h.tearDown()

    private fun expectedNet(side: Side, entry: Double, exit: Double, lots: Double) =
        if (side == Side.LONG) (exit - entry) * FillEngine.LOT_OZ * lots - FillEngine.COMMISSION_PER_LOT * lots
        else (entry - exit) * FillEngine.LOT_OZ * lots - FillEngine.COMMISSION_PER_LOT * lots

    @Test
    fun aLongClosesAtTheBidAndTheRowPnlMatchesThatPrice() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        h.onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        h.awaitUntil { h.live(1L).isNotEmpty() }
        val position = h.live(1L).single()
        val bid = vm.quote.value.bid

        h.onMain { vm.closePosition(position.id) }
        h.awaitUntil { h.closed(1L).isNotEmpty() }

        val closed = h.closed(1L).single()
        assertEquals(FillEngine.roundPrice(bid), closed.closePrice!!, 1e-9)
        assertEquals(
            expectedNet(Side.LONG, position.entryPrice!!, closed.closePrice!!, 0.10),
            closed.pnl!!,
            1e-9,
        )
    }

    @Test
    fun aShortClosesAtTheAskAndTheRowPnlMatchesThatPrice() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.ask > 0.0 }

        h.onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.SHORT)) }
        h.awaitUntil { h.live(1L).isNotEmpty() }
        val position = h.live(1L).single()
        val ask = vm.quote.value.ask

        h.onMain { vm.closePosition(position.id) }
        h.awaitUntil { h.closed(1L).isNotEmpty() }

        val closed = h.closed(1L).single()
        assertEquals(FillEngine.roundPrice(ask), closed.closePrice!!, 1e-9)
        assertEquals(
            expectedNet(Side.SHORT, position.entryPrice!!, closed.closePrice!!, 0.10),
            closed.pnl!!,
            1e-9,
        )
    }
}
