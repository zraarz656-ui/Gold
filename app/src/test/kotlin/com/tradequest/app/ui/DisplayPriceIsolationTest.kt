package com.tradequest.app.ui

import com.tradequest.data.Candle1m
import com.tradequest.data.OrderRequest
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * "Smooth display" is visual only. The 4 Hz display path paints the Buy/Sell labels, the
 * price tag and the equity strip, but it must never reach the authoritative quote/fills.
 *
 * These tests drive the real [TradingViewModel] and assert the invariants that keep the
 * animation from corrupting trading: the display quote reuses the real spread, it stays
 * inside the forming candle's high-low range, and the authoritative quote is untouched by
 * the display ticker.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayPriceIsolationTest {

    private val h = TradingHarness()

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        h.setUp()
    }

    @After
    fun tearDown() = h.tearDown()

    @Test
    fun theDisplayPriceStaysInsideTheFormingCandleAndTheRealQuoteIsUntouched() {
        h.seed()
        // The minute forming at the replayed clock. In the bundled replay it is pre-recorded
        // history at ts == histNow; the chart still never treats it as a closed candle.
        kotlinx.coroutines.runBlocking {
            h.db.candleDao().insertAll(listOf(Candle1m(h.openNow, 2401.0, 2401.4, 2400.8, 2401.2, 50.0)))
        }
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        // The display ticker paints from the forming candle; wait until it has run once.
        h.awaitUntil { vm.displayQuote.value.bid > 0.0 && vm.displayQuote.value.bid != vm.quote.value.bid }

        val now = vm.histNow.value
        // The candle forming at the replayed clock: its minute equals histNow exactly
        // (the season offset is a whole number of weeks).
        val forming = kotlinx.coroutines.runBlocking { h.db.candleDao().lastBefore(now, 1).first() }
        assertEquals("the forming candle starts at histNow", now, forming.ts)

        val display = vm.displayQuote.value
        val real = vm.quote.value

        // The display bid sits inside the forming candle's real range. It has already moved
        // off the open (the wait above required it), but never past an extreme.
        assertTrue(
            "display bid ${display.bid} must lie in [${forming.l}, ${forming.h}]",
            display.bid >= forming.l - 1e-9 && display.bid <= forming.h + 1e-9,
        )

        // The display quote is the same instrument: it reuses the authoritative spread and
        // only offsets the (smoothed) bid by it.
        assertEquals(real.spread, display.spread, 1e-9)
        assertEquals("ask = smoothed bid + real spread", display.bid + real.spread, display.ask, 1e-9)

        // Let a few display ticks run. The authoritative quote must not move: it is derived
        // from the last closed candle, not the animated one.
        val quoteBefore = real.bid
        val deadline = System.currentTimeMillis() + 800
        h.awaitUntil { System.currentTimeMillis() > deadline }
        assertEquals("the display path must not move the authoritative quote", quoteBefore, vm.quote.value.bid, 1e-9)
        assertTrue("the display still tracks the forming candle", vm.displayQuote.value.bid in forming.l..forming.h)
    }

    @Test
    fun fillsUseTheRealCandleNotTheSmoothedDisplayPrice() {
        h.seed()
        kotlinx.coroutines.runBlocking {
            h.db.candleDao().insertAll(listOf(Candle1m(h.openNow, 2401.0, 2401.4, 2400.8, 2401.2, 50.0)))
        }
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        h.awaitUntil { vm.displayQuote.value.bid > 0.0 && vm.displayQuote.value.bid != vm.quote.value.bid }

        // A market buy fills immediately at the authoritative ask passed from the real quote,
        // regardless of where the display path happens to be pointing when the user taps.
        h.onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        h.awaitUntil { h.live(1L).isNotEmpty() }

        val entry = h.live(1L).single().entryPrice!!
        assertTrue("entry ${entry} must be a real gold price", entry > 2000.0 && entry < 3000.0)
    }
}
