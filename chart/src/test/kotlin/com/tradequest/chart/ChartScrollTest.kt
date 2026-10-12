package com.tradequest.chart

import com.tradequest.engine.Candle
import com.tradequest.engine.Timeframe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Phase 3 right-side scroll behaviour: the view may travel up to 60% of the plot width
 * past the newest candle, returns to the default live-edge padding on Jump/Fit, and parks a
 * handful of candles on the right (never clipped at the left border), on every timeframe.
 */
class ChartScrollTest {

    private fun series(count: Int): List<Candle> {
        var price = 2400.0
        return (0 until count).map { i ->
            val o = price
            val c = o + if (i % 3 == 0) 0.5 else -0.3
            price = c
            Candle(1_700_000_000_000L + i * 60_000L, o, maxOf(o, c) + 0.3, minOf(o, c) - 0.3, c, 100.0)
        }
    }

    // 40dp bars at 1x density => 25 candles across a 1000px plot.
    private fun controller(tf: Timeframe, count: Int): ChartController {
        val c = ChartController(series(count), emptyList(), tf)
        c.onLayout(1f, 1000f)
        // The controller starts at 6dp bars; widen so the counts below are predictable.
        repeat(20) { c.zoomByFactor(2f) }
        // Park at the live edge so the default padding is the reference.
        c.jumpToLatest()
        return c
    }

    @Test
    fun `few candles sit on the right with empty space on the left, on every timeframe`() {
        for (tf in listOf(Timeframe.W1, Timeframe.D1, Timeframe.M1)) {
            for (count in intArrayOf(1, 2, 5)) {
                val c = controller(tf, count)
                val vp = c.state.viewport
                val bars = c.state.barCount
                val visible = ChartMath.visibleCandles(1000f, vp.candleWidthPx)
                val lastX = ChartMath.indexToX((bars - 1).toFloat(), vp)
                // The newest candle is at the default padding from the right edge.
                assertEquals(
                    1000f - ChartMath.defaultRightPadding(visible) * vp.candleWidthPx,
                    lastX, 1.0f,
                )
                // Every candle is inside the plot, i.e. not clipped at the left border.
                val firstX = ChartMath.indexToX(0f, vp)
                assertTrue(firstX > 0f, "$tf count=$count bars=$bars firstX=$firstX")
                assertTrue(lastX <= 1000f + 1e-3f, "$tf count=$count lastX=$lastX")
            }
        }
    }

    @Test
    fun `the view can scroll past the newest candle up to the max padding`() {
        val c = controller(Timeframe.M1, 500)
        val bars = c.state.barCount
        // Drag the chart to the right (finger right => the window moves left, towards newest).
        repeat(50) { c.pan(1000f) }
        val vp = c.state.viewport
        val visible = ChartMath.visibleCandles(1000f, vp.candleWidthPx)
        val maxScroll = ((bars - 1) - visible) + ChartMath.maxRightPadding(visible)
        assertEquals(maxScroll, vp.scrollIndex, 1.0f)
        // The newest candle now sits further left than at the live edge, i.e. scrolled past it.
        val lastX = ChartMath.indexToX((bars - 1).toFloat(), vp)
        val liveX = 1000f - ChartMath.defaultRightPadding(visible) * vp.candleWidthPx
        assertTrue(lastX < liveX - 1f, "lastX=$lastX liveX=$liveX")
    }

    @Test
    fun `jump to latest and fit return to the default padding`() {
        val c = controller(Timeframe.M1, 500)
        val bars = c.state.barCount
        c.pan(1000f)
        assertTrue(!c.state.liveEdgeFollowing)
        c.jumpToLatest()
        val vp = c.state.viewport
        val visible = ChartMath.visibleCandles(1000f, vp.candleWidthPx)
        assertEquals(ChartMath.liveEdgeScroll(1000f, vp.candleWidthPx, bars), vp.scrollIndex, 1e-3f)
        val lastX = ChartMath.indexToX((bars - 1).toFloat(), vp)
        assertEquals(1000f - ChartMath.defaultRightPadding(visible) * vp.candleWidthPx, lastX, 1.0f)
        assertTrue(c.state.liveEdgeFollowing)
    }

    @Test
    fun `a new candle at the live edge keeps the default padding`() {
        val c = controller(Timeframe.M1, 200)
        val before = c.state.viewport.scrollIndex
        val visible = ChartMath.visibleCandles(1000f, c.state.viewport.candleWidthPx)
        val last = c.state.m1.last()
        c.appendM1(
            Candle(last.ts + 60_000L, last.c, last.c + 1.0, last.c - 1.0, last.c + 0.5, 1.0),
            last.ts + 60_000L,
        )
        // Following the live edge advances the scroll by exactly one bar, so the padding holds.
        assertEquals(before + 1f, c.state.viewport.scrollIndex, 1e-3f)
        assertEquals(ChartMath.liveEdgeScroll(1000f, c.state.viewport.candleWidthPx, 201), c.state.viewport.scrollIndex, 1e-3f)
        assertTrue(visible > 0f)
    }

    @Test
    fun `scrolling back to the oldest candle stops at the left edge`() {
        val c = controller(Timeframe.M1, 500)
        repeat(50) { c.pan(-2000f) }
        assertEquals(0f, c.state.viewport.scrollIndex, 1e-3f)
    }

    @Test
    fun `the max padding is never below the default`() {
        for (visible in floatArrayOf(1f, 5f, 10f, 25f, 100f)) {
            assertTrue(
                ChartMath.maxRightPadding(visible) >= ChartMath.defaultRightPadding(visible),
                "visible=$visible",
            )
        }
    }
}
