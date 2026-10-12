package com.tradequest.chart

import com.tradequest.engine.Candle
import com.tradequest.engine.Timeframe
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A price interval, always `min <= max`. */
data class PriceRange(val min: Double, val max: Double) {
    val span: Double get() = max - min
}

/** Which palette the chart draws with. */
enum class ThemeId { DARK, LIGHT, OLED, COLORBLIND }

/** A labelled tick on the time axis. */
data class TimeTick(val index: Int, val displayTs: Long, val isDayBoundary: Boolean)

/** Where the crosshair currently sits. */
data class CrosshairInfo(val candle: Candle, val price: Double, val x: Float, val y: Float)

/**
 * A viewport onto the bar array: [scrollIndex] is the fractional index of the leftmost
 * visible bar, [candleWidthPx] the on-screen width of one bar. When [manualPriceScale]
 * is set the y-axis is pinned to [manualPriceMin]..[manualPriceMax] instead of
 * auto-fitting.
 */
data class Viewport(
    val scrollIndex: Float,
    val candleWidthPx: Float,
    val manualPriceMin: Double? = null,
    val manualPriceMax: Double? = null,
    val manualPriceScale: Boolean = false,
) {
    val autoScale: Boolean get() = !manualPriceScale
}

/** The plot rectangle for one canvas: everything except the right price gutter and the
 *  bottom time axis. This is the single source of truth for the drawable area. */
data class PlotRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Resolved pixel geometry for one draw pass. */
data class ChartGeometry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val priceRange: PriceRange,
    val visible: IntRange,
    /** Full canvas width in px; the far-right gutter tags anchor to it. */
    val screenWidth: Float = 0f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The plot rectangle, derived from the corners so there is one definition of it. */
    val plot: PlotRect get() = PlotRect(left, top, right, bottom)
}

/**
 * Pure coordinate maths for the candle chart. No Android or Compose types appear here so
 * the whole thing is unit-testable on the JVM.
 */
object ChartMath {
    const val MIN_CANDLE_WIDTH_DP = 2.0f
    const val MAX_CANDLE_WIDTH_DP = 40.0f
    const val MIN_AUTO_RANGE = 2.0
    const val MIN_MANUAL_RANGE = 0.5
    const val AUTO_FIT_PADDING = 0.05

    fun indexToX(index: Float, viewport: Viewport): Float =
        (index - viewport.scrollIndex) * viewport.candleWidthPx

    fun xToIndex(x: Float, viewport: Viewport): Float =
        (x / viewport.candleWidthPx) + viewport.scrollIndex

    /**
     * The plot rectangle inside a canvas, excluding the right price gutter ([axisWidthPx])
     * and the bottom time axis ([bottomAxisPx]). Every draw path and hit test derives its
     * geometry from this so no layer can disagree about where the plot is.
     */
    fun plotRect(canvasWidth: Float, canvasHeight: Float, axisWidthPx: Float, bottomAxisPx: Float): PlotRect =
        PlotRect(
            left = 0f,
            top = 0f,
            right = maxOf(canvasWidth - axisWidthPx, 1f),
            bottom = maxOf(canvasHeight - bottomAxisPx, 1f),
        )

    fun priceToY(price: Double, range: PriceRange, plot: PlotRect): Float =
        priceToY(price, range, plot.top, plot.bottom)

    fun priceToY(price: Double, range: PriceRange, top: Float, bottom: Float): Float {
        if (range.span <= 0.0) return (top + bottom) / 2f
        val t = (range.max - price) / range.span
        return (top + (bottom - top) * t).toFloat()
    }

    fun yToPrice(y: Float, range: PriceRange, top: Float, bottom: Float): Double {
        if (abs(bottom - top) < 1e-6f) return range.max
        val t = (y - top) / (bottom - top)
        return range.max - t * range.span
    }

    fun yToPrice(y: Float, range: PriceRange, plot: PlotRect): Double =
        yToPrice(y, range, plot.top, plot.bottom)

    fun visibleRange(viewport: Viewport, plotWidth: Float, candleCount: Int, buffer: Int = 1): IntRange {
        if (candleCount <= 0) return IntRange.EMPTY
        val first = floor(viewport.scrollIndex.toDouble()).toInt() - buffer
        val last = ceil(viewport.scrollIndex + plotWidth / viewport.candleWidthPx).toInt() + buffer
        return maxOf(0, first)..minOf(candleCount - 1, last)
    }

