package com.tradequest.chart

/** What an [ChartOrderLine] represents. */
enum class OrderLineKind { ENTRY, SL, TP, PENDING }

/**
 * A horizontal price line drawn over the candles: an entry, its stop/target, or a
 * pending order's trigger. Draggable lines are edited back through [ChartController].
 */
data class ChartOrderLine(
    val id: Long,
    val kind: OrderLineKind,
    val price: Double,
    val label: String,
    val pnlText: String? = null,
    val positive: Boolean = true,
    val draggable: Boolean = true,
)

/** An entry/exit arrow drawn at [barIndex]. */
data class ChartMarker(val barIndex: Int, val price: Double, val entry: Boolean, val long: Boolean)
