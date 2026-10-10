package com.tradequest.chart

import kotlin.math.abs

/**
 * Measures the pixel width of a tag's text so a tag can shrink to fit its label exactly.
 * The renderer supplies a real Compose measurer; tests supply a deterministic one, so the
 * renderer and the hit test always agree on where a tag ends.
 */
fun interface TagTextMeasure {
    fun width(text: String, sp: Float): Float

    companion object {
        /** A rough per-character estimate, only used when no real measurer is available. */
        val Approximate = TagTextMeasure { text, sp -> text.length * sp * 0.55f }
    }
}

/**
 * Shared geometry for the level tags and handles, in dp so it scales with the device and
 * the price-label-size setting. The renderer and the hit test both read these, so what you
 * see is exactly what you can grab.
 */
object LevelGeometry {
    /** Smallest an order tag may shrink to, so a glyph plus its padding always fit. */
    const val MIN_ORDER_TAG_WIDTH_DP = 44f

    /** Tag height for every tag (order tags and the slim current-price tag). */
    const val TAG_HEIGHT_DP = 22f

    /** The tappable "x" box at the tag's right edge. */
    const val CLOSE_BOX_DP = 24f

    /** The current-price / crosshair tag: fits "1234.56" at 12sp semibold. */
    const val PRICE_TAG_WIDTH_DP = 80f
    const val PRICE_TAG_HEIGHT_DP = 22f

    /** The notch on the current-price tag that points at the price line. */
    const val PRICE_TAG_NOTCH_DP = 5f

    /** The "+SL"/"+TP" handle, at the plot's right edge. */
    const val HANDLE_WIDTH_DP = 40f
    const val HANDLE_HEIGHT_DP = 22f

    /** Inner padding of a tag, from the fill to the text. */
    const val TAG_PAD_DP = 6f

    /** Corner radius of every tag pill: the slim 4dp radius. */
    const val TAG_RADIUS_DP = 4f

    /** The order-tag text size, matching the renderer. */
    const val ORDER_TAG_SP = 12f

    /** Order tags are drawn semi-transparent so the candles stay visible through them. */
    const val ORDER_TAG_OPACITY = 0.85f

    fun tagHeight(density: Float, scale: Float): Float = TAG_HEIGHT_DP * density * scale
    fun closeBox(density: Float, scale: Float): Float = CLOSE_BOX_DP * density * scale
    fun priceTagWidth(density: Float, scale: Float): Float = PRICE_TAG_WIDTH_DP * density * scale
    fun priceTagHeight(density: Float, scale: Float): Float = PRICE_TAG_HEIGHT_DP * density * scale
    fun priceTagNotch(density: Float, scale: Float): Float = PRICE_TAG_NOTCH_DP * density * scale
    fun handleWidth(density: Float, scale: Float): Float = HANDLE_WIDTH_DP * density * scale
    fun handleHeight(density: Float, scale: Float): Float = HANDLE_HEIGHT_DP * density * scale

    /**
     * An order tag's width: the measured text plus padding, floored so it never collapses,
     * and widened by the close box when the tag carries one. The tag is right-aligned
     * against the plot's right edge and grows left.
     */
    fun orderTagWidth(
        measure: TagTextMeasure,
        text: String,
        density: Float,
        scale: Float,
        closeBox: Boolean,
    ): Float {
        val textPx = measure.width(text, ORDER_TAG_SP * scale)
        val padPx = 2f * TAG_PAD_DP * density * scale
        val boxPx = if (closeBox) closeBox(density, scale) else 0f
        val minPx = MIN_ORDER_TAG_WIDTH_DP * density * scale
        return maxOf(textPx + padPx + boxPx, minPx)
    }
}

/** What the finger landed on, if anything. */
sealed interface LevelHit {
    /** The "x" box on a tag: tap to clear the level. */
    data class CloseBox(val lineId: Long, val kind: OrderLineKind) : LevelHit

    /** The line body over the plot, or a tag body: drag to move the level. */
    data class Line(val lineId: Long, val kind: OrderLineKind) : LevelHit

