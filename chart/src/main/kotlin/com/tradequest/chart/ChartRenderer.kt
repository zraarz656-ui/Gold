package com.tradequest.chart

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tradequest.engine.Candle
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

const val PRICE_LABEL_MIN_GAP_DP = 20.0f
const val TIME_LABEL_MIN_GAP_DP = 64.0f

/** Order tags must stay readable on their fill in all four themes. */
private const val ORDER_TAG_MIN_CONTRAST = 4.5

/** The current-price tag is large and bold, per the visual spec. */
private const val CURRENT_PRICE_TAG_SP = 15f

/** Plot rectangle + resolved price range + visible bar window for one frame. */
fun geometryFor(state: ChartState, sizePx: Size, axisWidthPx: Float, bottomAxisPx: Float): ChartGeometry {
    val plot = ChartMath.plotRect(sizePx.width, sizePx.height, axisWidthPx, bottomAxisPx)
    val visible = ChartMath.visibleRange(state.viewport, plot.right, state.barCount, 2)
    return ChartGeometry(
        plot.left, plot.top, plot.right, plot.bottom,
        resolvePriceRange(state, visible), visible, sizePx.width,
    )
}

/** Auto-fit the visible bars, or honour the manual range when the user has pinned one. */
fun resolvePriceRange(state: ChartState, visible: IntRange): PriceRange {
    val vp = state.viewport
    if (vp.manualPriceScale) {
        return ChartMath.clampManualRange(
            vp.manualPriceMin ?: 0.0,
            vp.manualPriceMax ?: 1.0,
            ChartMath.fullExtent(state.bars),
        )
    }
    val e = ChartMath.dataExtent(state.bars, visible) ?: return PriceRange(0.0, 1.0)
    return ChartMath.autoFitRange(e.min, e.max)
}

fun DrawScope.drawChart(
    state: ChartState,
    geo: ChartGeometry,
    textMeasurer: TextMeasurer,
    paths: CandlePaths,
    crosshair: CrosshairInfo?,
    density: Float = 1f,
    measure: TagTextMeasure = TagTextMeasure.Approximate,
) {
    val theme = state.theme
    val priceTicks = ChartMath.niceTicks(geo.priceRange.min, geo.priceRange.max, 6)
    val plot = geo.plot

    // Everything that belongs to the plot is clipped to it, so no layer can bleed over the
    // price gutter, the time axis or its neighbours.
    clipRect(plot.left, plot.top, plot.right, plot.bottom) {
        for (p in priceTicks) {
            val y = ChartMath.priceToY(p, geo.priceRange, plot)
            drawLine(theme.grid, Offset(plot.left, y), Offset(plot.right, y), 1f)
        }
        drawLine(theme.grid, Offset(plot.right, plot.top), Offset(plot.right, plot.bottom), 1f)

        drawMarketBands(state, geo)
        drawCandles(state, geo, paths)
        drawIndicatorHook(state, geo)
        drawDrawingsHook(state, geo)
        drawOrderLinesHook(state, geo, textMeasurer)

        if (state.bars.lastOrNull() != null) drawCurrentPriceLine(state, geo)
        if (crosshair != null) drawCrosshair(state, geo, crosshair)
    }

    val last = state.bars.lastOrNull()
    val scale = state.labelScale
    val currentY = last?.let { ChartMath.priceToY(it.c, geo.priceRange, plot) }
    val crosshairY = crosshair?.let { it.y.coerceIn(plot.top, plot.bottom) }
    val lastUp = last != null && last.c >= last.o
    val currentColor = TagStyle.currentPriceFill(theme, lastUp)
    val minGapPx = density * PRICE_LABEL_MIN_GAP_DP
    val priceTagH = LevelGeometry.priceTagHeight(density, scale)

    // The far-right tags (current price, crosshair) anchor to the screen edge and are drawn
    // last. Grid labels stay clear of them and of each other.
    val pinnedY = listOfNotNull(currentY, crosshairY).map { it.coerceIn(plot.top, plot.bottom) }
    val blockGap = maxOf(minGapPx, priceTagH)

    // Nothing in the far-right gutter may cover the pinned price tags; the order tags are
    // drawn first, over the candles, but they never reach into that gutter.
    drawOrderOverlays(state, geo, textMeasurer, measure)

    // Grid price labels live in the right gutter; clipped there so they never cover the plot.
    clipRect(plot.right, 0f, size.width, size.height) {
        for (p in priceTicks) {
            val y = ChartMath.priceToY(p, geo.priceRange, plot)
            if (ChartMath.collidesWithAny(y, pinnedY, blockGap)) continue
            drawGridLabel(textMeasurer, theme, formatPrice(p), plot.right + 4f, y, scale)
        }
    }

    // The crosshair tag (finger) is drawn first; the current-price tag is drawn last so it
    // is never hidden, even when the crosshair sits on the same price.
    if (crosshairY != null) {
        val inverted = TagStyle.invertedTag(theme)
        val rect = TagGeom.priceTag(size.width, plot, crosshairY, density, scale)
        drawPriceLabel(
            textMeasurer, rect, inverted.fill,
            ChartMath.yToPrice(crosshairY, geo.priceRange, plot), scale, theme, textColor = inverted.text,
        )
    }
    if (last != null && currentY != null) {
        val rect = TagGeom.priceTag(size.width, plot, currentY, density, scale)
        drawPriceLabel(textMeasurer, rect, currentColor, last.c, scale, theme, textSizeSp = CURRENT_PRICE_TAG_SP)
    }

    // The magnified price bubble follows the finger, so it is drawn last and unclipped.
    state.dragPreview?.let { drawDragPreview(state, geo, textMeasurer, it) }
}

