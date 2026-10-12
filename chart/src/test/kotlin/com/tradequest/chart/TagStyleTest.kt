package com.tradequest.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Colour guarantees for the price tags. These are the "text is readable" promises the
 * visual pass makes, so they are asserted rather than eyeballed.
 */
class TagStyleTest {

    @Test
    fun `current-price text reaches seven to one in every theme and direction`() {
        for (theme in ChartTheme.all) {
            for (up in listOf(true, false)) {
                val fill = TagStyle.currentPriceFill(theme, up)
                val text = TagStyle.textOn(fill)
                val ratio = TagStyle.contrast(fill, text)
                assertTrue(
                    ratio >= 7.0,
                    "${theme.name} up=$up tag text contrast $ratio < 7.0 (fill=$fill text=$text)",
                )
            }
        }
    }

    @Test
    fun `the current-price fill keeps the palette hue when the palette already clears seven`() {
        // OLED green is bright enough as-is; it should not be altered.
        val fill = TagStyle.currentPriceFill(ChartTheme.OLED, up = true)
        assertEquals(ChartTheme.OLED.up, fill)
    }

    @Test
    fun `order-tag text clears four and a half to one on its fill in every theme`() {
        for (theme in ChartTheme.all) {
            for (kind in OrderLineKind.entries) {
                val fill = TagStyle.ensureContrast(TagStyle.orderColor(kind), 4.5)
                val ratio = TagStyle.contrast(fill, TagStyle.textOn(fill))
                assertTrue(ratio >= 4.5, "${theme.name} $kind contrast $ratio < 4.5")
            }
        }
    }

    @Test
    fun `the crosshair tag reads on every theme`() {
        for (theme in ChartTheme.all) {
            val tag = TagStyle.invertedTag(theme)
            val ratio = TagStyle.contrast(tag.fill, tag.text)
            assertTrue(ratio >= 7.0, "${theme.name} crosshair contrast $ratio < 7.0")
        }
    }

    @Test
    fun `textOn picks the higher-contrast of black and white`() {
        assertEquals(androidx.compose.ui.graphics.Color.White, TagStyle.textOn(androidx.compose.ui.graphics.Color(0xFF202020)))
        assertEquals(androidx.compose.ui.graphics.Color.Black, TagStyle.textOn(androidx.compose.ui.graphics.Color(0xFFEDEDED)))
    }

    @Test
    fun `contrast matches the WCAG reference values`() {
        val ratio = TagStyle.contrast(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black)
        assertEquals(21.0, ratio, 0.01)
    }

    @Test
    fun `brighten lifts a grid label away from the background in every theme`() {
        for (theme in ChartTheme.all) {
            val bright = TagStyle.brighten(theme.axisText, theme, 0.35f)
            val before = TagStyle.contrast(theme.axisText, theme.background)
            val after = TagStyle.contrast(bright, theme.background)
            assertTrue(after > before, "${theme.name}: brightening did not help ($before -> $after)")
        }
    }

    @Test
    fun `label-size default is medium so the current-price tag is fifteen sp`() {
        assertEquals(PriceLabelSize.MEDIUM, PriceLabelSize.default)
        assertEquals(1.0f, PriceLabelSize.default.scale)
        assertEquals(15f, 15f * PriceLabelSize.default.scale)
    }

    @Test
    fun `the current-price fill is fully opaque when drawn`() {
        for (theme in ChartTheme.all) {
            for (up in listOf(true, false)) {
                val fill = TagStyle.currentPriceFill(theme, up)
                assertEquals(1f, fill.alpha, 1e-6f, "${theme.name} up=$up pill must be opaque")
            }
        }
    }
}
