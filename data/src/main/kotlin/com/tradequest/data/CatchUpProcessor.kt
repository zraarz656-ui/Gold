package com.tradequest.data

import androidx.room.withTransaction
import com.tradequest.data.EngineMapper.toEngine
import com.tradequest.engine.AccountState
import com.tradequest.engine.FillEngine
import com.tradequest.engine.NewsEvent
import com.tradequest.engine.Side

data class CatchUpFill(val orderId: Long, val side: Side, val lots: Double, val price: Double, val reason: String)

data class CatchUpEvent(val ts: Long, val type: String, val message: String)

data class CatchUpClose(val positionId: Long, val reason: String, val netPnl: Double)

/** What catch-up did, for the "catching up" state and for notifications. */
data class CatchUpResult(
    val processedCandles: Int,
    val fromTs: Long,
    val toTs: Long,
    val fills: List<CatchUpFill> = emptyList(),
    val events: List<CatchUpEvent> = emptyList(),
    val closes: List<CatchUpClose> = emptyList(),
) {
    val isEmpty: Boolean get() = processedCandles == 0
}

/**
 * Replays every 1-minute candle between the season's [Season.lastProcessedTs] and
 * `histNow` through [FillEngine], committing to Room in batches.
 *
 * Each batch is committed **together with** the new [Season.lastProcessedTs] and the
 * engine checkpoint, so a crash mid-way can only lose the in-flight batch. On restart,
 * catch-up resumes from the last committed checkpoint and produces the same state as an
 * uninterrupted run. Only candles with `ts < histNow` are read, so a future candle is
 * never exposed.
 */
class CatchUpProcessor(
    private val db: TradeQuestDatabase,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    companion object {
        const val DEFAULT_BATCH_SIZE = 500
        private const val NEWS_WINDOW_MS = 6 * 60_000L
    }

    suspend fun run(seasonId: Long, histNow: Long, onProgress: (Long) -> Unit = {}): CatchUpResult {
        val season = db.seasonDao().byId(seasonId) ?: return CatchUpResult(0, 0, 0)
        val from = season.lastProcessedTs
        if (from >= histNow) return CatchUpResult(0, from, from)

        val candles = db.candleDao().after(from, histNow)
        if (candles.isEmpty()) return CatchUpResult(0, from, from)

        val news = loadNews(from, histNow)
        var state: AccountState = loadState(season).toEngine()

        val allFills = ArrayList<CatchUpFill>()
        val allEvents = ArrayList<CatchUpEvent>()
        val allCloses = ArrayList<CatchUpClose>()
        var checkpoint = from

        var index = 0
        while (index < candles.size) {
            val end = minOf(index + batchSize, candles.size)
            val batch = candles.subList(index, end)
            val nextState: AccountState
            val fills = ArrayList<CatchUpFill>()
            val events = ArrayList<CatchUpEvent>()

            // Snapshot the open rows so a closed trade keeps its type/tag/note.
            val lookup = TradeProjection.open(seasonId, state).associateBy { it.id }
            val closedRows = ArrayList<TradeOrder>()
            val closes = ArrayList<CatchUpClose>()

            var s = state
            for (row in batch) {
                val result = FillEngine.processCandle(s, row.toEngine(), news)
                s = result.state
                result.fills.forEach { fills.add(CatchUpFill(it.orderId, it.side, it.lots, it.price, it.reason)) }
                result.events.forEach { events.add(CatchUpEvent(it.ts, it.type.name, it.message)) }
                result.closed.forEach { c ->
                    closedRows.add(TradeProjection.closedRows(seasonId, listOf(c), lookup::get).first())
                    closes.add(CatchUpClose(c.positionId, c.reason.name, c.netPnl))
                }
            }
            nextState = s

            val lastTs = batch.last().ts
            commit(seasonId, nextState, lastTs, closedRows)
            checkpoint = lastTs
            state = nextState
            allFills.addAll(fills)
            allEvents.addAll(events)
            allCloses.addAll(closes)
            index = end
            onProgress(checkpoint)
        }

        return CatchUpResult(candles.size, from, checkpoint, allFills, allEvents, allCloses)
    }

    /** Load the authoritative engine state, falling back to the database on first run. */
    private suspend fun loadState(season: Season): AccountStateDto {
        val text = db.settingsDao().get(AccountCheckpoint.KEY)
        if (text != null) return AccountCheckpoint.decode(text, season.startBalance)
        val closedPnl = db.tradeOrderDao().closed(season.id).sumOf { it.pnl ?: 0.0 }
        return AccountStateDto.initial(season.startBalance + closedPnl)
    }

    private suspend fun loadNews(from: Long, to: Long): List<NewsEvent> =
        db.newsDao().between(from - NEWS_WINDOW_MS, to + NEWS_WINDOW_MS).map {
            NewsEvent(it.ts, it.title, it.impact)
        }

    /** Commit rows, projections and the checkpoint atomically. */
    private suspend fun commit(
        seasonId: Long,
        state: AccountState,
        lastTs: Long,
        closedRows: List<TradeOrder>,
    ) {
        val dto = AccountStateDto.from(state)
        db.withTransaction {
            db.tradeOrderDao().deleteLive(seasonId)
            db.tradeOrderDao().insertAll(TradeProjection.pending(seasonId, state))
            db.tradeOrderDao().insertAll(TradeProjection.open(seasonId, state))
            if (closedRows.isNotEmpty()) db.tradeOrderDao().insertAll(closedRows)
            db.settingsDao().put(SettingEntity(AccountCheckpoint.KEY, AccountCheckpoint.encode(dto)))
            db.seasonDao().updateLastProcessed(seasonId, lastTs)
        }
    }
}
