package com.tradequest.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tag and gutter geometry the layout pass promises: order tags shrink to their text and
 * right-align against the gutter, and the price pill fills the measured gutter exactly so
 * nothing extends past the screen edge.
 */
class TagGeometryTest {

    private val density = 1f
    private val scale = 1f

    // Deterministic "width = characters x size" measurer, so the maths is easy to assert.
    private val measure = TagTextMeasure { text, sp -> text.length * sp }

    @Test
    fun `an order tag shrinks to its text plus padding`() {
        val w = LevelGeometry.orderTagWidth(measure, "Entry 1.0L", density, scale, closeBox = false)
        // 10 chars x 12sp + 2 x 6dp padding = 132
        assertEquals(132f, w, 1e-4f)
    }

    @Test
    fun `a tag with a close box is wide enough for the box`() {
        val w = LevelGeometry.orderTagWidth(measure, "SL", density, scale, closeBox = true)
        // 2 x 12 + 12 padding + 24 close box = 60, over the 44dp floor.
        assertEquals(60f, w, 1e-4f)
    }

    @Test
    fun `an order tag never collapses below the minimum width`() {
        val w = LevelGeometry.orderTagWidth(measure, "", density, scale, closeBox = false)
        assertEquals(LevelGeometry.MIN_ORDER_TAG_WIDTH_DP, w, 1e-4f)
    }

    @Test
    fun `an order tag is right-aligned against the plot edge`() {
        val plot = PlotRect(0f, 0f, 800f, 400f)
        val rect = TagGeom.orderTag(plot, 200f, 120f, density, scale)
        assertEquals(800f, rect.right, 1e-4f)
        assertEquals(680f, rect.left, 1e-4f)
    }

    @Test
    fun `the price pill fills the measured gutter and ends on the screen edge`() {
        val screenWidth = 1080f
        val gutter = 130f
        val plot = PlotRect(0f, 0f, screenWidth - gutter, 600f)
        val rect = TagGeom.priceTag(screenWidth, plot, 300f, density, scale)
        assertEquals(screenWidth, rect.right, 1e-4f)
        // A gutter wider than the 104dp floor is filled exactly.
        assertEquals(screenWidth - gutter, rect.left, 1e-4f)
    }

    @Test
    fun `a narrow gutter still fits the pill at its floor width`() {
        val screenWidth = 1080f
        val plot = PlotRect(0f, 0f, screenWidth - 96f, 600f)
        val rect = TagGeom.priceTag(screenWidth, plot, 300f, density, scale)
        assertEquals(screenWidth - LevelGeometry.PRICE_TAG_WIDTH_DP, rect.left, 1e-4f)
    }

    @Test
    fun `nothing in the pill extends past the screen edge`() {
        val screenWidth = 1080f
        val plot = PlotRect(0f, 0f, screenWidth - 150f, 600f)
        val rect = TagGeom.priceTag(screenWidth, plot, 300f, density, scale)
        assertTrue(rect.right <= screenWidth + 1e-4f, "pill right ${rect.right} past $screenWidth")
    }

    @Test
    fun `the pill widens for a price wider than the gutter`() {
        val screenWidth = 1080f
        val plot = PlotRect(0f, 0f, screenWidth - 90f, 600f)
        val rect = TagGeom.priceTag(screenWidth, plot, 300f, density, scale, textWidth = 200f)
        // 200 + 2 x 6 padding = 212, wider than the 90px gutter.
        assertEquals(212f, rect.width, 1e-4f)
        assertEquals(screenWidth, rect.right, 1e-4f)
    }

    @Test
    fun `the grouped entry tag keeps its measured width so the text is never clipped`() {
        val plot = PlotRect(0f, 0f, 800f, 400f)
        // A wide "2 pos  -1234.56" needs more than the 44dp * 1.6 floor.
        val rect = TagGeom.entryGroupTag(plot, 200f, textWidth = 150f, density, scale)
        assertEquals(150f + 2f * LevelGeometry.TAG_PAD_DP, rect.width, 1e-4f)
        assertEquals(800f, rect.right, 1e-4f)
    }

    @Test
    fun `the grouped entry tag never collapses below the minimum width`() {
        val plot = PlotRect(0f, 0f, 800f, 400f)
        val rect = TagGeom.entryGroupTag(plot, 200f, textWidth = 0f, density, scale)
        assertEquals(
            LevelGeometry.MIN_ORDER_TAG_WIDTH_DP + 2f * LevelGeometry.TAG_PAD_DP,
            rect.width,
            1e-4f,
        )
    }
}
