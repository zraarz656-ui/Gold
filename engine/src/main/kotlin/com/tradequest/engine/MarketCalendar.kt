package com.tradequest.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Maps a UTC timestamp to the start of the aggregation bucket that contains it.
 *
 * * M15 and H1 buckets are aligned to UTC.
 * * D1, H4 and W1 buckets follow the FX rollover at 17:00 America/New_York so DST
 *   changes move their UTC boundary by one hour.
 */
object MarketCalendar {

    private val NY = MarketTime.NEW_YORK

    /** Local rollover time (17:00) in New York. */
    private val ROLLOVER: LocalTime = LocalTime.of(MarketTime.ROLLOVER_HOUR, 0)

    private fun zdt(ts: Long): ZonedDateTime = Instant.ofEpochMilli(ts).atZone(NY)

    private fun epoch(date: LocalDate, time: LocalTime): Long =
        ZonedDateTime.of(date, time, NY).toInstant().toEpochMilli()

    /**
     * The most recent 17:00 New York boundary at or before [ts]. This is the start of
     * the FX trading day that contains [ts].
     */
    fun dayStart(ts: Long): Long {
        val z = zdt(ts)
        val today = epoch(z.toLocalDate(), ROLLOVER)
        return if (today <= ts) today else epoch(z.toLocalDate().minusDays(1), ROLLOVER)
    }

    /**
     * The six 4-hour bucket starts of the FX day that begins at [dayStartTs]:
     * 17:00 and 21:00 on the rollover date, then 01:00, 05:00, 09:00 and 13:00 on the
     * following date (New York times).
     */
    private fun h4Starts(dayStartTs: Long): LongArray {
        val base = zdt(dayStartTs).toLocalDate()
        return longArrayOf(
            epoch(base, LocalTime.of(17, 0)),
            epoch(base, LocalTime.of(21, 0)),
            epoch(base.plusDays(1), LocalTime.of(1, 0)),
            epoch(base.plusDays(1), LocalTime.of(5, 0)),
            epoch(base.plusDays(1), LocalTime.of(9, 0)),
            epoch(base.plusDays(1), LocalTime.of(13, 0)),
        )
    }

    /** The start of the 4-hour bucket containing [ts]. */
    fun h4Start(ts: Long): Long {
        val starts = h4Starts(dayStart(ts))
        var result = starts[0]
        for (s in starts) {
            if (s <= ts) result = s else break
        }
        return result
    }

    /**
     * The most recent Sunday 17:00 New York boundary at or before [ts]. This is the
     * weekly open.
     */
    fun weekStart(ts: Long): Long {
        val z = zdt(ts)
        val daysSinceSunday = z.dayOfWeek.value % 7L
        val sunday = z.toLocalDate().minusDays(daysSinceSunday)
        val candidate = epoch(sunday, ROLLOVER)
        return if (candidate <= ts) candidate else epoch(sunday.minusWeeks(1), ROLLOVER)
    }

    /**
     * True when [ts] sits in the weekend shutdown (Friday 17:00 through Sunday 17:00
     * New York), i.e. exactly when [isClosed] holds. Gaps that land inside this window
     * are the normal weekend break and are not real data gaps.
     */
    fun isWeekend(ts: Long): Boolean = isClosed(ts)

    /** True when [ts] is a regular weekday session minute, i.e. the market is open. */
    fun isWeekdayOpen(ts: Long): Boolean = !isClosed(ts)

    /**
     * True while the FX market is closed for the weekend: from the Friday 17:00 New York
     * rollover until Sunday 17:00 New York.
     */
    fun isClosed(ts: Long): Boolean {
        val z = zdt(ts)
        val minutes = z.hour * 60 + z.minute
        val rollover = MarketTime.ROLLOVER_HOUR * 60
        return when (z.dayOfWeek.value) {
            5 -> minutes >= rollover
            6 -> true
            7 -> minutes < rollover
            else -> false
        }
    }

    /**
     * Start of the [tf] bucket that contains [ts].
     *
     * @throws IllegalArgumentException if [tf] is [Timeframe.M1], which is already the
     *   finest granularity and needs no calendar mapping.
     */
    fun bucketStart(tf: Timeframe, ts: Long): Long = when (tf) {
        Timeframe.M1 -> MarketTime.floorTo(ts, MarketTime.MINUTE_MS)
        Timeframe.M15 -> MarketTime.floorTo(ts, 15 * MarketTime.MINUTE_MS)
        Timeframe.H1 -> MarketTime.floorTo(ts, 60 * MarketTime.MINUTE_MS)
        Timeframe.H4 -> h4Start(ts)
        Timeframe.D1 -> dayStart(ts)
        Timeframe.W1 -> weekStart(ts)
    }
}