/**
 * The order tags (entry/SL/TP/pending) and their "+SL"/"+TP" handles. Tags are solid pills
 * that end at the plot's right edge and grow left over the candles, so a full price line
 * fits. Each is outlined and bold; draggable SL/TP carry an "x" close box. Tags are stacked
 * by [TagLayout] so no two overlap and none runs off the plot edges.
 */
internal fun DrawScope.orderTagWidth(
    measure: TagTextMeasure,
    text: String,
    density: Float,
    scale: Float,
    closeBox: Boolean,
): Float = LevelGeometry.orderTagWidth(measure, text, density, scale, closeBox)

/**
 * The order tags (entry/SL/TP/pending) and their "+SL"/"+TP" handles. Each tag shrinks to
 * its text and is right-aligned against the plot's right edge, drawn at
 * [LevelGeometry.ORDER_TAG_OPACITY] so the candles stay visible through it. Entries that
 * sit within 20dp of each other collapse into one grouped "N pos" tag (see [EntryGroups]).
 */
private fun DrawScope.drawOrderOverlays(
    state: ChartState,
    geo: ChartGeometry,
    textMeasurer: TextMeasurer,
    measure: TagTextMeasure,
): List<TagRect> {
    val plot = geo.plot
    val scale = state.labelScale
    val density = state.density
    val theme = state.theme
    val preview = state.dragPreview
    val tagH = LevelGeometry.tagHeight(density, scale)
    val closeW = LevelGeometry.closeBox(density, scale)

    // Entries that sit within 20dp of each other merge into one grouped tag, so the hit
    // test and the renderer compute the same clusters.
    val groups = entryGroupsFor(state, geo)
    val groupedIds = groups.flatMap { it.ids }.toSet()

    // Every tagged line (committed plus the drag in flight) with its desired Y.
    data class Tag(val line: ChartOrderLine, val y: Float, val closeBox: Boolean)
    val wanted = ArrayList<Tag>()
    for (line in state.orderLines) {
        if (preview != null && preview.line.id == line.id && preview.line.kind == line.kind) continue
        if (line.id in groupedIds) continue
        val yRaw = ChartMath.priceToY(line.drawPrice, geo.priceRange, plot)
        if (yRaw < plot.top - tagH || yRaw > plot.bottom + tagH) continue
        val y = yRaw.coerceIn(plot.top + tagH / 2f, plot.bottom - tagH / 2f)
        wanted.add(Tag(line, y, line.needsCloseBox()))
    }
    preview?.let {
        val yRaw = ChartMath.priceToY(it.price, geo.priceRange, plot)
        wanted.add(Tag(it.line, yRaw.coerceIn(plot.top + tagH / 2f, plot.bottom - tagH / 2f), it.line.needsCloseBox()))
    }

    // The free tags are laid out so no two overlap.
    val centers = wanted.map { it.y }
    val placed = TagLayout.place(centers, tagH, plot.top, plot.bottom)

    val textSize = (LevelGeometry.ORDER_TAG_SP * scale).sp
    for ((i, tag) in wanted.withIndex()) {
        val line = tag.line
        val text = line.tagText()
        val width = LevelGeometry.orderTagWidth(measure, text, density, scale, tag.closeBox)
        val rect = TagGeom.orderTag(plot, placed[i], width, density, scale)
        // 85% opacity keeps the candles readable through the tag; contrast is measured
        // against the tag's own colour, so "black or white text" still holds.
        val fill = TagStyle.ensureContrast(line.color(), ORDER_TAG_MIN_CONTRAST)
            .copy(alpha = LevelGeometry.ORDER_TAG_OPACITY)
        val textColor = TagStyle.textOn(line.color())
        drawTagBox(rect, fill, theme, density)
        drawText(
            textMeasurer, text,
            Offset(rect.left + LevelGeometry.TAG_PAD_DP * density * scale, rect.top + (rect.height - LevelGeometry.ORDER_TAG_SP * scale) / 2f),
            TextStyle(textColor, textSize, FontWeight.Bold),
        )
        if (tag.closeBox) {
            val closeLeft = rect.right - closeW
            drawRect(Color(0x66000000), Offset(closeLeft, rect.top), Size(closeW, rect.height))
            drawLine(Color.White, Offset(closeLeft + closeW * 0.3f, rect.centerY - 4f * scale), Offset(closeLeft + closeW * 0.7f, rect.centerY + 4f * scale), 1.5f)
            drawLine(Color.White, Offset(closeLeft + closeW * 0.7f, rect.centerY - 4f * scale), Offset(closeLeft + closeW * 0.3f, rect.centerY + 4f * scale), 1.5f)
        }
    }

    // The grouped "N pos  pnl" tags, one per cluster of close entries. Tapping one opens
    // the Positions tab (resolved by the hit test from the same rectangles).
    val groupRects = ArrayList<TagRect>()
    for (g in groups) {
        val y = g.centerY.coerceIn(plot.top + tagH / 2f, plot.bottom - tagH / 2f)
        val rect = TagGeom.entryGroupTag(plot, y, density, scale)
        groupRects.add(rect)
        val text = groupTagText(g)
        val fill = TagStyle.ensureContrast(if (g.positive) theme.up else theme.down, ORDER_TAG_MIN_CONTRAST)
            .copy(alpha = LevelGeometry.ORDER_TAG_OPACITY)
        drawTagBox(rect, fill, theme, density)
        val measured = textMeasurer.measure(text, TextStyle(TagStyle.textOn(fill), textSize, FontWeight.Bold))
        val tx = rect.left + (rect.width - measured.size.width) / 2f
        drawText(measured, topLeft = Offset(tx, rect.centerY - measured.size.height / 2f))
    }

    // "+SL"/"+TP" handles for a reference line that has no such level yet, side by side.
    val hw = LevelGeometry.handleWidth(density, scale)
    val hh = LevelGeometry.handleHeight(density, scale)
    for (line in state.orderLines) {
        val handles = LevelHitTest.handlesOf(line)
        if (handles.isEmpty()) continue
        val yRaw = ChartMath.priceToY(line.drawPrice, geo.priceRange, plot)
        if (yRaw < plot.top - hh || yRaw > plot.bottom + hh) continue
        val y = yRaw.coerceIn(plot.top + hh / 2f, plot.bottom - hh / 2f)
        val start = plot.right - handles.size * hw
        handles.forEachIndexed { i, kind ->
            val label = if (kind == OrderLineKind.SL) "+SL" else "+TP"
            val color = TagStyle.orderColor(kind)
            val left = start + i * hw
            val rect = TagRect(left, y - hh / 2f, left + hw, y + hh / 2f)
            drawTagBox(rect, Color(0xE61A1F27), theme, density)
            drawRect(color, Offset(left, y - hh / 2f), Size(3f, hh))
            drawText(
                textMeasurer, label,
                Offset(left + 7f * scale, y - 6f * scale),
                TextStyle(color, (11f * scale).sp, FontWeight.Bold),
            )
        }
    }
    return groupRects
}

