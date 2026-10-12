package com.tradequest.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The order-tag layout promises: no tag is dropped, none overlaps, handles stay clear. */
class OrderTagsTest {

    private val plot = PlotRect(0f, 0f, 800f, 400f)
    private val tagH = 24f
    private val handleH = 22f
    private val gap = 20f

    private fun tags(count: Int, y: Float = 200f) = List(count) {
        OrderTags.TagLine(centerY = y, lineY = y, width = 120f, density = 1f, scale = 1f)
    }

    @Test
    fun `every line gets a tag even when they all share one price`() {
        val frame = OrderTags.layout(plot, tags(5), emptyList(), tagH, handleH, gap)
        assertEquals(5, frame.tags.size, "a tag was dropped")
    }

    @Test
    fun `stacked tags never overlap and keep at least the minimum gap`() {
        val frame = OrderTags.layout(plot, tags(6), emptyList(), tagH, handleH, gap)
        val centers = frame.tags.map { it.rect.centerY }.sorted()
        for (i in 1 until centers.size) {
            assertTrue(centers[i] - centers[i - 1] >= tagH - 1e-3f, "overlap: $centers")
        }
    }

    @Test
    fun `a tag separated from its line is flagged for a leader`() {
        // Two tags at the same price both cannot sit on the line: one is displaced.
        val frame = OrderTags.layout(plot, tags(2, y = 200f), emptyList(), tagH, handleH, gap)
        assertTrue(frame.tags.any { it.leader }, "expected a leader for the stacked tag")
    }

    @Test
    fun `a lone tag sits on its line with no leader`() {
        val frame = OrderTags.layout(plot, tags(1, y = 200f), emptyList(), tagH, handleH, gap)
        assertTrue(!frame.tags[0].leader)
        assertEquals(200f, frame.tags[0].rect.centerY, 1e-3f)
    }

    @Test
    fun `handles are pushed left of the whole tag column`() {
        val handle = OrderTags.HandleBox(1, OrderLineKind.SL, 0, 200f, 1f, 1f)
        val frame = OrderTags.layout(plot, tags(1, y = 200f), listOf(handle), tagH, handleH, gap)
        assertTrue(frame.handles[0].right <= frame.tags.minOf { it.rect.left } + 1e-3f, "handle overlaps a tag")
    }

    @Test
    fun `a tag whose line is off-screen is still drawn`() {
        val off = listOf(OrderTags.TagLine(centerY = -500f, lineY = -500f, width = 100f, density = 1f, scale = 1f))
        val frame = OrderTags.layout(plot, off, emptyList(), tagH, handleH, gap)
        assertEquals(1, frame.tags.size)
        assertTrue(frame.tags[0].rect.centerY >= plot.top, "tag left the plot")
        assertTrue(frame.tags[0].leader, "an off-screen tag needs a leader toward its line")
    }
}
