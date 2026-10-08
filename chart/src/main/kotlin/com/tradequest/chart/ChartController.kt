package com.tradequest.chart

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tradequest.engine.Candle
import com.tradequest.engine.NewsEvent
import com.tradequest.engine.Timeframe

/**
 * Owns the chart's mutable view state: the aggregated bar cache, the [ChartState] and
 * the gesture-driven viewport. All mutations go through here so the Compose layer only
 * has to read [state].
 */
class ChartController(
    m1: List<Candle>,
    news: List<NewsEvent> = emptyList(),
    initialTimeframe: Timeframe = Timeframe.M15,
) {
    private var cache = TimeframeCache(m1)

    var plotWidthPx: Float = 1000f
    var density: Float = 1f
        private set

    private var barWidthDp = 6f
    private var widthInitialized = false

    var state by mutableStateOf(
        ChartState(
            m1 = m1,
            news = news,
            timeframe = initialTimeframe,
            bars = cache.bars(initialTimeframe),
            viewport = Viewport(0f, 6f),
            theme = ChartTheme.DARK,
            liveEdgeFollowing = false,
        ),
    )
        private set

    var pendingNewCandles by mutableStateOf(0)
        private set

    init {
        jumpToLatest()
    }

    fun onLayout(densityPx: Float, plotWidthPx: Float) {
        this.density = densityPx
        this.plotWidthPx = plotWidthPx
        if (!widthInitialized) {
            widthInitialized = true
            state = state.copy(viewport = state.viewport.copy(candleWidthPx = barWidthDp * densityPx))
        }
    }

    private fun visibleCandles(): Float = plotWidthPx / state.viewport.candleWidthPx

    private fun rightPaddingCandles(): Float = RIGHT_PADDING_CANDLES

    fun jumpToLatest() {
        val scroll = (state.barCount - visibleCandles()) + rightPaddingCandles()
        state = state.copy(
            viewport = state.viewport.copy(scrollIndex = maxOf(0f, scroll)),
            liveEdgeFollowing = true,
        )
        pendingNewCandles = 0
    }

    private fun atLiveEdge(): Boolean = atLiveEdge(state.viewport.scrollIndex)

    private fun atLiveEdge(scrollIndex: Float): Boolean {
        val maxScroll = (state.barCount - visibleCandles()) + rightPaddingCandles()
        return scrollIndex >= maxScroll - 1.5f
    }

    fun pan(dxPx: Float) {
        val idxDelta = dxPx / state.viewport.candleWidthPx
        val raw = state.viewport.scrollIndex + idxDelta
        val clamped = ChartMath.clampScroll(
            raw, plotWidthPx, state.viewport.candleWidthPx, state.barCount, rightPaddingCandles(),
        )
        val following = atLiveEdge(clamped)
        state = state.copy(
            viewport = state.viewport.copy(scrollIndex = clamped),
            liveEdgeFollowing = following,
        )
        if (following) pendingNewCandles = 0
    }

    fun zoom(pivotX: Float, scale: Float) {
        val old = state.viewport.candleWidthPx
        val minPx = density * ChartMath.MIN_CANDLE_WIDTH_DP
        val maxPx = density * ChartMath.MAX_CANDLE_WIDTH_DP
        val newWidth = (old * scale).coerceIn(minPx, maxPx)
        if (newWidth == old) return
        val scroll = ChartMath.zoomAnchor(state.viewport.scrollIndex, pivotX, old, newWidth)
        val clamped = ChartMath.clampScroll(
            scroll, plotWidthPx, newWidth, state.barCount, rightPaddingCandles(),
        )
        state = state.copy(viewport = state.viewport.copy(scrollIndex = clamped, candleWidthPx = newWidth))
        barWidthDp = newWidth / density
        state = state.copy(liveEdgeFollowing = atLiveEdge())
    }

    fun setTimeframe(tf: Timeframe) {
        if (tf == state.timeframe) return
        val centerTime = centerTimeMs()
        state = state.copy(timeframe = tf, bars = cache.bars(tf))
        centerOn(centerTime)
    }

    private fun refreshActiveBars() {
        state = state.copy(bars = cache.bars(state.timeframe))
    }

    private fun centerTimeMs(): Long =
        timeAtIndex(ChartMath.xToIndex(plotWidthPx / 2f, state.viewport))

    fun timeAtIndex(index: Float): Long {
        val bars = state.bars
        if (bars.isEmpty()) return state.displayOffsetMs
        val i = index.toInt().coerceIn(0, bars.size - 1)
        val nextTs = if (i + 1 < bars.size) bars[i + 1].ts else bars[i].ts + spanMs(state.timeframe)
        val frac = index - i
        return bars[i].ts + ((nextTs - bars[i].ts) * frac).toLong() + state.displayOffsetMs
    }

    private fun centerOn(timeMs: Long) {
        val times = state.bars.map { it.ts + state.displayOffsetMs }
        val idx = ChartMath.fractionalIndex(times, timeMs)
        val scroll = idx - visibleCandles() / 2f
        val clamped = ChartMath.clampScroll(
            scroll, plotWidthPx, state.viewport.candleWidthPx, state.barCount, rightPaddingCandles(),
        )
        state = state.copy(
            viewport = state.viewport.copy(scrollIndex = clamped),
            liveEdgeFollowing = atLiveEdge(),
        )
    }

    /**
     * Fold a freshly closed 1-minute candle into the chart. When the user is at the live
     * edge the view follows; otherwise the scroll is anchored so the visible bars do not
     * jump, and [pendingNewCandles] counts the off-screen bars.
     */
    fun appendM1(candle: Candle, clockMs: Long) {
        val oldBarCount = state.barCount
        val allM1 = cache.onNewM1(candle)
        val bars = cache.bars(state.timeframe)
        val newBars = bars.size - oldBarCount
        if (atLiveEdge()) {
            pendingNewCandles = 0
            state = state.copy(m1 = allM1, bars = bars, clockMs = clockMs)
            jumpToLatest()
            return
        }
        val anchored = state.viewport.scrollIndex + maxOf(newBars, 0)
        state = state.copy(
            m1 = allM1,
            bars = bars,
            viewport = state.viewport.copy(scrollIndex = anchored),
            clockMs = clockMs,
        )
        pendingNewCandles += 1
    }

    fun setTheme(theme: ChartTheme) {
        state = state.copy(theme = theme)
    }

    fun setDrawMode(on: Boolean) {
        state = state.copy(drawMode = on)
    }

    fun resetPriceScale() {
        state = state.copy(viewport = state.viewport.copy(manualPriceMin = null, manualPriceMax = null, manualPriceScale = false))
    }

    fun setManualPriceRange(min: Double, max: Double) {
        val r = ChartMath.clampManualRange(min, max, ChartMath.fullExtent(state.bars))
        state = state.copy(
            viewport = state.viewport.copy(manualPriceMin = r.min, manualPriceMax = r.max, manualPriceScale = true),
        )
    }

    fun currentPriceRange(): PriceRange {
        val vp = state.viewport
        if (vp.manualPriceScale) {
            return ChartMath.clampManualRange(
                vp.manualPriceMin ?: 0.0,
                vp.manualPriceMax ?: 1.0,
                ChartMath.fullExtent(state.bars),
            )
        }
        val visible = ChartMath.visibleRange(vp, plotWidthPx, state.barCount, 2)
        return resolvePriceRange(state, visible)
    }

    fun scalePriceRange(factor: Double) {
        val changed = ChartMath.scaleRange(currentPriceRange(), factor, ChartMath.fullExtent(state.bars))
        setManualPriceRange(changed.min, changed.max)
    }

    fun panPriceRange(delta: Double) {
        if (state.viewport.manualPriceScale) {
            val changed = ChartMath.panRange(currentPriceRange(), delta)
            setManualPriceRange(changed.min, changed.max)
        }
    }

    fun setClock(ms: Long) {
        state = state.copy(clockMs = ms)
    }

    /** Replace the whole series once the dataset is loaded from Room. */
    fun replaceData(m1: List<Candle>, news: List<NewsEvent>) {
        cache = TimeframeCache(m1)
        state = state.copy(m1 = m1, news = news, bars = cache.bars(state.timeframe))
        jumpToLatest()
    }

    fun setOverlays(lines: List<ChartOrderLine>, markers: List<ChartMarker>) {
        state = state.copy(orderLines = lines, markers = markers)
    }

    fun setMarketClosed(closed: Boolean) {
        if (state.marketClosed != closed) state = state.copy(marketClosed = closed)
    }

    /** Bar index of the bar opening at [ts], or -1 when outside the visible series. */
    fun barIndexForTs(ts: Long): Int {
        val bars = state.bars
        if (bars.isEmpty()) return -1
        val i = ChartMath.fractionalIndex(bars.map { it.ts }, ts)
        val idx = kotlin.math.round(i).toInt()
        return if (idx in bars.indices) idx else -1
    }

    /** Bar index under a tap at [x], or -1. */
    fun barIndexAt(x: Float, right: Float): Int =
        GestureMath.barIndexAt(x, state.viewport, state.barCount, right)

    /** Price at a vertical position within the plot area. */
    fun priceAtY(y: Float, top: Float, bottom: Float): Double =
        ChartMath.yToPrice(y, currentPriceRange(), top, bottom)

    fun setDisplayOffset(ms: Long) {
        if (state.displayOffsetMs != ms) state = state.copy(displayOffsetMs = ms)
    }

    companion object {
        const val RIGHT_PADDING_CANDLES = 6f

        fun spanMs(tf: Timeframe): Long = when (tf) {
            Timeframe.M1 -> 60_000L
            Timeframe.M15 -> 900_000L
            Timeframe.H1 -> 3_600_000L
            Timeframe.H4 -> 14_400_000L
            Timeframe.D1 -> 86_400_000L
            Timeframe.W1 -> 604_800_000L
        }
    }
}