/** The text on a merged entry tag: "3 pos  -5.10", matching the entry tag's P&L format. */
internal fun groupTagText(g: EntryGroup): String {
    val pnl = g.pnl ?: return "${g.count} pos"
    val sign = if (pnl >= 0) "+" else "-"
    return "${g.count} pos  $sign%.2f".format(java.util.Locale.US, kotlin.math.abs(pnl))
}

/**
 * The merged entry groups for a frame, shared by the renderer and the hit test so a tap on
 * a "N pos" tag lands exactly where the tag is drawn. Pure given the geometry.
 */
fun entryGroupsFor(state: ChartState, geo: ChartGeometry): List<EntryGroup> {
    val points = state.orderLines
        .filter { it.kind == OrderLineKind.ENTRY }
        .map { line ->
            EntryPoint(
                ids = listOf(line.id),
                y = ChartMath.priceToY(line.drawPrice, geo.priceRange, geo.plot),
                pnl = line.pnlText?.toDoubleOrNull(),
                positive = line.positive,
            )
        }
    return EntryGroups.cluster(points, EntryGroups.MIN_GAP_DP * state.density)
}

/** A filled pill with rounded corners and a theme-aware hairline outline. */
private fun DrawScope.drawTagBox(rect: TagRect, fill: Color, theme: ChartTheme, density: Float) {
    val radius = LevelGeometry.TAG_RADIUS_DP * density
    val size = Size(rect.width, rect.height)
    val topLeft = Offset(rect.left, rect.top)
    drawRoundRect(fill, topLeft = topLeft, size = size, cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
        TagStyle.outline(theme), topLeft = topLeft, size = size,
        cornerRadius = CornerRadius(radius, radius), style = Stroke(width = 1f),
    )
}

