package com.tradequest.chart

import com.tradequest.engine.Candle
import com.tradequest.engine.Timeframe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Controller-level behaviour for the restored interactions (zoom buttons, price drag). */
class ChartControllerTest {

    private fun series(count: Int, startTs: Long = 1_700_000_000_000L): List<Candle> {
        var price = 2400.0
        return (0 until count).map { i ->
            val o = price
            val c = o + if (i % 3 == 0) 0.5 else -0.3
            price = c
            Candle(startTs + i * 60_000L, o, maxOf(o, c) + 0.3, minOf(o, c) - 0.3, c, 100.0)
        }
    }

    private fun controller(count: Int = 500): ChartController {
        val c = ChartController(series(count), emptyList(), Timeframe.M1)
        c.onLayout(1f, 1000f)
        return c
    }

    @Test
    fun `zoom in narrows the visible window`() {
        val c = controller()
        val before = c.state.viewport.candleWidthPx
        c.zoomByFactor(2f)
        assertTrue(c.state.viewport.candleWidthPx > before)
    }

    @Test
    fun `zoom out widens the visible window and is clamped`() {
        val c = controller()
        repeat(40) { c.zoomByFactor(0.5f) }
        assertEquals(ChartMath.MIN_CANDLE_WIDTH_DP, c.state.viewport.candleWidthPx, 0.001f)
    }

    @Test
    fun `dragging the price gutter does nothing until the range is pinned`() {
        val c = controller()
        assertFalse(c.state.viewport.manualPriceScale)
        val before = c.currentPriceRange()
        c.panPriceRange(5.0)
        assertFalse(c.state.viewport.manualPriceScale)
        assertEquals(before.min, c.currentPriceRange().min, 0.001)
    }

    @Test
    fun `dragging a pinned price range shifts it`() {
        val c = controller()
        val base = c.currentPriceRange()
        c.setManualPriceRange(base.min, base.max)
        val before = c.currentPriceRange()
        c.panPriceRange(5.0)
        assertTrue(c.state.viewport.manualPriceScale)
        assertEquals(before.min + 5.0, c.currentPriceRange().min, 0.001)
    }

    @Test
    fun `auto fit restores an auto-scaled range`() {
        val c = controller()
        val base = c.currentPriceRange()
        c.setManualPriceRange(base.min, base.max)
        assertTrue(c.state.viewport.manualPriceScale)
        c.autoFitPrice()
        assertFalse(c.state.viewport.manualPriceScale)
    }

    @Test
    fun `scale price range changes the span`() {
        val c = controller()
        val base = c.currentPriceRange()
        c.setManualPriceRange(base.min, base.max)
        val before = c.currentPriceRange()
        c.scalePriceRange(0.5)
        val after = c.currentPriceRange()
        assertTrue(after.max - after.min < (before.max - before.min))
    }

    @Test
    fun `panning the time axis stops following the live edge`() {
        val c = controller()
        assertTrue(c.state.liveEdgeFollowing)
        c.pan(-500f)
        assertFalse(c.state.liveEdgeFollowing)
    }

    @Test
    fun `jump to latest returns to the live edge`() {
        val c = controller()
        c.pan(800f)
        c.jumpToLatest()
        assertTrue(c.state.liveEdgeFollowing)
        assertNotNull(c.state.bars.lastOrNull())
    }
}
