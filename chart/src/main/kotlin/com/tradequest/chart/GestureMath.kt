package com.tradequest.chart

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Pure gesture maths for pan/zoom/fling, separated from the Compose gesture layer. */
object GestureMath {
    const val FLING_DECAY = 0.92f
    const val FLING_MIN_VELOCITY = 120.0f

    /** Pixels-per-second velocity from recent `(timeMs, xPx)` samples. */
    fun velocity(samples: List<Pair<Long, Float>>): Float {
        if (samples.size < 2) return 0f
        val last = samples.last()
        val oldest = samples.firstOrNull { last.first - it.first <= 80 } ?: samples.first()
        val dt = maxOf(last.first - oldest.first, 1L)
        return (last.second - oldest.second) / dt.toFloat() * 1000f
    }

    fun shouldFling(velocity: Float): Boolean = abs(velocity) >= FLING_MIN_VELOCITY

    fun decay(velocity: Float): Float = FLING_DECAY * velocity

    fun barIndexAt(xPx: Float, viewport: Viewport, barCount: Int, plotRight: Float): Int {
        if (barCount <= 0 || xPx < 0f || xPx > plotRight) return -1
        val idx = ChartMath.xToIndex(xPx, viewport).roundToInt()
        return idx.coerceIn(0, barCount - 1)
    }

    fun applyPan(
        viewport: Viewport,
        dxPx: Float,
        plotWidthPx: Float,
        barCount: Int,
        rightPaddingCandles: Float = 6f,
    ): Viewport {
        val idxDelta = -dxPx / viewport.candleWidthPx
        val raw = viewport.scrollIndex + idxDelta
        val clamped = ChartMath.clampScroll(raw, plotWidthPx, viewport.candleWidthPx, barCount, rightPaddingCandles)
        return viewport.copy(scrollIndex = clamped)
    }

    fun applyZoom(
        viewport: Viewport,
        pivotX: Float,
        zoom: Float,
        densityPx: Float,
        plotWidthPx: Float,
        barCount: Int,
        rightPaddingCandles: Float = 6f,
    ): Viewport {
        if (zoom == 0f) return viewport
        val minPx = densityPx * ChartMath.MIN_CANDLE_WIDTH_DP
        val maxPx = densityPx * ChartMath.MAX_CANDLE_WIDTH_DP
        val newWidth = (viewport.candleWidthPx * zoom).coerceIn(minPx, maxPx)
        if (newWidth == viewport.candleWidthPx) return viewport
        val scroll = ChartMath.zoomAnchor(viewport.scrollIndex, pivotX, viewport.candleWidthPx, newWidth)
        val clamped = ChartMath.clampScroll(scroll, plotWidthPx, newWidth, barCount, rightPaddingCandles)
        return viewport.copy(scrollIndex = clamped, candleWidthPx = newWidth)
    }

    /** Keep the same bars in view when [newCandles] are appended at the live edge. */
    fun anchorScrollOnAppend(scrollIndex: Float, newCandles: Int): Float = newCandles + scrollIndex

    fun priceScaleFactor(totalDyPx: Float, viewportHeightPx: Float): Double {
        val h = maxOf(viewportHeightPx, 1f)
        return exp((totalDyPx / h).toDouble())
    }
}