/** The magnified price bubble shown above the finger while a level is dragged. */
private fun DrawScope.drawDragPreview(
    state: ChartState,
    geo: ChartGeometry,
    textMeasurer: TextMeasurer,
    preview: DragPreview,
) {
    val text = formatPrice(preview.price)
    val w = 74f
    val h = 24f
    val fx = if (preview.x.isNaN()) 0f else preview.x
    val fy = if (preview.y.isNaN()) 0f else preview.y
    val x = (fx - w / 2f).coerceIn(0f, maxOf(size.width - w, 0f))
    val y = (fy - h - 16f).coerceIn(0f, maxOf(size.height - h, 0f))
    drawRect(Color(0xE61A1F27), Offset(x, y), Size(w, h))
    drawRect(state.theme.crosshair, Offset(x, y), Size(w, h), style = Stroke(1.5f))
    drawText(textMeasurer, text, Offset(x + 7f, y + 5f), TextStyle(Color.White, 13.sp))
}

/** Shade the weekend gaps (a candle gap wider than two bars means the market was shut). */
private fun DrawScope.drawMarketBands(state: ChartState, geo: ChartGeometry) {
    val theme = state.theme
    val vp = state.viewport
    val span = ChartController.spanMs(state.timeframe)
    val bars = state.bars
    if (geo.visible.isEmpty()) return
    for (i in maxOf(1, geo.visible.first)..geo.visible.last) {
        if (i - 1 < 0) continue
        val gap = bars[i].ts - bars[i - 1].ts
        if (gap > 2 * span) {
            val center = ChartMath.indexToX(i - 0.5f, vp)
            val w = maxOf(vp.candleWidthPx * 0.35f, 6f)
            val left = center - w / 2f
            if (left < geo.plot.right && left + w > geo.plot.left) {
                drawRect(theme.marketClosed, Offset(left, geo.plot.top), Size(w, geo.plot.height))
                drawLine(theme.grid, Offset(center, geo.plot.top), Offset(center, geo.plot.bottom), 1f)
            }
        }
    }
}

