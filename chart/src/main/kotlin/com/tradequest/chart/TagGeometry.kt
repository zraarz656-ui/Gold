package com.tradequest.chart

/** A screen-space rectangle for a tag. */
data class TagRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerY: Float get() = (top + bottom) / 2f
    val centerX: Float get() = (left + right) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * One definition of every tag's on-screen rectangle, shared by the renderer and the hit
 * test so the tag you see is exactly the tag you can grab. Order tags end at the plot's
 * right edge and grow left over the chart; the current-price and crosshair tags sit in the
 * far-right gutter against the screen edge, where nothing can hide them.
 */
object TagGeom {

    fun orderTag(plot: PlotRect, centerY: Float, width: Float, density: Float, scale: Float): TagRect {
        val h = LevelGeometry.tagHeight(density, scale)
        val y = centerY.coerceIn(plot.top + h / 2f, plot.bottom - h / 2f)
        return TagRect(plot.right - width, y - h / 2f, plot.right, y + h / 2f)
    }

    /** The merged "N pos" entry tag: right-aligned, at least as wide as its measured text. */
    fun entryGroupTag(plot: PlotRect, centerY: Float, textWidth: Float, density: Float, scale: Float): TagRect {
        val textPx = maxOf(textWidth, LevelGeometry.MIN_ORDER_TAG_WIDTH_DP * density * scale)
        val w = textPx + 2f * LevelGeometry.TAG_PAD_DP * density * scale
        return orderTag(plot, centerY, w, density, scale)
    }

    /**
     * The current-price pill. It fills the gutter exactly (so nothing can cover it and it is
     * flush with the screen edge), but never narrower than its own text plus padding, so the
     * price is never clipped. [textWidth] is the measured width of the widest price to show.
     */
    fun priceTag(
        screenWidth: Float,
        plot: PlotRect,
        centerY: Float,
        density: Float,
        scale: Float,
        textWidth: Float = 0f,
    ): TagRect {
        val h = LevelGeometry.priceTagHeight(density, scale)
        // The pill fills the gutter (screenWidth - plot.right) so it always fits the widest
        // label; its right edge sits on the screen edge and nothing extends past it.
        val w = maxOf(
            screenWidth - plot.right,
            textWidth + 2f * LevelGeometry.TAG_PAD_DP * density * scale,
            LevelGeometry.priceTagWidth(density, scale),
        )
        val y = centerY.coerceIn(plot.top + h / 2f, plot.bottom - h / 2f)
        return TagRect(screenWidth - w, y - h / 2f, screenWidth, y + h / 2f)
    }
}
