package com.tradequest.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GestureMathTest {

    @Test
    fun `velocity needs at least two samples`() {
        assertEquals(0f, GestureMath.velocity(listOf(0L to 0f)), 1e-6f)
    }

    @Test
    fun `velocity is pixels per second`() {
        val v = GestureMath.velocity(listOf(0L to 0f, 50L to 50f))
        assertEquals(1000f, v, 1e-3f)
    }

    @Test
    fun `fling threshold`() {
        assertFalse(GestureMath.shouldFling(100f))
        assertTrue(GestureMath.shouldFling(120f))
    }

    @Test
    fun `pan moves scroll index opposite to drag`() {
        val vp = Viewport(scrollIndex = 10f, candleWidthPx = 10f)
        val panned = GestureMath.applyPan(vp, dxPx = 50f, plotWidthPx = 1000f, barCount = 500)
        // dragging right by 50px moves the window back 5 bars
        assertEquals(5f, panned.scrollIndex, 1e-3f)
    }

    @Test
    fun `zoom keeps the pivot bar under the finger`() {
        val vp = Viewport(scrollIndex = 0f, candleWidthPx = 10f)
        val pivotX = 200f
        val before = ChartMath.xToIndex(pivotX, vp)
        val zoomed = GestureMath.applyZoom(
            vp, pivotX = pivotX, zoom = 2f, densityPx = 1f, plotWidthPx = 1000f, barCount = 500,
        )
        val after = ChartMath.xToIndex(pivotX, zoomed)
        assertEquals(before, after, 1e-3f)
    }

    @Test
    fun `zoom clamps candle width`() {
        val vp = Viewport(scrollIndex = 0f, candleWidthPx = 10f)
        val tooBig = GestureMath.applyZoom(vp, 0f, 1000f, 1f, 1000f, 500)
        assertTrue(tooBig.candleWidthPx <= ChartMath.MAX_CANDLE_WIDTH_DP)
        val tooSmall = GestureMath.applyZoom(vp, 0f, 0.0001f, 1f, 1000f, 500)
        assertTrue(tooSmall.candleWidthPx >= ChartMath.MIN_CANDLE_WIDTH_DP)
    }

    @Test
    fun `bar index is bounded and rejects outside taps`() {
        val vp = Viewport(0f, 10f)
        assertEquals(5, GestureMath.barIndexAt(50f, vp, barCount = 20, plotRight = 1000f))
        assertEquals(-1, GestureMath.barIndexAt(-1f, vp, barCount = 20, plotRight = 1000f))
        assertEquals(-1, GestureMath.barIndexAt(1001f, vp, barCount = 20, plotRight = 1000f))
    }
}