private fun DrawScope.drawCandles(state: ChartState, geo: ChartGeometry, paths: CandlePaths) {
    val theme = state.theme
    val vp = state.viewport
    paths.rewind()
    val halfW = maxOf(0.5f, vp.candleWidthPx / 2f - 0.5f)
    for (i in geo.visible.first..geo.visible.last) {
        val c = state.bars[i]
        val x = ChartMath.indexToX(i.toFloat(), vp)
        if (x < geo.plot.left - vp.candleWidthPx || x > geo.plot.right + vp.candleWidthPx) continue
        val up = c.c >= c.o
        val yH = ChartMath.priceToY(c.h, geo.priceRange, geo.plot)
        val yL = ChartMath.priceToY(c.l, geo.priceRange, geo.plot)
        val yO = ChartMath.priceToY(c.o, geo.priceRange, geo.plot)
        val yC = ChartMath.priceToY(c.c, geo.priceRange, geo.plot)
        val wick = if (up) paths.upWick else paths.downWick
        wick.moveTo(x, yH)
        wick.lineTo(x, yL)
        val bodyTop = min(yO, yC)
        val bodyH = maxOf(1f, maxOf(yO, yC) - bodyTop)
        val body = if (up) paths.upBody else paths.downBody
        body.addRect(Rect(x - halfW, bodyTop, x + halfW, bodyTop + bodyH))
    }
    drawPath(paths.upWick, theme.upWick, style = Stroke(1f))
    drawPath(paths.downWick, theme.downWick, style = Stroke(1f))
    drawPath(paths.upBody, theme.up)
    drawPath(paths.downBody, theme.down)
}

// Extension points for Phase 3 overlays (indicators, drawings, order lines).
private fun DrawScope.drawIndicatorHook(state: ChartState, geo: ChartGeometry) {}
private fun DrawScope.drawDrawingsHook(state: ChartState, geo: ChartGeometry) {}

/** Draw the order/SL/TP lines and entry/exit markers supplied by the trading layer. */
private fun DrawScope.drawOrderLinesHook(state: ChartState, geo: ChartGeometry, textMeasurer: TextMeasurer) {
    if (state.orderLines.isEmpty() && state.markers.isEmpty() && state.dragPreview == null) return
    val preview = state.dragPreview
    for (line in state.orderLines) {
        if (preview != null && preview.line.id == line.id && preview.line.kind == line.kind) continue
        val y = ChartMath.priceToY(line.drawPrice, geo.priceRange, geo.plot)
        if (y < geo.plot.top - 12f || y > geo.plot.bottom + 12f) continue
        val color = line.color()
        if (line.kind == OrderLineKind.ENTRY) {
            drawLine(color, Offset(geo.plot.left, y), Offset(geo.plot.right, y), 1.5f)
        } else {
            drawDashedLine(color, Offset(geo.plot.left, y), Offset(geo.plot.right, y))
        }
    }
    preview?.let {
        val y = ChartMath.priceToY(it.price, geo.priceRange, geo.plot)
        drawDashedLine(it.line.color(), Offset(geo.plot.left, y), Offset(geo.plot.right, y))
    }
    if (preview == null) {
        for (m in state.markers) {
            val x = ChartMath.indexToX(m.barIndex.toFloat(), state.viewport)
            if (x < geo.plot.left - 8f || x > geo.plot.right + 8f) continue
            val y = ChartMath.priceToY(m.price, geo.priceRange, geo.plot)
            drawMarker(x, y, m.entry, m.long)
        }
    }
}

