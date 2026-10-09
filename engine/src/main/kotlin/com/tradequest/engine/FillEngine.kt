package com.tradequest.engine

import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** An entry order. Pending orders carry a trigger [price]; market orders do not. */
data class Order(
    val id: Long,
    val side: Side,
    val type: OrderType,
    val lots: Double,
    /** Trigger price for pending orders; ignored for [OrderType.MARKET]. */
    val price: Double? = null,
    val sl: Double? = null,
    val tp: Double? = null,
    /** Candle timestamp used as the placement moment. */
    val placedAtTs: Long,
    /** Trailing-stop distance in price units; null disables trailing. */
    val trailDistance: Double? = null,
)

/** An open position. All fields are immutable; changes produce a copy. */
data class Position(
    val id: Long,
    val side: Side,
    val lots: Double,
    val entryPrice: Double,
    val openedAtTs: Long,
    val sl: Double?,
    val tp: Double?,
    val trailDistance: Double? = null,
    /** Trail candidate computed this candle; takes effect on the next candle. */
    val pendingTrail: Double? = null,
)

/** Immutable account state. */
data class AccountState(
    val balance: Double,
    val orders: List<Order> = emptyList(),
    val positions: List<Position> = emptyList(),
    /** FX day (17:00 NY) the state currently belongs to. */
    val dayTs: Long = Long.MIN_VALUE,
    /** Equity at the start of [dayTs]. */
    val dayStartEquity: Double = balance,
    /** True once the daily loss limit has blocked new orders. */
    val dailyBlocked: Boolean = false,
    /** True once the daily loss limit has been reported for the current day. */
    val dailyWarned: Boolean = false,
    val nextPositionId: Long = 1L,
)

/** A realised entry fill. */
data class Fill(
    val orderId: Long,
    val side: Side,
    val lots: Double,
    val price: Double,
    /** One of MARKET, PENDING or GAP. */
    val reason: String,
)

/** Why a position was closed. */
enum class CloseReason { SL, TP, STOP_OUT, MANUAL }

/** A realised close with its profit and loss. */
data class ClosedPosition(
    val positionId: Long,
    val side: Side,
    val lots: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val grossPnl: Double,
    val commission: Double,
    val netPnl: Double,
    val reason: CloseReason,
    /** Timestamp of the candle whose processing closed the position. */
    val closeTs: Long,
)

/** Risk events emitted while processing a candle. */
enum class EventType { MARGIN_WARNING, STOP_OUT, DAILY_LIMIT }

/** A risk event with the candle timestamp that produced it. */
data class EngineEvent(val type: EventType, val ts: Long, val message: String)

/** Outcome of processing one candle. */
data class ProcessResult(
    val state: AccountState,
    val fills: List<Fill>,
    val closed: List<ClosedPosition>,
    val events: List<EngineEvent>,
    val equityAtClose: Double,
)

/**
 * Deterministic execution engine.
 *
 * Prices coming in as [Candle] are **bid** prices; the ask is `bid + spread`. Every
 * function is pure: the same inputs always produce the same outputs, and account state
 * is never mutated in place.
 *
 * ## Per-candle processing order
 * 1. gap check at the open (folded into pending-order evaluation),
 * 2. trigger pending orders,
 * 3. SL/TP on open positions (SL wins ties),
 * 4. update trailing stops (effective next candle),
 * 5. margin check on worst-case equity,
 * 6. daily-loss check on worst-case equity,
 * 7. record the equity snapshot at the close.
 */
object FillEngine {

    /** Ounces per lot. */
    const val LOT_OZ: Double = 100.0

    /** Round-trip commission per lot, charged on close. */
    const val COMMISSION_PER_LOT: Double = 7.0

    /** Account leverage. */
    const val LEVERAGE: Double = 100.0

    const val NORMAL_SPREAD: Double = 0.30
    const val NEWS_SPREAD: Double = 0.80
    const val ROLLOVER_SPREAD: Double = 0.60

