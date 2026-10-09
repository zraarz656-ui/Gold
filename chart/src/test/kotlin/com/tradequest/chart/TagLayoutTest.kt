package com.tradequest.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The tag-stacking maths: no two tags overlap and none leaves the plot. */
class TagLayoutTest {

    private val height = 20f
    private val top = 0f
    private val bottom = 400f

    @Test
    fun `tags already clear of each other are left alone`() {
        val placed = TagLayout.place(listOf(50f, 150f, 250f), height, top, bottom)
        assertEquals(listOf(50f, 150f, 250f), placed)
    }

    @Test
    fun `tags that collide are pushed apart in input order`() {
        val placed = TagLayout.place(listOf(100f, 105f), height, top, bottom)
        assertTrue(placed[1] - placed[0] >= height, "placed=$placed")
    }

    @Test
    fun `every placed tag stays within the plot`() {
        val placed = TagLayout.place(listOf(-500f, 5f, 6f, 700f), height, top, bottom)
        placed.forEach {
            assertTrue(it >= top + height / 2f - 0.001f, "above plot: $it")
            assertTrue(it <= bottom - height / 2f + 0.001f, "below plot: $it")
        }
    }

    @Test
    fun `no two placed tags overlap even when crowded at the top`() {
        val placed = TagLayout.place(List(6) { 2f }, height, top, bottom).sorted()
        for (i in 1 until placed.size) {
            assertTrue(placed[i] - placed[i - 1] >= height - 0.001f, "overlap: $placed")
        }
    }
}