/** Tag text for a line, rebuilt from the drawn price so it updates while dragging. */
internal fun ChartOrderLine.tagText(price: Double = drawPrice): String {
    val ctx = levelContext()
    return when (kind) {
        OrderLineKind.SL, OrderLineKind.TP -> LevelRules.levelTag(kind, ctx, price)
        OrderLineKind.PENDING -> label
        OrderLineKind.ENTRY -> pnlText?.let { "$label $it" } ?: label
    }
}

internal fun ChartOrderLine.needsCloseBox(): Boolean =
    draggable && (kind == OrderLineKind.SL || kind == OrderLineKind.TP)

private fun ChartOrderLine.color(): Color = TagStyle.orderColor(kind)

/** A small triangle at an entry/exit point. */
private fun DrawScope.drawMarker(x: Float, y: Float, entry: Boolean, long: Boolean) {
    val size = 7f
    val path = Path()
    if (entry) {
        if (long) {
            path.moveTo(x, y - size)
            path.lineTo(x - size, y - size * 2.2f)
            path.lineTo(x + size, y - size * 2.2f)
        } else {
            path.moveTo(x, y + size)
            path.lineTo(x - size, y + size * 2.2f)
            path.lineTo(x + size, y + size * 2.2f)
        }
    } else {
        if (long) {
            path.moveTo(x, y + size)
            path.lineTo(x - size, y + size * 2.2f)
            path.lineTo(x + size, y + size * 2.2f)
        } else {
            path.moveTo(x, y - size)
            path.lineTo(x - size, y - size * 2.2f)
            path.lineTo(x + size, y - size * 2.2f)
        }
    }
    path.close()
    drawPath(path, if (long) Color(0xFF26A69A) else Color(0xFFEF5350))
}

/** The current-price line: solid, in the up/down colour, with its label in the gutter clip. */
private fun DrawScope.drawCurrentPriceLine(state: ChartState, geo: ChartGeometry) {
    val last = state.bars.lastOrNull() ?: return
    val y = ChartMath.priceToY(last.c, geo.priceRange, geo.plot)
    val color = TagStyle.currentPriceFill(state.theme, last.c >= last.o)
    drawLine(color, Offset(geo.plot.left, y), Offset(geo.plot.right, y), 1.5f)
}

private fun DrawScope.drawCrosshair(
    state: ChartState,
    geo: ChartGeometry,
    crosshair: CrosshairInfo,
) {
    val theme = state.theme
    val cx = crosshair.x.coerceIn(geo.plot.left, geo.plot.right)
    val cy = crosshair.y.coerceIn(geo.plot.top, geo.plot.bottom)
    drawDashedLine(theme.crosshair, Offset(cx, geo.plot.top), Offset(cx, geo.plot.bottom))
    drawDashedLine(theme.crosshair, Offset(geo.plot.left, cy), Offset(geo.plot.right, cy))
}

private fun DrawScope.drawDashedLine(color: Color, a: Offset, b: Offset) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy)
    if (len <= 0f) return
    val ux = dx / len
    val uy = dy / len
    var t = 0f
    while (t < len) {
        val end = min(t + 6f, len)
        drawLine(color, Offset(a.x + ux * t, a.y + uy * t), Offset(a.x + ux * end, a.y + uy * end), 1f)
        t += 6f + 5f
    }
}