    const val MARGIN_WARNING_LEVEL: Double = 100.0
    const val STOP_OUT_LEVEL: Double = 50.0
    const val DAILY_LOSS_FRACTION: Double = 0.03

    /** A HIGH-impact release widens the spread within this window either side. */
    const val NEWS_SPREAD_WINDOW_MS: Long = 5 * 60_000L

    /** The rollover spread spans the ten minutes centred on 17:00 NY. */
    const val ROLLOVER_WINDOW_MS: Long = 5 * 60_000L

    /** Slippage applies only to candles within this window of a HIGH-impact release. */
    const val NEWS_SLIP_WINDOW_MS: Long = 60_000L

    const val SLIP_MIN: Double = 0.20
    const val SLIP_MAX: Double = 1.00

    /** Round a price to the nearest cent. */
    fun roundPrice(x: Double): Double = (x * 100.0).roundToLong() / 100.0

    /** Round a lot size to the nearest 0.01. */
    fun roundLots(x: Double): Double = (x * 100.0).roundToLong() / 100.0

    /**
     * Spread for the candle opening at [ts].
     *
     * Widens to [NEWS_SPREAD] within [NEWS_SPREAD_WINDOW_MS] of a HIGH-impact event and
     * to [ROLLOVER_SPREAD] within [ROLLOVER_WINDOW_MS] of the 17:00 New York rollover;
     * the wider of the two applies.
     */
    fun spreadAt(ts: Long, news: List<NewsEvent>): Double {
        var spread = NORMAL_SPREAD
        if (withinHighNews(ts, news, NEWS_SPREAD_WINDOW_MS)) spread = max(spread, NEWS_SPREAD)
        if (withinRollover(ts)) spread = max(spread, ROLLOVER_SPREAD)
        return spread
    }

    /**
     * Add [order] to [state], unless the daily loss limit currently blocks new orders.
     *
     * @return the updated state, or [state] unchanged when blocked.
     */
    fun placeOrder(state: AccountState, order: Order): AccountState =
        if (state.dailyBlocked) state else state.copy(orders = state.orders + order)

