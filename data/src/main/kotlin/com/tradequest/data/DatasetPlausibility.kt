package com.tradequest.data

import com.tradequest.engine.Candle
import com.tradequest.engine.MarketCalendar

/** Outcome of a structural plausibility pass over a candle series. */
sealed interface PlausibilityResult {
    data object Pass : PlausibilityResult

    /** A check failed; [reason] is human-readable and shown verbatim by the app. */
    data class Fail(val reason: String) : PlausibilityResult
}

/**
 * Structural sanity checks for a candle series, run before it is trusted.
 *
 * These do not judge price level (that is the meta/sha provenance job) — they catch a
 * mechanically generated series: identical week after week, no daily maintenance break and
 * no weekend closure, or OHLC rows that violate the high >= low invariant.
 *
 * All times are UTC epoch ms; the weekend test uses [MarketCalendar], i.e. the 17:00 New
 * York rollover.
 */
object DatasetPlausibility {

    /**
     * The per-week candle counts must not all be identical; a machine stitch emits the same
     * count every week. A tolerance of 0 makes the check fire exactly on that condition.
     */
    const val WEEK_COUNT_TOLERANCE = 0

    /** At least this share of weeks must carry a 30..120 minute weekday gap. */
    const val REQUIRED_WEEKDAY_GAP_WEEKS = 0.9

    const val MIN_WEEKDAY_GAP_MINUTES = 30L
    const val MAX_WEEKDAY_GAP_MINUTES = 120L
    const val MIN_WEEKEND_CLOSURE_HOURS = 40
    const val MIN_MEDIAN_CLOSE = 100.0
    const val MAX_MEDIAN_CLOSE = 100_000.0

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val WEEK_MS = 7 * DAY_MS

    fun check(candles: List<Candle>): PlausibilityResult {
        if (candles.isEmpty()) return PlausibilityResult.Fail("no candles")
        val sorted = candles.sortedBy { it.ts }

        // 1. OHLC geometry.
        for (c in sorted) {
            val ok = c.h >= c.l &&
                c.o >= c.l && c.o <= c.h &&
                c.c >= c.l && c.c <= c.h
            if (!ok) {
                return PlausibilityResult.Fail(
                    "OHLC out of order at ts=${c.ts}: o=${c.o} h=${c.h} l=${c.l} c=${c.c}",
                )
            }
        }

        // 2. Median close in a gold-sane band.
        val median = median(sorted.map { it.c })
        if (median < MIN_MEDIAN_CLOSE || median > MAX_MEDIAN_CLOSE) {
            return PlausibilityResult.Fail(
                "median close $median outside $MIN_MEDIAN_CLOSE..$MAX_MEDIAN_CLOSE",
            )
        }

        // 3. Weekly candle counts must vary (a machine cannot emit identical weeks).
        val perWeek = HashMap<Long, Int>()
        for (c in sorted) perWeek.merge(weekIndex(c.ts), 1, Int::plus)
        if (perWeek.size < 2) {
            return PlausibilityResult.Fail("dataset spans fewer than two weeks")
        }
        val counts = perWeek.values
        if (counts.max() - counts.min() <= WEEK_COUNT_TOLERANCE) {
            return PlausibilityResult.Fail(
                "every week has the same candle count (${counts.first()}); generated series",
            )
        }

        // 4. Weekend closure: at least one >= 40 h gap starting on a closed minute.
        var weekendClosure = false
        // 5. Weekday gaps of 30..120 minutes, present in nearly every week.
        val weeksWithWeekdayGap = HashSet<Long>()
        for (i in 1 until sorted.size) {
            val from = sorted[i - 1].ts
            val to = sorted[i].ts
            val minutes = (to - from) / MINUTE_MS
            if (MarketCalendar.isClosed(from)) {
                if (minutes >= MIN_WEEKEND_CLOSURE_HOURS * 60L) weekendClosure = true
            } else if (minutes in MIN_WEEKDAY_GAP_MINUTES..MAX_WEEKDAY_GAP_MINUTES) {
                weeksWithWeekdayGap.add(weekIndex(from))
            }
        }
        if (!weekendClosure) {
            return PlausibilityResult.Fail(
                "no weekend closure of >= $MIN_WEEKEND_CLOSURE_HOURS h found",
            )
        }
        val withGap = perWeek.keys.count { it in weeksWithWeekdayGap }
        if (withGap < perWeek.size * REQUIRED_WEEKDAY_GAP_WEEKS) {
            return PlausibilityResult.Fail(
                "only $withGap of ${perWeek.size} weeks have a " +
                    "$MIN_WEEKDAY_GAP_MINUTES..$MAX_WEEKDAY_GAP_MINUTES minute weekday gap",
            )
        }
        return PlausibilityResult.Pass
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** Monday-UTC week bucket for [ts]. */
    private fun weekIndex(ts: Long): Long = Math.floorDiv(ts + 3 * DAY_MS, WEEK_MS)
}
