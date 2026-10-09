package com.tradequest.data

import androidx.room.withTransaction
import com.tradequest.engine.ClockEngine
import com.tradequest.engine.MarketTime

/** The 12-week Gold Season challenge settings. */
object SeasonConfig {
    const val START_BALANCE = 10_000.0
    const val DURATION_MS = 12L * ClockEngine.WEEK_MS
}

/**
 * Creates and reads the single active season.
 *
 * On first launch the dataset start is read from Room and [ClockEngine.computeOffset]
 * derives the whole-week shift; the season is then created with a $10,000 balance.
 */
class SeasonRepository(
    private val db: TradeQuestDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun active(): Season? = db.seasonDao().active()

    fun activeFlow() = db.seasonDao().activeFlow()

    /** Create the season if none exists; returns the active season either way. */
    suspend fun ensureSeason(): Season {
        db.seasonDao().active()?.let { return it }
        val datasetStart = db.candleDao().minTs() ?: clock()
        val realNow = clock()
        val offset = ClockEngine.computeOffset(realNow, datasetStart)
        val season = Season(
            startedAt = realNow,
            offsetMs = offset,
            startBalance = SeasonConfig.START_BALANCE,
            status = SeasonStatus.ACTIVE,
            lastProcessedTs = ClockEngine.histNow(realNow, offset),
        )
        val id = db.seasonDao().insert(season)
        return season.copy(id = id)
    }

    /** Historical "now" for [season]: real time shifted back by the fixed offset. */
    fun histNow(season: Season, realNow: Long = clock()): Long =
        ClockEngine.histNow(realNow, season.offsetMs)

    /** Last candle the chart may show: the last fully closed minute at or before histNow. */
    fun lastVisibleCandleTs(season: Season, realNow: Long = clock()): Long =
        ClockEngine.lastVisibleCandleTs(histNow(season, realNow))

    fun isOver(season: Season, lastCandleTs: Long, realNow: Long = clock()): Boolean =
        ClockEngine.isSeasonOver(histNow(season, realNow), lastCandleTs)

    suspend fun updateLastProcessed(seasonId: Long, ts: Long) =
        db.seasonDao().updateLastProcessed(seasonId, ts)

    suspend fun end(seasonId: Long, score: Double, endedAt: Long = clock()) =
        db.seasonDao().end(seasonId, SeasonStatus.ENDED, endedAt, score)

    /**
     * Debug-only: wipe the season, its trades, stats and equity curve, and drop the engine
     * checkpoint, then create a fresh season. Imported candles and news are kept. Legacy
     * rows are never migrated, so this is the way to clear pre-`closeTs` history.
     */
    suspend fun resetActive(): Season {
        db.withTransaction {
            db.tradeOrderDao().deleteAll()
            db.dailyStatsDao().deleteAll()
            db.equitySnapshotDao().deleteAll()
            db.seasonDao().deleteAll()
            db.settingsDao().delete(AccountCheckpoint.KEY)
        }
        return ensureSeason()
    }

    /** Progress through the 12 weeks, 0..1. */
    fun progress(season: Season, realNow: Long = clock()): Float {
        val elapsed = (realNow - season.startedAt).coerceAtLeast(0L)
        return (elapsed.toDouble() / SeasonConfig.DURATION_MS).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        val WEEK_MS = MarketTime.WEEK_MS
    }
}
