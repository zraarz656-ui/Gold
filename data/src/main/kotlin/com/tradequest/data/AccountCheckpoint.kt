package com.tradequest.data

import com.tradequest.engine.AccountState
import com.tradequest.engine.ClosedPosition
import com.tradequest.engine.Order
import com.tradequest.engine.OrderType
import com.tradequest.engine.Position
import com.tradequest.engine.Side
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Exact, serialisable snapshot of [AccountState].
 *
 * This is the authoritative engine checkpoint. It is written in the same transaction as
 * [Season.lastProcessedTs], so catch-up always resumes from a consistent pair and an
 * interrupted run produces exactly the same result as an uninterrupted one.
 */
@Serializable
data class AccountStateDto(
    val balance: Double,
    val orders: List<OrderDto> = emptyList(),
    val positions: List<PositionDto> = emptyList(),
    val dayTs: Long = Long.MIN_VALUE,
    val dayStartEquity: Double = 0.0,
    val dailyBlocked: Boolean = false,
    val dailyWarned: Boolean = false,
    val nextPositionId: Long = 1L,
) {
    fun toEngine(): AccountState = AccountState(
        balance = balance,
        orders = orders.map { it.toEngine() },
        positions = positions.map { it.toEngine() },
        dayTs = dayTs,
        dayStartEquity = dayStartEquity,
        dailyBlocked = dailyBlocked,
        dailyWarned = dailyWarned,
        nextPositionId = nextPositionId,
    )

    companion object {
        fun from(state: AccountState): AccountStateDto = AccountStateDto(
            balance = state.balance,
            orders = state.orders.map { OrderDto.from(it) },
            positions = state.positions.map { PositionDto.from(it) },
            dayTs = state.dayTs,
            dayStartEquity = state.dayStartEquity,
            dailyBlocked = state.dailyBlocked,
            dailyWarned = state.dailyWarned,
            nextPositionId = state.nextPositionId,
        )

        fun initial(balance: Double): AccountStateDto =
            AccountStateDto(balance = balance, dayStartEquity = balance)
    }
}

@Serializable
data class OrderDto(
    val id: Long,
    val side: Side,
    val type: OrderType,
    val lots: Double,
    val price: Double? = null,
    val sl: Double? = null,
    val tp: Double? = null,
    val placedAtTs: Long,
    val trailDistance: Double? = null,
    val queued: Boolean = false,
) {
    fun toEngine(): Order = Order(id, side, type, lots, price, sl, tp, placedAtTs, trailDistance, queued)

    companion object {
        fun from(o: Order): OrderDto =
            OrderDto(o.id, o.side, o.type, o.lots, o.price, o.sl, o.tp, o.placedAtTs, o.trailDistance, o.queued)
    }
}

@Serializable
data class PositionDto(
    val id: Long,
    val side: Side,
    val lots: Double,
    val entryPrice: Double,
    val openedAtTs: Long,
    val sl: Double? = null,
    val tp: Double? = null,
    val trailDistance: Double? = null,
    val pendingTrail: Double? = null,
) {
    fun toEngine(): Position =
        Position(id, side, lots, entryPrice, openedAtTs, sl, tp, trailDistance, pendingTrail)

    companion object {
        fun from(p: Position): PositionDto =
            PositionDto(p.id, p.side, p.lots, p.entryPrice, p.openedAtTs, p.sl, p.tp, p.trailDistance, p.pendingTrail)
    }
}

/** Reads and writes the engine checkpoint stored in `settings`. */
object AccountCheckpoint {
    const val KEY = "account_state"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(dto: AccountStateDto): String = json.encodeToString(AccountStateDto.serializer(), dto)

    fun decode(text: String?, initialBalance: Double): AccountStateDto =
        if (text.isNullOrBlank()) AccountStateDto.initial(initialBalance)
        else runCatching { json.decodeFromString(AccountStateDto.serializer(), text) }
            .getOrDefault(AccountStateDto.initial(initialBalance))
}

/** Turns an [AccountState] into the user-facing `trade_order` rows. */
object TradeProjection {

    fun pending(seasonId: Long, state: AccountState): List<TradeOrder> =
        state.orders.map { orderRow(seasonId, it) }

    fun open(seasonId: Long, state: AccountState): List<TradeOrder> =
        state.positions.map { positionRow(seasonId, it) }

    fun closedRows(seasonId: Long, closed: List<ClosedPosition>, lookup: (Long) -> TradeOrder?): List<TradeOrder> =
        closed.distinctBy { it.positionId }.map { c -> closedRow(seasonId, lookup(c.positionId), c) }

    private fun orderRow(seasonId: Long, o: Order): TradeOrder = TradeOrder(
        id = o.id,
        seasonId = seasonId,
        type = o.type,
        side = o.side,
        lots = o.lots,
        entryPrice = o.price,
        sl = o.sl,
        tp = o.tp,
        trailingDist = o.trailDistance,
        status = if (o.queued) OrderStatus.QUEUED else OrderStatus.PENDING,
        openedAt = o.placedAtTs,
        closedAt = null,
        closePrice = null,
        pnl = null,
        fees = null,
        tag = null,
        note = null,
        pendingTrail = null,
    )

    private fun positionRow(seasonId: Long, p: Position): TradeOrder = TradeOrder(
        id = p.id,
        seasonId = seasonId,
        type = OrderType.MARKET,
        side = p.side,
        lots = p.lots,
        entryPrice = p.entryPrice,
        sl = p.sl,
        tp = p.tp,
        trailingDist = p.trailDistance,
        status = OrderStatus.OPEN,
        openedAt = p.openedAtTs,
        closedAt = null,
        closePrice = null,
        pnl = null,
        fees = null,
        tag = null,
        note = null,
        pendingTrail = p.pendingTrail,
    )

    private fun closedRow(seasonId: Long, existing: TradeOrder?, c: ClosedPosition): TradeOrder = TradeOrder(
        id = c.positionId,
        seasonId = seasonId,
        type = existing?.type ?: OrderType.MARKET,
        side = c.side,
        lots = c.lots,
        entryPrice = c.entryPrice,
        sl = existing?.sl,
        tp = existing?.tp,
        trailingDist = existing?.trailingDist,
        status = OrderStatus.CLOSED,
        openedAt = existing?.openedAt,
        closedAt = c.closeTs,
        closePrice = c.exitPrice,
        pnl = c.netPnl,
        fees = c.commission,
        tag = existing?.tag,
        note = existing?.note,
        pendingTrail = null,
    )
}
