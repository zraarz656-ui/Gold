package com.tradequest.chart

/** What a placed order tag resolves to when tapped. */
sealed interface TagTarget {
    data class Line(val id: Long, val kind: OrderLineKind) : TagTarget
    data object Group : TagTarget
}

/** One order tag to place, with the text and rectangle width it needs. */
data class FrameTag(
    val target: TagTarget,
    val text: String,
    val centerY: Float,
    val lineY: Float,
    val width: Float,
    val closeBox: Boolean,
    val fillKind: OrderLineKind?,
    val positive: Boolean,
)

/**
 * Everything the renderer and the hit test need to draw and grab one frame's order tags.
 * Computed once from the state and the geometry, so the tag you see is exactly the tag you
 * can tap ([tags] and [handles] are index-parallel to [order]).
 */
data class OrderTagFrame(
    val tags: List<OrderTags.Slot>,
    val order: List<FrameTag>,
    val handles: List<TagRect>,
    val handleTargets: List<TagTarget.Line>,
)

/** Width of an order tag: its measured text plus padding, floored so it never collapses. */
private fun tagWidthOf(measure: TagTextMeasure, text: String, density: Float, scale: Float, closeBox: Boolean): Float {
    val textPx = measure.width(text, LevelGeometry.ORDER_TAG_SP * scale)
    val padPx = 2f * LevelGeometry.TAG_PAD_DP * density * scale
    val boxPx = if (closeBox) LevelGeometry.closeBox(density, scale) else 0f
    val minPx = LevelGeometry.MIN_ORDER_TAG_WIDTH_DP * density * scale
    return maxOf(textPx + padPx + boxPx, minPx)
}

/**
 * Build the frame: the merged entry groups, the per-line tags (SL, TP, pending, single
 * entry), and the "+SL"/"+TP" handles for the selected position. Every non-entry line gets
 * a tag even when its line is off-screen; the tag is clamped inside the plot and a leader
 * is drawn. Only the selected position offers handles, and only for the levels it lacks.
 */
fun orderTagFrameFor(
    state: ChartState,
    geo: ChartGeometry,
    measure: TagTextMeasure,
    selectedEntryId: Long?,
): OrderTagFrame {
    val plot = geo.plot
    val density = state.density
    val scale = state.labelScale
    val preview = state.dragPreview

    val groups = entryGroupsFor(state, geo)
    val groupedIds = groups.flatMap { it.ids }.toSet()

    val frameTags = ArrayList<FrameTag>()

    // The single entry that merges nothing: an ordinary entry tag.
    for (line in state.orderLines) {
        if (line.id in groupedIds) continue
        if (preview != null && preview.line.id == line.id && preview.line.kind == line.kind) continue
        val lineY = ChartMath.priceToY(line.drawPrice, geo.priceRange, plot)
        when (line.kind) {
            OrderLineKind.ENTRY -> {
                val text = line.tagText()
                frameTags.add(
                    FrameTag(
                        TagTarget.Line(line.id, line.kind), text,
                        centerY = lineY, lineY = lineY,
                        width = maxOf(tagWidthOf(measure, text, density, scale, false), LevelGeometry.MIN_ORDER_TAG_WIDTH_DP * density * scale),
                        closeBox = false, fillKind = OrderLineKind.ENTRY, positive = line.positive,
                    ),
                )
            }
            OrderLineKind.SL, OrderLineKind.TP, OrderLineKind.PENDING -> {
                val text = line.tagText()
                val close = line.needsCloseBox()
                frameTags.add(
                    FrameTag(
                        TagTarget.Line(line.id, line.kind), text,
                        centerY = lineY, lineY = lineY,
                        width = tagWidthOf(measure, text, density, scale, close),
                        closeBox = close, fillKind = line.kind, positive = line.positive,
                    ),
                )
            }
        }
    }

    // The merged "N pos" tags.
    for (g in groups) {
        val text = groupTagText(g)
        val w = maxOf(measure.width(text, LevelGeometry.ORDER_TAG_SP * scale), LevelGeometry.MIN_ORDER_TAG_WIDTH_DP * density * scale) +
            2f * LevelGeometry.TAG_PAD_DP * density * scale
        frameTags.add(
            FrameTag(
                TagTarget.Group, text,
                centerY = g.centerY, lineY = g.centerY, width = w,
                closeBox = false, fillKind = null, positive = g.positive,
            ),
        )
    }

    // Handles for the selected position's missing levels only.
    val handleTargets = ArrayList<TagTarget.Line>()
    val handleBoxes = ArrayList<OrderTags.HandleBox>()
    if (selectedEntryId != null) {
        val sel = state.orderLines.firstOrNull { it.id == selectedEntryId && it.kind == OrderLineKind.ENTRY }
        if (sel != null) {
            val y = ChartMath.priceToY(sel.drawPrice, geo.priceRange, plot)
            sel.handles.forEachIndexed { i, kind ->
                handleTargets.add(TagTarget.Line(sel.id, kind))
                handleBoxes.add(OrderTags.HandleBox(sel.id, kind, i, y, density, scale))
            }
        }
    }

    val tagHeight = LevelGeometry.tagHeight(density, scale)
    val handleHeight = LevelGeometry.handleHeight(density, scale)
    val gapPx = OrderTags.STACK_MIN_GAP_DP * density

    val layout = OrderTags.layout(
        plot = plot,
        tagLines = frameTags.map {
            OrderTags.TagLine(it.centerY, it.lineY, it.width, density, scale)
        },
        handles = handleBoxes,
        tagHeight = tagHeight,
        handleHeight = handleHeight,
        gapPx = gapPx,
    )

    return OrderTagFrame(layout.tags, frameTags, layout.handles, handleTargets)
}
