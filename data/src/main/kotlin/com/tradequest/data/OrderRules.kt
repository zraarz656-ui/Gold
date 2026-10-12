package com.tradequest.data

import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import kotlin.math.abs

/**
 * Structural rules for manually entered orders and stop levels.
 *
 * A price typed into a field can be anything; nothing downstream checks it. The chart's
 * drag path validates through `LevelRules`, but the order sheet and the Positions editor
 * bypass that, so an SL on the wrong side of the entry (or a resting trigger on the wrong
 * side of the market) was accepted verbatim. The engine then does exactly what it is told:
 * a long with SL above entry closes instantly at the current price, and a buy limit above
 * the market fills at once — both look like the app "closing my order by itself".
 *
 * These are pure functions returning a human-readable reason, or null when valid.
 */
object OrderRules {

    /** A resting trigger must keep at least this much beyond the spread from the market. */
    const val MIN_TRIGGER_DISTANCE_BASE = 0.10

    /** Minimum distance a resting trigger must keep from the market. */
    fun minTriggerDistance(spread: Double): Double = spread + MIN_TRIGGER_DISTANCE_BASE

    /** True when [price] is a usable positive price on the 0.01 grid. */
    fun isPrice(price: Double?): Boolean = price != null && price > 0.0 && price.isFinite()

    /** Reason an SL is impossible for [side] at [entry], or null when valid. */
    fun stopError(side: Side, entry: Double, sl: Double?): String? {
        if (sl == null) return null
        if (!isPrice(sl)) return "Stop loss must be a positive price"
        val long = side == Side.LONG
        return when {
            long && sl >= entry -> "Stop loss must be below the entry (${fmt(entry)})"
            !long && sl <= entry -> "Stop loss must be above the entry (${fmt(entry)})"
            else -> null
        }
    }

    /** Reason a TP is impossible for [side] at [entry], or null when valid. */
    fun takeProfitError(side: Side, entry: Double, tp: Double?): String? {
        if (tp == null) return null
        if (!isPrice(tp)) return "Take profit must be a positive price"
        val long = side == Side.LONG
        return when {
            long && tp <= entry -> "Take profit must be above the entry (${fmt(entry)})"
            !long && tp >= entry -> "Take profit must be below the entry (${fmt(entry)})"
            else -> null
        }
    }

    /** Reason the SL/TP pair is impossible for [side] at [entry], or null when valid. */
    fun stopsError(side: Side, entry: Double, sl: Double?, tp: Double?): String? =
        stopError(side, entry, sl) ?: takeProfitError(side, entry, tp)

    /**
     * Reason a resting trigger price is impossible, or null when valid.
     * A buy limit rests below the ask, a buy stop above it; a sell limit rests above the
     * bid, a sell stop below it.
     */
    fun triggerError(type: OrderType, price: Double?, bid: Double, ask: Double): String? {
        if (type == OrderType.MARKET) return null
        if (!isPrice(price)) return "Trigger price is required"
        val p = price!!
        if (bid <= 0.0 || ask <= 0.0) return null
        return when (type) {
            OrderType.BUY_LIMIT -> if (p >= ask) "A buy limit must be below the market (${fmt(ask)})" else null
            OrderType.BUY_STOP -> if (p <= ask) "A buy stop must be above the market (${fmt(ask)})" else null
            OrderType.SELL_LIMIT -> if (p <= bid) "A sell limit must be above the market (${fmt(bid)})" else null
            OrderType.SELL_STOP -> if (p >= bid) "A sell stop must be below the market (${fmt(bid)})" else null
            OrderType.MARKET -> null
        }
    }

    /** Reason a resting trigger sits too close to the market, or null when distant enough. */
    fun triggerDistanceError(price: Double?, bid: Double, ask: Double, spread: Double): String? {
        if (!isPrice(price)) return null
        val market = if (bid > 0.0 && ask > 0.0) (bid + ask) / 2.0 else maxOf(bid, ask)
        if (market <= 0.0) return null
        val min = minTriggerDistance(spread)
        return if (abs(price!! - market) < min) "Keep ${fmt(min)} away from the market" else null
    }

    /** Full validation of a fresh [request] against the live [bid]/[ask]; null when valid. */
    fun requestError(request: OrderRequest, bid: Double, ask: Double, spread: Double, entry: Double): String? {
        if (request.lots <= 0.0) return "Lot size must be greater than 0"
        triggerError(request.type, request.price, bid, ask)?.let { return it }
        if (request.type != OrderType.MARKET) {
            triggerDistanceError(request.price, bid, ask, spread)?.let { return it }
        }
        val side = request.side ?: sideForType(request.type)
        return stopsError(side, entry, request.sl, request.tp)
    }

    fun sideForType(type: OrderType): Side = when (type) {
        OrderType.SELL_LIMIT, OrderType.SELL_STOP -> Side.SHORT
        else -> Side.LONG
    }

    private fun fmt(v: Double): String = "%.2f".format(v)
}
