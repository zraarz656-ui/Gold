package com.tradequest.data

import com.tradequest.data.EngineMapper.toEngine
import com.tradequest.engine.Candle
import com.tradequest.engine.ClockEngine
import com.tradequest.engine.Timeframe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Serves candles for the chart.
 *
 * The chart must never see a candle from the future of the replayed clock, so every read
 * is bounded by the caller's `histNow` (via [ClockEngine.lastVisibleCandleTs]). The
 * [ticker] is a single per-minute signal the chart observes to pull the newly closed
 * candle; it is not a data source of its own.
 */
class CandleRepository(private val db: TradeQuestDatabase) {

    private val _ticker = MutableStateFlow(0L)

    /** Emits the timestamp of the latest closed candle whenever one becomes available. */
    val ticker: StateFlow<Long> = _ticker.asStateFlow()

    suspend fun count(): Long = db.candleDao().count()

    suspend fun minTs(): Long? = db.candleDao().minTs()

    suspend fun maxTs(): Long? = db.candleDao().maxTs()

    /**
     * The last [limit] candles at or before [histNow], ascending.
     *
     * [histNow] is the replayed wall clock; the bound is the last fully closed minute, so
     * a candle that is still forming is never exposed.
     */
    suspend fun upTo(histNow: Long, limit: Int = DEFAULT_WINDOW): List<Candle> {
        val rows = db.candleDao().lastBefore(ClockEngine.lastVisibleCandleTs(histNow), limit)
        return rows.asReversed().map { it.toEngine() }
    }

    /** The full 1-minute series at or before the last closed minute of [histNow]. */
    suspend fun allUpTo(histNow: Long): List<Candle> =
        db.candleDao().upTo(ClockEngine.lastVisibleCandleTs(histNow)).map { it.toEngine() }

    /** Candles for the given timeframe at or before [histNow]. */
    suspend fun aggregateUpTo(histNow: Long, tf: Timeframe, limit: Int = DEFAULT_WINDOW): List<Candle> {
        val m1 = allUpTo(histNow)
        return com.tradequest.engine.Aggregator.aggregate(m1, tf).takeLast(limit)
    }

    /** The candle that closes last at or before [histNow], or null. */
    suspend fun latestClosed(histNow: Long): Candle? =
        db.candleDao().lastBefore(histNow - MINUTE_MS, 1).firstOrNull()?.toEngine()

    /**
     * Milliseconds until the candle forming at [histNow] closes (the next minute boundary).
     * The season offset is a whole number of weeks, so historical and real minute boundaries
     * coincide.
     */
    fun remainingToClose(histNow: Long): Long = MINUTE_MS - Math.floorMod(histNow, MINUTE_MS)

    /** Publish a new closed candle timestamp to the ticker. */
    fun publish(closedTs: Long) {
        if (closedTs > _ticker.value) _ticker.value = closedTs
    }

    /** Flow of the latest closed candle, re-read whenever the ticker fires. */
    fun observeLatestClosed(histNowProvider: () -> Long): Flow<Candle?> = kotlinx.coroutines.flow.flow {
        emit(latestClosed(histNowProvider()))
        ticker.collect { emit(latestClosed(histNowProvider())) }
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val DEFAULT_WINDOW = 20_000
    }
}
