package com.tradequest.data

import com.tradequest.engine.MarketTime
import java.time.Instant
import java.time.LocalDate

/**
 * Why a candle gap exists.
 *
 * * [DAILY_BREAK] — the routine ~1-hour break around the 17:00 New York rollover.
 * * [WEEKEND] — the Friday-17:00 → Sunday-17:00 New York weekend shut (plus the short
 *   Sunday re-open delay the data carries).
 * * [UNEXPECTED] — anything else: a real hole in the data worth investigating.
 */
enum class GapKind { DAILY_BREAK, WEEKEND, UNEXPECTED }

/** A gap plus its classification. Times are UTC epoch ms. */
data class ClassifiedGap(val fromTs: Long, val toTs: Long, val minutes: Long, val kind: GapKind) {
    /** Human length, e.g. `53h 02m` or `3h 32m`. */
    val length: String get() = "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
}

/** Everything the Data panel shows, read straight from Room. */
data class DatabaseSummary(
    val candleCount: Long,
    val firstTs: Long,
    val lastTs: Long,
    val newsCount: Long,
    val unexpectedGaps: List<ClassifiedGap>,
    val normalGapCount: Int = 0,
    val closeFirst: Double = 0.0,
    val closeLast: Double = 0.0,
    val closeMin: Double = 0.0,
    val closeMedian: Double = 0.0,
    val closeMax: Double = 0.0,
)

/**
 * Reads small aggregates out of the candle table for the Data panel.
 *
 * [summary] streams the table once (SELECT ts ORDER BY ts) to find gaps, which is fine for
 * the ~100k-row personal dataset; it never materialises the full OHLC rows.
 */
object DatabaseIntrospector {

    /** A gap wider than this many minutes is worth reporting. */
    const val GAP_MINUTES = 5L
    private const val GAP_MS = GAP_MINUTES * MarketTime.MINUTE_MS

    /**
     * How much of a gap may fall outside the routine closed windows and still count as
     * "normal". The daily break is ~1h; the weekend data re-opens ~2h after Sunday 17:00,
     * so a small margin keeps those normal while a Sep-25-scale hole stays unexpected.
     * Chosen at 150 min: comfortably above the 120-min weekend re-open delay and safely
     * below the 182-minute early close that makes the July-4 weekend unexpected.
     */
    private const val NORMAL_SLOP_MS = 150 * MarketTime.MINUTE_MS

    suspend fun summary(db: TradeQuestDatabase): DatabaseSummary {
        val candles = db.candleDao().count()
        val first = db.candleDao().minTs()
        val last = db.candleDao().maxTs()
        val news = db.newsDao().count()
        if (candles == 0L || first == null || last == null) {
            return DatabaseSummary(candles, 0L, 0L, news, emptyList())
        }
        val classified = ArrayList<ClassifiedGap>()
        var prev = Long.MIN_VALUE
        db.candleDao().allTimestamps().forEach { ts ->
            if (prev != Long.MIN_VALUE && ts - prev > GAP_MS) {
                classified.add(classify(prev, ts))
            }
            prev = ts
        }
        val closes = db.candleDao().allCloses()
        return DatabaseSummary(
            candleCount = candles,
            firstTs = first,
            lastTs = last,
            newsCount = news,
            unexpectedGaps = classified.filter { it.kind == GapKind.UNEXPECTED },
            normalGapCount = classified.count { it.kind != GapKind.UNEXPECTED },
            closeFirst = closes.firstOrNull() ?: 0.0,
            closeLast = closes.lastOrNull() ?: 0.0,
            closeMin = closes.minOrNull() ?: 0.0,
            closeMedian = median(closes),
            closeMax = closes.maxOrNull() ?: 0.0,
        )
    }

    /** Classify every >[GAP_MINUTES] gap into normal (daily/weekend) or unexpected. */
    fun classify(fromTs: Long, toTs: Long): ClassifiedGap {
        val minutes = (toTs - fromTs) / MarketTime.MINUTE_MS
        val kind = when {
            // A gap that swallows a whole weekend window, with only routine slack outside it.
            coversWeekend(fromTs, toTs) -> GapKind.WEEKEND
            // A short gap entirely inside one New York day is the daily rollover break.
            toTs - fromTs <= NORMAL_SLOP_MS && dayOf(fromTs) == dayOf(toTs) -> GapKind.DAILY_BREAK
            else -> GapKind.UNEXPECTED
        }
        return ClassifiedGap(fromTs, toTs, minutes, kind)
    }

    private const val ROLLOVER_HOUR = MarketTime.ROLLOVER_HOUR
    private val NY = MarketTime.NEW_YORK

    private fun localDate(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(NY).toLocalDate()
    private fun dayOf(ts: Long): LocalDate = localDate(ts)

    private fun at(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(NY).toInstant().toEpochMilli()

    /**
     * The weekend window (Friday 17:00 → Sunday 17:00 New York) that follows [fromTs].
     * Returns the window's start and end as UTC epoch ms.
     */
    private fun followingWeekend(fromTs: Long): Pair<Long, Long> {
        val z = Instant.ofEpochMilli(fromTs).atZone(NY)
        val daysSinceSunday = z.dayOfWeek.value % 7L
        val sunday = z.toLocalDate().minusDays(daysSinceSunday)
        val candidate = at(sunday, ROLLOVER_HOUR)
        val baseSunday = if (candidate <= fromTs) sunday else sunday.minusWeeks(1)
        return at(baseSunday.plusDays(5), ROLLOVER_HOUR) to at(baseSunday.plusDays(7), ROLLOVER_HOUR)
    }

    /**
     * True when [fromTs]..[toTs] spans a full weekend window and only routine slack
     * (<= [NORMAL_SLOP_MS]) lies outside it. The Sep-25-style hole is far longer than the
     * window, so it does not qualify.
     */
    private fun coversWeekend(fromTs: Long, toTs: Long): Boolean {
        val (friday17, sunday17) = followingWeekend(fromTs)
        if (toTs <= friday17) return false
        val outside = maxOf(0L, friday17 - fromTs) + maxOf(0L, toTs - sunday17)
        return outside <= NORMAL_SLOP_MS
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
