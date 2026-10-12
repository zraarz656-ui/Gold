package com.tradequest.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The forming bar is built only from the previous close and the displayed price, so it never
 * reveals the forming minute's real high, low or close. These tests pin the four guarantees
 * the display relies on: the open equals the previous close, the price stays inside the
 * running high-low, the running extremes only ever extend, and the path is bounded.
 */
class FormingBarTest {

    private val minute = 60_000L

    @Test
    fun openEqualsThePreviousClose() {
        val prevClose = 2401.37
        val f = FormingBar(1_000 * minute, prevClose, prevClose, prevClose, prevClose)
        assertEquals(prevClose, f.open, 1e-12)
    }

    @Test
    fun advancingExtendsTheRunningHighLowAndKeepsTheOpen() {
        val open = 2400.0
        var f = FormingBar(0L, open, open, open, open)
        // Walk the price up then down; the open must never move and the range only widens.
        for (p in listOf(2401.0, 2402.5, 2402.0, 2399.0, 2398.5, 2400.5)) {
            f = f.advanced(p)
            assertEquals(open, f.open, 1e-12, "open is fixed at the previous close")
            assertTrue(p >= f.low - 1e-12 && p <= f.high + 1e-12, "price inside running high-low")
        }
        assertEquals(2402.5, f.high, 1e-12)
        assertEquals(2398.5, f.low, 1e-12)
        assertEquals(2400.5, f.display, 1e-12)
    }

    @Test
    fun theDisplayedPriceNeverLeavesTheEnvelopeAroundThePreviousClose() {
        val prevClose = 2400.0
        val env = 0.8
        val ts = 7L * minute
        for (t in 0 until minute step 250L) {
            val p = FormingPath.displayAt(ts, prevClose, env, t.toDouble())
            assertTrue(p >= prevClose - env - 1e-9, "price $p below the band")
            assertTrue(p <= prevClose + env + 1e-9, "price $p above the band")
        }
    }

    @Test
    fun thePathStartsAtThePreviousCloseAndTouchesBothSides() {
        val prevClose = 2400.0
        val env = 0.5
        val ts = 8L * minute
        assertEquals(prevClose, FormingPath.displayAt(ts, prevClose, env, 0.0), 1e-12)

        // Over the minute the path visits both extremes of the envelope.
        var hi = -Double.MAX_VALUE
        var lo = Double.MAX_VALUE
        for (t in 0..minute step 50L) {
            val p = FormingPath.displayAt(ts, prevClose, env, t.toDouble())
            hi = maxOf(hi, p)
            lo = minOf(lo, p)
        }
        assertEquals(prevClose + env, hi, 1e-9)
        assertEquals(prevClose - env, lo, 1e-9)
    }

    @Test
    fun thePathIsDeterministicPerMinute() {
        val a = (0 until minute.toInt() step 250).map { FormingPath.displayAt(3 * minute, 2400.0, 0.7, it.toDouble()) }
        val b = (0 until minute.toInt() step 250).map { FormingPath.displayAt(3 * minute, 2400.0, 0.7, it.toDouble()) }
        assertEquals(a, b)
        val c = (0 until minute.toInt() step 250).map { FormingPath.displayAt(4 * minute, 2400.0, 0.7, it.toDouble()) }
        assertTrue(a != c, "different minutes must differ")
    }

    @Test
    fun theFormingCandleNeverExposesTheDatasetMinute() {
        // The renderer builds the forming candle from the bar, whose high/low/close are all the
        // displayed price's running extremes — not any stored candle's OHLC.
        val f = FormingBar(0L, 2400.0, 2400.5, 2400.5, 2400.0)
        assertEquals(2400.0, f.candle.o, 1e-12)
        assertEquals(2400.5, f.candle.c, 1e-12)
        assertEquals(2400.5, f.candle.h, 1e-12)
        assertEquals(2400.0, f.candle.l, 1e-12)
    }
}