    /**
     * Process one 1-minute [candle] against [state].
     *
     * @param news news events used for spread widening and slippage.
     */
    fun processCandle(state: AccountState, candle: Candle, news: List<NewsEvent>): ProcessResult {
        val spread = spreadAt(candle.ts, news)
        var balance = state.balance
        val positions = state.positions.toMutableList()
        val orders = state.orders.toMutableList()
        val fills = mutableListOf<Fill>()
        val closed = mutableListOf<ClosedPosition>()
        val events = mutableListOf<EngineEvent>()
        var nextPositionId = state.nextPositionId

        // Day rollover: reset the daily budget at the 17:00 New York cut.
        var dayTs = state.dayTs
        var dayStartEquity = state.dayStartEquity
        var dailyBlocked = state.dailyBlocked
        var dailyWarned = state.dailyWarned
        val day = MarketCalendar.dayStart(candle.ts)
        if (day != dayTs) {
            dayTs = day
            dayStartEquity = balance + positions.sumOf { floating(it, openMark(it, candle, spread)) }
            dailyBlocked = false
            dailyWarned = false
        }

        // 1 + 2. Gap / trigger check for pending and market orders.
        for (order in orders.toList()) {
            if (candle.ts <= order.placedAtTs) continue
            val plan = pendingFill(order, candle, spread, news) ?: continue
            orders.remove(order)
            positions.add(
                Position(
                    id = nextPositionId++,
                    side = order.side,
                    lots = order.lots,
                    entryPrice = plan.price,
                    openedAtTs = candle.ts,
                    sl = order.sl,
                    tp = order.tp,
                    trailDistance = order.trailDistance,
                ),
            )
            fills.add(Fill(order.id, order.side, order.lots, plan.price, plan.reason))
        }

        // 3. SL/TP for every open position. SL wins when both trigger.
        for (p in positions.toList()) {
            var pos = p
            if (p.pendingTrail != null) {
                pos = p.copy(sl = improvedStop(p, p.pendingTrail), pendingTrail = null)
                replace(positions, p, pos)
            }
            val slFill = stopLossFill(pos, candle, spread, news)
            val tpFill = takeProfitFill(pos, candle, spread)
            val exit = slFill ?: tpFill ?: continue
            val reason = if (slFill != null) CloseReason.SL else CloseReason.TP
            val result = realise(balance, pos, exit, reason, candle.ts)
            balance = result.first
            closed.add(result.second)
            positions.remove(pos)
        }

        // 4. Trailing stops: compute a candidate now, apply it on the next candle.
        for (i in positions.indices) {
            val p = positions[i]
            val distance = p.trailDistance ?: continue
            val candidate = if (p.side == Side.LONG) candle.h - distance else candle.l + distance
            if (candidate != improvedStop(p, candidate)) continue
            positions[i] = p.copy(pendingTrail = candidate)
        }

        // 5. Margin check on worst-case equity; stop out the biggest loser until safe.
        while (positions.isNotEmpty()) {
            val used = usedMargin(positions, candle, spread)
            val level = if (used <= 0.0) Double.MAX_VALUE else worstEquity(balance, positions, candle, spread) / used * 100.0
            if (level >= STOP_OUT_LEVEL) break
            val loser = positions.minByOrNull { floating(it, worstMark(it, candle, spread)) }!!
            val result = realise(balance, loser, worstMark(loser, candle, spread), CloseReason.STOP_OUT, candle.ts)
            balance = result.first
            closed.add(result.second)
            positions.remove(loser)
            events.add(EngineEvent(EventType.STOP_OUT, candle.ts, "Stop-out closed position ${loser.id}"))
        }

        val finalUsed = usedMargin(positions, candle, spread)
        val finalLevel =
            if (positions.isEmpty() || finalUsed <= 0.0) Double.MAX_VALUE
            else worstEquity(balance, positions, candle, spread) / finalUsed * 100.0
        if (positions.isNotEmpty() && finalLevel < MARGIN_WARNING_LEVEL) {
            events.add(EngineEvent(EventType.MARGIN_WARNING, candle.ts, "Margin level %.1f%%".format(finalLevel)))
        }

        // 6. Daily loss limit on worst-case equity.
        if (!dailyWarned && worstEquity(balance, positions, candle, spread) <= dayStartEquity * (1.0 - DAILY_LOSS_FRACTION)) {
            dailyWarned = true
            dailyBlocked = true
            events.add(EngineEvent(EventType.DAILY_LIMIT, candle.ts, "Daily loss limit reached"))
        }

        // 7. Equity snapshot at the close.
        val equityAtClose = balance + positions.sumOf { floating(it, closeMark(it, candle, spread)) }

        return ProcessResult(
            state = AccountState(
                balance = balance,
                orders = orders,
                positions = positions,
                dayTs = dayTs,
                dayStartEquity = dayStartEquity,
                dailyBlocked = dailyBlocked,
                dailyWarned = dailyWarned,
                nextPositionId = nextPositionId,
            ),
            fills = fills,
            closed = closed,
            events = events,
            equityAtClose = equityAtClose,
        )
    }

    // ------------------------------------------------------------------ internals

    private data class FillPlan(val price: Double, val reason: String)

