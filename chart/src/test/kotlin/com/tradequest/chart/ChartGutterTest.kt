package com.tradequest.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The right price gutter: wide enough for the widest label, never wider than it must be. */
class ChartGutterTest {

    private val measure = TagTextMeasure { text, sp -> text.length * sp }
    private val density = 1f

    @Test
    fun `the gutter is the widest label plus padding`() {
        // 7 chars at 15sp = 105, plus 2 x 4dp padding = 113.
        val w = ChartGutter.widthPx(listOf("2400.50" to 15f, "2400.50" to 12f), measure, density)
        assertEquals(113f, w, 1e-4f)
    }

    @Test
    fun `the gutter takes the widest of all samples`() {
        val w = ChartGutter.widthPx(listOf("1" to 12f, "12345.67" to 15f), measure, density)
        assertEquals(8 * 15f + 8f, w, 1e-4f)
    }

    @Test
    fun `the gutter never falls below the minimum`() {
        val w = ChartGutter.widthPx(listOf("1" to 12f), measure, density)
        assertEquals(ChartGutter.MIN_WIDTH_DP, w, 1e-4f)
    }

    @Test
    fun `a bigger label size widens the gutter`() {
        val small = ChartGutter.widthPx(listOf("2400.50" to 15f * 0.85f), measure, density)
        val large = ChartGutter.widthPx(listOf("2400.50" to 15f * 1.2f), measure, density)
        assertTrue(large > small, "large=$large small=$small")
    }
}
