package com.tradequest.app.ui

import com.tradequest.engine.ClockEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Gated regression for the live minute tick: advancing the replayed clock by two minutes
 * must deliver two new CLOSED candles to the chart and reset the countdown.
 *
 * The tick is driven exactly as the on-device [com.tradequest.app.clock.LiveClock] drives
 * it: move the replayed clock, publish the newly visible candle timestamp to the ticker.
 */
@RunWith(RobolectricTestRunner::class)
class LiveMinuteTickTest {
    private val h = TradingHarness()

    @Before fun setUp() = h.setUp()
    @After fun tearDown() = h.tearDown()

    @Test
    fun advancingTwoMinutesAppendsTwoClosedCandlesAndResetsTheCountdown() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        val startBars = vm.controller.state.m1.size
        val startCountdown = vm.controller.state.countdownMs

        repeat(2) { step ->
            h.clockMs = h.openNow + (step + 1) * h.min
            h.candles.publish(ClockEngine.lastVisibleCandleTs(h.clockMs))
            h.awaitUntil {
                vm.controller.state.m1.size == startBars + step + 1 &&
                    vm.controller.state.countdownMs == h.min
            }
        }

        assertEquals(startBars + 2, vm.controller.state.m1.size)
        // A fresh minute always resets the close countdown to a full minute.
        assertEquals(h.min, vm.controller.state.countdownMs)
        assertEquals(startCountdown, vm.controller.state.countdownMs)
        assertEquals(h.openNow + 2 * h.min, vm.histNow.value)
    }

    @Test
    fun theLiveEdgeStaysAtTheNewestCandleWhileFollowing() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        assertTrue("chart should follow the live edge on open", vm.controller.state.liveEdgeFollowing)

        h.clockMs = h.openNow + h.min
        h.candles.publish(ClockEngine.lastVisibleCandleTs(h.clockMs))
        h.awaitUntil { vm.controller.state.m1.size > 0 && vm.controller.state.countdownMs == h.min }

        assertTrue("still following after a new candle", vm.controller.state.liveEdgeFollowing)
        assertEquals(0, vm.controller.pendingNewCandles)
    }
}
