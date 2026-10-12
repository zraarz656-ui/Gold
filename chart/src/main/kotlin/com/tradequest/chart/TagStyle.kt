package com.tradequest.chart

import androidx.compose.ui.graphics.Color

/**
 * How large the price labels (current-price, grid, order, crosshair tags) are drawn.
 * [MEDIUM] is the default; the choice is persisted in DataStore. Pure data so the scale
 * can be unit-tested.
 */
enum class PriceLabelSize(val label: String, val scale: Float) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1.0f),
    LARGE("Large", 1.2f),
    ;

    companion object {
        /** Medium: the current-price tag is 15sp at this scale, as specified. */
        val default: PriceLabelSize = MEDIUM

        fun fromId(id: String?): PriceLabelSize =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: default
    }
}

/**
 * Colour maths for the price tags. Kept free of Compose drawing so the contrast promises
 * ("white or black text, at least 7:1"; "readable in all four themes, 4.5:1") are asserted
 * in tests rather than eyeballed. Colours are plain [Color]s.
 */
object TagStyle {

    /** Order-tag fills: entry = accent blue, SL = red, TP = green, pending = amber. */
    fun orderColor(kind: OrderLineKind): Color = when (kind) {
        OrderLineKind.ENTRY -> Color(0xFF42A5F5)
        OrderLineKind.SL -> Color(0xFFEF5350)
        OrderLineKind.TP -> Color(0xFF26A69A)
        OrderLineKind.PENDING -> Color(0xFFFFB300)
    }

    /** The up/down colour a candle's close direction implies, from the chart palette. */
    fun candleColor(theme: ChartTheme, up: Boolean): Color = if (up) theme.up else theme.down

    /**
     * The fill for the current-price tag and line: the theme's up/down colour, nudged only
     * if needed so that black-or-white text reaches [minRatio]. Keeps the hue, so it still
     * reads as the palette's green or red.
     */
    fun currentPriceFill(theme: ChartTheme, up: Boolean, minRatio: Double = 7.0): Color =
        ensureContrast(candleColor(theme, up), minRatio)

    /**
     * Black or white text on [fill], whichever has more contrast. Never returns a colour
     * that fails the caller's [minRatio] promise more than the palette forces; pair it with
     * [ensureContrast] when a hard floor is required.
     */
    fun textOn(fill: Color): Color {
        val onWhite = contrast(fill, Color.White)
        val onBlack = contrast(fill, Color.Black)
        return if (onWhite >= onBlack) Color.White else Color.Black
    }

    /**
     * Nudge [fill] toward black or white by the smallest amount that lifts the best text
     * colour to [minRatio]. Returns [fill] unchanged when it already clears the floor, so
     * a palette colour is only altered when readability demands it.
     */
    fun ensureContrast(fill: Color, minRatio: Double): Color {
        val text = textOn(fill)
        if (contrast(fill, text) >= minRatio) return fill
        val target = if (text == Color.Black) Color.White else Color.Black
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (contrast(lerp(fill, target, mid), text) >= minRatio) hi = mid else lo = mid
        }
        return lerp(fill, target, hi)
    }

    /**
     * The crosshair price tag is inverted against the chart: a light fill on a dark theme,
     * a dark fill on a light theme. Text is the surface colour so it reads as a cut-out.
     */
    data class Inverted(val fill: Color, val text: Color)

    fun invertedTag(theme: ChartTheme): Inverted =
        if (theme.isLight) Inverted(Color(0xFF1B2430), Color(0xFFFFFFFF))
        else Inverted(Color(0xFFECEFF1), Color(0xFF12161C))

    /**
     * A thin 1dp outline that separates a tag from the candles behind it: a light hairline
     * on dark themes, a dark hairline on light themes.
     */
    fun outline(theme: ChartTheme): Color =
        if (theme.isLight) Color(0x66000000) else Color(0x99FFFFFF)

    /** Lighten [color] toward the theme's foreground by [t], for brighter grid labels. */
    fun brighten(color: Color, theme: ChartTheme, t: Float): Color {
        val target = if (theme.isLight) Color.Black else Color.White
        return lerp(color, target, t)
    }

    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun lerp(a: Color, b: Color, t: Float): Color =
        Color(
            red = a.red + (b.red - a.red) * t,
            green = a.green + (b.green - a.green) * t,
            blue = a.blue + (b.blue - a.blue) * t,
            alpha = a.alpha,
        )
}