    private fun pendingFill(order: Order, candle: Candle, spread: Double, news: List<NewsEvent>): FillPlan? =
        when (order.type) {
            OrderType.MARKET -> {
                val price = if (order.side == Side.LONG) candle.o + spread else candle.o
                FillPlan(roundPrice(price), "MARKET")
            }

            OrderType.BUY_LIMIT -> {
                val target = order.price!!
                if (candle.l + spread <= target) {
                    if (candle.o + spread <= target) FillPlan(roundPrice(candle.o + spread), "GAP")
                    else FillPlan(roundPrice(target), "PENDING")
                } else null
            }

            OrderType.BUY_STOP -> {
                val target = order.price!!
                if (candle.h + spread >= target) {
                    if (candle.o + spread >= target) FillPlan(roundPrice(candle.o + spread), "GAP")
                    else FillPlan(slippage(target, up = true, orderId = order.id, ts = candle.ts, candle = candle, news = news), "PENDING")
                } else null
            }

            OrderType.SELL_LIMIT -> {
                val target = order.price!!
                if (candle.h >= target) {
                    if (candle.o >= target) FillPlan(roundPrice(candle.o), "GAP")
                    else FillPlan(roundPrice(target), "PENDING")
                } else null
            }

            OrderType.SELL_STOP -> {
                val target = order.price!!
                if (candle.l <= target) {
                    if (candle.o <= target) FillPlan(roundPrice(candle.o), "GAP")
                    else FillPlan(slippage(target, up = false, orderId = order.id, ts = candle.ts, candle = candle, news = news), "PENDING")
                } else null
            }
        }

    /** SL exit price, or null when the stop is not hit. */
    private fun stopLossFill(pos: Position, candle: Candle, spread: Double, news: List<NewsEvent>): Double? {
        val sl = pos.sl ?: return null
        return if (pos.side == Side.LONG) {
            if (candle.l > sl) null
            else if (candle.o <= sl) roundPrice(candle.o)
            else slippage(sl, up = false, orderId = pos.id, ts = candle.ts, candle = candle, news = news)
        } else {
            if (candle.h + spread < sl) null
            else if (candle.o + spread >= sl) roundPrice(candle.o + spread)
            else slippage(sl, up = true, orderId = pos.id, ts = candle.ts, candle = candle, news = news)
        }
    }

    /** TP exit price, or null when the target is not hit. TPs fill at the exact price. */
    private fun takeProfitFill(pos: Position, candle: Candle, spread: Double): Double? {
        val tp = pos.tp ?: return null
        return if (pos.side == Side.LONG) {
            if (candle.h >= tp) roundPrice(tp) else null
        } else {
            if (candle.l + spread <= tp) roundPrice(tp) else null
        }
    }

    /**
     * Slippage against the trader for stop / SL fills. Zero outside a HIGH-news window;
     * otherwise a deterministic value in [SLIP_MIN, SLIP_MAX], capped so the fill stays
     * inside the candle's [low, high] range.
     */
    private fun slippage(base: Double, up: Boolean, orderId: Long, ts: Long, candle: Candle, news: List<NewsEvent>): Double {
        if (!withinHighNews(ts, news, NEWS_SLIP_WINDOW_MS)) return roundPrice(base)
        val r = Random(seed(orderId, ts)).nextDouble()
        var slip = SLIP_MIN + r * (SLIP_MAX - SLIP_MIN)
        slip = if (up) min(slip, max(0.0, candle.h - base)) else min(slip, max(0.0, base - candle.l))
        val fill = if (up) base + slip else base - slip
        return roundPrice(fill)
    }

    /** Deterministic PRNG seed from the order id and the candle timestamp. */
    private fun seed(orderId: Long, ts: Long): Long = orderId * 1_000_003L xor (ts * 31L)

    /** Best (most protective) stop between the current one and a candidate. */
    private fun improvedStop(pos: Position, candidate: Double): Double {
        val current = pos.sl
        return if (pos.side == Side.LONG) {
            if (current == null) candidate else max(current, candidate)
        } else {
            if (current == null) candidate else min(current, candidate)
        }
    }

    private fun replace(list: MutableList<Position>, old: Position, new: Position) {
        val i = list.indexOf(old)
        if (i >= 0) list[i] = new
    }

    /** Floating P/L of [pos] marked at [mark] (bid for longs, ask for shorts). */
    private fun floating(pos: Position, mark: Double): Double =
        if (pos.side == Side.LONG) (mark - pos.entryPrice) * LOT_OZ * pos.lots
        else (pos.entryPrice - mark) * LOT_OZ * pos.lots

    /** Worst-case mark: the candle low for longs, the candle high plus spread for shorts. */
    private fun worstMark(pos: Position, candle: Candle, spread: Double): Double =
        if (pos.side == Side.LONG) candle.l else candle.h + spread

