package com.tradequest.app.ui.theme

import com.tradequest.chart.ChartTheme
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the promise that text stays readable in every theme. Text tokens (input text,
 * labels, placeholders, values, semantic values and button text) must reach at least
 * 4.5:1 (WCAG AA for normal text). [TradeQuestColors.outline] is a border/divider, not
 * text, so it only has to clear 3:1 (WCAG AA for non-text UI). This is what the order
 * sheet broke with default Material colours.
 */
class ThemeContrastTest {

    private val minTextRatio = 4.5
    private val minOutlineRatio = 3.0

    @Test
    fun foregroundTokensMeetAaOnTheirSurfaces() {
        forEachTheme { name, c ->
            // Body text, labels, placeholders and values.
            assertContrast(name, "onSurface on surface", c.onSurface, c.surface, minTextRatio)
            assertContrast(name, "onSurface on surfaceVariant", c.onSurface, c.surfaceVariant, minTextRatio)
            assertContrast(name, "onSurfaceVariant on surface", c.onSurfaceVariant, c.surface, minTextRatio)
            assertContrast(name, "onSurfaceVariant on surfaceVariant", c.onSurfaceVariant, c.surfaceVariant, minTextRatio)
            // Semantic values drawn as text.
            assertContrast(name, "accent on surface", c.accent, c.surface, minTextRatio)
            assertContrast(name, "positive on surface", c.positive, c.surface, minTextRatio)
            assertContrast(name, "negative on surface", c.negative, c.surface, minTextRatio)
            assertContrast(name, "warning on surface", c.warning, c.surface, minTextRatio)
            assertContrast(name, "positive on surfaceVariant", c.positive, c.surfaceVariant, minTextRatio)
            assertContrast(name, "negative on surfaceVariant", c.negative, c.surfaceVariant, minTextRatio)
            assertContrast(name, "warning on surfaceVariant", c.warning, c.surfaceVariant, minTextRatio)
            // Button/selected-chip text on its filled background.
            assertContrast(name, "onAccent on accent", c.onAccent, c.accent, minTextRatio)
            assertContrast(name, "onAccent on positive", c.onAccent, c.positive, minTextRatio)
            assertContrast(name, "onAccent on negative", c.onAccent, c.negative, minTextRatio)
        }
    }

    @Test
    fun outlineTokensMeetAaForNonText() {
        forEachTheme { name, c ->
            // Borders and dividers are non-text UI; WCAG AA requires 3:1 against the
            // surfaces they sit on.
            assertContrast(name, "outline on surface", c.outline, c.surface, minOutlineRatio)
            assertContrast(name, "outline on surfaceVariant", c.outline, c.surfaceVariant, minOutlineRatio)
        }
    }

    private fun forEachTheme(block: (String, TradeQuestColors) -> Unit) {
        for (theme in ChartTheme.all) {
            block(theme.name, colorsFor(theme))
        }
    }

    private fun assertContrast(
        theme: String,
        what: String,
        fg: androidx.compose.ui.graphics.Color,
        bg: androidx.compose.ui.graphics.Color,
        minRatio: Double,
    ) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(ratio >= minRatio) {
            "$theme: $what has contrast %.2f:1, needs >= $minRatio:1".format(ratio)
        }
    }

    private fun contrastRatio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun relativeLuminance(c: androidx.compose.ui.graphics.Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
