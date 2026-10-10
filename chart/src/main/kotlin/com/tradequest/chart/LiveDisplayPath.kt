package com.tradequest.chart

import com.tradequest.engine.Candle
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Deterministic intra-candle display path — visual only.
 *
 * Between candle closes the screen shows a price that wanders inside the forming candle's
 * real high-low range. The path is a pure function of the candle's timestamp, so the same
 * minute always plays out identically, and it is never used for fills, SL/TP triggers or
 * P&L: those always read the completed candle's OHLC.
 *
 * Guarantees the renderer and the tests rely on:
 *  - the value always stays within `[low, high]`;
 *  - over one candle the path touches both the high and the low;
 *  - at the close (progress 1) the value equals the candle's close;
 *  - the value at progress 0 is the candle's open.
 *
 * The path is built in normalised range space: `y = (price - low) / (high - low)` in [0, 1].
 * It starts at the open's position, visits both extremes as exact nodes, and ends at the
 * close's position. Each segment is a smoothstep between two nodes, so `y` never leaves the
 * interval spanned by its endpoints — hence never leaves [0, 1].
 */
object LiveDisplayPath {

    /** Display updates per second. */
    const val UPDATES_PER_SECOND = 4

    private const val MS_PER_SECOND = 1_000.0
    private const val MINUTE_MS = 60_000.0
    private const val EPS = 1e-9

    /**
     * The displayed price for [candle] at [elapsedMs] into its minute. [elapsedMs] outside
     * `[0, minute)` is clamped, so a stale tick shows the open (before) or the close (after).
     */
    fun valueAt(candle: Candle, elapsedMs: Double): Double {
        val h = max(candle.h, candle.l)
        val l = min(candle.h, candle.l)
        if (h - l <= EPS) return h

        val p = (elapsedMs / MINUTE_MS).coerceIn(0.0, 1.0)
        val y = normalised(candle.ts, candle.o, candle.c, h, l, p)
        return (l + (h - l) * y).coerceIn(l, h)
    }

    /** The display value at whole 4-per-second ticks `[0, seconds]` seconds into the candle. */
    fun samples(candle: Candle, seconds: Int): List<Double> {
        val ticks = seconds * UPDATES_PER_SECOND
        return (0..ticks).map { tick -> valueAt(candle, tick * (MS_PER_SECOND / UPDATES_PER_SECOND)) }
    }

    /**
     * Normalised path in [0, 1]. Nodes, in time order: the open, then the two extremes
     * (ordered by the timestamp's parity so different minutes differ), then the close.
     * The extreme nodes sit on whole 4-per-second ticks, so a sampled tick lands on each
     * extreme exactly.
     */
    private fun normalised(ts: Long, o: Double, c: Double, h: Double, l: Double, p: Double): Double {
        val span = h - l
        val yOpen = ((o - l) / span).coerceIn(0.0, 1.0)
        val yClose = ((c - l) / span).coerceIn(0.0, 1.0)

        val rnd = Random(ts)
        // 0.20 .. 0.40 and 0.60 .. 0.80, both on the 240-tick grid of a minute.
        val a = rnd.nextInt(48, 97) / 240.0
        val b = rnd.nextInt(144, 193) / 240.0
        val highFirst = ts % 2L == 0L
        val firstExtreme = if (highFirst) 1.0 else 0.0
        val secondExtreme = 1.0 - firstExtreme

        return when {
            p <= a -> lerpSmooth(yOpen, firstExtreme, p / a)
            p <= b -> lerpSmooth(firstExtreme, secondExtreme, (p - a) / (b - a))
            else -> lerpSmooth(secondExtreme, yClose, (p - b) / (1.0 - b))
        }
    }

    /** Monotone smoothstep between [from] and [to]; stays inside `[min, max]` for t in [0,1]. */
    private fun lerpSmooth(from: Double, to: Double, t: Double): Double {
        val s = t.coerceIn(0.0, 1.0)
        val e = s * s * (3.0 - 2.0 * s)
        return from + (to - from) * e
    }
}