    /** Close mark: the candle close for longs, the close plus spread for shorts. */
    private fun closeMark(pos: Position, candle: Candle, spread: Double): Double =
        if (pos.side == Side.LONG) candle.c else candle.c + spread

    /** Open mark used to value positions at the start of a day. */
    private fun openMark(pos: Position, candle: Candle, spread: Double): Double =
        if (pos.side == Side.LONG) candle.o else candle.o + spread

    private fun worstEquity(balance: Double, positions: List<Position>, candle: Candle, spread: Double): Double =
        balance + positions.sumOf { floating(it, worstMark(it, candle, spread)) }

    private fun usedMargin(positions: List<Position>, candle: Candle, spread: Double): Double =
        positions.sumOf { worstMark(it, candle, spread) * it.lots }

    /** Realise [pos] at [exitPrice]: return the new balance and the close record. */
    private fun realise(
        balance: Double,
        pos: Position,
        exitPrice: Double,
        reason: CloseReason,
        closeTs: Long,
    ): Pair<Double, ClosedPosition> {
        val gross =
            if (pos.side == Side.LONG) (exitPrice - pos.entryPrice) * LOT_OZ * pos.lots
            else (pos.entryPrice - exitPrice) * LOT_OZ * pos.lots
        val commission = COMMISSION_PER_LOT * pos.lots
        return balance + gross - commission to ClosedPosition(
            positionId = pos.id,
            side = pos.side,
            lots = pos.lots,
            entryPrice = pos.entryPrice,
            exitPrice = exitPrice,
            grossPnl = gross,
            commission = commission,
            netPnl = gross - commission,
            reason = reason,
            closeTs = closeTs,
        )
    }

    /**
     * Close an open position at [exitPrice] (the current bid), returning the new state and
     * the realised trade. Used for manual and partial closes; the candle loop does not
     * call this. Partial closes keep the original position id on the remainder.
     *
     * [closeTs] is the timestamp of the last visible candle (the "now" of the live market)
     * and becomes [ClosedPosition.closeTs].
     */
    fun closePosition(
        state: AccountState,
        positionId: Long,
        exitPrice: Double,
        closeTs: Long,
        lots: Double = Double.MAX_VALUE,
    ): Pair<AccountState, ClosedPosition>? {
        val pos = state.positions.firstOrNull { it.id == positionId } ?: return null
        val closingLots = min(lots, pos.lots)
        if (closingLots <= 0.0) return null
        val realised = realise(state.balance, pos.copy(lots = closingLots), roundPrice(exitPrice), CloseReason.MANUAL, closeTs)
        val remainder = pos.lots - closingLots
        val positions = if (remainder <= 1e-9) {
            state.positions.filterNot { it.id == positionId }
        } else {
            state.positions.map { if (it.id == positionId) pos.copy(lots = roundLots(remainder)) else it }
        }
        return state.copy(balance = realised.first, positions = positions) to realised.second
    }

    /** Cancel a pending order. Returns the new state, or null when [orderId] is unknown. */
    fun cancelOrder(state: AccountState, orderId: Long): AccountState? =
        if (state.orders.none { it.id == orderId }) null
        else state.copy(orders = state.orders.filterNot { it.id == orderId })

    /** True if [ts] is within [windowMs] (inclusive) of a HIGH-impact event. */
    private fun withinHighNews(ts: Long, news: List<NewsEvent>, windowMs: Long): Boolean =
        news.any { it.impact == Impact.HIGH && kotlin.math.abs(it.ts - ts) <= windowMs }

    /** True if [ts] lies in the ten minutes around the 17:00 New York rollover. */
    private fun withinRollover(ts: Long): Boolean {
        // The boundary at or before ts + W is the only one that can be within W of ts.
        val boundary = MarketCalendar.dayStart(ts + ROLLOVER_WINDOW_MS)
        return kotlin.math.abs(ts - boundary) <= ROLLOVER_WINDOW_MS
    }
}
