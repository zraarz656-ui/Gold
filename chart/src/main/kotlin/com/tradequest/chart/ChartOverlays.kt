package com.tradequest.chart

import com.tradequest.engine.Side

/** What an [ChartOrderLine] represents. */
enum class OrderLineKind { ENTRY, SL, TP, PENDING }

/**
 * A horizontal price line drawn over the candles: an entry, its stop/target, or a
 * pending order's trigger.
 *
 * The geometry a drag needs (which order, which side, how much money is at stake) is
 * carried here so the whole gesture can be resolved and rendered in the chart module.
 * [level] is the live, possibly uncommitted preview price of a drag; [price] is what the
 * owning order currently holds. [handles] are the "+SL"/"+TP" affordances offered on an
 * entry that has no such level yet.
 */
data class ChartOrderLine(
    val id: Long,
    val kind: OrderLineKind,
    val price: Double,
    val label: String = "",
    val pnlText: String? = null,
    val positive: Boolean = true,
    /** Entry lines are references, not levels; only SL/TP/pending respond to touches. */
    val draggable: Boolean = true,
    val side: Side = Side.LONG,
    val lots: Double = 1.0,
    val entryPrice: Double = 0.0,
    val handles: List<OrderLineKind> = emptyList(),
) {
    /** The price drawn for this line; the drag preview is a separate line. */
    val drawPrice: Double get() = price
}

/** An entry/exit arrow drawn at [barIndex]. */
data class ChartMarker(val barIndex: Int, val price: Double, val entry: Boolean, val long: Boolean)

/**
 * Everything the chart needs to draw and act on the order levels: the reference lines,
 * the market price a drag is validated against, and the spread used for the minimum
 * distance rule.
 */
data class ChartOverlayState(
    val lines: List<ChartOrderLine> = emptyList(),
    val markers: List<ChartMarker> = emptyList(),
    /** Current bid, used to colour SL/TP tags with money and percent. */
    val bid: Double = 0.0,
    /** Current spread; the minimum level distance is `spread + 0.10`. */
    val spread: Double = 0.30,
)
