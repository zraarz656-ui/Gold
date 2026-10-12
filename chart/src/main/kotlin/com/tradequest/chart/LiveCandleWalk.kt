package com.tradequest.chart

import com.tradequest.engine.Candle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Random-walk generator for the next live 1-minute candle (Phase 2 placeholder).
 *
 * Phase 3 replaces this with candles produced by replaying the real dataset.
 */
object LiveCandleWalk {
    const val MIN_RANGE = 0.3
    const val RANGE_JITTER = 1.2

    fun next(prev: Candle?, rnd: Random): Candle {
        val base = prev ?: Candle(0L, 2400.0, 2400.0, 2400.0, 2400.0, 0.0)
        val ts = base.ts + 60_000L
        val o = base.c
        val body = gaussian(rnd).coerceIn(-4.0, 4.0) * 0.35
        val close = max(o + body, 1.0)
        val bodyRange = abs(close - o)
        val range = max(rnd.nextDouble() * RANGE_JITTER + MIN_RANGE, bodyRange)
        val wick = max(0.01, (range - bodyRange) / 2.0)
        val high = max(o, close) + wick
        val low = min(o, close) - wick
        val volume = rnd.nextDouble() * 700.0 + 300.0
        return Candle(ts, o, round2(high), round2(low), round2(close), round2(volume))
    }

    private fun gaussian(rnd: Random): Double {
        val u1 = max(rnd.nextDouble(), 1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(ln(u1) * -2.0) * cos(2.0 * Math.PI * u2)
    }

    private fun round2(v: Double): Double = Math.rint(v * 100.0) / 100.0
}