    /** An empty entry's "+SL"/"+TP" handle: drag out a new level. */
    data class Handle(val lineId: Long, val kind: OrderLineKind) : LevelHit

    /** The entry's own tag: a tap clears every level on that position. */
    data class EntryTag(val lineId: Long) : LevelHit

    /** A grouped "N pos" tag: a tap opens the Positions tab. */
    data object EntryGroupTag : LevelHit
}

/**
 * Screen-space hit testing for the order levels and the merged entry groups. Kept free of
 * Compose so the tolerance maths is unit-testable; the caller turns a resolved [LevelHit]
 * into a [LevelEdit] or a tab switch.
 *
 * Priority, highest first: a grouped entry tag, then the "x" box, then a "+SL"/"+TP"
 * handle (both small and explicit), then the line/tag body.
 */
object LevelHitTest {

    /** Half of the 44dp touch target, so a line is grabbable above and below. */
    const val DEFAULT_TOLERANCE_DP = 22f

    /** Extra horizontal slack on a "+" handle, kept small so neighbours do not overlap. */
    const val HANDLE_X_PAD = 4f

    fun tolerancePx(density: Float): Float = DEFAULT_TOLERANCE_DP * density

    fun hit(
        state: ChartState,
        geo: ChartGeometry,
        x: Float,
        y: Float,
        selectedEntryId: Long? = null,
        measure: TagTextMeasure = TagTextMeasure.Approximate,
    ): LevelHit? {
        val density = state.density
        val labelScale = state.labelScale
        val plot = geo.plot
        val frame = orderTagFrameFor(state, geo, measure, selectedEntryId)

        // 0. A grouped entry tag sits over everything; tapping it opens the Positions tab.
        frame.order.forEachIndexed { i, ft ->
            if (ft.target is TagTarget.Group && frame.tags[i].rect.contains(x, y)) return LevelHit.EntryGroupTag
        }

        // 1. "+SL"/"+TP" handles for the selected position only. An exact hit always wins;
        //    the widened band is only a fallback so a near miss on a fat finger still lands.
        frame.handles.forEachIndexed { i, rect ->
            if (rect.contains(x, y)) return LevelHit.Handle(frame.handleTargets[i].id, frame.handleTargets[i].kind)
        }
        frame.handles.forEachIndexed { i, rect ->
            val t = frame.handleTargets[i]
            if (x in rect.left - HANDLE_X_PAD..rect.right + HANDLE_X_PAD && abs(y - rect.centerY) <= DEFAULT_TOLERANCE_DP * 1.5f) {
                return LevelHit.Handle(t.id, t.kind)
            }
        }

        // 2. A line's own tag: the "x" box clears an SL/TP; the body clears an entry's levels
        //    or an entry tag opens the position. Grouped tags were handled above.
        frame.order.forEachIndexed { i, ft ->
            val t = ft.target
            if (t !is TagTarget.Line) return@forEachIndexed
            val rect = frame.tags[i].rect
            if (!rect.contains(x, y)) return@forEachIndexed
            when (t.kind) {
                OrderLineKind.SL, OrderLineKind.TP -> {
                    val boxLeft = rect.right - LevelGeometry.closeBox(density, labelScale)
                    if (x >= boxLeft - 2f) return LevelHit.CloseBox(t.id, t.kind)
                    return LevelHit.Line(t.id, t.kind)
                }
                else -> return LevelHit.EntryTag(t.id)
            }
        }

        // 3. The line body over the plot, or its tag reaching into the gutter.
        state.orderLines
            .filter { it.draggable && it.kind != OrderLineKind.ENTRY }
            .minByOrNull { abs(ChartMath.priceToY(it.drawPrice, geo.priceRange, plot) - y) }
            ?.let { line ->
                val y0 = ChartMath.priceToY(line.drawPrice, geo.priceRange, plot)
                if (abs(y - y0) <= tolerancePx(density)) {
                    if (x in plot.left..plot.right) return LevelHit.Line(line.id, line.kind)
                    if (x > plot.right) return LevelHit.Line(line.id, line.kind)
                }
            }

        return null
    }
}
