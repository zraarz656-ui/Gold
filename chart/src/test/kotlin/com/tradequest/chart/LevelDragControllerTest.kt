package com.tradequest.chart

import com.tradequest.engine.Candle
import com.tradequest.engine.Side
import com.tradequest.engine.Timeframe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The level-drag session on the controller: preview lifecycle and outcome resolution. */
class LevelDragControllerTest {

    private fun controller(): ChartController {
        val candles = (0 until 100).map { i ->
            Candle(1_700_000_000_000L + i * 60_000L, 2400.0, 2401.0, 2399.0, 2400.5, 50.0)
        }
        val c = ChartController(candles, emptyList(), Timeframe.M1)
        c.onLayout(1f, 1000f)
        return c
    }

    private fun levelLine(kind: OrderLineKind, price: Double) = ChartOrderLine(
        id = 1, kind = kind, price = price, draggable = true, side = Side.LONG, lots = 1.0, entryPrice = 2400.0,
    )

    private fun withOverlay(c: ChartController, vararg lines: ChartOrderLine) {
        c.setOverlay(ChartOverlayState(lines = lines.toList(), bid = 2400.0, spread = 0.30))
    }

    @Test
    fun `dragging an existing level previews then resolves to a Set`() {
        val c = controller()
        withOverlay(c, levelLine(OrderLineKind.SL, 2390.0))
        c.beginLevelDrag(LevelHit.Line(1, OrderLineKind.SL))
        c.updateLevelDrag(2385.0, 42f, 88f)
        assertEquals(2385.0, c.state.dragPreview!!.price, 1e-9)

        val outcome = c.endLevelDrag(2385.0, market = 2400.0, spread = 0.30)
        assertEquals(LevelOutcome.Set(1, OrderLineKind.SL, 2385.0, isNew = false), outcome)
        // The preview survives until the app layer commits or cancels.
        assertNotNull(c.state.dragPreview)
        c.cancelLevelDrag()
        assertNull(c.state.dragPreview)
        assertNull(c.activeDrag)
    }

    @Test
    fun `a handle drag is flagged as new`() {
        val c = controller()
        withOverlay(c, levelLine(OrderLineKind.SL, 2390.0))
        c.beginLevelDrag(LevelHit.Handle(1, OrderLineKind.SL))
        val outcome = c.endLevelDrag(2380.0, market = 2400.0, spread = 0.30)
        assertEquals(LevelOutcome.Set(1, OrderLineKind.SL, 2380.0, isNew = true), outcome)
    }

    @Test
    fun `an invalid level is rejected and the preview is dropped`() {
        val c = controller()
        withOverlay(c, levelLine(OrderLineKind.SL, 2390.0))
        c.beginLevelDrag(LevelHit.Line(1, OrderLineKind.SL))
        // A long SL above the entry is invalid.
        val outcome = c.endLevelDrag(2410.0, market = 2400.0, spread = 0.30)
        assertTrue(outcome is LevelOutcome.Rejected)
    }

    @Test
    fun `setOverlay keeps the drag preview in flight`() {
        val c = controller()
        withOverlay(c, levelLine(OrderLineKind.TP, 2410.0))
        c.beginLevelDrag(LevelHit.Line(1, OrderLineKind.TP))
        c.updateLevelDrag(2415.0, 10f, 20f)
        // The app rebuilds the overlay on every order emission; the preview must survive.
        withOverlay(c, levelLine(OrderLineKind.TP, 2410.0))
        assertEquals(2415.0, c.state.dragPreview!!.price, 1e-9)
    }

    @Test
    fun `cancel drops the drag without an outcome`() {
        val c = controller()
        withOverlay(c, levelLine(OrderLineKind.SL, 2390.0))
        c.beginLevelDrag(LevelHit.Line(1, OrderLineKind.SL))
        c.updateLevelDrag(2380.0, 5f, 5f)
        c.cancelLevelDrag()
        assertNull(c.activeDrag)
        assertNull(c.state.dragPreview)
    }
}