private fun DrawScope.drawAxisLabel(textMeasurer: TextMeasurer, theme: ChartTheme, text: String, x: Float, y: Float) {
    drawText(textMeasurer, text, Offset(x, y), TextStyle(theme.axisText, 10.sp))
}

private fun DrawScope.drawGridLabel(
    textMeasurer: TextMeasurer,
    theme: ChartTheme,
    text: String,
    x: Float,
    centerY: Float,
    scale: Float,
) {
    // Brighter than the old axis text, 12sp medium; still subtle relative to the tags.
    val color = TagStyle.brighten(theme.axisText, theme, 0.35f).copy(alpha = 0.85f)
    val size = (12f * scale).sp
    drawText(textMeasurer, text, Offset(x, centerY - 6f * scale), TextStyle(color, size, FontWeight.Medium))
}

/**
 * A price tag drawn in the far-right gutter. [color] is the fill; text is black or white
 * for the best contrast on it. Rounded, outlined, horizontally text-centered in the tag.
 */
private fun DrawScope.drawPriceLabel(
    textMeasurer: TextMeasurer,
    rect: TagRect,
    color: Color,
    price: Double,
    scale: Float,
    theme: ChartTheme,
    textColor: Color = TagStyle.textOn(color),
    textSizeSp: Float = 12f,
) {
    drawTagBox(rect, color, theme, density)
    val measured = textMeasurer.measure(
        formatPrice(price),
        TextStyle(textColor, (textSizeSp * scale).sp, FontWeight.Bold),
    )
    val tx = rect.left + (rect.width - measured.size.width) / 2f
    val ty = rect.centerY - measured.size.height / 2f
    drawText(measured, topLeft = Offset(tx, ty))
}

fun formatPrice(p: Double): String = String.format(java.util.Locale.US, "%.2f", p)

fun DrawScope.drawTimeAxis(state: ChartState, geo: ChartGeometry, textMeasurer: TextMeasurer, density: Float = 1f) {
    val theme = state.theme
    val vp = state.viewport
    val bars = state.bars
    if (bars.isEmpty()) return
    val axisTop = geo.plot.bottom
    clipRect(geo.plot.left, axisTop, geo.plot.right, size.height) {
        drawLine(theme.grid, Offset(geo.plot.left, axisTop), Offset(geo.plot.right, axisTop), 1f)
        val displayTs = bars.map { it.ts + state.displayOffsetMs }
        val minSpacingPx = density * TIME_LABEL_MIN_GAP_DP
        val ticks = ChartMath.timeAxisTicks(
            displayTs, geo.visible, state.timeframe, vp.scrollIndex, vp.candleWidthPx, minSpacingPx,
        )
        for (t in ticks) {
            val x = ChartMath.indexToX(t.index.toFloat(), vp)
            if (x < geo.plot.left - 6f || x > geo.plot.right) continue
            // Draw the ticks the label marks: a short vertical line at the tick, then the text.
            drawLine(theme.grid, Offset(x, axisTop), Offset(x, axisTop + 4f), 1f)
            drawAxisLabel(textMeasurer, theme, formatTimeLabel(t.displayTs, state.timeframe), x + 3f, axisTop + 4f)
        }
    }
}

/** Centre banner shown while the replayed market is shut. */
fun DrawScope.drawMarketClosedBanner(state: ChartState, geo: ChartGeometry, textMeasurer: TextMeasurer) {
    val text = "Market closed"
    val cx = (geo.plot.left + geo.plot.right) / 2f
    val cy = (geo.plot.top + geo.plot.bottom) / 2f
    drawRect(Color(0xCC1A1F27), Offset(cx - 92f, cy - 20f), Size(184f, 40f))
    drawText(textMeasurer, text, Offset(cx - 78f, cy - 10f), TextStyle(Color(0xFFFFB300), 16.sp))
}
