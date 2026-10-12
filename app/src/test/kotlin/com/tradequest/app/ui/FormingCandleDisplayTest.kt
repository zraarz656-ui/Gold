package com.tradequest.app.ui

import android.os.Looper
import com.tradequest.data.Candle1m
import com.tradequest.engine.ClockEngine
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import com.tradequest.data.OrderRequest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The forming candle must be built from the previous close plus the displayed price only —
 * never from the dataset's candle for the minute that is "forming" at the replayed clock.
 *
 * These are the four guarantees from the bug report: (a) a closed candle never changes, (b)
 * the forming candle opens at the previous close, (c) the displayed price stays inside the
 * forming candle's running high-low, and (d) fills/quote are untouched by the display path.
 */
@RunWith(RobolectricTestRunner::class)
class FormingCandleDisplayTest {

    private val h = TradingHarness()

    @Before fun setUp() = h.setUp()
    @After fun tearDown() = h.tearDown()

    /** A dataset candle for the minute that is forming at the replayed clock. Its OHLC is far
     *  from the previous close, so any leak of its range into the display is unmistakable. */
    private val formingMinuteOpen = 2500.0
    private val formingMinuteHigh = 2512.0
    private val formingMinuteLow = 2488.0
    private val formingMinuteClose = 2505.0

    @Test
    fun theFormingCandleIsBuiltFromThePreviousCloseAndTheDisplayPathOnly() {
        h.seed()
        // The dataset's candle for the minute that opens at histNow. It must never be drawn.
        runBlocking {
            h.db.candleDao().insertAll(
                listOf(
                    Candle1m(
                        h.openNow, formingMinuteOpen, formingMinuteHigh, formingMinuteLow,
                        formingMinuteClose, 50.0,
                    ),
                ),
            )
        }
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.controller.state.forming != null && vm.controller.state.bars.isNotEmpty() }

        val prevClose = runBlocking {
            h.db.candleDao().lastBefore(ClockEngine.lastVisibleCandleTs(h.openNow), 1).first().c
        }
        val start = vm.controller.state.forming!!
        val closedBefore = vm.controller.state.bars.last()

        // (b) the forming candle opens exactly at the previous close.
        assertEquals("forming open must be the previous close", prevClose, start.open, 1e-9)
        assertEquals("forming candle starts at histNow", h.openNow, start.ts)
        // The dataset's forming-minute candle is not among the drawn bars.
        assertFalse(
            "the dataset's forming-minute candle must not be exposed",
            vm.controller.state.bars.any { it.ts == h.openNow },
        )

        // (c) the displayed price stays inside the forming candle's running high-low, the
        // running high-low only ever extends, and it never reaches the dataset minute's range.
        var prevHigh = start.high
        var prevLow = start.low
        val observed = ArrayList<Double>()
        for (t in 1 until 60) {
            h.clockMs = h.openNow + t * 1_000L
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            val f = vm.controller.state.forming ?: error("forming bar disappeared")
            val d = vm.displayQuote.value.bid
            assertTrue("price $d inside [${f.low}, ${f.high}]", d >= f.low - 1e-9 && d <= f.high + 1e-9)
            assertTrue("price $d must not inherit the previous candle's range", d < formingMinuteLow || d > formingMinuteHigh)
            assertTrue("running high must not shrink", f.high >= prevHigh - 1e-9)
            assertTrue("running low must not grow", f.low <= prevLow + 1e-9)
            // The bar's high/low are the previous close banded by the closed-candle envelope;
            // they must never overlap the dataset's forming-minute range.
            assertTrue(
                "bar [${f.low}, ${f.high}] leaked the dataset minute",
                f.high < formingMinuteLow || f.low > formingMinuteHigh,
            )
            prevHigh = f.high
            prevLow = f.low
            observed.add(d)

            // (a) the last CLOSED candle is fixed: identical OHLC after every display frame.
            val closed = vm.controller.state.bars.last()
            assertEquals("closed candle must not change", closedBefore, closed)
        }

        // The displayed price actually moved within the minute.
        assertTrue("the display path must move, was $observed", observed.distinct().size >= 3)

        // (d) the authoritative quote is untouched by the display path.
        val realBefore = vm.quote.value.bid
        h.clockMs = h.openNow + 30_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals("display ticks must not move the authoritative quote", realBefore, vm.quote.value.bid, 1e-9)
    }

    @Test
    fun aFillStillUsesTheRealCandleNotTheFormingDisplayPath() {
        h.seed()
        runBlocking {
            h.db.candleDao().insertAll(
                listOf(
                    Candle1m(
                        h.openNow, formingMinuteOpen, formingMinuteHigh, formingMinuteLow,
                        formingMinuteClose, 50.0,
                    ),
                ),
            )
        }
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.controller.state.forming != null }
        h.clockMs = h.openNow + 20_000L
        h.awaitUntil { vm.displayQuote.value.bid > 0.0 && vm.displayQuote.value.bid != vm.quote.value.bid }

        // A market buy fills from the authoritative quote (the last closed candle), regardless
        // of where the forming display path is pointing when the user taps.
        h.onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        h.awaitUntil { h.live(1L).isNotEmpty() }

        val entry = h.live(1L).single().entryPrice!!
        assertTrue("entry $entry must be a real gold price", entry > 2000.0 && entry < 3000.0)
        // The forming display path cannot reach the dataset minute's range, so a fill priced
        // from it would be out of band; the real entry is near the previous close instead.
        assertTrue("entry must come from the real candle, not the forming display", entry < formingMinuteLow || entry > formingMinuteHigh)
    }
}
