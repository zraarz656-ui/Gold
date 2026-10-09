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

    /** Tag height; also the line's touch target half-height. */
    const val TAG_HEIGHT_DP = 24f

    /** The tappable "x" box at the tag's right edge. */
    const val CLOSE_BOX_DP = 24f

    /** The current-price / crosshair tag: fits "1234.56" at 15sp bold. */
    const val PRICE_TAG_WIDTH_DP = 104f
    const val PRICE_TAG_HEIGHT_DP = 28f

    /** The "+SL"/"+TP" handle, at the plot's right edge. */
    const val HANDLE_WIDTH_DP = 40f
    const val HANDLE_HEIGHT_DP = 22f

    /** Inner padding of a tag, from the fill to the text. */
    const val TAG_PAD_DP = 6f

    /** Corner radius of every tag pill. */
    const val TAG_RADIUS_DP = 6f

    /** The order-tag text size, matching the renderer. */
    const val ORDER_TAG_SP = 12f

    /** Order tags are drawn semi-transparent so the candles stay visible through them. */
    const val ORDER_TAG_OPACITY = 0.85f

    fun tagHeight(density: Float, scale: Float): Float = TAG_HEIGHT_DP * density * scale
    fun closeBox(density: Float, scale: Float): Float = CLOSE_BOX_DP * density * scale
    fun priceTagWidth(density: Float, scale: Float): Float = PRICE_TAG_WIDTH_DP * density * scale
    fun priceTagHeight(density: Float, scale: Float): Float = PRICE_TAG_HEIGHT_DP * density * scale
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

    /** The "+SL"/"+TP" handles a reference line (entry or pending trigger) offers. */
    fun handlesOf(line: ChartOrderLine): List<OrderLineKind> =
        if (line.kind == OrderLineKind.ENTRY || line.kind == OrderLineKind.PENDING) line.handles else emptyList()

    fun hit(
        overlay: ChartOverlayState,
        geo: ChartGeometry,
        x: Float,
        y: Float,
        density: Float,
        labelScale: Float = 1f,
        measure: TagTextMeasure = TagTextMeasure.Approximate,
        groups: List<EntryGroup> = emptyList(),
    ): LevelHit? {
        val tol = tolerancePx(density)
        val plot = geo.plot

        // 0. A grouped entry tag sits over everything; tapping it opens the Positions tab.
        groups.forEach { g ->
            val band = TagGeom.entryGroupTag(plot, g.centerY, density, labelScale)
            if (band.contains(x, y)) return LevelHit.EntryGroupTag
        }

        // 1. "+SL"/"+TP" handles, drawn at the plot's right edge inside the gutter.
        overlay.lines.forEach { line ->
            handlesOf(line).forEach { kind ->
                if (inHandleBox(line, kind, geo, x, y, density, labelScale)) return LevelHit.Handle(line.id, kind)
            }
        }

        // 2. The reference lines' own tags: a tap clears every level on the position/order.
        overlay.lines
            .filter { it.kind == OrderLineKind.ENTRY || it.kind == OrderLineKind.PENDING }
            .firstOrNull { inTagBody(it, geo, x, y, density, labelScale, measure) }
            ?.let { return LevelHit.EntryTag(it.id) }

        // 3. The "x" box on an SL/TP tag: tap to clear that level. (A pending trigger's own
        //    tag clears every level instead; see branch 2.)
        overlay.lines
            .filter { it.kind == OrderLineKind.SL || it.kind == OrderLineKind.TP }
            .firstOrNull { inCloseBox(it, geo, x, y, density, labelScale, measure) }
            ?.let { return LevelHit.CloseBox(it.id, it.kind) }

        overlay.lines
            .filter { it.draggable && it.kind != OrderLineKind.ENTRY }
            .minByOrNull { abs(ChartMath.priceToY(it.drawPrice, geo.priceRange, plot) - y) }
            ?.let { line ->
                val y0 = ChartMath.priceToY(line.drawPrice, geo.priceRange, plot)
                val near = abs(y - y0) <= tol
                if (!near) return@let
                // 4. The line body over the plot (the tag band is inside the plot too).
                if (x in plot.left..plot.right) return LevelHit.Line(line.id, line.kind)
                if (x > plot.right) return LevelHit.Line(line.id, line.kind)
            }

        return null
    }

    private fun tagWidth(line: ChartOrderLine, density: Float, scale: Float, measure: TagTextMeasure): Float =
        LevelGeometry.orderTagWidth(measure, line.tagText(), density, scale, line.needsCloseBox())

    private fun inTagBody(line: ChartOrderLine, geo: ChartGeometry, x: Float, y: Float, density: Float, scale: Float, measure: TagTextMeasure): Boolean {
        val y0 = ChartMath.priceToY(line.drawPrice, geo.priceRange, geo.plot)
        val right = geo.plot.right
        val left = right - tagWidth(line, density, scale, measure)
        return x in left..right && abs(y - y0) <= LevelGeometry.tagHeight(density, scale)
    }

    private fun inHandleBox(line: ChartOrderLine, kind: OrderLineKind, geo: ChartGeometry, x: Float, y: Float, density: Float, scale: Float): Boolean {
        val handles = handlesOf(line)
        val index = handles.indexOf(kind)
        if (index < 0) return false
        val hw = LevelGeometry.handleWidth(density, scale)
        val total = handles.size * hw
        val start = geo.plot.right - total
        // Keep the x band tight so adjacent handles do not steal each other's taps; the
        // height stays fat for fingers.
        val left = start + index * hw - HANDLE_X_PAD
        val right = start + (index + 1) * hw + HANDLE_X_PAD
        val y0 = ChartMath.priceToY(line.drawPrice, geo.priceRange, geo.plot)
        return x in left..right && abs(y - y0) <= DEFAULT_TOLERANCE_DP * 1.5f
    }

    private fun inCloseBox(line: ChartOrderLine, geo: ChartGeometry, x: Float, y: Float, density: Float, scale: Float, measure: TagTextMeasure): Boolean {
        val y0 = ChartMath.priceToY(line.drawPrice, geo.priceRange, geo.plot)
        val right = geo.plot.right
        val left = right - tagWidth(line, density, scale, measure)
        val boxLeft = right - LevelGeometry.closeBox(density, scale)
        return x in maxOf(left, boxLeft - 2f)..right && abs(y - y0) <= LevelGeometry.tagHeight(density, scale)
    }
}
