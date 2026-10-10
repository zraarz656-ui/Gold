package com.tradequest.app.ui

import android.os.Looper
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
import org.robolectric.Shadows.shadowOf

/**
 * Tap coalescing: a market tap opens exactly one position, and a second tap inside the
 * platform double-tap window (400 ms) opens none. The guard lives at the single submission
 * funnel (`placeOrder`); fill/stop logic is never involved.
 */
@RunWith(RobolectricTestRunner::class)
class TapCoalescingTest {

    private val h = TradingHarness()

    @Before fun setUp() = h.setUp()
    @After fun tearDown() = h.tearDown()

    private fun pump(frames: Int = 40) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)) }
    }

    @Test
    fun oneMarketTapSubmitsOnceAndOpensOnePosition() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        h.onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        h.awaitUntil { h.live(1L).isNotEmpty() }
        pump()

        assertEquals("one tap submits one order", 1, vm.submitCount.value)
        assertEquals("one tap opens one position", 1, h.live(1L).size)
        assertEquals(OrderStatus.OPEN, h.live(1L).single().status)
        assertNull(vm.submitError.value)
    }

    @Test
    fun doubleTapWithin400msStillOpensExactlyOnePosition() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        val req = OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)
        h.onMain { vm.placeOrder(req) }
        Thread.sleep(150L) // second tap well inside the 400 ms window
        h.onMain { vm.placeOrder(req) }
        h.awaitUntil { h.live(1L).isNotEmpty() }
        pump()

        assertEquals("the duplicate tap must not submit a second order", 1, vm.submitCount.value)
        assertEquals("a double tap opens one position", 1, h.live(1L).size)
        assertNull(vm.submitError.value)
    }

    @Test
    fun twoTapsApartStillOpenTwoPositions() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        val req = OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)
        h.onMain { vm.placeOrder(req) }
        h.awaitUntil { h.live(1L).isNotEmpty() }
        Thread.sleep(500L) // beyond the window: a deliberate second order
        h.onMain { vm.placeOrder(req) }
        h.awaitUntil { h.live(1L).size == 2 }
        pump()

        assertEquals("two separate taps submit twice", 2, vm.submitCount.value)
        assertEquals("two separate taps open two positions", 2, h.live(1L).size)
    }
}
