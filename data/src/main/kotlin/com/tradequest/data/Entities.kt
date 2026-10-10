package com.tradequest.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tradequest.engine.Impact
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side

/** One 1-minute XAUUSD candle. [ts] is the UTC epoch-ms open and the primary key. */
@Entity(tableName = "candle_1m")
data class Candle1m(
    @PrimaryKey val ts: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val tickVolume: Double,
)

/** A scheduled news release. */
@Entity(tableName = "news_event", indices = [Index("ts")])
data class NewsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val title: String,
    val impact: Impact,
)

/** Lifecycle state of a 12-week challenge season. */
enum class SeasonStatus { ACTIVE, ENDED }

/**
 * A Gold Season challenge.
 *
 * [offsetMs] is the fixed whole-week shift from real time to historical time.
 * [lastProcessedTs] is the last 1-minute candle folded into the engine; catch-up resumes
 * from here, which makes it the crash-safe checkpoint.
 * [needsReset] is set when a forced data re-import changed the dataset's time range, which
 * invalidates the season's clock offset; the UI asks the user to start a fresh season.
 */
@Entity(tableName = "season")
data class Season(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val offsetMs: Long,
    val startBalance: Double,
    val status: SeasonStatus,
    val endedAt: Long? = null,
    val score: Double = 0.0,
    val lastProcessedTs: Long,
    val needsReset: Boolean = false,
)

/** Lifecycle state of a trade order. */
enum class OrderStatus { PENDING, QUEUED, OPEN, CLOSED, CANCELLED }

/** A trade order (pending) or open/closed position. */
@Entity(tableName = "trade_order", indices = [Index("seasonId"), Index("status")])
data class TradeOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seasonId: Long,
    val type: OrderType,
    val side: Side,
    val lots: Double,
    val entryPrice: Double?,
    val sl: Double?,
    val tp: Double?,
    val trailingDist: Double?,
    val status: OrderStatus,
    val openedAt: Long?,
    val closedAt: Long?,
    val closePrice: Double?,
    val pnl: Double?,
    val fees: Double?,
    val tag: String?,
    val note: String?,
    /** Trailing-stop candidate computed on the last candle; applied on the next one. */
    val pendingTrail: Double? = null,
)

/** Per-day rollup for the daily loss limit and the equity curve. */
@Entity(tableName = "daily_stats", primaryKeys = ["seasonId", "dayKey"])
data class DailyStats(
    val seasonId: Long,
    val dayKey: Long,
    val startEquity: Double,
    val pnl: Double,
    val limitHit: Boolean,
)

/** Equity sampled at a candle close. */
@Entity(tableName = "equity_snapshot", primaryKeys = ["seasonId", "ts"])
data class EquitySnapshotEntity(
    val seasonId: Long,
    val ts: Long,
    val equity: Double,
    val balance: Double,
    val margin: Double,
)

/** Small key/value store for app settings and import flags. */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
