package com.tradequest.data

import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Structural rules for manually entered SL/TP and resting trigger prices. */
class OrderRulesTest {

    private val bid = 3980.00
    private val ask = 3980.30
    private val spread = ask - bid

    @Test
    fun aStopOnTheWrongSideOfALongIsRejected() {
        val err = OrderRules.stopError(Side.LONG, entry = 3980.0, sl = 5000.0)
        assertEquals("Stop loss must be below the entry (3980.00)", err)
        assertNull(OrderRules.stopError(Side.LONG, entry = 3980.0, sl = 3900.0))
    }

    @Test
    fun aStopOnTheWrongSideOfAShortIsRejected() {
        assertEquals("Stop loss must be above the entry (3980.00)", OrderRules.stopError(Side.SHORT, 3980.0, 3900.0))
        assertNull(OrderRules.stopError(Side.SHORT, 3980.0, 4050.0))
    }

    @Test
    fun takeProfitsMustPointTheRightWay() {
        assertEquals("Take profit must be above the entry (3980.00)", OrderRules.takeProfitError(Side.LONG, 3980.0, 3900.0))
        assertEquals("Take profit must be below the entry (3980.00)", OrderRules.takeProfitError(Side.SHORT, 3980.0, 4050.0))
        assertNull(OrderRules.takeProfitError(Side.LONG, 3980.0, 4100.0))
    }

    @Test
    fun nonPositiveStopsAreRejected() {
        assertEquals("Stop loss must be a positive price", OrderRules.stopError(Side.LONG, 3980.0, -5.0))
        assertEquals("Take profit must be a positive price", OrderRules.takeProfitError(Side.LONG, 3980.0, 0.0))
    }

    @Test
    fun restingTriggersMustSitOnTheirOwnSideOfTheMarket() {
        assertNull(OrderRules.triggerError(OrderType.BUY_LIMIT, 3900.0, bid, ask))
        assertEquals("A buy limit must be below the market (3980.30)", OrderRules.triggerError(OrderType.BUY_LIMIT, 5000.0, bid, ask))
        assertNull(OrderRules.triggerError(OrderType.BUY_STOP, 4050.0, bid, ask))
        assertEquals("A buy stop must be above the market (3980.30)", OrderRules.triggerError(OrderType.BUY_STOP, 3900.0, bid, ask))
        assertNull(OrderRules.triggerError(OrderType.SELL_LIMIT, 4050.0, bid, ask))
        assertEquals("A sell limit must be above the market (3980.00)", OrderRules.triggerError(OrderType.SELL_LIMIT, 3900.0, bid, ask))
        assertNull(OrderRules.triggerError(OrderType.SELL_STOP, 3900.0, bid, ask))
        assertEquals("A sell stop must be below the market (3980.00)", OrderRules.triggerError(OrderType.SELL_STOP, 4050.0, bid, ask))
    }

    @Test
    fun aMissingTriggerPriceIsRejectedForRestingOrders() {
        assertEquals("Trigger price is required", OrderRules.triggerError(OrderType.BUY_LIMIT, null, bid, ask))
        assertNull(OrderRules.triggerError(OrderType.MARKET, null, bid, ask))
    }

    @Test
    fun fullRequestValidationCatchesTheBadStopAndBadTrigger() {
        // A market long with SL above the entry (what the user could type).
        val bad = OrderRequest(OrderType.MARKET, 0.1, side = Side.LONG, sl = 5000.0)
        assertEquals(
            "Stop loss must be below the entry (3980.30)",
            OrderRules.requestError(bad, bid, ask, spread, entry = ask),
        )

        // A buy limit priced above the market fills instantly instead of resting.
        val badLimit = OrderRequest(OrderType.BUY_LIMIT, 0.1, price = 5000.0)
        assertEquals(
            "A buy limit must be below the market (3980.30)",
            OrderRules.requestError(badLimit, bid, ask, spread, entry = 5000.0),
        )

        // A valid resting buy limit with a sensible stop.
        val good = OrderRequest(OrderType.BUY_LIMIT, 0.1, price = 3950.0, sl = 3900.0, tp = 4100.0)
        assertNull(OrderRules.requestError(good, bid, ask, spread, entry = 3950.0))
    }

    @Test
    fun aTriggerTooCloseToTheMarketIsRejected() {
        val close = OrderRequest(OrderType.BUY_LIMIT, 0.1, price = 3980.20)
        assertEquals(
            "Keep 0.40 away from the market",
            OrderRules.requestError(close, bid, ask, spread, entry = 3980.20),
        )
    }
}
