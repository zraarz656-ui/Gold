package com.tradequest.data

import com.tradequest.engine.Candle
import com.tradequest.engine.Impact
import com.tradequest.engine.NewsEvent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** A generated candle series plus its news markers. */
data class GeneratedData(val candles: List<Candle>, val news: List<NewsEvent>)

/**
 * Deterministic synthetic XAUUSD data for the debug "fake generator" fallback.
 *
 * **Test-only.** The importer reaches this only through `DatasetImporter.import(allowFake
 * = true)`, which no production caller passes; the app shows a data-error screen when the
 * bundled asset is missing or smaller than the row gate. Kept self-contained so the data
 * module carries no dependency on the chart module.
 */
object FakeCandles {
    private const val MINUTE_MS = 60_000L
    private const val WEEK_MS = 604_800_000L

    private val TITLES = listOf(
        "US Non-Farm Payrolls", "FOMC Statement", "US CPI y/y", "ISM Manufacturing PMI",
        "Fed Chair Speech", "US Retail Sales", "Unemployment Claims", "ECB Rate Decision",
        "US GDP q/q", "Core PCE Price Index",
    )

    /** Contiguous weekday minutes from [startMs], skipping Saturday and Sunday. */
    fun generate(count: Int, startMs: Long, seed: Long = 42L): List<Candle> {
        val rnd = Random(seed)
        val out = ArrayList<Candle>(count)
        var price = 2400.0
        var ts = startMs
        while (out.size < count) {
            val day = Math.floorMod(ts / 86_400_000L, 7L) // 0 = Thursday of epoch, 3 = Sunday
            if (day == 4L || day == 5L) { // Saturday, Sunday
                ts += MINUTE_MS
                continue
            }
            val o = price
            val c = max(o + gaussian(rnd) * 0.35, 1.0)
            price = c
            val body = abs(c - o)
            val wick = 0.2 + rnd.nextDouble() * 0.5
            val h = max(o, c) + wick
            val l = min(o, c) - wick
            out.add(Candle(ts, round2(o), round2(h), round2(l), round2(c), round2(rnd.nextDouble() * 700 + 300)))
            ts += MINUTE_MS
        }
        return out
    }

    fun generateWithNews(count: Int, startMs: Long, seed: Long = 42L, newsCount: Int = 30): GeneratedData {
        val candles = generate(count, startMs, seed)
        return GeneratedData(candles, buildNews(candles, newsCount, seed + 1))
    }

    /** A Monday week-open so [generate] starts cleanly, "now" minus [weeksBack] weeks. */
    fun startFor(nowMs: Long, weeksBack: Long): Long {
        val floor = nowMs - weeksBack * WEEK_MS
        val day = Math.floorMod(floor / 86_400_000L, 7L)
        val toMonday = (day - 1L + 7L) % 7L // 1 = Monday of the epoch week
        return (floor / 86_400_000L - toMonday) * 86_400_000L
    }

    private fun buildNews(candles: List<Candle>, n: Int, seed: Long): List<NewsEvent> {
        if (candles.isEmpty()) return emptyList()
        val rnd = Random(seed)
        val out = ArrayList<NewsEvent>(n)
        var attempts = 0
        while (out.size < n && attempts < n * 60) {
            attempts++
            val ts = candles[rnd.nextInt(candles.size)].ts
            val impact = when (rnd.nextInt(10)) {
                0, 1 -> Impact.HIGH
                2, 3, 4 -> Impact.MEDIUM
                else -> Impact.LOW
            }
            out.add(NewsEvent(ts, TITLES[rnd.nextInt(TITLES.size)], impact))
        }
        return out.sortedBy { it.ts }
    }

    private fun round2(v: Double): Double = Math.rint(v * 100.0) / 100.0

    private fun gaussian(rnd: Random): Double {
        val u1 = max(rnd.nextDouble(), 1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(ln(u1) * -2.0) * cos(2.0 * Math.PI * u2)
    }
}
