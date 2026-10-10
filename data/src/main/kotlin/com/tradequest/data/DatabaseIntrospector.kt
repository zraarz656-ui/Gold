package com.tradequest.data

import com.tradequest.engine.MarketCalendar

/** One large intraday gap in the stored 1-minute candles. Times are UTC epoch ms. */
data class CandleGap(val fromTs: Long, val toTs: Long) {
    val minutes: Long get() = (toTs - fromTs) / 60_000L
}

/** Everything the Data panel shows, read straight from Room. */
data class DatabaseSummary(
    val candleCount: Long,
    val firstTs: Long,
    val lastTs: Long,
    val newsCount: Long,
    val gaps: List<CandleGap>,
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
    private const val GAP_MS = GAP_MINUTES * 60_000L

    suspend fun summary(db: TradeQuestDatabase): DatabaseSummary {
        val candles = db.candleDao().count()
        val first = db.candleDao().minTs()
        val last = db.candleDao().maxTs()
        val news = db.newsDao().count()
        if (candles == 0L || first == null || last == null) {
            return DatabaseSummary(candles, 0L, 0L, news, emptyList())
        }
        val gaps = ArrayList<CandleGap>()
        var prev = Long.MIN_VALUE
        db.candleDao().allTimestamps().forEach { ts ->
            if (prev != Long.MIN_VALUE && ts - prev > GAP_MS) gaps.add(CandleGap(prev, ts))
            prev = ts
        }
        val closes = db.candleDao().allCloses()
        return DatabaseSummary(
            candles, first, last, news, gaps,
            closeFirst = closes.firstOrNull() ?: 0.0,
            closeLast = closes.lastOrNull() ?: 0.0,
            closeMin = closes.minOrNull() ?: 0.0,
            closeMedian = median(closes),
            closeMax = closes.maxOrNull() ?: 0.0,
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * Gaps wider than [GAP_MINUTES] that fall on a weekday session, excluding the routine
     * Friday-17:00 → Sunday-17:00 weekend shut. A gap is counted when its start is a
     * weekday open; this is what the bundled weekday-only dataset should report as zero.
     */
    fun weekdayGaps(summary: DatabaseSummary): List<CandleGap> =
        summary.gaps.filter { MarketCalendar.isWeekdayOpen(it.fromTs) }
}
