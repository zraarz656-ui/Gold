package com.tradequest.chart

import com.tradequest.engine.Candle
import com.tradequest.engine.NewsEvent
import com.tradequest.engine.Timeframe

/** Timeframes offered in the chart toolbar, shortest first. */
val TIMEFRAMES: List<Timeframe> = listOf(
    Timeframe.M1, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1,
)

/** Short label used on the timeframe selector. */
fun Timeframe.label(): String = when (this) {
    Timeframe.M1 -> "1m"
    Timeframe.M15 -> "15m"
    Timeframe.H1 -> "1h"
    Timeframe.H4 -> "4h"
    Timeframe.D1 -> "D"
    Timeframe.W1 -> "W"
}

/**
 * Everything the chart needs to draw one frame.
 *
 * [m1] is the raw 1-minute series; [bars] is the active timeframe's aggregation.
 * [displayOffsetMs] shifts dataset timestamps onto the historical clock so the axis
 * reads in replayed time.
 */
data class ChartState(
    val m1: List<Candle> = emptyList(),
    val news: List<NewsEvent> = emptyList(),
    val timeframe: Timeframe = Timeframe.M15,
    val bars: List<Candle> = emptyList(),
    val viewport: Viewport = Viewport(0f, 6f),
    val theme: ChartTheme = ChartTheme.DARK,
    val displayOffsetMs: Long = 0L,
    val drawMode: Boolean = false,
    val clockMs: Long? = null,
    val liveEdgeFollowing: Boolean = true,
    val overlay: ChartOverlayState = ChartOverlayState(),
    val marketClosed: Boolean = false,
    /** Milliseconds until the current candle closes; null hides the countdown. */
    val countdownMs: Long? = null,
    /** Whether the candle-close countdown under the price tag is drawn (settings toggle). */
    val showCountdown: Boolean = true,
    /** The line being dragged, drawn at the finger's price instead of its committed price. */
    val dragPreview: DragPreview? = null,
    /** How large the price tags are drawn; persisted in DataStore. */
    val labelSize: PriceLabelSize = PriceLabelSize.default,
    /** Device density, so the chart and the hit test share one dp-based geometry. */
    val density: Float = 1f,
    /** The single position whose "+SL"/"+TP" handles are shown; null when none is selected. */
    val selectedEntryId: Long? = null,
) {
    /** Lines to draw: the overlay's lines plus the live drag preview. */
    val orderLines: List<ChartOrderLine> get() = overlay.lines
    val markers: List<ChartMarker> get() = overlay.markers

    /** Convenience for the renderer and hit test: the label-size multiplier. */
    val labelScale: Float get() = labelSize.scale

    val barCount: Int get() = bars.size
}

/** The level currently under the finger, drawn in place of its committed line. */
data class DragPreview(
    val line: ChartOrderLine,
    val price: Double,
    val x: Float,
    val y: Float,
)
