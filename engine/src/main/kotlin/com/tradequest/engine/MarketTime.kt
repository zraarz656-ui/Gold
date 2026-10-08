package com.tradequest.engine

import java.time.ZoneId

/** Shared market-clock constants and calendar helpers. */
object MarketTime {

    /** FX day rollover zone. A trading "day" starts at 17:00 in this zone. */
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

    /** Milliseconds in one minute. */
    const val MINUTE_MS: Long = 60_000L

    /** Milliseconds in one day. */
    const val DAY_MS: Long = 24L * 60 * 60 * 1000

    /** Milliseconds in one week. */
    const val WEEK_MS: Long = 7L * DAY_MS

    /** Rollover hour (17:00) in [NEW_YORK]. */
    const val ROLLOVER_HOUR: Int = 17

    /** Floor division (works for negative dividends). */
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if (a % b != 0L && (a xor b) < 0) q - 1 else q
    }

    /** Floor [ts] down to a multiple of [sizeMs]. */
    fun floorTo(ts: Long, sizeMs: Long): Long = floorDiv(ts, sizeMs) * sizeMs
}