    /** Minimum empty space kept to the right of the newest candle, in candles. */
    const val DEFAULT_RIGHT_PADDING_CANDLES = 10f

    /** Default right padding as a share of the plot width, in candles. */
    const val DEFAULT_RIGHT_PADDING_FRACTION = 0.12f

    /** The furthest right the view may scroll: 60% of the plot width of empty space. */
    const val MAX_RIGHT_PADDING_FRACTION = 0.60f

    /**
     * The live-edge right padding in candles: at least [DEFAULT_RIGHT_PADDING_CANDLES], or
     * [DEFAULT_RIGHT_PADDING_FRACTION] of the visible width when that is larger.
     */
    fun defaultRightPadding(visibleCandles: Float): Float =
        maxOf(DEFAULT_RIGHT_PADDING_CANDLES, DEFAULT_RIGHT_PADDING_FRACTION * visibleCandles)

    /**
     * The maximum right padding in candles: at least the default padding, so the live edge
     * is always reachable, and [MAX_RIGHT_PADDING_FRACTION] of the width otherwise. The two
     * only conflict when fewer than about 17 candles fit, where the default is the floor.
     */
    fun maxRightPadding(visibleCandles: Float): Float =
        maxOf(MAX_RIGHT_PADDING_FRACTION * visibleCandles, defaultRightPadding(visibleCandles))

    /** Candles that fit across the plot at the current bar width. */
    fun visibleCandles(plotWidth: Float, candleWidthPx: Float): Float =
        if (candleWidthPx <= 0f) 1f else plotWidth / candleWidthPx

    /** The scroll index that parks the newest candle at the default live-edge padding. */
    fun liveEdgeScroll(plotWidth: Float, candleWidthPx: Float, candleCount: Int): Float {
        if (candleCount <= 0) return 0f
        val visible = visibleCandles(plotWidth, candleWidthPx)
        return (candleCount - 1) - visible + defaultRightPadding(visible)
    }

    /**
     * Clamp the horizontal scroll to the reachable window.
     *
     * The right end leaves up to [maxRightPadding] candles of empty space past the newest
     * candle (so the user can scroll past the live edge). The left end is the oldest data
     * at the plot's left edge; with fewer candles than fit, the whole run is parked on the
     * right instead, so a handful of candles is never clipped at the left border.
     */
    fun clampScroll(
        scrollIndex: Float,
        plotWidth: Float,
        candleWidthPx: Float,
        candleCount: Int,
        defaultRightPaddingCandles: Float,
        maxRightPaddingCandles: Float,
    ): Float {
        if (candleCount <= 0) return scrollIndex
        val visible = visibleCandles(plotWidth, candleWidthPx)
        val base = (candleCount - 1) - visible
        val sDefault = base + defaultRightPaddingCandles
        val sMax = base + maxRightPaddingCandles
        val sMin = minOf(0f, sDefault)
        return scrollIndex.coerceIn(sMin, maxOf(sMax, sMin))
    }

    fun zoomAnchor(scrollIndex: Float, pivotX: Float, oldWidth: Float, newWidth: Float): Float {
        val pivotIndex = xToIndex(pivotX, Viewport(scrollIndex, oldWidth))
        return pivotIndex - pivotX / newWidth
    }

    fun niceStep(rawStep: Double): Double {
        if (!rawStep.isFinite() || rawStep <= 0.0) return 1.0
        val exp = floor(log10(rawStep))
        val pow = 10.0.pow(exp)
        val frac = rawStep / pow
        val nice = when {
            frac > 5.0 -> 10.0
            frac > 2.0 -> 5.0
            frac > 1.0 -> 2.0
            else -> 1.0
        }
        return nice * pow
    }

    fun niceTicks(min: Double, max: Double, targetTicks: Int): List<Double> {
        if (!min.isFinite() || !max.isFinite() || max <= min) return emptyList()
        val step = niceStep((max - min) / maxOf(1, targetTicks))
        val start = ceil(min / step) * step
        val out = ArrayList<Double>()
        var v = start
        while (v <= max + 1e-6 * step && out.size < 1000) {
            out.add(v)
            v += step
        }
        return out
    }

    /** Index of the last entry in [times] that is `<= ts`, or -1. */
    fun indexAtOrBefore(times: List<Long>, ts: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= ts) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun fractionalIndex(times: List<Long>, centerTimeMs: Long): Float {
        if (times.isEmpty()) return 0f
        val i = indexAtOrBefore(times, centerTimeMs)
        if (i < 0) return 0f
        if (i >= times.size - 1) return (times.size - 1).toFloat()
        val t0 = times[i]
        val t1 = times[i + 1]
        return if (t1 == t0) i.toFloat() else i + (centerTimeMs - t0).toFloat() / (t1 - t0).toFloat()
    }

