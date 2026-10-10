package com.tradequest.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CandleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(candles: List<Candle1m>)

    @Query("SELECT COUNT(*) FROM candle_1m")
    suspend fun count(): Long

    @Query("SELECT MIN(ts) FROM candle_1m")
    suspend fun minTs(): Long?

    @Query("SELECT MAX(ts) FROM candle_1m")
    suspend fun maxTs(): Long?

    /** Inclusive-exclusive range `[from, to)`, ascending. */
    @Query("SELECT * FROM candle_1m WHERE ts >= :from AND ts < :to ORDER BY ts ASC")
    suspend fun range(from: Long, to: Long): List<Candle1m>

    /** Strictly after the last processed checkpoint and strictly before histNow. */
    @Query("SELECT * FROM candle_1m WHERE ts > :afterTs AND ts < :beforeTs ORDER BY ts ASC")
    suspend fun after(afterTs: Long, beforeTs: Long): List<Candle1m>

    @Query("SELECT COUNT(*) FROM candle_1m WHERE ts > :afterTs AND ts < :beforeTs")
    suspend fun countBetween(afterTs: Long, beforeTs: Long): Long

    /** Every candle at or before [ts], ascending. Never returns a future candle. */
    @Query("SELECT * FROM candle_1m WHERE ts <= :ts ORDER BY ts ASC")
    suspend fun upTo(ts: Long): List<Candle1m>

    /** Close of the earliest candle, or null when the table is empty. */
    @Query("SELECT c FROM candle_1m ORDER BY ts ASC LIMIT 1")
    suspend fun firstClose(): Double?

    /** Close of the latest candle, or null when the table is empty. */
    @Query("SELECT c FROM candle_1m ORDER BY ts DESC LIMIT 1")
    suspend fun lastClose(): Double?

    /** All closes ascending; used to derive min/median/max for the debug panel. */
    @Query("SELECT c FROM candle_1m ORDER BY ts ASC")
    suspend fun allCloses(): List<Double>

    @Query("SELECT * FROM candle_1m WHERE ts <= :ts ORDER BY ts DESC LIMIT :limit")
    suspend fun lastBefore(ts: Long, limit: Int): List<Candle1m>

    /** All candle timestamps ascending; used to scan for gaps without loading OHLC rows. */
    @Query("SELECT ts FROM candle_1m ORDER BY ts ASC")
    suspend fun allTimestamps(): List<Long>

    @Query("DELETE FROM candle_1m")
    suspend fun deleteAll()
}

@Dao
interface NewsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(news: List<NewsEntity>)

    @Query("SELECT COUNT(*) FROM news_event")
    suspend fun count(): Long

    @Query("SELECT * FROM news_event ORDER BY ts ASC")
    suspend fun all(): List<NewsEntity>

    @Query("SELECT * FROM news_event WHERE ts BETWEEN :from AND :to ORDER BY ts ASC")
    suspend fun between(from: Long, to: Long): List<NewsEntity>

    @Query("DELETE FROM news_event")
    suspend fun deleteAll()
}

@Dao
interface SeasonDao {
    @Insert
    suspend fun insert(season: Season): Long

    @Upsert
    suspend fun upsert(season: Season)

