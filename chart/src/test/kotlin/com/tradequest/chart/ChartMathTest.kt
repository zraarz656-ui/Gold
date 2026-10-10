package com.tradequest.chart

import com.tradequest.engine.Candle
import com.tradequest.engine.Timeframe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChartMathTest {

    private val vp = Viewport(scrollIndex = 0f, candleWidthPx = 10f)

    @Test
    fun `index and x round trip`() {
        assertEquals(50f, ChartMath.indexToX(5f, vp), 1e-3f)
        assertEquals(5f, ChartMath.xToIndex(50f, vp), 1e-3f)
    }

    @Test
    fun `price and y round trip`() {
        val range = PriceRange(2000.0, 2100.0)
        val y = ChartMath.priceToY(2050.0, range, 0f, 100f)
        assertEquals(50f, y, 1e-3f)
        assertEquals(2050.0, ChartMath.yToPrice(y, range, 0f, 100f), 1e-6)
    }

    @Test
    fun `visible range is clipped to the bar count`() {
        val wide = Viewport(0f, 10f)
        assertEquals(0..100, ChartMath.visibleRange(wide, 1000f, 101, buffer = 0))
        val shifted = Viewport(95f, 10f)
        assertEquals(95..100, ChartMath.visibleRange(shifted, 100f, 101, buffer = 0))
    }

    @Test
    fun `clamp scroll allows scrolling past the newest candle up to the max padding`() {
        // visible = 10 bars; base = 49 - 10 = 39; max pad = 60% of 10 = 6, floored to the
        // default 10, so the reachable window is 29..49.
        val clamped = ChartMath.clampScroll(
            1000f, plotWidth = 100f, candleWidthPx = 10f, candleCount = 50,
            defaultRightPaddingCandles = 10f, maxRightPaddingCandles = 10f,
        )
        assertEquals(49f, clamped, 1e-3f)
    }

    @Test
    fun `clamp scroll stops at the oldest candle for a long series`() {
        // visible = 100 bars over 1000 candles: base = 999 - 100 = 899, default pad = 120.
        val clamped = ChartMath.clampScroll(
            -500f, plotWidth = 1000f, candleWidthPx = 10f, candleCount = 1000,
            defaultRightPaddingCandles = 120f, maxRightPaddingCandles = 600f,
        )
        assertEquals(0f, clamped, 1e-3f)
    }

    @Test
    fun `a handful of candles is parked on the right, never clipped at the left`() {
        for (count in intArrayOf(1, 2, 5)) {
            val visible = ChartMath.visibleCandles(1000f, 10f) // 100
            val default = ChartMath.defaultRightPadding(visible)
            val liveEdge = ChartMath.liveEdgeScroll(1000f, 10f, count)
            // The default live-edge position is preserved by the clamp (not pushed off it).
            val clamped = ChartMath.clampScroll(
                liveEdge, plotWidth = 1000f, candleWidthPx = 10f, candleCount = count,
                defaultRightPaddingCandles = default, maxRightPaddingCandles = ChartMath.maxRightPadding(visible),
            )
            assertEquals(liveEdge, clamped, 1e-3f)
            val lastX = ChartMath.indexToX((count - 1).toFloat(), Viewport(clamped, 10f))
            assertEquals(1000f - default * 10f, lastX, 1e-3f)
            val firstX = ChartMath.indexToX(0f, Viewport(clamped, 10f))
            assertTrue(firstX > 0f, "count=$count firstX=$firstX should be to the right")
        }
    }

    @Test
    fun `the live edge keeps the default padding, the far end the max padding`() {
        val visible = ChartMath.visibleCandles(1000f, 10f) // 100
        assertEquals(10f, ChartMath.defaultRightPadding(50f), 1e-3f) // floor
        assertEquals(12f, ChartMath.defaultRightPadding(visible), 1e-3f) // 12% of 100
        assertEquals(60f, ChartMath.maxRightPadding(visible), 1e-3f) // 60% of 100
    }

    @Test
    fun `auto fit pads and enforces a minimum span`() {
        val r = ChartMath.autoFitRange(2000.0, 2100.0)
        assertTrue(r.span >= ChartMath.MIN_AUTO_RANGE)
        assertTrue(r.min < 2000.0 && r.max > 2100.0)
        val flat = ChartMath.autoFitRange(2050.0, 2050.0)
        assertTrue(flat.span >= ChartMath.MIN_AUTO_RANGE)
    }

    @Test
    fun `data extent ignores bars outside the window`() {
        val bars = listOf(
            candle(0, h = 2005.0, l = 1995.0),
            candle(1, h = 2010.0, l = 1990.0),
            candle(2, h = 2030.0, l = 2020.0),
        )
        val e = ChartMath.dataExtent(bars, 0..1)!!
        assertEquals(1990.0, e.min, 1e-9)
        assertEquals(2010.0, e.max, 1e-9)
        assertNull(ChartMath.dataExtent(bars, IntRange.EMPTY))
    }

    @Test
    fun `indexAtOrBefore finds the last bar at or before a timestamp`() {
        val times = listOf(100L, 200L, 300L)
        assertEquals(1, ChartMath.indexAtOrBefore(times, 250L))
        assertEquals(-1, ChartMath.indexAtOrBefore(times, 50L))
        assertEquals(2, ChartMath.indexAtOrBefore(times, 300L))
    }

    @Test
    fun `fractional index interpolates between bars`() {
        val times = listOf(0L, 1000L)
        assertEquals(0.5f, ChartMath.fractionalIndex(times, 500L), 1e-3f)
    }

    @Test
    fun `nice ticks land on round numbers`() {
        val ticks = ChartMath.niceTicks(2000.0, 2100.0, 6)
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it >= 2000.0 && it <= 2100.0 + 1e-6 })
    }

    @Test
    fun `time axis ticks are spaced at least the minimum gap`() {
        val m1 = (0 until 240).map { it * 60_000L }
        val ticks = ChartMath.timeAxisTicks(
            displayTs = m1,
            visible = 0..239,
            timeframe = Timeframe.M1,
            scrollIndex = 0f,
            candleWidthPx = 10f,
            minSpacingPx = 64f,
        )
        assertTrue(ticks.isNotEmpty())
        var lastX = Float.NEGATIVE_INFINITY
        for (t in ticks) {
            val x = (t.index - 0f) * 10f
            assertTrue(x - lastX >= 64f - 1e-3f)
            lastX = x
        }
    }

    @Test
    fun `plot rect excludes the gutter and the time axis`() {
        val plot = ChartMath.plotRect(canvasWidth = 1000f, canvasHeight = 600f, axisWidthPx = 60f, bottomAxisPx = 20f)
        assertEquals(0f, plot.left)
        assertEquals(0f, plot.top)
        assertEquals(940f, plot.right)
        assertEquals(580f, plot.bottom)
        assertEquals(940f, plot.width)
        assertEquals(580f, plot.height)
    }

    @Test
    fun `plot rect never collapses below one pixel`() {
        val plot = ChartMath.plotRect(canvasWidth = 10f, canvasHeight = 5f, axisWidthPx = 60f, bottomAxisPx = 20f)
        assertEquals(1f, plot.right)
        assertEquals(1f, plot.bottom)
    }

    @Test
    fun `price and y stay consistent through the plot rect overload`() {
        val plot = ChartMath.plotRect(800f, 500f, 60f, 20f)
        val range = PriceRange(2000.0, 2100.0)
        val y = ChartMath.priceToY(2050.0, range, plot)
        assertEquals(240f, y, 1e-3f)
        assertEquals(2050.0, ChartMath.yToPrice(y, range, plot), 1e-6)
    }

    private fun candle(ts: Long, h: Double, l: Double) =
        Candle(ts, o = (h + l) / 2, h = h, l = l, c = (h + l) / 2, v = 1.0)
}
