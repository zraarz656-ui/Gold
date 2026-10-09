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
    private val measure = TagTextMeasure { text, sp -> text.length * sp }

    private fun state(
        vararg lines: ChartOrderLine,
        selected: Long? = null,
    ) = ChartState(
        overlay = ChartOverlayState(lines = lines.toList()),
        density = density,
        selectedEntryId = selected,
    )

    private fun entry(handles: List<OrderLineKind>, entry: Double = 2400.0) = ChartOrderLine(
        id = 1, kind = OrderLineKind.ENTRY, price = entry, label = "Entry 1.0L", draggable = false,
        side = Side.LONG, lots = 1.0, entryPrice = entry, handles = handles,
    )

    private fun level(kind: OrderLineKind, price: Double, id: Long = 1) = ChartOrderLine(
        id = id, kind = kind, price = price, draggable = true, side = Side.LONG, lots = 1.0, entryPrice = 2400.0,
    )

    @Test
    fun `the line body is grabbable above and below its price`() {
        val y = ChartMath.priceToY(2410.0, range, geo.plot)
        val hit = LevelHitTest.hit(state(level(OrderLineKind.SL, 2410.0)), geo, 400f, y + 10f, null, measure)
        assertEquals(LevelHit.Line(1, OrderLineKind.SL), hit)
    }

    @Test
    fun `a line far from the finger is not hit`() {
        val y = ChartMath.priceToY(2410.0, range, geo.plot)
        val hit = LevelHitTest.hit(state(level(OrderLineKind.SL, 2410.0)), geo, 400f, y + 200f, null, measure)
        assertEquals(null, hit)
    }

    @Test
    fun `only the selected position offers its missing handles`() {
        val st = state(entry(listOf(OrderLineKind.SL, OrderLineKind.TP)), selected = 1)
        val frame = orderTagFrameFor(st, geo, measure, 1L)
        assertEquals(2, frame.handles.size)
        for ((i, rect) in frame.handles.withIndex()) {
            val expected = frame.handleTargets[i].kind
            assertEquals(expected, LevelHitTest.hit(st, geo, rect.centerX, rect.centerY, 1L, measure)?.let {
                (it as LevelHit.Handle).kind
            })
        }
    }

    @Test
    fun `an unselected position offers no handles`() {
        val st = state(entry(listOf(OrderLineKind.SL, OrderLineKind.TP)), selected = null)
        val y = ChartMath.priceToY(2400.0, range, geo.plot)
        val hit = LevelHitTest.hit(st, geo, geo.right - 20f, y, null, measure)
        assertTrue(hit !is LevelHit.Handle, "expected no handle, got $hit")
    }

    @Test
    fun `a hit on the tag close box clears the level`() {
        val st = state(level(OrderLineKind.TP, 2410.0))
        val frame = orderTagFrameFor(st, geo, measure, null)
        val rect = frame.tags[0].rect
        val hit = LevelHitTest.hit(st, geo, rect.right - 4f, rect.centerY, null, measure)
        assertEquals(LevelHit.CloseBox(1, OrderLineKind.TP), hit)
    }

    @Test
    fun `a hit on the tag body drags the level`() {
        val st = state(level(OrderLineKind.TP, 2410.0))
        val frame = orderTagFrameFor(st, geo, measure, null)
        val rect = frame.tags[0].rect
        val hit = LevelHitTest.hit(st, geo, rect.left + 6f, rect.centerY, null, measure)
        assertEquals(LevelHit.Line(1, OrderLineKind.TP), hit)
    }

    @Test
    fun `a tap on an entry tag selects the position`() {
        val st = state(entry(emptyList()), level(OrderLineKind.TP, 2410.0, id = 2))
        val frame = orderTagFrameFor(st, geo, measure, null)
        val entrySlot = frame.order.indexOfFirst { (it.target as? TagTarget.Line)?.id == 1L }
        val rect = frame.tags[entrySlot].rect
        assertEquals(LevelHit.EntryTag(1), LevelHitTest.hit(st, geo, rect.centerX, rect.centerY, null, measure))
    }

    @Test
    fun `the nearest line wins when two are close`() {
        val ov = state(level(OrderLineKind.SL, 2410.0, id = 1), level(OrderLineKind.TP, 2411.0, id = 2))
        val y = ChartMath.priceToY(2411.0, range, geo.plot)
        val hit = LevelHitTest.hit(ov, geo, 400f, y, null, measure)
        assertEquals(LevelHit.Line(2, OrderLineKind.TP), hit)
    }
}
