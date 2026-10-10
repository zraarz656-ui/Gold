package com.tradequest.chart

import com.tradequest.engine.Candle
import com.tradequest.engine.Timeframe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression for the live-follow bug: with no user gesture the chart must keep the newest
 * candle in view as minutes tick, and report no "N new" backlog.
 *
 * The chart is built the way the app builds it — an EMPTY controller that is laid out
 * first and has its data injected later via [ChartController.replaceData]. A resize (or the
 * data arriving after the first layout) moves where the live edge sits, so the previously
 * applied scroll becomes stale; the follow decision must not be derived from that stale
 * position.
 */
class LiveFollowTest {

    private fun series(count: Int): List<Candle> {
        var price = 2400.0
        return (0 until count).map { i ->
            val o = price
            val c = o + if (i % 3 == 0) 0.5 else -0.3
            price = c
            Candle(1_700_000_000_000L + i * 60_000L, o, maxOf(o, c) + 0.3, minOf(o, c) - 0.3, c, 100.0)
        }
    }

    private fun next(c: ChartController): Candle {
        val last = c.state.m1.last()
        return Candle(last.ts + 60_000L, last.c, last.c + 1.0, last.c - 1.0, last.c + 0.5, 1.0)
    }

    /** The newest candle's right edge, relative to the plot's right border. */
    private fun newestX(c: ChartController): Float =
        ChartMath.indexToX((c.state.barCount - 1).toFloat(), c.state.viewport)

    @Test
    fun `no gesture keeps following across four ticks after data arrives post-layout`() {
        // The app's boot races the async Room read against the first layout. When the data
        // lands first (the controller still at its default plot width), replaceData anchors
        // the scroll for that width; the layout then changes the bar width, leaving the
        // scroll stale. Following must not be derived from that stale position.
        val c = ChartController(emptyList(), emptyList(), Timeframe.M1)
        c.replaceData(series(20_000), emptyList())
        c.onLayout(2.75f, 1000f)

        repeat(4) { step ->
            val n = next(c)
            c.appendM1(n, n.ts)
            assertTrue(c.state.liveEdgeFollowing, "still following after tick ${step + 1}")
            assertEquals(0, c.pendingNewCandles, "no backlog after tick ${step + 1}")
            val x = newestX(c)
            assertTrue(x <= c.plotWidthPx + 1e-3f, "newest candle on screen after tick ${step + 1}: x=$x")
            assertEquals(
                ChartMath.liveEdgeScroll(c.plotWidthPx, c.state.viewport.candleWidthPx, c.state.barCount),
                c.state.viewport.scrollIndex, 1e-3f,
                "scroll re-anchored to the live edge after tick ${step + 1}",
            )
        }
    }

    @Test
    fun `a deliberate scroll away stops following and counts new candles`() {
        val c = ChartController(series(500), emptyList(), Timeframe.M1)
        c.onLayout(1f, 1000f)
        // Finger drag right scrolls the window left, past the live edge into empty space.
        repeat(50) { c.pan(1000f) }
        assertFalse(c.state.liveEdgeFollowing, "scrolling past the live edge stops following")

        val before = c.state.viewport.scrollIndex
        repeat(4) { c.appendM1(next(c), 0L) }
        assertFalse(c.state.liveEdgeFollowing)
        assertEquals(4, c.pendingNewCandles)
        // The view stayed anchored (scroll advanced with the new bars) instead of jumping.
        assertEquals(before + 4f, c.state.viewport.scrollIndex, 1e-3f)
    }

    @Test
    fun `jump to latest resumes following and clears the backlog`() {
        val c = ChartController(series(500), emptyList(), Timeframe.M1)
        c.onLayout(1f, 1000f)
        repeat(50) { c.pan(1000f) }
        repeat(4) { c.appendM1(next(c), 0L) }
        assertEquals(4, c.pendingNewCandles)

        c.jumpToLatest()
        assertTrue(c.state.liveEdgeFollowing)
        assertEquals(0, c.pendingNewCandles)
    }
}
