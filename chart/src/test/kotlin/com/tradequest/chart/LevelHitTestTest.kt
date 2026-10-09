package com.tradequest.chart

import com.tradequest.engine.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Hit-testing of the order lines, tags and "+SL"/"+TP" handles in screen space. */
class LevelHitTestTest {

    private val range = PriceRange(2380.0, 2420.0)
    private val geo = ChartGeometry(left = 0f, top = 0f, right = 800f, bottom = 400f, priceRange = range, visible = 0..10)
    private val density = 1f

    private fun entry(handles: List<OrderLineKind>, entry: Double = 2400.0) = ChartOrderLine(
        id = 1, kind = OrderLineKind.ENTRY, price = entry, label = "Entry 1.0L", draggable = false,
        side = Side.LONG, lots = 1.0, entryPrice = entry, handles = handles,
    )

    private fun level(kind: OrderLineKind, price: Double) = ChartOrderLine(
        id = 1, kind = kind, price = price, draggable = true, side = Side.LONG, lots = 1.0, entryPrice = 2400.0,
    )

    private fun overlay(vararg lines: ChartOrderLine) = ChartOverlayState(lines = lines.toList())

    @Test
    fun `the line body is grabbable above and below its price`() {
        val y = ChartMath.priceToY(2410.0, range, geo.plot)
        val hit = LevelHitTest.hit(overlay(level(OrderLineKind.SL, 2410.0)), geo, 400f, y + 10f, density)
        assertEquals(LevelHit.Line(1, OrderLineKind.SL), hit)
    }

    @Test
    fun `a line far from the finger is not hit`() {
        val y = ChartMath.priceToY(2410.0, range, geo.plot)
        val hit = LevelHitTest.hit(overlay(level(OrderLineKind.SL, 2410.0)), geo, 400f, y + 200f, density)
        assertEquals(null, hit)
    }

    @Test
    fun `an empty entry offers its missing handles on the right gutter`() {
        val ov = overlay(entry(listOf(OrderLineKind.SL, OrderLineKind.TP)))
        val y = ChartMath.priceToY(2400.0, range, geo.plot)
        val hw = LevelGeometry.handleWidth(density, 1f)
        // Two handles sit side by side against the plot's right edge; the left-most is SL.
        val slX = geo.right - 2 * hw + hw / 2f
        val tpX = geo.right - hw / 2f
        assertEquals(LevelHit.Handle(1, OrderLineKind.SL), LevelHitTest.hit(ov, geo, slX, y, density))
        assertEquals(LevelHit.Handle(1, OrderLineKind.TP), LevelHitTest.hit(ov, geo, tpX, y, density))
    }

    @Test
    fun `an entry with levels set offers no plus-SL plus-TP handles`() {
        val ov = overlay(entry(emptyList()))
        val y = ChartMath.priceToY(2400.0, range, geo.plot)
        // Tapping the entry tag clears the position's levels; it is never a handle.
        val hit = LevelHitTest.hit(ov, geo, geo.right - 20f, y, density)
        assertTrue(hit !is LevelHit.Handle, "expected no handle, got $hit")
    }

    @Test
    fun `a hit on the tag close box clears the level`() {
        val ov = overlay(level(OrderLineKind.TP, 2410.0))
        val y = ChartMath.priceToY(2410.0, range, geo.plot)
        // The "x" box occupies the tag's right edge, inside the plot.
        val closeX = geo.right - LevelGeometry.closeBox(density, 1f) / 2f
        val hit = LevelHitTest.hit(ov, geo, closeX, y, density)
        assertEquals(LevelHit.CloseBox(1, OrderLineKind.TP), hit)
    }

    @Test
    fun `a hit on the tag body drags the level`() {
        val ov = overlay(level(OrderLineKind.TP, 2410.0))
        val y = ChartMath.priceToY(2410.0, range, geo.plot)
        // The tag grows left over the chart from the plot's right edge.
        val tagX = geo.right - 40f
        val hit = LevelHitTest.hit(ov, geo, tagX, y, density)
        assertEquals(LevelHit.Line(1, OrderLineKind.TP), hit)
    }

    @Test
    fun `a tap on the entry tag clears the position's levels`() {
        val ov = overlay(entry(listOf(OrderLineKind.SL)), level(OrderLineKind.TP, 2410.0))
        val y = ChartMath.priceToY(2400.0, range, geo.plot)
        // Inside the entry tag body but clear of the "+SL" handle band at the plot edge.
        val tagX = geo.right - 60f
        assertEquals(LevelHit.EntryTag(1), LevelHitTest.hit(ov, geo, tagX, y, density))
    }

    @Test
    fun `the nearest line wins when two are close`() {
        val wellAway = 2410.0
        val near = 2411.0
        val ov = overlay(level(OrderLineKind.SL, wellAway), level(OrderLineKind.TP, near))
        val y = ChartMath.priceToY(near, range, geo.plot)
        val hit = LevelHitTest.hit(ov, geo, 400f, y, density)
        assertEquals(LevelHit.Line(1, OrderLineKind.TP), hit)
    }
}