    @Query("SELECT * FROM season WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    suspend fun active(): Season?

    @Query("SELECT * FROM season WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    fun activeFlow(): Flow<Season?>

    @Query("SELECT * FROM season WHERE id = :id")
    suspend fun byId(id: Long): Season?

    @Query("UPDATE season SET lastProcessedTs = :ts WHERE id = :id")
    suspend fun updateLastProcessed(id: Long, ts: Long)

    @Query("UPDATE season SET needsReset = 1 WHERE needsReset = 0")
    suspend fun flagAllForReset()

    @Query("UPDATE season SET needsReset = 0 WHERE id = :id")
    suspend fun clearNeedsReset(id: Long)

    @Query("UPDATE season SET status = :status, endedAt = :endedAt, score = :score WHERE id = :id")
    suspend fun end(id: Long, status: SeasonStatus, endedAt: Long, score: Double)

    @Query("SELECT COUNT(*) FROM season")
    suspend fun count(): Long

    @Query("DELETE FROM season")
    suspend fun deleteAll()
}

@Dao
interface TradeOrderDao {
    @Insert
    suspend fun insert(order: TradeOrder): Long

    @Insert
    suspend fun insertAll(orders: List<TradeOrder>)

    @Update
    suspend fun update(order: TradeOrder)

    @Query("SELECT * FROM trade_order WHERE seasonId = :seasonId ORDER BY id ASC")
    suspend fun bySeason(seasonId: Long): List<TradeOrder>

    @Query("SELECT * FROM trade_order WHERE seasonId = :seasonId ORDER BY id ASC")
    fun bySeasonFlow(seasonId: Long): Flow<List<TradeOrder>>

    @Query("SELECT * FROM trade_order WHERE seasonId = :seasonId AND status IN ('PENDING', 'QUEUED', 'OPEN') ORDER BY id ASC")
    suspend fun live(seasonId: Long): List<TradeOrder>

    @Query("SELECT * FROM trade_order WHERE seasonId = :seasonId AND status IN ('PENDING', 'QUEUED', 'OPEN') ORDER BY id ASC")
    fun liveFlow(seasonId: Long): Flow<List<TradeOrder>>

    @Query("SELECT * FROM trade_order WHERE id = :id")
    suspend fun byId(id: Long): TradeOrder?

    @Query("UPDATE trade_order SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: OrderStatus)

    @Query("UPDATE trade_order SET sl = :sl, tp = :tp WHERE id = :id")
    suspend fun updateStops(id: Long, sl: Double?, tp: Double?)

    /** Drop the live rows before rewriting them from the engine projection. */
    @Query("DELETE FROM trade_order WHERE seasonId = :seasonId AND status IN ('PENDING', 'QUEUED', 'OPEN')")
    suspend fun deleteLive(seasonId: Long)

    @Query("SELECT * FROM trade_order WHERE seasonId = :seasonId AND status = 'CLOSED' ORDER BY closedAt ASC, id ASC")
    suspend fun closed(seasonId: Long): List<TradeOrder>

    @Query("SELECT COUNT(*) FROM trade_order WHERE seasonId = :seasonId AND status = 'CLOSED'")
    suspend fun closedCount(seasonId: Long): Int

    @Query("DELETE FROM trade_order")
    suspend fun deleteAll()

    @Query(
        "SELECT * FROM trade_order WHERE seasonId = :seasonId AND status = 'CLOSED' " +
            "AND closedAt >= :sinceTs ORDER BY closedAt ASC, id ASC",
    )
    fun closedFlow(seasonId: Long, sinceTs: Long): Flow<List<TradeOrder>>
}

@Dao
interface DailyStatsDao {
    @Upsert
    suspend fun upsert(stats: DailyStats)

    @Query("SELECT * FROM daily_stats WHERE seasonId = :seasonId AND dayKey = :dayKey")
    suspend fun forDay(seasonId: Long, dayKey: Long): DailyStats?

    @Query("SELECT * FROM daily_stats WHERE seasonId = :seasonId ORDER BY dayKey ASC")
    suspend fun bySeason(seasonId: Long): List<DailyStats>

    @Query("DELETE FROM daily_stats")
    suspend fun deleteAll()
}

@Dao
interface EquitySnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(snapshots: List<EquitySnapshotEntity>)

    @Query("SELECT * FROM equity_snapshot WHERE seasonId = :seasonId ORDER BY ts ASC")
    suspend fun bySeason(seasonId: Long): List<EquitySnapshotEntity>

    @Query("SELECT * FROM equity_snapshot WHERE seasonId = :seasonId ORDER BY ts DESC LIMIT 1")
    suspend fun latest(seasonId: Long): EquitySnapshotEntity?

    @Query("DELETE FROM equity_snapshot")
    suspend fun deleteAll()
}

@Dao
interface SettingsDao {
    @Upsert
    suspend fun put(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE key = :key")
    fun flow(key: String): kotlinx.coroutines.flow.Flow<String?>

    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingEntity>

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)
}
