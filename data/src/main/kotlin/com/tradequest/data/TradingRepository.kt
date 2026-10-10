package com.tradequest.data

import androidx.room.withTransaction
import com.tradequest.engine.AccountState
import com.tradequest.engine.ClosedPosition
import com.tradequest.engine.FillEngine
import com.tradequest.engine.MarketCalendar
import com.tradequest.engine.Order
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import kotlinx.coroutines.flow.Flow

/** A request from the order sheet. */
data class OrderRequest(
    val type: OrderType,
    val lots: Double,
    /** Direction. Required for [OrderType.MARKET] because the type carries no side. */
    val side: Side? = null,
    val price: Double? = null,
    val sl: Double? = null,
    val tp: Double? = null,
    val trailingDist: Double? = null,
    val tag: String? = null,
    val note: String? = null,
)

/**
 * Places and manages orders for the active season.
 *
 * The engine checkpoint in `settings` is the source of truth; the `trade_order` table is
 * rewritten from it inside the same transaction. Because [placeOrder] only appends the
 * order, the fill happens on the **next** candle during catch-up — an order can never be
 * evaluated against the candle it was placed on.
 */
class TradingRepository(private val db: TradeQuestDatabase) {

    fun liveOrders(seasonId: Long): Flow<List<TradeOrder>> = db.tradeOrderDao().liveFlow(seasonId)

    fun allOrders(seasonId: Long): Flow<List<TradeOrder>> = db.tradeOrderDao().bySeasonFlow(seasonId)

    /** Closed trades whose stored `closedAt` is at or after [sinceTs]. */
    fun closedSince(seasonId: Long, sinceTs: Long): Flow<List<TradeOrder>> =
        db.tradeOrderDao().closedFlow(seasonId, sinceTs)

    /** Every closed trade of the season, oldest first. Closed trades never vanish. */
    fun closedOrders(seasonId: Long): Flow<List<TradeOrder>> =
        db.tradeOrderDao().closedFlow(seasonId, 0L)

    suspend fun order(id: Long): TradeOrder? = db.tradeOrderDao().byId(id)

    /**
     * Place an order at the replayed instant [placedAt].
     *
     * An OPEN market fills a market order at once at the displayed price ([ask] for a long,
     * [bid] for a short) and opens the position stamped at [placedAt] — it never waits for
     * the next candle. While the market is SHUT the order is stored as QUEUED and the engine
     * fills it at the first candle after the reopen. Limit and stop orders always rest and
     * fill on a later candle.
     */
    suspend fun place(
        seasonId: Long,
        request: OrderRequest,
        placedAt: Long,
        bid: Double,
        ask: Double,
    ): Long {
        val state = loadState(seasonId)
        val orderId = state.nextPositionId
        val side = request.side ?: sideOf(request.type)
        val marketOpen = !MarketCalendar.isClosed(placedAt)
        val order = Order(
            id = orderId,
            side = side,
            type = request.type,
            lots = FillEngine.roundLots(request.lots),
            price = request.price?.let { FillEngine.roundPrice(it) },
            sl = request.sl?.let { FillEngine.roundPrice(it) },
            tp = request.tp?.let { FillEngine.roundPrice(it) },
            placedAtTs = placedAt,
            trailDistance = request.trailingDist,
            queued = request.type == OrderType.MARKET && !marketOpen,
        )
        val next = if (request.type == OrderType.MARKET && marketOpen) {
            FillEngine.fillMarketImmediately(state, order, if (side == Side.LONG) ask else bid, placedAt)
        } else {
            FillEngine.placeOrder(state, order).copy(nextPositionId = orderId + 1)
        }
        persist(seasonId, next, tag = request.tag, note = request.note)
        return orderId
    }

    suspend fun cancel(seasonId: Long, orderId: Long): Boolean {
        val state = loadState(seasonId)
        val updated = FillEngine.cancelOrder(state, orderId) ?: return false
        persist(seasonId, updated)
        return true
    }

    suspend fun editStops(seasonId: Long, orderId: Long, sl: Double?, tp: Double?) {
        val state = loadState(seasonId)
        val orders = state.orders.map {
            if (it.id == orderId) it.copy(sl = sl?.let(FillEngine::roundPrice), tp = tp?.let(FillEngine::roundPrice)) else it
        }
        val positions = state.positions.map {
            if (it.id == orderId) it.copy(sl = sl?.let(FillEngine::roundPrice), tp = tp?.let(FillEngine::roundPrice)) else it
        }
        persist(seasonId, state.copy(orders = orders, positions = positions))
    }

    suspend fun movePendingPrice(seasonId: Long, orderId: Long, price: Double) {
        val state = loadState(seasonId)
        val orders = state.orders.map {
            if (it.id == orderId) it.copy(price = FillEngine.roundPrice(price)) else it
        }
        persist(seasonId, state.copy(orders = orders))
    }

    suspend fun setTrailing(seasonId: Long, orderId: Long, distance: Double?) {
        val state = loadState(seasonId)
        val positions = state.positions.map {
            if (it.id == orderId) it.copy(trailDistance = distance) else it
        }
        persist(seasonId, state.copy(positions = positions))
    }

    /** Close all or part of an open position at [exitPrice] (the current bid). */
    suspend fun closePosition(
        seasonId: Long,
        positionId: Long,
        exitPrice: Double,
        closeTs: Long,
        lots: Double? = null,
    ): ClosedPosition? {
        val state = loadState(seasonId)
        val existing = TradeProjection.open(seasonId, state).associateBy { it.id }
        val result = FillEngine.closePosition(state, positionId, exitPrice, closeTs, lots ?: Double.MAX_VALUE)
            ?: return null
        val (updated, closed) = result
        val closedRow = TradeProjection.closedRows(seasonId, listOf(closed), existing::get).first()
        db.withTransaction {
            db.tradeOrderDao().deleteLive(seasonId)
            db.tradeOrderDao().insertAll(TradeProjection.pending(seasonId, updated))
            db.tradeOrderDao().insertAll(TradeProjection.open(seasonId, updated))
            db.tradeOrderDao().insertAll(listOf(closedRow))
            db.settingsDao().put(SettingEntity(AccountCheckpoint.KEY, AccountCheckpoint.encode(AccountStateDto.from(updated))))
        }
        return closed
    }

    private suspend fun loadState(seasonId: Long): AccountState {
        val balance = db.seasonDao().byId(seasonId)?.startBalance ?: 0.0
        return AccountCheckpoint.decode(db.settingsDao().get(AccountCheckpoint.KEY), balance).toEngine()
    }

    private suspend fun persist(seasonId: Long, state: AccountState, tag: String? = null, note: String? = null) {
        val dto = AccountStateDto.from(state)
        db.withTransaction {
            db.tradeOrderDao().deleteLive(seasonId)
            db.tradeOrderDao().insertAll(TradeProjection.pending(seasonId, state).map { it.copy(tag = tag, note = note) })
            db.tradeOrderDao().insertAll(TradeProjection.open(seasonId, state))
            db.settingsDao().put(SettingEntity(AccountCheckpoint.KEY, AccountCheckpoint.encode(dto)))
        }
    }

    private fun sideOf(type: OrderType): Side = when (type) {
        OrderType.SELL_LIMIT, OrderType.SELL_STOP -> Side.SHORT
        else -> Side.LONG
    }
}
