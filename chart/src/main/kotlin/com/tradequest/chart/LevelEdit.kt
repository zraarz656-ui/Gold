package com.tradequest.chart

import com.tradequest.engine.FillEngine
import com.tradequest.engine.Side
import kotlin.math.abs
import kotlin.math.roundToLong

/** The order a level edit belongs to, in the terms validation needs. */
data class LevelContext(
    val id: Long,
    val kind: OrderLineKind,
    val side: Side,
    val entryPrice: Double,
    val lots: Double,
)

/** The chart line's context, so a drag can be resolved without re-reading the order. */
fun ChartOrderLine.levelContext(): LevelContext =
    LevelContext(id, kind, side, entryPrice, lots)

/**
 * A level drag in flight. [detached] distinguishes a "+SL"/"+TP" handle (which starts at
 * the entry and creates a level) from an existing line being moved. [currentPrice] is the
 * snapped preview price.
 */
data class LevelDragSession(
    val id: Long,
    val kind: OrderLineKind,
    val side: Side,
    val lots: Double,
    val entryPrice: Double,
    val detached: Boolean,
    var currentPrice: Double,
) {
    fun context(): LevelContext = LevelContext(id, kind, side, entryPrice, lots)
}

/**
 * What the user did to a level. Every gesture is reduced to one of these so the chart
 * layer can validate, render and commit without any Android types.
 */
sealed interface LevelEdit {
    /** Drag an existing SL/TP/pending line to a raw price. */
    data class Existing(val context: LevelContext, val rawPrice: Double) : LevelEdit

    /** Drag a "+SL"/"+TP" handle away from an entry to create a level. */
    data class New(val context: LevelContext, val rawPrice: Double) : LevelEdit

    /** Take the level off (its "x" on the tag, or dragging it back onto the entry). */
    data class Remove(val context: LevelContext) : LevelEdit
}

/** The resolved result of a level edit, handed back to the app layer to persist. */
sealed interface LevelOutcome {
    /** Set (or move) a level to [price]. [isNew] marks a freshly created handle level. */
    data class Set(val id: Long, val kind: OrderLineKind, val price: Double, val isNew: Boolean = false) : LevelOutcome

    /** Remove SL (kind = SL) or TP (kind = TP) from the order. */
    data class Clear(val id: Long, val kind: OrderLineKind) : LevelOutcome

    /** The gesture produced nothing (e.g. a handle released back on the entry). */
    data object Cancelled : LevelOutcome

    /** The proposed price is invalid; [message] says why and the line snaps back. */
    data class Rejected(val message: String) : LevelOutcome
}

/**
 * Pure level rules: snap to the 0.01 grid, validate against side/entry/market, and turn
 * an edit into a commit. Kept free of Compose so it is unit-testable on the JVM.
 */
object LevelRules {

    /** The level grid, matching the price rounding used everywhere else. */
    const val TICK = 0.01

    /** Extra distance beyond the spread a level must keep from the market. */
    const val MIN_DISTANCE_BASE = 0.10

    /** How close to the entry a released level must be to count as "removed". */
    const val REMOVE_TOLERANCE = 0.03

    /** Snap a raw price to the 0.01 grid. */
    fun snap(price: Double): Double = (price / TICK).roundToLong() * TICK

    /** Minimum distance a level must keep from the current price. */
    fun minDistance(spread: Double): Double = spread + MIN_DISTANCE_BASE

    /** Resolve [edit] against the current market price and spread. */
    fun resolve(edit: LevelEdit, market: Double, spread: Double): LevelOutcome = when (edit) {
        is LevelEdit.Remove -> LevelOutcome.Clear(edit.context.id, edit.context.kind)
        is LevelEdit.Existing -> resolveLevel(edit.context, edit.rawPrice, market, spread, creating = false)
        is LevelEdit.New -> resolveLevel(edit.context, edit.rawPrice, market, spread, creating = true)
    }

    private fun resolveLevel(
        ctx: LevelContext,
        rawPrice: Double,
        market: Double,
        spread: Double,
        creating: Boolean,
    ): LevelOutcome {
        val price = snap(rawPrice)

        if (ctx.kind == OrderLineKind.SL || ctx.kind == OrderLineKind.TP) {
            // Releasing on the entry removes an existing level; a new handle just cancels.
            if (abs(price - ctx.entryPrice) <= REMOVE_TOLERANCE) {
                return if (creating) LevelOutcome.Cancelled else LevelOutcome.Clear(ctx.id, ctx.kind)
            }
            validateLevel(ctx, price, market, spread)?.let { return LevelOutcome.Rejected(it) }
            return LevelOutcome.Set(ctx.id, ctx.kind, price, isNew = creating)
        }

        // Pending trigger: any price is allowed, but it must not sit on top of the market.
        if (market > 0.0 && abs(price - market) < minDistance(spread)) {
            return LevelOutcome.Rejected("Keep ${minDistance(spread).format2()} away from price")
        }
        return LevelOutcome.Set(ctx.id, ctx.kind, price)
    }

    /** Null when the level is valid, else the reason it was rejected. */
    fun validateLevel(ctx: LevelContext, price: Double, market: Double, spread: Double): String? {
        val min = minDistance(spread)
        if (market > 0.0 && abs(price - market) < min) {
            return "Keep ${min.format2()} away from price"
        }
        val long = ctx.side == Side.LONG
        return when (ctx.kind) {
            OrderLineKind.SL -> {
                val ok = if (long) price < ctx.entryPrice else price > ctx.entryPrice
                if (ok) null else if (long) "SL must be below the entry" else "SL must be above the entry"
            }
            OrderLineKind.TP -> {
                val ok = if (long) price > ctx.entryPrice else price < ctx.entryPrice
                if (ok) null else if (long) "TP must be above the entry" else "TP must be below the entry"
            }
            else -> null
        }
    }

    /** Money an SL/TP level represents at the current [lots], signed by the position side. */
    fun levelPnl(ctx: LevelContext, price: Double): Double {
        val direction = if (ctx.side == Side.LONG) 1.0 else -1.0
        return (price - ctx.entryPrice) * direction * FillEngine.LOT_OZ * ctx.lots
    }

    /** Percent of notional an SL/TP level represents, same sign as [levelPnl]. */
    fun levelPercent(ctx: LevelContext, price: Double): Double {
        val notional = ctx.entryPrice * ctx.lots * FillEngine.LOT_OZ
        return if (notional == 0.0) 0.0 else 100.0 * levelPnl(ctx, price) / notional
    }

    /** Tag text for an SL/TP line: names the price, then the money at stake. */
    fun levelTag(kind: OrderLineKind, ctx: LevelContext, price: Double): String {
        val money = signedMoney(levelPnl(ctx, price))
        val pct = "%+.1f%%".format(levelPercent(ctx, price))
        val p = formatPrice(price)
        return when (kind) {
            // Price first so every order tag names its level, e.g. "SL 2378.50  -14.90".
            OrderLineKind.SL -> "SL $p  $money ($pct)"
            OrderLineKind.TP -> "TP $p  $money"
            else -> kind.name
        }
    }

    fun signedMoney(v: Double): String = (if (v >= 0) "+" else "-") + "$" + "%.2f".format(abs(v))
}

private fun Double.format2(): String = "%.2f".format(this)
