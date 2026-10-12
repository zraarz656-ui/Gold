package com.tradequest.chart

import com.tradequest.engine.Candle
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The minute currently forming, built only from information available at the moment.
 *
 * The dataset is replayed, so the minute that is "forming" at the replayed clock is in fact
 * pre-recorded. The chart must not reveal its high, low or close, so this bar is NOT the
 * dataset's candle for that minute: it starts at the previous close and only ever extends to
 * the displayed price. [open] equals the previous candle's close, and [high]/[low] are the
 * running extremes of the displayed price (so the open is always inside them).
 *
 * It is visual only: nothing here reaches the engine. Fills, SL/TP and P&L read the completed
 * candle, never this bar.
 */
data class FormingBar(
    val ts: Long,
    val open: Double,
    val display: Double,
    val high: Double,
    val low: Double,
) {
    /** The bar as drawn: body [open]..[display], wick [low]..[high]. */
    val candle: Candle get() = Candle(ts, open, high, low, display, 0.0)

    /** Extend the running high/low with a new displayed price. */
    fun advanced(display: Double): FormingBar =
        copy(display = display, high = max(high, display), low = min(low, display))
}

/**
 * The visual path of the displayed price inside the forming minute.
 *
 * It is a pure function of the minute's timestamp, so the same minute always plays out the
 * same way, and it is bounded by an [envelope] derived from *closed* candles (never the
 * forming minute's own high, low or close). The path starts at 0 (the previous close) and
 * wanders to +envelope then -envelope (or the mirror, by timestamp parity) and back to 0.
 *
 * Guarantees the renderer and the tests rely on:
 *  - at elapsed 0 the value is exactly the previous close (the forming open);
 *  - the value never leaves `prevClose +/- envelope`;
 *  - the open is always inside the running high-low.
 */
object FormingPath {

    /** Display updates per second. */
    const val UPDATES_PER_SECOND = 4

    private const val MINUTE_MS = 60_000.0
    private const val EPS = 1e-9

    /**
     * The displayed price [elapsedMs] into the minute that opens at [minuteTs], given the
     * previous candle's [prevClose] and the [envelope] (a small, closed-candle-derived band).
     * [elapsedMs] outside `[0, minute)` is clamped, so a stale frame shows the open (before)
     * or returns to the open (after).
     */
    fun displayAt(minuteTs: Long, prevClose: Double, envelope: Double, elapsedMs: Double): Double {
        val env = max(envelope, EPS)
        val p = (elapsedMs / MINUTE_MS).coerceIn(0.0, 1.0)
        return prevClose + env * offset(minuteTs, p)
    }

    /**
     * Normalised offset in [-1, 1]. Nodes, in time order: 0, then the two extremes (ordered
     * by the timestamp's parity so different minutes differ), then back to 0. Each segment is
     * a smoothstep, so the offset never leaves the interval spanned by its endpoints.
     */
    private fun offset(minuteTs: Long, p: Double): Double {
        val rnd = Random(minuteTs)
        // 0.20 .. 0.40 and 0.60 .. 0.80, both on the 240-tick grid of a minute.
        val a = rnd.nextInt(48, 97) / 240.0
        val b = rnd.nextInt(144, 193) / 240.0
        val first = if (minuteTs % 2L == 0L) 1.0 else -1.0
        val second = -first
        return when {
            p <= a -> lerpSmooth(0.0, first, p / a)
            p <= b -> lerpSmooth(first, second, (p - a) / (b - a))
            else -> lerpSmooth(second, 0.0, (p - b) / (1.0 - b))
        }
    }

    /** Monotone smoothstep between [from] and [to]; stays inside `[min, max]` for t in [0,1]. */
    private fun lerpSmooth(from: Double, to: Double, t: Double): Double {
        val s = t.coerceIn(0.0, 1.0)
        val e = s * s * (3.0 - 2.0 * s)
        return from + (to - from) * e
    }
}