    fun autoFitRange(low: Double, high: Double): PriceRange {
        var lo = low
        var hi = high
        if (hi <= lo) {
            val mid = (lo + hi) / 2.0
            lo = mid - 0.5
            hi = mid + 0.5
        }
        val pad = (hi - lo) * AUTO_FIT_PADDING
        lo -= pad
        hi += pad
        if (hi - lo < MIN_AUTO_RANGE) {
            val mid = (lo + hi) / 2.0
            lo = mid - MIN_AUTO_RANGE / 2.0
            hi = mid + MIN_AUTO_RANGE / 2.0
        }
        return PriceRange(lo, hi)
    }

    fun clampManualRange(min: Double, max: Double, maxSpan: Double = 0.0): PriceRange {
        var lo = minOf(min, max)
        var hi = maxOf(min, max)
        if (hi - lo < MIN_MANUAL_RANGE) {
            val mid = (lo + hi) / 2.0
            lo = mid - MIN_MANUAL_RANGE / 2.0
            hi = mid + MIN_MANUAL_RANGE / 2.0
        }
        if (maxSpan > 0.0 && hi - lo > maxSpan) {
            val mid = (lo + hi) / 2.0
            lo = mid - maxSpan / 2.0
            hi = mid + maxSpan / 2.0
        }
        return PriceRange(lo, hi)
    }

    fun scaleRange(range: PriceRange, factor: Double, maxSpan: Double = 0.0): PriceRange {
        if (!factor.isFinite() || factor <= 0.0) return range
        val newSpan = range.span * factor
        val mid = (range.min + range.max) / 2.0
        return clampManualRange(mid - newSpan / 2.0, mid + newSpan / 2.0, maxSpan)
    }

    fun panRange(range: PriceRange, delta: Double): PriceRange =
        PriceRange(range.min + delta, range.max + delta)

    fun dataExtent(bars: List<Candle>, visible: IntRange): PriceRange? {
        if (visible.isEmpty() || bars.isEmpty()) return null
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in visible.first..visible.last) {
            if (i < 0 || i >= bars.size) continue
            val c = bars[i]
            if (c.l < lo) lo = c.l
            if (c.h > hi) hi = c.h
        }
        return if (lo > hi) null else PriceRange(lo, hi)
    }

    /** Full-span of the data, floored at [MIN_MANUAL_RANGE], used to bound manual zoom. */
    fun fullExtent(bars: List<Candle>): Double {
        val e = dataExtent(bars, bars.indices) ?: return MIN_AUTO_RANGE
        return maxOf(autoFitRange(e.min, e.max).span, MIN_MANUAL_RANGE)
    }

    fun collidesWithAny(labelY: Float, occupiedY: List<Float>, minGapPx: Float): Boolean =
        occupiedY.any { abs(it - labelY) < minGapPx }

    fun timeAxisTicks(
        displayTs: List<Long>,
        visible: IntRange,
        timeframe: Timeframe,
        scrollIndex: Float,
        candleWidthPx: Float,
        minSpacingPx: Float,
    ): List<TimeTick> {
        if (visible.isEmpty() || displayTs.isEmpty()) return emptyList()
        val intraday = timeframe == Timeframe.M1 || timeframe == Timeframe.M15 ||
            timeframe == Timeframe.H1 || timeframe == Timeframe.H4
        val out = ArrayList<TimeTick>()
        var lastX = Float.NEGATIVE_INFINITY
        for (i in visible.first..visible.last) {
            if (i < 0 || i >= displayTs.size) continue
            val ts = displayTs[i]
            val z = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC)
            val isDayBoundary: Boolean
            val eligible: Boolean
            if (intraday) {
                isDayBoundary = z.hour == 0 && z.minute == 0
                eligible = isDayBoundary || z.minute % 15 == 0
            } else {
                isDayBoundary = true
                eligible = true
            }
            if (!eligible) continue
            val x = (i - scrollIndex) * candleWidthPx
            val gap = x - lastX
            if (out.isEmpty() || gap >= minSpacingPx) {
                out.add(TimeTick(i, ts, isDayBoundary))
                lastX = x
            } else if (isDayBoundary) {
                out[out.size - 1] = TimeTick(i, ts, true)
                lastX = x
            }
        }
        return out
    }
}
