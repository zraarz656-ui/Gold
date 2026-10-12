package com.tradequest.chart

/**
 * Layout for every order tag on the chart: the SL, TP, pending and (single) entry tags,
 * which are always drawn and never dropped, plus the "+SL"/"+TP" handles for the single
 * selected position. Kept free of Compose so the layout rules — "no tag is ever dropped",
 * "no two tags overlap (min 20dp)", "handles sit left of the tags, never on a tag or the
 * price pill" — are proven in tests and shared verbatim by the renderer and the hit test.
 */
object OrderTags {

    /** Minimum vertical separation between two stacked tags. */
    const val STACK_MIN_GAP_DP = 20f

    /** Pixel gap between a moved tag and its leader line, so a nudge is visible. */
    const val LEADER_THRESHOLD_PX = 1f

    /**
     * A placed tag: its screen rectangle and the pixel Y of the line it belongs to. When
     * [leader] is true the tag was displaced from its line and the renderer draws a thin
     * leader from the line to the tag.
     */
    data class Slot(
        val rect: TagRect,
        val lineY: Float,
        val leader: Boolean,
    )

    /** The tag stacked for one order line, in input order. [width] is the full tag width. */
    data class TagLine(
        val centerY: Float,
        val lineY: Float,
        val width: Float,
        val density: Float,
        val scale: Float,
    )

    /** A "+SL"/"+TP" handle for the selected position. */
    data class HandleBox(
        val id: Long,
        val kind: OrderLineKind,
        val index: Int,
        val y: Float,
        val density: Float,
        val scale: Float,
    )

    data class Layout(
        val tags: List<Slot>,
        val handles: List<TagRect>,
    )

    /**
     * Place the tags and the handles.
     *
     * The tag column is right-aligned at the plot's right edge; every line gets a tag, and
     * the tags are stacked at least [gapPx] apart (never dropped). The handles are pushed to
     * the left of the whole tag column and stacked on their own so a handle never covers a
     * tag. Returns two lists indexed to match [tagLines] and [handles] respectively.
     */
    fun layout(
        plot: PlotRect,
        tagLines: List<TagLine>,
        handles: List<HandleBox>,
        tagHeight: Float,
        handleHeight: Float,
        gapPx: Float,
    ): Layout {
        val sep = maxOf(gapPx, tagHeight + 2f)
        val tagCenters = TagLayout.placeSpaced(tagLines.map { it.centerY }, tagHeight / 2f, plot.top, plot.bottom, sep)
        val tags = tagLines.indices.map { i ->
            val l = tagLines[i]
            val rect = TagGeom.orderTag(plot, tagCenters[i], l.width, l.density, l.scale)

            // The tag is drawn at the line's exact Y whenever it fits; a displacement draws
            // a leader. A tag whose line is off-screen still gets a tag, clamped inside the
            // plot, and the leader points off toward the line.
            val displaced = kotlin.math.abs(rect.centerY - l.lineY) > LEADER_THRESHOLD_PX
            Slot(rect, l.lineY, leader = displaced)
        }

        // Handles: left of the entire tag column, stacked on their own.
        val handleRight = tags.minOfOrNull { it.rect.left } ?: plot.right
        val handleCenters = TagLayout.placeSpaced(
            handles.map { it.y }, handleHeight / 2f, plot.top, plot.bottom, maxOf(handleHeight + 2f, gapPx),
        )
        val handleRects = handles.indices.map { i ->
            val h = handles[i]
            val w = LevelGeometry.handleWidth(h.density, h.scale)
            val y = handleCenters[i]
            TagRect(handleRight - w, y - handleHeight / 2f, handleRight, y + handleHeight / 2f)
        }
        return Layout(tags, handleRects)
    }
}
