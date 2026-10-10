package com.tradequest.chart

import com.tradequest.engine.Candle
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The display path is visual only, but it must be well-behaved: inside the candle's range,
 * touching both extremes, ending on the close, and identical for the same minute.
 */
class LiveDisplayPathTest {

    private val minuteMs = 60_000.0

    private fun candle(ts: Long, o: Double, h: Double, l: Double, c: Double) =
        Candle(ts, o, h, l, c, 100.0)

    /** A spread of candles: both parities, some where o > c, some flat-ish. */
    private fun candles(): List<Candle> {
        val rnd = Random(42)
        return (0 until 200).map { i ->
            val ts = 1_700_000_000_000L + i * 60_000L
            val o = 2400.0 + rnd.nextDouble(-5.0, 5.0)
            val c = o + rnd.nextDouble(-4.0, 4.0)
            val h = maxOf(o, c) + rnd.nextDouble(0.05, 3.0)
            val l = minOf(o, c) - rnd.nextDouble(0.05, 3.0)
            candle(ts, o, h, l, c)
        }
    }

    @Test
    fun staysInsideTheHighLowRangeAtEveryTick() {
        for (c in candles()) {
            for (v in LiveDisplayPath.samples(c, 60)) {
                assertTrue(v >= c.l - 1e-9, "value $v below low ${c.l} for ts=${c.ts}")
                assertTrue(v <= c.h + 1e-9, "value $v above high ${c.h} for ts=${c.ts}")
            }
        }
    }

    @Test
    fun touchesTheHighAndTheLow() {
        for (c in candles()) {
            val values = LiveDisplayPath.samples(c, 60)
            val maxV = values.max()
            val minV = values.min()
            assertEquals(c.h, maxV, 1e-6, "path never reached the high ${c.h} for ts=${c.ts}")
            assertEquals(c.l, minV, 1e-6, "path never reached the low ${c.l} for ts=${c.ts}")
        }
    }

    @Test
    fun endsExactlyOnTheClose() {
        for (c in candles()) {
            assertEquals(c.c, LiveDisplayPath.valueAt(c, minuteMs), 1e-9, "not the close at p=1")
            // A tick that arrives a little late must still show the close, not a wrapped value.
            assertEquals(c.c, LiveDisplayPath.valueAt(c, minuteMs + 500.0), 1e-9)
            // Progress clamps below zero too.
            assertEquals(c.o, LiveDisplayPath.valueAt(c, -100.0), 1e-9)
        }
    }

    @Test
    fun isDeterministicForTheSameMinute() {
        for (c in candles()) {
            val a = LiveDisplayPath.samples(c, 60)
            val b = LiveDisplayPath.samples(c, 60)
            assertEquals(a, b, "same minute produced a different path")
        }
        // And a fixed minute is stable across calls at a specific tick.
        val c = candle(1_700_000_000_123L, 2400.0, 2403.0, 2398.0, 2402.0)
        assertEquals(LiveDisplayPath.valueAt(c, 7_000.0), LiveDisplayPath.valueAt(c, 7_000.0), 1e-12)
    }

    @Test
    fun emitsFourUpdatesPerSecond() {
        val c = candles().first()
        assertEquals(1, LiveDisplayPath.samples(c, 0).size)
        assertEquals(5, LiveDisplayPath.samples(c, 1).size)   // t=0,0.25,0.5,0.75,1s
        assertEquals(241, LiveDisplayPath.samples(c, 60).size) // inclusive of the close
    }

    @Test
    fun differentMinutesUseDifferentPaths() {
        // Parity seeding: adjacent candle timestamps should not share a path when their
        // oscillation term differs.
        val a = candle(1_700_000_000_000L, 2400.0, 2404.0, 2396.0, 2402.0)
        val b = candle(1_700_000_000_000L + 1L, 2400.0, 2404.0, 2396.0, 2402.0)
        assertTrue(LiveDisplayPath.samples(a, 60) != LiveDisplayPath.samples(b, 60))
    }
}
