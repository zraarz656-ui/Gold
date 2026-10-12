package com.tradequest.app.ui

import android.os.Looper
import com.tradequest.data.Candle1m
import com.tradequest.engine.ClockEngine
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression for the frozen current-price tag: the tag (and the Buy/Sell labels) sat on the
 * forming candle's OPEN for the whole minute because the display price was derived from
 * `_histNow`, which only advances on the minute tick — so within a minute it held the
 * minute's start instant and the display path never moved off its first value.
 *
 * The display price must be the live quote: over two minutes of replay it changes, and it
 * never sits on the forming candle's open.
 */
@RunWith(RobolectricTestRunner::class)
class LivePriceTagTest {

    private val h = TradingHarness()

    @Before fun setUp() = h.setUp()
    @After fun tearDown() = h.tearDown()

    /** The forming candle of each minute opens far outside the display band (which is anchored
     *  at the previous close), so "the tag is pinned to the open" is unambiguous. */
    private val firstMinuteOpen = 2445.0
    private val secondMinuteOpen = 2470.0

    @Test
    fun theTagPriceMovesAcrossTwoMinutesAndNeverSitsOnTheFormingOpen() {
        h.seed()
        runBlocking {
            h.db.candleDao().insertAll(
                listOf(
                    Candle1m(h.openNow, firstMinuteOpen, 2445.4, 2444.6, 2445.1, 50.0),
                    Candle1m(h.openNow + h.min, secondMinuteOpen, 2470.4, 2469.6, 2470.1, 50.0),
                ),
            )
        }
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.displayQuote.value.bid > 0.0 }

        val samples = ArrayList<Double>()
        var nextBoundary = h.openNow + h.min
        val end = h.openNow + 2 * h.min - 5_000L
        var t = h.openNow + 5_000L
        while (t <= end) {
            h.clockMs = t
            if (t >= nextBoundary) {
                // The live clock's minute tick, exactly as LiveClock drives it.
                h.candles.publish(ClockEngine.lastVisibleCandleTs(t))
                h.awaitUntil { vm.histNow.value >= nextBoundary }
                nextBoundary += h.min
            } else {
                // Let the 4 Hz display ticker paint a frame at this instant.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            }

            val bucket = Math.floorDiv(t, h.min) * h.min
            val formingOpen = if (bucket == h.openNow) firstMinuteOpen else secondMinuteOpen
            val bid = vm.displayQuote.value.bid
            assertNotEquals(
                "the tag must not sit on the forming open at t=$t",
                formingOpen, bid, 1e-9,
            )
            samples.add(bid)
            t += 5_000L
        }

        // About 22 frames over two minutes; the price must actually move within the minute.
        assertTrue("collected frames=$samples", samples.size >= 20)
        assertTrue(
            "the tag must take several distinct values, was $samples",
            samples.distinct().size >= 3,
        )
    }
}
