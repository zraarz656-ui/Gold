package com.tradequest.chart

import androidx.compose.ui.graphics.Color

/**
 * Colour palette for the chart. Colours are recovered from the Phase 2 build so the
 * recreated chart matches the original look.
 */
data class ChartTheme(
    val id: ThemeId,
    val name: String,
    val background: Color,
    val grid: Color,
    val axisText: Color,
    val up: Color,
    val down: Color,
    val upWick: Color,
    val downWick: Color,
    val crosshair: Color,
    val currentPrice: Color,
    val marketClosed: Color,
    val newsLow: Color,
    val newsMedium: Color,
    val newsHigh: Color,
) {
    companion object {
        val DARK = ChartTheme(
            ThemeId.DARK, "Dark",
            Color(0xFF12161C), Color(0x22FFFFFF), Color(0xFF9AA4B2), Color(0xFF26A69A), Color(0xFFEF5350),
            Color(0xFF26A69A), Color(0xFFEF5350), Color(0xFFB0BEC5), Color(0xFF42A5F5), Color(0x14000000),
            Color(0xFF78909C), Color(0xFFFFB300), Color(0xFFE53935),
        )

        val LIGHT = ChartTheme(
            ThemeId.LIGHT, "Light",
            Color(0xFFFDFDFD), Color(0x1A000000), Color(0xFF546E7A), Color(0xFF00897B), Color(0xFFD32F2F),
            Color(0xFF00897B), Color(0xFFD32F2F), Color(0xFF37474F), Color(0xFF1565C0), Color(0x0A000000),
            Color(0xFF90A4AE), Color(0xFFF57F17), Color(0xFFC62828),
        )

        val OLED = ChartTheme(
            ThemeId.OLED, "OLED",
            Color(0xFF000000), Color(0x1FFFFFFF), Color(0xFF8A94A6), Color(0xFF00E676), Color(0xFFFF5252),
            Color(0xFF00E676), Color(0xFFFF5252), Color(0xFFCFD8DC), Color(0xFF40C4FF), Color(0x0AFFFFFF),
            Color(0xFF607D8B), Color(0xFFFFC107), Color(0xFFFF1744),
        )

        val COLOR_BLIND = ChartTheme(
            ThemeId.COLORBLIND, "Color-blind",
            Color(0xFF0E1116), Color(0x22FFFFFF), Color(0xFF9AA4B2), Color(0xFF1E88E5), Color(0xFFFF8F00),
            Color(0xFF1E88E5), Color(0xFFFF8F00), Color(0xFFB0BEC5), Color(0xFF9E9E9E), Color(0x14000000),
            Color(0xFF78909C), Color(0xFFFDD835), Color(0xFFFF6D00),
        )

        val all: List<ChartTheme> = listOf(DARK, LIGHT, OLED, COLOR_BLIND)

        fun byId(id: ThemeId): ChartTheme = all.first { it.id == id }
    }
}
