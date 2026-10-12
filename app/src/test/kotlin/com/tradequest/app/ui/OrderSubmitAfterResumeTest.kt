package com.tradequest.app.ui

import com.tradequest.data.OrderRequest
import com.tradequest.data.OrderStatus
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Reproduction for "trading is broken after minimize/restore", plus the validation rules
 * for manually entered stops and triggers.
 *
 * Drives the real [TradingViewModel] through launch, a simulated background/restore cycle
 * (ON_RESUME is all the app handles; ON_STOP is a no-op) and a market buy. Uses the real
 * Room database, clock and repositories, so a stuck flag, a cancelled scope or a held mutex
 * would surface here exactly as it does on device.
 */
@RunWith(RobolectricTestRunner::class)
class OrderSubmitAfterResumeTest {

    private val h = TradingHarness()
    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        h.setUp()
    }

    @After
    fun tearDown() = h.tearDown()

    private fun buildViewModel() = h.buildViewModel()
    private fun seed() = h.seed()
    private fun awaitUntil(timeoutMs: Long = 20_000L, condition: () -> Boolean) = h.awaitUntil(timeoutMs, condition)
    private inline fun <T> onMain(noinline block: () -> T): T = h.onMain(block)
    private fun live(seasonId: Long) = h.live(seasonId)
    private fun closed(seasonId: Long) = h.closed(seasonId)

    @Test
    fun submitIsAcceptedAsSoonAsTheQuoteIsLive() {
        // Before the fix this was the "trading is broken after minimize/restore" window:
        // the chart shows live prices (_quote set in loadInitialWindow) while `ready` is
        // still false, so every submit early-returned with
        //   W/TradeQuest: placeOrder: early return - Market data is still loading
        // This test waits only for a live quote, then submits.
        seed()
        val vm = buildViewModel()
        awaitUntil { vm.quote.value.bid > 0.0 }

        onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        awaitUntil { live(1L).isNotEmpty() }

        assertEquals(1, live(1L).size)
        assertNull("a submit with a live quote must never be dropped: ${vm.submitError.value}", vm.submitError.value)
    }

    @Test
    fun marketBuyAfterResumeOpensExactlyOnePosition() {
        seed()
        val vm = buildViewModel()
        awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        // Minimize then restore. The app only acts on ON_RESUME.
        onMain { vm.onResume() }
        awaitUntil { true }

        onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        awaitUntil { live(1L).isNotEmpty() }

        val rows = live(1L)
        assertNull("submit must not report an error: ${vm.submitError.value}", vm.submitError.value)
        assertEquals("exactly one live order expected", 1, rows.size)
        assertEquals(OrderStatus.OPEN, rows.single().status)
        assertNotNull(rows.single().entryPrice)
    }

    @Test
    fun submitBeforeReadyReportsAReasonInsteadOfFailingSilently() {
        val vm = buildViewModel()
        onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        awaitUntil { vm.submitError.value != null }

        assertNotNull("a rejected submit must surface a reason", vm.submitError.value)
        assertTrue(closed(1L).isEmpty())
    }

    @Test
    fun submitIsStillAcceptedAfterRepeatedResumes() {
        seed()
        val vm = buildViewModel()
        awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        repeat(3) {
            onMain { vm.onResume() }
            awaitUntil { true }
        }
        onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        awaitUntil { live(1L).isNotEmpty() }
        assertEquals(1, live(1L).size)
        assertNull(vm.submitError.value)
    }

    @Test
    fun aStopOnTheWrongSideIsRejectedAndOpensNothing() {
        seed()
        val vm = buildViewModel()
        awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        // An SL above the entry on a long is impossible; the engine would close it on the
        // very next candle at a nonsensical profit ("orders close by themselves").
        onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG, sl = 5000.0)) }
        awaitUntil { vm.submitError.value != null }

        assertNotNull("a wrong-side stop must surface a reason", vm.submitError.value)
        assertTrue("no position may be opened: ${vm.submitError.value}", live(1L).isEmpty())
    }

    @Test
    fun aBuyLimitAboveTheMarketIsRejected() {
        seed()
        val vm = buildViewModel()
        awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        // A buy limit must rest below the market; above it, it fills instantly as a "GAP".
        onMain { vm.placeOrder(OrderRequest(OrderType.BUY_LIMIT, 0.10, price = 9999.0)) }
        awaitUntil { vm.submitError.value != null }

        assertNotNull("an impossible trigger price must surface a reason", vm.submitError.value)
        assertTrue("no order may rest: ${vm.submitError.value}", live(1L).isEmpty())
    }
}
