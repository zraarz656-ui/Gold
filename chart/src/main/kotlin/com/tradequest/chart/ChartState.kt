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
    val orderLines: List<ChartOrderLine> = emptyList(),
    val markers: List<ChartMarker> = emptyList(),
    val marketClosed: Boolean = false,
) {
    val barCount: Int get() = bars.size
}
