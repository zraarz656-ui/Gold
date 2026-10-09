package com.tradequest.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.tradequest.chart.ChartTheme
import com.tradequest.chart.ThemeId

/**
 * The app's colour tokens. Every screen must draw from here; no screen may use a
 * hard-coded [Color] so the four themes (dark, light, OLED, colour-blind) all stay
 * readable. Token pairs are asserted for contrast in ThemeContrastTest.
 */
data class TradeQuestColors(
    /** Window/page background. */
    val surface: Color,
    /** Raised container: panels, cards and the order sheet background. */
    val surfaceVariant: Color,
    /** Primary text on [surface]/[surfaceVariant]. */
    val onSurface: Color,
    /** Secondary/muted text and field labels on [surface]/[surfaceVariant]. */
    val onSurfaceVariant: Color,
    /** Borders, dividers and field outlines. */
    val outline: Color,
    /** Interactive highlight / selection. */
    val accent: Color,
    /** Text/icon colour placed on top of [accent], [positive] or [negative]. */
    val onAccent: Color,
    /** Profitable values. */
    val positive: Color,
    /** Losing values. */
    val negative: Color,
    /** Attention/limit-order values (amber). */
    val warning: Color,
)

/** The Material 3 colour scheme derived from these tokens. */
val TradeQuestColors.scheme: ColorScheme
    get() = if (isLight) {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = accent,
            onSecondary = onAccent,
            tertiary = positive,
            onTertiary = onAccent,
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = negative,
            onError = onAccent,
            errorContainer = surfaceVariant,
            onErrorContainer = onSurface,
        )
    } else {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = accent,
            onSecondary = onAccent,
            tertiary = positive,
            onTertiary = onAccent,
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = negative,
            onError = onAccent,
            errorContainer = surfaceVariant,
            onErrorContainer = onSurface,
        )
    }

/** Light surfaces need dark-accent text; dark surfaces need light-accent text. */
val TradeQuestColors.isLight: Boolean get() = surface.luminance() > 0.5

/** Dark theme (default): slate background, teal/red P&L. */
val DarkColors = TradeQuestColors(
    surface = Color(0xFF12161C),
    surfaceVariant = Color(0xFF1A1F27),
    onSurface = Color(0xFFECEFF1),
    onSurfaceVariant = Color(0xFFB0BEC5),
    outline = Color(0xFF546E7A),
    accent = Color(0xFF42A5F5),
    onAccent = Color(0xFF0B0E12),
    positive = Color(0xFF26A69A),
    negative = Color(0xFFEF5350),
    warning = Color(0xFFFFB300),
)

/** Light theme: near-white background, darker accents to keep 4.5:1. */
val LightColors = TradeQuestColors(
    surface = Color(0xFFFDFDFD),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2430),
    onSurfaceVariant = Color(0xFF3E4C59),
    outline = Color(0xFF5A6672),
    accent = Color(0xFF1565C0),
    onAccent = Color(0xFFFFFFFF),
    positive = Color(0xFF00796B),
    negative = Color(0xFFC62828),
    warning = Color(0xFF8A6100),
)

/** OLED theme: true black background to save power on OLED panels. */
val OledColors = TradeQuestColors(
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF101418),
    onSurface = Color(0xFFECEFF1),
    onSurfaceVariant = Color(0xFFA7B4C2),
    outline = Color(0xFF4A5560),
    accent = Color(0xFF40C4FF),
    onAccent = Color(0xFF0B0E12),
    positive = Color(0xFF00E676),
    negative = Color(0xFFFF5252),
    warning = Color(0xFFFFC107),
)

/** Colour-blind theme: blue/orange instead of green/red, with dark text on the buttons. */
val ColorBlindColors = TradeQuestColors(
    surface = Color(0xFF0E1116),
    surfaceVariant = Color(0xFF171C23),
    onSurface = Color(0xFFECEFF1),
    onSurfaceVariant = Color(0xFFB0BEC5),
    outline = Color(0xFF546E7A),
    accent = Color(0xFF42A5F5),
    onAccent = Color(0xFF0B0E12),
    positive = Color(0xFF1E88E5),
    negative = Color(0xFFFF8F00),
    warning = Color(0xFFFFC107),
)

/** Resolve the app tokens for a chart theme so the chart and the UI stay in sync. */
fun colorsFor(theme: ChartTheme): TradeQuestColors = when (theme.id) {
    ThemeId.DARK -> DarkColors
    ThemeId.LIGHT -> LightColors
    ThemeId.OLED -> OledColors
    ThemeId.COLORBLIND -> ColorBlindColors
}

val LocalTradeQuestColors = staticCompositionLocalOf { DarkColors }

/** Convenience accessor for the current tokens. */
val tradeColors: TradeQuestColors
    @Composable get() = LocalTradeQuestColors.current

/**
 * Applies the Material 3 scheme for [theme] and exposes the tokens through
 * [LocalTradeQuestColors]. Also publishes [onSurface] as the default content colour so
 * every child `Text` inherits a readable colour without setting it explicitly.
 */
@Composable
fun TradeQuestTheme(theme: ChartTheme = ChartTheme.DARK, content: @Composable () -> Unit) {
    val colors = colorsFor(theme)
    CompositionLocalProvider(
        LocalTradeQuestColors provides colors,
        androidx.compose.material3.LocalContentColor provides colors.onSurface,
        content = {
            MaterialTheme(colorScheme = colors.scheme, content = content)
        },
    )
}

private fun Color.luminance(): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f
        else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}
