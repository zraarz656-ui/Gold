package com.tradequest.engine

/**
 * Maps a real ("wall clock") instant onto the historical timeline of a dataset.
 *
 * The historical clock is the real clock shifted back by a whole number of weeks so
 * that replayed time lands in the second week of the dataset. Shifting by exact weeks
 * keeps the weekday and the time of day identical between real and historical time.
 */
object ClockEngine {

    /** One week in milliseconds. */
    const val WEEK_MS: Long = MarketTime.WEEK_MS

    /**
     * Whole-week shift applied to [realNowMs].
     *
     * The historical instant `realNowMs - offset` is guaranteed to fall in
     * `[datasetStartMs + WEEK_MS, datasetStartMs + 2 * WEEK_MS)`, i.e. the second week
     * of the dataset.
     *
     * @param realNowMs      current real time (UTC epoch ms).
     * @param datasetStartMs first timestamp available in the dataset (UTC epoch ms).
     * @return a non-negative multiple of [WEEK_MS].
     */
    fun computeOffset(realNowMs: Long, datasetStartMs: Long): Long {
        val elapsed = realNowMs - datasetStartMs
        val k = MarketTime.floorDiv(elapsed - WEEK_MS, WEEK_MS)
        return k * WEEK_MS
    }

    /** Historical "now": the real instant shifted back by [offsetMs]. */
    fun histNow(realNowMs: Long, offsetMs: Long): Long = realNowMs - offsetMs

    /**
     * Timestamp (open) of the last fully completed 1-minute candle at or before
     * [histNowMs]: floor to the minute, then step back one minute.
     */
    fun lastVisibleCandleTs(histNowMs: Long): Long =
        MarketTime.floorTo(histNowMs, MarketTime.MINUTE_MS) - MarketTime.MINUTE_MS

    /**
     * True once the historical clock has moved past the last candle the dataset holds,
     * i.e. there is no further candle left to replay.
     */
    fun isSeasonOver(histNowMs: Long, lastCandleTs: Long): Boolean =
        lastVisibleCandleTs(histNowMs) >= lastCandleTs
}
