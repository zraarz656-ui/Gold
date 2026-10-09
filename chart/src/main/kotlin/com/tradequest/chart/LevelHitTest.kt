package com.tradequest.chart

import kotlin.math.abs

/**
 * Shared geometry for the level tags and handles. The renderer and the hit test both read
 * these, so what you see is exactly what you can grab.
 */
object LevelGeometry {
    /** Tag height; also the line's touch target half-height. */
    const val TAG_HEIGHT = 18f

    /** Tag width (excluding the close box's own overflow). */
    const val TAG_WIDTH = 132f

    /** The tappable "x" box at the tag's right edge. */
    const val CLOSE_BOX = 22f

    /** The "+SL"/"+TP" handle, in the right gutter. */
    const val HANDLE_WIDTH = 34f
    const val HANDLE_HEIGHT = 18f
}

/** What the finger landed on, if anything. */
sealed interface LevelHit {
    /** The "x" box on a tag: tap to clear the level. */
    data class CloseBox(val lineId: Long, val kind: OrderLineKind) : LevelHit

    /** The line body over the plot, or a tag body: drag to move the level. */
    data class Line(val lineId: Long, val kind: OrderLineKind) : LevelHit

    /** An empty entry's "+SL"/"+TP" handle: drag out a new level. */
    data class Handle(val lineId: Long, val kind: OrderLineKind) : LevelHit

    /** The entry's own gutter tag: a tap clears every level on that position. */
    data class EntryTag(val lineId: Long) : LevelHit
}

/**
 * Screen-space hit testing for the order levels. Kept free of Compose so the tolerance
 * maths is unit-testable; the caller turns a resolved [LevelHit] into a [LevelEdit].
 *
 * Priority, highest first: the "x" box, then a "+SL"/"+TP" handle (both small and
 * explicit), then the line/tag body.
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
    ): LevelHit? {
        val tol = tolerancePx(density)
        val plot = geo.plot

        // 1. "+SL"/"+TP" handles, drawn at the plot's right edge inside the gutter.
        overlay.lines.forEach { line ->
            handlesOf(line).forEach { kind ->
                if (inHandleBox(line, kind, geo, x, y)) return LevelHit.Handle(line.id, kind)
            }
        }

        // 2. The reference lines' own gutter tags: a tap clears every level on the position/order.
        overlay.lines
            .filter { it.kind == OrderLineKind.ENTRY || it.kind == OrderLineKind.PENDING }
            .firstOrNull { x > plot.right && inTagBody(it, geo, x, y) }
            ?.let { return LevelHit.EntryTag(it.id) }

        // 3. The "x" box on an SL/TP tag: tap to clear that level. (A pending trigger's own
        //    tag clears every level instead; see branch 2.)
        overlay.lines
            .filter { it.kind == OrderLineKind.SL || it.kind == OrderLineKind.TP }
            .firstOrNull { x > plot.right && inCloseBox(it, geo, x, y) }
            ?.let { return LevelHit.CloseBox(it.id, it.kind) }

        overlay.lines
            .filter { it.draggable && it.kind != OrderLineKind.ENTRY }
            .minByOrNull { abs(ChartMath.priceToY(it.drawPrice, geo.priceRange, plot) - y) }
            ?.let { line ->
                val y0 = ChartMath.priceToY(line.drawPrice, geo.priceRange, plot)
                val near = abs(y - y0) <= tol
                if (!near) return@let
                // 4. The line body over the plot, or anywhere else on the tag.
                if (x in plot.left..plot.right) return LevelHit.Line(line.id, line.kind)
                if (x > plot.right) return LevelHit.Line(line.id, line.kind)
            }

        return null
    }

    private fun inTagBody(line: ChartOrderLine, geo: ChartGeometry, x: Float, y: Float): Boolean {
        val y0 = ChartMath.priceToY(line.drawPrice, geo.priceRange, geo.plot)
        val left = geo.plot.right + 1f
        val right = left + LevelGeometry.TAG_WIDTH
        return x in left..right && abs(y - y0) <= LevelGeometry.TAG_HEIGHT
    }

    private fun inHandleBox(line: ChartOrderLine, kind: OrderLineKind, geo: ChartGeometry, x: Float, y: Float): Boolean {
        val handles = handlesOf(line)
        val index = handles.indexOf(kind)
        if (index < 0) return false
        val total = handles.size * LevelGeometry.HANDLE_WIDTH
        val start = geo.plot.right - total
        // Keep the x band tight so adjacent handles do not steal each other's taps; the
        // height stays fat for fingers.
        val left = start + index * LevelGeometry.HANDLE_WIDTH - HANDLE_X_PAD
        val right = start + (index + 1) * LevelGeometry.HANDLE_WIDTH + HANDLE_X_PAD
        val y0 = ChartMath.priceToY(line.drawPrice, geo.priceRange, geo.plot)
        return x in left..right && abs(y - y0) <= DEFAULT_TOLERANCE_DP * 1.5f
    }

    private fun inCloseBox(line: ChartOrderLine, geo: ChartGeometry, x: Float, y: Float): Boolean {
        val y0 = ChartMath.priceToY(line.drawPrice, geo.priceRange, geo.plot)
        val left = geo.plot.right + 1f + LevelGeometry.TAG_WIDTH - LevelGeometry.CLOSE_BOX
        val right = geo.plot.right + 1f + LevelGeometry.TAG_WIDTH
        return x in left..right && abs(y - y0) <= LevelGeometry.TAG_HEIGHT
    }
}
