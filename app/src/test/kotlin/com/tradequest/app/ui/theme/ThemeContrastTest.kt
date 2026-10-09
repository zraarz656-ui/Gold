package com.tradequest.app.ui.theme

import com.tradequest.chart.ChartTheme
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the promise that text stays readable in every theme. Input text, labels,
 * placeholders and values must reach at least 4.5:1 (WCAG AA) against the surface they
 * sit on; this is what the order sheet broke with default Material colours.
 */
class ThemeContrastTest {

    private val minRatio = 4.5

    @Test
    fun foregroundTokensMeetAaOnTheirSurfaces() {
        forEachTheme { name, c ->
            // Body text, labels, placeholders and values.
            assertContrast(name, "onSurface on surface", c.onSurface, c.surface)
            assertContrast(name, "onSurface on surfaceVariant", c.onSurface, c.surfaceVariant)
            assertContrast(name, "onSurfaceVariant on surface", c.onSurfaceVariant, c.surface)
            assertContrast(name, "onSurfaceVariant on surfaceVariant", c.onSurfaceVariant, c.surfaceVariant)
            // Semantic values drawn as text.
            assertContrast(name, "accent on surface", c.accent, c.surface)
            assertContrast(name, "positive on surface", c.positive, c.surface)
            assertContrast(name, "negative on surface", c.negative, c.surface)
            assertContrast(name, "warning on surface", c.warning, c.surface)
            assertContrast(name, "positive on surfaceVariant", c.positive, c.surfaceVariant)
            assertContrast(name, "negative on surfaceVariant", c.negative, c.surfaceVariant)
            assertContrast(name, "warning on surfaceVariant", c.warning, c.surfaceVariant)
            // Button/selected-chip text on its filled background.
            assertContrast(name, "onAccent on accent", c.onAccent, c.accent)
            assertContrast(name, "onAccent on positive", c.onAccent, c.positive)
            assertContrast(name, "onAccent on negative", c.onAccent, c.negative)
        }
    }

    private fun forEachTheme(block: (String, TradeQuestColors) -> Unit) {
        for (theme in ChartTheme.all) {
            block(theme.name, colorsFor(theme))
        }
    }

    private fun assertContrast(theme: String, what: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
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
