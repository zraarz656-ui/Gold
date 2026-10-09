package com.tradequest.chart

/**
 * The width of the right-hand price gutter. It is measured from the widest label the gutter
 * must hold (the current-price pill, the grid ticks and the crosshair tag) plus padding, so
 * the current-price pill always fits and nothing spills past the screen edge.
 *
 * The padding is [PAD_DP] on each side, so the gutter is the widest label plus 12dp — the
 * same 12dp the current-price pill adds around its own text. Pure given a text measurer, so
 * "the pill fits the gutter" is asserted in tests.
 */
object ChartGutter {

    /** Breathing room kept on each side of the widest label; 2 x 6dp = 12dp of padding. */
    const val PAD_DP = LevelGeometry.TAG_PAD_DP

    /** A floor so a very short price still leaves a usable gutter. */
    const val MIN_WIDTH_DP = 56f

    /**
     * The gutter width for [samples] (each a label and its text size in sp), in pixels:
     * the widest measured label plus [PAD_DP] on both sides, floored at [MIN_WIDTH_DP].
     */
    fun widthPx(samples: List<Pair<String, Float>>, measure: TagTextMeasure, density: Float): Float {
        val widest = samples.maxOfOrNull { measure.width(it.first, it.second) } ?: 0f
        val minPx = MIN_WIDTH_DP * density
        return maxOf(widest + 2f * PAD_DP * density, minPx)
    }

    /** The text sizes the gutter must accommodate: the pill (15sp) and a grid tick (12sp). */
    fun sampleSizes(scale: Float): Pair<Float, Float> = 15f * scale to 12f * scale
}
