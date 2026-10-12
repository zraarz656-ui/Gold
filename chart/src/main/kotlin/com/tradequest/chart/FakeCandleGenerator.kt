package com.tradequest.chart

import com.tradequest.engine.Candle
import com.tradequest.engine.Impact
import com.tradequest.engine.NewsEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** A generated candle series plus its news markers. */
data class FakeDataSet(val candles: List<Candle>, val news: List<NewsEvent>)

/**
 * Deterministic synthetic XAUUSD data used before the real dataset existed (Phase 2).
 *
 * Kept for reference and tests; Phase 3 reads candles from the Room repository instead.
 */
object FakeCandleGenerator {
    private const val MINUTE_MS = 60_000L
    private const val WEEK_MS = 604_800_000L
    private val NY: ZoneId = ZoneId.of("America/New_York")

    private val TITLES = listOf(
        "US Non-Farm Payrolls", "FOMC Statement", "US CPI y/y", "ISM Manufacturing PMI",
        "Fed Chair Speech", "US Retail Sales", "Unemployment Claims", "ECB Rate Decision",
        "US GDP q/q", "Core PCE Price Index", "Treasury Auction", "Consumer Confidence",
    )

    fun isMarketOpen(ts: Long): Boolean {
        val z = Instant.ofEpochMilli(ts).atZone(NY)
        return when (z.dayOfWeek.value) {
            5 -> z.hour < 17
            6 -> false
            7 -> z.hour >= 17
            else -> true
        }
    }

    private fun weekOpen(ts: Long): Long {
        val z = Instant.ofEpochMilli(ts).atZone(NY)
        val daysSinceSunday = z.dayOfWeek.value % 7
        val sunday = z.toLocalDate().minusDays(daysSinceSunday.toLong())
        return sunday.atTime(17, 0).atZone(NY).toInstant().toEpochMilli()
    }

    fun generate(count: Int = ChartViewModel.DEFAULT_COUNT, startMs: Long = defaultStart(), seed: Long = 42L): List<Candle> {
        val rnd = Random(seed)
        val out = ArrayList<Candle>(count)
        var open = 2400.0
        var vol = 0.055
        var candlesInBurst = 0
        var ts = weekOpen(startMs)
        while (out.size < count) {
            if (!isMarketOpen(ts)) {
                ts += MINUTE_MS
                continue
            }
            val prev = out.lastOrNull()
            val base = if (prev == null || ts - prev.ts <= MINUTE_MS) {
                open
            } else {
                maxOf(prev.c + gaussian(rnd) * 3.0, 200.0)
            }
            if (candlesInBurst > 0) {
                candlesInBurst--
                vol = 0.97 * vol + 0.00165
            } else if (rnd.nextDouble() < 0.0025) {
                candlesInBurst = rnd.nextInt(75) + 20
                vol = rnd.nextDouble() * 0.3 + 0.055
            }
            vol = vol.coerceIn(0.03, 1.2)
            val close = maxOf(base + gaussian(rnd) * vol, 200.0)
            val body = abs(close - base)
            val wick = 0.6 * vol * rnd.nextDouble() + 0.3 * body
            val high = maxOf(base, close) + rnd.nextDouble() * wick
            val low = minOf(base, close) - rnd.nextDouble() * wick
            val volume = (rnd.nextDouble() * 600.0 + 400.0) * (4.0 * vol + 1.0)
            out.add(Candle(ts, round2(base), round2(high), round2(low), round2(close), round2(volume)))
            ts += MINUTE_MS
            open = close
        }
        return out
    }

    fun generateWithNews(
        count: Int = ChartViewModel.DEFAULT_COUNT,
        startMs: Long = defaultStart(),
        seed: Long = 42L,
        newsCount: Int = 30,
    ): FakeDataSet {
        val candles = generate(count, startMs, seed)
        return FakeDataSet(candles, buildNews(candles, newsCount, 1 + seed))
    }

    private fun buildNews(candles: List<Candle>, n: Int, seed: Long): List<NewsEvent> {
        if (candles.isEmpty()) return emptyList()
        val rnd = Random(seed)
        val out = ArrayList<NewsEvent>(n)
        var attempts = 0
        while (out.size < n && attempts < n * 60) {
            attempts++
            val ts = candles[rnd.nextInt(candles.size)].ts
            val z = Instant.ofEpochMilli(ts).atZone(NY)
            if (z.dayOfWeek.value <= 5 && z.hour in 7..16) {
                val impact = when (rnd.nextInt(10)) {
                    0, 1 -> Impact.HIGH
                    2, 3, 4 -> Impact.MEDIUM
                    else -> Impact.LOW
                }
                out.add(NewsEvent(ts, TITLES[rnd.nextInt(TITLES.size)], impact))
            }
        }
        return out.sortedBy { it.ts }
    }

    private fun defaultStart(): Long = weekOpen(System.currentTimeMillis() - 7_776_000_000L)

    private fun round2(v: Double): Double = Math.rint(v * 100.0) / 100.0

    private fun gaussian(rnd: Random): Double {
        val u1 = maxOf(rnd.nextDouble(), 1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(ln(u1) * -2.0) * cos(2.0 * Math.PI * u2)
    }
}
